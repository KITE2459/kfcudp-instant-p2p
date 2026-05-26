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
    private static final int UDP_RECV_SIZE = 2048;

    /**
     * 한 번의 read() 이벤트에서 처리할 최대 UDP 패킷 수.
     * 초과분은 다음 NIO select 루프에서 처리됨 — eventLoop 점유 시간 분산.
     * 16개 × MTU(1450) ≈ 23KB/회 → 2ms 인터벌 기준 충분한 처리량.
     */
    private static final int MAX_READ_PER_LOOP = 16;

    // KcpChannel이 단독 소유 — KcpUdpChannel.config()가 직접 참조해 무한재귀 방지
    private final DefaultChannelConfig config;

    final KcpUdpChannel udpChannel;
    private final KcpCore kcp;
    private volatile boolean kcpActive = true;

    private int     tsUpdate;
    private boolean flushPending;

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
        KcpOutput output = (data, c) -> {
            udpChannel.unsafe().write(data, udpChannel.voidPromise());
            udpChannel.unsafe().flush();
        };
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
    @Override protected void doBeginRead()   throws Exception { udpChannel.doBeginRead(); }

    @Override
    protected void doClose() {
        LOG.info("[kcp] doClose — conv=0x{}, sndBuf={}, sndQueue={}",
                Integer.toHexString(kcp.getConv()), kcp.waitSnd(), kcp.sndQueueSize());
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
        if (sent) doUpdateKcp();
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

    boolean kcpIsActive() { return kcpActive; }
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

                try {
                    // 1) UDP 수신 — 최대 MAX_READ_PER_LOOP개로 제한
                    //    UDP 소켓 수신만 분산. rcvQueue 드레인은 별도로 전부 처리.
                    int readCount = 0;
                    try {
                        do {
                            int n = doReadMessages(readBuf);
                            if (n == 0) break;
                            if (n < 0) { closed = true; break; }
                            allocHandle.incMessagesRead(n);
                            readCount++;
                        } while (readCount < MAX_READ_PER_LOOP
                                && allocHandle.continueReading(
                                io.netty.util.UncheckedBooleanSupplier.TRUE_SUPPLIER));
                    } catch (Throwable t) { ex = t; }

                    // 2) KCP input — 패킷별 격리: 한 패킷 파싱 오류가 연결 전체를 끊지 않음
                    if (ex == null) {
                        for (Object o : readBuf) {
                            ByteBuf pkt = (ByteBuf) o;
                            try {
                                kcpChannel.kcpInput(pkt);
                            } catch (Throwable t) {
                                // conv 불일치 같은 치명적 오류만 여기 도달 — 연결 종료
                                LOG.warn("[kcp][PARSE] fatal kcpInput error, closing: {}", t.toString());
                                ex = t;
                                break;
                            }
                        }
                    }

                    // readBuf 해제
                    for (Object o : readBuf) io.netty.util.ReferenceCountUtil.release(o);
                    readBuf.clear();
                    allocHandle.readComplete();
                    // OP_READ 재등록을 위해 autoRead=true 강제 후 readComplete 전파.
                    // FlowControlHandler가 autoRead=false로 설정했을 때
                    // HeadContext.readIfIsAutoRead()가 OP_READ를 재등록하지 않아
                    // 이후 UDP 패킷을 수신하지 못하는 문제 방지.
                    kcpChannel.config().setAutoRead(true);
                    pipeline().fireChannelReadComplete();

                    if (ex != null) {
                        LOG.warn("[kcp] read() exception: {}", ex.toString());
                        closed = closeOnReadError(ex);
                        kcpChannel.pipeline().fireExceptionCaught(ex);
                    } else {
                        // 3) ACK 즉시 flush
                        kcpChannel.doUpdateKcp();

                        // 4) rcvQueue → pipeline 전체 드레인
                        //    FlowControlHandler가 autoRead=false를 설정하면 내부 queue에서
                        //    꺼내기를 멈춤. KCP는 자체 흐름 제어를 하므로 MC의 autoRead 조작이
                        //    불필요 — 항상 true로 강제해서 FlowControlHandler가 막지 않도록 함.
                        if (kcpChannel.kcpIsActive()) {
                            try {
                                ByteBufAllocator ba = cfg.getAllocator();
                                ChannelPipeline pipe = kcpChannel.pipeline();
                                int peekSize;
                                boolean recv = false;
                                while ((peekSize = kcpChannel.kcpPeekSize()) >= 0) {
                                    recv = true;
                                    if (peekSize == 0) {
                                        LOG.warn("[kcp][DISPATCH] peekSize=0, skipping empty segment");
                                        // 빈 세그먼트 — recv 호출 없이 skip (무한루프 방지)
                                        break;
                                    }
                                    ByteBuf recvBuf = ba.ioBuffer(peekSize);
                                    kcpChannel.kcpReceive(recvBuf);
                                    pipe.fireChannelRead(recvBuf);
                                }
                                if (recv) pipe.fireChannelReadComplete();
                            } catch (Throwable t) {
                                LOG.warn("[kcp] pipeline dispatch exception: {}", t.toString());
                                closed = true;
                                kcpChannel.pipeline().fireExceptionCaught(t);
                            }
                        }
                    }

                    if (closed) {
                        inputShutdown = true;
                        if (isOpen()) close(voidPromise());
                    }
                } finally {
                    if (!cfg.isAutoRead()) removeReadOp();
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