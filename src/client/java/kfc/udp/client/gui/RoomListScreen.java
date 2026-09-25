package kfc.udp.client.gui;

import kfc.udp.client.KfcudpClient;
import kfc.udp.client.webrtc.PublicRoomBrowser;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
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
import net.minecraft.client.gui.widget.CyclingButtonWidget;
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

    /** 맨 위 첫 행 — 채널 설정 버튼(좌측)과 빠른 시작(우측). CustomRoomScreen의
     * "채널 설정(좌측 상단)/Regen Invite(우측 상단)"과 완전히 같은 좌표·크기 쌍이라
     * 호스트/접속자 화면 어디서든 같은 자리에 뜬다. 화면 제목은 이 행의 좌우
     * 버튼 사이 빈 중앙에 세로 중앙 정렬로 그린다(TITLE_Y — CustomRoomScreen의
     * TITLE_Y 계산식과 동일한 이유). */
    static final int CHANNEL_Y = 10;
    static final int CHANNEL_FIELD_W = 100;
    static final int CHANNEL_FIELD_H = 20;

    /** 하단 체크박스 줄(다른 버전 숨기기·중계 통신 강제, 초대코드 입력란 바로 위) — 체크박스 사이 간격. */
    private static final int CHECKBOX_GAP = 10;
    /** 체크박스를 버튼과 같은 줄에서 세로 가운데에 오게 이만큼 내린다. */
    private static final int CHECKBOX_DY = 2;

    /** 방송 필터 순환 버튼의 라벨 번역 키 접미사 — enum 이름을 그대로 안 쓰는 이유는
     * lang 키를 소문자·스네이크케이스 관례로 맞추기 위함. */
    private static String broadcastFilterKey(kfc.udp.client.webrtc.P2PConfig.BroadcastFilter f) {
        return switch (f) {
            case ALL -> "all";
            case ALLOWED_ONLY -> "allowed_only";
            case DISALLOWED_ONLY -> "disallowed_only";
        };
    }

    static final int TITLE_Y = CHANNEL_Y + (CHANNEL_FIELD_H - 9) / 2;

    /** 검색창(좌측) + 중계 통신 강제(우측, 검색창이 차지하고 남는 폭) — 첫 행 바로
     * 아래 둘째 행. 예전엔 빠른 시작이 검색창과 다른 행에서 한 줄을 통째로 더 썼는데,
     * 빠른 시작을 첫 행 우측(Regen Invite 자리)으로 옮기면서 이 행 하나로 줄었다.
     * 지금은 그 자리를 방송인 역할 받기 버튼이 대신 차지한다 — 빠른 시작은 기능(quickStartButton
     * 필드·onQuickStart·활성화 로직)은 그대로 두고 화면에 추가만 안 해서 안 보이게 했다
     * (init()의 quickStartButton 관련 주석 참고). 검색창과 구분선 사이엔 숨 쉴 틈을 남겨서
     * 구분선이 내용에 바짝 붙어 보이지 않게 했다. */
    static final int SEARCH_Y      = CHANNEL_Y + CHANNEL_FIELD_H + 4;
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
    static final int BLOCK_BTN = 15; // 홀수 — 테두리 안쪽(13px)이라 5px 글자가 양쪽 4px로 딱 맞는다
    static final int BLOCK_BTN_COLOR = 0xFF505050;
    static final int BLOCK_BTN_HOVER_COLOR = 0xFF707070;
    /** 버튼 안 아이콘(❌·♻·⚡·📶 등)의 세로 위치 — 가로는 이모지마다 실측 너비가 달라 glyphX로
     * 매번 다시 재는데, 세로는 전부 9px 높이 글자라 이 오프셋 하나로 공통 중앙 정렬이 된다:
     * (15-5)/2-2=3 (그림자는 어두워 눈에 안 잡히므로 밝은 5×5 글자 기준으로 잡은 값). */
    static final int BLOCK_X_DY = (BLOCK_BTN - 5) / 2 - 2;
    /** 차단 버튼 바로 왼쪽의 핑 막대 — 바닐라 서버 목록 스프라이트 크기 그대로. 세로는 칸 가운데가
     * 아니라 첫째 줄(제목) 높이에 맞춰 위쪽에 둔다(y+4) — 둘째 줄 오른쪽 끝의 버전 표기와 세로로
     * 두 줄처럼 보이게, 버전의 오른쪽 끝도 이 핑 막대의 오른쪽 끝(bx-3)에 맞춘다. */
    private static final int PING_W = 10;
    private static final int PING_H = 8;
    /** 둘째 줄의 인원 표기(N/M)와 버전 표기 사이 간격 — 공백 한 칸(바닐라 폰트에서 4px).
     * 인원은 버전의 왼쪽 끝에 맞춰 우측 정렬하고, 닉네임은 그 앞까지만 잘린다. */
    private static final int COUNT_GAP = 4;
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
    private int codeRowY, cancelY, divider2Y;

    static final int ROW_BG_COLOR          = 0xFF000000;
    static final int ROW_BORDER_COLOR      = 0xFFA0A0A0;
    static final int ROW_BORDER_HOVER_COLOR = 0xFFFFFFFF;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT      = Component.translatable("instant-p2p.room_list.title");
    private static final Component EMPTY_TEXT      = Component.translatable("instant-p2p.room_list.empty");
    private static final Component CODE_LABEL_TEXT = Component.translatable("instant-p2p.join_room.code_label");
    private static final Component JOIN_TEXT       = Component.translatable("instant-p2p.join_room.join");
    private static final Component FORCE_RELAY_TEXT = Component.translatable("instant-p2p.force_relay");
    private static final Component FORCE_RELAY_TOOLTIP_TEXT = Component.translatable("instant-p2p.force_relay_tooltip");
    private static final Component SEARCH_LABEL_TEXT = Component.translatable("instant-p2p.room_list.search_label");
    private static final Component QUICK_START_TEXT = Component.translatable("instant-p2p.room_list.quick_start");
    private static final Component CHANNEL_SETTINGS_TEXT = Component.translatable("instant-p2p.channel.settings");
    private static final Component BLOCKED_LIST_TEXT = Component.translatable("instant-p2p.room_list.blocked_list");
    private static final Component CHZZK_LINK_TEXT = Component.translatable("instant-p2p.room_list.chzzk_link");
    private static final Component BLOCK_TOOLTIP_TEXT = Component.translatable("instant-p2p.room_list.block_tooltip");
    private static final Component CLEANUP_TEXT = Component.translatable("instant-p2p.room_list.cleanup");
    private static final Component HIDE_OTHER_VERSIONS_TEXT = Component.translatable("instant-p2p.room_list.hide_other_versions");
    private static final Component HIDE_OTHER_VERSIONS_TOOLTIP_TEXT = Component.translatable("instant-p2p.room_list.hide_other_versions_tooltip");
    private static final Component BROADCAST_FILTER_TEXT = Component.translatable("instant-p2p.room_list.broadcast_filter");
    private static final Component REMOVED_TEXT = Component.translatable("instant-p2p.room_list.removed");
    private static final Component OTHER_VERSION_TOOLTIP_TEXT = Component.translatable("instant-p2p.room_list.other_version_tooltip");
    *///?} else {
    private static final Text TITLE_TEXT      = Text.translatable("instant-p2p.room_list.title");
    private static final Text EMPTY_TEXT      = Text.translatable("instant-p2p.room_list.empty");
    private static final Text CODE_LABEL_TEXT = Text.translatable("instant-p2p.join_room.code_label");
    private static final Text JOIN_TEXT       = Text.translatable("instant-p2p.join_room.join");
    private static final Text FORCE_RELAY_TEXT = Text.translatable("instant-p2p.force_relay");
    private static final Text FORCE_RELAY_TOOLTIP_TEXT = Text.translatable("instant-p2p.force_relay_tooltip");
    private static final Text SEARCH_LABEL_TEXT = Text.translatable("instant-p2p.room_list.search_label");
    private static final Text QUICK_START_TEXT = Text.translatable("instant-p2p.room_list.quick_start");
    private static final Text CHANNEL_SETTINGS_TEXT = Text.translatable("instant-p2p.channel.settings");
    private static final Text BLOCKED_LIST_TEXT = Text.translatable("instant-p2p.room_list.blocked_list");
    private static final Text CHZZK_LINK_TEXT = Text.translatable("instant-p2p.room_list.chzzk_link");
    private static final Text BLOCK_TOOLTIP_TEXT = Text.translatable("instant-p2p.room_list.block_tooltip");
    private static final Text CLEANUP_TEXT = Text.translatable("instant-p2p.room_list.cleanup");
    private static final Text HIDE_OTHER_VERSIONS_TEXT = Text.translatable("instant-p2p.room_list.hide_other_versions");
    private static final Text HIDE_OTHER_VERSIONS_TOOLTIP_TEXT = Text.translatable("instant-p2p.room_list.hide_other_versions_tooltip");
    private static final Text BROADCAST_FILTER_TEXT = Text.translatable("instant-p2p.room_list.broadcast_filter");
    private static final Text REMOVED_TEXT = Text.translatable("instant-p2p.room_list.removed");
    private static final Text OTHER_VERSION_TOOLTIP_TEXT = Text.translatable("instant-p2p.room_list.other_version_tooltip");
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
    /** 칸별로 "삭제됨"(방장이 닫았거나 끊긴 방)인지 — 회색 표시·선택 막기용. 정리 버튼·스크롤 전까지 자리에 남는다. */
    private boolean[] rowRemoved = new boolean[0];
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
    @Nullable private Button cleanupButton;
    *///?} else {
    private final java.util.List<ButtonWidget> rowButtons = new java.util.ArrayList<>();
    @Nullable private TextWidget emptyLabel;
    @Nullable private TextFieldWidget searchField;
    @Nullable private TextFieldWidget codeField;
    @Nullable private ButtonWidget joinButton;
    @Nullable private ButtonWidget quickStartButton;
    @Nullable private ButtonWidget cleanupButton;
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
        this.divider2Y = sectionRowY - 8; // 구분선 밑 1~7px = 가로 스크롤바 자리

        // 채널 설정 버튼 — 채널 값은 별도 화면(ChannelScreen)에서만 보인다(방송 화면에 채널이 드러나지 않게).
        // cx-155는 CustomRoomScreen의 Regen Invite(우측 상단, (cx+155)-100)와 중심 기준 좌우반전된 위치.
        this.addRenderableWidget(Button.builder(CHANNEL_SETTINGS_TEXT, b ->
                        Objects.requireNonNull(this.minecraft).setScreenAndShow(new ChannelScreen(this)))
                .bounds(cx - 155, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H).build());

        // 빠른 시작 — 자리는 그대로(우측 상단, CustomRoomScreen의 Regen Invite와 같은 위치)
        // 두되 화면에는 추가하지 않는다: 기능(활성화 토글·onQuickStart)은 남기고 버튼만 숨긴다.
        // 지금 이 자리는 아래 방송인 역할 받기 버튼이 대신 차지한다.
        this.quickStartButton = Button.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .bounds((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                .build();
        this.quickStartButton.active = false;

        // 방송인 역할 받기(치지직 연동) — 빠른 시작이 쓰던 우측 상단 자리를 대신 차지한다.
        this.addRenderableWidget(
                Button.builder(CHZZK_LINK_TEXT, b ->
                        Objects.requireNonNull(this.minecraft).setScreenAndShow(new ChzzkLinkScreen(this)))
                        .bounds((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                        .build()
        );

        // 초대코드 입력란 바로 위 줄 — 체크박스 둘을 가로로 이어 붙여 화면 가운데에 둔다(라벨 길이가 언어마다 달라
        // 만든 뒤 폭을 잰다).
        // Checkbox.Builder.build()의 tooltip()은 라벨이 한 줄을 넘칠 때만(overflowsRowLimit) 실제로
        // setTooltip을 불러준다(바이트코드로 확인) — 우리 라벨은 전부 한 줄이라 빌더로는 절대 안 뜬다.
        // build() 뒤에 Checkbox.setTooltip을 직접 불러야 라벨 길이와 무관하게 항상 붙는다.
        var hideVersionsCheckbox = Checkbox.builder(HIDE_OTHER_VERSIONS_TEXT, this.font)
                .pos(0, sectionRowY + CHECKBOX_DY)
                .selected(kfc.udp.client.webrtc.P2PConfig.isHideOtherVersions())
                .onValueChange((cb, value) -> {
                    kfc.udp.client.webrtc.P2PConfig.setHideOtherVersions(value);
                    this.refreshRooms();
                })
                .build();
        hideVersionsCheckbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(HIDE_OTHER_VERSIONS_TOOLTIP_TEXT));
        var forceRelayCheckbox = Checkbox.builder(FORCE_RELAY_TEXT, this.font)
                .pos(0, sectionRowY + CHECKBOX_DY)
                .selected(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                .onValueChange((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                .build();
        forceRelayCheckbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(FORCE_RELAY_TOOLTIP_TEXT));
        // 오른쪽에 하나 더 — 방송 필터. 체크박스(2상태)로는 "비방송만 보기"를 표현할 수 없어서 순환
        // 버튼(전체/허용만/비허용만 3상태)으로 둔다. 순수 로컬 필터 — PublicRoomAnnouncer가 채널
        // 문자열에 붙이는 태그를 읽기만 한다(P2PConfig.announcedChannel/isBroadcastTagged 참고).
        int broadcastFilterW = 130;
        var broadcastFilterButton = net.minecraft.client.gui.components.CycleButton.builder(
                        (kfc.udp.client.webrtc.P2PConfig.BroadcastFilter f) -> Component.translatable(
                                "instant-p2p.room_list.broadcast_filter." + broadcastFilterKey(f)),
                        kfc.udp.client.webrtc.P2PConfig.getBroadcastFilter())
                .withValues(kfc.udp.client.webrtc.P2PConfig.BroadcastFilter.values())
                .withTooltip(f -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                        "instant-p2p.room_list.broadcast_filter_tooltip." + broadcastFilterKey(f))))
                .create(0, sectionRowY, broadcastFilterW, 20, BROADCAST_FILTER_TEXT, (btn, value) -> {
                    kfc.udp.client.webrtc.P2PConfig.setBroadcastFilter(value);
                    this.refreshRooms();
                });
        // 왼쪽부터 다른 버전 숨기기 · 방송 필터(가운데) · 중계 통신 강제(오른쪽) 순으로 배치한다.
        int checkboxesW = hideVersionsCheckbox.getWidth() + CHECKBOX_GAP + broadcastFilterW
                + CHECKBOX_GAP + forceRelayCheckbox.getWidth();
        int checkboxX = cx - checkboxesW / 2;
        hideVersionsCheckbox.setX(checkboxX);
        broadcastFilterButton.setX(checkboxX + hideVersionsCheckbox.getWidth() + CHECKBOX_GAP);
        forceRelayCheckbox.setX(broadcastFilterButton.getX() + broadcastFilterW + CHECKBOX_GAP);
        this.addRenderableWidget(hideVersionsCheckbox);
        this.addRenderableWidget(forceRelayCheckbox);
        this.addRenderableWidget(broadcastFilterButton);

        // 검색 줄 — 왼쪽 삭제된 방 정리(채널 설정 버튼과 왼쪽 끝을 맞춤), 오른쪽 차단 목록(랜덤 접속과 오른쪽 끝을 맞춤,
        // 아래에서 추가), 가운데 검색창이 그 사이를 6px씩 띄우고 채운다.
        this.cleanupButton = Button.builder(CLEANUP_TEXT, b -> this.onCleanupClicked())
                .bounds(cx - 155, SEARCH_Y, REFRESH_W, 20).build();
        this.addRenderableWidget(this.cleanupButton);
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
        this.rowRemoved = new boolean[slots];
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
        this.updateCleanupButton();
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
        this.divider2Y = sectionRowY - 8; // 구분선 밑 1~7px = 가로 스크롤바 자리

        // 채널 설정 버튼 — 채널 값은 별도 화면(ChannelScreen)에서만 보인다(방송 화면에 채널이 드러나지 않게).
        // cx-155는 CustomRoomScreen의 Regen Invite(우측 상단, (cx+155)-100)와 중심 기준 좌우반전된 위치.
        this.addDrawableChild(ButtonWidget.builder(CHANNEL_SETTINGS_TEXT, b ->
                        Objects.requireNonNull(this.client).setScreen(new ChannelScreen(this)))
                .dimensions(cx - 155, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H).build());

        // 빠른 시작 — 자리는 그대로(우측 상단, CustomRoomScreen의 Regen Invite와 같은 위치)
        // 두되 화면에는 추가하지 않는다: 기능(활성화 토글·onQuickStart)은 남기고 버튼만 숨긴다.
        // 지금 이 자리는 아래 방송인 역할 받기 버튼이 대신 차지한다.
        this.quickStartButton = ButtonWidget.builder(QUICK_START_TEXT, b -> this.onQuickStart())
                .dimensions((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                .build();
        this.quickStartButton.active = false;

        // 방송인 역할 받기(치지직 연동) — 빠른 시작이 쓰던 우측 상단 자리를 대신 차지한다.
        this.addDrawableChild(
                ButtonWidget.builder(CHZZK_LINK_TEXT, b ->
                        Objects.requireNonNull(this.client).setScreen(new ChzzkLinkScreen(this)))
                        .dimensions((cx + 155) - CHANNEL_FIELD_W, CHANNEL_Y, CHANNEL_FIELD_W, CHANNEL_FIELD_H)
                        .build()
        );

        // 초대코드 입력란 바로 위 줄 — 체크박스 둘을 가로로 이어 붙여 화면 가운데에 둔다(라벨 길이가 언어마다 달라
        // 만든 뒤 폭을 잰다).
        var hideVersionsCheckbox = CheckboxWidget.builder(HIDE_OTHER_VERSIONS_TEXT, this.textRenderer)
                .pos(0, sectionRowY + CHECKBOX_DY)
                .checked(kfc.udp.client.webrtc.P2PConfig.isHideOtherVersions())
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(HIDE_OTHER_VERSIONS_TOOLTIP_TEXT))
                .callback((cb, value) -> {
                    kfc.udp.client.webrtc.P2PConfig.setHideOtherVersions(value);
                    this.refreshRooms();
                })
                .build();
        var forceRelayCheckbox = CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                .pos(0, sectionRowY + CHECKBOX_DY)
                .checked(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(FORCE_RELAY_TOOLTIP_TEXT))
                .callback((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                .build();
        // 오른쪽에 하나 더 — 방송 필터. 체크박스(2상태)로는 "비방송만 보기"를 표현할 수 없어서 순환
        // 버튼(전체/허용만/비허용만 3상태)으로 둔다. 순수 로컬 필터 — PublicRoomAnnouncer가 채널
        // 문자열에 붙이는 태그를 읽기만 한다(P2PConfig.announcedChannel/isBroadcastTagged 참고).
        int broadcastFilterW = 130;
        //? if >=1.21.11 <26.1 {
        /*var broadcastFilterButton = CyclingButtonWidget.builder(
                        (kfc.udp.client.webrtc.P2PConfig.BroadcastFilter f) -> Text.translatable(
                                "instant-p2p.room_list.broadcast_filter." + broadcastFilterKey(f)),
                        kfc.udp.client.webrtc.P2PConfig.getBroadcastFilter())
                .values(kfc.udp.client.webrtc.P2PConfig.BroadcastFilter.values())
                .tooltip(f -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable(
                        "instant-p2p.room_list.broadcast_filter_tooltip." + broadcastFilterKey(f))))
                .build(0, sectionRowY, broadcastFilterW, 20, BROADCAST_FILTER_TEXT, (btn, value) -> {
                    kfc.udp.client.webrtc.P2PConfig.setBroadcastFilter(value);
                    this.refreshRooms();
                });
        *///?}
        //? if <1.21.11 {
        var broadcastFilterButton = CyclingButtonWidget.builder(
                        (kfc.udp.client.webrtc.P2PConfig.BroadcastFilter f) -> Text.translatable(
                                "instant-p2p.room_list.broadcast_filter." + broadcastFilterKey(f)))
                .values(kfc.udp.client.webrtc.P2PConfig.BroadcastFilter.values())
                .initially(kfc.udp.client.webrtc.P2PConfig.getBroadcastFilter())
                .tooltip(f -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable(
                        "instant-p2p.room_list.broadcast_filter_tooltip." + broadcastFilterKey(f))))
                .build(0, sectionRowY, broadcastFilterW, 20, BROADCAST_FILTER_TEXT, (btn, value) -> {
                    kfc.udp.client.webrtc.P2PConfig.setBroadcastFilter(value);
                    this.refreshRooms();
                });
        //?}
        // 왼쪽부터 다른 버전 숨기기 · 방송 필터(가운데) · 중계 통신 강제(오른쪽) 순으로 배치한다.
        int checkboxesW = hideVersionsCheckbox.getWidth() + CHECKBOX_GAP + broadcastFilterW
                + CHECKBOX_GAP + forceRelayCheckbox.getWidth();
        int checkboxX = cx - checkboxesW / 2;
        hideVersionsCheckbox.setX(checkboxX);
        broadcastFilterButton.setX(checkboxX + hideVersionsCheckbox.getWidth() + CHECKBOX_GAP);
        forceRelayCheckbox.setX(broadcastFilterButton.getX() + broadcastFilterW + CHECKBOX_GAP);
        this.addDrawableChild(hideVersionsCheckbox);
        this.addDrawableChild(forceRelayCheckbox);
        this.addDrawableChild(broadcastFilterButton);

        // 검색 줄 — 왼쪽 삭제된 방 정리(채널 설정 버튼과 왼쪽 끝을 맞춤), 오른쪽 차단 목록(랜덤 접속과 오른쪽 끝을 맞춤,
        // 아래에서 추가), 가운데 검색창이 그 사이를 6px씩 띄우고 채운다.
        this.cleanupButton = ButtonWidget.builder(CLEANUP_TEXT, b -> this.onCleanupClicked())
                .dimensions(cx - 155, SEARCH_Y, REFRESH_W, 20).build();
        this.addDrawableChild(this.cleanupButton);
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
        this.rowRemoved = new boolean[slots];
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
        this.updateCleanupButton();
    }
    //?}

    @Override
    public void tick() {
        super.tick();
        this.refreshRooms();
        this.updateCleanupButton();
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
    // 방 목록 갱신 — 항상 서버 목록을 그대로 따라간다(새 방은 바로 들어온다). 사라진 방은 바로 빼지 않는다 — 인원이
    // 바뀔 때 방장이 재공지하느라 잠깐(보통 1초 안) 끊겼다 다시 붙으므로 FLICKER_GRACE_MS 동안은 원래대로 두고, 그 뒤로도
    // 없으면 "삭제됨"으로 표시만 하고 자리에 남긴다. 정리 버튼을 누르거나 목록을 스크롤할 때 뺀다(cleanupRemoved) —
    // 고르려던 방이 멋대로 자리를 옮기지 않게. 정렬은 연 시각 순(오래된 방이 앞)이라 새 방은 뒤에 붙는다.
    private static final long FLICKER_GRACE_MS = 1_500;
    private static final long CLEANUP_COOLDOWN_MS = 1_000;
    /** 검색 줄 왼쪽 삭제된 방 정리 버튼 폭(차단 목록 버튼과 같은 폭으로 좌우 대칭). */
    private static final int REFRESH_W = 90;
    private long cleanupCooldownUntilMs;
    private List<PublicRoomBrowser.RoomEntry> liveSource;
    /** 지금 보여주는 방(살아 있는 방 + 삭제됨 표시 방) — 코드 → 마지막으로 받은 정보. */
    private final java.util.Map<String, PublicRoomBrowser.RoomEntry> shown = new java.util.HashMap<>();
    private final java.util.Map<String, Long> missingSince = new java.util.HashMap<>();
    private List<PublicRoomBrowser.RoomEntry> displayList = List.of();

    /** 보여줄 목록 — 구성이 바뀔 때만 새 리스트를 만들어서, 참조가 같으면 내용도 같다(필터 캐시가 이걸 믿는다). */
    private List<PublicRoomBrowser.RoomEntry> displayRooms() {
        List<PublicRoomBrowser.RoomEntry> live = this.browser.getCurrentRooms();
        if (live == this.liveSource) return this.displayList;
        this.liveSource = live;
        long now = System.currentTimeMillis();
        java.util.Map<String, PublicRoomBrowser.RoomEntry> liveByCode = new java.util.HashMap<>();
        for (PublicRoomBrowser.RoomEntry r : live) liveByCode.put(r.code(), r);
        this.shown.putAll(liveByCode);
        for (var e : this.shown.entrySet()) {
            PublicRoomBrowser.RoomEntry latest = liveByCode.get(e.getKey());
            if (latest != null) {
                e.setValue(latest);
                this.missingSince.remove(e.getKey());
            } else {
                this.missingSince.putIfAbsent(e.getKey(), now);
            }
        }
        this.rebuildDisplayList();
        return this.displayList;
    }

    private void rebuildDisplayList() {
        this.displayList = this.shown.values().stream()
                .sorted(java.util.Comparator.comparingLong(PublicRoomBrowser.RoomEntry::openedAtMs)
                        .thenComparing(PublicRoomBrowser.RoomEntry::code))
                .toList();
    }

    /** 잠깐 끊긴 게 아니라 정말 사라진 방인지 — FLICKER_GRACE_MS가 지난 뒤부터 "삭제됨"이다. */
    private boolean isRemoved(String code) {
        Long since = this.missingSince.get(code);
        return since != null && System.currentTimeMillis() - since >= FLICKER_GRACE_MS;
    }

    /** 채널 설정 화면에서 적용을 눌렀을 때 — 새 채널들의 lobby로 다시 구독하고 보이던 목록은 비운다. */
    void onChannelsChanged() {
        this.browser.restart();
        this.shown.clear();
        this.missingSince.clear();
        this.liveSource = null;
        this.displayList = List.of();
        this.scrollCol = 0;
    }

    /** "삭제됨" 방을 목록에서 뺀다 — 정리 버튼·스크롤 때. */
    private void cleanupRemoved() {
        boolean changed = false;
        var it = this.missingSince.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!this.isRemoved(e.getKey())) continue;
            this.shown.remove(e.getKey());
            it.remove();
            changed = true;
        }
        if (changed) this.rebuildDisplayList();
    }

    private void onCleanupClicked() {
        long now = System.currentTimeMillis();
        if (now < this.cleanupCooldownUntilMs) return;
        this.cleanupCooldownUntilMs = now + CLEANUP_COOLDOWN_MS;
        this.cleanupRemoved();
        this.refreshRooms();
        this.updateCleanupButton();
    }

    private void refreshRooms() {
        List<PublicRoomBrowser.RoomEntry> all = this.displayRooms();
        // 채널 목록·버전 숨기기·방송 필터까지 캐시 키에 넣는다 — 안 넣으면 그 값만 바뀌었을 땐
        // all/searchQuery/banVersion이 그대로라 재계산을 건너뛰어서, 체크박스를 눌러도 화면을
        // 나갔다 들어와야(다른 값이 바뀌어 캐시가 깨져야) 반영되는 것처럼 보인다 — 방송 필터가
        // 실제로 이 버그였다.
        String channel = kfc.udp.client.webrtc.P2PConfig.getChannelKey()
                + (kfc.udp.client.webrtc.P2PConfig.isHideOtherVersions() ? "#v" : "")
                + "#b" + kfc.udp.client.webrtc.P2PConfig.getBroadcastFilter();
        int banVersion = kfc.udp.client.webrtc.P2PBanManager.banListVersion();
        if (all != this.filterSource || !channel.equals(this.filterChannel)
                || !this.searchQuery.equals(this.filterQuery) || banVersion != this.filterBanVersion) {
            String myUuid = this.myUuid();
            String q = this.searchQuery.trim().toLowerCase(Locale.ROOT);
            List<String> mine = kfc.udp.client.webrtc.P2PConfig.getChannels();
            boolean mineAnd = kfc.udp.client.webrtc.P2PConfig.isChannelAnd();
            boolean hideOtherVersions = kfc.udp.client.webrtc.P2PConfig.isHideOtherVersions();
            kfc.udp.client.webrtc.P2PConfig.BroadcastFilter broadcastFilter = kfc.udp.client.webrtc.P2PConfig.getBroadcastFilter();
            this.filteredRooms = all.stream()
                    .filter(r -> kfc.udp.client.webrtc.P2PConfig.roomVisible(
                            kfc.udp.client.webrtc.P2PConfig.stripBroadcastTag(r.channel()), r.channelAnd(), mine, mineAnd))
                    .filter(r -> !hideOtherVersions || r.sameVersion())
                    .filter(r -> switch (broadcastFilter) {
                        case ALL -> true;
                        case ALLOWED_ONLY -> kfc.udp.client.webrtc.P2PConfig.isBroadcastTagged(r.channel());
                        case DISALLOWED_ONLY -> !kfc.udp.client.webrtc.P2PConfig.isBroadcastTagged(r.channel());
                    })
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
                this.rowRemoved[i] = this.isRemoved(room.code());
                btn.visible = true;
            } else {
                this.rowRoom[i] = null;
                this.rowRemoved[i] = false;
                btn.visible = false;
            }
        }

        if (this.emptyLabel != null) this.emptyLabel.visible = rooms.isEmpty();
        if (this.quickStartButton != null) this.quickStartButton.active = !this.joinableRooms().isEmpty();
    }

    /** 지금 들어갈 수 있는 방 — 삭제됨·다른 버전은 뺀다. */
    private List<PublicRoomBrowser.RoomEntry> joinableRooms() {
        return this.filteredRooms.stream().filter(r -> r.sameVersion() && !this.isRemoved(r.code())).toList();
    }

    /** 방 칸 클릭 = 바로 접속. 삭제됨·다른 버전 방은 눌러도 접속 실패만 뜨므로 막는다. */
    private void onRowClicked(int row) {
        PublicRoomBrowser.RoomEntry r = this.rowRoom[row];
        if (r == null || this.rowRemoved[row] || !r.sameVersion()) return;
        if (!this.stillLive(r.code())) return;
        this.confirmCapacityThenJoin(r);
    }

    /** 정원이 찬 방에 개발자·서포터 특권으로 밀고 들어가려 할 때 — "다시 보지 않기"를 안 눌렀다면
     * — 접속 전에 한 번 확인한다. 개발자·서포터도 이제 정원에 그대로 세이므로(P2PBanManager.
     * countedPlayers), 특권은 "정원이 차도 들어갈 수 있다"는 뜻이 됐다 — 조용히 들어가는 대신
     * 한 번은 스스로 확인하게 한다. 방 목록에 이미 있는 현재/최대 인원(r)만으로 판단하므로
     * (RoomMembersProbe 같은 별도 네트워크 조회 없이) 초대 코드로 직접 입장할 때는 이 확인이
     * 안 뜬다 — 그 경로는 사전에 정원을 알 방법이 없다. */
    private void confirmCapacityThenJoin(PublicRoomBrowser.RoomEntry r) {
        //? if >=26.1 {
        /*java.util.UUID me = net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
        *///?} else {
        java.util.UUID me = net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
        //?}
        boolean full = r.maxPlayers() > 0 && r.currentPlayers() >= r.maxPlayers();
        if (!full || me == null || !kfc.udp.client.DevBadge.hasPerk(me)
                || kfc.udp.client.webrtc.P2PConfig.isCapacityBypassWarningDismissed()) {
            this.confirmBroadcastThenJoin(r);
            return;
        }
        //? if >=26.1 {
        /*assert this.minecraft != null;
        this.minecraft.setScreenAndShow(new SafetyWarningScreen(this,
                "instant-p2p.join_capacity_warning.heading", "instant-p2p.join_capacity_warning.message",
                0xFFFFFF55, 0xFF1A1A00, kfc.udp.client.webrtc.P2PConfig::setCapacityBypassWarningDismissed,
                () -> this.confirmBroadcastThenJoin(r)));
        *///?} else {
        assert this.client != null;
        this.client.setScreen(new SafetyWarningScreen(this,
                "instant-p2p.join_capacity_warning.heading", "instant-p2p.join_capacity_warning.message",
                0xFFFFFF55, 0xFF1A1A00, kfc.udp.client.webrtc.P2PConfig::setCapacityBypassWarningDismissed,
                () -> this.confirmBroadcastThenJoin(r)));
        //?}
    }

    /** 방송 비허용 방(P2PConfig.isBroadcastTagged가 false)이면 — "다시 보지 않기"를 안 눌렀다면
     * — 접속 전에 노란 확인 팝업을 한 번 띄운다. 방송 중인 사람이 실수로 그런 방에 들어가는 걸
     * 막고, 방송 필터로 걸러 쓰라고 안내하기 위함(SafetyWarningScreen 클래스 주석 참고). */
    private void confirmBroadcastThenJoin(PublicRoomBrowser.RoomEntry r) {
        if (kfc.udp.client.webrtc.P2PConfig.isBroadcastTagged(r.channel())
                || kfc.udp.client.webrtc.P2PConfig.isBroadcastJoinWarningDismissed()) {
            this.joinRoom(r.code());
            return;
        }
        String code = r.code();
        //? if >=26.1 {
        /*assert this.minecraft != null;
        this.minecraft.setScreenAndShow(new SafetyWarningScreen(this,
                "instant-p2p.join_broadcast_warning.heading", "instant-p2p.join_broadcast_warning.message",
                0xFFFFFF55, 0xFF1A1A00, kfc.udp.client.webrtc.P2PConfig::setBroadcastJoinWarningDismissed,
                () -> this.joinRoom(code)));
        *///?} else {
        assert this.client != null;
        this.client.setScreen(new SafetyWarningScreen(this,
                "instant-p2p.join_broadcast_warning.heading", "instant-p2p.join_broadcast_warning.message",
                0xFFFFFF55, 0xFF1A1A00, kfc.udp.client.webrtc.P2PConfig::setBroadcastJoinWarningDismissed,
                () -> this.joinRoom(code)));
        //?}
    }

    /** 클릭 시점에 정말 아직 살아 있는지 한 번 더 확인한다 — rowRemoved는 깜빡임 방지용 유예
     * (FLICKER_GRACE_MS) 때문에 방이 사라진 지 1.5초 안이면 아직 "삭제됨"으로 안 바뀐다. 그
     * 좁은 틈에 클릭하면 호스트가 이미 없는 걸 알면서도 접속을 시도해 호스트 대기 타임아웃
     * (WebRtcClient.HOST_ARRIVE_TIMEOUT_SEC)까지 기다리게 된다 — browser.getCurrentRooms()는
     * 네트워크 왕복 없이 이미 로컬에 있는 최신 라이브 목록이라, 여기서 유예 없이 바로 거르면
     * 그 대기를 대부분 건너뛸 수 있다. 걸러지면 missingSince를 강제로 만료시켜 그 자리가
     * 바로 "삭제됨"으로 보이게 한다(다시 눌러도 또 시도하지 않도록).
     */
    private boolean stillLive(String code) {
        if (this.browser.getCurrentRooms().stream().anyMatch(r -> r.code().equals(code))) return true;
        this.missingSince.put(code, 0L);
        this.refreshRooms();
        return false;
    }

    private void joinRoom(String code) {
        //? if >=26.1 {
        /*assert this.minecraft != null;
        KfcudpClient.joinRoomByCode(this.minecraft, this.parent, code);
        *///?} else {
        assert this.client != null;
        KfcudpClient.joinRoomByCode(this.client, this.parent, code);
        //?}
    }

    /** 검색 결과(비어 있으면 전체) 중 무작위 방 하나에 바로 접속한다. */
    private void onQuickStart() {
        List<PublicRoomBrowser.RoomEntry> rooms = this.joinableRooms();
        if (rooms.isEmpty()) return;
        PublicRoomBrowser.RoomEntry r = rooms.get(RANDOM.nextInt(rooms.size()));
        if (!this.stillLive(r.code())) return; // 다시 누르면 방금 걸러진 자리 대신 다른 방이 뽑힌다.
        this.confirmBroadcastThenJoin(r);
    }

    /** 접속 버튼·Enter — 입력한 초대 코드로. */
    private void onJoinByCode() {
        assert this.codeField != null;
        //? if >=26.1 {
        /*String code = this.codeField.getValue().trim();
        *///?} else {
        String code = this.codeField.getText().trim();
        //?}
        if (code.isEmpty()) return;
        this.joinRoom(code);
    }

    //? if >=26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        if (this.popup.keyPressed(input.key())) return true;
        if (input.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN && this.joinButton != null && this.joinButton.active) { // Enter
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
        // 휠 아래 = 오른쪽으로 한 열, 위 = 왼쪽으로 한 열. 넘길 때 "삭제됨" 방도 정리한다.
        int newCol = Math.max(0, Math.min(maxCol, this.scrollCol - (int) Math.signum(verticalAmount)));
        if (newCol != this.scrollCol) {
            this.scrollCol = newCol;
            this.cleanupRemoved();
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
            this.cleanupRemoved();
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

    /** 마우스가 올라간 칸이 다른 버전 방이면 그 칸, 아니면 -1 — 왜 못 고르는지 툴팁으로 알려준다. */
    private int hoveredOtherVersionRow() {
        for (int i = 0; i < this.rowRoom.length; i++) {
            if (this.rowRoom[i] != null && !this.rowRoom[i].sameVersion() && this.rowButtons.get(i).isHovered()) return i;
        }
        return -1;
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

    // 왼쪽 버튼 번호는 26.3에서 0 → 1로 바뀌었다(오른쪽 1 → 3) — 숫자 대신 InputConstants 상수로 비교한다.
    // 마우스 클릭/드래그/릴리즈 콜백 시그니처가 1.21.9에서 (double,double,int,...)에서
    // 값 타입(Click/MouseButtonEvent) 하나로 바뀌었다 — keyPressed의 KeyEvent/KeyInput/
    // 순수 int 3분기와 같은 이유·같은 패턴(독립된 3개 블록, 안 겹치는 조건이라
    // if/else로 안 엮어도 버전마다 정확히 하나만 살아남는다).
    //? if >=26.1 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (this.popup.isOpen()) {
            if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) this.popup.mouseClicked(click.x(), click.y());
            return true;
        }
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && this.isOnScrollbarTrack(click.x(), click.y())) {
            this.draggingScrollbar = true;
            this.scrollToTrackX(click.x());
            return true;
        }
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
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
            boolean closed = this.rowRemoved[i];
            boolean otherVersion = !r.sameVersion();
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.outline(x, y, CELL_W, CELL_H, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            // 제목은 MAX_TITLE_LENGTH로 막지만 다른 클라이언트가 보낸 값이라 폭으로도 한 번 더 자른다.
            context.text(this.font, this.font.plainSubstrByWidth(r.title(), textW), x + 6, y + 3, closed || otherVersion ? 0xFF707070 : 0xFFFFFFFF);
            // 둘째 줄: 닉네임 … (인원) 버전(오른쪽 끝). 버전이 다르면 버전을 빨갛게.
            String version = r.version();
            int versionW = this.font.width(version);
            String count = "(" + r.currentPlayers() + "/" + r.maxPlayers() + ")";
            int countX = bx - 3 - versionW - COUNT_GAP - this.font.width(count);
            context.text(this.font, closed ? REMOVED_TEXT.getString()
                            : this.font.plainSubstrByWidth(r.hostNickname(), Math.max(0, countX - 3 - (x + 6))),
                    x + 6, y + 13, closed ? 0xFFFF5555 : 0xFFA0A0A0);
            if (!closed) context.text(this.font, count, countX, y + 13, 0xFFA0A0A0);
            context.text(this.font, version, bx - 3 - versionW, y + 13, otherVersion ? 0xFFFF5555 : 0xFF707070);
            if (!closed) this.drawPingSprite(context, pingSprite(r.estimatedPingMs(), i), bx - 3 - PING_W, y + 4);
            // 차단 버튼 — 회색 바탕에 빨간 ❌. 목록엔 아직 차단 안 한 방장만 뜨므로 ❌ 하나뿐이다.
            glyphButton(context, this.font, "❌", bx, by, 0xFFFF5555, hoveredBlock == i);
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
        int hoveredRow = this.hoveredOtherVersionRow();
        if (hoveredRow >= 0) {
            context.setTooltipForNextFrame(this.font, OTHER_VERSION_TOOLTIP_TEXT, mouseX, mouseY);
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
            boolean closed = this.rowRemoved[i];
            boolean otherVersion = !r.sameVersion();
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            // drawBorder → drawStrokedRectangle 개명(1.21.9~) — DrawContext.java 클래스 주석 없음, javap로 확인.
            context.drawStrokedRectangle(x, y, CELL_W, CELL_H, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            // 제목은 MAX_TITLE_LENGTH로 막지만 다른 클라이언트가 보낸 값이라 폭으로도 한 번 더 자른다.
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(r.title(), textW)), x + 6, y + 3, closed || otherVersion ? 0xFF707070 : 0xFFFFFFFF);
            // 둘째 줄: 닉네임 … (인원) 버전(오른쪽 끝). 버전이 다르면 버전을 빨갛게.
            String version = r.version();
            int versionW = this.textRenderer.getWidth(version);
            String count = "(" + r.currentPlayers() + "/" + r.maxPlayers() + ")";
            int countX = bx - 3 - versionW - COUNT_GAP - this.textRenderer.getWidth(count);
            context.drawTextWithShadow(this.textRenderer, Text.literal(closed ? REMOVED_TEXT.getString()
                            : this.textRenderer.trimToWidth(r.hostNickname(), Math.max(0, countX - 3 - (x + 6)))),
                    x + 6, y + 13, closed ? 0xFFFF5555 : 0xFFA0A0A0);
            if (!closed) context.drawTextWithShadow(this.textRenderer, Text.literal(count), countX, y + 13, 0xFFA0A0A0);
            context.drawTextWithShadow(this.textRenderer, Text.literal(version), bx - 3 - versionW, y + 13, otherVersion ? 0xFFFF5555 : 0xFF707070);
            if (!closed) this.drawPingSprite(context, pingSprite(r.estimatedPingMs(), i), bx - 3 - PING_W, y + 4);
            // 차단 버튼 — 회색 바탕에 빨간 ❌. 목록엔 아직 차단 안 한 방장만 뜨므로 ❌ 하나뿐이다.
            glyphButton(context, this.textRenderer, "❌", bx, by, 0xFFFF5555, hoveredBlock == i);
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
        int hoveredRow = this.hoveredOtherVersionRow();
        if (hoveredRow >= 0) {
            context.drawTooltip(this.textRenderer, OTHER_VERSION_TOOLTIP_TEXT, mouseX, mouseY);
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
            boolean closed = this.rowRemoved[i];
            boolean otherVersion = !r.sameVersion();
            context.fill(x, y, x + CELL_W, y + CELL_H, ROW_BG_COLOR);
            context.drawBorder(x, y, CELL_W, CELL_H, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
            // 제목은 MAX_TITLE_LENGTH로 막지만 다른 클라이언트가 보낸 값이라 폭으로도 한 번 더 자른다.
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(r.title(), textW)), x + 6, y + 3, closed || otherVersion ? 0xFF707070 : 0xFFFFFFFF);
            // 둘째 줄: 닉네임 … (인원) 버전(오른쪽 끝). 버전이 다르면 버전을 빨갛게.
            String version = r.version();
            int versionW = this.textRenderer.getWidth(version);
            String count = "(" + r.currentPlayers() + "/" + r.maxPlayers() + ")";
            int countX = bx - 3 - versionW - COUNT_GAP - this.textRenderer.getWidth(count);
            context.drawTextWithShadow(this.textRenderer, Text.literal(closed ? REMOVED_TEXT.getString()
                            : this.textRenderer.trimToWidth(r.hostNickname(), Math.max(0, countX - 3 - (x + 6)))),
                    x + 6, y + 13, closed ? 0xFFFF5555 : 0xFFA0A0A0);
            if (!closed) context.drawTextWithShadow(this.textRenderer, Text.literal(count), countX, y + 13, 0xFFA0A0A0);
            context.drawTextWithShadow(this.textRenderer, Text.literal(version), bx - 3 - versionW, y + 13, otherVersion ? 0xFFFF5555 : 0xFF707070);
            if (!closed) this.drawPingSprite(context, pingSprite(r.estimatedPingMs(), i), bx - 3 - PING_W, y + 4);
            // 차단 버튼 — 회색 바탕에 빨간 ❌. 목록엔 아직 차단 안 한 방장만 뜨므로 ❌ 하나뿐이다.
            glyphButton(context, this.textRenderer, "❌", bx, by, 0xFFFF5555, hoveredBlock == i);
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
        int hoveredRow = this.hoveredOtherVersionRow();
        if (hoveredRow >= 0) {
            context.drawTooltip(this.textRenderer, OTHER_VERSION_TOOLTIP_TEXT, mouseX, mouseY);
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

    *///?}

    // 정리 버튼 — 누르고 1초 동안은 다시 못 누른다. 매 tick 부른다.
    private void updateCleanupButton() {
        if (this.cleanupButton == null) return;
        this.cleanupButton.active = System.currentTimeMillis() >= this.cleanupCooldownUntilMs;
    }
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
    // ❌·♻·⚡·📶 전부 폭이 이모지마다 달라 고정 오프셋으로는 못 맞춘다 — 버튼 폭 안에서 실측
    // 너비로 가운데 정렬한다(가로). 세로는 전부 9px 높이라 BLOCK_X_DY 하나로 공통 중앙 정렬된다.
    //? if >=26.1 {
    /*static int glyphX(net.minecraft.client.gui.Font font, String glyph, int boxX) {
        return boxX + (BLOCK_BTN - font.width(glyph)) / 2;
    }
    *///?} else {
    static int glyphX(net.minecraft.client.font.TextRenderer font, String glyph, int boxX) {
        return boxX + (BLOCK_BTN - font.getWidth(glyph)) / 2;
    }
    //?}

    // ❌·⚡·♻ 아이콘만 실측 중앙 위치에서 살짝 처져 보여서 미세 조정한다.
    // ❌·♻는 아래로 1px(가로는 우측 보정 뒤 다시 왼쪽으로 1px 요청받아 결과적으로 0),
    // ⚡는 아래로 1px + 오른쪽으로 1px.
    static int glyphNudgeX(String glyph) {
        if (glyph.equals("⚡")) return 1;
        return 0;
    }

    static int glyphNudgeY(String glyph) {
        return glyph.equals("❌") || glyph.equals("⚡") || glyph.equals("♻") ? 1 : 0;
    }

    // 아이콘 버튼 한 칸(회색 바탕 + 테두리 + 가운데 정렬 아이콘) — 방 목록의 ❌와 차단 목록의
    // ❌/♻/⚡/📶가 전부 같은 모양이어야 하므로 그리는 곳을 여기 하나로 모았다. 예전엔 화면마다
    // 이 세 줄을 각자 그려서 같은 동작인데 아이콘·색이 어긋나는 버그가 났다
    // (BlockedPlayersScreen 클래스 주석 참고). 방장 표시(📶)는 클릭이 안 되니 hovered=false로 부른다.
    // 테두리 메서드 이름이 1.21.9(drawStrokedRectangle)·26.1(outline)에서 바뀌어 셋으로 나눈다.
    //? if >=26.1 {
    /*static void glyphButton(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font, String glyph,
                            int x, int y, int color, boolean hovered) {
        ctx.fill(x, y, x + BLOCK_BTN, y + BLOCK_BTN, hovered ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
        ctx.outline(x, y, BLOCK_BTN, BLOCK_BTN, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
        ctx.text(font, glyph, glyphX(font, glyph, x) + glyphNudgeX(glyph), y + BLOCK_X_DY + glyphNudgeY(glyph), color);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*static void glyphButton(DrawContext ctx, net.minecraft.client.font.TextRenderer font, String glyph,
                            int x, int y, int color, boolean hovered) {
        ctx.fill(x, y, x + BLOCK_BTN, y + BLOCK_BTN, hovered ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
        ctx.drawStrokedRectangle(x, y, BLOCK_BTN, BLOCK_BTN, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
        ctx.drawTextWithShadow(font, Text.literal(glyph), glyphX(font, glyph, x) + glyphNudgeX(glyph), y + BLOCK_X_DY + glyphNudgeY(glyph), color);
    }
    *///?}
    //? if <1.21.9 {
    static void glyphButton(DrawContext ctx, net.minecraft.client.font.TextRenderer font, String glyph,
                            int x, int y, int color, boolean hovered) {
        ctx.fill(x, y, x + BLOCK_BTN, y + BLOCK_BTN, hovered ? BLOCK_BTN_HOVER_COLOR : BLOCK_BTN_COLOR);
        ctx.drawBorder(x, y, BLOCK_BTN, BLOCK_BTN, hovered ? ROW_BORDER_HOVER_COLOR : ROW_BORDER_COLOR);
        ctx.drawTextWithShadow(font, Text.literal(glyph), glyphX(font, glyph, x) + glyphNudgeX(glyph), y + BLOCK_X_DY + glyphNudgeY(glyph), color);
    }
    //?}

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
