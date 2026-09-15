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
    /** 채널 입력란 한 칸 높이 — 채널 1·2 두 칸을 원래 한 칸(CHANNEL_FIELD_H) 자리 가운데에 위아래로 쌓는다. */
    static final int CHANNEL_PART_H = 14;
    /** 채널 한 칸 입력 한도 — 영문 15자(AAAAAAAAAAAAAAA, 90px), 한글 11자(가×11, 99px). 글자 수와 폭을 둘 다 막아서
     * 좁은 글자만 쓰면 글자 수에서, 한글처럼 넓은 글자는 폭에서 걸린다. 넘치는 입력은 입력란 응답에서 바로 자른다. */
    static final int CHANNEL_MAX_CHARS = 15;
    static final int CHANNEL_MAX_W = 99;

    /** 하단 체크박스 두 개의 공통 x — 박스 왼쪽 끝을 맞추고, 긴 쪽 라벨 끝이 초대코드 입력란(cx-CODE_FIELD_W/2)에서
     * CHECKBOX_GAP만큼 떨어지게 한다. 화면 폭과 무관하게 입력란에 붙어 다니고, 좁은 화면에선 왼쪽 끝 4px에서 멈춘다. */
    private static int checkboxColumnX(int cx, int widthA, int widthB) {
        return Math.max(4, cx - CODE_FIELD_W / 2 - CHECKBOX_GAP - Math.max(widthA, widthB));
    }

    private static final int CHECKBOX_GAP = 18;

    /** 채널 입력란 part(0 = 채널 1 위, 1 = 채널 2 아래)의 y — CustomRoomScreen도 같은 자리에 쓴다. */
    static int channelFieldY(int part) {
        return CHANNEL_Y + CHANNEL_FIELD_H / 2 - CHANNEL_PART_H + part * CHANNEL_PART_H;
    }
    static final int TITLE_Y = CHANNEL_Y + (CHANNEL_FIELD_H - 9) / 2;

    /** 검색창(좌측) + 중계 통신 강제(우측, 검색창이 차지하고 남는 폭) — 첫 행 바로
     * 아래 둘째 행. 예전엔 빠른 시작이 검색창과 다른 행에서 한 줄을 통째로 더 썼는데,
     * 빠른 시작을 첫 행 우측(Regen Invite 자리)으로 옮기면서 이 행 하나로 줄었다.
     * 검색창과 구분선 사이엔 숨 쉴 틈을 남겨서 구분선이 내용에 바짝 붙어 보이지
     * 않게 했다. */
    // 채널 입력란 두 칸(channelFieldY)의 아래 끝에서 4px 띄운다 — 두 칸이 원래 한 칸 자리보다 위아래로 넘쳐서
    // 검색 줄·구분선·방 목록 섹션이 그만큼 같이 내려간다(아래 구분선은 하단 섹션 기준이라 그대로).
    static final int SEARCH_Y      = CHANNEL_Y + CHANNEL_FIELD_H / 2 + CHANNEL_PART_H + 4;
    /** 상단(채널·빠른시작/검색·중계강제)과 리스트 사이 구분선 — 기존 마크 멀티플레이
     * 화면이 상단/목록/하단 3부분을 가로줄로 나누는 걸 그대로 참조했다
     * ({@link #renderRows}에서 실제로 그림). */
    static final int DIVIDER1_Y   = SEARCH_Y + 28;
    static final int LIST_Y       = DIVIDER1_Y + 6;
    static final int ROW_H        = 26;
    static final int ROW_W        = 300;
    /** 방 칸 — 크기는 고정, 열·행 수는 화면(GUI 크기·해상도)에 맞춰 init()에서 정한다. 열 우선으로
     * 채우고(1열의 1~N행, 2열의 1~N행…) 휠 한 칸에 한 열씩 옆으로 넘긴다. */
    static final int CELL_GAP = 4;
    static final int CELL_W = 148;
    /** 목록 좌우 최소 여백. */
    static final int LIST_MARGIN = 10;
    private static final int CELL_H = ROW_H - 2;
    /** 방 칸 우측 끝의 차단 버튼(정사각형) — 접속용 히든 버튼(rowButtons)은 이 버튼과 여백만큼 폭을
     * 줄여서 클릭 영역이 안 겹친다. 그리기·클릭 판정은 renderRows/mouseClicked에서 직접 한다
     * (P2PBanManager 클래스 주석 참고 — 개인 차단(=밴) 기능). */
    static final int BLOCK_BTN = 15; // 홀수 — 테두리 안쪽(13px)에 5px x가 4:4로 딱 떨어진다(BLOCK_X_DX 참고)
    static final int BLOCK_BTN_COLOR = 0xFF505050;
    static final int BLOCK_BTN_HOVER_COLOR = 0xFF707070;
    /** 차단 버튼 안 소문자 x의 그리기 위치 — 대문자 X는 위아래 모양이 달라 상하좌우 대칭인 소문자를 쓴다.
     * 기본 폰트의 x는 가로 5px(0~4열)·세로 5px(글자 기준 2~6행). 그림자는 어두워 눈에 안 잡히므로 밝은 5×5만
     * 가운데에 둔다 — 버튼 안쪽이 짝수(16px 버튼→14px)면 5px는 절대 가운데에 못 오고 반 픽셀씩 좌상/우하로
     * 치우쳐 보여서, 버튼을 홀수(15px→안쪽 13px)로 해 양쪽 4px로 딱 맞춘다: 가로 (15-5)/2=5, 세로 5-2(글자 위 빈 줄)=3. */
    static final int BLOCK_X_DX = (BLOCK_BTN - 5) / 2;
    static final int BLOCK_X_DY = (BLOCK_BTN - 5) / 2 - 2;
    /** 차단 버튼 바로 왼쪽의 핑 막대 — 바닐라 서버 목록 스프라이트 크기 그대로. */
    private static final int PING_W = 10;
    private static final int PING_H = 8;
    /** 방 제목이 차지할 수 있는 폭 = 칸에서 핑 막대·차단 버튼 앞 글자 영역. 글자 수가 아니라 폭으로 막아서
     * 한글(9px)은 12자, 영문(대부분 6px)은 18자 안팎까지 들어간다 — CustomRoomScreen 제목 입력란이 이 폭으로 막는다. */
    public static final int TITLE_TEXT_W = CELL_W - 6 - (3 + BLOCK_BTN + 3 + PING_W + 2);
    /** 폭 제한과 별개로 공지 payload가 커지지 않게 거는 글자 수 상한(좁은 글자만 쓰는 경우 대비). */
    public static final int MAX_TITLE_LENGTH = 32;
    static final int DIVIDER_COLOR = 0xFF555555;
    /** 두 구분선 사이(방 목록 섹션) 배경을 살짝 어둡게 — 기존 마크 멀티플레이/LAN
     * 화면의 서버 목록 섹션처럼 가운데가 위아래보다 더 짙어 보이게 한다. 각 행은
     * 자기 배경(ROW_BG_COLOR, 완전 불투명)을 그 위에 덮어 그리므로 이 배경은 행과
     * 행 사이·리스트 여백에서만 보인다. */
    static final int MIDDLE_SECTION_BG = 0x40000000;

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
    private static final Component REFRESH_TEXT = Component.translatable("instant-p2p.room_list.refresh");
    private static final Component LIVE_UPDATE_TEXT = Component.translatable("instant-p2p.room_list.live_update");
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
    private static final Text REFRESH_TEXT = Text.translatable("instant-p2p.room_list.refresh");
    private static final Text LIVE_UPDATE_TEXT = Text.translatable("instant-p2p.room_list.live_update");
    //?}

    private final Screen parent;
    private final PublicRoomBrowser browser = new PublicRoomBrowser();
    /** 차단 확인 팝업 — 떠 있는 동안 입력은 전부 팝업이 받는다(ConfirmPopup 클래스 주석). */
    private final ConfirmPopup popup = new ConfirmPopup();
    /** 맨 왼쪽에 보이는 열 번호 — 휠 한 칸에 1씩 바뀐다. */
    private int scrollCol = 0;
    /** 열마다의 행 수 — 화면 높이(GUI 크기·해상도)에 따라 init()에서 정한다. */
    private int rows = 1;
    /** 한 화면에 보이는 열 수 — 화면 폭에 맞춰 init()에서 정한다. */
    private int cols = 1;
    /** 칸(slot)별로 지금 보이는 방과 좌상단 좌표. slot = 화면상 열 * rows + 행. */
    private PublicRoomBrowser.RoomEntry[] rowRoom = new PublicRoomBrowser.RoomEntry[0];
    /** 칸별 removalCountdown 값 — 0 정상, 양수는 "방 제거됨(n)" 남은 초, -1은 다음 갱신까지 "방 제거됨". 회색 표시·클릭 막기용. */
    private int[] rowRemovalSec = new int[0];
    private int[] rowX = new int[0];
    private int[] rowY = new int[0];
    private String searchQuery = "";
    /** 검색어까지 반영된 현재 목록 — 화면에 보이는 칸뿐 아니라 빠른 시작이 무작위로 고를
     * 전체 후보 풀로도 쓴다. */
    private List<PublicRoomBrowser.RoomEntry> filteredRooms = List.of();
    private static final Random RANDOM = new Random();

    //? if >=26.1 {
    /*private final java.util.List<Button> rowButtons = new java.util.ArrayList<>();
    @Nullable private StringWidget emptyLabel;
    @Nullable private EditBox searchField;
    @Nullable private EditBox codeField;
    @Nullable private Button joinButton;
    @Nullable private Button quickStartButton;
    @Nullable private Button refreshButton;
    *///?} else {
    private final java.util.List<ButtonWidget> rowButtons = new java.util.ArrayList<>();
    @Nullable private TextWidget emptyLabel;
    @Nullable private TextFieldWidget searchField;
    @Nullable private TextFieldWidget codeField;
    @Nullable private ButtonWidget joinButton;
    @Nullable private ButtonWidget quickStartButton;
    @Nullable private ButtonWidget refreshButton;
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
        // 하단 섹션은 버튼(20px)을 4px 간격으로 촘촘히 쌓는다 — 가로 스크롤바가 구분선 바로 밑으로 내려와서
        // 가운데(방 목록) 섹션이 행 하나를 더 쓸 수 있게.
        this.cancelY = this.height - 24;
        this.codeRowY = this.cancelY - 24;
        // "초대코드로 입장" 행은 오른쪽에 차단 목록 버튼이 붙으므로 버튼 한 줄(20px) 높이로
        // 잡고, 제목 글자는 그 행 안에서 세로 가운데에 둔다.
        int sectionRowY = this.codeRowY - 24;
        this.sectionTitleY = sectionRowY + (20 - 9) / 2;
        this.divider2Y = sectionRowY - 8; // 구분선 밑 1~7px = 가로 스크롤바 자리

        // 채널 입력란 두 칸(위 채널 1, 아래 채널 2) — 두 채널이 모두 같은 방끼리만 보인다(P2PConfig.getChannel).
        // cx-155는 CustomRoomScreen의 Regen Invite(우측 상단, (cx+155)-100)와 중심 기준 좌우반전된 위치.
        for (int part = 0; part < 2; part++) {
            int p = part;
            EditBox field = new EditBox(this.font, cx - 155, channelFieldY(part), CHANNEL_FIELD_W, CHANNEL_PART_H, CHANNEL_TEXT);
            field.setMaxLength(CHANNEL_MAX_CHARS);
            field.setValue(kfc.udp.client.webrtc.P2PConfig.getChannelPart(part));
            field.setTooltip(Tooltip.create(CHANNEL_TEXT));
            field.setResponder(text -> {
                if (this.font.width(text) > CHANNEL_MAX_W) { // 폭 한도 — 잘라 다시 넣으면 이 응답이 한 번 더 불린다
                    field.setValue(this.font.plainSubstrByWidth(text, CHANNEL_MAX_W));
                    return;
                }
                kfc.udp.client.webrtc.P2PConfig.setChannelPart(p, text);
                this.refreshRooms();
            });
            this.addRenderableWidget(field);
        }

        // 빠른 시작 — CustomRoomScreen의 Regen Invite와 완전히 같은 자리·크기
        // (우측 상단). 채널 입력란과 한 행을 이뤄 더 이상 별도 행을 안 쓴다.
        this.quickStartButton = Button.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .bounds((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                .build();
        this.quickStartButton.active = false;
        this.addRenderableWidget(this.quickStartButton);

        // 초대코드 입력란 왼쪽 — 입력란 줄에 중계 통신 강제, 그 아래(접속 버튼) 줄에 실시간 갱신. 위치는 두 체크박스를
        // 다 만든 뒤 checkboxColumnX로 입력란에 붙여 정한다.
        var forceRelayCheckbox = Checkbox.builder(FORCE_RELAY_TEXT, this.font)
                .pos(16, this.codeRowY)
                .selected(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                .onValueChange((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                .build();
        this.addRenderableWidget(forceRelayCheckbox);
        var liveUpdateCheckbox = Checkbox.builder(LIVE_UPDATE_TEXT, this.font)
                .pos(16, this.cancelY)
                .selected(this.liveUpdate)
                .onValueChange((cb, value) -> this.setLiveUpdate(value))
                .build();
        int checkboxX = checkboxColumnX(cx, forceRelayCheckbox.getWidth(), liveUpdateCheckbox.getWidth());
        forceRelayCheckbox.setX(checkboxX);
        liveUpdateCheckbox.setX(checkboxX);
        this.addRenderableWidget(liveUpdateCheckbox);

        // 검색 줄 — 왼쪽 새로고침(채널 입력란과 왼쪽 끝을 맞춤), 오른쪽 차단 목록(랜덤 접속과 오른쪽 끝을 맞춤,
        // 아래에서 추가), 가운데 검색창이 그 사이를 6px씩 띄우고 채운다.
        this.refreshButton = Button.builder(REFRESH_TEXT, b -> this.onRefreshClicked())
                .bounds(cx - 155, SEARCH_Y, REFRESH_W, 20).build();
        this.addRenderableWidget(this.refreshButton);
        int searchFieldW = 2 * (155 - REFRESH_W - 6);
        this.searchField = new EditBox(this.font, cx - searchFieldW / 2, SEARCH_Y, searchFieldW, 20, SEARCH_LABEL_TEXT);
        this.searchField.setMaxLength(32);
        this.searchField.setHint(Component.translatable("instant-p2p.room_list.search_placeholder").withStyle(ChatFormatting.DARK_GRAY));
        this.searchField.setResponder(text -> {
            this.searchQuery = text;
            this.refreshRooms();
        });
        this.addRenderableWidget(this.searchField);

        // 열 수 = 좌우 여백 안에 칸이 들어가는 만큼. 행 수 = 위 구분선(LIST_Y는 그 6px 아래)부터 아래
        // 구분선 2px 위까지 칸이 겹치지 않고 들어가는 만큼 — 스크롤바는 아래 섹션에 있어 자리를 안 먹는다.
        this.cols = Math.max(1, (this.width - 2 * LIST_MARGIN + CELL_GAP) / (CELL_W + CELL_GAP));
        this.rows = Math.max(1, (this.divider2Y - 2 - LIST_Y) / ROW_H);
        int slots = this.cols * this.rows;
        this.rowRoom = new PublicRoomBrowser.RoomEntry[slots];
        this.rowRemovalSec = new int[slots];
        this.rowX = new int[slots];
        this.rowY = new int[slots];
        this.rowButtons.clear();
        for (int i = 0; i < slots; i++) {
            int slot = i;
            Button btn = Button.builder(Component.empty(), b -> this.onRowClicked(slot))
                    .bounds(listX, LIST_Y, CELL_W - BLOCK_BTN - 6, CELL_H) // 위치는 refreshRooms가 칸마다 잡는다
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

        // 차단 목록 관리 — 검색 줄 오른쪽 끝. 새로고침과 같은 폭으로 좌우 대칭, 오른쪽 끝은 랜덤 접속(cx+155)과 맞춘다.
        this.addRenderableWidget(
                Button.builder(BLOCKED_LIST_TEXT, b ->
                        Objects.requireNonNull(this.minecraft).setScreenAndShow(new BlockedPlayersScreen(this)))
                        .bounds((cx + 155) - REFRESH_W, SEARCH_Y, REFRESH_W, 20)
                        .build()
        );

        this.setInitialFocus(this.codeField);
        this.browser.start();
        this.refreshRooms();
        this.updateRefreshButton();
    }
    *///?} else {
    @Override
    protected void init() {
        int cx = this.width / 2;
        int listX = cx - ROW_W / 2;
        // 하단 섹션은 버튼(20px)을 4px 간격으로 촘촘히 쌓는다 — 가로 스크롤바가 구분선 바로 밑으로 내려와서
        // 가운데(방 목록) 섹션이 행 하나를 더 쓸 수 있게.
        this.cancelY = this.height - 24;
        this.codeRowY = this.cancelY - 24;
        // "초대코드로 입장" 행은 오른쪽에 차단 목록 버튼이 붙으므로 버튼 한 줄(20px) 높이로
        // 잡고, 제목 글자는 그 행 안에서 세로 가운데에 둔다.
        int sectionRowY = this.codeRowY - 24;
        this.sectionTitleY = sectionRowY + (20 - 9) / 2;
        this.divider2Y = sectionRowY - 8; // 구분선 밑 1~7px = 가로 스크롤바 자리

        // 채널 입력란 두 칸(위 채널 1, 아래 채널 2) — 두 채널이 모두 같은 방끼리만 보인다(P2PConfig.getChannel).
        // cx-155는 CustomRoomScreen의 Regen Invite(우측 상단, (cx+155)-100)와 중심 기준 좌우반전된 위치.
        for (int part = 0; part < 2; part++) {
            int p = part;
            TextFieldWidget field = new TextFieldWidget(this.textRenderer, cx - 155, channelFieldY(part), CHANNEL_FIELD_W, CHANNEL_PART_H, CHANNEL_TEXT);
            field.setMaxLength(CHANNEL_MAX_CHARS);
            field.setText(kfc.udp.client.webrtc.P2PConfig.getChannelPart(part));
            field.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(CHANNEL_TEXT));
            field.setChangedListener(text -> {
                if (this.textRenderer.getWidth(text) > CHANNEL_MAX_W) { // 폭 한도 — 잘라 다시 넣으면 이 응답이 한 번 더 불린다
                    field.setText(this.textRenderer.trimToWidth(text, CHANNEL_MAX_W));
                    return;
                }
                kfc.udp.client.webrtc.P2PConfig.setChannelPart(p, text);
                this.refreshRooms();
            });
            this.addDrawableChild(field);
        }

        // 빠른 시작 — CustomRoomScreen의 Regen Invite와 완전히 같은 자리·크기
        // (우측 상단). 채널 입력란과 한 행을 이뤄 더 이상 별도 행을 안 쓴다.
        this.quickStartButton = ButtonWidget.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .dimensions((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                .build();
        this.quickStartButton.active = false;
        this.addDrawableChild(this.quickStartButton);

        // 초대코드 입력란 왼쪽 — 입력란 줄에 중계 통신 강제, 그 아래(접속 버튼) 줄에 실시간 갱신. 위치는 두 체크박스를
        // 다 만든 뒤 checkboxColumnX로 입력란에 붙여 정한다.
        var forceRelayCheckbox = CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                .pos(16, this.codeRowY)
                .checked(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                .callback((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                .build();
        this.addDrawableChild(forceRelayCheckbox);
        var liveUpdateCheckbox = CheckboxWidget.builder(LIVE_UPDATE_TEXT, this.textRenderer)
                .pos(16, this.cancelY)
                .checked(this.liveUpdate)
                .callback((cb, value) -> this.setLiveUpdate(value))
                .build();
        int checkboxX = checkboxColumnX(cx, forceRelayCheckbox.getWidth(), liveUpdateCheckbox.getWidth());
        forceRelayCheckbox.setX(checkboxX);
        liveUpdateCheckbox.setX(checkboxX);
        this.addDrawableChild(liveUpdateCheckbox);

        // 검색 줄 — 왼쪽 새로고침(채널 입력란과 왼쪽 끝을 맞춤), 오른쪽 차단 목록(랜덤 접속과 오른쪽 끝을 맞춤,
        // 아래에서 추가), 가운데 검색창이 그 사이를 6px씩 띄우고 채운다.
        this.refreshButton = ButtonWidget.builder(REFRESH_TEXT, b -> this.onRefreshClicked())
                .dimensions(cx - 155, SEARCH_Y, REFRESH_W, 20).build();
        this.addDrawableChild(this.refreshButton);
        int searchFieldW = 2 * (155 - REFRESH_W - 6);
        this.searchField = new TextFieldWidget(this.textRenderer, cx - searchFieldW / 2, SEARCH_Y, searchFieldW, 20, SEARCH_LABEL_TEXT);
        this.searchField.setMaxLength(32);
        this.searchField.setPlaceholder(Text.translatable("instant-p2p.room_list.search_placeholder").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        this.searchField.setChangedListener(text -> {
            this.searchQuery = text;
            this.refreshRooms();
        });
        this.addDrawableChild(this.searchField);

        // 열 수 = 좌우 여백 안에 칸이 들어가는 만큼. 행 수 = 위 구분선(LIST_Y는 그 6px 아래)부터 아래
        // 구분선 2px 위까지 칸이 겹치지 않고 들어가는 만큼 — 스크롤바는 아래 섹션에 있어 자리를 안 먹는다.
        this.cols = Math.max(1, (this.width - 2 * LIST_MARGIN + CELL_GAP) / (CELL_W + CELL_GAP));
        this.rows = Math.max(1, (this.divider2Y - 2 - LIST_Y) / ROW_H);
        int slots = this.cols * this.rows;
        this.rowRoom = new PublicRoomBrowser.RoomEntry[slots];
        this.rowRemovalSec = new int[slots];
        this.rowX = new int[slots];
        this.rowY = new int[slots];
        this.rowButtons.clear();
        for (int i = 0; i < slots; i++) {
            int slot = i;
            ButtonWidget btn = ButtonWidget.builder(Text.empty(), b -> this.onRowClicked(slot))
                    .dimensions(listX, LIST_Y, CELL_W - BLOCK_BTN - 6, CELL_H) // 위치는 refreshRooms가 칸마다 잡는다
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

        // 차단 목록 관리 — 검색 줄 오른쪽 끝. 새로고침과 같은 폭으로 좌우 대칭, 오른쪽 끝은 랜덤 접속(cx+155)과 맞춘다.
        this.addDrawableChild(
                ButtonWidget.builder(BLOCKED_LIST_TEXT, b ->
                        Objects.requireNonNull(this.client).setScreen(new BlockedPlayersScreen(this)))
                        .dimensions((cx + 155) - REFRESH_W, SEARCH_Y, REFRESH_W, 20)
                        .build()
        );

        this.setInitialFocus(this.codeField);
        this.browser.start();
        this.refreshRooms();
        this.updateRefreshButton();
    }
    //?}

    @Override
    public void tick() {
        super.tick();
        this.refreshRooms();
        this.updateRefreshButton();
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
    // 방 목록 갱신 — "실시간 갱신"(P2PConfig.isLiveRoomList)이 켜져 있으면 서버 목록을 그대로 따라간다. 끄면 방 구성·순서는
    // 1분마다 또는 새로고침 버튼을 누를 때만 바꾸고, 그 사이엔 이미 보이는 방의 인원·핑만 최신으로 보여준다.
    // 사라진 방은 바로 빼지 않는다 — 인원이 바뀔 때 방장이 재공지하느라 잠깐(보통 1초 안) 끊겼다 다시 붙으므로
    // FLICKER_GRACE_MS 동안은 원래대로 두고, 그 뒤로도 없으면 실시간일 땐 "방 제거됨(3)"→(2)→(1)로 세고 빼고, 실시간이
    // 아닐 땐 다음 갱신까지 "방 제거됨"으로 제자리에 남긴다. 정렬은 연 시각 순(오래된 방이 앞)이라 새 방은 뒤에 붙는다.
    private static final long FLICKER_GRACE_MS = 1_500;
    private static final int REMOVAL_COUNTDOWN_S = 3;
    private static final long AUTO_REFRESH_MS = 60_000;
    private static final long REFRESH_COOLDOWN_MS = 5_000;
    /** 화면을 막 연 직후엔 샤드 목록이 차례로 도착하므로, 실시간이 꺼져 있어도 이 시간 동안은 따라간다. */
    private static final long INITIAL_FILL_MS = 3_000;
    /** 검색 줄 왼쪽 새로고침 버튼 폭 — "새로고침 중...(5)"·"실시간 갱신 중"이 들어가는 폭. */
    private static final int REFRESH_W = 90;
    private final long openedAtMs = System.currentTimeMillis();
    private boolean liveUpdate = kfc.udp.client.webrtc.P2PConfig.isLiveRoomList();
    private long nextAutoRefreshMs = this.openedAtMs + AUTO_REFRESH_MS;
    private long refreshCooldownUntilMs;
    private boolean refreshRequested;
    private List<PublicRoomBrowser.RoomEntry> liveSource;
    /** 지금 보여주는 방(살아 있는 방 + 사라지는 중인 방) — 코드 → 마지막으로 받은 정보. */
    private final java.util.Map<String, PublicRoomBrowser.RoomEntry> shown = new java.util.HashMap<>();
    private final java.util.Map<String, Long> missingSince = new java.util.HashMap<>();
    private List<PublicRoomBrowser.RoomEntry> displayList = List.of();

    private void setLiveUpdate(boolean live) {
        this.liveUpdate = live;
        kfc.udp.client.webrtc.P2PConfig.setLiveRoomList(live);
        if (live) this.liveSource = null; // 다음 tick에 서버 목록을 곧장 따라가게
        else this.nextAutoRefreshMs = System.currentTimeMillis() + AUTO_REFRESH_MS;
        this.updateRefreshButton();
    }

    /** 새로고침 — 실시간이 꺼져 있을 때만. 누르면 5초 동안 다시 못 누르고, 자동 갱신 타이머도 1분으로 다시 돈다. */
    private void onRefreshClicked() {
        long now = System.currentTimeMillis();
        if (this.liveUpdate || now < this.refreshCooldownUntilMs) return;
        this.refreshRequested = true;
        this.refreshCooldownUntilMs = now + REFRESH_COOLDOWN_MS;
        this.refreshRooms();
        this.updateRefreshButton();
    }

    /** 보여줄 목록 — 구성이 바뀔 때만 새 리스트를 만들어서, 참조가 같으면 내용도 같다(필터 캐시가 이걸 믿는다). */
    private List<PublicRoomBrowser.RoomEntry> displayRooms() {
        List<PublicRoomBrowser.RoomEntry> live = this.browser.getCurrentRooms();
        long now = System.currentTimeMillis();
        boolean liveChanged = live != this.liveSource;
        this.liveSource = live;
        boolean changed = false;

        boolean refreshDue = this.refreshRequested || now >= this.nextAutoRefreshMs;
        if (!this.liveUpdate && (refreshDue || (liveChanged && now < this.openedAtMs + INITIAL_FILL_MS))) {
            // 갱신 시점 — 보이는 목록을 지금 서버 목록과 똑같이 맞춘다(사라진 방도 이때 빠진다).
            if (refreshDue) this.nextAutoRefreshMs = now + AUTO_REFRESH_MS;
            this.refreshRequested = false;
            this.shown.clear();
            this.missingSince.clear();
            for (PublicRoomBrowser.RoomEntry r : live) this.shown.put(r.code(), r);
            changed = true;
        } else if (liveChanged) {
            java.util.Map<String, PublicRoomBrowser.RoomEntry> liveByCode = new java.util.HashMap<>();
            for (PublicRoomBrowser.RoomEntry r : live) liveByCode.put(r.code(), r);
            // 실시간이면 새 방도 들이고, 아니면 이미 보이는 방의 정보만 최신으로 바꾼다.
            if (this.liveUpdate) this.shown.putAll(liveByCode);
            for (var e : this.shown.entrySet()) {
                PublicRoomBrowser.RoomEntry latest = liveByCode.get(e.getKey());
                if (latest != null) {
                    e.setValue(latest);
                    this.missingSince.remove(e.getKey());
                } else {
                    this.missingSince.putIfAbsent(e.getKey(), now);
                }
            }
            changed = true;
        }

        if (this.liveUpdate) {
            long removeAfterMs = FLICKER_GRACE_MS + REMOVAL_COUNTDOWN_S * 1000L;
            var it = this.missingSince.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (now - e.getValue() < removeAfterMs) continue;
                this.shown.remove(e.getKey());
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            this.displayList = this.shown.values().stream()
                    .sorted(java.util.Comparator.comparingLong(PublicRoomBrowser.RoomEntry::openedAtMs)
                            .thenComparing(PublicRoomBrowser.RoomEntry::code))
                    .toList();
        }
        return this.displayList;
    }

    /** 사라지는 중인 방 — 실시간이면 남은 초(3→2→1), 실시간이 아니면 -1(다음 갱신까지 "방 제거됨"), 정상이면 0. */
    private int removalCountdown(String code) {
        Long since = this.missingSince.get(code);
        if (since == null) return 0;
        long elapsed = System.currentTimeMillis() - since;
        if (elapsed < FLICKER_GRACE_MS) return 0;
        if (!this.liveUpdate) return -1;
        return (int) Math.max(1, REMOVAL_COUNTDOWN_S - (elapsed - FLICKER_GRACE_MS) / 1000);
    }

    private void refreshRooms() {
        List<PublicRoomBrowser.RoomEntry> all = this.displayRooms();
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
                    .filter(r -> !kfc.udp.client.webrtc.P2PBanManager.isBannedIn(r.bannedHashes(), r.code(), myUuid))
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

        // 목록이 줄어들면(필터 변경 등) 스크롤 위치를 범위 안으로 당긴다.
        int maxCol = Math.max(0, this.totalCols() - this.cols);
        if (this.scrollCol > maxCol) this.scrollCol = maxCol;

        // 열 우선 배치 — 화면의 1열에 scrollCol번째 열의 방들(위→아래), 2열에 그다음 열의 방들.
        int listX = this.listX();
        for (int i = 0; i < this.rowRoom.length; i++) {
            int col = i / this.rows, row = i % this.rows;
            int idx = (this.scrollCol + col) * this.rows + row;
            var btn = this.rowButtons.get(i);
            this.rowX[i] = listX + col * (CELL_W + CELL_GAP);
            this.rowY[i] = LIST_Y + row * ROW_H;
            btn.setX(this.rowX[i]);
            btn.setY(this.rowY[i]);
            if (idx < rooms.size()) {
                PublicRoomBrowser.RoomEntry room = rooms.get(idx);
                this.rowRoom[i] = room;
                this.rowRemovalSec[i] = this.removalCountdown(room.code());
                btn.visible = true;
            } else {
                this.rowRoom[i] = null;
                this.rowRemovalSec[i] = 0;
                btn.visible = false;
            }
        }

        if (this.emptyLabel != null) this.emptyLabel.visible = rooms.isEmpty();
        if (this.quickStartButton != null) this.quickStartButton.active = !rooms.isEmpty();
    }

    private void onRowClicked(int row) {
        PublicRoomBrowser.RoomEntry r = this.rowRoom[row];
        if (r == null || this.rowRemovalSec[row] != 0) return; // 사라지는 중인 방은 눌러도 접속 실패만 뜨므로 막는다
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
        List<PublicRoomBrowser.RoomEntry> rooms = this.filteredRooms.stream().filter(r -> !this.missingSince.containsKey(r.code())).toList();
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
        if (this.popup.keyPressed(input.key())) return true;
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
        if (this.popup.keyPressed(input.key())) return true;
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
        if (this.popup.keyPressed(keyCode)) return true;
        if (keyCode == 257 && this.joinButton != null && this.joinButton.active) { // Enter
            this.onJoinByCode();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.popup.isOpen()) return true;
        // 커서가 방 목록 섹션 안에 있을 때만 스크롤한다 — 검색란/초대 코드 섹션에서
        // 휠을 굴렸는데 안 보이는 방 목록이 넘어가버리는 건 직관적이지 않다.
        if (mouseY < DIVIDER1_Y || mouseY > this.divider2Y + 1 + SCROLLBAR_W)
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int maxCol = Math.max(0, this.totalCols() - this.cols);
        if (maxCol <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        // 휠 아래 = 오른쪽으로 한 열, 위 = 왼쪽으로 한 열.
        int newCol = Math.max(0, Math.min(maxCol, this.scrollCol - (int) Math.signum(verticalAmount)));
        if (newCol != this.scrollCol) {
            this.scrollCol = newCol;
            this.refreshRooms();
        }
        return true;
    }

    /** 가로 스크롤바 두께 — 칸 아래 별도 공간에 그려서 칸을 침범하지 않는다(차단 목록 화면도 같이 쓴다). */
    static final int SCROLLBAR_W = 6;

    /** 방 목록 전체의 열 수(열마다 rows개씩). */
    private int totalCols() {
        return (this.filteredRooms.size() + this.rows - 1) / this.rows;
    }

    /** 한 화면(cols열)에 다 들어오면 — 화면에 안 보이는 열이 생기기 전까지는 — 그리지도, 드래그를 받지도 않는다. */
    private boolean hasScrollbar() {
        return this.totalCols() > this.cols;
    }

    /** 방 칸들이 차지하는 전체 폭 — 화면 가운데 정렬. */
    private int listW() {
        return this.cols * CELL_W + (this.cols - 1) * CELL_GAP;
    }

    private int listX() {
        return (this.width - this.listW()) / 2;
    }

    /** 가로 스크롤바는 아래 구분선 바로 밑(하단 섹션 맨 위), 목록 폭 전체를 트랙으로 쓴다. */
    private int scrollbarY() {
        return this.divider2Y + 1;
    }

    /** 썸의 가로 위치·폭 — {x, width}. 열이 많을수록 짧아지되 최소 10px은 남겨 손으로 집을 수 있게 한다. */
    private int[] scrollbarThumb() {
        int trackX = this.listX();
        int total = this.totalCols();
        int thumbW = Math.max(10, this.listW() * this.cols / total);
        int maxCol = Math.max(1, total - this.cols);
        return new int[]{trackX + (this.listW() - thumbW) * this.scrollCol / maxCol, thumbW};
    }

    /** 트랙 안의 X 위치(클릭/드래그)를 scrollCol로 환산 — 썸 중심이 그 X에 오도록. */
    private void scrollToTrackX(double mouseX) {
        int maxCol = this.totalCols() - this.cols;
        if (maxCol <= 0) return;
        int thumbW = this.scrollbarThumb()[1];
        double rel = (mouseX - (this.listX()) - thumbW / 2.0) / (this.listW() - thumbW);
        int newCol = Math.max(0, Math.min(maxCol, (int) Math.round(rel * maxCol)));
        if (newCol != this.scrollCol) {
            this.scrollCol = newCol;
            this.refreshRooms();
        }
    }

    private boolean draggingScrollbar = false;

    /** 가로 스크롤바 트랙을 클릭했는지 — 마우스 입력 API가 버전마다 달라서(아래 3분기) 판정만 여기로 뽑아뒀다. */
    private boolean isOnScrollbarTrack(double mouseX, double mouseY) {
        if (!this.hasScrollbar()) return false;
        int trackX = this.listX();
        int sbY = this.scrollbarY();
        return mouseX >= trackX && mouseX < trackX + this.listW() && mouseY >= sbY && mouseY < sbY + SCROLLBAR_W;
    }

    /** (mouseX,mouseY)가 핑 막대 위에 있는 칸, 없으면 -1 — 툴팁용. */
    private int pingIconAt(double mouseX, double mouseY) {
        for (int i = 0; i < this.rowRoom.length; i++) {
            if (this.rowRoom[i] == null) continue;
            int px = this.rowX[i] + CELL_W - BLOCK_BTN - 6 - PING_W;
            int py = this.rowY[i] + (CELL_H - PING_H) / 2;
            if (mouseX >= px && mouseX < px + PING_W && mouseY >= py && mouseY < py + PING_H) return i;
        }
        return -1;
    }

    /** 바닐라 서버 목록과 같은 핑 막대 스프라이트(막대 기준도 바닐라, SignalingRtt.bars). 아직 모르면
     * 바닐라처럼 "측정 중" 애니메이션을 행마다 조금씩 어긋나게 돌린다. */
    private static String pingSprite(long pingMs, int row) {
        int bars = kfc.udp.client.webrtc.SignalingRtt.bars(pingMs);
        if (bars > 0) return "server_list/ping_" + bars;
        int frame = (int) (System.currentTimeMillis() / 100L + row * 2L & 7L);
        return "server_list/pinging_" + ((frame > 4 ? 8 - frame : frame) + 1);
    }

    /** (mouseX,mouseY)가 차단 버튼 위에 있는 칸, 없으면 -1. 접속용 히든 버튼은 이 버튼 앞에서 끝나서
     * 클릭 영역이 안 겹친다. */
    private int blockButtonAt(double mouseX, double mouseY) {
        for (int i = 0; i < this.rowRoom.length; i++) {
            if (this.rowRoom[i] == null) continue;
            int bx = this.rowX[i] + CELL_W - 3 - BLOCK_BTN;
            int by = this.rowY[i] + (CELL_H - BLOCK_BTN) / 2;
            if (mouseX >= bx && mouseX < bx + BLOCK_BTN && mouseY >= by && mouseY < by + BLOCK_BTN) return i;
        }
        return -1;
    }

    /** 그 행 방장의 차단(=밴) 여부를 뒤집는다 — P2PBanManager 클래스 주석 참고,
     * 인게임 /ban·/pardon과 완전히 같은 목록을 그대로 건드리는 것이라 즉시
     * 로컬에 반영되고, 목록은 다음 tick(refreshRooms)에 자동으로 다시 걸러진다. */
    private void toggleBlockAt(int row) {
        PublicRoomBrowser.RoomEntry r = this.rowRoom[row];
        if (r == null) return;
        String uuid = r.hostUuid(), name = r.hostNickname();
        boolean blocked = kfc.udp.client.webrtc.P2PBanManager.isPlayerBanned(uuid);
        // 바로 바꾸지 않고 확인 팝업부터 — 확인하면 그때 반영된다.
        this.setFocused(null); // 뒤의 입력란에 포커스가 남아 있으면 팝업 중 타이핑이 들어간다
        this.popup.open(blocked ? "instant-p2p.confirm.unblock" : "instant-p2p.confirm.block", name, () -> {
            if (blocked) kfc.udp.client.webrtc.P2PBanManager.pardonPlayerByUuid(uuid);
            else kfc.udp.client.webrtc.P2PBanManager.banPlayer(uuid, name, "Blocked from room list.");
        });
    }

    /** 들어가려는 방에 내가 차단한 유저가 있을 때(KfcudpClient.joinRoomByCode) — 화면을 바꾸지 않고 이 화면 위에
     * 팝업으로 묻는다. "네"면 join을 실행하고, "아니오"·Esc·10초 경과면 그냥 닫혀 방 목록에 남는다. */
    public void confirmBlockedJoin(List<String> names, Runnable join) {
        this.setFocused(null);
        this.popup.openBlockedJoin(names, join);
    }

    // 마우스 클릭/드래그/릴리즈 콜백 시그니처가 1.21.9에서 (double,double,int,...)에서
    // 값 타입(Click/MouseButtonEvent) 하나로 바뀌었다 — keyPressed의 KeyEvent/KeyInput/
    // 순수 int 3분기와 같은 이유·같은 패턴(독립된 3개 블록, 안 겹치는 조건이라
    // if/else로 안 엮어도 버전마다 정확히 하나만 살아남는다).
    //? if >=26.1 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (this.popup.isOpen()) {
            if (click.button() == 0) this.popup.mouseClicked(click.x(), click.y());
            return true;
        }
        if (click.button() == 0 && this.isOnScrollbarTrack(click.x(), click.y())) {
            this.draggingScrollbar = true;
            this.scrollToTrackX(click.x());
            return true;
        }
        if (click.button() == 0) {
            int row = this.blockButtonAt(click.x(), click.y());
            if (row >= 0) { playClick(); this.toggleBlockAt(row); return true; }
        }
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
        if (click.button() == 0 && this.isOnScrollbarTrack(click.x(), click.y())) {
            this.draggingScrollbar = true;
            this.scrollToTrackX(click.x());
            return true;
        }
        if (click.button() == 0) {
            int row = this.blockButtonAt(click.x(), click.y());
            if (row >= 0) { playClick(); this.toggleBlockAt(row); return true; }
        }
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
        if (button == 0 && this.isOnScrollbarTrack(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollToTrackX(mouseX);
            return true;
        }
        if (button == 0) {
            int row = this.blockButtonAt(mouseX, mouseY);
            if (row >= 0) { playClick(); this.toggleBlockAt(row); return true; }
        }
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

    @Override
    public void removed() {
        super.removed();
        this.browser.stop();
    }

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        int realX = mouseX, realY = mouseY;
        if (this.popup.isOpen()) { mouseX = -1; mouseY = -1; } // 팝업 뒤 위젯·툴팁이 호버되지 않게
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
        this.popup.render(context, this.width, this.height, realX, realY);
    }

    private void renderRows(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // 칸 안 글자 영역 — 좌측 여백 6px, 우측엔 핑 막대·차단 버튼과 여백.
        int textW = TITLE_TEXT_W;
        int hoveredBlock = this.blockButtonAt(mouseX, mouseY);
        for (int i = 0; i < this.rowRoom.length; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int x = this.rowX[i], y = this.rowY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            boolean hovered = this.rowButtons.get(i).isHovered();
            boolean closed = this.rowRemovalSec[i] != 0;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.outline(x, y, CELL_W, CELL_H, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            // 제목은 MAX_TITLE_LENGTH로 막지만 다른 클라이언트가 보낸 값이라 폭으로도 한 번 더 자른다.
            context.text(this.font, this.font.plainSubstrByWidth(r.title(), textW), x + 6, y + 3, closed ? 0xFF707070 : 0xFFFFFFFF);
            String count = "  (" + r.currentPlayers() + "/" + r.maxPlayers() + ")";
            context.text(this.font, closed ? removedLabel(this.rowRemovalSec[i])
                            : this.font.plainSubstrByWidth(r.hostNickname(), textW - this.font.width(count)) + count,
                    x + 6, y + 13, closed ? 0xFFFF5555 : 0xFFA0A0A0);
            if (!closed) this.drawPingSprite(context, pingSprite(r.estimatedPingMs(), i), bx - 3 - PING_W, y + (CELL_H - PING_H) / 2);
            // 차단 버튼 — 회색 바탕에 빨간 x. 목록엔 아직 차단 안 한 방장만 뜨므로 x 하나뿐이다.
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBlock == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.outline(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBlock == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.text(this.font, "x", bx + BLOCK_X_DX, by + BLOCK_X_DY, 0xFFFF5555);
        }
        if (this.hasScrollbar()) {
            int trackX = this.listX();
            int sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
        }
        // 차단 아이콘에 마우스를 올리면 클릭 시 무슨 일이 생기는지 알려준다 — 실제로
        // 화면에 뜬 행은 이미 필터를 통과한 것들이라 항상 "아직 안 차단됨"(X) 상태뿐
        // 이므로(P2PBanManager 클래스 주석 참고), 툴팁도 "차단합니다" 한 가지면 된다.
        if (this.blockButtonAt(mouseX, mouseY) >= 0) {
            context.setTooltipForNextFrame(this.font, BLOCK_TOOLTIP_TEXT, mouseX, mouseY);
        }
        int pingRow = this.pingIconAt(mouseX, mouseY);
        if (pingRow >= 0) {
            context.setTooltipForNextFrame(this.font, pingTooltip(this.rowRoom[pingRow]), mouseX, mouseY);
        }
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int realX = mouseX, realY = mouseY;
        if (this.popup.isOpen()) { mouseX = -1; mouseY = -1; } // 팝업 뒤 위젯·툴팁이 호버되지 않게
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
        this.popup.render(context, this.width, this.height, realX, realY);
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY) {
        // 칸 안 글자 영역 — 좌측 여백 6px, 우측엔 핑 막대·차단 버튼과 여백.
        int textW = TITLE_TEXT_W;
        int hoveredBlock = this.blockButtonAt(mouseX, mouseY);
        for (int i = 0; i < this.rowRoom.length; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int x = this.rowX[i], y = this.rowY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            boolean hovered = this.rowButtons.get(i).isHovered();
            boolean closed = this.rowRemovalSec[i] != 0;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            // drawBorder → drawStrokedRectangle 개명(1.21.9~) — DrawContext.java 클래스 주석 없음, javap로 확인.
            context.drawStrokedRectangle(x, y, CELL_W, CELL_H, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            // 제목은 MAX_TITLE_LENGTH로 막지만 다른 클라이언트가 보낸 값이라 폭으로도 한 번 더 자른다.
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(r.title(), textW)), x + 6, y + 3, closed ? 0xFF707070 : 0xFFFFFFFF);
            String count = "  (" + r.currentPlayers() + "/" + r.maxPlayers() + ")";
            context.drawTextWithShadow(this.textRenderer, Text.literal(closed ? removedLabel(this.rowRemovalSec[i])
                            : this.textRenderer.trimToWidth(r.hostNickname(), textW - this.textRenderer.getWidth(count)) + count),
                    x + 6, y + 13, closed ? 0xFFFF5555 : 0xFFA0A0A0);
            if (!closed) this.drawPingSprite(context, pingSprite(r.estimatedPingMs(), i), bx - 3 - PING_W, y + (CELL_H - PING_H) / 2);
            // 차단 버튼 — 회색 바탕에 빨간 x. 목록엔 아직 차단 안 한 방장만 뜨므로 x 하나뿐이다.
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBlock == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.drawStrokedRectangle(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBlock == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal("x"), bx + BLOCK_X_DX, by + BLOCK_X_DY, 0xFFFF5555);
        }
        if (this.hasScrollbar()) {
            int trackX = this.listX();
            int sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
        }
        // 차단 아이콘에 마우스를 올리면 클릭 시 무슨 일이 생기는지 알려준다 — 실제로
        // 화면에 뜬 행은 이미 필터를 통과한 것들이라 항상 "아직 안 차단됨"(X) 상태뿐
        // 이므로(P2PBanManager 클래스 주석 참고), 툴팁도 "차단합니다" 한 가지면 된다.
        if (this.blockButtonAt(mouseX, mouseY) >= 0) {
            context.drawTooltip(this.textRenderer, BLOCK_TOOLTIP_TEXT, mouseX, mouseY);
        }
        int pingRow = this.pingIconAt(mouseX, mouseY);
        if (pingRow >= 0) {
            context.drawTooltip(this.textRenderer, pingTooltip(this.rowRoom[pingRow]), mouseX, mouseY);
        }
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int realX = mouseX, realY = mouseY;
        if (this.popup.isOpen()) { mouseX = -1; mouseY = -1; } // 팝업 뒤 위젯·툴팁이 호버되지 않게
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
        this.popup.render(context, this.width, this.height, realX, realY);
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY) {
        // 칸 안 글자 영역 — 좌측 여백 6px, 우측엔 핑 막대·차단 버튼과 여백.
        int textW = TITLE_TEXT_W;
        int hoveredBlock = this.blockButtonAt(mouseX, mouseY);
        for (int i = 0; i < this.rowRoom.length; i++) {
            PublicRoomBrowser.RoomEntry r = this.rowRoom[i];
            if (r == null) continue;
            int x = this.rowX[i], y = this.rowY[i];
            int bx = x + CELL_W - 3 - BLOCK_BTN, by = y + (CELL_H - BLOCK_BTN) / 2;
            boolean hovered = this.rowButtons.get(i).isHovered();
            boolean closed = this.rowRemovalSec[i] != 0;
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.drawBorder(x, y, CELL_W, CELL_H, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            // 제목은 MAX_TITLE_LENGTH로 막지만 다른 클라이언트가 보낸 값이라 폭으로도 한 번 더 자른다.
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(r.title(), textW)), x + 6, y + 3, closed ? 0xFF707070 : 0xFFFFFFFF);
            String count = "  (" + r.currentPlayers() + "/" + r.maxPlayers() + ")";
            context.drawTextWithShadow(this.textRenderer, Text.literal(closed ? removedLabel(this.rowRemovalSec[i])
                            : this.textRenderer.trimToWidth(r.hostNickname(), textW - this.textRenderer.getWidth(count)) + count),
                    x + 6, y + 13, closed ? 0xFFFF5555 : 0xFFA0A0A0);
            if (!closed) this.drawPingSprite(context, pingSprite(r.estimatedPingMs(), i), bx - 3 - PING_W, y + (CELL_H - PING_H) / 2);
            // 차단 버튼 — 회색 바탕에 빨간 x. 목록엔 아직 차단 안 한 방장만 뜨므로 x 하나뿐이다.
            context.fill(bx, by, bx + BLOCK_BTN, by + BLOCK_BTN, hoveredBlock == i ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
            context.drawBorder(bx, by, BLOCK_BTN, BLOCK_BTN, hoveredBlock == i ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            context.drawTextWithShadow(this.textRenderer, Text.literal("x"), bx + BLOCK_X_DX, by + BLOCK_X_DY, 0xFFFF5555);
        }
        if (this.hasScrollbar()) {
            int trackX = this.listX();
            int sbY = this.scrollbarY();
            drawHScroller(context, "widget/scroller_background", trackX, sbY, this.listW());
            int[] thumb = this.scrollbarThumb();
            drawHScroller(context, "widget/scroller", thumb[0], sbY, thumb[1]);
        }
        // 차단 아이콘에 마우스를 올리면 클릭 시 무슨 일이 생기는지 알려준다 — 실제로
        // 화면에 뜬 행은 이미 필터를 통과한 것들이라 항상 "아직 안 차단됨"(X) 상태뿐
        // 이므로(P2PBanManager 클래스 주석 참고), 툴팁도 "차단합니다" 한 가지면 된다.
        if (this.blockButtonAt(mouseX, mouseY) >= 0) {
            context.drawTooltip(this.textRenderer, BLOCK_TOOLTIP_TEXT, mouseX, mouseY);
        }
        int pingRow = this.pingIconAt(mouseX, mouseY);
        if (pingRow >= 0) {
            context.drawTooltip(this.textRenderer, pingTooltip(this.rowRoom[pingRow]), mouseX, mouseY);
        }
    }
    //?}

    // 핑 막대 스프라이트 그리기 — 그리는 API가 1.21.2(RenderLayer)·1.21.6(RenderPipeline)·26.x(blitSprite)에서
    // 바뀌어 버전별로 나눈다. 툴팁 문구는 바닐라 서버 목록과 같은 번역 키("%s ms" / "Pinging...").
    //? if >=26.1 {
    /*private void drawPingSprite(GuiGraphicsExtractor context, String sprite, int x, int y) {
        context.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                net.minecraft.resources.Identifier.withDefaultNamespace(sprite), x, y, PING_W, PING_H);
    }

    private static Component pingTooltip(PublicRoomBrowser.RoomEntry r) {
        long ping = r.estimatedPingMs();
        return ping < 0 ? Component.translatable("multiplayer.status.pinging")
                : Component.translatable("multiplayer.status.ping", ping);
    }

    private static String removedLabel(int seconds) {
        return (seconds > 0 ? Component.translatable("instant-p2p.room_list.removed", seconds)
                : Component.translatable("instant-p2p.room_list.removed_static")).getString();
    }

    // 새로고침 버튼의 활성·문구 — 매 tick 부른다. 문구가 실제로 바뀔 때만 setMessage(스크린리더가 매번 다시 읽지 않게).
    private void updateRefreshButton() {
        if (this.refreshButton == null) return;
        long now = System.currentTimeMillis();
        Component label;
        if (this.liveUpdate) {
            label = Component.translatable("instant-p2p.room_list.refresh_live");
        } else if (now < this.refreshCooldownUntilMs) {
            label = Component.translatable("instant-p2p.room_list.refreshing", (this.refreshCooldownUntilMs - now + 999) / 1000);
        } else {
            label = REFRESH_TEXT;
        }
        this.refreshButton.active = !this.liveUpdate && now >= this.refreshCooldownUntilMs;
        if (!label.getString().equals(this.refreshButton.getMessage().getString())) this.refreshButton.setMessage(label);
    }
    *///?}
    //? if >=1.21.6 <26.1 {
    /*private void drawPingSprite(DrawContext context, String sprite, int x, int y) {
        context.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                net.minecraft.util.Identifier.ofVanilla(sprite), x, y, PING_W, PING_H);
    }
    *///?}
    //? if >=1.21.2 <1.21.6 {
    private void drawPingSprite(DrawContext context, String sprite, int x, int y) {
        context.drawGuiTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,
                net.minecraft.util.Identifier.ofVanilla(sprite), x, y, PING_W, PING_H);
    }
    //?}
    //? if <1.21.2 {
    /*private void drawPingSprite(DrawContext context, String sprite, int x, int y) {
        context.drawGuiTexture(net.minecraft.util.Identifier.ofVanilla(sprite), x, y, PING_W, PING_H);
    }
    *///?}
    // 바닐라 버튼 클릭음 — 위젯이 아니라 직접 그린 버튼(차단 x, 팝업 확인/취소 등)은 소리를 안 내서 따로 튼다.
    // 정적 도우미가 1.21.2(ClickableWidget.playClickSound)·26.x(AbstractWidget.playButtonClickSound)에서 생겼고,
    // 그 전은 바닐라 버튼이 하던 대로 직접 재생한다.
    //? if >=26.1 {
    /*static void playClick() {
        net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(
                net.minecraft.client.Minecraft.getInstance().getSoundManager());
    }
    *///?}
    //? if >=1.21.2 <26.1 {
    static void playClick() {
        net.minecraft.client.gui.widget.ClickableWidget.playClickSound(
                net.minecraft.client.MinecraftClient.getInstance().getSoundManager());
    }
    //?}
    //? if <1.21.2 {
    /*static void playClick() {
        net.minecraft.client.MinecraftClient.getInstance().getSoundManager().play(
                net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
    *///?}

    // 스프라이트 한 장 그리기(ConfirmPopup 버튼 등) — API가 1.21.2·1.21.6·26.x에서 바뀌어 넷으로 나눈다.
    //? if >=26.1 {
    /*static void drawSprite(GuiGraphicsExtractor context, String sprite, int x, int y, int w, int h) {
        context.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                net.minecraft.resources.Identifier.withDefaultNamespace(sprite), x, y, w, h);
    }
    *///?}
    //? if >=1.21.6 <26.1 {
    /*static void drawSprite(DrawContext context, String sprite, int x, int y, int w, int h) {
        context.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                net.minecraft.util.Identifier.ofVanilla(sprite), x, y, w, h);
    }
    *///?}
    //? if >=1.21.2 <1.21.6 {
    static void drawSprite(DrawContext context, String sprite, int x, int y, int w, int h) {
        context.drawGuiTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,
                net.minecraft.util.Identifier.ofVanilla(sprite), x, y, w, h);
    }
    //?}
    //? if <1.21.2 {
    /*static void drawSprite(DrawContext context, String sprite, int x, int y, int w, int h) {
        context.drawGuiTexture(net.minecraft.util.Identifier.ofVanilla(sprite), x, y, w, h);
    }
    *///?}

    // 가로 스크롤바 — 바닐라 서버 목록의 세로 스크롤바 스프라이트(widget/scroller[_background], 6px 폭 nine-slice)를
    // 6×len 세로로 그린 뒤 시계 방향 90° 돌려 len×6 가로로 눕힌다. 행렬 API가 1.21.6(MatrixStack → Matrix3x2fStack)에서,
    // 스프라이트 API가 1.21.2·1.21.6·26.x에서 바뀌어 넷으로 나눈다. 차단 목록 화면도 같이 쓴다.
    //? if >=26.1 {
    /*static void drawHScroller(GuiGraphicsExtractor context, String sprite, int x, int y, int len) {
        context.pose().pushMatrix();
        context.pose().translate(x + len, y);
        context.pose().rotate((float) (Math.PI / 2));
        context.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                net.minecraft.resources.Identifier.withDefaultNamespace(sprite), 0, 0, SCROLLBAR_W, len);
        context.pose().popMatrix();
    }
    *///?}
    //? if >=1.21.6 <26.1 {
    /*static void drawHScroller(DrawContext context, String sprite, int x, int y, int len) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x + len, y);
        context.getMatrices().rotate((float) (Math.PI / 2));
        context.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                net.minecraft.util.Identifier.ofVanilla(sprite), 0, 0, SCROLLBAR_W, len);
        context.getMatrices().popMatrix();
    }
    *///?}
    //? if >=1.21.2 <1.21.6 {
    static void drawHScroller(DrawContext context, String sprite, int x, int y, int len) {
        context.getMatrices().push();
        context.getMatrices().translate(x + len, y, 0);
        context.getMatrices().multiply(new org.joml.Quaternionf().rotationZ((float) (Math.PI / 2)));
        context.drawGuiTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,
                net.minecraft.util.Identifier.ofVanilla(sprite), 0, 0, SCROLLBAR_W, len);
        context.getMatrices().pop();
    }
    //?}
    //? if <1.21.2 {
    /*static void drawHScroller(DrawContext context, String sprite, int x, int y, int len) {
        context.getMatrices().push();
        context.getMatrices().translate(x + len, y, 0);
        context.getMatrices().multiply(new org.joml.Quaternionf().rotationZ((float) (Math.PI / 2)));
        context.drawGuiTexture(net.minecraft.util.Identifier.ofVanilla(sprite), 0, 0, SCROLLBAR_W, len);
        context.getMatrices().pop();
    }
    *///?}

    //? if <26.1 {
    private static Text pingTooltip(PublicRoomBrowser.RoomEntry r) {
        long ping = r.estimatedPingMs();
        return ping < 0 ? Text.translatable("multiplayer.status.pinging")
                : Text.translatable("multiplayer.status.ping", ping);
    }

    private static String removedLabel(int seconds) {
        return (seconds > 0 ? Text.translatable("instant-p2p.room_list.removed", seconds)
                : Text.translatable("instant-p2p.room_list.removed_static")).getString();
    }

    // 새로고침 버튼의 활성·문구 — 매 tick 부른다. 문구가 실제로 바뀔 때만 setMessage(스크린리더가 매번 다시 읽지 않게).
    private void updateRefreshButton() {
        if (this.refreshButton == null) return;
        long now = System.currentTimeMillis();
        Text label;
        if (this.liveUpdate) {
            label = Text.translatable("instant-p2p.room_list.refresh_live");
        } else if (now < this.refreshCooldownUntilMs) {
            label = Text.translatable("instant-p2p.room_list.refreshing", (this.refreshCooldownUntilMs - now + 999) / 1000);
        } else {
            label = REFRESH_TEXT;
        }
        this.refreshButton.active = !this.liveUpdate && now >= this.refreshCooldownUntilMs;
        if (!label.getString().equals(this.refreshButton.getMessage().getString())) this.refreshButton.setMessage(label);
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
