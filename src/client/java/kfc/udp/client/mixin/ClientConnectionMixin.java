package kfc.udp.client.mixin;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import kfc.udp.client.kcp.KcpAddressRegistry;
import kfc.udp.client.kcp.KcpClientHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.util.profiler.MultiValueDebugSampleLogImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/util/profiler/MultiValueDebugSampleLogImpl;)Lnet/minecraft/network/ClientConnection;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void kfcudp$interceptConnect(
            InetSocketAddress address,
            boolean useEpoll,
            @Nullable MultiValueDebugSampleLogImpl packetSizeLog,
            CallbackInfoReturnable<ClientConnection> cir) {

        if (!KcpAddressRegistry.hasPending()) return;

        String host = KcpAddressRegistry.pollHost();
        int    port = KcpAddressRegistry.pollPort();
        if (host == null || port < 0) return;

        LOG.info("[kcp-mixin] intercepted → KCP {}:{}", host, port);

        InetSocketAddress kcpAddr = new InetSocketAddress(host, port);
        long conv = CONV_SEQ.getAndIncrement() & 0xFFFFFFFFL;

        ClientConnection connection = new ClientConnection(NetworkSide.CLIENTBOUND);
        KcpClientHandler kcpHandler = new KcpClientHandler(conv, kcpAddr);

        Bootstrap bootstrap = new Bootstrap()
                .group(KCP_GROUP)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("kcp", kcpHandler);
                        ClientConnection.addHandlers(p, NetworkSide.CLIENTBOUND, false, null);
                        connection.addFlowControlHandler(p);
                    }
                });

        bootstrap.connect(kcpAddr).syncUninterruptibly();

        cir.setReturnValue(connection);
    }
}