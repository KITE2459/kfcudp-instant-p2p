package kfc.udp.client;

import kfc.udp.client.gui.CustomRoomScreen;
import kfc.udp.client.webrtc.P2PBanManager;
import kfc.udp.client.webrtc.WebRtcBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import kfc.udp.client.gui.JoinRoomScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class KfcudpClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("kfcudp");
    private static final Random RANDOM = new Random();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int INVITE_TIMEOUT_TICKS = 20 * 120;

    private static String activeInviteCode = null;
    private static int inviteTicksRemaining = 0;
    private static int activeMaxPlayers = 8;

    @Override
    public void onInitializeClient() {
        LOG.info("[kfcudp] WebRTC bridge mod initialized");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // 멀티플레이 화면 - 초대 수락하기 버튼
            if (screen instanceof MultiplayerScreen) {
                int btnW = 100;
                int btnH = 20;
                int btnX = scaledWidth - btnW - 10;
                int btnY = 10;
                Screens.getButtons(screen).add(
                        ButtonWidget.builder(
                                Text.translatable("kfcudp.join_room.title"),
                                button -> client.setScreen(new JoinRoomScreen(screen))
                        ).dimensions(btnX, btnY, btnW, btnH).build()
                );
                return;
            }

            if (!(screen instanceof GameMenuScreen gameMenu)) return;
            if (!gameMenu.shouldShowMenu()) return;
            if (!client.isInSingleplayer()) return;

            int btnW = 100;
            int btnH = 20;
            int btnX = scaledWidth - btnW - 10;
            int btnY = 10;

            Screens.getButtons(screen).add(
                    ButtonWidget.builder(
                            Text.translatable("kfcudp.custom_room.title"),
                            button -> client.setScreen(new CustomRoomScreen(screen))
                    ).dimensions(btnX, btnY, btnW, btnH).build()
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (activeInviteCode == null) return;
            if (client.world == null || client.player == null) {
                cancelInvite();
                return;
            }

            IntegratedServer server = client.getServer();
            boolean hasGuests = server != null && server.getCurrentPlayerCount() > 1;

            if (hasGuests) {
                inviteTicksRemaining = INVITE_TIMEOUT_TICKS;
            } else {
                inviteTicksRemaining--;
                if (inviteTicksRemaining <= 0) {
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.translatable("kfcudp.msg.invite_expired"),
                                false
                        );
                    }
                    cancelInvite();
                }
            }
        });

        // 플레이어 접속 시 최대 인원 초과 체크
        // ban 명령어 등록 (리슨 서버에서도 동작)
        P2PBanManager.registerCommands();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null) return;
            // ban 체크
            P2PBanManager.checkOnJoin(handler.player);
            int current = server.getCurrentPlayerCount();
            if (current > activeMaxPlayers) {
                handler.player.networkHandler.disconnect(
                        Text.translatable("kfcudp.msg.room_full", activeMaxPlayers)
                );
            }
        });

        // 서버 연결 해제 시 KCP/QUIC 프로세스 종료.
        // 즉시 kill하면 0x1B Disconnect 패킷이 유실되어 서버에 고스트 잔류.
        // 1초 대기 후 종료 — 패킷 전송 완료 후 kill.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Thread t = new Thread(() -> {
                // DISCONNECT 시점에 0x1B는 이미 로컬 TCP에 쓰임.
                // 1초 대기로 KCP가 서버에 전달할 시간 확보 후 kill.
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                WebRtcBridge.stopProtocol();
            }, "kcp-delayed-stop");
            t.setDaemon(true);
            t.start();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WebRtcBridge.stop();
            WebRtcBridge.stopHost();
            WebRtcBridge.stopProtocol();
            WebRtcBridge.cleanup();
        }, "kfcudp-shutdown"));
    }

    /**
     * CustomRoomScreen에서 Start 누를 때 호출
     */
    public static void startCustomRoom(MinecraftClient client,
                                       GameMode gameMode, int maxPlayers, boolean allowCheats) {
        if (client.player == null) return;

        IntegratedServer server = client.getServer();
        if (server == null) {
            client.player.sendMessage(Text.translatable("kfcudp.msg.singleplay_only"), false);
            return;
        }

        // 기존 초대 만료
        if (activeInviteCode != null) {
            client.player.sendMessage(
                    Text.translatable("kfcudp.msg.prev_invite_expired", activeInviteCode), false);
            cancelInvite();
        }

        activeMaxPlayers = maxPlayers;

        // openToLan: allowCheats 그대로 전달 (LAN 기본 동작)
        int lanPort = -1;
        if (!server.isRemote() && server.getServerPort() == -1) {
            lanPort = net.minecraft.util.NetworkUtils.findLocalPort();
            server.openToLan(gameMode, allowCheats, lanPort);
        }
        final int finalPort = (lanPort == -1) ? server.getServerPort() : lanPort;

        // ban 명령어를 dispatcher에 재등록하고 모든 플레이어에게 커맨드 트리 갱신
        server.execute(() -> server.execute(() -> {
            P2PBanManager.reregisterToDispatcher(server);
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                server.getCommandManager().sendCommandTree(sp);
            }
        }));

        // 초대 코드 생성
        String code = generateCode();

        try {
            WebRtcBridge.startHost(code, "127.0.0.1:" + finalPort);
        } catch (Exception e) {
            LOG.error("[kfcudp] Failed to start host: {}", e.getMessage(), e);
            client.player.sendMessage(Text.translatable("kfcudp.msg.host_failed"), false);
            return;
        }

        activeInviteCode = code;
        inviteTicksRemaining = INVITE_TIMEOUT_TICKS;

        MutableText prefix   = Text.translatable("kfcudp.msg.invite_prefix");
        MutableText codeText = Text.literal(code).setStyle(Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withBold(true)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(code))
                .withHoverEvent(new HoverEvent.ShowText(Text.translatable("kfcudp.msg.click_to_copy")))
        );
        MutableText suffix = Text.translatable("kfcudp.msg.invite_suffix");

        client.player.sendMessage(
                Text.empty().append(prefix).append(codeText).append(suffix), false);

        client.setScreen(null);
        client.mouse.lockCursor();
    }

    private static void cancelInvite() {
        activeInviteCode = null;
        inviteTicksRemaining = 0;
        WebRtcBridge.stopHost();
    }

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}