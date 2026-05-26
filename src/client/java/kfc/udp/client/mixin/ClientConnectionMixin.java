package kfc.udp.client.mixin;

import kfc.udp.client.kcp.KcpAddressRegistry;
import kfc.udp.client.kcp.KcpChannel;
import kfc.udp.client.kcp.KcpExceptionHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClientConnection.connect() 인터셉트.
 * kcp-netty 라이브러리 완전 제거 — KcpChannel(커스텀)으로 대체.
 * 변경사항:
 *   - UkcpClientChannel → KcpChannel (직접 구현)
 *   - UkcpChannelOption 전부 제거 (KcpCore에 하드코딩)
 *   - ChannelOptionHelper.nodelay() 제거 (KcpCore에 하드코딩)
 *   - SO_RCVBUF/SO_SNDBUF 제거 (KcpUdpChannel 생성자에 하드코딩)
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("kcp-mixin");

    @Unique
    private static final NioEventLoopGroup KCP_GROUP = new NioEventLoopGroup(2, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override public Thread newThread(@NotNull Runnable r) {
            Thread t = new Thread(r, "kcp-io-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** conv 시퀀스 — 충돌 최소화를 위해 랜덤 시작 */
    @Unique
    private static final AtomicLong CONV_SEQ = new AtomicLong(
            (long)(Math.random() * 0xFFFFFFFFL) & 0xFFFFFFFFL
    );

    @Inject(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void kfcudp$interceptConnect(
            InetSocketAddress address,
            boolean useEpoll,
            ClientConnection connection,
            CallbackInfoReturnable<ChannelFuture> cir) {

        if (!KcpAddressRegistry.consumeIfKcp()) return;

        final int conv = (int)(CONV_SEQ.getAndIncrement() & 0xFFFFFFFFL);
        LOG.info("[kcp] Connecting via KCP conv=0x{}", Integer.toHexString(conv));

        Bootstrap bootstrap = new Bootstrap();
        bootstrap
                .group(KCP_GROUP)
                .channelFactory(() -> new KcpChannel(conv))  // conv 하드코딩 생성
                .handler(new ChannelInitializer<KcpChannel>() {
                    @Override
                    protected void initChannel(KcpChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // MC 기본 핸들러 (LengthFieldDecoder 등)
                        ClientConnection.addHandlers(p, NetworkSide.CLIENTBOUND, false, null);
                        // flow control
                        connection.addFlowControlHandler(p);
                        // KCP state=-1 예외 처리
                        p.addLast("kcp-exception", KcpExceptionHandler.INSTANCE);
                    }
                });

        ChannelFuture future = bootstrap.connect(address).syncUninterruptibly();
        cir.setReturnValue(future);
    }
}
