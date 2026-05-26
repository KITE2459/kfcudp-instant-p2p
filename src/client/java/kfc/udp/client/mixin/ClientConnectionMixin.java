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
                .channelFactory(() -> new KcpChannel(conv))
                .handler(new ChannelInitializer<KcpChannel>() {
                    @Override
                    protected void initChannel(KcpChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        ClientConnection.addHandlers(p, NetworkSide.CLIENTBOUND, false, null);
                        connection.addFlowControlHandler(p);
                        p.addLast("kcp-exception", KcpExceptionHandler.INSTANCE);
                    }
                });

        ChannelFuture future = bootstrap.connect(address).syncUninterruptibly();
        cir.setReturnValue(future);
    }
}