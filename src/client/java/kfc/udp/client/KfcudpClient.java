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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import kfc.udp.client.gui.JoinRoomScreen;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
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

    /** 이전 게스트가 실제로 나갈 때까지 미뤄둔 방 오픈 파라미터. null이면 대기 중인 게 없음. */
    private static PendingRoom pendingRoom = null;

    //? if >=26.1 {
    /*private record PendingRoom(GameType gameMode, int maxPlayers, boolean allowCheats) {}
    *///?} else {
    private record PendingRoom(GameMode gameMode, int maxPlayers, boolean allowCheats) {}
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
                                button -> client.setScreenAndShow(new JoinRoomScreen(screen))
                        ).bounds(btnX, btnY, btnW, btnH).build();
                Screens.getWidgets(screen).add(joinBtn);
                kfcudp$injectedWidgets.put(screen, java.util.List.of(joinBtn));
                return;
            }

            if (!(screen instanceof PauseScreen gameMenu)) return;
            if (!gameMenu.showsPauseMenu()) return;
            if (!client.isLocalServer()) return;

            int btnW = 100;
            int btnH = 20;
            int btnX = scaledWidth - btnW - 10;
            int btnY = 10;

            java.util.List<net.minecraft.client.gui.components.AbstractWidget> added = new java.util.ArrayList<>();

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
                openRoomNow(client, p.gameMode(), p.maxPlayers(), p.allowCheats());
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
                                button -> client.setScreen(new JoinRoomScreen(screen))
                        ).dimensions(btnX, btnY, btnW, btnH).build();
                Screens.getButtons(screen).add(joinBtn);
                kfcudp$injectedWidgets.put(screen, java.util.List.of(joinBtn));
                return;
            }

            if (!(screen instanceof GameMenuScreen gameMenu)) return;
            if (!gameMenu.shouldShowMenu()) return;
            if (!client.isInSingleplayer()) return;

            int btnW = 100;
            int btnH = 20;
            int btnX = scaledWidth - btnW - 10;
            int btnY = 10;

            java.util.List<net.minecraft.client.gui.widget.ClickableWidget> added = new java.util.ArrayList<>();

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
                openRoomNow(client, p.gameMode(), p.maxPlayers(), p.allowCheats());
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
        }, "kfcudp-shutdown"));
    }
    //?}

    /**
     * CustomRoomScreen에서 Start 누를 때 호출
     */
    //? if >=26.1 {
    /*public static void startCustomRoom(Minecraft client,
                                       GameType gameMode, int maxPlayers, boolean allowCheats) {
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
            pendingRoom = new PendingRoom(gameMode, maxPlayers, allowCheats);
            client.setScreenAndShow(null);
            return;
        }

        openRoomNow(client, gameMode, maxPlayers, allowCheats);
    }
    *///?} else {
    public static void startCustomRoom(MinecraftClient client,
                                       GameMode gameMode, int maxPlayers, boolean allowCheats) {
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
            pendingRoom = new PendingRoom(gameMode, maxPlayers, allowCheats);
            client.setScreen(null);
            return;
        }

        openRoomNow(client, gameMode, maxPlayers, allowCheats);
    }
    //?}

    //? if >=26.1 {
    /*private static void openRoomNow(Minecraft client,
                                     GameType gameMode, int maxPlayers, boolean allowCheats) {
        if (client.player == null) return;
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return;

        activeMaxPlayers = maxPlayers;
        P2PBanManager.setRoomMaxPlayers(maxPlayers);
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

        client.setScreenAndShow(null);
        client.mouseHandler.grabMouse();
    }
    *///?} else {
    private static void openRoomNow(MinecraftClient client,
                                     GameMode gameMode, int maxPlayers, boolean allowCheats) {
        if (client.player == null) return;
        IntegratedServer server = client.getServer();
        if (server == null) return;

        activeMaxPlayers = maxPlayers;
        P2PBanManager.setRoomMaxPlayers(maxPlayers);
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

        client.setScreen(null);
        client.mouse.lockCursor();
    }
    //?}

    private static void cancelInvite() {
        activeInviteCode = null;
        inviteTicksRemaining = 0;
        inviteEverJoined = false;
        P2PBanManager.setRoomMaxPlayers(0);
        closeRoomGracefully();
    }

    /**
     * 게스트를 먼저 정상적인 사유로 끊고(0x1B Disconnect), 그 패킷이 터널을
     * 통과할 시간을 준 뒤에야 실제로 터널(WebRtcHost)을 종료한다.
     * <p>
     * 터널을 바로 끊어버리면 조인자 쪽 Minecraft 클라이언트는 소켓이 그냥
     * 뚝 끊긴 걸로 보여서 "Internal Exception: connection reset" 같은 날것의
     * 에러 화면이 뜬다 — 정상 Disconnect 패킷이 먼저 지나가야 "방장이 방을
     * 닫았습니다" 같은 깔끔한 화면이 뜬다.
     */
    //? if >=26.1 {
    /*private static void closeRoomGracefully() {
        try {
            Minecraft client = Minecraft.getInstance();
            IntegratedServer server = client.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    try {
                        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                            if (P2PBanManager.isHost(server, sp)) continue;
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

        // 연달아 새 방을 열면 startCustomRoom → WebRtcBridge.startHost가 이미 이전
        // 인스턴스를 동기적으로 닫아 둔다 — 이 지연 스레드는 그 사이 새 방이
        // 열리지 않았을 때만(토큰이 여전히 현재 호스트일 때만) 실제로 멈춘다.
        Object hostToken = WebRtcBridge.currentHostToken();
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            WebRtcBridge.stopHostIfCurrent(hostToken);
        }, "kfcudp-room-close");
        t.setDaemon(true);
        t.start();
    }
    *///?} else {
    private static void closeRoomGracefully() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            IntegratedServer server = client.getServer();
            if (server != null) {
                server.execute(() -> {
                    try {
                        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                            if (P2PBanManager.isHost(server, sp)) continue;
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

        // 연달아 새 방을 열면 startCustomRoom → WebRtcBridge.startHost가 이미 이전
        // 인스턴스를 동기적으로 닫아 둔다 — 이 지연 스레드는 그 사이 새 방이
        // 열리지 않았을 때만(토큰이 여전히 현재 호스트일 때만) 실제로 멈춘다.
        Object hostToken = WebRtcBridge.currentHostToken();
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            WebRtcBridge.stopHostIfCurrent(hostToken);
        }, "kfcudp-room-close");
        t.setDaemon(true);
        t.start();
    }
    //?}

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
