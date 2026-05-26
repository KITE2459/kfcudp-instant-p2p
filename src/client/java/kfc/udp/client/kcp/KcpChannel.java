package kfc.udp.client.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.ChannelException;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.util.internal.StringUtil;

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

            if (kcp.getState() == -1 && ex == null)
                ex = new KcpException("State=-1 after update()");
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

    void kcpInput(ByteBuf buf) throws IOException {
        int ret = kcp.input(buf);
        switch (ret) {
            case -1: throw new IOException("No enough bytes of head");
            case -2: throw new IOException("No enough bytes of data");
            case -3: throw new IOException("Mismatch cmd");
            case -4: throw new IOException("Conv inconsistency");
        }
    }

    void doUpdateKcp() {
        if (!kcpActive) return;
        try {
            kcp.update(milliSeconds());
        } catch (Throwable t) {
            fireExceptionAndClose(t);
            return;
        }
        if (kcp.getState() == -1)
            fireExceptionAndClose(new KcpException("State=-1 after update()"));
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
        unsafe().closeForcibly();
        ((ChannelPromise) closeFuture()).trySuccess();
    }

    private void fireExceptionAndClose(Throwable t) {
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

                    // 2) KCP input
                    if (ex == null) {
                        try {
                            for (Object o : readBuf) kcpChannel.kcpInput((ByteBuf) o);
                        } catch (Throwable t) { ex = t; }
                    }

                    // readBuf 해제
                    for (Object o : readBuf) io.netty.util.ReferenceCountUtil.release(o);
                    readBuf.clear();
                    allocHandle.readComplete();

                    if (ex != null) {
                        closed = closeOnReadError(ex);
                        kcpChannel.pipeline().fireExceptionCaught(ex);
                    } else {
                        // 3) ACK 즉시 flush
                        kcpChannel.doUpdateKcp();

                        // 4) rcvQueue → pipeline 전체 드레인
                        //    제한 없이 전부 꺼내야 함.
                        //    rcvQueue에 남기면 새 UDP 패킷이 올 때까지 영원히 대기하게 됨.
                        if (kcpChannel.kcpIsActive()) {
                            try {
                                ByteBufAllocator ba = cfg.getAllocator();
                                ChannelPipeline pipe = kcpChannel.pipeline();
                                int peekSize;
                                boolean recv = false;
                                while ((peekSize = kcpChannel.kcpPeekSize()) >= 0) {
                                    recv = true;
                                    ByteBuf recvBuf = ba.ioBuffer(peekSize);
                                    kcpChannel.kcpReceive(recvBuf);
                                    pipe.fireChannelRead(recvBuf);
                                }
                                if (recv) pipe.fireChannelReadComplete();
                            } catch (Throwable t) {
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