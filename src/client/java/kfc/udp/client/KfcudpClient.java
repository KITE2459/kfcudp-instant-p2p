package kfc.udp.client;

import kfc.udp.client.gui.CustomRoomScreen;
import kfc.udp.client.webrtc.P2PBanManager;
import kfc.udp.client.webrtc.P2PWhitelistManager;
import kfc.udp.client.webrtc.WebRtcBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import kfc.udp.client.gui.RoomListScreen;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class KfcudpClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("instant-p2p");
    private static final Random RANDOM = new Random();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int INVITE_TIMEOUT_TICKS = 20 * 120;

    private static String activeInviteCode = null;
    private static int inviteTicksRemaining = 0;
    private static int activeMaxPlayers = 8;
    /** 게스트가 한 번이라도 접속하면 true — 이후로는 미접속 만료 타이머를 다시 걸지 않는다. */
    private static boolean inviteEverJoined = false;

    /**
     * 정원(N/M) 표시용 — 접속자는 방장의 {@link #activeMaxPlayers}를 직접 모르므로,
     * 방장이 JOIN 완료 시점에 이 접두사로 시작하는 시스템 메시지로 몰래 보내준다.
     * {@code ClientReceiveMessageEvents.ALLOW_GAME}에서 이 접두사를 가로채 채팅에는
     * 안 띄우고 숫자만 파싱해서 저장한다. 새 커스텀 네트워킹 패킷을 추가하는 대신
     * 이미 쓰고 있는 바닐라 채팅 경로를 재사용 — VILLASframework 시그널링 스키마는
     * 서버가 고정해 둔 거라 건드리지 않는 게 안전하다(WebRtcHost 클래스 주석 참고).
     */
    private static final String CAPACITY_MARKER = "kfcudp:capacity:";
    /** 접속자 쪽에서 파싱해 캐시해 둔 방 정원. 0이면 아직 못 받음(host이거나, 마커 도착 전). */
    private static volatile int guestRoomMaxPlayers = 0;

    /** 이전 게스트가 실제로 나갈 때까지 미뤄둔 방 오픈 파라미터. null이면 대기 중인 게 없음. */
    private static PendingRoom pendingRoom = null;

    //? if >=26.1 {
    /*private record PendingRoom(GameType gameMode, int maxPlayers, boolean allowCheats, boolean manageCommands,
                                boolean publicRoom, String title) {}
    *///?} else {
    private record PendingRoom(GameMode gameMode, int maxPlayers, boolean allowCheats, boolean manageCommands,
                                boolean publicRoom, String title) {}
    //?}

    //? if >=26.2 {
    /*private static boolean kfcudp$publishServer(net.minecraft.client.server.IntegratedServer server,
            net.minecraft.world.level.GameType gameMode, boolean allowCheats, int lanPort) {
        // 26.2부터 PlayerList#isOp가 방장(싱글플레이 오너)에 대해서는
        // commandsAllowedForOtherPlayers(구 allowCommandsForAllPlayers)를 아예 안 보고
        // WorldData#isAllowCommands()만 그대로 반환한다 — 안 켜주면 게스트는 치트가 되는데
        // 정작 방장 본인은 안 되는 상황이 생긴다.
        server.setWorldAllowCommands(allowCheats);
        return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, gameMode, allowCheats, lanPort);
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static boolean kfcudp$publishServer(net.minecraft.client.server.IntegratedServer server,
            net.minecraft.world.level.GameType gameMode, boolean allowCheats, int lanPort) {
        return server.publishServer(gameMode, allowCheats, lanPort);
    }
    *///?}

    // 바닐라 "Open to LAN"으로 이미 열려 있어 publishServer를 다시 못 부르는 경우
    // (재바인드 실패) Allow Commands만 값을 갱신하는 경로에서도 26.2는 방장 본인의
    // 치트 권한을 위해 WorldData#allowCommands를 별도로 켜줘야 한다 — 위
    // kfcudp$publishServer의 주석 참고.
    //? if >=26.2 {
    /*private static void kfcudp$applyWorldAllowCommands(net.minecraft.client.server.IntegratedServer server, boolean allowCheats) {
        server.setWorldAllowCommands(allowCheats);
        // setWorldAllowCommands() 안의 updateCommandsAllowedForOtherPlayers()는
        // IntegratedServer가 따로 캐시해 둔 commandsAllowedForOtherPlayers 필드가
        // null이 아니면 그 값을 그대로 PlayerList에 재전파한다. 그 필드는 최초
        // publishServer() 호출(진짜 Open to LAN) 때 딱 한 번만 세팅되고 그 뒤로는
        // 아무도 안 건드려서, "이미 열려 있어 재바인드 없이 값만 갱신"하는 이
        // 경로에서 계속 최초 오픈 당시 값으로 되돌아간다 — 방을 다시 열어도
        // 접속자 쪽 치트 설정이 1차 값에 고정되는 버그의 원인. 캐시 필드 자체를
        // 직접 갱신해야 한다.
        server.setCommandsAllowedForOtherPlayers(allowCheats);
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static void kfcudp$applyWorldAllowCommands(net.minecraft.client.server.IntegratedServer server, boolean allowCheats) {
        // 26.1.x는 PlayerList#isOp가 방장에 대해 allowCommandsForAllPlayers로도 폴백하므로
        // 별도 처리가 필요 없다.
    }
    *///?}

    //? if >=26.1 {
    /*private static final java.util.Map<net.minecraft.client.gui.screens.Screen, java.util.List<net.minecraft.client.gui.components.AbstractWidget>>
            kfcudp$injectedWidgets = new java.util.WeakHashMap<>();

    @Override
    public void onInitializeClient() {
        LOG.info("[instant-p2p] WebRTC bridge mod initialized");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // 창 크기 변경 등으로 같은 화면에 AFTER_INIT이 다시 불릴 수 있다 —
            // 매번 새로 추가하면 버튼이 프레임마다 쌓이므로, 이전에 우리가 넣은
            // 버튼을 먼저 지우고 다시 그린다.
            java.util.List<net.minecraft.client.gui.components.AbstractWidget> previous = kfcudp$injectedWidgets.remove(screen);
            if (previous != null) Screens.getWidgets(screen).removeAll(previous);

            // 멀티플레이 화면 - 초대 수락하기 버튼
            if (screen instanceof JoinMultiplayerScreen) {
                int btnW = 100;
                int btnH = 20;
                int btnX = scaledWidth - btnW - 10;
                int btnY = 10;
                Button joinBtn = Button.builder(
                                Component.translatable("instant-p2p.join_room.title"),
                                button -> client.setScreenAndShow(new RoomListScreen(screen))
                        ).bounds(btnX, btnY, btnW, btnH).build();
                Screens.getWidgets(screen).add(joinBtn);
                kfcudp$injectedWidgets.put(screen, java.util.List.of(joinBtn));
                return;
            }

            if (!(screen instanceof PauseScreen gameMenu)) return;
            if (!gameMenu.showsPauseMenu()) return;

            boolean isHost = client.isLocalServer();
            // 접속자 쪽엔 Custom Room 버튼이 없으니, "우리 방에 webrtc로 들어와 있는
            // 세션인지"는 활성 webrtc 연결 여부로 판별한다 — JOIN 메시지에서 쓰는
            // 것과 동일한 신호(WebRtcBridge.getActiveConnectionUsesRelay()).
            boolean isGuestSession = !isHost && WebRtcBridge.getActiveConnectionUsesRelay() != null;
            if (!isHost && !isGuestSession) return;

            int btnW = 100;
            int btnH = 20;
            int btnX = scaledWidth - btnW - 10;
            int btnY = 10;

            java.util.List<net.minecraft.client.gui.components.AbstractWidget> added = new java.util.ArrayList<>();

            if (isHost) {
                Button customRoomBtn = Button.builder(
                                Component.translatable("instant-p2p.custom_room.title"),
                                button -> client.setScreenAndShow(new CustomRoomScreen(screen))
                        ).bounds(btnX, btnY, btnW, btnH).build();
                Screens.getWidgets(screen).add(customRoomBtn);
                added.add(customRoomBtn);

                // 방이 열려 있으면 초대 코드를 다시 복사할 수 있게 바로 밑에 표시
                if (activeInviteCode != null) {
                    String code = activeInviteCode;
                    Button codeBtn = Button.builder(
                                    Component.literal(code).withStyle(ChatFormatting.YELLOW),
                                    button -> client.keyboardHandler.setClipboard(code)
                            ).bounds(btnX, btnY + btnH + 2, btnW, btnH)
                                    .tooltip(Tooltip.create(
                                            Component.translatable("instant-p2p.msg.click_to_copy")))
                                    .build();
                    Screens.getWidgets(screen).add(codeBtn);
                    added.add(codeBtn);
                }
            }

            // 인원 표시(N/M) — 버튼이 아니라 초대 코드 아래에 작은 흰색 그림자
            // 텍스트로 표시한다. 방장은 직접 아는 값, 접속자는 JOIN 시점에 몰래 받아
            // 캐시해 둔 값(guestRoomMaxPlayers)을 쓴다.
            Integer current = null, max = null;
            if (isHost && activeInviteCode != null) {
                IntegratedServer server = client.getSingleplayerServer();
                if (server != null) {
                    current = server.getPlayerList().getPlayers().size();
                    max = activeMaxPlayers;
                }
            } else if (isGuestSession && guestRoomMaxPlayers > 0 && client.getConnection() != null) {
                current = client.getConnection().getOnlinePlayers().size();
                max = guestRoomMaxPlayers;
            }
            if (current != null) {
                net.minecraft.client.gui.components.StringWidget countText =
                        new net.minecraft.client.gui.components.StringWidget(
                                Component.translatable("instant-p2p.pause.player_count", current, max),
                                client.font);
                countText.setX(btnX + (btnW - countText.getWidth()) / 2);
                countText.setY(btnY + (btnH + 2) * 2 + 4);
                Screens.getWidgets(screen).add(countText);
                added.add(countText);
            }

            kfcudp$injectedWidgets.put(screen, added);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (activeInviteCode == null) return;
            if (client.level == null || client.player == null) {
                cancelInvite();
                return;
            }

            // 26.2부터 바닐라 Multiplayer Options 화면에서 LAN을 직접 다시 닫을 수 있다 —
            // 그렇게 닫힌 경우도 우리 쪽 방/초대장을 같이 정리해야 한다.
            IntegratedServer publishCheckServer = client.getSingleplayerServer();
            if (publishCheckServer == null || !publishCheckServer.isPublished()) {
                client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.invite_expired"));
                cancelInvite();
                return;
            }

            // 게스트가 한 번이라도 들어왔으면 그 뒤론 미접속 만료 자체를 안 건다 —
            // 방장이 명시적으로 방을 닫기 전까진 유지. (실제 감지는 ServerPlayConnectionEvents.JOIN)
            if (inviteEverJoined) return;

            inviteTicksRemaining--;
            if (inviteTicksRemaining <= 0) {
                client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.invite_expired"));
                cancelInvite();
            }
        });

        // 이전 게스트를 내보내는 중이면 "떠났습니다" 메시지가 먼저 뜨도록,
        // 방장만 남을 때까지 새 방 오픈을 미뤄둔다.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingRoom == null) return;
            IntegratedServer server = client.getSingleplayerServer();
            if (server == null || server.getPlayerCount() <= 1) {
                PendingRoom p = pendingRoom;
                pendingRoom = null;
                openRoomNow(client, p.gameMode(), p.maxPlayers(), p.allowCheats(), p.manageCommands(), p.publicRoom(), p.title());
            }
        });

        // 새 초대장을 발급하는 순간 이전 게스트를 비동기로 내보내는 중이라
        // server.getPlayerCount()로는 "누가 들어왔는지"를 신뢰할 수 없다
        // (아직 안 나간 이전 게스트가 새 방의 참여자로 잘못 카운트됨).
        // 로그인 완료 이벤트로 실제 신규 참여만 감지한다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null || inviteEverJoined) return;
            if (P2PBanManager.isHost(server, handler.player)) return;
            inviteEverJoined = true;
            // 접속자는 방 정원(activeMaxPlayers)을 직접 모르니, 채팅에는 안 뜨는
            // 마커 메시지로 몰래 알려준다 — ClientReceiveMessageEvents.ALLOW_GAME에서
            // 가로채 파싱한다. CAPACITY_MARKER 필드 주석 참고.
            handler.player.sendSystemMessage(Component.literal(CAPACITY_MARKER + activeMaxPlayers), false);
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendSystemMessage(
                            Component.translatable("instant-p2p.msg.invite_no_longer_expires"));
                }
            });
        });

        // webrtc.로 접속한 경우 월드 진입 시점에 내 연결이 직결인지 중계인지 알려준다.
        // kcp./일반 서버 접속이면 활성 webrtc 세션이 없으니 null → 아무 것도 안 뜸.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Boolean relay = WebRtcBridge.getActiveConnectionUsesRelay();
            if (relay == null || client.player == null) return;
            client.player.sendSystemMessage(Component.translatable(relay
                    ? "instant-p2p.msg.my_connection_relay"
                    : "instant-p2p.msg.my_connection_direct"));
        });

        // 방장이 몰래 보낸 정원(N/M) 마커 메시지를 채팅에 띄우지 않고 가로채 파싱한다.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String s = message.getString();
            if (!s.startsWith(CAPACITY_MARKER)) return true;
            try {
                guestRoomMaxPlayers = Integer.parseInt(s.substring(CAPACITY_MARKER.length()));
            } catch (NumberFormatException ignored) {}
            return false;
        });

        // ban/whitelist 명령어 등록 (리슨 서버에서도 동작)
        // 밴/화이트리스트/정원 체크는 PlayerManagerMixin → P2PBanManager.checkCanJoin 에서
        // LOGIN 단계에 처리한다. JOIN 이벤트에서 끊으면 이미 월드에 스폰된 뒤라
        // "joined the game" / "left the game" 로그가 남는다.
        P2PBanManager.registerCommands();
        P2PWhitelistManager.registerCommands();

        // 서버 연결 해제 시 KCP/QUIC 프로세스 종료.
        // 즉시 kill하면 0x1B Disconnect 패킷이 유실되어 서버에 고스트 잔류.
        // 1초 대기 후 종료 — 패킷 전송 완료 후 kill.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            guestRoomMaxPlayers = 0;
            Thread t = new Thread(() -> {
                // DISCONNECT 시점에 0x1B는 이미 로컬 TCP에 쓰임.
                // 1초 대기로 KCP가 서버에 전달할 시간 확보 후 kill.
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                WebRtcBridge.stopProtocol();
            }, "kcp-delayed-stop");
            t.setDaemon(true);
            t.start();
        });

        // "Save and Quit to Title"로 월드를 닫을 때도 방 재생성과 똑같이 접속자에게
        // 정상 Disconnect 패킷("호스트가 방을 닫았습니다")을 먼저 보낸다. 이게 없으면
        // 바닐라가 네트워크 채널을 그냥 끊어버려서 접속자 쪽엔 "연결 끊김" 같은 날것의
        // 에러 화면이 뜬다 — closeRoomGracefully()의 주석 참고. JVM 자체는 안 죽으므로
        // cancelInvite()의 비동기 지연 종료가 끝까지 안전하게 실행된다.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (activeInviteCode != null) cancelInvite();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WebRtcBridge.stop();
            WebRtcBridge.stopHost();
            WebRtcBridge.stopProtocol();
        }, "kfcudp-shutdown"));
    }
    *///?} else {
    private static final java.util.Map<net.minecraft.client.gui.screen.Screen, java.util.List<net.minecraft.client.gui.widget.ClickableWidget>>
            kfcudp$injectedWidgets = new java.util.WeakHashMap<>();

    @Override
    public void onInitializeClient() {
        LOG.info("[instant-p2p] WebRTC bridge mod initialized");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // 창 크기 변경 등으로 같은 화면에 AFTER_INIT이 다시 불릴 수 있다 —
            // 매번 새로 추가하면 버튼이 프레임마다 쌓이므로, 이전에 우리가 넣은
            // 버튼을 먼저 지우고 다시 그린다.
            java.util.List<net.minecraft.client.gui.widget.ClickableWidget> previous = kfcudp$injectedWidgets.remove(screen);
            if (previous != null) Screens.getButtons(screen).removeAll(previous);

            // 멀티플레이 화면 - 초대 수락하기 버튼
            if (screen instanceof MultiplayerScreen) {
                int btnW = 100;
                int btnH = 20;
                int btnX = scaledWidth - btnW - 10;
                int btnY = 10;
                ButtonWidget joinBtn = ButtonWidget.builder(
                                Text.translatable("instant-p2p.join_room.title"),
                                button -> client.setScreen(new RoomListScreen(screen))
                        ).dimensions(btnX, btnY, btnW, btnH).build();
                Screens.getButtons(screen).add(joinBtn);
                kfcudp$injectedWidgets.put(screen, java.util.List.of(joinBtn));
                return;
            }

            if (!(screen instanceof GameMenuScreen gameMenu)) return;
            if (!gameMenu.shouldShowMenu()) return;

            boolean isHost = client.isInSingleplayer();
            // 접속자 쪽엔 Custom Room 버튼이 없으니, "우리 방에 webrtc로 들어와 있는
            // 세션인지"는 활성 webrtc 연결 여부로 판별한다 — JOIN 메시지에서 쓰는
            // 것과 동일한 신호(WebRtcBridge.getActiveConnectionUsesRelay()).
            boolean isGuestSession = !isHost && WebRtcBridge.getActiveConnectionUsesRelay() != null;
            if (!isHost && !isGuestSession) return;

            int btnW = 100;
            int btnH = 20;
            int btnX = scaledWidth - btnW - 10;
            int btnY = 10;

            java.util.List<net.minecraft.client.gui.widget.ClickableWidget> added = new java.util.ArrayList<>();

            if (isHost) {
                ButtonWidget customRoomBtn = ButtonWidget.builder(
                                Text.translatable("instant-p2p.custom_room.title"),
                                button -> client.setScreen(new CustomRoomScreen(screen))
                        ).dimensions(btnX, btnY, btnW, btnH).build();
                Screens.getButtons(screen).add(customRoomBtn);
                added.add(customRoomBtn);

                // 방이 열려 있으면 초대 코드를 다시 복사할 수 있게 바로 밑에 표시
                if (activeInviteCode != null) {
                    String code = activeInviteCode;
                    ButtonWidget codeBtn = ButtonWidget.builder(
                                    Text.literal(code).formatted(Formatting.YELLOW),
                                    button -> client.keyboard.setClipboard(code)
                            ).dimensions(btnX, btnY + btnH + 2, btnW, btnH)
                                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                                            Text.translatable("instant-p2p.msg.click_to_copy")))
                                    .build();
                    Screens.getButtons(screen).add(codeBtn);
                    added.add(codeBtn);
                }
            }

            // 인원 표시(N/M) — 버튼이 아니라 초대 코드 아래에 작은 흰색 그림자
            // 텍스트로 표시한다. 방장은 직접 아는 값, 접속자는 JOIN 시점에 몰래 받아
            // 캐시해 둔 값(guestRoomMaxPlayers)을 쓴다.
            Integer current = null, max = null;
            if (isHost && activeInviteCode != null) {
                IntegratedServer server = client.getServer();
                if (server != null) {
                    current = server.getPlayerManager().getPlayerList().size();
                    max = activeMaxPlayers;
                }
            } else if (isGuestSession && guestRoomMaxPlayers > 0 && client.getNetworkHandler() != null) {
                current = client.getNetworkHandler().getPlayerList().size();
                max = guestRoomMaxPlayers;
            }
            if (current != null) {
                net.minecraft.client.gui.widget.TextWidget countText =
                        new net.minecraft.client.gui.widget.TextWidget(
                                Text.translatable("instant-p2p.pause.player_count", current, max),
                                client.textRenderer);
                countText.setX(btnX + (btnW - countText.getWidth()) / 2);
                countText.setY(btnY + (btnH + 2) * 2 + 4);
                Screens.getButtons(screen).add(countText);
                added.add(countText);
            }

            kfcudp$injectedWidgets.put(screen, added);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (activeInviteCode == null) return;
            if (client.world == null || client.player == null) {
                cancelInvite();
                return;
            }

            // 바닐라에서 LAN을 직접 다시 닫을 수 있는 경우(향후 버전 대비) — 그렇게
            // 닫힌 경우도 우리 쪽 방/초대장을 같이 정리해야 한다.
            IntegratedServer publishCheckServer = client.getServer();
            if (publishCheckServer == null || !publishCheckServer.isRemote()) {
                client.player.sendMessage(Text.translatable("instant-p2p.msg.invite_expired"), false);
                cancelInvite();
                return;
            }

            // 게스트가 한 번이라도 들어왔으면 그 뒤론 미접속 만료 자체를 안 건다 —
            // 방장이 명시적으로 방을 닫기 전까진 유지. (실제 감지는 ServerPlayConnectionEvents.JOIN)
            if (inviteEverJoined) return;

            inviteTicksRemaining--;
            if (inviteTicksRemaining <= 0) {
                client.player.sendMessage(Text.translatable("instant-p2p.msg.invite_expired"), false);
                cancelInvite();
            }
        });

        // 이전 게스트를 내보내는 중이면 "떠났습니다" 메시지가 먼저 뜨도록,
        // 방장만 남을 때까지 새 방 오픈을 미뤄둔다.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingRoom == null) return;
            IntegratedServer server = client.getServer();
            if (server == null || server.getCurrentPlayerCount() <= 1) {
                PendingRoom p = pendingRoom;
                pendingRoom = null;
                openRoomNow(client, p.gameMode(), p.maxPlayers(), p.allowCheats(), p.manageCommands(), p.publicRoom(), p.title());
            }
        });

        // 새 초대장을 발급하는 순간 이전 게스트를 비동기로 내보내는 중이라
        // server.getCurrentPlayerCount()로는 "누가 들어왔는지"를 신뢰할 수 없다
        // (아직 안 나간 이전 게스트가 새 방의 참여자로 잘못 카운트됨).
        // 로그인 완료 이벤트로 실제 신규 참여만 감지한다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null || inviteEverJoined) return;
            if (P2PBanManager.isHost(server, handler.player)) return;
            inviteEverJoined = true;
            // 접속자는 방 정원(activeMaxPlayers)을 직접 모르니, 채팅에는 안 뜨는
            // 마커 메시지로 몰래 알려준다 — ClientReceiveMessageEvents.ALLOW_GAME에서
            // 가로채 파싱한다. CAPACITY_MARKER 필드 주석 참고.
            handler.player.sendMessage(Text.literal(CAPACITY_MARKER + activeMaxPlayers), false);
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.translatable("instant-p2p.msg.invite_no_longer_expires"), false);
                }
            });
        });

        // webrtc.로 접속한 경우 월드 진입 시점에 내 연결이 직결인지 중계인지 알려준다.
        // kcp./일반 서버 접속이면 활성 webrtc 세션이 없으니 null → 아무 것도 안 뜸.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Boolean relay = WebRtcBridge.getActiveConnectionUsesRelay();
            if (relay == null || client.player == null) return;
            client.player.sendMessage(Text.translatable(relay
                    ? "instant-p2p.msg.my_connection_relay"
                    : "instant-p2p.msg.my_connection_direct"), false);
        });

        // 방장이 몰래 보낸 정원(N/M) 마커 메시지를 채팅에 띄우지 않고 가로채 파싱한다.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String s = message.getString();
            if (!s.startsWith(CAPACITY_MARKER)) return true;
            try {
                guestRoomMaxPlayers = Integer.parseInt(s.substring(CAPACITY_MARKER.length()));
            } catch (NumberFormatException ignored) {}
            return false;
        });

        // ban/whitelist 명령어 등록 (리슨 서버에서도 동작)
        // 밴/화이트리스트/정원 체크는 PlayerManagerMixin → P2PBanManager.checkCanJoin 에서
        // LOGIN 단계에 처리한다. JOIN 이벤트에서 끊으면 이미 월드에 스폰된 뒤라
        // "joined the game" / "left the game" 로그가 남는다.
        P2PBanManager.registerCommands();
        P2PWhitelistManager.registerCommands();

        // 서버 연결 해제 시 KCP/QUIC 프로세스 종료.
        // 즉시 kill하면 0x1B Disconnect 패킷이 유실되어 서버에 고스트 잔류.
        // 1초 대기 후 종료 — 패킷 전송 완료 후 kill.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            guestRoomMaxPlayers = 0;
            Thread t = new Thread(() -> {
                // DISCONNECT 시점에 0x1B는 이미 로컬 TCP에 쓰임.
                // 1초 대기로 KCP가 서버에 전달할 시간 확보 후 kill.
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                WebRtcBridge.stopProtocol();
            }, "kcp-delayed-stop");
            t.setDaemon(true);
            t.start();
        });

        // "Save and Quit to Title"로 월드를 닫을 때도 방 재생성과 똑같이 접속자에게
        // 정상 Disconnect 패킷("호스트가 방을 닫았습니다")을 먼저 보낸다. 이게 없으면
        // 바닐라가 네트워크 채널을 그냥 끊어버려서 접속자 쪽엔 "연결 끊김" 같은 날것의
        // 에러 화면이 뜬다 — closeRoomGracefully()의 주석 참고. JVM 자체는 안 죽으므로
        // cancelInvite()의 비동기 지연 종료가 끝까지 안전하게 실행된다.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (activeInviteCode != null) cancelInvite();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WebRtcBridge.stop();
            WebRtcBridge.stopHost();
            WebRtcBridge.stopProtocol();
        }, "kfcudp-shutdown"));
    }
    //?}

    /**
     * CustomRoomScreen에서 Start 누를 때 호출
     */
    //? if >=26.1 {
    /*public static void startCustomRoom(Minecraft client,
                                       GameType gameMode, int maxPlayers, boolean allowCheats, boolean manageCommands,
                                       boolean publicRoom, String title) {
        if (client.player == null) return;

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.singleplay_only"));
            return;
        }

        // 기존 초대 만료
        if (activeInviteCode != null) {
            client.player.sendSystemMessage(
                    Component.translatable("instant-p2p.msg.prev_invite_expired", activeInviteCode));
            cancelInvite();
        }

        // 게스트가 아직 남아 있으면(퇴장 처리가 비동기라 바로 안 빠짐) "떠났습니다"
        // 메시지가 먼저 뜨도록 방장만 남을 때까지 기다렸다가 새 방을 연다.
        if (server.getPlayerCount() > 1) {
            pendingRoom = new PendingRoom(gameMode, maxPlayers, allowCheats, manageCommands, publicRoom, title);
            client.setScreenAndShow(null);
            return;
        }

        openRoomNow(client, gameMode, maxPlayers, allowCheats, manageCommands, publicRoom, title);
    }
    *///?} else {
    public static void startCustomRoom(MinecraftClient client,
                                       GameMode gameMode, int maxPlayers, boolean allowCheats, boolean manageCommands,
                                       boolean publicRoom, String title) {
        if (client.player == null) return;

        IntegratedServer server = client.getServer();
        if (server == null) {
            client.player.sendMessage(Text.translatable("instant-p2p.msg.singleplay_only"), false);
            return;
        }

        // 기존 초대 만료
        if (activeInviteCode != null) {
            client.player.sendMessage(
                    Text.translatable("instant-p2p.msg.prev_invite_expired", activeInviteCode), false);
            cancelInvite();
        }

        // 게스트가 아직 남아 있으면(퇴장 처리가 비동기라 바로 안 빠짐) "떠났습니다"
        // 메시지가 먼저 뜨도록 방장만 남을 때까지 기다렸다가 새 방을 연다.
        if (server.getCurrentPlayerCount() > 1) {
            pendingRoom = new PendingRoom(gameMode, maxPlayers, allowCheats, manageCommands, publicRoom, title);
            client.setScreen(null);
            return;
        }

        openRoomNow(client, gameMode, maxPlayers, allowCheats, manageCommands, publicRoom, title);
    }
    //?}

    //? if >=26.1 {
    /*private static void openRoomNow(Minecraft client,
                                     GameType gameMode, int maxPlayers, boolean allowCheats, boolean manageCommands,
                                     boolean publicRoom, String title) {
        if (client.player == null) return;
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return;

        activeMaxPlayers = maxPlayers;
        P2PBanManager.setRoomMaxPlayers(maxPlayers);
        // "관리 명령어" 옵션은 Allow Commands(치트)와 완전히 독립 — 방장은 이 값과
        // 무관하게 항상 kick/ban/whitelist를 쓸 수 있다(P2PBanManager.requireAdminOrHost).
        P2PBanManager.setGuestManagementEnabled(manageCommands);
        // 화이트리스트 on/off는 세션 간 안 남기고 방 열 때마다 꺼진 상태로 시작
        P2PWhitelistManager.setEnabled(false);

        // openToLan: allowCheats 그대로 전달 (LAN 기본 동작)
        int lanPort = -1;
        if (!server.isPublished() && server.getPort() == -1) {
            lanPort = net.minecraft.util.HttpUtil.getAvailablePort();
            kfcudp$publishServer(server, gameMode, allowCheats, lanPort);
        } else {
            // 바닐라 "Open to LAN"으로 이미 열려 있던 경우 — openToLan()을 다시 부르면
            // 포트 재바인드 시도로 실패해서 여기서 고른 설정이 그냥 무시된다.
            // Allow Commands / 게임모드 둘 다 값만 따로 적용한다.
            server.getPlayerList().setAllowCommandsForAllPlayers(allowCheats);
            kfcudp$applyWorldAllowCommands(server, allowCheats);
            ((kfc.udp.client.mixin.IntegratedServerAccessor) server)
                    .kfcudp$setForcedGameMode(gameMode);
        }
        // openToLan은 max player count를 안 건드리므로 바닐라 기본값(8)에 그대로 걸려 있다.
        // P2PBanManager.checkCanJoin은 여기서 정한 정원보다 낮은 경우에만 거부하고,
        // 통과시키면 PlayerManagerMixin이 취소하지 않아 바닐라 자체 정원 체크가 이어서 돈다.
        // 26.x: PlayerList는 이제 정원을 저장 안 하고 IntegratedServer#getMaxPlayerCount()가
        // 8을 하드코딩해서 반환한다 — 위 P2PBanManager.setRoomMaxPlayers(maxPlayers)를
        // IntegratedServerMaxPlayersMixin이 읽어서 대신 가로챈다. 여기선 추가로 할 일 없음.
        final int finalPort = (lanPort == -1) ? server.getPort() : lanPort;

        // ban/whitelist 명령어를 dispatcher에 재등록하고 모든 플레이어에게 커맨드 트리 갱신
        server.execute(() -> server.execute(() -> {
            P2PBanManager.reregisterToDispatcher(server);
            P2PWhitelistManager.reregisterToDispatcher(server);
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(sp);
            }
        }));

        // 초대 코드 생성
        String code = generateCode();

        try {
            WebRtcBridge.startHost(code, "127.0.0.1:" + finalPort);
        } catch (Exception e) {
            LOG.error("[instant-p2p] Failed to start host: {}", e.getMessage(), e);
            client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.host_failed"));
            return;
        }
        // 방 제목을 비워뒀으면 무작위 문자열 대신 초대 코드 그대로 쓴다 — 코드를 알면
        // 어차피 방을 특정할 수 있으니 별도 무작위 식별자를 지어낼 이유가 없다.
        if (publicRoom && title.isEmpty()) {
            title = "Room - " + code;
        }
        if (publicRoom) {
            WebRtcBridge.publishPublicRoom(code, title, client.player.getName().getString());
        }

        activeInviteCode = code;
        inviteTicksRemaining = INVITE_TIMEOUT_TICKS;
        inviteEverJoined = false;

        MutableComponent prefix   = Component.translatable("instant-p2p.msg.invite_prefix");
        MutableComponent codeText = Component.literal(code).setStyle(Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withBold(true)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(code))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("instant-p2p.msg.click_to_copy")))
        );
        MutableComponent suffix = Component.translatable("instant-p2p.msg.invite_suffix");

        client.player.sendSystemMessage(
                Component.empty().append(prefix).append(codeText).append(suffix));
        client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.invite_expiry_notice"));
        if (publicRoom) {
            client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.public_room_notice", title));
        }

        client.setScreenAndShow(null);
        client.mouseHandler.grabMouse();
    }
    *///?} else {
    private static void openRoomNow(MinecraftClient client,
                                     GameMode gameMode, int maxPlayers, boolean allowCheats, boolean manageCommands,
                                     boolean publicRoom, String title) {
        if (client.player == null) return;
        IntegratedServer server = client.getServer();
        if (server == null) return;

        activeMaxPlayers = maxPlayers;
        P2PBanManager.setRoomMaxPlayers(maxPlayers);
        // "관리 명령어" 옵션은 Allow Commands(치트)와 완전히 독립 — 방장은 이 값과
        // 무관하게 항상 kick/ban/whitelist를 쓸 수 있다(P2PBanManager.requireAdminOrHost).
        P2PBanManager.setGuestManagementEnabled(manageCommands);
        // 화이트리스트 on/off는 세션 간 안 남기고 방 열 때마다 꺼진 상태로 시작
        P2PWhitelistManager.setEnabled(false);

        // openToLan: allowCheats 그대로 전달 (LAN 기본 동작)
        int lanPort = -1;
        if (!server.isRemote() && server.getServerPort() == -1) {
            lanPort = net.minecraft.util.NetworkUtils.findLocalPort();
            server.openToLan(gameMode, allowCheats, lanPort);
        } else {
            // 바닐라 "Open to LAN"으로 이미 열려 있던 경우 — openToLan()을 다시 부르면
            // 포트 재바인드 시도로 실패해서 여기서 고른 설정이 그냥 무시된다.
            // Allow Commands / 게임모드 둘 다 값만 따로 적용한다.
            server.getPlayerManager().setCheatsAllowed(allowCheats);
            ((kfc.udp.client.mixin.IntegratedServerAccessor) server)
                    .kfcudp$setForcedGameMode(gameMode);
        }
        // openToLan은 max player count를 안 건드리므로 바닐라 기본값(8)에 그대로 걸려 있다.
        // P2PBanManager.checkCanJoin은 여기서 정한 정원보다 낮은 경우에만 거부하고,
        // 통과시키면 PlayerManagerMixin이 취소하지 않아 바닐라 자체 정원 체크가 이어서 돈다.
        //? if <1.21.9 {
        // 1.21.5~1.21.8: PlayerManager#maxPlayers는 생성자에서만 정해지는 final 필드라
        // PlayerManagerAccessor(Mixin @Accessor)로 직접 덮어써야 실제로 8명 이상 들어올 수 있다.
        ((kfc.udp.client.mixin.PlayerManagerAccessor) server.getPlayerManager())
                .kfcudp$setMaxPlayers(maxPlayers);
        //?}
        // 1.21.9+: PlayerManager는 이제 정원을 저장 안 하고 IntegratedServer#getMaxPlayerCount()가
        // 8을 하드코딩해서 반환한다 — 위 P2PBanManager.setRoomMaxPlayers(maxPlayers)를
        // IntegratedServerMaxPlayersMixin이 읽어서 대신 가로챈다. 여기선 추가로 할 일 없음.
        final int finalPort = (lanPort == -1) ? server.getServerPort() : lanPort;

        // ban/whitelist 명령어를 dispatcher에 재등록하고 모든 플레이어에게 커맨드 트리 갱신
        server.execute(() -> server.execute(() -> {
            P2PBanManager.reregisterToDispatcher(server);
            P2PWhitelistManager.reregisterToDispatcher(server);
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                server.getCommandManager().sendCommandTree(sp);
            }
        }));

        // 초대 코드 생성
        String code = generateCode();

        try {
            WebRtcBridge.startHost(code, "127.0.0.1:" + finalPort);
        } catch (Exception e) {
            LOG.error("[instant-p2p] Failed to start host: {}", e.getMessage(), e);
            client.player.sendMessage(Text.translatable("instant-p2p.msg.host_failed"), false);
            return;
        }
        // 방 제목을 비워뒀으면 무작위 문자열 대신 초대 코드 그대로 쓴다 — 코드를 알면
        // 어차피 방을 특정할 수 있으니 별도 무작위 식별자를 지어낼 이유가 없다.
        if (publicRoom && title.isEmpty()) {
            title = "Room - " + code;
        }
        if (publicRoom) {
            WebRtcBridge.publishPublicRoom(code, title, client.player.getName().getString());
        }

        activeInviteCode = code;
        inviteTicksRemaining = INVITE_TIMEOUT_TICKS;
        inviteEverJoined = false;

        MutableText prefix   = Text.translatable("instant-p2p.msg.invite_prefix");
        MutableText codeText = Text.literal(code).setStyle(Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withBold(true)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(code))
                .withHoverEvent(new HoverEvent.ShowText(Text.translatable("instant-p2p.msg.click_to_copy")))
        );
        MutableText suffix = Text.translatable("instant-p2p.msg.invite_suffix");

        client.player.sendMessage(
                Text.empty().append(prefix).append(codeText).append(suffix), false);
        client.player.sendMessage(Text.translatable("instant-p2p.msg.invite_expiry_notice"), false);
        if (publicRoom) {
            client.player.sendMessage(Text.translatable("instant-p2p.msg.public_room_notice", title), false);
        }

        client.setScreen(null);
        client.mouse.lockCursor();
    }
    //?}

    private static void cancelInvite() {
        cancelInvite(null, false);
    }

    //? if >=26.1 {
    /*private static void cancelInvite(IntegratedServer explicitServer, boolean waitForClose) {
        activeInviteCode = null;
        inviteTicksRemaining = 0;
        inviteEverJoined = false;
        P2PBanManager.setRoomMaxPlayers(0);
        P2PBanManager.setGuestManagementEnabled(false);
        closeRoomGracefully(explicitServer, waitForClose);
    }

    // ServerDisconnectStopMixin(서버 스레드, 안전망)이 호출한다. Mixin이 들고 있는
    // 서버 인스턴스를 그대로 받는다(client.getSingleplayerServer()로 다시 조회하면
    // 이미 null로 비워진 뒤일 수 있다).
    public static void kfcudp$onWorldStopping(IntegratedServer explicitServer) {
        if (activeInviteCode != null) cancelInvite(explicitServer, false);
    }

    // IntegratedServerStopMixin(stop/halt HEAD)이 호출한다 — 실제로 방을 정리하는
    // 건 거의 항상 이쪽이고, 이 지점은 렌더 스레드다. 그대로 블로킹해서 기다리면
    // "Saving level" 화면을 그릴 기회조차 없이 그 프레임에 멈춘 것처럼 보인다
    // (렌더 루프 자체가 우리 sleep 안에 갇히므로) — 블로킹 전에 그 화면을 먼저
    // 강제로 한 프레임 그려서 바닐라가 종료할 때와 똑같이 보이게 한다.
    public static void kfcudp$onIntegratedServerStopping(IntegratedServer explicitServer) {
        if (activeInviteCode == null) return;
        Minecraft.getInstance().setScreenAndShow(
                new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        cancelInvite(explicitServer, true);
    }
    *///?} else {
    private static void cancelInvite(IntegratedServer explicitServer, boolean waitForClose) {
        activeInviteCode = null;
        inviteTicksRemaining = 0;
        inviteEverJoined = false;
        P2PBanManager.setRoomMaxPlayers(0);
        P2PBanManager.setGuestManagementEnabled(false);
        closeRoomGracefully(explicitServer, waitForClose);
    }

    // ServerDisconnectStopMixin(서버 스레드, 안전망)이 호출한다. Mixin이 들고 있는
    // 서버 인스턴스를 그대로 받는다(client.getServer()로 다시 조회하면 이미 null로
    // 비워진 뒤일 수 있다).
    public static void kfcudp$onWorldStopping(IntegratedServer explicitServer) {
        if (activeInviteCode != null) cancelInvite(explicitServer, false);
    }

    // IntegratedServerStopMixin(stop/halt HEAD)이 호출한다 — 실제로 방을 정리하는
    // 건 거의 항상 이쪽이고, 이 지점은 렌더 스레드다. 그대로 블로킹해서 기다리면
    // "Saving level" 화면을 그릴 기회조차 없이 그 프레임에 멈춘 것처럼 보인다
    // (렌더 루프 자체가 우리 sleep 안에 갇히므로) — 블로킹 전에 그 화면을 먼저
    // 강제로 한 프레임 그려서 바닐라가 종료할 때와 똑같이 보이게 한다.
    public static void kfcudp$onIntegratedServerStopping(IntegratedServer explicitServer) {
        if (activeInviteCode == null) return;
        MinecraftClient.getInstance().setScreenAndRender(
                new MessageScreen(Text.translatable("menu.savingLevel")));
        cancelInvite(explicitServer, true);
    }
    //?}

    /**
     * 게스트를 먼저 정상적인 사유로 끊고(0x1B Disconnect), 그 패킷이 터널을
     * 통과할 시간을 준 뒤에야 실제로 터널(WebRtcHost)을 종료한다.
     * <p>
     * 터널을 바로 끊어버리면 조인자 쪽 Minecraft 클라이언트는 소켓이 그냥
     * 뚝 끊긴 걸로 보여서 "Internal Exception: connection reset" 같은 날것의
     * 에러 화면이 뜬다 — 정상 Disconnect 패킷이 먼저 지나가야 "방장이 방을
     * 닫았습니다" 같은 깔끔한 화면이 뜬다.
     * <p>
     * {@code explicitServer}가 있으면(월드 종료 경로) 그걸 그대로 쓰고, 없으면(방
     * 재생성 경로, null) {@code client}에서 새로 조회한다. {@code waitForClose}는
     * kfcudp$onIntegratedServerStopping이 "Saving level" 화면을 이미 그린 뒤에만
     * true로 넘어온다 — 그 전에는 절대 렌더 스레드를 블로킹하면 안 된다.
     */
    //? if >=26.1 {
    /*private static void closeRoomGracefully(IntegratedServer explicitServer, boolean waitForClose) {
        try {
            IntegratedServer server = explicitServer != null ? explicitServer : Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                final IntegratedServer finalServer = server;
                // execute()는 큐잉만 하고 안 기다리는데, SERVER_STOPPING/onDisconnected
                // 경로는 이미 서버 스레드 안이라 그러면 서버가 틱 루프를 멈춘 뒤라
                // 영영 실행 안 될 수 있다. executeBlocking()은 이미 서버 스레드면
                // 즉시 실행하고, 아니면(방 재생성, 클라이언트 스레드) 큐잉 후
                // 완료까지 기다린다.
                finalServer.executeBlocking(() -> {
                    try {
                        for (ServerPlayer sp : finalServer.getPlayerList().getPlayers()) {
                            if (P2PBanManager.isHost(finalServer, sp)) continue;
                            sp.connection.disconnect(Component.translatable("instant-p2p.msg.room_closed"));
                        }
                    } catch (Exception e) {
                        LOG.warn("[instant-p2p] Failed to kick guests before closing room: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] closeRoomGracefully failed: {}", e.getMessage());
        }
        kfcudp$delayedStopHost(waitForClose);
    }
    *///?} else {
    private static void closeRoomGracefully(IntegratedServer explicitServer, boolean waitForClose) {
        try {
            IntegratedServer server = explicitServer != null ? explicitServer : MinecraftClient.getInstance().getServer();
            if (server != null) {
                final IntegratedServer finalServer = server;
                // execute()의 "sync" 버전이라길래 executeSync()를 썼었는데, 실제로는
                // 그냥 execute()의 별칭이라 전혀 안 기다린다(바이트코드 확인) — 진짜
                // "이미 서버 스레드면 즉시 실행, 아니면 큐잉 후 완료까지 대기"하는
                // 건 submitAndJoin()이다(바닐라 disconnect() 내부에서도 이걸 쓴다).
                finalServer.submitAndJoin(() -> {
                    try {
                        for (ServerPlayerEntity sp : finalServer.getPlayerManager().getPlayerList()) {
                            if (P2PBanManager.isHost(finalServer, sp)) continue;
                            sp.networkHandler.disconnect(Text.translatable("instant-p2p.msg.room_closed"));
                        }
                    } catch (Exception e) {
                        LOG.warn("[instant-p2p] Failed to kick guests before closing room: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] closeRoomGracefully failed: {}", e.getMessage());
        }
        kfcudp$delayedStopHost(waitForClose);
    }
    //?}

    // 연달아 새 방을 열면 startCustomRoom → WebRtcBridge.startHost가 이미 이전
    // 인스턴스를 동기적으로 닫아 둔다 — 그 사이 새 방이 열리지 않았을 때만(토큰이
    // 여전히 현재 호스트일 때만) 실제로 멈춘다. waitForClose면("Saving level" 화면을
    // 이미 그려 둔 월드 종료 경로) 이 지연 자체가 호출자를 블로킹하고, 아니면(방
    // 재생성) 별도 스레드로 흘려보내 게임을 안 멈추게 한다.
    private static void kfcudp$delayedStopHost(boolean waitForClose) {
        Object hostToken = WebRtcBridge.currentHostToken();
        if (waitForClose) {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            WebRtcBridge.stopHostIfCurrent(hostToken);
            return;
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            WebRtcBridge.stopHostIfCurrent(hostToken);
        }, "kfcudp-room-close");
        t.setDaemon(true);
        t.start();
    }

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** RoomListScreen 하단 코드 입력과 방 목록 클릭 공용 — 코드로 접속. */
    //? if >=26.1 {
    /*public static void joinRoomByCode(Minecraft client, Screen parent, String code) {
        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parseString(address);
        ServerData serverInfo = new ServerData(
                Component.translatable("instant-p2p.join_room.server_name").getString(), address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(parent, client, serverAddress, serverInfo, false, null);
    }
    *///?} else {
    public static void joinRoomByCode(MinecraftClient client, Screen parent, String code) {
        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parse(address);
        ServerInfo serverInfo = new ServerInfo(
                Text.translatable("instant-p2p.join_room.server_name").getString(), address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(parent, client, serverAddress, serverInfo, false, null);
    }
    //?}
}
