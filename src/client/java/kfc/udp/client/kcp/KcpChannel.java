package kfc.udp.client.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.ChannelException;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.util.internal.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.TimeUnit;

/**
 * KCP 클라이언트 채널 — kcp-netty UkcpClientChannel + UkcpClientUdpChannel 통합.
 * 구조:
 *   KcpChannel          ← MC 파이프라인이 붙는 채널 (AbstractChannel)
 *     └─ KcpUdpChannel  ← NIO DatagramChannel 래퍼 (AbstractNioMessageChannel)
 * 스레드 모델:
 *   KcpUdpChannel은 doRegister()에서 KcpChannel과 동일한 eventLoop에 등록.
 *   → read() 포함 모든 I/O 콜백은 단일 eventLoop 스레드에서 직렬 실행.
 *   → KcpCore 접근은 항상 단일 스레드 — 별도 동기화 불필요.
 * 패킷 폭주 대응:
 *   한 번의 read()에서 UDP 수신은 최대 MAX_READ_PER_LOOP개로 제한.
 *   rcvQueue → pipeline 드레인은 제한 없이 전부 처리 (남기면 미전달 발생).
 *   → eventLoop가 중간에 송신(write/flush) 등 다른 태스크를 처리할 수 있어
 *     사람 많을 때도 keepalive 응답 지연 없음.
 */
