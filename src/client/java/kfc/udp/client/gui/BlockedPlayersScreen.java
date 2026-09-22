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
 * 이름으로 나열해 칸 오른쪽의 x 버튼으로 해제한다 — 방 목록의 차단 버튼과 같은 모양·자리.
 * <p>
 * 배치·스크롤은 방 목록과 같다 — 칸 크기는 고정, 열·행 수는 화면에 맞춰 정하고, 열 우선으로 채워
 * 휠 한 칸에 한 열씩 옆으로 넘기며, 화면에 안 보이는 열이 생기면 아래 섹션 맨 위에 가로 스크롤바가 뜬다.
 */
public class BlockedPlayersScreen extends Screen {

    private static final int HINT_Y = SEARCH_Y + (20 - 9) / 2;
    private static final int BACK_W = 200;
    private static final int CELL_H = ROW_H - 2;
    /** 닉네임이 차지할 수 있는 폭 — 좌측 여백 6px, 우측엔 x 버튼과 여백. */
    private static final int NAME_W = CELL_W - 6 - (3 + BLOCK_BTN + 3);

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT = Component.translatable("instant-p2p.blocked_players.title");
    private static final Component EMPTY_TEXT = Component.translatable("instant-p2p.blocked_players.empty");
    private static final Component HINT_TEXT  = Component.translatable("instant-p2p.blocked_players.hint");
    private static final Component ONLINE_TITLE_TEXT = Component.translatable("instant-p2p.online_players.title");
    private static final Component ONLINE_EMPTY_TEXT = Component.translatable("instant-p2p.online_players.empty");
    private static final Component ONLINE_HINT_TEXT  = Component.translatable("instant-p2p.online_players.hint");
    *///?} else {
    private static final Text TITLE_TEXT = Text.translatable("instant-p2p.blocked_players.title");
    private static final Text EMPTY_TEXT = Text.translatable("instant-p2p.blocked_players.empty");
    private static final Text HINT_TEXT  = Text.translatable("instant-p2p.blocked_players.hint");
    private static final Text ONLINE_TITLE_TEXT = Text.translatable("instant-p2p.online_players.title");
    private static final Text ONLINE_EMPTY_TEXT = Text.translatable("instant-p2p.online_players.empty");
    private static final Text ONLINE_HINT_TEXT  = Text.translatable("instant-p2p.online_players.hint");
    //?}

