package kfc.udp.client.gui;

import kfc.udp.client.webrtc.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
//?}

import java.util.List;
import java.util.Objects;

import static kfc.udp.client.gui.RoomListScreen.*;

/**
 * 방 목록 화면(RoomListScreen)의 "차단 목록" 버튼으로 연다. 차단(=밴)한 플레이어의
 * 방은 방 목록 필터에 걸려 애초에 안 보이므로(P2PBanManager 클래스 주석 참고), 여기서
 * 이름으로 나열해 칸 오른쪽의 ♻ 버튼으로 해제한다 — 자리는 방 목록의 차단 버튼과 같다.
 * <p>
 * 배치·스크롤은 방 목록과 같다 — 칸 크기는 고정, 열·행 수는 화면에 맞춰 정하고, 열 우선으로 채워
 * 휠 한 칸에 한 열씩 옆으로 넘기며, 화면에 안 보이는 열이 생기면 아래 섹션 맨 위에 가로 스크롤바가 뜬다.
 * <p>
 * <b>접속자 모드(online=true)에서 내가 등급자(개발자·서포터·방송인)면 ❌/♻ 왼쪽에 노란 강퇴
 * 버튼이 하나 더 뜬다</b>(ExpelManager.kick 호출) — 온라인이면서 방장이 아닌 대상에만 뜨고,
 * 추방과 달리 재입장을 막지 않는 1회성 강퇴다(ExpelManager 클래스 주석 참고). 예전엔 이 강퇴·
 * 추방 기능이 별도 화면(ExpelledPlayersScreen)이었는데, 여기 차단 목록에서 차단(❌)을 눌러도
 * 어차피 같이 추방 요청이 나가면서(아래 handleClick 참고) 화면 두 개가 사실상 같은 일을
 * 반씩 나눠 하고 있어 혼란스러웠다 — 그래서 강퇴 버튼만 이 화면에 옮겨 붙이고 그 화면은
 * 없앴다. 닉네임 왼쪽 얼굴 아이콘은 접속 중이면 탭 목록 텍스처를 바로, 아니면 세션 서버에서
 * 프로필을 받아와 언제나 그린다(drawHead/resolveProfile 참고) — 같이 없어도 빈칸이 아니다.
 */
public class BlockedPlayersScreen extends Screen {

    private static final int HINT_Y = SEARCH_Y + (20 - 9) / 2;
    private static final int BACK_W = 200;
    private static final int CELL_H = ROW_H - 2;
    /** 탭 목록처럼 닉네임 왼쪽에 얼굴을 띄운다 — 크기·이름과의 간격. */
    private static final int HEAD_SIZE = 16;
    private static final int HEAD_GAP = 4;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT = Component.translatable("instant-p2p.blocked_players.title");
    private static final Component EMPTY_TEXT = Component.translatable("instant-p2p.blocked_players.empty");
    private static final Component HINT_TEXT  = Component.translatable("instant-p2p.blocked_players.hint");
    private static final Component ONLINE_TITLE_TEXT = Component.translatable("instant-p2p.online_players.title");
    // "(스트리머 보호 활성)" 부분만 빨갛게 — 앞쪽 "플레이어 차단"은 기본 흰색 그대로 이어붙인다.
    private static final Component ONLINE_TITLE_PRIVILEGED_TEXT = Component.translatable("instant-p2p.online_players.title")
            .copy().append(Component.translatable("instant-p2p.online_players.title_privileged_suffix").withStyle(net.minecraft.ChatFormatting.RED));
    private static final Component ONLINE_EMPTY_TEXT = Component.translatable("instant-p2p.online_players.empty");
    private static final Component ONLINE_HINT_TEXT  = Component.translatable("instant-p2p.online_players.hint");
    private static final Component ONLINE_HINT_PRIVILEGED_TEXT = Component.translatable("instant-p2p.online_players.hint_privileged");
    *///?} else {
    private static final Text TITLE_TEXT = Text.translatable("instant-p2p.blocked_players.title");
    private static final Text EMPTY_TEXT = Text.translatable("instant-p2p.blocked_players.empty");
    private static final Text HINT_TEXT  = Text.translatable("instant-p2p.blocked_players.hint");
    private static final Text ONLINE_TITLE_TEXT = Text.translatable("instant-p2p.online_players.title");
    // "(스트리머 보호 활성)" 부분만 빨갛게 — 앞쪽 "플레이어 차단"은 기본 흰색 그대로 이어붙인다.
    private static final Text ONLINE_TITLE_PRIVILEGED_TEXT = Text.translatable("instant-p2p.online_players.title")
            .copy().append(Text.translatable("instant-p2p.online_players.title_privileged_suffix").formatted(net.minecraft.util.Formatting.RED));
    private static final Text ONLINE_EMPTY_TEXT = Text.translatable("instant-p2p.online_players.empty");
    private static final Text ONLINE_HINT_TEXT  = Text.translatable("instant-p2p.online_players.hint");
    private static final Text ONLINE_HINT_PRIVILEGED_TEXT = Text.translatable("instant-p2p.online_players.hint_privileged");
    //?}