public final class KcpChannel extends AbstractChannel implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger("kcp-channel");

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';

    private static final int UDP_BUF_SIZE  = 32 * 1024 * 1024;
    private static final int UDP_RECV_SIZE = 4096;

    /**
     * 한 번의 read() 이벤트에서 처리할 최대 UDP 패킷 수.
     * 청크 로딩 시 수백 개 세그먼트가 한꺼번에 도착 — 너무 작으면 NIO 사이클이
     * 반복되면서 처리량이 TCP 대비 크게 낮아짐.
     * 256개 × MTU(1450) ≈ 370KB/회 → 청크 50KB 기준 약 7개 청크를 한 사이클에 처리.
     */
    private static final int MAX_READ_PER_LOOP = 4096;

    /**
     * 한 번에 파이프라인으로 넘길 최대 바이트.
     * 경기 시작처럼 수백 KB가 한꺼번에 도착할 때 전량을 한 배치로 넘기면
     * 압축해제·디코딩·핸들러 실행이 한 사이클을 길게 점유해 그 뒤의 keepalive
     * 응답까지 밀린다(= 출발 직후 핑 급등). 256KB 단위로 끊어 넘기면 사이클
     * 사이에 송신·타이머가 끼어들 수 있어 지연 피크가 낮아진다.
     */
    private static final int DRAIN_MAX_BYTES = 256 * 1024;

    // KcpChannel이 단독 소유 — KcpUdpChannel.config()가 직접 참조해 무한재귀 방지
    private final DefaultChannelConfig config;

    final KcpUdpChannel udpChannel;
    private final KcpCore kcp;
    private volatile boolean kcpActive = true;

    private int     tsUpdate;
    private boolean flushPending;

    /**
     * 파이프라인이 데이터를 더 받을 준비가 됐는지.
     * MC(ClientConnection/FlowControlHandler)는 메인스레드가 밀리면 autoRead=false로
     * 패킷 투입을 제동한다. 예전 구현은 매 read()마다 autoRead=true를 강제해 이 제동을
     * 무력화했고(패킷이 소화 속도보다 빨리 밀려듦), 그 상태에서 네트워크 스레드와
     * 메인스레드가 같은 월드 상태를 동시에 만지는 모드가 있으면 경합으로 크래시했다.
     * 이제는 UDP 수신(=KCP 프로토콜 유지)과 파이프라인 전달(=백프레셔 대상)을 분리한다.
     */
    private boolean pipelineWants;

    boolean closeAnother = false;

    // ── 진단용 상태 추적 ──────────────────────────────────────────────────────
    /** 마지막으로 ACK(수신 데이터 포함)를 처리한 시각 (milliSeconds) */
    private int lastAckTime = 0;
    /** sndBuf 경고를 마지막으로 찍은 시각 — 과도한 warn 방지 */
    private int lastSndBufWarnTime = 0;
    private static final int SNDBUF_WARN_INTERVAL = 5_000; // 5초마다 1회

    public KcpChannel(int conv) {
        super(null);
        this.config = new DefaultChannelConfig(this);
        try {
            this.udpChannel = new KcpUdpChannel(this);
        } catch (IOException e) {
            throw new ChannelException("Failed to open DatagramChannel", e);
        }
        // 데이터그램마다 flush하지 않는다 — kcp.flush()가 한 사이클에 수백 개의
        // MTU 데이터그램을 낼 수 있는데(청크 로딩), 그때마다 pipeline flush를
        // 타면 오버헤드가 패킷 수에 비례. write만 하고 사이클 끝에 udpFlush() 1회.
        KcpOutput output = (data, c) ->
                udpChannel.unsafe().write(data, udpChannel.voidPromise());
        this.kcp = new KcpCore(conv, output, ByteBufAllocator.DEFAULT);
    }

    // ── AbstractChannel ───────────────────────────────────────────────────────

    @Override public ChannelMetadata metadata()     { return METADATA; }
    @Override public ChannelConfig   config()       { return config; }
    @Override protected KcpClientUnsafe newUnsafe() { return new KcpClientUnsafe(); }
    @Override public    KcpClientUnsafe unsafe()    { return (KcpClientUnsafe) super.unsafe(); }

    @Override public boolean isOpen()   { return udpChannel.isOpen(); }
    @Override public boolean isActive() { return udpChannel.isActive(); }

    @Override protected boolean isCompatible(EventLoop loop) { return loop instanceof NioEventLoop; }
    @Override protected SocketAddress localAddress0()  { return udpChannel.localAddress(); }
    @Override protected SocketAddress remoteAddress0() { return udpChannel.remoteAddress(); }

    @Override
    protected void doRegister() {
        eventLoop().register(udpChannel).addListener(f -> {
            if (!f.isSuccess()) forceClose();
        });
    }

    @Override protected void doBind(SocketAddress addr) throws Exception { udpChannel.doBind(addr); }
    @Override protected void doDisconnect()  throws Exception { udpChannel.doDisconnect(); }
    /**
     * 파이프라인이 read()를 요청했다 — 보류 중이던 수신분을 전달한다.
     * UDP 소켓 읽기 자체는 항상 유지된다(ACK·재전송 처리가 멈추면 세션이 죽으므로).
     */
    @Override
    protected void doBeginRead() throws Exception {
        pipelineWants = true;
        udpChannel.doBeginRead();
        if (kcpActive) udpChannel.unsafe0().drainToPipeline();
    }

    /**
     * 명시적 종료 신호.
     * KCP는 UDP 기반이라 TCP FIN 같은 종료 신호가 프로토콜에 없어, 서버 측은
     * 무통신 타임아웃으로만 종료를 감지한다. 그 사이 MC 서버에 고스트 플레이어가
     * 남으므로, 종료 직전 종료 마커를 보내 서버가 즉시 해당 세션을 닫도록 한다.
     * 마커는 데이터 포트가 아닌 control 포트(data port + 1)로 보낸다. 데이터
     * 포트로 보내면 서버측 recvmmsg(batch IO)가 raw fd에서 먼저 삼켜 가로챌 수
     * 없기 때문이다. control 포트로 분리해 데이터 경로의 recvmmsg를 보존한다.
     * 포맷: [0xFE][convID 4B LE]. NAT가 출처 포트를 바꿔도(Symmetric NAT) 서버가
     * convID로 세션을 식별하므로 안전하다. (출처 주소 매칭이 아님)
     */
    private static final byte CLOSE_MARKER = (byte) 0xFE;

    private void sendCloseMarker() {
        try {
            DatagramChannel ch = udpChannel.javaChannel();
            if (!ch.isOpen() || !ch.isConnected()) return;

            java.net.SocketAddress raddr = ch.getRemoteAddress();
            if (!(raddr instanceof InetSocketAddress data)) return;
            // control 포트 = 데이터 포트 + 1
            InetSocketAddress ctrl = new InetSocketAddress(data.getAddress(), data.getPort() + 1);

            int conv = kcp.getConv();
            ByteBuffer pkt = ByteBuffer.allocate(5);
            pkt.put(CLOSE_MARKER);
            pkt.order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1, conv);
            pkt.rewind();

            // connected 채널이라 send에 명시 주소를 못 쓰므로, 일회용 비연결 채널로 전송.
            try (DatagramChannel tmp = DatagramChannel.open()) {
                tmp.send(pkt, ctrl);
            }
        } catch (Throwable t) {
            // 마커 전송 실패는 치명적이지 않음 — 서버측 idleTimeout 안전망이 정리
            LOG.debug("[kcp] close marker send failed: {}", t.toString());
        }
    }

    @Override
    protected void doClose() {
        LOG.info("[kcp] doClose — conv=0x{}, sndBuf={}, sndQueue={}",
                Integer.toHexString(kcp.getConv()), kcp.waitSnd(), kcp.sndQueueSize());
        udpFlush(); // 잔여 ACK/세그먼트 방출
        // release()/소켓 close 전에 마커를 먼저 보낸다.
        sendCloseMarker();
        kcpActive = false;
        kcp.release();
        if (!closeAnother) {
            closeAnother = true;
            udpChannel.unsafe().close(udpChannel.unsafe().voidPromise());
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        boolean sent = false;
        for (;;) {
            Object msg = in.current();
            if (msg == null) { flushPending = false; break; }
            if (kcp.canSend(true)) {
                kcp.send((ByteBuf) msg);
                in.remove();
                sent = true;
            } else {
                flushPending = true;
                LOG.debug("[kcp][TX] canSend=false, sndBuf={}, sndQueue={}, rmtWnd={}",
                        kcp.waitSnd(), kcp.sndQueueSize(), kcp.rmtWnd());
                break;
            }
        }
        if (sent) {
            doUpdateKcp();
            udpFlush();
        }
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) return msg;
        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    @Override public InetSocketAddress localAddress()  { return (InetSocketAddress) super.localAddress(); }
    @Override public InetSocketAddress remoteAddress() { return (InetSocketAddress) super.remoteAddress(); }

    // ── 타이머 루프 ───────────────────────────────────────────────────────────

    @Override
    public void run() {
        if (!isActive()) return;
        int current = milliSeconds();
        Throwable ex = null;
        int nextTs = tsUpdate;

        if (itimediff(current, tsUpdate) >= 0) {
            try {
                kcp.update(current);
                nextTs = kcp.check(current);
            } catch (Throwable t) { ex = t; }

            if (kcp.getState() == -1 && ex == null) {
                int silenceSec = lastAckTime == 0 ? -1
                        : (current - lastAckTime) / 1000;
                LOG.warn("[kcp] run: State=-1, sndBuf={}, sndQueue={}, rmtWnd={}, ackSilence={}s",
                        kcp.waitSnd(), kcp.sndQueueSize(), kcp.rmtWnd(), silenceSec);
                ex = new KcpException("State=-1 after update()");
            } else {
                // sndBuf 적체 주기적 경고 (State=-1 전에 미리 감지)
                int sndBuf = kcp.waitSnd();
                if (sndBuf > 100 && itimediff(current, lastSndBufWarnTime) >= SNDBUF_WARN_INTERVAL) {
                    int silenceSec = lastAckTime == 0 ? -1
                            : (current - lastAckTime) / 1000;
                    LOG.warn("[kcp] sndBuf buildup: sndBuf={}, sndQueue={}, rmtWnd={}, ackSilence={}s",
                            sndBuf, kcp.sndQueueSize(), kcp.rmtWnd(), silenceSec);
                    lastSndBufWarnTime = current;
                }
            }
        }

        if (ex != null) {
            fireExceptionAndClose(ex);
            return;
        }

        if (flushPending && kcp.canSend(false))
            unsafe().forceFlush();
        udpFlush();

        tsUpdate = nextTs;
        scheduleUpdate(nextTs, current);
    }

    // ── KCP 헬퍼 — 모두 eventLoop 스레드에서만 호출됨 ────────────────────────

    void kcpInput(ByteBuf buf) {
        int readable = buf.readableBytes();
        // KCP 헤더(24바이트) 미만 패킷 — 서버사이드 kcp-go와 동일하게 무시
        if (readable < 24) {
            LOG.warn("[kcp][PARSE] skip short packet, len={} (< OVERHEAD 24)", readable);
            return;
        }

        lastAckTime = milliSeconds();

        int ret = kcp.input(buf);
        switch (ret) {
            case -1:
                // 루프 내 잔여 바이트가 OVERHEAD 미만 — 정상 종료 케이스이므로 skip
                LOG.warn("[kcp][PARSE] -1 (head underflow in loop), skip packet");
                return;
            case -2:
                // 데이터 길이 필드가 실제 바이트보다 큼 — 손상된 패킷, 연결은 유지
                LOG.warn("[kcp][PARSE] -2 (data underflow), skip packet");
                return;
            case -3:
                // 알 수 없는 cmd — 무시
                LOG.warn("[kcp][PARSE] -3 (unknown cmd), skip packet");
                return;
            case -4:
                // conv 불일치 — 재접속 직후 이전 conv 패킷이 OS 버퍼에 남아있는 경우
                // 예외로 연결을 끊지 않고 해당 패킷만 버림
                LOG.warn("[kcp][PARSE] -4 conv inconsistency (skip) — expected conv=0x{}",
                        Integer.toHexString(kcp.getConv()));
        }
    }

    void doUpdateKcp() {
        if (!kcpActive) return;
        try {
            kcp.update(milliSeconds());
        } catch (Throwable t) {
            LOG.warn("[kcp] doUpdateKcp exception: {}", t.toString());
            fireExceptionAndClose(t);
            return;
        }
        if (kcp.getState() == -1) {
            LOG.warn("[kcp] doUpdateKcp: State=-1, sndBuf={}, sndQueue={}, rmtWnd={}",
                    kcp.waitSnd(), kcp.sndQueueSize(), kcp.rmtWnd());
            fireExceptionAndClose(new KcpException("State=-1 after update()"));
        }
    }

    /** 사이클 단위 UDP 일괄 플러시 — output 콜백은 write만 하므로 반드시 짝으로 호출 */
    void udpFlush() {
        udpChannel.unsafe().flush();
    }

    boolean kcpIsActive() { return kcpActive; }

    /**
     * 지금 파이프라인으로 데이터를 밀어도 되는지.
     * autoRead=true거나, autoRead=false여도 파이프라인이 명시적으로 read()를
     * 요청했다면(FlowControlHandler의 1건씩 요청) 전달한다.
     */
    boolean pipelineReady() { return config.isAutoRead() || pipelineWants; }

    /** 한 배치를 전달한 뒤 호출 — 다음 전달은 autoRead이거나 새 read() 요청이 있을 때. */
    void pipelineDelivered() { pipelineWants = false; }
    int     kcpPeekSize() { return kcp.peekSize(); }

    void kcpReceive(ByteBuf buf) throws IOException {
        int ret = kcp.recv(buf);
        if (ret == -3) throw new IOException("Received data exceeds buf capacity");
    }

    void scheduleUpdate(int tsUpdate, int current) {
        this.tsUpdate = tsUpdate;
        eventLoop().schedule(this, tsUpdate - current, TimeUnit.MILLISECONDS);
    }

    boolean isFlushPending() { return flushPending; }

    private void forceClose() {
        LOG.warn("[kcp] forceClose called — channel registration failed");
        unsafe().closeForcibly();
        ((ChannelPromise) closeFuture()).trySuccess();
    }

    private void fireExceptionAndClose(Throwable t) {
        LOG.warn("[kcp] fireExceptionAndClose: {}", t.toString());
        pipeline().fireExceptionCaught(t);
        if (isActive()) unsafe().close(unsafe().voidPromise());
    }

    // ── KcpClientUnsafe ───────────────────────────────────────────────────────

    public final class KcpClientUnsafe extends AbstractUnsafe {

        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) return;
            try {
                boolean wasActive = isActive();
                udpChannel.unsafe().connect(remote, local, udpChannel.newPromise());

                int current = milliSeconds();
                int tsUp = kcp.check(current);
                tsUpdate = tsUp;
                scheduleUpdate(tsUp, current);

                boolean active = isActive();
                boolean promiseSet = promise.trySuccess();
                if (!wasActive && active) pipeline().fireChannelActive();
                if (!promiseSet) close(voidPromise());
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remote));
                closeIfClosed();
            }
        }

        @Override protected void flush0() {
            if (isFlushPending()) return;
            super.flush0();
        }

        void forceFlush() { super.flush0(); }
    }

    // ── KcpUdpChannel ─────────────────────────────────────────────────────────

    static final class KcpUdpChannel extends AbstractNioMessageChannel {

        private static final ChannelMetadata UDP_META = new ChannelMetadata(false);
        private final KcpChannel kcpChannel;
        boolean inputShutdown;

        KcpUdpChannel(KcpChannel kcpChannel) throws IOException {
            super(null, SelectorProvider.provider().openDatagramChannel(), SelectionKey.OP_READ);
            this.kcpChannel = kcpChannel;
            try {
                javaChannel().socket().setReceiveBufferSize(UDP_BUF_SIZE);
                javaChannel().socket().setSendBufferSize(UDP_BUF_SIZE);
            } catch (Exception ignored) {}
        }

        @Override protected DatagramChannel javaChannel() { return (DatagramChannel) super.javaChannel(); }
        @Override public ChannelMetadata metadata() { return UDP_META; }
        @Override public ChannelConfig config() { return kcpChannel.config; }
        @Override protected KcpUdpUnsafe newUnsafe() { return new KcpUdpUnsafe(); }

        /** KcpChannel.doBeginRead()가 보류분을 드레인할 때 사용 */
        KcpUdpUnsafe unsafe0() { return (KcpUdpUnsafe) super.unsafe(); }

        @Override
        public boolean isActive() {
            DatagramChannel ch = javaChannel();
            return ch.isOpen() && ch.socket().isBound();
        }

        @Override protected SocketAddress localAddress0()  { return javaChannel().socket().getLocalSocketAddress(); }
        @Override protected SocketAddress remoteAddress0() { return javaChannel().socket().getRemoteSocketAddress(); }

        @Override protected void doBind(SocketAddress addr) throws Exception {
            javaChannel().socket().bind(addr);
        }

        @Override
        protected boolean doConnect(SocketAddress remote, SocketAddress local) throws Exception {
            if (local != null) doBind(local);
            javaChannel().connect(remote);
            return true;
        }

        @Override protected void doDisconnect() throws Exception { doClose(); }
        @Override protected void doFinishConnect() { throw new Error(); }

        @Override
        protected void doClose() throws Exception {
            javaChannel().close();
            if (!kcpChannel.closeAnother) {
                kcpChannel.closeAnother = true;
                kcpChannel.unsafe().close(kcpChannel.unsafe().voidPromise());
            }
        }

        @Override protected void doBeginRead() throws Exception {
            if (!inputShutdown) super.doBeginRead();
        }

        @Override
        protected int doReadMessages(java.util.List<Object> buf) throws Exception {
            ByteBuf data = alloc().ioBuffer(UDP_RECV_SIZE);
            try {
                ByteBuffer nio = data.internalNioBuffer(data.writerIndex(), data.writableBytes());
                int pos  = nio.position();
                int read = javaChannel().read(nio);
                if (read <= 0) { data.release(); return read; }
                data.writerIndex(data.writerIndex() + nio.position() - pos);
                buf.add(data);
                return 1;
            } catch (Throwable t) {
                data.release();
                throw t;
            }
        }

        @Override
        protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
            ByteBuf data = (ByteBuf) msg;
            if (data.readableBytes() == 0) return true;
            ByteBuffer nio = data.internalNioBuffer(data.readerIndex(), data.readableBytes());
            return javaChannel().write(nio) > 0;
        }

        @Override
        protected Object filterOutboundMessage(Object msg) {
            if (msg instanceof ByteBuf buf) {
                return (buf.isDirect() && buf.nioBufferCount() == 1) ? buf : newDirectBuffer(buf);
            }
            throw new UnsupportedOperationException();
        }

        @Override protected boolean continueOnWriteError() { return true; }

        // ── 수신 루프 ─────────────────────────────────────────────────────────
        final class KcpUdpUnsafe extends AbstractNioUnsafe {

            private final java.util.ArrayList<Object> readBuf = new java.util.ArrayList<>();

            @Override
            public void read() {
                assert eventLoop().inEventLoop();
                final ChannelConfig cfg = config();

                final RecvByteBufAllocator.ExtendedHandle allocHandle =
                        (RecvByteBufAllocator.ExtendedHandle) recvBufAllocHandle();
                allocHandle.reset(cfg);

                Throwable ex   = null;
                boolean closed = false;

                // 1) UDP 수신 — 최대 MAX_READ_PER_LOOP개로 제한
                //    UDP 소켓 수신만 분산. rcvQueue 드레인은 별도로 전부 처리.
                // continueReading()은 ChannelMetadata/할당자 설정에 따라
                // maxMessagesPerRead(기본 1~16)로 조기 종료될 수 있어 사용하지
                // 않는다 — 버스트 시 셀렉터 사이클이 패킷 수만큼 반복되는 원인.
                // OS 버퍼가 빌 때까지(또는 상한까지) 직접 드레인한다.
                int readCount = 0;
                try {
                    for (;;) {
                        int n = doReadMessages(readBuf);
                        if (n == 0) break;
                        if (n < 0) { closed = true; break; }
                        allocHandle.incMessagesRead(n);
                        if (++readCount >= MAX_READ_PER_LOOP) break;
                    }
                } catch (Throwable t) { ex = t; }

                // 2) KCP input 전체 처리 후 일괄 드레인
                // 패킷별 즉시 드레인은 fireChannelRead를 수백 번 호출해서
                // MC 파이프라인(압축해제+디코딩)이 수백 번 실행 → eventLoop 점유 증가
                // → 다음 UDP read()가 밀려 처리량 감소
                // 전체 kcpInput 완료 후 rcvQueue를 한 번에 드레인하는 게 효율적
                if (ex == null) {
                    for (Object o : readBuf) {
                        ByteBuf pkt = (ByteBuf) o;
                        try {
                            kcpChannel.kcpInput(pkt);
                        } catch (Throwable t) {
                            LOG.warn("[kcp][PARSE] fatal kcpInput error, closing: {}", t.toString());
                            ex = t;
                            break;
                        }
                    }
                    // ACKNoDelay: 배치 전체의 ACK를 한 번에 coalesce 전송.
                    // 기존엔 패킷마다 flushAck를 호출해, 청크 로딩 시 수신 패킷
                    // 수(수백)만큼의 작은 ACK 데이터그램이 그대로 나가 return 경로
                    // 패킷 레이트와 sendto 시스콜을 폭증시켰다(서버도 그만큼 처리).
                    // MTU 하나에 ACK가 ~60개 들어가므로, 배치 후 1회 flush로
                    // ACK 데이터그램이 수백 개 → 수 개로 줄어든다.
                    if (ex == null) kcpChannel.kcp.flushAck();
                }

                // readBuf 해제
                for (Object o : readBuf) io.netty.util.ReferenceCountUtil.release(o);
                readBuf.clear();
                allocHandle.readComplete();
                pipeline().fireChannelReadComplete();

                if (ex != null) {
                    LOG.warn("[kcp] read() exception: {}", ex.toString());
                    closed = closeOnReadError(ex);
                    kcpChannel.pipeline().fireExceptionCaught(ex);
                } else {
                    // 3) update (재전송 타이머, sndQueue flush)
                    kcpChannel.doUpdateKcp();
                    // ACK·재전송 데이터그램 일괄 방출 (output은 write만 하므로)
                    kcpChannel.udpFlush();

                    // 4) rcvQueue → pipeline 드레인 (백프레셔 존중)
                    //    autoRead=false이고 read() 요청도 없으면 여기서 멈춘다.
                    //    데이터는 KCP rcvQueue에 남고, 수신 윈도우가 줄어들면서
                    //    서버 송신이 자연히 감속한다(KCP 흐름제어). 소비자가
                    //    준비되면 doBeginRead()가 이어서 드레인한다.
                    // kcp-go Read()처럼 세그먼트들을 CompositeByteBuf로 합쳐서
                    // fireChannelRead를 1번만 호출.
                    // 세그먼트마다 호출하면 splitter가 매번 누적 버퍼를 재할당하고
                    // zlib inflate 호출 횟수도 늘어남.
                    if (!drainToPipeline()) closed = true;
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) close(voidPromise());
                }
            }

            /**
             * KCP rcvQueue → MC 파이프라인 전달.
             * 한 번에 최대 DRAIN_MAX_BYTES까지만 묶어 넘긴다(거대 배치가 한 프레임을
             * 통째로 점유해 keepalive 응답이 밀리는 것을 방지). 남은 분량은 다음
             * read() 요청이나 다음 사이클에 이어서 전달된다.
             *
             * @return 정상 처리 여부(false면 예외로 채널을 닫아야 함)
             */
            boolean drainToPipeline() {
                if (!kcpChannel.kcpIsActive() || !kcpChannel.pipelineReady()) return true;
                final ChannelConfig cfg = config();
                try {
                    ByteBufAllocator ba = cfg.getAllocator();
                    ChannelPipeline pipe = kcpChannel.pipeline();
                    int peekSize;
                    int batched = 0;
                    io.netty.buffer.CompositeByteBuf composite = null;
                    while ((peekSize = kcpChannel.kcpPeekSize()) >= 0) {
                        if (peekSize == 0) {
                            LOG.warn("[kcp][DISPATCH] peekSize=0, skipping empty segment");
                            break;
                        }
                        ByteBuf recvBuf = ba.ioBuffer(peekSize);
                        kcpChannel.kcpReceive(recvBuf);
                        if (composite == null)
                            composite = io.netty.buffer.Unpooled.compositeBuffer();
                        composite.addComponent(true, recvBuf);
                        batched += peekSize;
                        if (batched >= DRAIN_MAX_BYTES) break;
                    }
                    if (composite != null && composite.isReadable()) {
                        kcpChannel.pipelineDelivered();
                        pipe.fireChannelRead(composite);
                        pipe.fireChannelReadComplete();
                    } else if (composite != null) {
                        composite.release();
                    }
                    return true;
                } catch (Throwable t) {
                    LOG.warn("[kcp] pipeline dispatch exception: {}", t.toString());
                    kcpChannel.pipeline().fireExceptionCaught(t);
                    return false;
                }
            }
        }
    }

    // ── 유틸리티 ──────────────────────────────────────────────────────────────

    public static int milliSeconds() {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private static int itimediff(int later, int earlier) { return later - earlier; }
}