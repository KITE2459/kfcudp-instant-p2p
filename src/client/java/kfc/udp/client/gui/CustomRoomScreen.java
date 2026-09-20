package kfc.udp.client.gui;

import kfc.udp.client.KfcudpClient;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CustomRoomScreen extends Screen {

    private static final int MIN_PLAYERS         = 2;
    private static final int MAX_PLAYERS         = 100;

    // 기존 멀티플레이 화면처럼 가로 구분선으로 3개 섹션(상단: 게임모드/정원,
    // 중간: 방 옵션, 하단: 재생성/취소)을 나눈다. 상단/중간은 위에서부터, 하단은
    // RoomListScreen과 마찬가지로 this.height 기준으로 아래에서부터 고정한다
    // (작은 화면에서 하단 섹션이 침범당하지 않게) — divider2Y/buttonsY는 init()에서
    // 계산하는 인스턴스 필드. 재생성/취소 버튼 자체는 기존처럼 좌우로 배치된다.
    /** 방 옵션 전체 통합 "적용" 버튼 — 오른쪽 끝은 돌아가기 버튼과 맞춘다(cx+155),
     * 높이는 돌아가기 버튼과 동일(20). 화면 제목(TITLE_Y)이 이 버튼과 같은 높이에
     * 오도록 제목 Y도 버튼 높이 기준으로 맞춰 잡는다(세로 중앙 정렬 기준 역산). */
    private static final int APPLY_BUTTON_Y = 10;
    private static final int APPLY_BUTTON_H = 20;
    private static final int APPLY_BUTTON_W = 100;
    /** "방 닫기 🚫" 버튼 폭 — 라벨(약 45px)에 양옆 여백만 남긴다. */
    private static final int CLOSE_ROOM_W = 64;
    private static final int TITLE_Y = APPLY_BUTTON_Y + (APPLY_BUTTON_H - 9) / 2;
    private static final int ROW1_Y  = 55;
    private static final int DIVIDER1_Y = ROW1_Y + 28;

    /** 방 옵션 섹션(헤더 텍스트 + 공개 허용 체크박스 + 방 제목 입력란). */
    private static final int ROW3_Y  = DIVIDER1_Y + 28;
    private static final int ROW_H         = 22;
    private static final int VISIBLE_ROWS  = 3;

    /** 옵션 체크박스 스크롤 영역 — 지금은 3개지만 앞으로 늘어날 걸 대비해 한 화면에
     * {@link #VISIBLE_ROWS}개씩만 보여주고 마우스 휠로 넘긴다. 공개 허용 행과의
     * 간격도 체크박스끼리의 간격({@link #ROW_H})과 똑같이 맞춘다 — 예전엔 여기만
     * 26이라 공개 허용만 유독 떨어져 보였다. */
    private static final int LIST_Y        = ROW3_Y + ROW_H;

    private static final int DIVIDER_COLOR = 0xFF555555;
    /** 두 구분선 사이(방 옵션 섹션) 배경을 살짝 어둡게 — 기존 마크 멀티플레이/LAN
     * 화면의 목록 섹션처럼 가운데가 위아래보다 더 짙어 보이게 한다. 위젯들은 이미
     * super.render()에서 그려진 뒤라, 그 위에 반투명 검정을 덮어 톤만 죽이는 방식
     * (완전 불투명이면 체크박스/입력란 글자가 안 보이게 된다). */
    private static final int MIDDLE_SECTION_BG = 0x40000000;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT        = Component.translatable("instant-p2p.custom_room.title");
    private static final Component EDIT_TITLE_TEXT    = Component.translatable("instant-p2p.custom_room.edit_title");
    private static final Component GAME_MODE_TEXT     = Component.translatable("instant-p2p.custom_room.game_mode");
    private static final Component MAX_PLAYERS_TEXT   = Component.translatable("instant-p2p.custom_room.max_players", MAX_PLAYERS);
    private static final Component ALLOW_COMMANDS_TEXT = Component.translatable("instant-p2p.custom_room.allow_commands");
    private static final Component ALLOW_COMMANDS_TOOLTIP_TEXT = Component.translatable("instant-p2p.custom_room.allow_commands_tooltip");
    private static final Component ALLOW_BROADCAST_TEXT = Component.translatable("instant-p2p.custom_room.allow_broadcast");
    private static final Component ALLOW_BROADCAST_TOOLTIP_TEXT = Component.translatable("instant-p2p.custom_room.allow_broadcast_tooltip");
    private static final Component FORCE_RELAY_TEXT   = Component.translatable("instant-p2p.force_relay");
    private static final Component FORCE_RELAY_TOOLTIP_TEXT = Component.translatable("instant-p2p.force_relay_tooltip");
    private static final Component START_TEXT         = Component.translatable("instant-p2p.custom_room.start");
    private static final Component RESTART_TEXT       = Component.translatable("instant-p2p.custom_room.restart");
    private static final Component RESTART_WARNING_TEXT = Component.translatable("instant-p2p.custom_room.restart_warning");
    private static final Component RESTART_TOOLTIP_TEXT = Component.translatable("instant-p2p.custom_room.restart_tooltip");
    private static final Component PUBLIC_TEXT        = Component.translatable("instant-p2p.custom_room.public_allow");
    private static final Component PUBLIC_TOOLTIP_TEXT = Component.translatable("instant-p2p.custom_room.public_allow_tooltip");
    private static final Component TITLE_PLACEHOLDER_TEXT = Component.translatable("instant-p2p.custom_room.title_placeholder");
    private static final Component TITLE_APPLY_TEXT   = Component.translatable("instant-p2p.custom_room.apply_title");
    private static final Component BACK_TEXT          = Component.translatable("instant-p2p.custom_room.back");
    private static final Component ROOM_OPTIONS_TEXT  = Component.translatable("instant-p2p.custom_room.room_options");
    private static final Component CHANNEL_SETTINGS_TEXT = Component.translatable("instant-p2p.channel.settings");
    *///?} else {
    private static final Text TITLE_TEXT        = Text.translatable("instant-p2p.custom_room.title");
    private static final Text EDIT_TITLE_TEXT    = Text.translatable("instant-p2p.custom_room.edit_title");
    private static final Text GAME_MODE_TEXT     = Text.translatable("instant-p2p.custom_room.game_mode");
    private static final Text MAX_PLAYERS_TEXT   = Text.translatable("instant-p2p.custom_room.max_players", MAX_PLAYERS);
    private static final Text ALLOW_COMMANDS_TEXT = Text.translatable("instant-p2p.custom_room.allow_commands");
    private static final Text ALLOW_COMMANDS_TOOLTIP_TEXT = Text.translatable("instant-p2p.custom_room.allow_commands_tooltip");
    private static final Text ALLOW_BROADCAST_TEXT = Text.translatable("instant-p2p.custom_room.allow_broadcast");
    private static final Text ALLOW_BROADCAST_TOOLTIP_TEXT = Text.translatable("instant-p2p.custom_room.allow_broadcast_tooltip");
    private static final Text FORCE_RELAY_TEXT   = Text.translatable("instant-p2p.force_relay");
    private static final Text FORCE_RELAY_TOOLTIP_TEXT = Text.translatable("instant-p2p.force_relay_tooltip");
    private static final Text START_TEXT         = Text.translatable("instant-p2p.custom_room.start");
    private static final Text RESTART_TEXT       = Text.translatable("instant-p2p.custom_room.restart");
    private static final Text RESTART_WARNING_TEXT = Text.translatable("instant-p2p.custom_room.restart_warning");
    private static final Text RESTART_TOOLTIP_TEXT = Text.translatable("instant-p2p.custom_room.restart_tooltip");
    private static final Text PUBLIC_TEXT        = Text.translatable("instant-p2p.custom_room.public_allow");
    private static final Text PUBLIC_TOOLTIP_TEXT = Text.translatable("instant-p2p.custom_room.public_allow_tooltip");
    private static final Text TITLE_PLACEHOLDER_TEXT = Text.translatable("instant-p2p.custom_room.title_placeholder");
    private static final Text TITLE_APPLY_TEXT   = Text.translatable("instant-p2p.custom_room.apply_title");
    private static final Text BACK_TEXT          = Text.translatable("instant-p2p.custom_room.back");
    private static final Text ROOM_OPTIONS_TEXT  = Text.translatable("instant-p2p.custom_room.room_options");
    private static final Text CHANNEL_SETTINGS_TEXT = Text.translatable("instant-p2p.channel.settings");
    //?}
    private static final int INVALID_COLOR       = 0xFFFF5555;

    /** 이미 켜진 방을 다시 연 경우 — 그 방의 설정을 그대로 채워서 보여준다. 방 옵션
     * (게임모드/정원/공개 허용/제목/치트/관리 명령어/중계 강제)은 전부 위젯을 바꿔도
     * 그 자리에서 반영되지 않고, 통합 "적용" 버튼을 눌러야만 한꺼번에 반영된다
     * ({@link kfc.udp.client.KfcudpClient#applyRoomSettings}) — 체크박스 하나 누를
     * 때마다 곧장 방이 공개되거나 하는 일이 없도록. 새 초대 코드가 필요할 때만
     * 재생성 버튼. */
    private final boolean editingActiveRoom = KfcudpClient.isRoomActive();

    private final Screen parent;
    //? if >=26.1 {
    /*private GameType gameMode;
    *///?} else {
    private GameMode gameMode;
    //?}
    private int maxPlayers;
    private boolean allowCheats;
    private boolean publicRoom;
    /** 다른 방 옵션과 마찬가지로 체크박스 자체는 그냥 이 필드만 바꾸고, 실제
     * {@link kfc.udp.client.webrtc.P2PConfig#setRelayOnly}는 적용 버튼을 눌러야
     * (호스팅 전이면 즉시) 반영된다 — 예전엔 이 옵션만 체크하자마자 바로 나갔다. */
    private boolean forceRelay;
    /** forceRelay와 같은 방식 — 체크박스는 이 필드만 바꾸고, {@link kfc.udp.client.webrtc.P2PConfig#setAllowBroadcast}는
     * 적용 버튼을 눌러야(호스팅 전이면 즉시) 반영된다. */
    private boolean allowBroadcast;
    private int checkboxScrollIndex = 0;
    /** init()이 끝나기 전까진 위젯들의 초기값 세팅 자체가 "값이 바뀜" 콜백을 트리거해도
     * 무시한다 — 안 그러면 화면을 열기만 해도 적용 버튼이 매번 활성 상태로 뜬다. */
    private boolean initialized = false;

    /** 하단 섹션(재생성/취소 버튼) — RoomListScreen과 마찬가지로 this.height 기준
     * 아래에서부터 고정해 작은 화면에서도 항상 화면 안에 붙어 있게 한다. */
    private int buttonsY, divider2Y;

    //? if >=26.1 {
    /*@Nullable private EditBox maxPlayersField;
    @Nullable private EditBox titleField;
    @Nullable private Button    startButton;
    @Nullable private Button    settingsApplyButton;
    private final List<Checkbox> optionCheckboxes = new ArrayList<>();
    *///?} else {
    @Nullable private TextFieldWidget maxPlayersField;
    @Nullable private TextFieldWidget titleField;
    @Nullable private ButtonWidget    startButton;
    @Nullable private ButtonWidget    settingsApplyButton;
    private final List<CheckboxWidget> optionCheckboxes = new ArrayList<>();
    //?}

    public CustomRoomScreen(Screen parent) {
        super(KfcudpClient.isRoomActive() ? EDIT_TITLE_TEXT : TITLE_TEXT);
        this.parent = parent;
        this.forceRelay = kfc.udp.client.webrtc.P2PConfig.isRelayOnly();
        this.allowBroadcast = kfc.udp.client.webrtc.P2PConfig.isAllowBroadcast();
        if (this.editingActiveRoom) {
            // 이미 켜진 방 — 지금 실제로 적용돼 있는 값을 그대로 보여준다.
            this.gameMode = KfcudpClient.getActiveGameMode();
            this.maxPlayers = KfcudpClient.getActiveMaxPlayers();
            this.allowCheats = KfcudpClient.isActiveAllowCheats();
            this.publicRoom = KfcudpClient.isActivePublicRoom();
        } else {
            //? if >=26.1 {
            /*this.gameMode = GameType.ADVENTURE;
            *///?} else {
            this.gameMode = GameMode.ADVENTURE;
            //?}
            this.maxPlayers = 8;
            this.allowCheats = false;
            this.publicRoom = false;
        }
    }

    //? if >=26.1 {
    /*@Override
    protected void init() {
        int cx = this.width / 2;
        this.buttonsY = this.height - 26;
        this.divider2Y = this.buttonsY - 14;

        // 게임 모드 선택 — 호스팅 중이면 위 통합 "적용" 버튼을 눌러야 반영된다(새 초대
        // 코드 발급 없이 핫스왑은 가능하지만, 다른 방 옵션들과 마찬가지로 즉시 반영은
        // 아니다).
        this.addRenderableWidget(
                CycleButton.builder(GameType::getShortDisplayName, this.gameMode)
                        .withValues(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR)
                        .create(cx - 155, ROW1_Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> { this.gameMode = mode; this.refreshSettingsApplyButton(); })
        );

        // 최대 인원 입력 — 항상 꽉 찬 너비. 호스팅 중이면(editingActiveRoom) 방 제목과
        // 함께 위쪽의 통합 "적용" 버튼을 눌러야만 반영된다(validateMaxPlayers/
        // refreshSettingsApplyButton 참고).
        this.maxPlayersField = new EditBox(
                this.font, cx + 5, ROW1_Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setValue(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setResponder(this::validateMaxPlayers);
        this.addRenderableWidget(this.maxPlayersField);

        // 시작/재생성 버튼 — 호스팅 전(시작)엔 원래 자리(하단 좌측)를 그대로 쓴다.
        // 호스팅 중(재생성)에만 화면 제목과 같은 높이(TITLE_Y 계산식 참고)의 위쪽
        // 자리로 옮기고, 방 옵션들은 아래 통합 적용 버튼으로 넘긴다. 경고 표시는
        // 문구 대신 짧은 기호(⚠)만 라벨에 넣고 전체 설명은 툴팁으로 옮겨서 좁은
        // 폭에서도 안전하게 들어간다.
        Component startLabel = this.editingActiveRoom
                ? RESTART_TEXT.copy().append(" ").append(RESTART_WARNING_TEXT.copy().withStyle(ChatFormatting.GOLD))
                : START_TEXT;
        var startBuilder = Button.builder(startLabel, btn -> this.onStart());
        if (this.editingActiveRoom) {
            startBuilder.bounds((cx + 155) - APPLY_BUTTON_W, APPLY_BUTTON_Y, APPLY_BUTTON_W, APPLY_BUTTON_H);
            startBuilder.tooltip(Tooltip.create(RESTART_TOOLTIP_TEXT));
        } else {
            startBuilder.bounds(cx - 155, this.buttonsY, 150, 20);
        }
        this.startButton = startBuilder.build();
        this.addRenderableWidget(this.startButton);

        // 방 닫기 — 호스팅 중에만, 화면 제목("방 설정 변경") 바로 아래 가운데. 🚫는 빨간색(1.21~ 게임 안에서 표시 확인).
        if (this.editingActiveRoom) {
            this.addRenderableWidget(Button.builder(
                            Component.translatable("instant-p2p.pause.close_room").append(" ")
                                    .append(Component.literal("🚫").withStyle(ChatFormatting.RED)),
                            b -> KfcudpClient.closeRoomFromMenu())
                    .bounds(cx - CLOSE_ROOM_W / 2, APPLY_BUTTON_Y + APPLY_BUTTON_H + 2, CLOSE_ROOM_W, APPLY_BUTTON_H)
                    .build());
        }

        // 채널 설정 버튼 — 위 재생성/시작 버튼(우측 상단 자리)과 중심 기준 좌우반전된 위치(좌측 상단).
        // RoomListScreen과 똑같은 좌표라 호스트/접속자 화면 어디서든 같은 자리에 뜬다. 채널 값은 그 화면(ChannelScreen)
        // 안에서만 보이고, 적용하면 공개 중인 방은 바로 다시 공지된다 — 이 화면의 적용 버튼과는 무관하다.
        this.addRenderableWidget(Button.builder(CHANNEL_SETTINGS_TEXT, b ->
                        Objects.requireNonNull(this.minecraft).setScreenAndShow(new ChannelScreen(this)))
                .bounds(cx - 155, RoomListScreen.CHANNEL_Y, APPLY_BUTTON_W, 20).build());

        // 방 옵션 전체 통합 적용 버튼 — 게임모드/정원/공개 허용/제목/치트/관리 명령어를
        // 한 번에 반영한다(onApplyButtonClicked/refreshSettingsApplyButton 참고).
        // 하단 섹션에 고정, 돌아가기 버튼과 좌우로 나란히 배치한다.
        if (this.editingActiveRoom) {
            this.settingsApplyButton = Button.builder(TITLE_APPLY_TEXT, btn -> this.onApplyButtonClicked())
                    .bounds(cx - 155, this.buttonsY, 150, 20)
                    .build();
            this.settingsApplyButton.active = false; // 초기 값 = 지금 설정과 같아 바뀐 게 없음
            this.addRenderableWidget(this.settingsApplyButton);
        }

        this.addRenderableWidget(
                Button.builder(BACK_TEXT, btn -> this.onClose())
                        .bounds(cx + 5, this.buttonsY, 150, 20)
                        .build()
        );

        // 방 옵션 — 호스팅 중이면(editingActiveRoom) 아래 체크박스는 위 통합 적용
        // 버튼을 눌러야 반영된다. 공개 허용 — 체크하면 오른쪽에 방 제목 입력란이
        // 나타난다. 비워두면 방 열 때 무작위 제목을 대신 쓴다(onStart 참고). 정원과
        // 마찬가지로 제목도 키 입력마다 그대로 적용하면 그때마다 공개 목록을 내렸다
        // 올리게 돼(applyRoomSettings의 publicChanged) 트래픽 낭비가 크므로, 위 통합
        // "적용" 버튼을 눌러야만 반영된다. 너비는 고정 — 왼쪽은 공개 허용 체크박스
        // 라벨(영어 기준)과 안 겹치는 선에서, 오른쪽은 게임모드/정원 행과 맞춘다.
        int titleFieldX = cx - 60;
        int titleFieldW = (cx + 155) - titleFieldX;
        this.titleField = new EditBox(this.font, titleFieldX, ROW3_Y, titleFieldW, 20, TITLE_PLACEHOLDER_TEXT);
        this.titleField.setMaxLength(RoomListScreen.MAX_TITLE_LENGTH);
        this.titleField.setHint(TITLE_PLACEHOLDER_TEXT);
        if (this.editingActiveRoom) {
            String activeTitle = KfcudpClient.getActiveTitle();
            this.titleField.setValue(activeTitle != null ? activeTitle : "");
        }
        this.titleField.visible = this.publicRoom;
        this.addRenderableWidget(this.titleField);
        this.titleField.setResponder(text -> {
            // 방 목록 칸에 들어가는 폭까지만 — 넘치면 잘라 다시 넣는다(다시 불린 응답은 폭 안이라 여기서 끝).
            if (this.font.width(text) > RoomListScreen.TITLE_TEXT_W) {
                this.titleField.setValue(this.font.plainSubstrByWidth(text, RoomListScreen.TITLE_TEXT_W));
                return;
            }
            this.refreshSettingsApplyButton();
        });

        // Checkbox.Builder.build()의 tooltip()은 라벨이 한 줄을 넘칠 때만(overflowsRowLimit) 실제로
        // setTooltip을 불러준다(바이트코드로 확인) — 우리 라벨은 전부 한 줄이라 빌더로는 절대 안 뜬다.
        // build() 뒤에 Checkbox.setTooltip을 직접 불러야 라벨 길이와 무관하게 항상 붙는다.
        Checkbox publicCheckbox = Checkbox.builder(PUBLIC_TEXT, this.font)
                .pos(cx - 155, ROW3_Y)
                .selected(this.publicRoom)
                .onValueChange((cb, value) -> {
                    this.publicRoom = value;
                    this.titleField.visible = value;
                    this.refreshSettingsApplyButton();
                })
                .build();
        publicCheckbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(PUBLIC_TOOLTIP_TEXT));
        this.addRenderableWidget(publicCheckbox);

        // 옵션 체크박스 — 치트/중계 강제/방송 허용. kick/ban/whitelist는 체크박스가 아니라 방장과 /op 받은
        // 사람만 쓴다(P2PBanManager.requireAdminOrHost). 목록이 늘어날 걸 대비해 스크롤 영역에
        // 담는다(repositionCheckboxes/mouseScrolled 참고).
        this.optionCheckboxes.clear();
        Checkbox allowCommandsCheckbox = Checkbox.builder(ALLOW_COMMANDS_TEXT, this.font)
                .pos(cx - 155, LIST_Y)
                .selected(this.allowCheats)
                .onValueChange((cb, value) -> { this.allowCheats = value; this.refreshSettingsApplyButton(); })
                .build();
        allowCommandsCheckbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(ALLOW_COMMANDS_TOOLTIP_TEXT));
        this.optionCheckboxes.add(allowCommandsCheckbox);
        Checkbox forceRelayCheckbox = Checkbox.builder(FORCE_RELAY_TEXT, this.font)
                .pos(cx - 155, LIST_Y)
                .selected(this.forceRelay)
                .onValueChange((cb, value) -> {
                    this.forceRelay = value;
                    if (this.editingActiveRoom) {
                        this.refreshSettingsApplyButton();
                    } else {
                        kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value);
                    }
                })
                .build();
        forceRelayCheckbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(FORCE_RELAY_TOOLTIP_TEXT));
        this.optionCheckboxes.add(forceRelayCheckbox);
        Checkbox allowBroadcastCheckbox = Checkbox.builder(ALLOW_BROADCAST_TEXT, this.font)
                .pos(cx - 155, LIST_Y)
                .selected(this.allowBroadcast)
                .onValueChange((cb, value) -> {
                    this.allowBroadcast = value;
                    if (this.editingActiveRoom) {
                        this.refreshSettingsApplyButton();
                    } else {
                        kfc.udp.client.webrtc.P2PConfig.setAllowBroadcast(value);
                    }
                })
                .build();
        allowBroadcastCheckbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(ALLOW_BROADCAST_TOOLTIP_TEXT));
        this.optionCheckboxes.add(allowBroadcastCheckbox);
        for (Checkbox cb : this.optionCheckboxes) this.addRenderableWidget(cb);
        this.checkboxScrollIndex = 0;
        this.repositionCheckboxes();

        this.validateMaxPlayers(this.maxPlayersField.getValue());
        this.initialized = true;
    }
    *///?}
    //? if >=1.21.11 <26.1 {
    /*@Override
    protected void init() {
        int cx = this.width / 2;
        this.buttonsY = this.height - 26;
        this.divider2Y = this.buttonsY - 14;

        // 게임 모드 선택 — 호스팅 중이면 위 통합 "적용" 버튼을 눌러야 반영된다(새 초대
        // 코드 발급 없이 핫스왑은 가능하지만, 다른 방 옵션들과 마찬가지로 즉시 반영은
        // 아니다).
        this.addDrawableChild(
                CyclingButtonWidget.builder(GameMode::getSimpleTranslatableName, this.gameMode)
                        .values(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)
                        .build(cx - 155, ROW1_Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> { this.gameMode = mode; this.refreshSettingsApplyButton(); })
        );

        // 최대 인원 입력 — 항상 꽉 찬 너비. 호스팅 중이면(editingActiveRoom) 방 제목과
        // 함께 위쪽의 통합 "적용" 버튼을 눌러야만 반영된다(validateMaxPlayers/
        // refreshSettingsApplyButton 참고).
        this.maxPlayersField = new TextFieldWidget(
                this.textRenderer, cx + 5, ROW1_Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setText(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setChangedListener(this::validateMaxPlayers);
        this.addDrawableChild(this.maxPlayersField);

        // 시작/재생성 버튼 — 호스팅 전(시작)엔 원래 자리(하단 좌측)를 그대로 쓴다.
        // 호스팅 중(재생성)에만 화면 제목과 같은 높이(TITLE_Y 계산식 참고)의 위쪽
        // 자리로 옮기고, 방 옵션들은 아래 통합 적용 버튼으로 넘긴다. 경고 표시는
        // 문구 대신 짧은 기호(⚠)만 라벨에 넣고 전체 설명은 툴팁으로 옮겨서 좁은
        // 폭에서도 안전하게 들어간다.
        Text startLabel = this.editingActiveRoom
                ? RESTART_TEXT.copy().append(" ").append(RESTART_WARNING_TEXT.copy().formatted(Formatting.GOLD))
                : START_TEXT;
        var startBuilder = ButtonWidget.builder(startLabel, btn -> this.onStart());
        if (this.editingActiveRoom) {
            startBuilder.dimensions((cx + 155) - APPLY_BUTTON_W, APPLY_BUTTON_Y, APPLY_BUTTON_W, APPLY_BUTTON_H);
            startBuilder.tooltip(Tooltip.of(RESTART_TOOLTIP_TEXT));
        } else {
            startBuilder.dimensions(cx - 155, this.buttonsY, 150, 20);
        }
        this.startButton = startBuilder.build();
        this.addDrawableChild(this.startButton);

        // 방 닫기 — 호스팅 중에만, 화면 제목("방 설정 변경") 바로 아래 가운데. 🚫는 빨간색(1.21~ 게임 안에서 표시 확인).
        if (this.editingActiveRoom) {
            this.addDrawableChild(ButtonWidget.builder(
                            Text.translatable("instant-p2p.pause.close_room").append(" ")
                                    .append(Text.literal("🚫").formatted(Formatting.RED)),
                            b -> KfcudpClient.closeRoomFromMenu())
                    .dimensions(cx - CLOSE_ROOM_W / 2, APPLY_BUTTON_Y + APPLY_BUTTON_H + 2, CLOSE_ROOM_W, APPLY_BUTTON_H)
                    .build());
        }

        // 채널 설정 버튼 — 위 재생성/시작 버튼(우측 상단 자리)과 중심 기준 좌우반전된 위치(좌측 상단).
        // RoomListScreen과 똑같은 좌표라 호스트/접속자 화면 어디서든 같은 자리에 뜬다. 채널 값은 그 화면(ChannelScreen)
        // 안에서만 보이고, 적용하면 공개 중인 방은 바로 다시 공지된다 — 이 화면의 적용 버튼과는 무관하다.
        this.addDrawableChild(ButtonWidget.builder(CHANNEL_SETTINGS_TEXT, b ->
                        Objects.requireNonNull(this.client).setScreen(new ChannelScreen(this)))
                .dimensions(cx - 155, RoomListScreen.CHANNEL_Y, APPLY_BUTTON_W, 20).build());

        // 방 옵션 전체 통합 적용 버튼 — 게임모드/정원/공개 허용/제목/치트/관리 명령어를
        // 한 번에 반영한다(onApplyButtonClicked/refreshSettingsApplyButton 참고).
        // 하단 섹션에 고정, 돌아가기 버튼과 좌우로 나란히 배치한다.
        if (this.editingActiveRoom) {
            this.settingsApplyButton = ButtonWidget.builder(TITLE_APPLY_TEXT, btn -> this.onApplyButtonClicked())
                    .dimensions(cx - 155, this.buttonsY, 150, 20)
                    .build();
            this.settingsApplyButton.active = false; // 초기 값 = 지금 설정과 같아 바뀐 게 없음
            this.addDrawableChild(this.settingsApplyButton);
        }

        this.addDrawableChild(
                ButtonWidget.builder(BACK_TEXT, btn -> this.close())
                        .dimensions(cx + 5, this.buttonsY, 150, 20)
                        .build()
        );

        // 방 옵션 — 호스팅 중이면(editingActiveRoom) 아래 체크박스는 위 통합 적용
        // 버튼을 눌러야 반영된다. 공개 허용 — 체크하면 오른쪽에 방 제목 입력란이
        // 나타난다. 비워두면 방 열 때 무작위 제목을 대신 쓴다(onStart 참고). 정원과
        // 마찬가지로 제목도 키 입력마다 그대로 적용하면 그때마다 공개 목록을 내렸다
        // 올리게 돼(applyRoomSettings의 publicChanged) 트래픽 낭비가 크므로, 위 통합
        // "적용" 버튼을 눌러야만 반영된다. 너비는 고정 — 왼쪽은 공개 허용 체크박스
        // 라벨(영어 기준)과 안 겹치는 선에서, 오른쪽은 게임모드/정원 행과 맞춘다.
        int titleFieldX = cx - 60;
        int titleFieldW = (cx + 155) - titleFieldX;
        this.titleField = new TextFieldWidget(this.textRenderer, titleFieldX, ROW3_Y, titleFieldW, 20, TITLE_PLACEHOLDER_TEXT);
        this.titleField.setMaxLength(RoomListScreen.MAX_TITLE_LENGTH);
        this.titleField.setPlaceholder(TITLE_PLACEHOLDER_TEXT);
        if (this.editingActiveRoom) {
            String activeTitle = KfcudpClient.getActiveTitle();
            this.titleField.setText(activeTitle != null ? activeTitle : "");
        }
        this.titleField.visible = this.publicRoom;
        this.addDrawableChild(this.titleField);
        this.titleField.setChangedListener(text -> {
            // 방 목록 칸에 들어가는 폭까지만 — 넘치면 잘라 다시 넣는다(다시 불린 응답은 폭 안이라 여기서 끝).
            if (this.textRenderer.getWidth(text) > RoomListScreen.TITLE_TEXT_W) {
                this.titleField.setText(this.textRenderer.trimToWidth(text, RoomListScreen.TITLE_TEXT_W));
                return;
            }
            this.refreshSettingsApplyButton();
        });

        this.addDrawableChild(
                CheckboxWidget.builder(PUBLIC_TEXT, this.textRenderer)
                        .pos(cx - 155, ROW3_Y)
                        .checked(this.publicRoom)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(PUBLIC_TOOLTIP_TEXT))
                        .callback((cb, value) -> {
                            this.publicRoom = value;
                            this.titleField.visible = value;
                            this.refreshSettingsApplyButton();
                        })
                        .build()
        );

        // 옵션 체크박스 — 치트/중계 강제/방송 허용. kick/ban/whitelist는 체크박스가 아니라 방장과 /op 받은
        // 사람만 쓴다(P2PBanManager.requireAdminOrHost). 목록이 늘어날 걸 대비해 스크롤 영역에
        // 담는다(repositionCheckboxes/mouseScrolled 참고).
        this.optionCheckboxes.clear();
        this.optionCheckboxes.add(
                CheckboxWidget.builder(ALLOW_COMMANDS_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.allowCheats)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(ALLOW_COMMANDS_TOOLTIP_TEXT))
                        .callback((cb, value) -> { this.allowCheats = value; this.refreshSettingsApplyButton(); })
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.forceRelay)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(FORCE_RELAY_TOOLTIP_TEXT))
                        .callback((cb, value) -> {
                            this.forceRelay = value;
                            if (this.editingActiveRoom) {
                                this.refreshSettingsApplyButton();
                            } else {
                                kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value);
                            }
                        })
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(ALLOW_BROADCAST_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.allowBroadcast)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(ALLOW_BROADCAST_TOOLTIP_TEXT))
                        .callback((cb, value) -> {
                            this.allowBroadcast = value;
                            if (this.editingActiveRoom) {
                                this.refreshSettingsApplyButton();
                            } else {
                                kfc.udp.client.webrtc.P2PConfig.setAllowBroadcast(value);
                            }
                        })
                        .build()
        );
        for (CheckboxWidget cb : this.optionCheckboxes) this.addDrawableChild(cb);
        this.checkboxScrollIndex = 0;
        this.repositionCheckboxes();

        this.validateMaxPlayers(this.maxPlayersField.getText());
        this.initialized = true;
    }
    *///?}
    //? if <1.21.11 {
    @Override
    protected void init() {
        int cx = this.width / 2;
        this.buttonsY = this.height - 26;
        this.divider2Y = this.buttonsY - 14;

        // 게임 모드 선택 — 호스팅 중이면 위 통합 "적용" 버튼을 눌러야 반영된다(새 초대
        // 코드 발급 없이 핫스왑은 가능하지만, 다른 방 옵션들과 마찬가지로 즉시 반영은
        // 아니다).
        this.addDrawableChild(
                CyclingButtonWidget.builder(GameMode::getSimpleTranslatableName)
                        .values(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)
                        .initially(this.gameMode)
                        .build(cx - 155, ROW1_Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> { this.gameMode = mode; this.refreshSettingsApplyButton(); })
        );

        // 최대 인원 입력 — 항상 꽉 찬 너비. 호스팅 중이면(editingActiveRoom) 방 제목과
        // 함께 위쪽의 통합 "적용" 버튼을 눌러야만 반영된다(validateMaxPlayers/
        // refreshSettingsApplyButton 참고).
        this.maxPlayersField = new TextFieldWidget(
                this.textRenderer, cx + 5, ROW1_Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setText(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setChangedListener(this::validateMaxPlayers);
        this.addDrawableChild(this.maxPlayersField);

        // 시작/재생성 버튼 — 호스팅 전(시작)엔 원래 자리(하단 좌측)를 그대로 쓴다.
        // 호스팅 중(재생성)에만 화면 제목과 같은 높이(TITLE_Y 계산식 참고)의 위쪽
        // 자리로 옮기고, 방 옵션들은 아래 통합 적용 버튼으로 넘긴다. 경고 표시는
        // 문구 대신 짧은 기호(⚠)만 라벨에 넣고 전체 설명은 툴팁으로 옮겨서 좁은
        // 폭에서도 안전하게 들어간다.
        Text startLabel = this.editingActiveRoom
                ? RESTART_TEXT.copy().append(" ").append(RESTART_WARNING_TEXT.copy().formatted(Formatting.GOLD))
                : START_TEXT;
        var startBuilder = ButtonWidget.builder(startLabel, btn -> this.onStart());
        if (this.editingActiveRoom) {
            startBuilder.dimensions((cx + 155) - APPLY_BUTTON_W, APPLY_BUTTON_Y, APPLY_BUTTON_W, APPLY_BUTTON_H);
            startBuilder.tooltip(Tooltip.of(RESTART_TOOLTIP_TEXT));
        } else {
            startBuilder.dimensions(cx - 155, this.buttonsY, 150, 20);
        }
        this.startButton = startBuilder.build();
        this.addDrawableChild(this.startButton);

        // 방 닫기 — 호스팅 중에만, 화면 제목("방 설정 변경") 바로 아래 가운데. 🚫는 빨간색(1.21~ 게임 안에서 표시 확인).
        if (this.editingActiveRoom) {
            this.addDrawableChild(ButtonWidget.builder(
                            Text.translatable("instant-p2p.pause.close_room").append(" ")
                                    .append(Text.literal("🚫").formatted(Formatting.RED)),
                            b -> KfcudpClient.closeRoomFromMenu())
                    .dimensions(cx - CLOSE_ROOM_W / 2, APPLY_BUTTON_Y + APPLY_BUTTON_H + 2, CLOSE_ROOM_W, APPLY_BUTTON_H)
                    .build());
        }

        // 채널 설정 버튼 — 위 재생성/시작 버튼(우측 상단 자리)과 중심 기준 좌우반전된 위치(좌측 상단).
        // RoomListScreen과 똑같은 좌표라 호스트/접속자 화면 어디서든 같은 자리에 뜬다. 채널 값은 그 화면(ChannelScreen)
        // 안에서만 보이고, 적용하면 공개 중인 방은 바로 다시 공지된다 — 이 화면의 적용 버튼과는 무관하다.
        this.addDrawableChild(ButtonWidget.builder(CHANNEL_SETTINGS_TEXT, b ->
                        Objects.requireNonNull(this.client).setScreen(new ChannelScreen(this)))
                .dimensions(cx - 155, RoomListScreen.CHANNEL_Y, APPLY_BUTTON_W, 20).build());

        // 방 옵션 전체 통합 적용 버튼 — 게임모드/정원/공개 허용/제목/치트/관리 명령어를
        // 한 번에 반영한다(onApplyButtonClicked/refreshSettingsApplyButton 참고).
        // 하단 섹션에 고정, 돌아가기 버튼과 좌우로 나란히 배치한다.
        if (this.editingActiveRoom) {
            this.settingsApplyButton = ButtonWidget.builder(TITLE_APPLY_TEXT, btn -> this.onApplyButtonClicked())
                    .dimensions(cx - 155, this.buttonsY, 150, 20)
                    .build();
            this.settingsApplyButton.active = false; // 초기 값 = 지금 설정과 같아 바뀐 게 없음
            this.addDrawableChild(this.settingsApplyButton);
        }

        this.addDrawableChild(
                ButtonWidget.builder(BACK_TEXT, btn -> this.close())
                        .dimensions(cx + 5, this.buttonsY, 150, 20)
                        .build()
        );

        // 방 옵션 — 호스팅 중이면(editingActiveRoom) 아래 체크박스는 위 통합 적용
        // 버튼을 눌러야 반영된다. 공개 허용 — 체크하면 오른쪽에 방 제목 입력란이
        // 나타난다. 비워두면 방 열 때 무작위 제목을 대신 쓴다(onStart 참고). 정원과
        // 마찬가지로 제목도 키 입력마다 그대로 적용하면 그때마다 공개 목록을 내렸다
        // 올리게 돼(applyRoomSettings의 publicChanged) 트래픽 낭비가 크므로, 위 통합
        // "적용" 버튼을 눌러야만 반영된다. 너비는 고정 — 왼쪽은 공개 허용 체크박스
        // 라벨(영어 기준)과 안 겹치는 선에서, 오른쪽은 게임모드/정원 행과 맞춘다.
        int titleFieldX = cx - 60;
        int titleFieldW = (cx + 155) - titleFieldX;
        this.titleField = new TextFieldWidget(this.textRenderer, titleFieldX, ROW3_Y, titleFieldW, 20, TITLE_PLACEHOLDER_TEXT);
        this.titleField.setMaxLength(RoomListScreen.MAX_TITLE_LENGTH);
        this.titleField.setPlaceholder(TITLE_PLACEHOLDER_TEXT);
        if (this.editingActiveRoom) {
            String activeTitle = KfcudpClient.getActiveTitle();
            this.titleField.setText(activeTitle != null ? activeTitle : "");
        }
        this.titleField.visible = this.publicRoom;
        this.addDrawableChild(this.titleField);
        this.titleField.setChangedListener(text -> {
            // 방 목록 칸에 들어가는 폭까지만 — 넘치면 잘라 다시 넣는다(다시 불린 응답은 폭 안이라 여기서 끝).
            if (this.textRenderer.getWidth(text) > RoomListScreen.TITLE_TEXT_W) {
                this.titleField.setText(this.textRenderer.trimToWidth(text, RoomListScreen.TITLE_TEXT_W));
                return;
            }
            this.refreshSettingsApplyButton();
        });

        this.addDrawableChild(
                CheckboxWidget.builder(PUBLIC_TEXT, this.textRenderer)
                        .pos(cx - 155, ROW3_Y)
                        .checked(this.publicRoom)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(PUBLIC_TOOLTIP_TEXT))
                        .callback((cb, value) -> {
                            this.publicRoom = value;
                            this.titleField.visible = value;
                            this.refreshSettingsApplyButton();
                        })
                        .build()
        );

        // 옵션 체크박스 — 치트/중계 강제/방송 허용. kick/ban/whitelist는 체크박스가 아니라 방장과 /op 받은
        // 사람만 쓴다(P2PBanManager.requireAdminOrHost). 목록이 늘어날 걸 대비해 스크롤 영역에
        // 담는다(repositionCheckboxes/mouseScrolled 참고).
        this.optionCheckboxes.clear();
        this.optionCheckboxes.add(
                CheckboxWidget.builder(ALLOW_COMMANDS_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.allowCheats)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(ALLOW_COMMANDS_TOOLTIP_TEXT))
                        .callback((cb, value) -> { this.allowCheats = value; this.refreshSettingsApplyButton(); })
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.forceRelay)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(FORCE_RELAY_TOOLTIP_TEXT))
                        .callback((cb, value) -> {
                            this.forceRelay = value;
                            if (this.editingActiveRoom) {
                                this.refreshSettingsApplyButton();
                            } else {
                                kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value);
                            }
                        })
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(ALLOW_BROADCAST_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.allowBroadcast)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(ALLOW_BROADCAST_TOOLTIP_TEXT))
                        .callback((cb, value) -> {
                            this.allowBroadcast = value;
                            if (this.editingActiveRoom) {
                                this.refreshSettingsApplyButton();
                            } else {
                                kfc.udp.client.webrtc.P2PConfig.setAllowBroadcast(value);
                            }
                        })
                        .build()
        );
        for (CheckboxWidget cb : this.optionCheckboxes) this.addDrawableChild(cb);
        this.checkboxScrollIndex = 0;
        this.repositionCheckboxes();

        this.validateMaxPlayers(this.maxPlayersField.getText());
        this.initialized = true;
    }
    //?}

    /** scrollIndex에 맞춰 보이는 {@link #VISIBLE_ROWS}개만 Y를 배치하고 나머지는 숨긴다. */
    private void repositionCheckboxes() {
        for (int i = 0; i < this.optionCheckboxes.size(); i++) {
            var cb = this.optionCheckboxes.get(i);
            int rel = i - this.checkboxScrollIndex;
            boolean shown = rel >= 0 && rel < VISIBLE_ROWS;
            cb.visible = shown;
            if (shown) cb.setY(LIST_Y + rel * ROW_H);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 커서가 중간 섹션(방 옵션) 안에 있을 때만 체크박스 목록을 스크롤한다 —
        // 그 밖(상단 게임모드/정원, 하단 재생성/취소)에서 휠을 굴렸는데 안 보이는
        // 체크박스가 넘어가버리는 건 직관적이지 않다.
        if (mouseY < DIVIDER1_Y || mouseY > this.divider2Y)
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int maxIndex = Math.max(0, this.optionCheckboxes.size() - VISIBLE_ROWS);
        if (maxIndex <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int newIndex = this.checkboxScrollIndex - (int) Math.signum(verticalAmount);
        newIndex = Math.max(0, Math.min(maxIndex, newIndex));
        if (newIndex != this.checkboxScrollIndex) {
            this.checkboxScrollIndex = newIndex;
            this.repositionCheckboxes();
        }
        return true;
    }

    private void validateMaxPlayers(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            if (v >= MIN_PLAYERS && v <= MAX_PLAYERS) {
                this.maxPlayers = v;
                if (this.maxPlayersField != null)
                    //? if >=26.1 {
                    /*this.maxPlayersField.setTextColor(0xFFFFFFFF);
                    *///?} else {
                    this.maxPlayersField.setEditableColor(0xFFFFFFFF);
                    //?}
                if (this.startButton != null)
                    this.startButton.active = true;
                this.refreshSettingsApplyButton();
            } else {
                if (this.maxPlayersField != null)
                    //? if >=26.1 {
                    /*this.maxPlayersField.setTextColor(INVALID_COLOR);
                    *///?} else {
                    this.maxPlayersField.setEditableColor(INVALID_COLOR);
                    //?}
                if (this.startButton != null)
                    this.startButton.active = false;
                if (this.settingsApplyButton != null)
                    this.settingsApplyButton.active = false;
            }
        } catch (NumberFormatException e) {
            if (this.maxPlayersField != null)
                //? if >=26.1 {
                /*this.maxPlayersField.setTextColor(INVALID_COLOR);
                *///?} else {
                this.maxPlayersField.setEditableColor(INVALID_COLOR);
                //?}
            if (this.startButton != null)
                this.startButton.active = false;
            if (this.settingsApplyButton != null)
                this.settingsApplyButton.active = false;
        }
    }

    /** Start 버튼 — 이미 켜진 방이어도 항상 방 재시작(새 초대 코드 발급). */
    private void onStart() {
        //? if >=26.1 {
        /*assert this.minecraft != null;
        String title = this.publicRoom ? this.titleField.getValue().trim() : null;
        KfcudpClient.startCustomRoom(this.minecraft, this.gameMode, this.maxPlayers, this.allowCheats,
                this.publicRoom, title);
        *///?} else {
        assert this.client != null;
        String title = this.publicRoom ? this.titleField.getText().trim() : null;
        KfcudpClient.startCustomRoom(this.client, this.gameMode, this.maxPlayers, this.allowCheats,
                this.publicRoom, title);
        //?}
    }

    /** 통합 "적용" 버튼의 클릭 핸들러 — 이미 켜진 방(editingActiveRoom)일 때만 존재하는
     * 버튼이다. 게임모드/정원/공개 허용/제목/치트/관리 명령어/중계 강제를 한꺼번에
     * 반영한다. 체크박스나 입력란을 아무리 만져도 여기 눌러야만 실제로 나간다 —
     * 그래야 예를 들어 "공개 허용"을 체크하는 순간 곧장 방이 공개되는 일 없이,
     * 제목까지 다 정한 뒤 한 번에 공개할 수 있다. */
    private void onApplyButtonClicked() {
        if (!this.editingActiveRoom) return;
        //? if >=26.1 {
        /*assert this.minecraft != null;
        String title = this.publicRoom ? this.titleField.getValue().trim() : null;
        boolean announced = KfcudpClient.applyRoomSettings(this.minecraft, this.gameMode, this.maxPlayers,
                this.allowCheats, this.publicRoom, title, this.allowBroadcast);
        // 제목을 비워둔 채 공개하면 applyRoomSettings가 "Room - 코드"를 대신 지어
        // 붙인다 — 화면 입력란은 여전히 빈 채로 남으므로, 그대로 두면 입력란(빈 값)과
        // 실제 활성 제목(자동 생성값)이 영원히 안 맞아 적용 버튼이 계속 활성 상태로
        // 고정돼 버린다. 실제로 적용된 값으로 입력란을 맞춰준다.
        if (this.publicRoom) this.titleField.setValue(KfcudpClient.getActiveTitle());
        // 중계 강제도 다른 옵션과 마찬가지로 여기서만 실제로 반영한다. 이미 직결로
        // 붙어있는 접속자를 강제로 끊어 재접속시키는 건 하지 않는다 — 그 접속자는
        // 다음에 새로 접속할 때부터 이 값을 적용받는다.
        // 중계 강제만 여기서 반영한다(방송 허용은 applyRoomSettings 안에서 방 설정과 함께 처리 —
        // 방송 허용 여부도 방송 필터에 실리는 공개 정보라 다른 방 옵션들과 같은 재공지·변경 로그
        // 경로를 타야 한다).
        kfc.udp.client.webrtc.P2PConfig.setRelayOnly(this.forceRelay);
        *///?} else {
        assert this.client != null;
        String title = this.publicRoom ? this.titleField.getText().trim() : null;
        boolean announced = KfcudpClient.applyRoomSettings(this.client, this.gameMode, this.maxPlayers,
                this.allowCheats, this.publicRoom, title, this.allowBroadcast);
        // 제목을 비워둔 채 공개하면 applyRoomSettings가 "Room - 코드"를 대신 지어
        // 붙인다 — 화면 입력란은 여전히 빈 채로 남으므로, 그대로 두면 입력란(빈 값)과
        // 실제 활성 제목(자동 생성값)이 영원히 안 맞아 적용 버튼이 계속 활성 상태로
        // 고정돼 버린다. 실제로 적용된 값으로 입력란을 맞춰준다.
        if (this.publicRoom) this.titleField.setText(KfcudpClient.getActiveTitle());
        // 중계 강제도 다른 옵션과 마찬가지로 여기서만 실제로 반영한다. 이미 직결로
        // 붙어있는 접속자를 강제로 끊어 재접속시키는 건 하지 않는다 — 그 접속자는
        // 다음에 새로 접속할 때부터 이 값을 적용받는다.
        // 중계 강제만 여기서 반영한다(방송 허용은 applyRoomSettings 안에서 방 설정과 함께 처리 —
        // 방송 허용 여부도 방송 필터에 실리는 공개 정보라 다른 방 옵션들과 같은 재공지·변경 로그
        // 경로를 타야 한다).
        kfc.udp.client.webrtc.P2PConfig.setRelayOnly(this.forceRelay);
        //?}
        // 적용 결과는 채팅으로 알리고, 초대코드 재생성처럼 곧장 게임 화면으로 돌아간다.
        // 방 전원에게 바뀐 설정을 알렸으면(applyRoomSettings) 방장에게 "적용 완료"를 또 띄우지 않는다.
        //? if >=26.1 {
        /*if (!announced && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.translatable("instant-p2p.msg.settings_applied"));
        }
        this.minecraft.setScreenAndShow(null);
        this.minecraft.mouseHandler.grabMouse();
        *///?} else {
        if (!announced && this.client.player != null) {
            this.client.player.sendMessage(Text.translatable("instant-p2p.msg.settings_applied"), false);
        }
        this.client.setScreen(null);
        this.client.mouse.lockCursor();
        //?}
    }

    /** 방 옵션 중 하나라도 지금 활성값과 다르면 통합 적용 버튼을 활성화한다. */
    private void refreshSettingsApplyButton() {
        if (this.settingsApplyButton == null) return;
        boolean changed = this.gameMode != KfcudpClient.getActiveGameMode()
                || this.maxPlayers != KfcudpClient.getActiveMaxPlayers()
                || this.allowCheats != KfcudpClient.isActiveAllowCheats()
                || this.publicRoom != KfcudpClient.isActivePublicRoom()
                || this.forceRelay != kfc.udp.client.webrtc.P2PConfig.isRelayOnly()
                || this.allowBroadcast != kfc.udp.client.webrtc.P2PConfig.isAllowBroadcast();
        //? if >=26.1 {
        /*if (!changed && this.publicRoom) {
            changed = !this.titleField.getValue().trim().equals(KfcudpClient.getActiveTitle());
        }
        *///?} else {
        if (!changed && this.publicRoom) {
            changed = !this.titleField.getText().trim().equals(KfcudpClient.getActiveTitle());
        }
        //?}
        this.settingsApplyButton.active = changed;
    }

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;

        context.centeredText(this.font, this.title, cx, TITLE_Y, 0xFFFFFFFF);

        context.centeredText(this.font, GAME_MODE_TEXT,  cx - 80, ROW1_Y - 12, 0xFFA0A0A0);
        context.centeredText(this.font, MAX_PLAYERS_TEXT, cx + 80, ROW1_Y - 12, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.text(this.font, ROOM_OPTIONS_TEXT, cx - 155, ROW3_Y - 12, 0xFFA0A0A0);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
    }
    *///?} else {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, TITLE_Y, 0xFFFFFFFF);

        context.drawCenteredTextWithShadow(this.textRenderer, GAME_MODE_TEXT,  cx - 80, ROW1_Y - 12, 0xFFA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, MAX_PLAYERS_TEXT, cx + 80, ROW1_Y - 12, 0xFFA0A0A0);
        context.fill(0, DIVIDER1_Y, this.width, DIVIDER1_Y + 1, DIVIDER_COLOR);
        context.fill(0, DIVIDER1_Y + 1, this.width, this.divider2Y, MIDDLE_SECTION_BG);
        context.drawTextWithShadow(this.textRenderer, ROOM_OPTIONS_TEXT, cx - 155, ROW3_Y - 12, 0xFFA0A0A0);
        context.fill(0, this.divider2Y, this.width, this.divider2Y + 1, DIVIDER_COLOR);
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
