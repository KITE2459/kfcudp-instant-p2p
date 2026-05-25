package kfc.udp.client.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * KCP를 Netty ChannelHandler로 래핑.
 * NioDatagramChannel (connect된 상태)의 파이프라인에서 동작:
 * [inbound]
 *   ByteBuf (UDP payload)
 *   → KcpClientHandler.channelRead: KCP input
 *   → fireChannelRead(ByteBuf): MC SplitterHandler로 전달
 * [outbound]
 *   ByteBuf (MC 패킷, prepender 통과 후)
 *   → KcpClientHandler.write: KCP send
 *   → ctx.writeAndFlush(ByteBuf): UDP로 전송
 * NioDatagramChannel을 connect()로 사용하면 특정 remote로 필터링되고
 * channelRead에 ByteBuf가 들어옴 (DatagramPacket 아님).
 */
public class KcpClientHandler extends ChannelDuplexHandler {

    private static final Logger LOG = LoggerFactory.getLogger("kcp-handler");

    private final long             conv;
    private final InetSocketAddress remote;
    private KcpSession             kcp;
    private ScheduledFuture<?>     updateTask;

    // KCP recv 버퍼 — MC 최대 패킷 대응 (4MB)
    private final byte[] recvBuf = new byte[4 * 1024 * 1024];

    public KcpClientHandler(long conv, InetSocketAddress remote) {
        this.conv   = conv;
        this.remote = remote;
    }

    // ── 생명주기 ──────────────────────────────────────────────────────────────

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        kcp = new KcpSession(conv, (data, len) -> {
            ByteBuf buf = ctx.alloc().buffer(len);
            buf.writeBytes(data, 0, len);
            // connect된 NioDatagramChannel은 ByteBuf를 직접 write
            ctx.writeAndFlush(buf);
        });

        // Go KCP 서버와 동일한 옵션 (applyKCPOptions)
        kcp.setNoDelay(1, 2, 1, false);
        kcp.setWindowSize(2048, 2048);
        kcp.setMtu(1450);
        kcp.setStreamMode(true);
        kcp.setAckNoDelay();

        // 2ms마다 KCP update (Go KCP interval=2 동일)
        updateTask = ctx.executor().scheduleAtFixedRate(() -> {
            try {
                kcp.update(System.currentTimeMillis());
                drainRecvQueue(ctx);
            } catch (Exception e) {
                LOG.warn("[kcp] update error: {}", e.getMessage());
            }
        }, 0, 2, TimeUnit.MILLISECONDS);

        LOG.info("[kcp] active conv=0x{} remote={}", Long.toHexString(conv), remote);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
        LOG.info("[kcp] inactive");
        super.channelInactive(ctx);
    }

    // ── inbound: UDP ByteBuf → KCP → MC ByteBuf ───────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf buf;

        if (msg instanceof DatagramPacket pkt) {
            // connect 안 된 경우 DatagramPacket으로 올 수도 있음
            buf = pkt.content();
        } else if (msg instanceof ByteBuf bb) {
            buf = bb;
        } else {
            super.channelRead(ctx, msg);
            return;
        }

        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            kcp.input(data, 0, data.length);
            drainRecvQueue(ctx);
        } finally {
            buf.release();
        }
    }

    private void drainRecvQueue(ChannelHandlerContext ctx) {
        boolean any = false;
        while (true) {
            int sz = kcp.peekSize();
            if (sz < 0) break;
            int n = kcp.recv(recvBuf, 0, recvBuf.length);
            if (n < 0) break;
            ByteBuf out = ctx.alloc().buffer(n);
            out.writeBytes(recvBuf, 0, n);
            ctx.fireChannelRead(out);
            any = true;
        }
        if (any) ctx.fireChannelReadComplete();
    }

    // ── outbound: MC ByteBuf → KCP → UDP ByteBuf ──────────────────────────────

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.write(ctx, msg, promise);
            return;
        }

        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            kcp.send(data, 0, data.length);
            // 즉시 flush — writeDelay=false와 동일
            kcp.update(System.currentTimeMillis());
            promise.setSuccess();
        } finally {
            buf.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("[kcp] exception: {}", cause.getMessage());
        ctx.close();
    }
}