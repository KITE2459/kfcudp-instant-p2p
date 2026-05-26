package kfc.udp.client.mixin;

import kfc.udp.client.WebRtcBridge;
import kfc.udp.client.kcp.KcpAddressRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.CookieStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(
            method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void kfcudp$onConnect(
            Screen screen,
            MinecraftClient client,
            ServerAddress address,
            ServerInfo serverInfo,
            boolean quickPlay,
            @Nullable CookieStorage cookieStorage,
            CallbackInfo ci) {

        String originalAddress = serverInfo != null ? serverInfo.address : address.getAddress();

        // webrtc. 처리
        String roomId = WebRtcBridge.parseRoomId(originalAddress);
        if (roomId != null) {
            ci.cancel();
            WebRtcBridge.LOG.info("[WebRTC] Connecting via WebRTC, roomId={}", roomId);
            try {
                int localPort = WebRtcBridge.start(roomId);
                ServerAddress localAddr = new ServerAddress("127.0.0.1", localPort);
                ServerInfo localInfo = new ServerInfo(
                        serverInfo != null ? serverInfo.name : roomId,
                        "127.0.0.1:" + localPort,
                        ServerInfo.ServerType.OTHER
                );
                client.execute(() ->
                        ConnectScreen.connect(screen, client, localAddr, localInfo, false, null)
                );
            } catch (Exception e) {
                WebRtcBridge.LOG.warn("[WebRTC] Failed to start: {}", e.getMessage());
            }
            return;
        }

        // kcp. 처리
        String kcpAddr = WebRtcBridge.parseKcpAddress(originalAddress);
        if (kcpAddr != null) {
            ci.cancel();

            ServerAddress parsed = ServerAddress.parse(kcpAddr);
            String host = parsed.getAddress();
            int    port = parsed.getPort();

            WebRtcBridge.LOG.info("[KCP] Connecting native KCP, server={}:{}", host, port);

            // register + connect를 client.execute() 안에서 연속 실행
            // → Server Pinger가 끼어들 타이밍 없음
            ServerAddress realAddr = ServerAddress.parse(kcpAddr);
            ServerInfo realInfo = new ServerInfo(
                    serverInfo != null ? serverInfo.name : kcpAddr,
                    kcpAddr,
                    ServerInfo.ServerType.OTHER
            );
            client.execute(() -> {
                KcpAddressRegistry.register();
                ConnectScreen.connect(screen, client, realAddr, realInfo, false, null);
            });
        }
    }
}