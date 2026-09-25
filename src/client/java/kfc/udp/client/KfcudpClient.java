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

    private static String activeInviteCode = null;
    private static int activeMaxPlayers = 8;
    /** 지금 접속해 있는 게스트 수(방장 제외) — 공개 방 목록의 인원 표기용으로 JOIN/
     * DISCONNECT 리스너에서 직접 증감시켜 둔다. {@code server.getPlayerList().size()}를
     * 그 자리에서 스냅샷하지 않는 이유: (1) DISCONNECT 이벤트가 실제 플레이어 목록
     * 제거보다 먼저/나중에 발생하는지가 마인크래프트 버전/이벤트마다 보장되지 않아
     * 방금 나간 사람이 카운트에 남거나 빠지는 게 일관되지 않았고, (2) applyRoomSettings는
     * 클라이언트(GUI) 스레드에서 곧장 호출되는데 그 안에서 서버 스레드가 언제든 수정할
     * 수 있는 리스트를 직접 읽는 건 스레드 안전하지 않다 — 정수 하나를 증감시키는 게
     * 훨씬 안전하고 정확하다. */
    private static volatile int activeGuestCount = 0;

    // ── 현재 활성 방의 옵션 값(핫스왑용) ─────────────────────────────────────────
    // CustomRoomScreen을 "방 만들기"뿐 아니라 "이미 켜진 방의 설정 변경"에도
    // 재사용하려면, 화면을 다시 열었을 때 지금 실제로 적용돼 있는 값을 채워 넣어야
    // 한다 — applyRoomSettings()/openRoomNow()가 방을 열거나 설정을 바꿀 때마다
    // 여기 같이 기록해 둔다. isRoomActive()가 false면(방이 없음) 의미 없는 값.
    private static boolean activeAllowCheats = false;
    private static boolean activePublicRoom = false;
    private static String activeTitle = "";
    /** 마지막으로 실제 공지(announce)에 실려나간 채널값 — applyRoomSettings가 채널만
     * 바뀌었을 때도(제목/정원/공개여부는 그대로여도) 재공지가 필요한지 판단하는 데 쓴다. */
    private static String activeChannel = "";
    //? if >=26.1 {
    /*private static GameType activeGameMode = GameType.ADVENTURE;
    *///?} else {
    private static GameMode activeGameMode = GameMode.ADVENTURE;
    //?}

    public static boolean isRoomActive() { return activeInviteCode != null; }

    /** 지금 이 클라이언트가 아는 방장 UUID — 내가 방장이면 내 UUID, 접속자면 JOIN 때 받아둔
     * {@link #guestHostUuid}(둘 다 아니면 null). DevBadge의 방장 표시(📶)가 이걸로 판단한다. */
    public static java.util.UUID currentHostUuid() {
        if (activeInviteCode != null) {
            //? if >=26.1 {
            /*return net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
            *///?} else {
            return net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
            //?}
        }
        return guestHostUuid;
    }
    public static int getActiveMaxPlayers() { return activeMaxPlayers; }
    public static boolean isActiveAllowCheats() { return activeAllowCheats; }
    public static boolean isActivePublicRoom() { return activePublicRoom; }
    public static String getActiveTitle() { return activeTitle; }
    //? if >=26.1 {
    /*public static GameType getActiveGameMode() { return activeGameMode; }
    *///?} else {
    public static GameMode getActiveGameMode() { return activeGameMode; }
    //?}

    // 바닐라 LAN 설정 화면(26.2 MultiplayerOptionsScreen / 26.3 WorldOptionsScreen)에서 접속자 관련 값을
    // 바꾸면 방 설정과 어긋난다 — IntegratedServerMaxPlayersMixin이 그 지점에서 이리로 밀어넣어 방 설정을
    // 같은 값으로 맞춘다. 방이 안 열려 있으면 그냥 바닐라 동작이라 건드리지 않는다.
    //? if >=26.2 {
    /*public static void syncGuestCheatsFromVanilla(boolean allowCheats) {
        if (activeInviteCode != null) activeAllowCheats = allowCheats;
    }

    public static void syncGuestGameModeFromVanilla(GameType gameMode) {
        if (activeInviteCode != null && gameMode != null) activeGameMode = gameMode;
    }
    *///?}

    /** 채널 설정 화면(ChannelScreen)에서 적용을 눌렀을 때 — 공개 중인 방이면 새 채널·규칙으로 다시 공지한다. */
    //? if >=26.1 {
    /*public static void republishForChannelChange(Minecraft client) {
        String channel = kfc.udp.client.webrtc.P2PConfig.getChannelKey();
        if (activeInviteCode == null || !activePublicRoom || client.player == null || channel.equals(activeChannel)) return;
        // publishPublicRoom(=PublicRoomAnnouncer.publish)이 채널 구성이 바뀐 걸 스스로 감지해 재접속한다 —
        // 여기서 먼저 내릴 필요 없다.
        WebRtcBridge.publishPublicRoom(activeInviteCode, activeTitle, client.player.getName().getString(),
                client.player.getUUID().toString(), activeGuestCount + 1, activeMaxPlayers);
        activeChannel = channel;
    }
    *///?} else {
    public static void republishForChannelChange(MinecraftClient client) {
        String channel = kfc.udp.client.webrtc.P2PConfig.getChannelKey();
        if (activeInviteCode == null || !activePublicRoom || client.player == null || channel.equals(activeChannel)) return;
        // publishPublicRoom(=PublicRoomAnnouncer.publish)이 채널 구성이 바뀐 걸 스스로 감지해 재접속한다 —
        // 여기서 먼저 내릴 필요 없다.
        WebRtcBridge.publishPublicRoom(activeInviteCode, activeTitle, client.player.getName().getString(),
                client.player.getUuid().toString(), activeGuestCount + 1, activeMaxPlayers);
        activeChannel = channel;
    }
    //?}

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
    /** 마커로 같이 받은 방장 UUID — 접속자 화면 인원 표시에서 방장은 특혜자여도 세기 위해. */
    private static volatile java.util.UUID guestHostUuid = null;

    /** 정원 마커 본문: "정원:방장UUID". */
    private static String capacityMarker(int max) {
        //? if >=26.1 {
        /*return CAPACITY_MARKER + max + ":" + Minecraft.getInstance().getUser().getProfileId();
        *///?} else {
        return CAPACITY_MARKER + max + ":" + MinecraftClient.getInstance().getSession().getUuidOrNull();
        //?}
    }

    /**
     * ESC 일시정지 화면에 넣은 인원 표시(N/M)를 몇 tick마다 다시 그릴지 —
     * refreshPauseMenuWidgets는 화면이 처음 열릴 때(AFTER_INIT)만 불려서 그
     * 순간의 인원 스냅샷을 텍스트에 박아넣고 끝이었다. ESC를 연 채로 누가
     * 들고나거나(호스트 기준 현재 인원) 방장이 정원을 바꿔도(접속자 기준
     * 최대 인원 — applyRoomSettings의 CAPACITY_MARKER 재전송 참고) 화면을
     * 닫았다 다시 열어야만 반영되던 문제를 고친다. 매 tick 다시 그리긴
     * 아까우니(버튼까지 다 지웠다 다시 만듦) 10틱(0.5초)마다만 갱신한다.
     */
    private static final int PAUSE_REFRESH_INTERVAL_TICKS = 10;
    private static int pauseRefreshCooldown = 0;

    /** 지금 붙어있는 세션이 webrtc 커스텀 방 접속자 세션인지 — JOIN 시점에 세팅,
     * DISCONNECT 시점에 읽고 리셋한다({@link #pendingRoomListRedirect} 참고). */
    private static volatile boolean activeSessionIsWebrtcGuest = false;
    /** 이번 접속 세션에서 이미 "차단한 유저가 접속했다"고 알린 플레이어 — 사람마다 한 번만(클라이언트 스레드 전용). */
    private static final java.util.Set<java.util.UUID> warnedBlockedPlayers = new java.util.HashSet<>();
    /** 방 클릭 → 입장 전 확인(RoomMembersProbe)이 도는 중 — 연타로 접속이 두 번 시작되지 않게(클라이언트 스레드 전용). */
    private static boolean joinCheckInFlight = false;
    /** 커스텀 방 접속자로 있다가 나왔을 때 true로 세팅 — 다음에 바닐라 멀티플레이
     * 화면이 뜨는 순간(ScreenEvents.AFTER_INIT) 그 화면 대신 RoomListScreen으로
     * 바꿔치기하고 즉시 false로 되돌린다. 방장이 방을 닫아서 쫓겨난 경우도,
     * 직접 "연결 끊기"를 누른 경우도 똑같이 적용된다 — 강퇴/에러로 끊긴
     * 경우엔 DisconnectedScreen에서 사유를 먼저 보고 "서버 목록으로" 눌러야
     * 이 화면에 도달하므로, 사유 확인은 그대로 되면서 그 다음 목적지만 바뀐다. */
    private static volatile boolean pendingRoomListRedirect = false;

    /** 이전 게스트가 실제로 나갈 때까지 미뤄둔 방 오픈 파라미터. null이면 대기 중인 게 없음. */
    private static PendingRoom pendingRoom = null;
    /** 퇴장 대기 상한 — 이 시각이 지나면 남은 사람이 있어도 방을 연다(영영 안 열리는 것 방지). */
    private static long pendingRoomDeadline = 0;
    private static final long PENDING_ROOM_MAX_WAIT_MS = 5_000;

    //? if >=26.1 {
    /*private record PendingRoom(GameType gameMode, int maxPlayers, boolean allowCheats,
                                boolean publicRoom, String title) {}
    *///?} else {
    private record PendingRoom(GameMode gameMode, int maxPlayers, boolean allowCheats,
                                boolean publicRoom, String title) {}
    //?}

    // "Custom Room"/"Join Room" 버튼을 누르면 실제 화면으로 바로 가는 대신, 한 번도 확인 안 눌렀다면
    // SafetyWarningScreen을 먼저 보여준다 — "다시 보지 않기"를 눌렀으면(P2PConfig.isSafetyWarningDismissed)
    // 곧장 target으로 간다.
    //? if >=26.1 {
    /*private static void kfcudp$openWithSafetyWarning(net.minecraft.client.Minecraft client, Screen parent, Screen target) {
        if (kfc.udp.client.webrtc.P2PConfig.isSafetyWarningDismissed()) client.setScreenAndShow(target);
        else client.setScreenAndShow(new kfc.udp.client.gui.SafetyWarningScreen(parent,
                "instant-p2p.safety_warning.heading", "instant-p2p.safety_warning.message",
                0xFFFF5555, 0xFF1A0000, kfc.udp.client.webrtc.P2PConfig::setSafetyWarningDismissed,
                () -> client.setScreenAndShow(target)));
    }
    *///?} else {
    private static void kfcudp$openWithSafetyWarning(MinecraftClient client, Screen parent, Screen target) {
        if (kfc.udp.client.webrtc.P2PConfig.isSafetyWarningDismissed()) client.setScreen(target);
        else client.setScreen(new kfc.udp.client.gui.SafetyWarningScreen(parent,
                "instant-p2p.safety_warning.heading", "instant-p2p.safety_warning.message",
                0xFFFF5555, 0xFF1A0000, kfc.udp.client.webrtc.P2PConfig::setSafetyWarningDismissed,
                () -> client.setScreen(target)));
    }
    //?}

    // 26.3부터 publishServer에서 게임 모드 인자가 빠졌다 — 접속자 게임 모드는 IntegratedServerMaxPlayersMixin이
    // getForcedGameType을 가로채 방 게임 모드(activeGameMode)로 돌려준다.
    //? if >=26.3 {
    /*private static boolean kfcudp$publishServer(net.minecraft.client.server.IntegratedServer server,
            net.minecraft.world.level.GameType gameMode, boolean allowCheats, int lanPort) {
        return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, allowCheats, lanPort);
    }
    *///?}
    //? if >=26.2 <26.3 {
    /*private static boolean kfcudp$publishServer(net.minecraft.client.server.IntegratedServer server,
            net.minecraft.world.level.GameType gameMode, boolean allowCheats, int lanPort) {
        return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, gameMode, allowCheats, lanPort);
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static boolean kfcudp$publishServer(net.minecraft.client.server.IntegratedServer server,
            net.minecraft.world.level.GameType gameMode, boolean allowCheats, int lanPort) {
        return server.publishServer(gameMode, allowCheats, lanPort);
    }
    *///?}

    // 바닐라 "Open to LAN"으로 이미 열려 있어 publishServer를 다시 못 부르는 경우(재바인드 실패) 접속자
    // 명령어 허용만 값을 갱신하는 경로.
    //
    // 월드 설정(WorldData#allowCommands)은 어느 버전에서도 건드리지 않는다 — 그건 level.dat에 저장돼서 방
    // 옵션 하나가 싱글 월드의 치트 설정을 영구히 바꿔버린다(랜 서버는 임시 값만 쓴다). 방장 치트는 월드 설정
    // 그대로 따라가고, 방 옵션은 접속자에게만 적용된다.
    //? if >=26.3 {
    /*private static void kfcudp$applyGuestCommandAccess(net.minecraft.client.server.IntegratedServer server, boolean allowCheats) {
        // 26.3은 이 값이 월드 설정과 함께 봐야 효력이 있어서, 접속자 권한 자체는
        // IntegratedServerMaxPlayersMixin이 방 옵션으로 답한다. 여기선 권한 재전송만 유발한다.
        server.setGuestCommandAccess(allowCheats);
    }
    *///?}
    //? if >=26.2 <26.3 {
    /*private static void kfcudp$applyGuestCommandAccess(net.minecraft.client.server.IntegratedServer server, boolean allowCheats) {
        server.getPlayerList().setAllowCommandsForAllPlayers(allowCheats);
        // 최초 publishServer() 때 한 번만 세팅되고 그 뒤론 아무도 안 건드려서, 나중에 PlayerList 값만
        // 갱신해도 다음 재전파 때 최초 값으로 되돌아간다 — 캐시 필드 자체도 같이 갱신해야 한다.
        server.setCommandsAllowedForOtherPlayers(allowCheats);
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static void kfcudp$applyGuestCommandAccess(net.minecraft.client.server.IntegratedServer server, boolean allowCheats) {
        server.getPlayerList().setAllowCommandsForAllPlayers(allowCheats);
        // 26.1.x는 PlayerList#isOp가 방장에 대해 allowCommandsForAllPlayers로도 폴백하므로
        // 별도 처리가 필요 없다.
    }
    *///?}

    // 이미 열려 있는 방의 접속자 게임 모드 갱신. 26.3은 그 필드가 없어져 IntegratedServerMaxPlayersMixin이
    // activeGameMode를 읽어 가므로 할 일이 없다.
    //? if >=26.3 {
    /*private static void kfcudp$setGuestGameMode(net.minecraft.client.server.IntegratedServer server, GameType gameMode) {}
    *///?}
    //? if >=26.1 <26.3 {
    /*private static void kfcudp$setGuestGameMode(net.minecraft.client.server.IntegratedServer server, GameType gameMode) {
        ((kfc.udp.client.mixin.IntegratedServerAccessor) server).kfcudp$setForcedGameMode(gameMode);
    }
    *///?}

    // 26.2부터 Minecraft#screen 필드가 없어지고 Minecraft#gui(Gui)로 화면 상태가
    // 옮겨갔다(javap로 확인) — 지금 떠 있는 화면을 물어보는 코드가 여러 군데(ESC
    // 인원 표시 실시간 갱신 등)라 여기 한 곳에서만 버전을 가른다.
    //? if >=26.2 {
    /*private static net.minecraft.client.gui.screens.Screen kfcudp$currentScreen(net.minecraft.client.Minecraft client) {
        return client.gui.screen();
    }
    *///?}
    //? if >=26.1 <26.2 {
    /*private static net.minecraft.client.gui.screens.Screen kfcudp$currentScreen(net.minecraft.client.Minecraft client) {
        return client.screen;
    }
    *///?}

    // 초대 코드 채팅의 "클릭하면 복사" 스타일. ClickEvent가 1.21.5부터 레코드(CopyToClipboard)로
    // 바뀌어서 그 전 버전은 Action 생성자를 쓴다.
    //? if >=1.21.5 <26.1 {
    private static Style kfcudp$copyOnClick(Style style, String code) {
        return style.withClickEvent(new ClickEvent.CopyToClipboard(code));
    }
    //?}
    //? if <1.21.5 {
    /*private static Style kfcudp$copyOnClick(Style style, String code) {
        return style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code));
    }
    *///?}

    //? if >=26.1 {
    /*private static final java.util.Map<net.minecraft.client.gui.screens.Screen, java.util.List<net.minecraft.client.gui.components.AbstractWidget>>
            kfcudp$injectedWidgets = new java.util.WeakHashMap<>();

    @Override
    public void onInitializeClient() {
        LOG.info("[instant-p2p] WebRTC bridge mod initialized");
        kfc.udp.client.webrtc.ExpelManager.register();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // 창 크기 변경 등으로 같은 화면에 AFTER_INIT이 다시 불릴 수 있다 —
            // 매번 새로 추가하면 버튼이 프레임마다 쌓이므로, 이전에 우리가 넣은
            // 버튼을 먼저 지우고 다시 그린다.
            java.util.List<net.minecraft.client.gui.components.AbstractWidget> previous = kfcudp$injectedWidgets.remove(screen);
            if (previous != null) Screens.getWidgets(screen).removeAll(previous);

            // 멀티플레이 화면 - 초대 수락하기 버튼
            if (screen instanceof JoinMultiplayerScreen) {
                // 커스텀 방 접속자로 있다가 막 나온 거라면, 이 화면 대신 곧장
                // RoomListScreen으로 보낸다 — "멀티를 하다가 나왔는데 바닐라
                // 서버 목록에 떨어지는" 게 아니라 커스텀 방 목록으로 돌아가게.
                // 강퇴/에러로 끊긴 경우도 DisconnectedScreen에서 사유를 보고
                // "서버 목록으로"를 눌러야 여기 도달하므로, 사유 확인은 그대로다.
                if (pendingRoomListRedirect) {
                    pendingRoomListRedirect = false;
                    client.setScreenAndShow(new RoomListScreen(screen));
                    return;
                }
                int btnW = 100;
                int btnH = 20;
                int btnX = scaledWidth - btnW - 10;
                int btnY = 10;
                Button joinBtn = Button.builder(
                                Component.translatable("instant-p2p.join_room.title"),
                                button -> kfcudp$openWithSafetyWarning(client, screen, new RoomListScreen(screen))
                        ).bounds(btnX, btnY, btnW, btnH).build();
                Screens.getWidgets(screen).add(joinBtn);
                kfcudp$injectedWidgets.put(screen, java.util.List.of(joinBtn));
                return;
            }

            refreshPauseMenuWidgets(client, screen);
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
            }
        });

        // 이전 게스트를 내보내는 중이면 "떠났습니다" 메시지가 먼저 뜨도록,
        // 방장만 남을 때까지 새 방 오픈을 미뤄둔다.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingRoom == null) return;
            IntegratedServer server = client.getSingleplayerServer();
            if (server == null || server.getPlayerCount() <= 1 || System.currentTimeMillis() >= pendingRoomDeadline) {
                PendingRoom p = pendingRoom;
                pendingRoom = null;
                openRoomNow(client, p.gameMode(), p.maxPlayers(), p.allowCheats(),p.publicRoom(), p.title());
            }
        });

        // ESC 일시정지 화면의 인원 표시(N/M) 실시간 갱신 — PAUSE_REFRESH_INTERVAL_TICKS
        // 필드 주석 참고.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            net.minecraft.client.gui.screens.Screen screen = kfcudp$currentScreen(client);
            if (!(screen instanceof PauseScreen)) {
                pauseRefreshCooldown = 0;
                return;
            }
            if (--pauseRefreshCooldown > 0) return;
            pauseRefreshCooldown = PAUSE_REFRESH_INTERVAL_TICKS;
            refreshPauseMenuWidgets(client, screen);
        });

        // 새 초대장을 발급하는 순간 이전 게스트를 비동기로 내보내는 중이라
        // server.getPlayerCount()로는 "누가 들어왔는지"를 신뢰할 수 없다
        // (아직 안 나간 이전 게스트가 새 방의 참여자로 잘못 카운트됨).
        // 로그인 완료 이벤트로 실제 신규 참여만 감지한다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null) return;
            if (P2PBanManager.isHost(server, handler.player)) return;
            // 접속자는 방 정원(activeMaxPlayers)을 직접 모르니, 채팅에는 안 뜨는
            // 마커 메시지로 몰래 알려준다 — ClientReceiveMessageEvents.ALLOW_GAME에서
            // 가로채 파싱한다. CAPACITY_MARKER 필드 주석 참고. guestRoomMaxPlayers는
            // 접속자 클라이언트마다 각자 캐시하는 값이라, 예전에 "방 생애주기당 첫
            // 접속자에게만" 보내던 건 버그였다 — 두 번째 이후 접속자는 이 마커를
            // 영원히 못 받아 일시정지 화면 인원 표시가 안 떴다. 접속자마다 매번 보낸다.
            handler.player.sendSystemMessage(Component.literal(capacityMarker(activeMaxPlayers)), false);
        });

        // 공개 방 목록의 인원(현재/최대) 표기 갱신용 — 위 마커 전송과 달리 매번(첫
        // 접속자뿐 아니라 계속) 걸어야 하므로 별개 리스너로 둔다. 실제 재발행은
        // PublicRoomAnnouncer가 디바운스하므로 여기서 매번 불러도 부담 없다.
        // server.getPlayerList().size()를 그 자리에서 스냅샷하지 않고 activeGuestCount를
        // 직접 증감시키는 이유는 그 필드 선언부 주석 참고 — 인원 변경 시 목록이 간헐적으로
        // 잘못 갱신되던 문제의 원인이었다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null || P2PBanManager.isHost(server, handler.player) || DevBadge.hasPerk(handler.player.getUUID())) return;
            activeGuestCount++;
            if (activePublicRoom) {
                WebRtcBridge.updatePublicRoomPlayerCount(activeGuestCount + 1, activeMaxPlayers);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (activeInviteCode == null || P2PBanManager.isHost(server, handler.player) || DevBadge.hasPerk(handler.player.getUUID())) return;
            activeGuestCount = Math.max(0, activeGuestCount - 1);
            if (activePublicRoom) {
                WebRtcBridge.updatePublicRoomPlayerCount(activeGuestCount + 1, activeMaxPlayers);
            }
        });

        // webrtc.로 접속한 경우 월드 진입 시점에 내 연결이 직결인지 중계인지 알려준다.
        // kcp./일반 서버 접속이면 활성 webrtc 세션이 없으니 null → 아무 것도 안 뜸.
        // 동시에 이 세션이 webrtc 커스텀 방 접속자 세션이었다는 걸 기억해 둔다
        // (DISCONNECT 시점에 읽어서 pendingRoomListRedirect 세팅용).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Boolean relay = WebRtcBridge.getActiveConnectionUsesRelay();
            if (relay == null || client.player == null) return;
            activeSessionIsWebrtcGuest = true;
            client.player.sendSystemMessage(Component.translatable(relay
                    ? "instant-p2p.msg.my_connection_relay"
                    : "instant-p2p.msg.my_connection_direct"));
            // 제작자·서포터·방송인이면 바로 아래에 본인 안내를 한 줄 더 — 나한테만 보인다.
            String roleKey = DevBadge.roleMessageKey(client.player.getUUID());
            if (roleKey != null) {
                net.minecraft.network.chat.MutableComponent line = Component.translatable(roleKey);
                // 특혜가 꺼져 있으면(DevBadge.PERKS_ENABLED) 괄호도 안 붙인다 — 안내와 실제가 달라지면 안 된다.
                if (DevBadge.hasPerk(client.player.getUUID())) line.append(Component.translatable("instant-p2p.msg.perk_note"));
                // 등급자(개발자·서포터·방송인 전부, ExpelManager.priority>0) 전원에게 공통 안내 —
                // 플레이어 차단 화면에서 강퇴·임시밴 권한이 있다는 걸 접속 즉시 알려준다.
                if (kfc.udp.client.webrtc.ExpelManager.priority(client.player.getUUID()) > 0) {
                    line.append(Component.translatable("instant-p2p.msg.expel_note"));
                }
                client.player.sendSystemMessage(line);
            }
        });

        // 방장 쪽: 지금 접속 중인 플레이어를 기록한다 — 입장하려는 사람이 차단한 유저가 있는지
        // 접속 전에 확인하는 데 쓴다(RoomMembersProbe).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> P2PBanManager.playerJoined(handler.player.getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> P2PBanManager.playerLeft(handler.player.getUUID()));

        // 내가 차단한 플레이어의 채팅은 안 보이게 한다.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                sender == null || !P2PBanManager.isPlayerBanned(P2PBanManager.profileId(sender).toString()));

        // 차단한 유저가 들어온 순간(서버의 "joined the game" 메시지)에만 채팅으로 경고한다. 서버는 이 메시지를
        // 접속자 목록(UUID)보다 먼저 보내므로 0.1초 뒤 이름으로 목록을 찾아 UUID로 대조한다. 한 세션에 사람마다
        // 한 번만 — 나갔다 다시 들어와도 조용.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!activeSessionIsWebrtcGuest || overlay
                    || !(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t)
                    || !t.getKey().startsWith("multiplayer.player.joined") || t.getArgs().length == 0) return;
            String name = t.getArgs()[0] instanceof Component c ? c.getString() : String.valueOf(t.getArgs()[0]);
            Minecraft mc = Minecraft.getInstance();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                var info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(name) : null;
                if (info == null || mc.player == null) return;
                java.util.UUID id = P2PBanManager.profileId(info.getProfile());
                if (P2PBanManager.isPlayerBanned(id.toString()) && warnedBlockedPlayers.add(id)) {
                    mc.player.sendSystemMessage(Component.translatable("instant-p2p.msg.blocked_player_joined", name));
                }
            }, java.util.concurrent.CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS, mc));
        });

        // 방장이 몰래 보낸 정원(N/M) 마커 메시지를 채팅에 띄우지 않고 가로채 파싱한다.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String s = message.getString();
            if (!s.startsWith(CAPACITY_MARKER)) return true;
            try {
                String[] v = s.substring(CAPACITY_MARKER.length()).split(":");
                guestRoomMaxPlayers = Integer.parseInt(v[0]);
                guestHostUuid = java.util.UUID.fromString(v[1]);
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored) {}
            return false;
        });

        // ban/whitelist 명령어 등록 (리슨 서버에서도 동작)
        // 밴/화이트리스트/정원 체크는 PlayerManagerMixin → P2PBanManager.checkCanJoin 에서
        // LOGIN 단계에 처리한다. JOIN 이벤트에서 끊으면 이미 월드에 스폰된 뒤라
        // "joined the game" / "left the game" 로그가 남는다.
        P2PBanManager.registerCommands();
        P2PWhitelistManager.registerCommands();
        // 모드 차단 ↔ 바닐라 "채팅에서 숨기기" 연동 — 들어갈 때마다 차단 목록을 바닐라 숨김 목록에 다시 채운다.
        ChatHideSync.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            guestRoomMaxPlayers = 0;
            guestHostUuid = null;
            warnedBlockedPlayers.clear();
            // 커스텀 방 접속자 세션이었으면, 다음에 뜰 바닐라 멀티플레이 화면을
            // RoomListScreen으로 바꿔치기하도록 표시해 둔다(AFTER_INIT에서 소비).
            if (activeSessionIsWebrtcGuest) {
                activeSessionIsWebrtcGuest = false;
                pendingRoomListRedirect = true;
            }
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
        }, "kfcudp-shutdown"));
    }
    *///?} else {
    private static final java.util.Map<net.minecraft.client.gui.screen.Screen, java.util.List<net.minecraft.client.gui.widget.ClickableWidget>>
            kfcudp$injectedWidgets = new java.util.WeakHashMap<>();

    @Override
    public void onInitializeClient() {
        LOG.info("[instant-p2p] WebRTC bridge mod initialized");
        kfc.udp.client.webrtc.ExpelManager.register();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // 창 크기 변경 등으로 같은 화면에 AFTER_INIT이 다시 불릴 수 있다 —
            // 매번 새로 추가하면 버튼이 프레임마다 쌓이므로, 이전에 우리가 넣은
            // 버튼을 먼저 지우고 다시 그린다.
            java.util.List<net.minecraft.client.gui.widget.ClickableWidget> previous = kfcudp$injectedWidgets.remove(screen);
            if (previous != null) Screens.getButtons(screen).removeAll(previous);

            // 멀티플레이 화면 - 초대 수락하기 버튼
            if (screen instanceof MultiplayerScreen) {
                // 커스텀 방 접속자로 있다가 막 나온 거라면, 이 화면 대신 곧장
                // RoomListScreen으로 보낸다 — "멀티를 하다가 나왔는데 바닐라
                // 서버 목록에 떨어지는" 게 아니라 커스텀 방 목록으로 돌아가게.
                // 강퇴/에러로 끊긴 경우도 DisconnectedScreen에서 사유를 보고
                // "서버 목록으로"를 눌러야 여기 도달하므로, 사유 확인은 그대로다.
                if (pendingRoomListRedirect) {
                    pendingRoomListRedirect = false;
                    client.setScreen(new RoomListScreen(screen));
                    return;
                }
                int btnW = 100;
                int btnH = 20;
                int btnX = scaledWidth - btnW - 10;
                int btnY = 10;
                ButtonWidget joinBtn = ButtonWidget.builder(
                                Text.translatable("instant-p2p.join_room.title"),
                                button -> kfcudp$openWithSafetyWarning(client, screen, new RoomListScreen(screen))
                        ).dimensions(btnX, btnY, btnW, btnH).build();
                Screens.getButtons(screen).add(joinBtn);
                kfcudp$injectedWidgets.put(screen, java.util.List.of(joinBtn));
                return;
            }

            refreshPauseMenuWidgets(client, screen);
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
            }
        });

        // 이전 게스트를 내보내는 중이면 "떠났습니다" 메시지가 먼저 뜨도록,
        // 방장만 남을 때까지 새 방 오픈을 미뤄둔다.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingRoom == null) return;
            IntegratedServer server = client.getServer();
            if (server == null || server.getCurrentPlayerCount() <= 1 || System.currentTimeMillis() >= pendingRoomDeadline) {
                PendingRoom p = pendingRoom;
                pendingRoom = null;
                openRoomNow(client, p.gameMode(), p.maxPlayers(), p.allowCheats(),p.publicRoom(), p.title());
            }
        });

        // ESC 일시정지 화면의 인원 표시(N/M) 실시간 갱신 — PAUSE_REFRESH_INTERVAL_TICKS
        // 필드 주석 참고.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!(client.currentScreen instanceof GameMenuScreen)) {
                pauseRefreshCooldown = 0;
                return;
            }
            if (--pauseRefreshCooldown > 0) return;
            pauseRefreshCooldown = PAUSE_REFRESH_INTERVAL_TICKS;
            refreshPauseMenuWidgets(client, client.currentScreen);
        });

        // 새 초대장을 발급하는 순간 이전 게스트를 비동기로 내보내는 중이라
        // server.getCurrentPlayerCount()로는 "누가 들어왔는지"를 신뢰할 수 없다
        // (아직 안 나간 이전 게스트가 새 방의 참여자로 잘못 카운트됨).
        // 로그인 완료 이벤트로 실제 신규 참여만 감지한다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null) return;
            if (P2PBanManager.isHost(server, handler.player)) return;
            // 접속자는 방 정원(activeMaxPlayers)을 직접 모르니, 채팅에는 안 뜨는
            // 마커 메시지로 몰래 알려준다 — ClientReceiveMessageEvents.ALLOW_GAME에서
            // 가로채 파싱한다. CAPACITY_MARKER 필드 주석 참고. guestRoomMaxPlayers는
            // 접속자 클라이언트마다 각자 캐시하는 값이라, 예전에 "방 생애주기당 첫
            // 접속자에게만" 보내던 건 버그였다 — 두 번째 이후 접속자는 이 마커를
            // 영원히 못 받아 일시정지 화면 인원 표시가 안 떴다. 접속자마다 매번 보낸다.
            handler.player.sendMessage(Text.literal(capacityMarker(activeMaxPlayers)), false);
        });

        // 공개 방 목록의 인원(현재/최대) 표기 갱신용 — 위 마커 전송과 달리 매번(첫
        // 접속자뿐 아니라 계속) 걸어야 하므로 별개 리스너로 둔다. 실제 재발행은
        // PublicRoomAnnouncer가 디바운스하므로 여기서 매번 불러도 부담 없다.
        // server.getPlayerManager().getPlayerList().size()를 그 자리에서 스냅샷하지
        // 않고 activeGuestCount를 직접 증감시키는 이유는 그 필드 선언부 주석 참고 —
        // 인원 변경 시 목록이 간헐적으로 잘못 갱신되던 문제의 원인이었다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (activeInviteCode == null || P2PBanManager.isHost(server, handler.player) || DevBadge.hasPerk(handler.player.getUuid())) return;
            activeGuestCount++;
            if (activePublicRoom) {
                WebRtcBridge.updatePublicRoomPlayerCount(activeGuestCount + 1, activeMaxPlayers);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (activeInviteCode == null || P2PBanManager.isHost(server, handler.player) || DevBadge.hasPerk(handler.player.getUuid())) return;
            activeGuestCount = Math.max(0, activeGuestCount - 1);
            if (activePublicRoom) {
                WebRtcBridge.updatePublicRoomPlayerCount(activeGuestCount + 1, activeMaxPlayers);
            }
        });

        // webrtc.로 접속한 경우 월드 진입 시점에 내 연결이 직결인지 중계인지 알려준다.
        // kcp./일반 서버 접속이면 활성 webrtc 세션이 없으니 null → 아무 것도 안 뜸.
        // 동시에 이 세션이 webrtc 커스텀 방 접속자 세션이었다는 걸 기억해 둔다
        // (DISCONNECT 시점에 읽어서 pendingRoomListRedirect 세팅용).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Boolean relay = WebRtcBridge.getActiveConnectionUsesRelay();
            if (relay == null || client.player == null) return;
            activeSessionIsWebrtcGuest = true;
            client.player.sendMessage(Text.translatable(relay
                    ? "instant-p2p.msg.my_connection_relay"
                    : "instant-p2p.msg.my_connection_direct"), false);
            // 제작자·서포터·방송인이면 바로 아래에 본인 안내를 한 줄 더 — 나한테만 보인다.
            String roleKey = DevBadge.roleMessageKey(client.player.getUuid());
            if (roleKey != null) {
                net.minecraft.text.MutableText line = Text.translatable(roleKey);
                // 특혜가 꺼져 있으면(DevBadge.PERKS_ENABLED) 괄호도 안 붙인다 — 안내와 실제가 달라지면 안 된다.
                if (DevBadge.hasPerk(client.player.getUuid())) line.append(Text.translatable("instant-p2p.msg.perk_note"));
                // 등급자(개발자·서포터·방송인 전부, ExpelManager.priority>0) 전원에게 공통 안내 —
                // 플레이어 차단 화면에서 강퇴·임시밴 권한이 있다는 걸 접속 즉시 알려준다.
                if (kfc.udp.client.webrtc.ExpelManager.priority(client.player.getUuid()) > 0) {
                    line.append(Text.translatable("instant-p2p.msg.expel_note"));
                }
                client.player.sendMessage(line, false);
            }
        });

        // 방장 쪽: 지금 접속 중인 플레이어를 기록한다 — 입장하려는 사람이 차단한 유저가 있는지
        // 접속 전에 확인하는 데 쓴다(RoomMembersProbe).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> P2PBanManager.playerJoined(handler.player.getUuid()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> P2PBanManager.playerLeft(handler.player.getUuid()));

        // 내가 차단한 플레이어의 채팅은 안 보이게 한다.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                sender == null || !P2PBanManager.isPlayerBanned(P2PBanManager.profileId(sender).toString()));

        // 차단한 유저가 들어온 순간(서버의 "joined the game" 메시지)에만 채팅으로 경고한다. 서버는 이 메시지를
        // 접속자 목록(UUID)보다 먼저 보내므로 0.1초 뒤 이름으로 목록을 찾아 UUID로 대조한다. 한 세션에 사람마다
        // 한 번만 — 나갔다 다시 들어와도 조용.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!activeSessionIsWebrtcGuest || overlay
                    || !(message.getContent() instanceof net.minecraft.text.TranslatableTextContent t)
                    || !t.getKey().startsWith("multiplayer.player.joined") || t.getArgs().length == 0) return;
            String name = t.getArgs()[0] instanceof Text c ? c.getString() : String.valueOf(t.getArgs()[0]);
            MinecraftClient mc = MinecraftClient.getInstance();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                var entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(name) : null;
                if (entry == null || mc.player == null) return;
                java.util.UUID id = P2PBanManager.profileId(entry.getProfile());
                if (P2PBanManager.isPlayerBanned(id.toString()) && warnedBlockedPlayers.add(id)) {
                    mc.player.sendMessage(Text.translatable("instant-p2p.msg.blocked_player_joined", name), false);
                }
            }, java.util.concurrent.CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS, mc));
        });

        // 방장이 몰래 보낸 정원(N/M) 마커 메시지를 채팅에 띄우지 않고 가로채 파싱한다.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String s = message.getString();
            if (!s.startsWith(CAPACITY_MARKER)) return true;
            try {
                String[] v = s.substring(CAPACITY_MARKER.length()).split(":");
                guestRoomMaxPlayers = Integer.parseInt(v[0]);
                guestHostUuid = java.util.UUID.fromString(v[1]);
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored) {}
            return false;
        });

        // ban/whitelist 명령어 등록 (리슨 서버에서도 동작)
        // 밴/화이트리스트/정원 체크는 PlayerManagerMixin → P2PBanManager.checkCanJoin 에서
        // LOGIN 단계에 처리한다. JOIN 이벤트에서 끊으면 이미 월드에 스폰된 뒤라
        // "joined the game" / "left the game" 로그가 남는다.
        P2PBanManager.registerCommands();
        P2PWhitelistManager.registerCommands();
        // 모드 차단 ↔ 바닐라 "채팅에서 숨기기" 연동 — 들어갈 때마다 차단 목록을 바닐라 숨김 목록에 다시 채운다.
        ChatHideSync.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            guestRoomMaxPlayers = 0;
            guestHostUuid = null;
            warnedBlockedPlayers.clear();
            // 커스텀 방 접속자 세션이었으면, 다음에 뜰 바닐라 멀티플레이 화면을
            // RoomListScreen으로 바꿔치기하도록 표시해 둔다(AFTER_INIT에서 소비).
            if (activeSessionIsWebrtcGuest) {
                activeSessionIsWebrtcGuest = false;
                pendingRoomListRedirect = true;
            }
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
        }, "kfcudp-shutdown"));
    }
    //?}

    /**
     * 방을 여는 순간 방장 자신의 탭 목록 항목을 강제로 다시 그리게 한다 — 탭 목록 이름은 접속
     * 시점에 딱 한 번만 계산돼 전송되는데(DevBadgeMixin 클래스 주석 참고), 방장은 이미 싱글
     * 플레이로 로그인해 있던 상태에서 방만 여는 거라 그 최초 계산 시점엔 DevBadge.isHostPlayer가
     * 아직 false였다 — 그래서 방장 본인의 📶 표시가 이름표·채팅엔 바로 붙어도(둘 다 매번 새로
     * 계산됨) 탭 목록엔 안 붙고, 다른 계기로 우연히 재전송되기 전까진 그대로였다.
     */
    //? if >=26.1 {
    /*private static void kfcudp$refreshHostTabList(IntegratedServer server, java.util.UUID hostUuid) {
        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(hostUuid);
        if (sp != null) server.getPlayerList().broadcastAll(
                new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
                        net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, sp));
    }
    *///?} else {
    private static void kfcudp$refreshHostTabList(IntegratedServer server, java.util.UUID hostUuid) {
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(hostUuid);
        if (sp != null) server.getPlayerManager().sendToAll(
                new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(
                        net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME, sp));
    }
    //?}

    /**
     * CustomRoomScreen에서 Start 누를 때 호출
     */
    //? if >=26.1 {
    /*public static void startCustomRoom(Minecraft client,
                                       GameType gameMode, int maxPlayers, boolean allowCheats,
                                       boolean publicRoom, String title) {
        if (client.player == null) return;

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.singleplay_only"));
            return;
        }

        // 기존 초대 만료
        boolean closedPrevious = activeInviteCode != null;
        if (closedPrevious) {
            client.player.sendSystemMessage(
                    Component.translatable("instant-p2p.msg.prev_invite_expired"));
            cancelInvite();
        }

        // 방금 닫은 방의 게스트를 내보내는 중이면(퇴장 처리가 비동기라 바로 안 빠짐)
        // "떠났습니다" 메시지가 먼저 뜨도록 잠깐 기다렸다가 새 방을 연다. 예전엔 조건 없이
        // 기다려서, 바닐라 LAN으로 들어온 사람이 남아 있으면 방이 영영 안 열렸다.
        if (closedPrevious && server.getPlayerCount() > 1) {
            pendingRoomDeadline = System.currentTimeMillis() + PENDING_ROOM_MAX_WAIT_MS;
            pendingRoom = new PendingRoom(gameMode, maxPlayers, allowCheats, publicRoom, title);
            client.setScreenAndShow(null);
            return;
        }

        openRoomNow(client, gameMode, maxPlayers, allowCheats, publicRoom, title);
    }
    *///?} else {
    public static void startCustomRoom(MinecraftClient client,
                                       GameMode gameMode, int maxPlayers, boolean allowCheats,
                                       boolean publicRoom, String title) {
        if (client.player == null) return;

        IntegratedServer server = client.getServer();
        if (server == null) {
            client.player.sendMessage(Text.translatable("instant-p2p.msg.singleplay_only"), false);
            return;
        }

        // 기존 초대 만료
        boolean closedPrevious = activeInviteCode != null;
        if (closedPrevious) {
            client.player.sendMessage(
                    Text.translatable("instant-p2p.msg.prev_invite_expired"), false);
            cancelInvite();
        }

        // 방금 닫은 방의 게스트를 내보내는 중이면(퇴장 처리가 비동기라 바로 안 빠짐)
        // "떠났습니다" 메시지가 먼저 뜨도록 잠깐 기다렸다가 새 방을 연다. 예전엔 조건 없이
        // 기다려서, 바닐라 LAN으로 들어온 사람이 남아 있으면 방이 영영 안 열렸다.
        if (closedPrevious && server.getCurrentPlayerCount() > 1) {
            pendingRoomDeadline = System.currentTimeMillis() + PENDING_ROOM_MAX_WAIT_MS;
            pendingRoom = new PendingRoom(gameMode, maxPlayers, allowCheats, publicRoom, title);
            client.setScreen(null);
            return;
        }

        openRoomNow(client, gameMode, maxPlayers, allowCheats, publicRoom, title);
    }
    //?}

    //? if >=26.1 {
    /*private static void openRoomNow(Minecraft client,
                                     GameType gameMode, int maxPlayers, boolean allowCheats,
                                     boolean publicRoom, String title) {
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
            kfcudp$applyGuestCommandAccess(server, allowCheats);
            kfcudp$setGuestGameMode(server, gameMode);
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
        // 방 제목을 비워뒀으면 "Room - 방장 닉네임" — 초대 코드는 목록·채팅에 드러나지 않게 쓰지 않는다.
        if (publicRoom && title.isEmpty()) {
            title = "Room - " + client.player.getName().getString();
        }
        // 새로 여는 방이라 게스트는 아직 없다(startCustomRoom이 방장만 남을 때까지
        // 기다렸다가 여기로 옴) — activeGuestCount 필드 선언부 주석 참고.
        activeGuestCount = 0;
        if (publicRoom) {
            WebRtcBridge.publishPublicRoom(code, title, client.player.getName().getString(),
                    client.player.getUUID().toString(), activeGuestCount + 1, maxPlayers);
        }

        activeInviteCode = code;
        activeGameMode = gameMode;
        activeAllowCheats = allowCheats;
        activePublicRoom = publicRoom;
        activeTitle = title;
        activeChannel = kfc.udp.client.webrtc.P2PConfig.getChannelKey();
        kfcudp$refreshHostTabList(server, client.player.getUUID());

        // 초대 코드 자체는 채팅에 안 띄운다(화면 공유·방송으로 새지 않게) — 누르면 클립보드로만 복사된다.
        MutableComponent prefix   = Component.translatable("instant-p2p.msg.invite_prefix");
        MutableComponent copyText = Component.translatable("instant-p2p.msg.invite_copy").setStyle(Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(code)));

        client.player.sendSystemMessage(Component.empty().append(prefix).append(copyText));
        if (publicRoom) kfcudp$sendPublicRoomNotice(client, title);

        client.setScreenAndShow(null);
        client.mouseHandler.grabMouse();
    }
    *///?} else {
    private static void openRoomNow(MinecraftClient client,
                                     GameMode gameMode, int maxPlayers, boolean allowCheats,
                                     boolean publicRoom, String title) {
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
        // 방 제목을 비워뒀으면 "Room - 방장 닉네임" — 초대 코드는 목록·채팅에 드러나지 않게 쓰지 않는다.
        if (publicRoom && title.isEmpty()) {
            title = "Room - " + client.player.getName().getString();
        }
        // 새로 여는 방이라 게스트는 아직 없다(startCustomRoom이 방장만 남을 때까지
        // 기다렸다가 여기로 옴) — activeGuestCount 필드 선언부 주석 참고.
        activeGuestCount = 0;
        if (publicRoom) {
            WebRtcBridge.publishPublicRoom(code, title, client.player.getName().getString(),
                    client.player.getUuid().toString(), activeGuestCount + 1, maxPlayers);
        }

        activeInviteCode = code;
        activeGameMode = gameMode;
        activeAllowCheats = allowCheats;
        activePublicRoom = publicRoom;
        activeTitle = title;
        activeChannel = kfc.udp.client.webrtc.P2PConfig.getChannelKey();
        kfcudp$refreshHostTabList(server, client.player.getUuid());

        // 초대 코드 자체는 채팅에 안 띄운다(화면 공유·방송으로 새지 않게) — 누르면 클립보드로만 복사된다.
        MutableText prefix   = Text.translatable("instant-p2p.msg.invite_prefix");
        MutableText copyText = Text.translatable("instant-p2p.msg.invite_copy").setStyle(kfcudp$copyOnClick(Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withUnderline(true), code));

        client.player.sendMessage(Text.empty().append(prefix).append(copyText), false);
        if (publicRoom) kfcudp$sendPublicRoomNotice(client, title);

        client.setScreen(null);
        client.mouse.lockCursor();
    }
    //?}

    /**
     * CustomRoomScreen에서 이미 켜진 방을 "적용" 누를 때 호출 — startCustomRoom과 달리
     * 새 초대 코드를 발급하거나 방을 닫았다 다시 열지 않는다. gameMode/maxPlayers/
     * allowCheats는 openRoomNow가 "바닐라 Open to LAN으로 이미 열려 있는 경우" 쓰는
     * 것과 똑같은 값-갱신 경로를 그대로 재사용한다 — 핫스왑 중인 방도 결국 그 상태와
     * 동일(이미 열려 있음)하기 때문. publicRoom/제목은 같은 코드에 새로 태그만 다시
     * 걸도록 일단 내렸다 필요하면 다시 올린다(unpublish 후 publish) — on/off 전환과
     * 제목 변경 둘 다 이 순서 하나로 처리된다.
     */
    //? if >=26.1 {
    /*public static boolean applyRoomSettings(Minecraft client,
                                       GameType gameMode, int maxPlayers, boolean allowCheats,
                                       boolean publicRoom, String title, boolean allowBroadcast) {
        if (activeInviteCode == null || client.player == null) return false;
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return false;

        int oldMaxPlayers = activeMaxPlayers;
        activeMaxPlayers = maxPlayers;
        P2PBanManager.setRoomMaxPlayers(maxPlayers);

        // 26.2+는 kfcudp$applyGuestCommandAccess가 setGuestCommandAccess/setCommandsAllowedForOtherPlayers를
        // 부르는데, 그게 IntegratedServerMaxPlayersMixin의 동기화 훅(kfcudp$syncGuestCheats)을 건드려서
        // activeAllowCheats가 이 호출 도중에 이미 새 값으로 바뀐다 — 아래 "바뀐 설정" 비교보다 먼저
        // 비교값을 떠 둬야 "치트 허용 로그가 안 뜬다" 버그가 안 생긴다.
        boolean allowCheatsChanged = allowCheats != activeAllowCheats;
        kfcudp$applyGuestCommandAccess(server, allowCheats);
        kfcudp$setGuestGameMode(server, gameMode);

        boolean allowBroadcastChanged = allowBroadcast != kfc.udp.client.webrtc.P2PConfig.isAllowBroadcast();
        kfc.udp.client.webrtc.P2PConfig.setAllowBroadcast(allowBroadcast);

        server.execute(() -> server.execute(() -> {
            P2PBanManager.reregisterToDispatcher(server);
            P2PWhitelistManager.reregisterToDispatcher(server);
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(sp);
                // forcedGameMode는 새로 들어오는 접속자에게만 적용되므로, 이미 접속
                // 중인 게스트는 따로 즉시 바꿔줘야 한다. 방장 본인은 건드리지 않는다
                // (호스트는 자기 세이브의 원래 게임모드를 그대로 유지).
                if (!P2PBanManager.isHost(server, sp)) {
                    sp.setGameMode(gameMode);
                    // 정원(N/M) 마커는 원래 JOIN 시점에만 보냈다 — 이미 접속 중인
                    // 게스트는 방장이 정원을 바꿔도 그 사실을 알 방법이 없어서, ESC
                    // 화면 인원 표시가 재접속해야만 바뀌었다. 바뀌었을 때만 다시 보낸다.
                    if (maxPlayers != oldMaxPlayers) {
                        sp.sendSystemMessage(Component.literal(capacityMarker(maxPlayers)), false);
                    }
                }
            }
        }));

        // 관련 없는 옵션(치트/관리 명령어)이 바뀔 때마다 공개 목록을 매번 내렸다
        // 다시 올리면 목록에서 방이 깜빡이고 시그널링에도 괜한 부하가 간다 —
        // 공개 여부/제목/정원이 실제로 달라질 때만 unpublish/publish한다. 정원은
        // (제목과 마찬가지로) 자기 전용 "적용" 버튼을 눌러야만 여기로 들어오므로
        // 스팸 걱정 없이 디바운스 없이 바로 반영한다 — 예전엔 인원 변경과 같은
        // 디바운스(최대 5초) 경로를 타서 제목은 즉시 바뀌는데 정원만 늦게(또는
        // 그 사이 또 바뀌면 아예 안) 반영되는 것처럼 보였다.
        if (publicRoom && title.isEmpty()) {
            title = "Room - " + client.player.getName().getString();
        }
        // 채널도 제목/정원과 마찬가지로 "적용" 버튼 전용 경로로만 여기 들어오므로
        // 스팸 걱정 없이 바로 반영한다 — 예전엔 채널만 바뀐 경우를 안 쳐서, 초대 코드를
        // 재생성(방 재시작)해야만 새 채널이 실제 공지에 반영되는 것처럼 보였다.
        String channel = kfc.udp.client.webrtc.P2PConfig.getChannelKey();
        boolean publicChanged = publicRoom != activePublicRoom
                || (publicRoom && !title.equals(activeTitle))
                || (publicRoom && maxPlayers != oldMaxPlayers)
                || (publicRoom && !channel.equals(activeChannel))
                // 방송 허용만 바뀐 경우도 여기서 같이 재공지한다 — 안 그러면 태그가 안 바뀐 옛 값 그대로
                // 남아서, 다음 접속자 입/퇴장으로 저절로 재공지될 때까지 접속자 목록의 방송 필터가
                // 낡은 상태를 계속 보여준다.
                || (publicRoom && allowBroadcastChanged);
        if (publicChanged) {
            if (publicRoom) {
                // publishPublicRoom(=PublicRoomAnnouncer.publish)이 방 코드·채널이 그대로면 재접속 없이
                // 메시지만 보낸다 — 여기서 먼저 내릴 필요가 없다(제목·정원만 바뀐 흔한 경우가 이렇게 된다).
                // 여기서도 activeGuestCount를 쓴다 — 필드 선언부 주석 참고: GUI 스레드에서
                // 서버 스레드가 만지는 플레이어 목록을 직접 스냅샷하는 건 안전하지 않다.
                WebRtcBridge.publishPublicRoom(activeInviteCode, title, client.player.getName().getString(),
                        client.player.getUUID().toString(), activeGuestCount + 1, maxPlayers);
                // 공개를 새로 켰을 때 방장에게 목록에 뭐로 보이는지 알린다 — 제목 변경은 아래에서 방 전원에게 알린다.
                if (!activePublicRoom) kfcudp$sendPublicRoomNotice(client, title);
            } else {
                WebRtcBridge.unpublishPublicRoom();
            }
        }

        // 바뀐 방 설정을 방 전원(방장 포함)에게 채팅으로 알린다. 채널·중계 강제는 방장 개인 설정이라 빼고(채널은
        // 가리기 기능이 있을 만큼 드러나면 안 되는 값), 제목은 따로 한 줄. 보내는 건 서버 스레드에서.
        java.util.List<Component> changes = new java.util.ArrayList<>();
        if (gameMode != activeGameMode) {
            changes.add(Component.translatable("instant-p2p.msg.setting.game_mode", gameMode.getShortDisplayName()));
        }
        if (maxPlayers != oldMaxPlayers) {
            changes.add(Component.translatable("instant-p2p.msg.setting.max_players", maxPlayers));
        }
        if (allowCheatsChanged) {
            changes.add(Component.translatable("instant-p2p.msg.setting.allow_commands",
                    Component.translatable(allowCheats ? "options.on" : "options.off")));
        }
        if (publicRoom != activePublicRoom) {
            changes.add(Component.translatable("instant-p2p.msg.setting.public",
                    Component.translatable(publicRoom ? "options.on" : "options.off")));
        }
        if (allowBroadcastChanged) {
            changes.add(Component.translatable("instant-p2p.msg.setting.allow_broadcast",
                    Component.translatable(allowBroadcast ? "options.on" : "options.off")));
        }
        // 제목은 방을 새로 공개할 때도 알린다 — 공개하는 순간이 곧 목록에 그 제목이 처음 걸리는 때라서.
        boolean titleChanged = publicRoom && (!activePublicRoom || !title.equals(activeTitle));
        MutableComponent joined = Component.empty();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(changes.get(i));
        }
        Component settingsMsg = changes.isEmpty() ? null : Component.translatable("instant-p2p.msg.room_settings_changed", joined);
        Component titleMsg = titleChanged ? Component.translatable("instant-p2p.msg.room_title_changed",
                Component.literal(title).withStyle(ChatFormatting.RESET)) : null;
        if (settingsMsg != null || titleMsg != null) {
            server.execute(() -> {
                if (settingsMsg != null) server.getPlayerList().broadcastSystemMessage(settingsMsg, false);
                if (titleMsg != null) server.getPlayerList().broadcastSystemMessage(titleMsg, false);
            });
        }

        activeGameMode = gameMode;
        activeAllowCheats = allowCheats;
        activePublicRoom = publicRoom;
        activeTitle = title;
        activeChannel = channel;
        return settingsMsg != null || titleMsg != null;
    }

    *///?} else {
    public static boolean applyRoomSettings(MinecraftClient client,
                                       GameMode gameMode, int maxPlayers, boolean allowCheats,
                                       boolean publicRoom, String title, boolean allowBroadcast) {
        if (activeInviteCode == null || client.player == null) return false;
        IntegratedServer server = client.getServer();
        if (server == null) return false;

        int oldMaxPlayers = activeMaxPlayers;
        activeMaxPlayers = maxPlayers;
        P2PBanManager.setRoomMaxPlayers(maxPlayers);

        boolean allowBroadcastChanged = allowBroadcast != kfc.udp.client.webrtc.P2PConfig.isAllowBroadcast();
        kfc.udp.client.webrtc.P2PConfig.setAllowBroadcast(allowBroadcast);

        server.getPlayerManager().setCheatsAllowed(allowCheats);
        ((kfc.udp.client.mixin.IntegratedServerAccessor) server)
                .kfcudp$setForcedGameMode(gameMode);
        //? if <1.21.9 {
        ((kfc.udp.client.mixin.PlayerManagerAccessor) server.getPlayerManager())
                .kfcudp$setMaxPlayers(maxPlayers);
        //?}

        server.execute(() -> server.execute(() -> {
            P2PBanManager.reregisterToDispatcher(server);
            P2PWhitelistManager.reregisterToDispatcher(server);
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                server.getCommandManager().sendCommandTree(sp);
                // forcedGameMode는 새로 들어오는 접속자에게만 적용되므로, 이미 접속
                // 중인 게스트는 따로 즉시 바꿔줘야 한다. 방장 본인은 건드리지 않는다
                // (호스트는 자기 세이브의 원래 게임모드를 그대로 유지).
                if (!P2PBanManager.isHost(server, sp)) {
                    sp.changeGameMode(gameMode);
                    // 정원(N/M) 마커는 원래 JOIN 시점에만 보냈다 — 이미 접속 중인
                    // 게스트는 방장이 정원을 바꿔도 그 사실을 알 방법이 없어서, ESC
                    // 화면 인원 표시가 재접속해야만 바뀌었다. 바뀌었을 때만 다시 보낸다.
                    if (maxPlayers != oldMaxPlayers) {
                        sp.sendMessage(Text.literal(capacityMarker(maxPlayers)), false);
                    }
                }
            }
        }));

        // 관련 없는 옵션(치트/관리 명령어)이 바뀔 때마다 공개 목록을 매번 내렸다
        // 다시 올리면 목록에서 방이 깜빡이고 시그널링에도 괜한 부하가 간다 —
        // 공개 여부/제목/정원이 실제로 달라질 때만 unpublish/publish한다. 정원은
        // (제목과 마찬가지로) 자기 전용 "적용" 버튼을 눌러야만 여기로 들어오므로
        // 스팸 걱정 없이 디바운스 없이 바로 반영한다 — 예전엔 인원 변경과 같은
        // 디바운스(최대 5초) 경로를 타서 제목은 즉시 바뀌는데 정원만 늦게(또는
        // 그 사이 또 바뀌면 아예 안) 반영되는 것처럼 보였다.
        if (publicRoom && title.isEmpty()) {
            title = "Room - " + client.player.getName().getString();
        }
        // 채널도 제목/정원과 마찬가지로 "적용" 버튼 전용 경로로만 여기 들어오므로
        // 스팸 걱정 없이 바로 반영한다 — 예전엔 채널만 바뀐 경우를 안 쳐서, 초대 코드를
        // 재생성(방 재시작)해야만 새 채널이 실제 공지에 반영되는 것처럼 보였다.
        String channel = kfc.udp.client.webrtc.P2PConfig.getChannelKey();
        boolean publicChanged = publicRoom != activePublicRoom
                || (publicRoom && !title.equals(activeTitle))
                || (publicRoom && maxPlayers != oldMaxPlayers)
                || (publicRoom && !channel.equals(activeChannel))
                // 방송 허용만 바뀐 경우도 여기서 같이 재공지한다 — 안 그러면 태그가 안 바뀐 옛 값 그대로
                // 남아서, 다음 접속자 입/퇴장으로 저절로 재공지될 때까지 접속자 목록의 방송 필터가
                // 낡은 상태를 계속 보여준다.
                || (publicRoom && allowBroadcastChanged);
        if (publicChanged) {
            if (publicRoom) {
                // publishPublicRoom(=PublicRoomAnnouncer.publish)이 방 코드·채널이 그대로면 재접속 없이
                // 메시지만 보낸다 — 여기서 먼저 내릴 필요가 없다(제목·정원만 바뀐 흔한 경우가 이렇게 된다).
                // 여기서도 activeGuestCount를 쓴다 — 필드 선언부 주석 참고: GUI 스레드에서
                // 서버 스레드가 만지는 플레이어 목록을 직접 스냅샷하는 건 안전하지 않다.
                WebRtcBridge.publishPublicRoom(activeInviteCode, title, client.player.getName().getString(),
                        client.player.getUuid().toString(), activeGuestCount + 1, maxPlayers);
                // 공개를 새로 켰을 때 방장에게 목록에 뭐로 보이는지 알린다 — 제목 변경은 아래에서 방 전원에게 알린다.
                if (!activePublicRoom) kfcudp$sendPublicRoomNotice(client, title);
            } else {
                WebRtcBridge.unpublishPublicRoom();
            }
        }

        // 바뀐 방 설정을 방 전원(방장 포함)에게 채팅으로 알린다. 채널·중계 강제는 방장 개인 설정이라 빼고(채널은
        // 가리기 기능이 있을 만큼 드러나면 안 되는 값), 제목은 따로 한 줄. 보내는 건 서버 스레드에서.
        java.util.List<Text> changes = new java.util.ArrayList<>();
        if (gameMode != activeGameMode) {
            changes.add(Text.translatable("instant-p2p.msg.setting.game_mode", gameMode.getSimpleTranslatableName()));
        }
        if (maxPlayers != oldMaxPlayers) {
            changes.add(Text.translatable("instant-p2p.msg.setting.max_players", maxPlayers));
        }
        if (allowCheats != activeAllowCheats) {
            changes.add(Text.translatable("instant-p2p.msg.setting.allow_commands",
                    Text.translatable(allowCheats ? "options.on" : "options.off")));
        }
        if (publicRoom != activePublicRoom) {
            changes.add(Text.translatable("instant-p2p.msg.setting.public",
                    Text.translatable(publicRoom ? "options.on" : "options.off")));
        }
        if (allowBroadcastChanged) {
            changes.add(Text.translatable("instant-p2p.msg.setting.allow_broadcast",
                    Text.translatable(allowBroadcast ? "options.on" : "options.off")));
        }
        // 제목은 방을 새로 공개할 때도 알린다 — 공개하는 순간이 곧 목록에 그 제목이 처음 걸리는 때라서.
        boolean titleChanged = publicRoom && (!activePublicRoom || !title.equals(activeTitle));
        MutableText joined = Text.empty();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(changes.get(i));
        }
        Text settingsMsg = changes.isEmpty() ? null : Text.translatable("instant-p2p.msg.room_settings_changed", joined);
        Text titleMsg = titleChanged ? Text.translatable("instant-p2p.msg.room_title_changed",
                Text.literal(title).formatted(Formatting.RESET)) : null;
        if (settingsMsg != null || titleMsg != null) {
            server.execute(() -> {
                if (settingsMsg != null) server.getPlayerManager().broadcast(settingsMsg, false);
                if (titleMsg != null) server.getPlayerManager().broadcast(titleMsg, false);
            });
        }

        activeGameMode = gameMode;
        activeAllowCheats = allowCheats;
        activePublicRoom = publicRoom;
        activeTitle = title;
        activeChannel = channel;
        return settingsMsg != null || titleMsg != null;
    }

    //?}

    // title을 그냥 %s로 넣으면 원문 앞쪽 §7(회색) 서식이 그대로 이어져서 제목까지 회색으로
    // 물든다 — 명시적으로 RESET을 줘서 끊어준다. 닫는 괄호는 제목 뒤라 서식이 끊겨 있으므로
    // 원문에서 §7을 다시 줘야 회색이 된다.
    //? if >=26.1 {
    /*private static void kfcudp$sendPublicRoomNotice(Minecraft client, String title) {
        client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.public_room_notice",
                Component.literal(title).withStyle(ChatFormatting.RESET)));
    }
    *///?} else {
    private static void kfcudp$sendPublicRoomNotice(MinecraftClient client, String title) {
        client.player.sendMessage(Text.translatable("instant-p2p.msg.public_room_notice",
                Text.literal(title).formatted(Formatting.RESET)), false);
    }
    //?}

    private static void cancelInvite() {
        cancelInvite(null, false);
    }

    /**
     * 일시정지 화면(ESC)에 방장/접속자용 위젯(Custom Room 버튼, 초대 코드 복사,
     * 인원 표시, 방 닫기)을 그려 넣는다 — ScreenEvents.AFTER_INIT에서도 부르고,
     * "방 닫기" 버튼 클릭 직후에도 같은 화면 인스턴스에 대해 다시 불러서 즉시
     * 갱신한다(재시작/재오픈 없이 그 자리에서 "방 만들기 전" 상태로 되돌아가게).
     * <p>
     * 각 줄의 세로 위치는 앞줄이 실제로 추가됐는지에 따라 누적(nextY)해서
     * 정하지, 고정 슬롯을 미리 다 확보해두지 않는다 — 예전엔 인원 표시 줄이
     * 텍스트 한 줄뿐인데도 버튼 한 줄만큼(22px) 자리를 차지해서, 그 아래 "방
     * 닫기" 버튼이 필요 이상으로 아래로 밀려 화면 중앙의 바닐라 일시정지 메뉴
     * 버튼들과 겹치는 문제가 있었다.
     */
    //? if >=26.1 {
    /*private static void refreshPauseMenuWidgets(Minecraft client, Screen screen) {
        java.util.List<net.minecraft.client.gui.components.AbstractWidget> previous = kfcudp$injectedWidgets.remove(screen);
        if (previous != null) Screens.getWidgets(screen).removeAll(previous);

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
        int btnX = screen.width - btnW - 10;
        int nextY = 10;
        int rowGap = btnH + 2;

        java.util.List<net.minecraft.client.gui.components.AbstractWidget> added = new java.util.ArrayList<>();

        if (isHost) {
            // 방장은 추방 대상이 될 수 없다(ExpelManager 클래스 주석 참고 — 방장을 내보낼 방법이
            // 없어서 애초에 시도 자체를 안 한다) — 그래서 이 버튼을 잠글 이유가 없다.
            Button customRoomBtn = Button.builder(
                            Component.translatable(activeInviteCode != null
                                    ? "instant-p2p.custom_room.edit_title"
                                    : "instant-p2p.custom_room.title"),
                            button -> kfcudp$openWithSafetyWarning(client, screen, new CustomRoomScreen(screen))
                    ).bounds(btnX, nextY, btnW, btnH).build();
            Screens.getWidgets(screen).add(customRoomBtn);
            added.add(customRoomBtn);
            nextY += rowGap;
        } else {
            // 접속자가 제작자·서포터면 방장의 "방 설정 변경" 자리에 역할을 표시한다 — 본인 화면에만 보인다.
            java.util.UUID me = client.player == null ? null : client.player.getUUID();
            boolean streamer = me != null && kfc.udp.client.webrtc.Roles.isStreamer(me);
            if (me != null && (DevBadge.hasBadge(me) || streamer)) {
                boolean dev = DevBadge.isDev(me);
                String roleKey = dev ? "instant-p2p.pause.role_dev" : streamer ? "instant-p2p.pause.role_streamer" : "instant-p2p.pause.role_supporter";
                ChatFormatting roleColor = dev ? ChatFormatting.AQUA : streamer ? ChatFormatting.RED : ChatFormatting.GOLD;
                net.minecraft.client.gui.components.StringWidget roleText =
                        new net.minecraft.client.gui.components.StringWidget(
                                Component.translatable(roleKey).withStyle(roleColor),
                                client.font);
                roleText.setX(btnX + (btnW - roleText.getWidth()) / 2);
                roleText.setY(nextY + (btnH - 9) / 2); // 방장의 버튼 한 줄 높이 가운데
                // 개발자·서포터는 인원수 무시 특혜 설명까지 같이(특혜가 꺼져 있으면 그 줄은 안 붙인다 —
                // 안내와 실제가 달라지면 안 된다), 셋 다 공통으로 추방(ExpelManager) 안내를 붙인다.
                Component tooltipText = Component.translatable("instant-p2p.pause.expel_tooltip");
                if (DevBadge.hasPerk(me)) {
                    tooltipText = Component.translatable("instant-p2p.pause.role_tooltip")
                            .copy().append(Component.literal("\n")).append(tooltipText);
                }
                roleText.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltipText));
                Screens.getWidgets(screen).add(roleText);
                added.add(roleText);
            }
            nextY += rowGap; // 접속자도 인원 표시가 방장(방 설정 변경 아래)과 같은 높이에 오게
        }

        // 인원 표시(N/M) — 버튼이 아니라 작은 흰색 그림자 텍스트로 표시한다. 방장은 직접 아는 값,
        // 접속자는 JOIN 시점에 몰래 받아 캐시해 둔 값(guestRoomMaxPlayers)을 쓴다.
        Integer current = null, max = null;
        if (isHost && activeInviteCode != null) {
            IntegratedServer server = client.getSingleplayerServer();
            if (server != null) {
                current = P2PBanManager.countedPlayers(server);
                max = activeMaxPlayers;
            }
        } else if (isGuestSession && guestRoomMaxPlayers > 0 && client.getConnection() != null) {
            // 개발자·서포터도 유령 취급하지 않고 그대로 센다(P2PBanManager.countedPlayers와 같은 규칙).
            current = client.getConnection().getOnlinePlayers().size();
            max = guestRoomMaxPlayers;
        }
        if (current != null) {
            net.minecraft.client.gui.components.StringWidget countText =
                    new net.minecraft.client.gui.components.StringWidget(
                            Component.translatable("instant-p2p.pause.player_count", current, max),
                            client.font);
            countText.setX(btnX + (btnW - countText.getWidth()) / 2);
            countText.setY(nextY + 2);
            Screens.getWidgets(screen).add(countText);
            added.add(countText);
            nextY += 12; // 텍스트 한 줄(9px) + 여백 — 버튼 한 줄(22px)보다 훨씬 얇다
        }

        // 플레이어 차단 — 인원수 바로 아래(호스트·접속자 공통). 방장·접속자 모두, 다른 사람이 들어올 수
        // 있는 세션일 때만. 같은 월드에 있는 사람을 골라 차단/해제한다(BlockedPlayersScreen 접속자 모드)
        // — 채팅이 가려지고, 앞으로 내가 여는 방에도 못 들어온다. 목록엔 지금 접속한 사람 다음에 이미
        // 차단해 나간(밴된) 사람도 이어서 나온다(BlockedPlayersScreen 클래스 주석 참고) — 버튼을 두 개로
        // 나누는 대신 한 화면에서 다 보이게.
        if (activeInviteCode != null || isGuestSession) {
            Button blockPlayersBtn = Button.builder(
                            Component.translatable("instant-p2p.pause.block_players"),
                            button -> client.setScreenAndShow(new kfc.udp.client.gui.BlockedPlayersScreen(screen, true))
                    ).bounds(btnX, nextY, btnW, btnH).build();
            Screens.getWidgets(screen).add(blockPlayersBtn);
            added.add(blockPlayersBtn);
            nextY += rowGap;
        }

        // 초대 코드 다시 복사 — 이제 플레이어 차단 버튼 자리를 이어받는다(호스트만, 코드 자체는 안 보여준다).
        // 접속자의 강퇴·임시밴 권한은 이제 별도 화면 없이 "플레이어 차단"(위에서 이미 추가됨) 안에
        // 통합돼 있다(BlockedPlayersScreen 클래스 주석 참고) — 그래서 여기선 호스트일 때만 버튼을 둔다.
        if (isHost && activeInviteCode != null) {
            String code = activeInviteCode;
            Button codeBtn = Button.builder(
                            Component.translatable("instant-p2p.pause.copy_invite").withStyle(ChatFormatting.YELLOW),
                            button -> client.keyboardHandler.setClipboard(code)
                    ).bounds(btnX, nextY, btnW, btnH).build();
            Screens.getWidgets(screen).add(codeBtn);
            added.add(codeBtn);
            nextY += rowGap;
        }

        kfcudp$injectedWidgets.put(screen, added);
    }
    *///?} else {
    private static void refreshPauseMenuWidgets(MinecraftClient client, Screen screen) {
        java.util.List<net.minecraft.client.gui.widget.ClickableWidget> previous = kfcudp$injectedWidgets.remove(screen);
        if (previous != null) Screens.getButtons(screen).removeAll(previous);

        if (!(screen instanceof GameMenuScreen gameMenu)) return;
        if (!gameMenu.shouldShowMenu()) return;

        boolean isHost = client.isInSingleplayer();
        boolean isGuestSession = !isHost && WebRtcBridge.getActiveConnectionUsesRelay() != null;
        if (!isHost && !isGuestSession) return;

        int btnW = 100;
        int btnH = 20;
        int btnX = screen.width - btnW - 10;
        int nextY = 10;
        int rowGap = btnH + 2;

        java.util.List<net.minecraft.client.gui.widget.ClickableWidget> added = new java.util.ArrayList<>();

        if (isHost) {
            // 방장은 추방 대상이 될 수 없다(ExpelManager 클래스 주석 참고 — 방장을 내보낼 방법이
            // 없어서 애초에 시도 자체를 안 한다) — 그래서 이 버튼을 잠글 이유가 없다.
            ButtonWidget customRoomBtn = ButtonWidget.builder(
                            Text.translatable(activeInviteCode != null
                                    ? "instant-p2p.custom_room.edit_title"
                                    : "instant-p2p.custom_room.title"),
                            button -> kfcudp$openWithSafetyWarning(client, screen, new CustomRoomScreen(screen))
                    ).dimensions(btnX, nextY, btnW, btnH).build();
            Screens.getButtons(screen).add(customRoomBtn);
            added.add(customRoomBtn);
            nextY += rowGap;
        } else {
            // 접속자가 제작자·서포터면 방장의 "방 설정 변경" 자리에 역할을 표시한다 — 본인 화면에만 보인다.
            java.util.UUID me = client.player == null ? null : client.player.getUuid();
            boolean streamer = me != null && kfc.udp.client.webrtc.Roles.isStreamer(me);
            if (me != null && (DevBadge.hasBadge(me) || streamer)) {
                boolean dev = DevBadge.isDev(me);
                String roleKey = dev ? "instant-p2p.pause.role_dev" : streamer ? "instant-p2p.pause.role_streamer" : "instant-p2p.pause.role_supporter";
                Formatting roleColor = dev ? Formatting.AQUA : streamer ? Formatting.RED : Formatting.GOLD;
                net.minecraft.client.gui.widget.TextWidget roleText =
                        new net.minecraft.client.gui.widget.TextWidget(
                                Text.translatable(roleKey).formatted(roleColor),
                                client.textRenderer);
                roleText.setX(btnX + (btnW - roleText.getWidth()) / 2);
                roleText.setY(nextY + (btnH - 9) / 2); // 방장의 버튼 한 줄 높이 가운데
                // 개발자·서포터는 인원수 무시 특혜 설명까지 같이(특혜가 꺼져 있으면 그 줄은 안 붙인다 —
                // 안내와 실제가 달라지면 안 된다), 셋 다 공통으로 추방(ExpelManager) 안내를 붙인다.
                Text tooltipText = Text.translatable("instant-p2p.pause.expel_tooltip");
                if (DevBadge.hasPerk(me)) {
                    tooltipText = Text.translatable("instant-p2p.pause.role_tooltip")
                            .copy().append(Text.literal("\n")).append(tooltipText);
                }
                roleText.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(tooltipText));
                Screens.getButtons(screen).add(roleText);
                added.add(roleText);
            }
            nextY += rowGap; // 접속자도 인원 표시가 방장(방 설정 변경 아래)과 같은 높이에 오게
        }

        Integer current = null, max = null;
        if (isHost && activeInviteCode != null) {
            IntegratedServer server = client.getServer();
            if (server != null) {
                current = P2PBanManager.countedPlayers(server);
                max = activeMaxPlayers;
            }
        } else if (isGuestSession && guestRoomMaxPlayers > 0 && client.getNetworkHandler() != null) {
            // 개발자·서포터도 유령 취급하지 않고 그대로 센다(P2PBanManager.countedPlayers와 같은 규칙).
            current = client.getNetworkHandler().getPlayerList().size();
            max = guestRoomMaxPlayers;
        }
        if (current != null) {
            net.minecraft.client.gui.widget.TextWidget countText =
                    new net.minecraft.client.gui.widget.TextWidget(
                            Text.translatable("instant-p2p.pause.player_count", current, max),
                            client.textRenderer);
            countText.setX(btnX + (btnW - countText.getWidth()) / 2);
            countText.setY(nextY + 2);
            Screens.getButtons(screen).add(countText);
            added.add(countText);
            nextY += 12;
        }

        // 플레이어 차단 — 인원수 바로 아래(호스트·접속자 공통). 방장·접속자 모두, 다른 사람이 들어올 수
        // 있는 세션일 때만. 같은 월드에 있는 사람을 골라 차단/해제한다(BlockedPlayersScreen 접속자 모드)
        // — 채팅이 가려지고, 앞으로 내가 여는 방에도 못 들어온다. 목록엔 지금 접속한 사람 다음에 이미
        // 차단해 나간(밴된) 사람도 이어서 나온다(BlockedPlayersScreen 클래스 주석 참고) — 버튼을 두 개로
        // 나누는 대신 한 화면에서 다 보이게.
        if (activeInviteCode != null || isGuestSession) {
            ButtonWidget blockPlayersBtn = ButtonWidget.builder(
                            Text.translatable("instant-p2p.pause.block_players"),
                            button -> client.setScreen(new kfc.udp.client.gui.BlockedPlayersScreen(screen, true))
                    ).dimensions(btnX, nextY, btnW, btnH).build();
            Screens.getButtons(screen).add(blockPlayersBtn);
            added.add(blockPlayersBtn);
            nextY += rowGap;
        }

        // 초대 코드 다시 복사 — 이제 플레이어 차단 버튼 자리를 이어받는다(호스트만, 코드 자체는 안 보여준다).
        // 접속자의 강퇴·임시밴 권한은 이제 별도 화면 없이 "플레이어 차단"(위에서 이미 추가됨) 안에
        // 통합돼 있다(BlockedPlayersScreen 클래스 주석 참고) — 그래서 여기선 호스트일 때만 버튼을 둔다.
        if (isHost && activeInviteCode != null) {
            String code = activeInviteCode;
            ButtonWidget codeBtn = ButtonWidget.builder(
                            Text.translatable("instant-p2p.pause.copy_invite").formatted(Formatting.YELLOW),
                            button -> client.keyboard.setClipboard(code)
                    ).dimensions(btnX, nextY, btnW, btnH).build();
            Screens.getButtons(screen).add(codeBtn);
            added.add(codeBtn);
            nextY += rowGap;
        }

        kfcudp$injectedWidgets.put(screen, added);
    }
    //?}

    /**
     * 방 설정 변경 화면(CustomRoomScreen)의 "방 닫기" — 방을 완전히 닫고 알린 뒤 곧장 게임 화면으로 돌아간다
     * (초대코드 재생성·설정 적용과 같은 흐름). 다음에 ESC를 열면 새 일시정지 화면이라 버튼도 새로 그려진다.
     * <p>
     * kickBlockedPlayer: 방장이 "플레이어 차단"(BlockedPlayersScreen 접속자 모드)으로 차단하면 지금 방에 있는
     * 그 사람도 바로 내보낸다 — 밴 목록엔 이미 들어가 있어 다시 들어오려 해도 로그인 단계에서 막힌다.
     * 방장이 아니면(접속자끼리 차단) 아무 것도 안 한다.
     */
    //? if >=26.1 {
    /*public static void closeRoomFromMenu() {
        Minecraft client = Minecraft.getInstance();
        closeRoomCompletely(client);
        if (client.player != null) {
            client.player.sendSystemMessage(Component.translatable("instant-p2p.msg.room_closed"));
        }
        client.setScreenAndShow(null);
        client.mouseHandler.grabMouse();
    }

    public static void kickBlockedPlayer(String uuid) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || activeInviteCode == null) return;
        java.util.UUID id = java.util.UUID.fromString(uuid);
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null && !P2PBanManager.isHost(server, sp)) {
                sp.connection.disconnect(Component.translatable("instant-p2p.msg.kicked_blocked"));
            }
        });
    }
    *///?} else {
    public static void closeRoomFromMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        closeRoomCompletely(client);
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("instant-p2p.msg.room_closed"), false);
        }
        client.setScreen(null);
        client.mouse.lockCursor();
    }

    public static void kickBlockedPlayer(String uuid) {
        IntegratedServer server = MinecraftClient.getInstance().getServer();
        if (server == null || activeInviteCode == null) return;
        java.util.UUID id = java.util.UUID.fromString(uuid);
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
            if (sp != null && !P2PBanManager.isHost(server, sp)) {
                sp.networkHandler.disconnect(Text.translatable("instant-p2p.msg.kicked_blocked"));
            }
        });
    }
    //?}

    // "Open to LAN" 상태(isRemote/isPublished)와 LanServerPinger 브로드캐스트를
    // 정리한다 — closeRoomCompletely가 부른다. closeRoomCompletely 자기 자신의
    // Mojang/Yarn 분기 "안"에다 26.2/26.1/Yarn 3단 분기를 또 넣으면(예전에
    // 시도해봤다가 컴파일이 통째로 깨졌다) 바깥 Mojang 주석 블록(/* ... */) 안에서
    // 안쪽 분기가 쓰는 또 다른 /* ... */가 Java는 블록 주석을 중첩 못 해서 바깥
    // 주석을 도중에 끊어버린다 — 그래서 이 3단 분기는 따로 최상위 메서드로 뺐다.
    //? if >=26.2 {
    /*private static void closeLanServer(IntegratedServer server) {
        // 26.2는 이 전체(pinger 정지+null화, 포트 -1, MultiplayerScope.OFF, 명령어
        // 권한 재동기화)를 처리하는 teardownPublishedState()가 바닐라에 있다.
        ((kfc.udp.client.mixin.IntegratedServerAccessor) server).kfcudp$teardownPublishedState();
    }
    *///?} else {
    //? if >=26.1 <26.2 {
    /*private static void closeLanServer(IntegratedServer server) {
        kfc.udp.client.mixin.IntegratedServerAccessor accessor =
                (kfc.udp.client.mixin.IntegratedServerAccessor) server;
        net.minecraft.client.server.LanServerPinger pinger = accessor.kfcudp$getLanPinger();
        if (pinger != null) pinger.interrupt();
        accessor.kfcudp$setLanPinger(null);
        accessor.kfcudp$setLanPort(-1);
    }
    *///?} else {
    private static void closeLanServer(IntegratedServer server) {
        kfc.udp.client.mixin.IntegratedServerAccessor accessor =
                (kfc.udp.client.mixin.IntegratedServerAccessor) server;
        net.minecraft.client.network.LanServerPinger pinger = accessor.kfcudp$getLanPinger();
        if (pinger != null) pinger.interrupt();
        accessor.kfcudp$setLanPinger(null);
        accessor.kfcudp$setLanPort(-1);
    }
    //?}
    //?}

    /** 방을 완전히 닫는다(일시정지 화면의 "방 닫기" 버튼) — 게스트를 내보내고
     * WebRTC 등록(시그널링/공개 목록)을 제거하는 것(cancelInvite가 이미 처리)에
     * 더해, 랜 서버 자체도 완전히 닫는다: 리스닝 채널을 닫아 새 접속을 막고,
     * "Open to LAN" 상태(isRemote/isPublished)를 리셋하고, 로컬망에 존재를
     * 계속 알리는 LanServerPinger 브로드캐스트도 멈춘다(세 개 다 따로 처리해야
     * 하는 이유는 IntegratedServerAccessor 클래스 주석 참고) — 그냥 두면 우리
     * 초대/WebRTC 경로 말고도 같은 네트워크의 다른 사람이 바닐라 LAN 목록/직접
     * 접속으로 여전히 들어올 수 있기 때문이다. 방 옵션은 전부 기본값으로
     * 되돌리되, 중계 통신 강제는 방 옵션이 아니라 전역 클라이언트 설정이라
     * 그대로 둔다(Config 값 유지). 월드 자체는 안 건드리므로 싱글플레이는
     * 그대로 이어간다. */
    //? if >=26.1 {
    /*private static void closeRoomCompletely(Minecraft client) {
        if (activeInviteCode == null) return;
        IntegratedServer server = client.getSingleplayerServer();
        cancelInvite();
        if (server != null) {
            server.execute(() -> {
                // ServerConnectionListener#stop()은 아직 접속 중인 연결(호스트 포함)엔
                // 손대지 않고 "새 연결을 받는" 리스닝 채널만 닫는다(바이트코드로 확인).
                server.getConnection().stop();
                // 위 stop()은 "Open to LAN" 상태 자체나 로컬망 브로드캐스트는 안
                // 건드린다 — closeLanServer/IntegratedServerAccessor 클래스 주석 참고.
                closeLanServer(server);
            });
        }
        activeGameMode = GameType.ADVENTURE;
        activeMaxPlayers = 8;
        activeAllowCheats = false;
        activePublicRoom = false;
        activeTitle = null;
        activeChannel = "";
    }
    *///?} else {
    private static void closeRoomCompletely(MinecraftClient client) {
        if (activeInviteCode == null) return;
        IntegratedServer server = client.getServer();
        cancelInvite();
        if (server != null) {
            server.execute(() -> {
                // ServerNetworkIo#stop()은 아직 접속 중인 연결(호스트 포함)엔 손대지
                // 않고 "새 연결을 받는" 리스닝 채널만 닫는다(바이트코드로 확인).
                server.getNetworkIo().stop();
                // 위 stop()은 "Open to LAN" 상태 자체나 로컬망 브로드캐스트는 안
                // 건드린다 — closeLanServer/IntegratedServerAccessor 클래스 주석 참고.
                closeLanServer(server);
            });
        }
        activeGameMode = GameMode.ADVENTURE;
        activeMaxPlayers = 8;
        activeAllowCheats = false;
        activePublicRoom = false;
        activeTitle = null;
        activeChannel = "";
    }
    //?}

    //? if >=26.1 {
    /*private static void cancelInvite(IntegratedServer explicitServer, boolean waitForClose) {
        activeInviteCode = null;
        activeGuestCount = 0;
        P2PBanManager.setRoomMaxPlayers(0);
        // 공개 목록에선 곧장 내린다 — 아래 1.5초 지연은 기존 터널로 Disconnect 패킷이 빠져나갈
        // 시간일 뿐인데, 예전엔 목록 제거까지 같이 늦어져서 그 사이 LAN이 이미 닫힌 방을
        // 누군가 눌러 접속에 실패했다.
        WebRtcBridge.unpublishPublicRoom();
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
        activeGuestCount = 0;
        P2PBanManager.setRoomMaxPlayers(0);
        // 공개 목록에선 곧장 내린다 — 아래 1.5초 지연은 기존 터널로 Disconnect 패킷이 빠져나갈
        // 시간일 뿐인데, 예전엔 목록 제거까지 같이 늦어져서 그 사이 LAN이 이미 닫힌 방을
        // 누군가 눌러 접속에 실패했다.
        WebRtcBridge.unpublishPublicRoom();
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

    /**
     * RoomListScreen 하단 코드 입력·방 목록 클릭·빠른 시작 공용 — 코드로 접속.
     * <p>
     * 접속을 시작하기 전에 방에 내가 차단한 유저가 있는지 먼저 확인한다(RoomMembersProbe, 네트워크라 별도 스레드).
     * 있으면 네/아니오 창을 띄워 "네"일 때만 접속하고, "아니오"면 누른 화면(방 목록)으로 돌아간다.
     */
    //? if >=26.1 {
    /*public static void joinRoomByCode(Minecraft client, Screen parent, String code) {
        if (joinCheckInFlight) return;
        joinCheckInFlight = true;
        Screen current = kfcudp$currentScreen(client);
        Thread t = new Thread(() -> {
            java.util.List<String> blocked = kfc.udp.client.webrtc.RoomMembersProbe.blockedPlayerNames(code);
            client.execute(() -> {
                joinCheckInFlight = false;
                if (kfcudp$currentScreen(client) != current) return; // 확인하는 사이 화면을 벗어났으면 접속하지 않는다
                // 차단한 유저가 있으면 방 목록 화면 위에 팝업으로 묻는다 — "아니오"(또는 10초 경과)면 그 화면 그대로.
                if (!blocked.isEmpty() && current instanceof RoomListScreen roomList) {
                    roomList.confirmBlockedJoin(blocked, () -> connectToRoom(client, parent, code));
                    return;
                }
                connectToRoom(client, parent, code);
            });
        }, "instant-p2p-join-check");
        t.setDaemon(true);
        t.start();
    }

    private static void connectToRoom(Minecraft client, Screen parent, String code) {
        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parseString(address);
        ServerData serverInfo = new ServerData(
                Component.translatable("instant-p2p.join_room.server_name").getString(), address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(parent, client, serverAddress, serverInfo, false, null);
    }
    *///?} else {
    public static void joinRoomByCode(MinecraftClient client, Screen parent, String code) {
        if (joinCheckInFlight) return;
        joinCheckInFlight = true;
        Screen current = client.currentScreen;
        Thread t = new Thread(() -> {
            java.util.List<String> blocked = kfc.udp.client.webrtc.RoomMembersProbe.blockedPlayerNames(code);
            client.execute(() -> {
                joinCheckInFlight = false;
                if (client.currentScreen != current) return; // 확인하는 사이 화면을 벗어났으면 접속하지 않는다
                // 차단한 유저가 있으면 방 목록 화면 위에 팝업으로 묻는다 — "아니오"(또는 10초 경과)면 그 화면 그대로.
                if (!blocked.isEmpty() && current instanceof RoomListScreen roomList) {
                    roomList.confirmBlockedJoin(blocked, () -> connectToRoom(client, parent, code));
                    return;
                }
                connectToRoom(client, parent, code);
            });
        }, "instant-p2p-join-check");
        t.setDaemon(true);
        t.start();
    }

    private static void connectToRoom(MinecraftClient client, Screen parent, String code) {
        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parse(address);
        ServerInfo serverInfo = new ServerInfo(
                Text.translatable("instant-p2p.join_room.server_name").getString(), address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(parent, client, serverAddress, serverInfo, false, null);
    }
    //?}
}
