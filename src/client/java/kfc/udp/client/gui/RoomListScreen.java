package kfc.udp.client.gui;

import kfc.udp.client.KfcudpClient;
import kfc.udp.client.webrtc.PublicRoomBrowser;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * 공개 방 목록 — 카트라이더 공방 목록처럼, 내부적으로는 초대 코드를 주고받지만
 * 사용자한테는 "방 제목 + 방장 닉네임" 목록만 보여주고 클릭 한 번으로 접속시킨다.
 * 데이터 출처는 {@link PublicRoomBrowser} 클래스 주석 참고.
 * <p>
 * 각 행은 실제 클릭 처리는 (숨겨진, alpha=0) 버튼 위젯에 맡기고 — 히트 테스트/호버
 * 판정을 새로 구현할 필요 없이 그대로 재사용 — 시각적으로는 화면 렌더 단계에서
 * 기존 마크 멀티플레이 서버 목록처럼 검은 배경 + 회색 테두리 + 흰색 방 제목 +
 * 회색 닉네임으로 직접 그린다({@link #renderRows} 참고).
 * <p>
 * 코드로 직접 접속하는 기능은 예전엔 별도 화면(JoinRoomScreen)이었지만, 굳이 화면을
 * 나눌 이유가 없어서 이 화면 하단에 그대로 붙였다 — 리스트에서 보이는 방을 클릭해도,
 * 코드를 직접 입력해도 결과(코드로 접속)는 같기 때문.
 */
public class RoomListScreen extends Screen {

    /** 검색창 — 리스트 위, 화면 상단 고정. */
    private static final int SEARCH_Y     = 36;
    private static final int LIST_Y       = 60;
    private static final int ROW_H        = 26;
    private static final int VISIBLE_ROWS = 4;
    private static final int ROW_W        = 300;
    /** 빠른 시작 버튼 — 리스트 바로 아래(리스트 길이에 종속, 화면 상단 쪽 레이아웃). */
    private static final int QUICK_START_Y = LIST_Y + VISIBLE_ROWS * ROW_H + 8;

    /** 초대코드 섹션(체크박스/제목/입력란/접속/취소)은 리스트 길이와 무관하게 화면 하단에 고정
     * — {@link #init()}에서 {@code this.height} 기준으로 계산해 인스턴스 필드에 채운다. */
    private int forceRelayY, sectionTitleY, codeRowY, cancelY;

    /** 코드 입력란 폭 — 접속/취소 버튼이 이 폭 안에서 절반씩 나눠 쓴다(기존 JoinRoomScreen 구조와 동일). */
    private static final int CODE_FIELD_W = 200;

    private static final int ROW_BG_COLOR          = 0xFF000000;
    private static final int ROW_BORDER_COLOR      = 0xFFA0A0A0;
    private static final int ROW_BORDER_HOVER_COLOR = 0xFFFFFFFF;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT      = Component.translatable("instant-p2p.room_list.title");
    private static final Component EMPTY_TEXT      = Component.translatable("instant-p2p.room_list.empty");
    private static final Component SECTION_TEXT    = Component.translatable("instant-p2p.room_list.enter_code");
    private static final Component CODE_LABEL_TEXT = Component.translatable("instant-p2p.join_room.code_label");
    private static final Component JOIN_TEXT       = Component.translatable("instant-p2p.join_room.join");
    private static final Component FORCE_RELAY_TEXT = Component.translatable("instant-p2p.force_relay");
    private static final Component SEARCH_LABEL_TEXT = Component.translatable("instant-p2p.room_list.search_label");
    private static final Component QUICK_START_TEXT = Component.translatable("instant-p2p.room_list.quick_start");
    *///?} else {
    private static final Text TITLE_TEXT      = Text.translatable("instant-p2p.room_list.title");
    private static final Text EMPTY_TEXT      = Text.translatable("instant-p2p.room_list.empty");
    private static final Text SECTION_TEXT    = Text.translatable("instant-p2p.room_list.enter_code");
    private static final Text CODE_LABEL_TEXT = Text.translatable("instant-p2p.join_room.code_label");
    private static final Text JOIN_TEXT       = Text.translatable("instant-p2p.join_room.join");
    private static final Text FORCE_RELAY_TEXT = Text.translatable("instant-p2p.force_relay");
    private static final Text SEARCH_LABEL_TEXT = Text.translatable("instant-p2p.room_list.search_label");
    private static final Text QUICK_START_TEXT = Text.translatable("instant-p2p.room_list.quick_start");
    //?}

    private final Screen parent;
    private final PublicRoomBrowser browser = new PublicRoomBrowser();
    private int scrollIndex = 0;
    private String searchQuery = "";
    /** 검색어까지 반영된 현재 목록 — 화면에 보이는 {@link #VISIBLE_ROWS}줄뿐 아니라
     * 빠른 시작이 무작위로 고를 전체 후보 풀로도 쓴다. */
    private List<PublicRoomBrowser.RoomEntry> filteredRooms = List.of();
    private static final Random RANDOM = new Random();

    //? if >=26.1 {
    /*private final java.util.List<Button> rowButtons = new java.util.ArrayList<>();
    private final PublicRoomBrowser.RoomEntry[] rowRoom = new PublicRoomBrowser.RoomEntry[VISIBLE_ROWS];
    @Nullable private StringWidget emptyLabel;
    @Nullable private EditBox searchField;
    @Nullable private EditBox codeField;
    @Nullable private Button joinButton;
    @Nullable private Button quickStartButton;
    *///?} else {
    private final java.util.List<ButtonWidget> rowButtons = new java.util.ArrayList<>();
    private final PublicRoomBrowser.RoomEntry[] rowRoom = new PublicRoomBrowser.RoomEntry[VISIBLE_ROWS];
    @Nullable private TextWidget emptyLabel;
    @Nullable private TextFieldWidget searchField;
    @Nullable private TextFieldWidget codeField;
    @Nullable private ButtonWidget joinButton;
    @Nullable private ButtonWidget quickStartButton;
    //?}

    public RoomListScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    //? if >=26.1 {
    /*@Override
    protected void init() {
        int cx = this.width / 2;
        int listX = cx - ROW_W / 2;
        this.cancelY = this.height - 26;
        this.codeRowY = this.cancelY - 26;
        this.sectionTitleY = this.codeRowY - 14;
        this.forceRelayY = this.sectionTitleY - 24;

        this.searchField = new EditBox(this.font, listX, SEARCH_Y, ROW_W, 20, SEARCH_LABEL_TEXT);
        this.searchField.setMaxLength(32);
        this.searchField.setHint(Component.translatable("instant-p2p.room_list.search_placeholder").withStyle(ChatFormatting.DARK_GRAY));
        this.searchField.setResponder(text -> {
            this.searchQuery = text;
            this.refreshRooms();
        });
        this.addRenderableWidget(this.searchField);

        this.rowButtons.clear();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int row = i;
            Button btn = Button.builder(Component.empty(), b -> this.onRowClicked(row))
                    .bounds(listX, LIST_Y + i * ROW_H, ROW_W, ROW_H - 2)
                    .build();
            btn.visible = false;
            btn.setAlpha(0.0F); // 실제 렌더는 renderRows()가 서버 목록 스타일로 직접 그린다
            this.addRenderableWidget(btn);
            this.rowButtons.add(btn);
        }

        this.emptyLabel = new StringWidget(EMPTY_TEXT, this.font);
        this.emptyLabel.setX(cx - this.emptyLabel.getWidth() / 2);
        this.emptyLabel.setY(LIST_Y);
        this.addRenderableWidget(this.emptyLabel);

        this.quickStartButton = Button.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .bounds(listX, QUICK_START_Y, ROW_W, 20)
                .build();
        this.quickStartButton.active = false;
        this.addRenderableWidget(this.quickStartButton);

        this.addRenderableWidget(
                Checkbox.builder(FORCE_RELAY_TEXT, this.font)
                        .pos(cx - CODE_FIELD_W / 2, this.forceRelayY)
                        .selected(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                        .onValueChange((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                        .build()
        );

        this.codeField = new EditBox(this.font, cx - CODE_FIELD_W / 2, this.codeRowY, CODE_FIELD_W, 20, CODE_LABEL_TEXT);
        this.codeField.setMaxLength(20);
        this.codeField.setHint(Component.translatable("instant-p2p.join_room.code_placeholder").withStyle(ChatFormatting.DARK_GRAY));
        this.codeField.setResponder(text -> this.joinButton.active = !text.trim().isEmpty());
        this.addRenderableWidget(this.codeField);

        this.joinButton = Button.builder(JOIN_TEXT, btn -> this.onJoinByCode())
                .bounds(cx - CODE_FIELD_W / 2, this.cancelY, CODE_FIELD_W / 2 - 5, 20)
                .build();
        this.joinButton.active = false;
        this.addRenderableWidget(this.joinButton);

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                        .bounds(cx + 5, this.cancelY, CODE_FIELD_W / 2 - 5, 20)
                        .build()
        );

        this.setInitialFocus(this.codeField);
        this.browser.start();
        this.refreshRooms();
    }
    *///?} else {
    @Override
    protected void init() {
        int cx = this.width / 2;
        int listX = cx - ROW_W / 2;
        this.cancelY = this.height - 26;
        this.codeRowY = this.cancelY - 26;
        this.sectionTitleY = this.codeRowY - 14;
        this.forceRelayY = this.sectionTitleY - 24;

        this.searchField = new TextFieldWidget(this.textRenderer, listX, SEARCH_Y, ROW_W, 20, SEARCH_LABEL_TEXT);
        this.searchField.setMaxLength(32);
        this.searchField.setPlaceholder(Text.translatable("instant-p2p.room_list.search_placeholder").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        this.searchField.setChangedListener(text -> {
            this.searchQuery = text;
            this.refreshRooms();
        });
        this.addDrawableChild(this.searchField);

        this.rowButtons.clear();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int row = i;
            ButtonWidget btn = ButtonWidget.builder(Text.empty(), b -> this.onRowClicked(row))
                    .dimensions(listX, LIST_Y + i * ROW_H, ROW_W, ROW_H - 2)
                    .build();
            btn.visible = false;
            btn.setAlpha(0.0F); // 실제 렌더는 renderRows()가 서버 목록 스타일로 직접 그린다
            this.addDrawableChild(btn);
            this.rowButtons.add(btn);
        }

        this.emptyLabel = new TextWidget(EMPTY_TEXT, this.textRenderer);
        this.emptyLabel.setX(cx - this.emptyLabel.getWidth() / 2);
        this.emptyLabel.setY(LIST_Y);
        this.addDrawableChild(this.emptyLabel);

        this.quickStartButton = ButtonWidget.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .dimensions(listX, QUICK_START_Y, ROW_W, 20)
                .build();
        this.quickStartButton.active = false;
        this.addDrawableChild(this.quickStartButton);

        this.addDrawableChild(
                CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                        .pos(cx - CODE_FIELD_W / 2, this.forceRelayY)
                        .checked(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                        .callback((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                        .build()
        );

        this.codeField = new TextFieldWidget(this.textRenderer, cx - CODE_FIELD_W / 2, this.codeRowY, CODE_FIELD_W, 20, CODE_LABEL_TEXT);
        this.codeField.setMaxLength(20);
        this.codeField.setPlaceholder(Text.translatable("instant-p2p.join_room.code_placeholder").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        this.codeField.setChangedListener(text -> this.joinButton.active = !text.trim().isEmpty());
        this.addDrawableChild(this.codeField);

        this.joinButton = ButtonWidget.builder(JOIN_TEXT, btn -> this.onJoinByCode())
                .dimensions(cx - CODE_FIELD_W / 2, this.cancelY, CODE_FIELD_W / 2 - 5, 20)
                .build();
        this.joinButton.active = false;
        this.addDrawableChild(this.joinButton);

        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, b -> this.close())
                        .dimensions(cx + 5, this.cancelY, CODE_FIELD_W / 2 - 5, 20)
                        .build()
        );

        this.setInitialFocus(this.codeField);
        this.browser.start();
        this.refreshRooms();
    }
    //?}

    @Override
    public void tick() {
        super.tick();
        this.refreshRooms();
    }

    /** {@link PublicRoomBrowser}가 들고 있는 현재 방 목록에 검색어를 적용해 행들을 다시 채운다. */
    private void refreshRooms() {
        List<PublicRoomBrowser.RoomEntry> all = this.browser.getCurrentRooms();
        String q = this.searchQuery.trim().toLowerCase(Locale.ROOT);
        List<PublicRoomBrowser.RoomEntry> rooms = q.isEmpty() ? all
                : all.stream()
                        .filter(r -> r.title().toLowerCase(Locale.ROOT).contains(q)
                                || r.hostNickname().toLowerCase(Locale.ROOT).contains(q))
                        .toList();
        this.filteredRooms = rooms;

        int maxIndex = Math.max(0, rooms.size() - VISIBLE_ROWS);
        if (this.scrollIndex > maxIndex) this.scrollIndex = maxIndex;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = this.scrollIndex + i;
            var btn = this.rowButtons.get(i);
            if (idx < rooms.size()) {
                this.rowRoom[i] = rooms.get(idx);
                btn.visible = true;
            } else {
                this.rowRoom[i] = null;
                btn.visible = false;
            }
        }

        if (this.emptyLabel != null) this.emptyLabel.visible = rooms.isEmpty();
        if (this.quickStartButton != null) this.quickStartButton.active = !rooms.isEmpty();
    }

    private void onRowClicked(int row) {
        PublicRoomBrowser.RoomEntry r = this.rowRoom[row];
        if (r == null) return;
        //? if >=26.1 {
        /*assert this.minecraft != null;
        KfcudpClient.joinRoomByCode(this.minecraft, this.parent, r.code());
        *///?} else {
        assert this.client != null;
        KfcudpClient.joinRoomByCode(this.client, this.parent, r.code());
        //?}
    }

    /** 검색 결과(비어 있으면 전체) 중 무작위 방 하나에 바로 접속한다. */
    private void onQuickStart() {
        List<PublicRoomBrowser.RoomEntry> rooms = this.filteredRooms;
        if (rooms.isEmpty()) return;
        PublicRoomBrowser.RoomEntry r = rooms.get(RANDOM.nextInt(rooms.size()));
        //? if >=26.1 {
        /*assert this.minecraft != null;
        KfcudpClient.joinRoomByCode(this.minecraft, this.parent, r.code());
        *///?} else {
        assert this.client != null;
        KfcudpClient.joinRoomByCode(this.client, this.parent, r.code());
        //?}
    }

    //? if >=26.1 {
    /*private void onJoinByCode() {
        assert this.codeField != null && this.minecraft != null;
        String code = this.codeField.getValue().trim();
        if (code.isEmpty()) return;
        KfcudpClient.joinRoomByCode(this.minecraft, this.parent, code);
    }
    *///?} else {
    private void onJoinByCode() {
        assert this.codeField != null && this.client != null;
        String code = this.codeField.getText().trim();
        if (code.isEmpty()) return;
        KfcudpClient.joinRoomByCode(this.client, this.parent, code);
    }
    //?}

    //? if >=26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        if (input.key() == 257 && this.joinButton != null && this.joinButton.active) { // Enter
            this.onJoinByCode();
            return true;
        }
        return super.keyPressed(input);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == 257 && this.joinButton != null && this.joinButton.active) { // Enter
            this.onJoinByCode();
            return true;
        }
        return super.keyPressed(input);
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && this.joinButton != null && this.joinButton.active) { // Enter
            this.onJoinByCode();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxIndex = Math.max(0, this.filteredRooms.size() - VISIBLE_ROWS);
        if (maxIndex <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int newIndex = this.scrollIndex - (int) Math.signum(verticalAmount);
        newIndex = Math.max(0, Math.min(maxIndex, newIndex));
        if (newIndex != this.scrollIndex) {
            this.scrollIndex = newIndex;
            this.refreshRooms();
        }
        return true;
    }

    @Override
    public void removed() {
        super.removed();
        this.browser.stop();
    }

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        context.centeredText(this.font, this.title, this.width / 2, SEARCH_Y - 14, 0xFFFFFFFF);
        context.text(this.font, SECTION_TEXT, this.width / 2 - CODE_FIELD_W / 2, this.sectionTitleY, 0xFFFFFFFF);
        this.renderRows(context, mouseX, mouseY);
    }

    private void renderRows(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int x = cx - ROW_W / 2;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int y = LIST_Y + i * ROW_H;
            int h = ROW_H - 2;
            boolean hovered = this.rowButtons.get(i).isHovered();
            context.fill(x, y, x + ROW_W, y + h, ROW_BG_COLOR);
            context.outline(x, y, ROW_W, h, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.text(this.font, r.title(), x + 6, y + 3, 0xFFFFFFFF);
            context.text(this.font, r.hostNickname(), x + 6, y + 13, 0xFFA0A0A0);
        }
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, SEARCH_Y - 14, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, SECTION_TEXT, this.width / 2 - CODE_FIELD_W / 2, this.sectionTitleY, 0xFFFFFFFF);
        this.renderRows(context, mouseX, mouseY);
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int x = cx - ROW_W / 2;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int y = LIST_Y + i * ROW_H;
            int h = ROW_H - 2;
            boolean hovered = this.rowButtons.get(i).isHovered();
            context.fill(x, y, x + ROW_W, y + h, ROW_BG_COLOR);
            // drawBorder → drawStrokedRectangle 개명(1.21.9~) — DrawContext.java 클래스 주석 없음, javap로 확인.
            context.drawStrokedRectangle(x, y, ROW_W, h, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.title()), x + 6, y + 3, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.hostNickname()), x + 6, y + 13, 0xFFA0A0A0);
        }
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, SEARCH_Y - 14, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, SECTION_TEXT, this.width / 2 - CODE_FIELD_W / 2, this.sectionTitleY, 0xFFFFFFFF);
        this.renderRows(context, mouseX, mouseY);
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int x = cx - ROW_W / 2;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int y = LIST_Y + i * ROW_H;
            int h = ROW_H - 2;
            boolean hovered = this.rowButtons.get(i).isHovered();
            context.fill(x, y, x + ROW_W, y + h, ROW_BG_COLOR);
            context.drawBorder(x, y, ROW_W, h, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.title()), x + 6, y + 3, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.hostNickname()), x + 6, y + 13, 0xFFA0A0A0);
        }
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
