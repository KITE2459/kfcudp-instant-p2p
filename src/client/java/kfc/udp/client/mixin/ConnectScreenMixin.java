package kfc.udp.client.mixin;

import kfc.udp.client.WebRtcBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
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
    private static void kfcudp$interceptConnect(
            Screen screen,
            MinecraftClient client,
            ServerAddress address,
            ServerInfo serverInfo,
            boolean quickPlay,
            @Nullable CookieStorage cookieStorage,
            CallbackInfo ci) {

        String originalAddress = serverInfo != null ? serverInfo.address : "";

        // webrtc. 처리
        String roomId = WebRtcBridge.parseRoomId(originalAddress);
        if (roomId == null) {
            roomId = WebRtcBridge.getAndClearPendingRoomId(serverInfo != null ? serverInfo.address : "");
        }
        if (roomId != null) {
            ci.cancel();
            final String finalRoomId = roomId;
            WebRtcBridge.LOG.info("[WebRTC] Connecting via WebRTC, roomId={}", finalRoomId);
            Thread thread = new Thread(() -> {
                try {
                    int localPort = WebRtcBridge.start(finalRoomId);
                    ServerAddress localAddr = ServerAddress.parse("127.0.0.1:" + localPort);
                    ServerInfo localInfo = new ServerInfo(
                            serverInfo != null ? serverInfo.name : finalRoomId,
                            "127.0.0.1:" + localPort,
                            ServerInfo.ServerType.OTHER
                    );
                    client.execute(() ->
                            ConnectScreen.connect(screen, client, localAddr, localInfo, false, null)
                    );
                } catch (Exception e) {
                    WebRtcBridge.LOG.error("[WebRTC] Failed to start bridge: {}", e.getMessage(), e);
                }
            }, "webrtc-connect");
            thread.setDaemon(true);
            thread.start();
            return;
        }

        // quic. 처리
        String quicAddr = WebRtcBridge.parseQuicAddress(originalAddress);
        if (quicAddr != null) {
            ci.cancel();
            final String finalQuicAddr = quicAddr;
            WebRtcBridge.LOG.info("[QUIC] Connecting via QUIC, server={}", finalQuicAddr);
            Thread thread = new Thread(() -> {
                try {
                    int localPort = WebRtcBridge.startQuic(finalQuicAddr);
                    ServerAddress localAddr = ServerAddress.parse("127.0.0.1:" + localPort);
                    ServerInfo localInfo = new ServerInfo(
                            serverInfo != null ? serverInfo.name : finalQuicAddr,
                            "127.0.0.1:" + localPort,
                            ServerInfo.ServerType.OTHER
                    );
                    client.execute(() ->
                            ConnectScreen.connect(screen, client, localAddr, localInfo, false, null)
                    );
                } catch (Exception e) {
                    WebRtcBridge.LOG.error("[QUIC] Failed to start bridge: {}", e.getMessage(), e);
                }
            }, "quic-connect");
            thread.setDaemon(true);
            thread.start();
        }
    }
}