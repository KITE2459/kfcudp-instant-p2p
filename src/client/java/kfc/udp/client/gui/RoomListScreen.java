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
import net.minecraft.client.gui.components.Tooltip;
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

    /** 코드 입력란 폭 — 접속/취소 버튼이 이 폭 안에서 절반씩 나눠 쓴다(기존 JoinRoomScreen 구조와 동일).
     * 검색창/빠른 시작도 리스트 폭(ROW_W) 대신 이 폭에 맞춰 세로로 쌓는다. */
    private static final int CODE_FIELD_W = 200;

    /** 맨 위 첫 행 — 채널 입력란(좌측)과 빠른 시작(우측). CustomRoomScreen의
     * "채널(좌측 상단)/Regen Invite(우측 상단)"과 완전히 같은 좌표·크기 쌍이라
     * 호스트/접속자 화면 어디서든 같은 자리에 뜬다. 화면 제목은 이 행의 좌우
     * 버튼 사이 빈 중앙에 세로 중앙 정렬로 그린다(TITLE_Y — CustomRoomScreen의
     * TITLE_Y 계산식과 동일한 이유). */
    private static final int CHANNEL_Y = 10;
    private static final int CHANNEL_FIELD_W = 100;
    private static final int CHANNEL_FIELD_H = 20;
    static final int TITLE_Y = CHANNEL_Y + (CHANNEL_FIELD_H - 9) / 2;

    /** 검색창(좌측) + 중계 통신 강제(우측, 검색창이 차지하고 남는 폭) — 첫 행 바로
     * 아래 둘째 행. 예전엔 빠른 시작이 검색창과 다른 행에서 한 줄을 통째로 더 썼는데,
     * 빠른 시작을 첫 행 우측(Regen Invite 자리)으로 옮기면서 이 행 하나로 줄었다.
     * 검색창과 구분선 사이엔 숨 쉴 틈을 남겨서 구분선이 내용에 바짝 붙어 보이지
     * 않게 했다. */
    static final int SEARCH_Y      = CHANNEL_Y + CHANNEL_FIELD_H + 4;
    /** 상단(채널·빠른시작/검색·중계강제)과 리스트 사이 구분선 — 기존 마크 멀티플레이
     * 화면이 상단/목록/하단 3부분을 가로줄로 나누는 걸 그대로 참조했다
     * ({@link #renderRows}에서 실제로 그림). */
    static final int DIVIDER1_Y   = SEARCH_Y + 28;
    static final int LIST_Y       = DIVIDER1_Y + 6;
    static final int ROW_H        = 26;
    static final int VISIBLE_ROWS = 4;
    static final int ROW_W        = 300;
    /** 각 행 우측 끝에 남겨두는 "차단" 아이콘 폭 — 접속용 히든 버튼(rowButtons)의
     * 실제 폭을 이만큼 줄여서 겹치지 않게 한다({@link #onRowClicked}로 접속되는
     * 클릭 영역과 차단 클릭 영역이 아예 안 겹치므로 우선순위를 따로 처리할 필요가
     * 없다). 아이콘 자체는 renderRows에서 직접 그리고, 클릭은 mouseClicked에서
     * 직접 판정한다(P2PBanManager 클래스 주석 참고 — 개인 차단(=밴) 기능). */
    private static final int BLOCK_ICON_W = 34;
    static final int DIVIDER_COLOR = 0xFF555555;
    /** 두 구분선 사이(방 목록 섹션) 배경을 살짝 어둡게 — 기존 마크 멀티플레이/LAN
     * 화면의 서버 목록 섹션처럼 가운데가 위아래보다 더 짙어 보이게 한다. 각 행은
     * 자기 배경(ROW_BG_COLOR, 완전 불투명)을 그 위에 덮어 그리므로 이 배경은 행과
     * 행 사이·리스트 여백에서만 보인다. */
    static final int MIDDLE_SECTION_BG = 0x40000000;
    /** 실제로 화면에 보이는 줄 수(VISIBLE_ROWS)보다 하나 더 쓰는 버튼/행 슬롯 —
     * 부드러운 스크롤(scrollAnim) 중엔 한 줄이 위/아래 경계에서 소수점 위치로
     * 걸쳐 있을 수 있어서, 그 걸쳐 있는 여분의 한 줄을 항상 그릴 자리가 필요하다
     * (스크롤이 정확히 정수 위치에서 멈춰 있을 때는 이 여분 슬롯이 그냥 비게 된다). */
    static final int ROW_SLOTS = VISIBLE_ROWS + 1;

    /** 초대코드 섹션(제목/입력란/접속/취소)은 리스트 길이와 무관하게 화면 하단에 고정
     * — {@link #init()}에서 {@code this.height} 기준으로 계산해 인스턴스 필드에 채운다.
     * divider2Y도 여기 종속시켜서(리스트 쪽 고정값이 아니라) 작은 화면에서 리스트/구분선이
     * 하단 섹션을 침범하지 않게 한다. */
    private int sectionTitleY, codeRowY, cancelY, divider2Y;

    static final int ROW_BG_COLOR          = 0xFF000000;
    static final int ROW_BORDER_COLOR      = 0xFFA0A0A0;
    static final int ROW_BORDER_HOVER_COLOR = 0xFFFFFFFF;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT      = Component.translatable("instant-p2p.room_list.title");
    private static final Component EMPTY_TEXT      = Component.translatable("instant-p2p.room_list.empty");
    private static final Component SECTION_TEXT    = Component.translatable("instant-p2p.room_list.enter_code");
    private static final Component CODE_LABEL_TEXT = Component.translatable("instant-p2p.join_room.code_label");
    private static final Component JOIN_TEXT       = Component.translatable("instant-p2p.join_room.join");
    private static final Component FORCE_RELAY_TEXT = Component.translatable("instant-p2p.force_relay");
    private static final Component SEARCH_LABEL_TEXT = Component.translatable("instant-p2p.room_list.search_label");
    private static final Component QUICK_START_TEXT = Component.translatable("instant-p2p.room_list.quick_start");
    private static final Component CHANNEL_TEXT = Component.translatable("instant-p2p.channel_label");
    private static final Component BLOCKED_LIST_TEXT = Component.translatable("instant-p2p.room_list.blocked_list");
    private static final Component BLOCK_TOOLTIP_TEXT = Component.translatable("instant-p2p.room_list.block_tooltip");
    *///?} else {
    private static final Text TITLE_TEXT      = Text.translatable("instant-p2p.room_list.title");
    private static final Text EMPTY_TEXT      = Text.translatable("instant-p2p.room_list.empty");
    private static final Text SECTION_TEXT    = Text.translatable("instant-p2p.room_list.enter_code");
    private static final Text CODE_LABEL_TEXT = Text.translatable("instant-p2p.join_room.code_label");
    private static final Text JOIN_TEXT       = Text.translatable("instant-p2p.join_room.join");
    private static final Text FORCE_RELAY_TEXT = Text.translatable("instant-p2p.force_relay");
    private static final Text SEARCH_LABEL_TEXT = Text.translatable("instant-p2p.room_list.search_label");
    private static final Text QUICK_START_TEXT = Text.translatable("instant-p2p.room_list.quick_start");
    private static final Text CHANNEL_TEXT = Text.translatable("instant-p2p.channel_label");
    private static final Text BLOCKED_LIST_TEXT = Text.translatable("instant-p2p.room_list.blocked_list");
    private static final Text BLOCK_TOOLTIP_TEXT = Text.translatable("instant-p2p.room_list.block_tooltip");
    //?}

    private final Screen parent;
    private final PublicRoomBrowser browser = new PublicRoomBrowser();
    private int scrollIndex = 0;
    /** 화면에 실제로 그려지는(렌더링용) 연속적인 스크롤 위치 — 정수인
     * {@link #scrollIndex}(목표)로 매 tick마다 부드럽게 근접해간다(easing, tick() 참고).
     * 정수 부분(baseIndex)은 몇 번째 방부터 그릴지, 소수 부분(frac)은 그 방들을
     * 위로 얼마나(0~1행) 밀어 그릴지를 정한다 — refreshRooms()가 이 값으로
     * rowRoom/rowY를 매 tick 다시 계산한다. */
    private double scrollAnim = 0;
    private String searchQuery = "";
    /** 검색어까지 반영된 현재 목록 — 화면에 보이는 {@link #VISIBLE_ROWS}줄뿐 아니라
     * 빠른 시작이 무작위로 고를 전체 후보 풀로도 쓴다. */
    private List<PublicRoomBrowser.RoomEntry> filteredRooms = List.of();
    private static final Random RANDOM = new Random();

    //? if >=26.1 {
    /*private final java.util.List<Button> rowButtons = new java.util.ArrayList<>();
    private final PublicRoomBrowser.RoomEntry[] rowRoom = new PublicRoomBrowser.RoomEntry[ROW_SLOTS];
    private final int[] rowY = new int[ROW_SLOTS];
    @Nullable private StringWidget emptyLabel;
    @Nullable private EditBox searchField;
    @Nullable private EditBox codeField;
    @Nullable private EditBox channelField;
    @Nullable private Button joinButton;
    @Nullable private Button quickStartButton;
    *///?} else {
    private final java.util.List<ButtonWidget> rowButtons = new java.util.ArrayList<>();
    private final PublicRoomBrowser.RoomEntry[] rowRoom = new PublicRoomBrowser.RoomEntry[ROW_SLOTS];
    private final int[] rowY = new int[ROW_SLOTS];
    @Nullable private TextWidget emptyLabel;
    @Nullable private TextFieldWidget searchField;
    @Nullable private TextFieldWidget codeField;
    @Nullable private TextFieldWidget channelField;
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
        // "초대코드로 입장" 행은 오른쪽에 차단 목록 버튼이 붙으므로 버튼 한 줄(20px) 높이로
        // 잡고, 제목 글자는 그 행 안에서 세로 가운데에 둔다.
        int sectionRowY = this.codeRowY - 26;
        this.sectionTitleY = sectionRowY + (20 - 9) / 2;
        this.divider2Y = sectionRowY - 6;

        // 채널 입력란 — 클래스 상수(CHANNEL_Y 등) 주석 참고. cx-155는 CustomRoomScreen의
        // Regen Invite(우측 상단, (cx+155)-100)와 중심 기준 좌우반전된 위치.
        this.channelField = new EditBox(this.font, cx - 155, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H, CHANNEL_TEXT);
        this.channelField.setMaxLength(32);
        this.channelField.setValue(kfc.udp.client.webrtc.P2PConfig.getChannel());
        this.channelField.setTooltip(Tooltip.create(CHANNEL_TEXT));
        this.channelField.setResponder(text -> {
            kfc.udp.client.webrtc.P2PConfig.setChannel(text);
            this.refreshRooms();
        });
        this.addRenderableWidget(this.channelField);

        // 빠른 시작 — CustomRoomScreen의 Regen Invite와 완전히 같은 자리·크기
        // (우측 상단). 채널 입력란과 한 행을 이뤄 더 이상 별도 행을 안 쓴다.
        this.quickStartButton = Button.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .bounds((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                .build();
        this.quickStartButton.active = false;
        this.addRenderableWidget(this.quickStartButton);

        // 중계 통신 강제 — 검색창과 같은 행, 우측. 검색창 폭을 정하려면 이 체크박스의
        // 실제 렌더 폭(라벨 길이에 따라 달라짐, 언어별로도 다름)을 먼저 알아야 하므로
        // 검색창보다 먼저 만든다 — 우측 끝을 리스트 우측 끝(listX+ROW_W)에 맞추고,
        // 검색창은 그 앞까지만 채운다(좌측은 listX로 고정).
        var forceRelayCheckbox = Checkbox.builder(FORCE_RELAY_TEXT, this.font)
                .pos(0, SEARCH_Y)
                .selected(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                .onValueChange((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                .build();
        int forceRelayX = (listX + ROW_W) - forceRelayCheckbox.getWidth();
        forceRelayCheckbox.setX(forceRelayX);
        this.addRenderableWidget(forceRelayCheckbox);

        int searchFieldW = forceRelayX - 10 - listX;
        this.searchField = new EditBox(this.font, listX, SEARCH_Y, searchFieldW, 20, SEARCH_LABEL_TEXT);
        this.searchField.setMaxLength(32);
        this.searchField.setHint(Component.translatable("instant-p2p.room_list.search_placeholder").withStyle(ChatFormatting.DARK_GRAY));
        this.searchField.setResponder(text -> {
            this.searchQuery = text;
            this.refreshRooms();
        });
        this.addRenderableWidget(this.searchField);

        this.rowButtons.clear();
        for (int i = 0; i < ROW_SLOTS; i++) {
            int row = i;
            Button btn = Button.builder(Component.empty(), b -> this.onRowClicked(row))
                    .bounds(listX, LIST_Y + i * ROW_H, ROW_W - BLOCK_ICON_W, ROW_H - 2)
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

        // 차단 목록 관리 — "초대코드로 입장" 제목과 같은 행, 취소 버튼과 같은 열·크기.
        this.addRenderableWidget(
                Button.builder(BLOCKED_LIST_TEXT, b ->
                        Objects.requireNonNull(this.minecraft).setScreenAndShow(new BlockedPlayersScreen(this)))
                        .bounds(cx + 5, this.codeRowY - 26, CODE_FIELD_W / 2 - 5, 20)
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
        // "초대코드로 입장" 행은 오른쪽에 차단 목록 버튼이 붙으므로 버튼 한 줄(20px) 높이로
        // 잡고, 제목 글자는 그 행 안에서 세로 가운데에 둔다.
        int sectionRowY = this.codeRowY - 26;
        this.sectionTitleY = sectionRowY + (20 - 9) / 2;
        this.divider2Y = sectionRowY - 6;

        // 채널 입력란 — 클래스 상수(CHANNEL_Y 등) 주석 참고. cx-155는 CustomRoomScreen의
        // Regen Invite(우측 상단, (cx+155)-100)와 중심 기준 좌우반전된 위치.
        this.channelField = new TextFieldWidget(this.textRenderer, cx - 155, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H, CHANNEL_TEXT);
        this.channelField.setMaxLength(32);
        this.channelField.setText(kfc.udp.client.webrtc.P2PConfig.getChannel());
        this.channelField.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(CHANNEL_TEXT));
        this.channelField.setChangedListener(text -> {
            kfc.udp.client.webrtc.P2PConfig.setChannel(text);
            this.refreshRooms();
        });
        this.addDrawableChild(this.channelField);

        // 빠른 시작 — CustomRoomScreen의 Regen Invite와 완전히 같은 자리·크기
        // (우측 상단). 채널 입력란과 한 행을 이뤄 더 이상 별도 행을 안 쓴다.
        this.quickStartButton = ButtonWidget.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .dimensions((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                .build();
        this.quickStartButton.active = false;
        this.addDrawableChild(this.quickStartButton);

        // 중계 통신 강제 — 검색창과 같은 행, 우측. 검색창 폭을 정하려면 이 체크박스의
        // 실제 렌더 폭(라벨 길이에 따라 달라짐, 언어별로도 다름)을 먼저 알아야 하므로
        // 검색창보다 먼저 만든다 — 우측 끝을 리스트 우측 끝(listX+ROW_W)에 맞추고,
        // 검색창은 그 앞까지만 채운다(좌측은 listX로 고정).
        var forceRelayCheckbox = CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                .pos(0, SEARCH_Y)
                .checked(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                .callback((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                .build();
        int forceRelayX = (listX + ROW_W) - forceRelayCheckbox.getWidth();
        forceRelayCheckbox.setX(forceRelayX);
        this.addDrawableChild(forceRelayCheckbox);

        int searchFieldW = forceRelayX - 10 - listX;
        this.searchField = new TextFieldWidget(this.textRenderer, listX, SEARCH_Y, searchFieldW, 20, SEARCH_LABEL_TEXT);
        this.searchField.setMaxLength(32);
        this.searchField.setPlaceholder(Text.translatable("instant-p2p.room_list.search_placeholder").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        this.searchField.setChangedListener(text -> {
            this.searchQuery = text;
            this.refreshRooms();
        });
        this.addDrawableChild(this.searchField);

        this.rowButtons.clear();
        for (int i = 0; i < ROW_SLOTS; i++) {
            int row = i;
            ButtonWidget btn = ButtonWidget.builder(Text.empty(), b -> this.onRowClicked(row))
                    .dimensions(listX, LIST_Y + i * ROW_H, ROW_W - BLOCK_ICON_W, ROW_H - 2)
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

        // 차단 목록 관리 — "초대코드로 입장" 제목과 같은 행, 취소 버튼과 같은 열·크기.
        this.addDrawableChild(
                ButtonWidget.builder(BLOCKED_LIST_TEXT, b ->
                        Objects.requireNonNull(this.client).setScreen(new BlockedPlayersScreen(this)))
                        .dimensions(cx + 5, this.codeRowY - 26, CODE_FIELD_W / 2 - 5, 20)
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
        // scrollIndex(정수, 목표) 쪽으로 매 tick 조금씩 근접 — 딱 튀는 대신 부드럽게
        // 미끄러지듯 움직인다. 거의 다 왔으면 그냥 목표값으로 스냅해서(0.02행 미만
        // 차이) 부동소수점이 영원히 근접만 하고 안 끝나는 걸 방지한다.
        this.scrollAnim += (this.scrollIndex - this.scrollAnim) * 0.35;
        if (Math.abs(this.scrollIndex - this.scrollAnim) < 0.02) this.scrollAnim = this.scrollIndex;
        this.refreshRooms();
    }

    //? if >=26.1 {
    /*private String myUuid() {
        return this.minecraft != null && this.minecraft.getUser() != null
                ? this.minecraft.getUser().getProfileId().toString() : null;
    }
    *///?} else {
    private String myUuid() {
        if (this.client == null) return null;
        java.util.UUID id = this.client.getSession().getUuidOrNull();
        return id != null ? id.toString() : null;
    }
    //?}

    // 필터 결과 캐시 — 입력(방 목록 참조·채널·검색어·밴 목록 버전)이 그대로면 매 tick(초당
    // 20번) 전체 목록을 다시 거르지 않는다. 방 목록은 바뀔 때만 새 리스트로 교체되므로
    // 참조 비교로 충분하다.
    private List<PublicRoomBrowser.RoomEntry> filterSource;
    private String filterChannel;
    private String filterQuery;
    private int filterBanVersion = -1;

    /** {@link PublicRoomBrowser}가 들고 있는 현재 방 목록에 채널·버전·차단·검색어를
     * 적용해 행들을 다시 채운다. 필터를 여기(매 tick)에서 거는 이유는
     * PublicRoomBrowser 클래스 주석 참고 — 입력란에서 채널을 바로 바꿔도 다음
     * tick에 곧장 반영된다. 버전은 사용자가 못 건드리는 자동 필터(P2PConfig.MC_VERSION
     * 클래스 주석 참고) — 애초에 접속이 안 되는 상대는 안 보여준다. 차단은 양방향
     * (내가 차단한 방장의 방 / 나를 차단한 방장의 방)이라 두 줄로 나눠서 건다
     * (P2PBanManager 클래스 주석 참고). */
    private void refreshRooms() {
        List<PublicRoomBrowser.RoomEntry> all = this.browser.getCurrentRooms();
        String channel = kfc.udp.client.webrtc.P2PConfig.getChannel();
        int banVersion = kfc.udp.client.webrtc.P2PBanManager.banListVersion();
        if (all != this.filterSource || !channel.equals(this.filterChannel)
                || !this.searchQuery.equals(this.filterQuery) || banVersion != this.filterBanVersion) {
            String myUuid = this.myUuid();
            String q = this.searchQuery.trim().toLowerCase(Locale.ROOT);
            this.filteredRooms = all.stream()
                    .filter(r -> kfc.udp.client.webrtc.P2PConfig.channelMatches(r.channel(), channel))
                    .filter(r -> r.version().equals(kfc.udp.client.webrtc.P2PConfig.MC_VERSION))
                    .filter(r -> !kfc.udp.client.webrtc.P2PBanManager.isPlayerBanned(r.hostUuid()))
                    .filter(r -> !kfc.udp.client.webrtc.P2PBanManager.isListedIn(r.blockedUuids(), myUuid))
                    .filter(r -> q.isEmpty()
                            || r.title().toLowerCase(Locale.ROOT).contains(q)
                            || r.hostNickname().toLowerCase(Locale.ROOT).contains(q))
                    .toList();
            this.filterSource = all;
            this.filterChannel = channel;
            this.filterQuery = this.searchQuery;
            this.filterBanVersion = banVersion;
        }
        List<PublicRoomBrowser.RoomEntry> rooms = this.filteredRooms;

        int maxIndex = Math.max(0, rooms.size() - VISIBLE_ROWS);
        if (this.scrollIndex > maxIndex) this.scrollIndex = maxIndex;
        // 목록이 갑자기 줄어들면(필터 변경 등) 애니메이션 목표도 즉시 범위 안으로
        // 당긴다 — 위로는 자연스럽게 이징되도록 그대로 둔다(줄어들 때만 강제 클램프).
        if (this.scrollAnim > maxIndex) this.scrollAnim = maxIndex;

        // scrollAnim(연속값)의 정수부는 "몇 번째 방부터 보이는가", 소수부는 그 방들을
        // 위로 얼마나(0~1행분) 밀어 그릴지 — ROW_SLOTS(=VISIBLE_ROWS+1)개의 슬롯에
        // 걸쳐 채운다. 화면(위쪽 경계~아래쪽 경계) 밖으로 걸치는 마지막 한 슬롯은
        // renderRows의 스크롤바 fade/스크롤바 clip으로 가려진다.
        int baseIndex = (int) Math.floor(this.scrollAnim);
        int pixelOffset = (int) Math.round((this.scrollAnim - baseIndex) * ROW_H);
        for (int i = 0; i < ROW_SLOTS; i++) {
            int idx = baseIndex + i;
            var btn = this.rowButtons.get(i);
            int y = LIST_Y + i * ROW_H - pixelOffset;
            this.rowY[i] = y;
            btn.setY(y);
            if (idx >= 0 && idx < rooms.size()) {
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
        // 커서가 방 목록 섹션 안에 있을 때만 스크롤한다 — 검색란/초대 코드 섹션에서
        // 휠을 굴렸는데 안 보이는 방 목록이 넘어가버리는 건 직관적이지 않다.
        if (mouseY < DIVIDER1_Y || mouseY > this.divider2Y)
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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

    /** 스크롤바 트랙 폭. 방 목록 우측 바로 밖(4px 띔)에 그린다 — 행 배경을
     * 침범하지 않게 별도 공간을 쓴다. */
    static final int SCROLLBAR_W = 6;
    static final int SCROLLBAR_GAP = 4;

    /** 방 목록이 한 화면(VISIBLE_ROWS)에 다 들어오면(스크롤할 게 없으면) 그리지도,
     * 드래그를 받지도 않는다. */
    private boolean hasScrollbar() {
        return this.filteredRooms.size() > VISIBLE_ROWS;
    }

    private int scrollbarX() {
        return this.width / 2 + ROW_W / 2 + SCROLLBAR_GAP;
    }

    /** 스크롤바 썸(막대)의 세로 위치·높이 — {y, height}. 목록이 길수록 썸이
     * 짧아진다(트랙 대비 VISIBLE_ROWS/전체 비율) — 최소 10px은 항상 보장해서
     * 목록이 아주 길어도 손으로 집을 수 있는 크기를 유지한다. */
    private int[] scrollbarThumb() {
        int trackH = VISIBLE_ROWS * ROW_H;
        int total = this.filteredRooms.size();
        int thumbH = Math.max(10, trackH * VISIBLE_ROWS / total);
        int maxIndex = Math.max(1, total - VISIBLE_ROWS);
        // scrollIndex(목표)가 아니라 scrollAnim(현재 애니메이션 위치)을 써서 썸도
        // 목록과 같은 속도로 부드럽게 미끄러지게 한다.
        int thumbY = LIST_Y + (int) Math.round((trackH - thumbH) * this.scrollAnim / maxIndex);
        return new int[]{thumbY, thumbH};
    }

    /** 트랙 안의 임의 Y 위치(클릭/드래그)를 scrollIndex로 환산 — 썸 중심이 그
     * Y에 오도록 계산한다. */
    private void scrollToTrackY(double mouseY) {
        int trackH = VISIBLE_ROWS * ROW_H;
        int total = this.filteredRooms.size();
        int maxIndex = total - VISIBLE_ROWS;
        if (maxIndex <= 0) return;
        int thumbH = scrollbarThumb()[1];
        double usable = trackH - thumbH;
        double rel = (mouseY - LIST_Y - thumbH / 2.0) / usable;
        int newIndex = (int) Math.round(rel * maxIndex);
        newIndex = Math.max(0, Math.min(maxIndex, newIndex));
        if (newIndex != this.scrollIndex) {
            this.scrollIndex = newIndex;
            this.refreshRooms();
        }
    }

    private boolean draggingScrollbar = false;

    /** 스크롤바 트랙(SCROLLBAR_W 폭, LIST_Y~+VISIBLE_ROWS*ROW_H)을 클릭했는지 —
     * 마우스 입력 API가 버전마다 달라서(아래 3분기) 판정 자체는 여기 하나로 뽑아뒀다. */
    private boolean isOnScrollbarTrack(double mouseX, double mouseY) {
        if (!this.hasScrollbar()) return false;
        int sbX = this.scrollbarX();
        int trackH = VISIBLE_ROWS * ROW_H;
        return mouseX >= sbX && mouseX < sbX + SCROLLBAR_W && mouseY >= LIST_Y && mouseY < LIST_Y + trackH;
    }

    /** 지금 보이는 행들 중 (mouseX,mouseY)가 우측 "차단" 아이콘 위에 있는 행의
     * 인덱스, 없으면 -1. rowButtons(접속용 히든 버튼)는 BLOCK_ICON_W만큼 폭을
     * 줄여둬서 이 영역과 안 겹친다. */
    private int blockIconRowAt(double mouseX, double mouseY) {
        int cx = this.width / 2;
        int iconX = cx + ROW_W / 2 - BLOCK_ICON_W;
        for (int i = 0; i < ROW_SLOTS; i++) {
            if (this.rowRoom[i] == null) continue;
            int y = this.rowY[i];
            int h = ROW_H - 2;
            // 부드러운 스크롤 중엔 행이 목록 경계 밖으로 걸쳐 있을 수 있다 —
            // renderRows가 그 부분을 스크롤바 clip으로 가리므로, 클릭도 안 보이는
            // 부분은 화면에 실제로 보이는 영역(LIST_Y~+VISIBLE_ROWS*ROW_H)으로 제한한다.
            int visibleTop = Math.max(y, LIST_Y);
            int visibleBottom = Math.min(y + h, LIST_Y + VISIBLE_ROWS * ROW_H);
            if (mouseX >= iconX && mouseX < iconX + BLOCK_ICON_W && mouseY >= visibleTop && mouseY < visibleBottom) {
                return i;
            }
        }
        return -1;
    }

    /** 그 행 방장의 차단(=밴) 여부를 뒤집는다 — P2PBanManager 클래스 주석 참고,
     * 인게임 /ban·/pardon과 완전히 같은 목록을 그대로 건드리는 것이라 즉시
     * 로컬에 반영되고, 목록은 다음 tick(refreshRooms)에 자동으로 다시 걸러진다. */
    private void toggleBlockAt(int row) {
        PublicRoomBrowser.RoomEntry r = this.rowRoom[row];
        if (r == null) return;
        if (kfc.udp.client.webrtc.P2PBanManager.isPlayerBanned(r.hostUuid())) {
            kfc.udp.client.webrtc.P2PBanManager.pardonPlayerByUuid(r.hostUuid());
        } else {
            kfc.udp.client.webrtc.P2PBanManager.banPlayer(r.hostUuid(), r.hostNickname(), "Blocked from room list.");
        }
    }

    /** 행이 목록 뷰포트(LIST_Y~+VISIBLE_ROWS*ROW_H) 경계를 얼마나 벗어났는지에 따라
     * 0(완전히 벗어남)~1(완전히 안쪽)의 감쇠 배율을 낸다 — 부드러운 스크롤 중 위/아래
     * 경계에 걸친 행이 뚝 끊기지 않고(scissor로 잘리기 직전) 옅어지도록 색상 알파에
     * 곱해서 쓴다(renderRows 참고). */
    static float rowFade(int y, int h) {
        int overlapTop = Math.max(0, LIST_Y - y);
        int overlapBottom = Math.max(0, (y + h) - (LIST_Y + VISIBLE_ROWS * ROW_H));
        int overlap = Math.max(overlapTop, overlapBottom);
        return 1f - Math.max(0f, Math.min(1f, overlap / (float) ROW_H));
    }

    /** ARGB 색상의 알파 채널에만 scale(0~1)을 곱한다. */
    static int fadeColor(int argb, float scale) {
        int a = (argb >>> 24) & 0xFF;
        int newA = Math.max(0, Math.min(255, Math.round(a * scale)));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }

    // 마우스 클릭/드래그/릴리즈 콜백 시그니처가 1.21.9에서 (double,double,int,...)에서
    // 값 타입(Click/MouseButtonEvent) 하나로 바뀌었다 — keyPressed의 KeyEvent/KeyInput/
    // 순수 int 3분기와 같은 이유·같은 패턴(독립된 3개 블록, 안 겹치는 조건이라
    // if/else로 안 엮어도 버전마다 정확히 하나만 살아남는다).
    //? if >=26.1 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == 0 && this.isOnScrollbarTrack(click.x(), click.y())) {
            this.draggingScrollbar = true;
            this.scrollToTrackY(click.y());
            return true;
        }
        if (click.button() == 0) {
            int row = this.blockIconRowAt(click.x(), click.y());
            if (row >= 0) { this.toggleBlockAt(row); return true; }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollToTrackY(click.y());
            // 드래그 중엔 easing 없이 마우스를 그대로 따라가야 손맛이 정확하다 —
            // scrollAnim이 뒤에서 부드럽게 쫓아오면 오히려 밀리는 느낌만 든다.
            this.scrollAnim = this.scrollIndex;
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
        if (click.button() == 0 && this.isOnScrollbarTrack(click.x(), click.y())) {
            this.draggingScrollbar = true;
            this.scrollToTrackY(click.y());
            return true;
        }
        if (click.button() == 0) {
            int row = this.blockIconRowAt(click.x(), click.y());
            if (row >= 0) { this.toggleBlockAt(row); return true; }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollToTrackY(click.y());
            // 드래그 중엔 easing 없이 마우스를 그대로 따라가야 손맛이 정확하다 —
            // scrollAnim이 뒤에서 부드럽게 쫓아오면 오히려 밀리는 느낌만 든다.
            this.scrollAnim = this.scrollIndex;
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
        if (button == 0 && this.isOnScrollbarTrack(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollToTrackY(mouseY);
            return true;
        }
        if (button == 0) {
            int row = this.blockIconRowAt(mouseX, mouseY);
            if (row >= 0) { this.toggleBlockAt(row); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollToTrackY(mouseY);
            // 드래그 중엔 easing 없이 마우스를 그대로 따라가야 손맛이 정확하다 —
            // scrollAnim이 뒤에서 부드럽게 쫓아오면 오히려 밀리는 느낌만 든다.
            this.scrollAnim = this.scrollIndex;
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

    @Override
    public void removed() {
        super.removed();
        this.browser.stop();
    }

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        context.centeredText(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        context.text(this.font, SECTION_TEXT, this.width / 2 - CODE_FIELD_W / 2, this.sectionTitleY, 0xFFFFFFFF);
        // 기존 마크 멀티플레이 화면의 상단/목록/하단 3분할 구분선을 그대로 참조 —
        // 검색·빠른시작 / 방 목록 / 초대코드 섹션을 가로줄로 나눠 보여준다. 두 구분선
        // 사이는 배경을 살짝 어둡게(MIDDLE_SECTION_BG) 칠해 목록 섹션이 구분되게 한다.
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        this.renderRows(context, mouseX, mouseY);
    }

    private void renderRows(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int x = cx - ROW_W / 2;
        // 목록 뷰포트 밖으로 걸친 부분은 잘라낸다 — rowFade가 그 경계로 갈수록
        // 미리 옅어지게 해서, 잘리는 순간이 뚝 끊기지 않고 부드럽게 느껴지게 한다.
        context.enableScissor(0, LIST_Y, this.width, LIST_Y + VISIBLE_ROWS * ROW_H);
        for (int i = 0; i < ROW_SLOTS; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int y = this.rowY[i];
            int h = ROW_H - 2;
            float fade = rowFade(y, h);
            if (fade <= 0f) continue;
            boolean hovered = this.rowButtons.get(i).isHovered();
            context.fill(x, y, x + ROW_W, y + h, fadeColor(ROW_BG_COLOR, fade));
            context.outline(x, y, ROW_W, h, fadeColor(hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR, fade));
            context.text(this.font, r.title(), x + 6, y + 3, fadeColor(0xFFFFFFFF, fade));
            context.text(this.font, r.hostNickname() + "  (" + r.currentPlayers() + "/" + r.maxPlayers() + ")", x + 6, y + 13, fadeColor(0xFFA0A0A0, fade));
            // 개인 차단 토글 — 이미 차단했으면 초록 체크(해제하려면 클릭), 아니면
            // 빨간 X(차단하려면 클릭). 언어 상관없이 짧은 기호만 쓴다(BLOCK_ICON_W가
            // 34px라 긴 라벨은 안 들어감) — 클릭 판정은 blockIconRowAt/mouseClicked.
            boolean blocked = kfc.udp.client.webrtc.P2PBanManager.isPlayerBanned(r.hostUuid());
            context.text(this.font, blocked ? "✓" : "X",
                    x + ROW_W - BLOCK_ICON_W + 12, y + 8, fadeColor(blocked ? 0xFF55FF55 : 0xFFFF5555, fade));
        }
        context.disableScissor();
        if (this.hasScrollbar()) {
            int sbX = this.scrollbarX();
            context.fill(sbX, LIST_Y, sbX + SCROLLBAR_W, LIST_Y + VISIBLE_ROWS * ROW_H, ROW_BG_COLOR);
            int[] thumb = this.scrollbarThumb();
            context.fill(sbX, thumb[0], sbX + SCROLLBAR_W, thumb[0] + thumb[1], ROW_BORDER_HOVER_COLOR);
        }
        // 차단 아이콘에 마우스를 올리면 클릭 시 무슨 일이 생기는지 알려준다 — 실제로
        // 화면에 뜬 행은 이미 필터를 통과한 것들이라 항상 "아직 안 차단됨"(X) 상태뿐
        // 이므로(P2PBanManager 클래스 주석 참고), 툴팁도 "차단합니다" 한 가지면 된다.
        if (this.blockIconRowAt(mouseX, mouseY) >= 0) {
            context.setTooltipForNextFrame(this.font, BLOCK_TOOLTIP_TEXT, mouseX, mouseY);
        }
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, SECTION_TEXT, this.width / 2 - CODE_FIELD_W / 2, this.sectionTitleY, 0xFFFFFFFF);
        // 기존 마크 멀티플레이 화면의 상단/목록/하단 3분할 구분선을 그대로 참조 —
        // 검색·빠른시작 / 방 목록 / 초대코드 섹션을 가로줄로 나눠 보여준다. 두 구분선
        // 사이는 배경을 살짝 어둡게(MIDDLE_SECTION_BG) 칠해 목록 섹션이 구분되게 한다.
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        this.renderRows(context, mouseX, mouseY);
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int x = cx - ROW_W / 2;
        // 목록 뷰포트 밖으로 걸친 부분은 잘라낸다 — rowFade가 그 경계로 갈수록
        // 미리 옅어지게 해서, 잘리는 순간이 뚝 끊기지 않고 부드럽게 느껴지게 한다.
        context.enableScissor(0, LIST_Y, this.width, LIST_Y + VISIBLE_ROWS * ROW_H);
        for (int i = 0; i < ROW_SLOTS; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int y = this.rowY[i];
            int h = ROW_H - 2;
            float fade = rowFade(y, h);
            if (fade <= 0f) continue;
            boolean hovered = this.rowButtons.get(i).isHovered();
            context.fill(x, y, x + ROW_W, y + h, fadeColor(ROW_BG_COLOR, fade));
            // drawBorder → drawStrokedRectangle 개명(1.21.9~) — DrawContext.java 클래스 주석 없음, javap로 확인.
            context.drawStrokedRectangle(x, y, ROW_W, h, fadeColor(hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR, fade));
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.title()), x + 6, y + 3, fadeColor(0xFFFFFFFF, fade));
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.hostNickname() + "  (" + r.currentPlayers() + "/" + r.maxPlayers() + ")"), x + 6, y + 13, fadeColor(0xFFA0A0A0, fade));
            boolean blocked = kfc.udp.client.webrtc.P2PBanManager.isPlayerBanned(r.hostUuid());
            context.drawTextWithShadow(this.textRenderer, Text.literal(blocked ? "✓" : "X"),
                    x + ROW_W - BLOCK_ICON_W + 12, y + 8, fadeColor(blocked ? 0xFF55FF55 : 0xFFFF5555, fade));
        }
        context.disableScissor();
        if (this.hasScrollbar()) {
            int sbX = this.scrollbarX();
            context.fill(sbX, LIST_Y, sbX + SCROLLBAR_W, LIST_Y + VISIBLE_ROWS * ROW_H, ROW_BG_COLOR);
            int[] thumb = this.scrollbarThumb();
            context.fill(sbX, thumb[0], sbX + SCROLLBAR_W, thumb[0] + thumb[1], ROW_BORDER_HOVER_COLOR);
        }
        // 차단 아이콘에 마우스를 올리면 클릭 시 무슨 일이 생기는지 알려준다 — 실제로
        // 화면에 뜬 행은 이미 필터를 통과한 것들이라 항상 "아직 안 차단됨"(X) 상태뿐
        // 이므로(P2PBanManager 클래스 주석 참고), 툴팁도 "차단합니다" 한 가지면 된다.
        if (this.blockIconRowAt(mouseX, mouseY) >= 0) {
            context.drawTooltip(this.textRenderer, BLOCK_TOOLTIP_TEXT, mouseX, mouseY);
        }
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, SECTION_TEXT, this.width / 2 - CODE_FIELD_W / 2, this.sectionTitleY, 0xFFFFFFFF);
        // 기존 마크 멀티플레이 화면의 상단/목록/하단 3분할 구분선을 그대로 참조 —
        // 검색·빠른시작 / 방 목록 / 초대코드 섹션을 가로줄로 나눠 보여준다. 두 구분선
        // 사이는 배경을 살짝 어둡게(MIDDLE_SECTION_BG) 칠해 목록 섹션이 구분되게 한다.
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
        this.renderRows(context, mouseX, mouseY);
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int x = cx - ROW_W / 2;
        // 목록 뷰포트 밖으로 걸친 부분은 잘라낸다 — rowFade가 그 경계로 갈수록
        // 미리 옅어지게 해서, 잘리는 순간이 뚝 끊기지 않고 부드럽게 느껴지게 한다.
        context.enableScissor(0, LIST_Y, this.width, LIST_Y + VISIBLE_ROWS * ROW_H);
        for (int i = 0; i < ROW_SLOTS; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int y = this.rowY[i];
            int h = ROW_H - 2;
            float fade = rowFade(y, h);
            if (fade <= 0f) continue;
            boolean hovered = this.rowButtons.get(i).isHovered();
            context.fill(x, y, x + ROW_W, y + h, fadeColor(ROW_BG_COLOR, fade));
            context.drawBorder(x, y, ROW_W, h, fadeColor(hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR, fade));
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.title()), x + 6, y + 3, fadeColor(0xFFFFFFFF, fade));
            context.drawTextWithShadow(this.textRenderer, Text.literal(r.hostNickname() + "  (" + r.currentPlayers() + "/" + r.maxPlayers() + ")"), x + 6, y + 13, fadeColor(0xFFA0A0A0, fade));
            boolean blocked = kfc.udp.client.webrtc.P2PBanManager.isPlayerBanned(r.hostUuid());
            context.drawTextWithShadow(this.textRenderer, Text.literal(blocked ? "✓" : "X"),
                    x + ROW_W - BLOCK_ICON_W + 12, y + 8, fadeColor(blocked ? 0xFF55FF55 : 0xFFFF5555, fade));
        }
        context.disableScissor();
        if (this.hasScrollbar()) {
            int sbX = this.scrollbarX();
            context.fill(sbX, LIST_Y, sbX + SCROLLBAR_W, LIST_Y + VISIBLE_ROWS * ROW_H, ROW_BG_COLOR);
            int[] thumb = this.scrollbarThumb();
            context.fill(sbX, thumb[0], sbX + SCROLLBAR_W, thumb[0] + thumb[1], ROW_BORDER_HOVER_COLOR);
        }
        // 차단 아이콘에 마우스를 올리면 클릭 시 무슨 일이 생기는지 알려준다 — 실제로
        // 화면에 뜬 행은 이미 필터를 통과한 것들이라 항상 "아직 안 차단됨"(X) 상태뿐
        // 이므로(P2PBanManager 클래스 주석 참고), 툴팁도 "차단합니다" 한 가지면 된다.
        if (this.blockIconRowAt(mouseX, mouseY) >= 0) {
            context.drawTooltip(this.textRenderer, BLOCK_TOOLTIP_TEXT, mouseX, mouseY);
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