    private final Screen parent;
    /** true = 접속자 모드(ESC 메뉴 "플레이어 차단") — 지금 접속한 다른 플레이어를 ❌로 차단, ♻로 해제.
     * 접속한 사람 뒤로 이미 차단해서 나간 사람도 이어서 나온다(refreshGrid 참고) — 버튼을 따로 안 두고
     * 한 화면에서 다 보이게.
     * false = 차단 목록 모드(방 목록의 "차단 목록", 접속 전 화면 전용) — 차단한 사람을 나열해 ♻로 해제. */
    private final boolean online;
    private List<P2PBanManager.BannedEntry> entries = List.of();
    private int entriesVersion = -1;
    /** 온라인 모드에서 지금 접속 중인 대상 uuid — 강퇴 버튼은 이 목록에 있는(=온라인) 대상에만 뜬다. */
    private java.util.Set<String> onlineUuids = java.util.Set.of();
    /** 내가 등급자(스트리머 이상)라 x/o 옆에 강퇴 버튼도 같이 뜨는지 — 온라인 모드에서만 켜진다. */
    private boolean showKickColumn = false;
    /** 닉네임이 차지할 수 있는 폭 — 머리 아이콘, ❌/♻ 버튼, (등급자면) 강퇴 버튼까지 뺀 나머지. */
    private int nameW = CELL_W - 6 - HEAD_SIZE - HEAD_GAP - (3 + BLOCK_BTN);

    /** 맨 왼쪽에 보이는 열 번호 — 휠 한 칸에 1씩 바뀐다. */
    private int scrollCol = 0;
    private int rows = 1, cols = 1;
    private boolean draggingScrollbar = false;
    private int backY, divider2Y;

    /** 칸(slot)별로 지금 보이는 항목과 좌상단 좌표. slot = 화면상 열 * rows + 행. */
    private P2PBanManager.BannedEntry[] cellEntry = new P2PBanManager.BannedEntry[0];
    private int[] cellX = new int[0];
    private int[] cellY = new int[0];
    /** 해제 확인 팝업 — 떠 있는 동안 입력은 전부 팝업이 받는다(ConfirmPopup 클래스 주석). */
    private final ConfirmPopup popup = new ConfirmPopup();

    public BlockedPlayersScreen(Screen parent) {
        this(parent, false);
    }

    public BlockedPlayersScreen(Screen parent, boolean online) {
        super(online ? (myPriority() > 0 ? ONLINE_TITLE_PRIVILEGED_TEXT : ONLINE_TITLE_TEXT) : TITLE_TEXT);
        this.parent = parent;
        this.online = online;
    }