    private final Screen parent;
    /** true = 접속자 모드(ESC 메뉴 "플레이어 차단") — 지금 접속한 다른 플레이어를 x로 차단, o로 해제.
     * 접속한 사람 뒤로 이미 차단해서 나간 사람도 이어서 나온다(refreshGrid 참고) — 버튼을 따로 안 두고
     * 한 화면에서 다 보이게.
     * false = 차단 목록 모드(방 목록의 "차단 목록", 접속 전 화면 전용) — 차단한 사람을 나열해 x로 해제. */
    private final boolean online;
    private List<P2PBanManager.BannedEntry> entries = List.of();
    private int entriesVersion = -1;

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
        super(online ? ONLINE_TITLE_TEXT : TITLE_TEXT);
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
        }

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

    /** 왼쪽 클릭이 스크롤바나 x 버튼에 걸렸으면 처리하고 true — 마우스 입력 API 3분기가 같이 쓴다. */
    private boolean handleClick(double mouseX, double mouseY) {
        if (this.isOnScrollbarTrack(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollToTrackX(mouseX);
            return true;
        }
        int slot = this.unblockButtonAt(mouseX, mouseY);
        if (slot < 0) return false;
        playClick();
        P2PBanManager.BannedEntry e = this.cellEntry[slot];
        boolean unblock = !this.online || P2PBanManager.isPlayerBanned(e.uuid());
        // 바로 바꾸지 않고 확인 팝업부터 — 확인하면 그때 반영된다(목록은 다음 tick에 다시 읽힌다).
        this.popup.open(unblock ? "instant-p2p.confirm.unblock" : "instant-p2p.confirm.block", displayName(e), () -> {
            java.util.UUID targetUuid = java.util.UUID.fromString(e.uuid());
            if (unblock) {
                P2PBanManager.pardonPlayerByUuid(e.uuid());
                kfc.udp.client.webrtc.FreezeManager.requestUnfreeze(targetUuid);
            } else {
                P2PBanManager.banPlayer(e.uuid(), e.name(), "Blocked in game.");
                kfc.udp.client.KfcudpClient.kickBlockedPlayer(e.uuid()); // 방장이면 지금 방에서도 내보낸다
                kfc.udp.client.webrtc.FreezeManager.requestFreeze(targetUuid); // 개발자·서포터·방송인이면 즉시 동결 요청
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

    /** 접속자 모드에서 이미 차단한 사람 — 초록 o(해제)·회색 이름으로 구분한다. 나머지는 빨간 x(차단 목록 모드에선 해제,
     * 접속자 모드에선 차단). o도 x처럼 상하좌우 대칭 5×5라 같은 BLOCK_X_DX/DY로 가운데에 온다. */
    private boolean blockedInOnlineMode(P2PBanManager.BannedEntry e) {
        return this.online && P2PBanManager.isPlayerBanned(e.uuid());
    }

    private String glyph(P2PBanManager.BannedEntry e) {
        return this.blockedInOnlineMode(e) ? "o" : "x";
    }

    private int glyphColor(P2PBanManager.BannedEntry e) {
        return this.blockedInOnlineMode(e) ? 0xFF55FF55 : 0xFFFF5555;
    }

    private int nameColor(P2PBanManager.BannedEntry e) {
        return this.blockedInOnlineMode(e) ? 0xFF808080 : 0xFFFFFFFF;
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
        context.centeredText(this.font, (this.online ? ONLINE_HINT_TEXT : HINT_TEXT), cx, HINT_Y, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        if (this.entries.isEmpty()) {
            context.centeredText(this.font, (this.online ? ONLINE_EMPTY_TEXT : EMPTY_TEXT), cx, LIST_Y + (ROW_H - 9) / 2, 0xFFA0A0A0);
        }

        int hoveredBtn = this.unblockButtonAt(mouseX, mouseY);
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null) continue;
            int x = this.cellX[i], y = this.cellY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.outline(x, y, CELL_W, CELL_H, ROW_BORDER_COLOR);
            context.text(this.font, this.font.plainSubstrByWidth(displayName(e), NAME_W), x + 6, y + (CELL_H - 9) / 2 + 1, this.nameColor(e));
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBtn == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.outline(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBtn == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.text(this.font, this.glyph(e), bx + BLOCK_X_DX, by + BLOCK_X_DY, this.glyphColor(e));
        }

        if (this.hasScrollbar()) {
            int trackX = this.listX(), sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
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
        context.drawCenteredTextWithShadow(this.textRenderer, (this.online ? ONLINE_HINT_TEXT : HINT_TEXT), cx, HINT_Y, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        if (this.entries.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, (this.online ? ONLINE_EMPTY_TEXT : EMPTY_TEXT), cx, LIST_Y + (ROW_H - 9) / 2, 0xFFA0A0A0);
        }

        int hoveredBtn = this.unblockButtonAt(mouseX, mouseY);
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null) continue;
            int x = this.cellX[i], y = this.cellY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.drawStrokedRectangle(x, y, CELL_W, CELL_H, ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(displayName(e), NAME_W)), x + 6, y + (CELL_H - 9) / 2 + 1, this.nameColor(e));
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBtn == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.drawStrokedRectangle(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBtn == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.glyph(e)), bx + BLOCK_X_DX, by + BLOCK_X_DY, this.glyphColor(e));
        }

        if (this.hasScrollbar()) {
            int trackX = this.listX(), sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
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
        context.drawCenteredTextWithShadow(this.textRenderer, (this.online ? ONLINE_HINT_TEXT : HINT_TEXT), cx, HINT_Y, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        if (this.entries.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, (this.online ? ONLINE_EMPTY_TEXT : EMPTY_TEXT), cx, LIST_Y + (ROW_H - 9) / 2, 0xFFA0A0A0);
        }

        int hoveredBtn = this.unblockButtonAt(mouseX, mouseY);
        for (int i = 0; i < this.cellEntry.length; i++) {
            P2PBanManager.BannedEntry e = this.cellEntry[i];
            if (e == null) continue;
            int x = this.cellX[i], y = this.cellY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.drawBorder(x, y, CELL_W, CELL_H, ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(displayName(e), NAME_W)), x + 6, y + (CELL_H - 9) / 2 + 1, this.nameColor(e));
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBtn == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.drawBorder(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBtn == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.glyph(e)), bx + BLOCK_X_DX, by + BLOCK_X_DY, this.glyphColor(e));
        }

        if (this.hasScrollbar()) {
            int trackX = this.listX(), sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
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