    //? if >=26.1 {
    /*@Override
    protected void init() {
        this.layout();
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                        .bounds(this.width / 2 - BACK_W / 2, this.backY, BACK_W, 20)
                        .build()
        );
        this.refreshGrid();
    }
    *///?} else {
    @Override
    protected void init() {
        this.layout();
        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.BACK, b -> this.close())
                        .dimensions(this.width / 2 - BACK_W / 2, this.backY, BACK_W, 20)
                        .build()
        );
        this.refreshGrid();
    }
    //?}

    /** 열·행 수와 칸 배열을 화면 크기에 맞춰 정한다 — 계산식은 RoomListScreen.init과 같다. */
    private void layout() {
        this.backY = this.height - 24;
        this.divider2Y = this.backY - 8; // 구분선 밑 1~7px = 가로 스크롤바 자리
        this.cols = Math.max(1, (this.width - 2 * LIST_MARGIN + CELL_GAP) / (CELL_W + CELL_GAP));
        this.rows = Math.max(1, (this.divider2Y - 2 - LIST_Y) / ROW_H);
        int slots = this.cols * this.rows;
        this.cellEntry = new P2PBanManager.BannedEntry[slots];
        this.cellX = new int[slots];
        this.cellY = new int[slots];
    }

    @Override
    public void tick() {
        super.tick();
        this.refreshGrid();
    }

    private int totalCols() {
        return (this.entries.size() + this.rows - 1) / this.rows;
    }

    private int listW() {
        return this.cols * CELL_W + (this.cols - 1) * CELL_GAP;
    }

    private int listX() {
        return (this.width - this.listW()) / 2;
    }

    /** 밴 목록이 바뀌었을 때만 다시 읽고, 현재 스크롤 열에 맞춰 칸마다 항목과 좌표를 채운다(열 우선). */
    private void refreshGrid() {
        if (this.online) {
            // 지금 접속한 사람 먼저, 그다음 이미 차단해서 나간(온라인 목록엔 없는) 사람을 이어붙인다 —
            // 버튼을 "플레이어 차단"/"차단 목록" 둘로 나누지 않고 한 화면에서 다 보이게. 매 tick 다시
            // 계산하는데 한 방 인원 + 밴 목록 둘 다 가벼워서 문제없다.
            List<P2PBanManager.BannedEntry> live = this.onlinePlayers();
            java.util.Set<String> liveUuids = live.stream().map(P2PBanManager.BannedEntry::uuid)
                    .collect(java.util.stream.Collectors.toSet());
            this.onlineUuids = liveUuids;
            this.showKickColumn = myPriority() > 0;
            List<P2PBanManager.BannedEntry> offlineBanned = P2PBanManager.listBannedPlayers().stream()
                    .filter(e -> !liveUuids.contains(e.uuid()))
                    .sorted(java.util.Comparator.comparing(P2PBanManager.BannedEntry::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            this.entries = java.util.stream.Stream.concat(live.stream(), offlineBanned.stream()).toList();
        } else {
            int version = P2PBanManager.banListVersion();
            if (version != this.entriesVersion) {
                this.entries = P2PBanManager.listBannedPlayers();
                this.entriesVersion = version;
            }
            this.onlineUuids = java.util.Set.of();
            this.showKickColumn = false;
        }
        this.nameW = CELL_W - 6 - HEAD_SIZE - HEAD_GAP - (3 + BLOCK_BTN) - (this.showKickColumn ? (3 + BLOCK_BTN) : 0);

        int maxCol = Math.max(0, this.totalCols() - this.cols);
        if (this.scrollCol > maxCol) this.scrollCol = maxCol;

        int listX = this.listX();
        for (int i = 0; i < this.cellEntry.length; i++) {
            int col = i / this.rows, row = i % this.rows;
            int idx = (this.scrollCol + col) * this.rows + row;
            this.cellEntry[i] = idx < this.entries.size() ? this.entries.get(idx) : null;
            this.cellX[i] = listX + col * (CELL_W + CELL_GAP);
            this.cellY[i] = LIST_Y + row * ROW_H;
        }
    }

    /** (mouseX,mouseY)가 x(해제) 버튼 위에 있는 칸, 없으면 -1 — 방 목록 차단 버튼과 같은 자리. */
    private int unblockButtonAt(double mouseX, double mouseY) {
        for (int i = 0; i < this.cellEntry.length; i++) {
            if (this.cellEntry[i] == null) continue;
            int bx = this.cellX[i] + CELL_W - 3 - BLOCK_BTN;
            int by = this.cellY[i] + (CELL_H - BLOCK_BTN) / 2;
            if (mouseX >= bx && mouseX < bx + BLOCK_BTN && mouseY >= by && mouseY < by + BLOCK_BTN) return i;
        }
        return -1;
    }

    /** 강퇴 버튼은 ❌/♻ 버튼 바로 왼쪽 — 등급자인 나한테만, 그리고 지금 온라인이면서 방장이 아닌
     * 대상에만 뜬다(방장은 강퇴 대상이 될 수 없다, ExpelManager 클래스 주석 참고). */
    private int kickButtonAt(double mouseX, double mouseY) {
        if (!this.showKickColumn) return -1;
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null || !this.onlineUuids.contains(e.uuid()) || isHostEntry(e)) continue;
            int bx = this.cellX[i] + CELL_W - 6 - 2 * BLOCK_BTN;
            int by = this.cellY[i] + (CELL_H - BLOCK_BTN) / 2;
            if (mouseX >= bx && mouseX < bx + BLOCK_BTN && mouseY >= by && mouseY < by + BLOCK_BTN) return i;
        }
        return -1;
    }

    /** 강퇴 버튼 대신 방장 표시가 뜨는 자리 — 클릭은 안 되고 호버 툴팁("호스트는 강퇴할 수
     * 없습니다")만 있다. kickButtonAt과 같은 자리, 조건만 방장이어야 한다는 것만 반대. */
    private int hostBadgeAt(double mouseX, double mouseY) {
        if (!this.showKickColumn) return -1;
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null || !this.onlineUuids.contains(e.uuid()) || !isHostEntry(e)) continue;
            int bx = this.cellX[i] + CELL_W - 6 - 2 * BLOCK_BTN;
            int by = this.cellY[i] + (CELL_H - BLOCK_BTN) / 2;
            if (mouseX >= bx && mouseX < bx + BLOCK_BTN && mouseY >= by && mouseY < by + BLOCK_BTN) return i;
        }
        return -1;
    }

    private static boolean isHostEntry(P2PBanManager.BannedEntry e) {
        return kfc.udp.client.DevBadge.isHostPlayer(java.util.UUID.fromString(e.uuid()));
    }

    /** 내 등급 — 0이면(무등급) 강퇴 버튼도, 제목·안내 문구의 "스트리머 보호 활성" 표시도 안 뜬다.
     * ExpelManager.priority와 같은 기준. static — 생성자의 super() 호출(제목 결정)에서도 써야 한다. */
    //? if >=26.1 {
    /*private static int myPriority() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player != null ? kfc.udp.client.webrtc.ExpelManager.priority(mc.player.getUUID()) : 0;
    }
    *///?} else {
    private static int myPriority() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        return mc.player != null ? kfc.udp.client.webrtc.ExpelManager.priority(mc.player.getUuid()) : 0;
    }
    //?}

    /** 온라인 모드에서 등급자면 강퇴·임시밴이 같이 켜진다는 안내 문구로 바꾼다. */
    //? if >=26.1 {
    /*private Component hintText() {
        if (!this.online) return HINT_TEXT;
        return this.showKickColumn ? ONLINE_HINT_PRIVILEGED_TEXT : ONLINE_HINT_TEXT;
    }
    *///?} else {
    private Text hintText() {
        if (!this.online) return HINT_TEXT;
        return this.showKickColumn ? ONLINE_HINT_PRIVILEGED_TEXT : ONLINE_HINT_TEXT;
    }
    //?}

    /** 차단(❌)/해제(♻) 버튼에 마우스를 올렸을 때 보여줄 안내 번역 키 — 차단 목록 모드(!online)에서는
     * 이 버튼이 항상 해제만 하므로 등급자 전용 차단 설명은 안 보여준다. */
    private String toggleTooltipKey(P2PBanManager.BannedEntry e) {
        return this.willUnblock(e) ? "instant-p2p.online_players.unblock_tooltip" : "instant-p2p.online_players.block_tooltip";
    }

    // 손으로 그린 버튼이라 위젯 툴팁이 아니라 직접 그려야 한다 — ConfirmPopup의 "그 외 n명" 호버
    // 툴팁과 같은 API. "\n"으로 줄바꿈된 번역문을 줄 단위로 쪼개 그대로 넘긴다(자동 줄바꿈 없음).
    //? if >=26.1 {
    /*private static java.util.List<Component> tooltipLines(String key) {
        return java.util.Arrays.stream(Component.translatable(key).getString().split("\n"))
                .<Component>map(Component::literal).toList();
    }
    *///?} else {
    private static java.util.List<Text> tooltipLines(String key) {
        return java.util.Arrays.stream(Text.translatable(key).getString().split("\n"))
                .<Text>map(Text::literal).toList();
    }
    //?}

    /** 왼쪽 클릭이 스크롤바나 x/강퇴 버튼에 걸렸으면 처리하고 true — 마우스 입력 API 3분기가 같이 쓴다. */
    private boolean handleClick(double mouseX, double mouseY) {
        if (this.isOnScrollbarTrack(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollToTrackX(mouseX);
            return true;
        }
        int kickSlot = this.kickButtonAt(mouseX, mouseY);
        if (kickSlot >= 0) {
            playClick();
            P2PBanManager.BannedEntry e = this.cellEntry[kickSlot];
            java.util.UUID targetUuid = java.util.UUID.fromString(e.uuid());
            // 킥은 차단과 달리 아무 것도 기록하지 않는다 — 확인 즉시 요청만 보낸다.
            this.popup.open("instant-p2p.confirm.kick", displayName(e), () -> kfc.udp.client.webrtc.ExpelManager.requestKick(targetUuid));
            return true;
        }
        int slot = this.unblockButtonAt(mouseX, mouseY);
        if (slot < 0) return false;
        playClick();
        P2PBanManager.BannedEntry e = this.cellEntry[slot];
        boolean unblock = this.willUnblock(e);
        // 바로 바꾸지 않고 확인 팝업부터 — 확인하면 그때 반영된다(목록은 다음 tick에 다시 읽힌다).
        this.popup.open(unblock ? "instant-p2p.confirm.unblock" : "instant-p2p.confirm.block", displayName(e), () -> {
            java.util.UUID targetUuid = java.util.UUID.fromString(e.uuid());
            if (unblock) {
                P2PBanManager.pardonPlayerByUuid(e.uuid());
                kfc.udp.client.webrtc.ExpelManager.requestReadmit(targetUuid);
            } else {
                P2PBanManager.banPlayer(e.uuid(), e.name(), "Blocked in game.");
                kfc.udp.client.KfcudpClient.kickBlockedPlayer(e.uuid()); // 방장이면 지금 방에서도 내보낸다
                kfc.udp.client.webrtc.ExpelManager.requestExpel(targetUuid, e.name()); // 개발자·서포터·방송인이면 즉시 추방 요청
            }
        });
        return true;
    }

    /** 같은 월드의 다른 플레이어(나 제외), 이름순. 방장이면 자기 서버, 접속자면 방장 서버의 탭 목록 그대로. */
    //? if >=26.1 {
    /*private List<P2PBanManager.BannedEntry> onlinePlayers() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return List.of();
        java.util.UUID me = mc.player.getUUID();
        return mc.getConnection().getOnlinePlayers().stream()
                .map(info -> info.getProfile())
                .filter(p -> !P2PBanManager.profileId(p).equals(me))
                .map(p -> new P2PBanManager.BannedEntry(P2PBanManager.profileId(p).toString(), P2PBanManager.profileName(p)))
                .sorted(java.util.Comparator.comparing(P2PBanManager.BannedEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
    *///?} else {
    private List<P2PBanManager.BannedEntry> onlinePlayers() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || mc.player == null) return List.of();
        java.util.UUID me = mc.player.getUuid();
        return mc.getNetworkHandler().getPlayerList().stream()
                .map(entry -> entry.getProfile())
                .filter(p -> !P2PBanManager.profileId(p).equals(me))
                .map(p -> new P2PBanManager.BannedEntry(P2PBanManager.profileId(p).toString(), P2PBanManager.profileName(p)))
                .sorted(java.util.Comparator.comparing(P2PBanManager.BannedEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
    //?}

    /** UUID → 세션 서버에서 가져온(실패하면 이름만 채운 대체) 프로필 — 지금 접속해 있지 않은
     * 대상의 진짜 스킨을 그리려면 이게 있어야 한다(빈 프로필로는 항상 기본 스킨만 나온다). 한
     * 번 물어본 UUID는 게임을 끄기 전까지 다시 안 물어본다({@link #resolveProfile}). */
    private static final java.util.Map<java.util.UUID, java.util.concurrent.CompletableFuture<com.mojang.authlib.GameProfile>>
            profileFutures = new java.util.concurrent.ConcurrentHashMap<>();

    //? if >=26.1 {
    /*private static java.util.concurrent.CompletableFuture<com.mojang.authlib.GameProfile> fetchProfileAsync(
            java.util.UUID id, com.mojang.authlib.GameProfile fallback) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var result = net.minecraft.client.Minecraft.getInstance().services().sessionService().fetchProfile(id, false);
                return result != null ? result.profile() : fallback;
            } catch (Exception ex) {
                return fallback;
            }
        }, net.minecraft.util.Util.ioPool());
    }
    *///?}
    // getSessionService()가 1.21.9에서 getApiServices().sessionService()로 옮겨갔다(Mojang 쪽
    // services().sessionService()와 같은 모양이 됨) — 그 경계로 나눈다.
    //? if >=1.21.9 <26.1 {
    /*private static java.util.concurrent.CompletableFuture<com.mojang.authlib.GameProfile> fetchProfileAsync(
            java.util.UUID id, com.mojang.authlib.GameProfile fallback) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var result = net.minecraft.client.MinecraftClient.getInstance().getApiServices().sessionService().fetchProfile(id, false);
                return result != null ? result.profile() : fallback;
            } catch (Exception ex) {
                return fallback;
            }
        }, net.minecraft.util.Util.getIoWorkerExecutor());
    }
    *///?}
    //? if <1.21.9 {
    private static java.util.concurrent.CompletableFuture<com.mojang.authlib.GameProfile> fetchProfileAsync(
            java.util.UUID id, com.mojang.authlib.GameProfile fallback) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var result = net.minecraft.client.MinecraftClient.getInstance().getSessionService().fetchProfile(id, false);
                return result != null ? result.profile() : fallback;
            } catch (Exception ex) {
                return fallback;
            }
        }, net.minecraft.util.Util.getIoWorkerExecutor());
    }
    //?}

    /** 아직 요청 중이면(또는 실패했으면) 이름만 채운 대체 프로필을 바로 돌려주고, 나중에 요청이
     * 끝나면 그다음 tick의 드로우부터 실제 프로필(텍스처 포함)로 자연히 바뀐다. */
    private static com.mojang.authlib.GameProfile resolveProfile(java.util.UUID id, String name) {
        com.mojang.authlib.GameProfile fallback = new com.mojang.authlib.GameProfile(id, name);
        return profileFutures.computeIfAbsent(id, u -> fetchProfileAsync(u, fallback)).getNow(fallback);
    }

    /** 닉네임 왼쪽에 탭 목록과 같은 얼굴 아이콘을 그린다 — 지금 접속해 있으면 탭 목록 정보를 바로
     * 쓰고(네트워크 요청 없음), 아니면 세션 서버에서 프로필을 받아와 항상 그린다(resolveProfile). */
    //? if >=26.1 {
    /*private void drawHead(GuiGraphicsExtractor context, P2PBanManager.BannedEntry e, int x, int y) {
        java.util.UUID id = java.util.UUID.fromString(e.uuid());
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(id) : null;
        net.minecraft.world.entity.player.PlayerSkin skin = info != null ? info.getSkin()
                : mc.getSkinManager().createLookup(resolveProfile(id, e.name()), false).get();
        net.minecraft.resources.Identifier tex = skin.body().texturePath();
        context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 8.0f, 8.0f, HEAD_SIZE, HEAD_SIZE, 8, 8, 64, 64, -1);
        context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 40.0f, 8.0f, HEAD_SIZE, HEAD_SIZE, 8, 8, 64, 64, -1);
    }
    *///?}
    // PlayerSkinProvider.getSkinTexturesSupplier(profile)가 1.21.9에서 supplySkinTextures(profile,
    // boolean)로 이름·시그니처가 바뀌었다(getSessionService 이전과 같은 경계) — 그 경계로 나눈다.
    //? if >=1.21.9 <26.1 {
    /*private void drawHead(DrawContext context, P2PBanManager.BannedEntry e, int x, int y) {
        java.util.UUID id = java.util.UUID.fromString(e.uuid());
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.client.network.PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(id) : null;
        var textures = entry != null ? entry.getSkinTextures()
                : mc.getSkinProvider().supplySkinTextures(resolveProfile(id, e.name()), false).get();
        net.minecraft.client.gui.PlayerSkinDrawer.draw(context, textures, x, y, HEAD_SIZE);
    }
    *///?}
    //? if <1.21.9 {
    private void drawHead(DrawContext context, P2PBanManager.BannedEntry e, int x, int y) {
        java.util.UUID id = java.util.UUID.fromString(e.uuid());
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.client.network.PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(id) : null;
        var textures = entry != null ? entry.getSkinTextures()
                : mc.getSkinProvider().getSkinTexturesSupplier(resolveProfile(id, e.name())).get();
        net.minecraft.client.gui.PlayerSkinDrawer.draw(context, textures, x, y, HEAD_SIZE);
    }
    //?}

    /** 이 칸을 누르면 차단이 해제되는지 — 차단 목록 모드(!online)는 목록 자체가 이미 차단한
     * 사람들이라 항상 해제고, 접속자 모드는 그중 이미 차단한 대상일 때만 해제(나머지는 차단).
     * 예전엔 "접속자 모드&& 이미 차단"만 봐서 차단 목록 모드에서는 항상 빨간 ❌만 뜨는 버그가
     * 있었다 — 해제 아이콘(♻)·색이 두 모드에서 다르게 보였던 원인. handleClick도 같은 기준을
     * 쓴다. 가로 위치는 RoomListScreen.glyphX로 실측 너비만큼 가운데 정렬한다(고정폭 ASCII가
     * 아니라 이모지라 BLOCK_X_DX로는 안 맞는다). */
    private boolean willUnblock(P2PBanManager.BannedEntry e) {
        return !this.online || P2PBanManager.isPlayerBanned(e.uuid());
    }

    private String glyph(P2PBanManager.BannedEntry e) {
        return this.willUnblock(e) ? "♻" : "❌";
    }

    private int glyphColor(P2PBanManager.BannedEntry e) {
        return this.willUnblock(e) ? 0xFF55FF55 : 0xFFFF5555;
    }

    private int nameColor(P2PBanManager.BannedEntry e) {
        return this.willUnblock(e) ? 0xFF808080 : 0xFFFFFFFF;
    }

    private static String displayName(P2PBanManager.BannedEntry e) {
        return e.name().isEmpty() ? e.uuid().substring(0, Math.min(8, e.uuid().length())) : e.name();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.popup.isOpen()) return true;
        if (mouseY < DIVIDER1_Y || mouseY > this.divider2Y + 1 + SCROLLBAR_W)
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int maxCol = Math.max(0, this.totalCols() - this.cols);
        if (maxCol <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        // 휠 아래 = 오른쪽으로 한 열, 위 = 왼쪽으로 한 열.
        int newCol = Math.max(0, Math.min(maxCol, this.scrollCol - (int) Math.signum(verticalAmount)));
        if (newCol != this.scrollCol) {
            this.scrollCol = newCol;
            this.refreshGrid();
        }
        return true;
    }

    private boolean hasScrollbar() {
        return this.totalCols() > this.cols;
    }

    private int scrollbarY() {
        return this.divider2Y + 1;
    }

    /** 썸의 가로 위치·폭 — {x, width}. 최소 10px은 남겨 손으로 집을 수 있게 한다. */
    private int[] scrollbarThumb() {
        int total = this.totalCols();
        int thumbW = Math.max(10, this.listW() * this.cols / total);
        int maxCol = Math.max(1, total - this.cols);
        return new int[]{this.listX() + (this.listW() - thumbW) * this.scrollCol / maxCol, thumbW};
    }

    private void scrollToTrackX(double mouseX) {
        int maxCol = this.totalCols() - this.cols;
        if (maxCol <= 0) return;
        int thumbW = this.scrollbarThumb()[1];
        double rel = (mouseX - this.listX() - thumbW / 2.0) / (this.listW() - thumbW);
        int newCol = Math.max(0, Math.min(maxCol, (int) Math.round(rel * maxCol)));
        if (newCol != this.scrollCol) {
            this.scrollCol = newCol;
            this.refreshGrid();
        }
    }

    private boolean isOnScrollbarTrack(double mouseX, double mouseY) {
        if (!this.hasScrollbar()) return false;
        int trackX = this.listX(), sbY = this.scrollbarY();
        return mouseX >= trackX && mouseX < trackX + this.listW() && mouseY >= sbY && mouseY < sbY + SCROLLBAR_W;
    }

    // 팝업이 떠 있으면 Enter/Esc를 팝업이 먼저 받는다(Esc가 화면을 닫지 않게). 시그니처 3분기는 RoomListScreen과 같다.
    //? if >=26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        return this.popup.keyPressed(input.key()) || super.keyPressed(input);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return this.popup.keyPressed(input.key()) || super.keyPressed(input);
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return this.popup.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    // 마우스 입력 시그니처가 버전마다 달라 RoomListScreen과 같은 독립 3분기로 둔다.
    //? if >=26.1 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (this.popup.isOpen()) {
            if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) this.popup.mouseClicked(click.x(), click.y());
            return true;
        }
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && this.handleClick(click.x(), click.y())) return true;
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollToTrackX(click.x());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
        if (this.popup.isOpen()) {
            if (click.button() == 0) this.popup.mouseClicked(click.x(), click.y());
            return true;
        }
        if (click.button() == 0 && this.handleClick(click.x(), click.y())) return true;
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollToTrackX(click.x());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.popup.isOpen()) {
            if (button == 0) this.popup.mouseClicked(mouseX, mouseY);
            return true;
        }
        if (button == 0 && this.handleClick(mouseX, mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollToTrackX(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    //?}

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        int realX = mouseX, realY = mouseY;
        if (this.popup.isOpen()) { mouseX = -1; mouseY = -1; } // 팝업 뒤 버튼이 호버되지 않게
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;
        context.centeredText(this.font, this.title, cx, TITLE_Y, 0xFFFFFFFF);
        context.centeredText(this.font, this.hintText(), cx, HINT_Y, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        if (this.entries.isEmpty()) {
            context.centeredText(this.font, (this.online ? ONLINE_EMPTY_TEXT : EMPTY_TEXT), cx, LIST_Y + (ROW_H - 9) / 2, 0xFFA0A0A0);
        }

        int hoveredBtn = this.unblockButtonAt(mouseX, mouseY);
        int hoveredKick = this.kickButtonAt(mouseX, mouseY);
        int hoveredHost = this.hostBadgeAt(mouseX, mouseY);
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null) continue;
            int x = this.cellX[i], y = this.cellY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.outline(x, y, CELL_W, CELL_H, ROW_BORDER_COLOR);
            this.drawHead(context, e, x + 6, y + (CELL_H - HEAD_SIZE) / 2);
            context.text(this.font, this.font.plainSubstrByWidth(displayName(e), this.nameW), x + 6 + HEAD_SIZE + HEAD_GAP, y + (CELL_H - 9) / 2 + 1, this.nameColor(e));
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBtn == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.outline(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBtn == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.text(this.font, this.glyph(e), glyphX(this.font, this.glyph(e), bx) + glyphNudgeX(this.glyph(e)), by + BLOCK_X_DY + glyphNudgeY(this.glyph(e)), this.glyphColor(e));
            if (this.showKickColumn && this.onlineUuids.contains(e.uuid())) {
                int kx = x + CELL_W - 6 - 2 * BLOCK_BTN;
                if (isHostEntry(e)) {
                    // 방장은 강퇴 대상이 될 수 없다(ExpelManager 클래스 주석 참고) — 빈 칸 대신
                    // 방장 표시를 박아서 왜 강퇴 버튼이 없는지 바로 보이게 한다.
                    context.fill(kx, by, kx + BLOCK_BTN, by + BLOCK_BTN, BLOCK_BTN_COLOR);
                    context.outline(kx, by, BLOCK_BTN, BLOCK_BTN, ROW_BORDER_COLOR);
                    context.text(this.font, "📶", glyphX(this.font, "📶", kx) + glyphNudgeX("📶"), by + BLOCK_X_DY + glyphNudgeY("📶"), 0xFF55FF55);
                } else {
                    context.fill(kx, by, kx + BLOCK_BTN, by + BLOCK_BTN, hoveredKick == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
                    context.outline(kx, by, BLOCK_BTN, BLOCK_BTN, hoveredKick == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
                    context.text(this.font, "⚡", glyphX(this.font, "⚡", kx) + glyphNudgeX("⚡"), by + BLOCK_X_DY + glyphNudgeY("⚡"), 0xFFFFFF55);
                }
            }
        }

        if (this.hasScrollbar()) {
            int trackX = this.listX(), sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
        }
        // 손으로 그린 버튼이라 위젯 툴팁 대신 직접 호버 판정해 그린다 — 팝업이 열려 있으면
        // mouseX/Y가 이미 -1로 죽어 있어(위 popup.isOpen() 분기) hoveredBtn/Kick도 자연히 -1.
        if (hoveredBtn >= 0) {
            context.setComponentTooltipForNextFrame(this.font, tooltipLines(this.toggleTooltipKey(this.cellEntry[hoveredBtn])), mouseX, mouseY);
        } else if (hoveredKick >= 0) {
            context.setComponentTooltipForNextFrame(this.font, tooltipLines("instant-p2p.online_players.kick_tooltip"), mouseX, mouseY);
        } else if (hoveredHost >= 0) {
            context.setComponentTooltipForNextFrame(this.font, tooltipLines("instant-p2p.online_players.host_tooltip"), mouseX, mouseY);
        }
        this.popup.render(context, this.width, this.height, realX, realY);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int realX = mouseX, realY = mouseY;
        if (this.popup.isOpen()) { mouseX = -1; mouseY = -1; } // 팝업 뒤 버튼이 호버되지 않게
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, TITLE_Y, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, this.hintText(), cx, HINT_Y, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        if (this.entries.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, (this.online ? ONLINE_EMPTY_TEXT : EMPTY_TEXT), cx, LIST_Y + (ROW_H - 9) / 2, 0xFFA0A0A0);
        }

        int hoveredBtn = this.unblockButtonAt(mouseX, mouseY);
        int hoveredKick = this.kickButtonAt(mouseX, mouseY);
        int hoveredHost = this.hostBadgeAt(mouseX, mouseY);
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null) continue;
            int x = this.cellX[i], y = this.cellY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.drawStrokedRectangle(x, y, CELL_W, CELL_H, ROW_BORDER_COLOR);
            this.drawHead(context, e, x + 6, y + (CELL_H - HEAD_SIZE) / 2);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(displayName(e), this.nameW)), x + 6 + HEAD_SIZE + HEAD_GAP, y + (CELL_H - 9) / 2 + 1, this.nameColor(e));
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBtn == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.drawStrokedRectangle(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBtn == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.glyph(e)), glyphX(this.textRenderer, this.glyph(e), bx) + glyphNudgeX(this.glyph(e)), by + BLOCK_X_DY + glyphNudgeY(this.glyph(e)), this.glyphColor(e));
            if (this.showKickColumn && this.onlineUuids.contains(e.uuid())) {
                int kx = x + CELL_W - 6 - 2 * BLOCK_BTN;
                if (isHostEntry(e)) {
                    context.fill(kx, by, kx + BLOCK_BTN, by + BLOCK_BTN, BLOCK_BTN_COLOR);
                    context.drawStrokedRectangle(kx, by, BLOCK_BTN, BLOCK_BTN, ROW_BORDER_COLOR);
                    context.drawTextWithShadow(this.textRenderer, Text.literal("📶"), glyphX(this.textRenderer, "📶", kx) + glyphNudgeX("📶"), by + BLOCK_X_DY + glyphNudgeY("📶"), 0xFF55FF55);
                } else {
                    context.fill(kx, by, kx + BLOCK_BTN, by + BLOCK_BTN, hoveredKick == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
                    context.drawStrokedRectangle(kx, by, BLOCK_BTN, BLOCK_BTN, hoveredKick == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
                    context.drawTextWithShadow(this.textRenderer, Text.literal("⚡"), glyphX(this.textRenderer, "⚡", kx) + glyphNudgeX("⚡"), by + BLOCK_X_DY + glyphNudgeY("⚡"), 0xFFFFFF55);
                }
            }
        }

        if (this.hasScrollbar()) {
            int trackX = this.listX(), sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
        }
        if (hoveredBtn >= 0) {
            context.drawTooltip(this.textRenderer, tooltipLines(this.toggleTooltipKey(this.cellEntry[hoveredBtn])), mouseX, mouseY);
        } else if (hoveredKick >= 0) {
            context.drawTooltip(this.textRenderer, tooltipLines("instant-p2p.online_players.kick_tooltip"), mouseX, mouseY);
        } else if (hoveredHost >= 0) {
            context.drawTooltip(this.textRenderer, tooltipLines("instant-p2p.online_players.host_tooltip"), mouseX, mouseY);
        }
        this.popup.render(context, this.width, this.height, realX, realY);
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int realX = mouseX, realY = mouseY;
        if (this.popup.isOpen()) { mouseX = -1; mouseY = -1; } // 팝업 뒤 버튼이 호버되지 않게
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, TITLE_Y, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, this.hintText(), cx, HINT_Y, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        if (this.entries.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, (this.online ? ONLINE_EMPTY_TEXT : EMPTY_TEXT), cx, LIST_Y + (ROW_H - 9) / 2, 0xFFA0A0A0);
        }

        int hoveredBtn = this.unblockButtonAt(mouseX, mouseY);
        int hoveredKick = this.kickButtonAt(mouseX, mouseY);
        int hoveredHost = this.hostBadgeAt(mouseX, mouseY);
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null) continue;
            int x = this.cellX[i], y = this.cellY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.drawBorder(x, y, CELL_W, CELL_H, ROW_BORDER_COLOR);
            this.drawHead(context, e, x + 6, y + (CELL_H - HEAD_SIZE) / 2);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(displayName(e), this.nameW)), x + 6 + HEAD_SIZE + HEAD_GAP, y + (CELL_H - 9) / 2 + 1, this.nameColor(e));
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBtn == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.drawBorder(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBtn == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.glyph(e)), glyphX(this.textRenderer, this.glyph(e), bx) + glyphNudgeX(this.glyph(e)), by + BLOCK_X_DY + glyphNudgeY(this.glyph(e)), this.glyphColor(e));
            if (this.showKickColumn && this.onlineUuids.contains(e.uuid())) {
                int kx = x + CELL_W - 6 - 2 * BLOCK_BTN;
                if (isHostEntry(e)) {
                    context.fill(kx, by, kx + BLOCK_BTN, by + BLOCK_BTN, BLOCK_BTN_COLOR);
                    context.drawBorder(kx, by, BLOCK_BTN, BLOCK_BTN, ROW_BORDER_COLOR);
                    context.drawTextWithShadow(this.textRenderer, Text.literal("📶"), glyphX(this.textRenderer, "📶", kx) + glyphNudgeX("📶"), by + BLOCK_X_DY + glyphNudgeY("📶"), 0xFF55FF55);
                } else {
                    context.fill(kx, by, kx + BLOCK_BTN, by + BLOCK_BTN, hoveredKick == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
                    context.drawBorder(kx, by, BLOCK_BTN, BLOCK_BTN, hoveredKick == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
                    context.drawTextWithShadow(this.textRenderer, Text.literal("⚡"), glyphX(this.textRenderer, "⚡", kx) + glyphNudgeX("⚡"), by + BLOCK_X_DY + glyphNudgeY("⚡"), 0xFFFFFF55);
                }
            }
        }

        if (this.hasScrollbar()) {
            int trackX = this.listX(), sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
        }
        if (hoveredBtn >= 0) {
            context.drawTooltip(this.textRenderer, tooltipLines(this.toggleTooltipKey(this.cellEntry[hoveredBtn])), mouseX, mouseY);
        } else if (hoveredKick >= 0) {
            context.drawTooltip(this.textRenderer, tooltipLines("instant-p2p.online_players.kick_tooltip"), mouseX, mouseY);
        } else if (hoveredHost >= 0) {
            context.drawTooltip(this.textRenderer, tooltipLines("instant-p2p.online_players.host_tooltip"), mouseX, mouseY);
        }
        this.popup.render(context, this.width, this.height, realX, realY);
    }
    //?}

    //? if >=26.1 {
    /*@Override
    public void onClose() {
        Objects.requireNonNull(this.minecraft).setScreenAndShow(this.parent);
    }
    *///?} else {
    @Override
    public void close() {
        Objects.requireNonNull(this.client).setScreen(this.parent);
    }
    //?}
}
