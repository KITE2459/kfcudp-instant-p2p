package kfc.udp.client.gui;

import kfc.udp.client.KfcudpClient;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CustomRoomScreen extends Screen {

    private static final int MIN_PLAYERS         = 2;
    private static final int MAX_PLAYERS         = 100;

    private static final int TITLE_Y = 30;
    private static final int ROW1_Y  = 80;
    private static final int ROW2_Y  = 110;
    /** 공개 허용 체크박스 + (체크 시) 방 제목 입력란 — 스크롤 영역 밖에 고정. */
    private static final int ROW3_Y  = 136;

    /** 옵션 체크박스 스크롤 영역 — 지금은 3개지만 앞으로 늘어날 걸 대비해 한 화면에
     * {@link #VISIBLE_ROWS}개씩만 보여주고 마우스 휠로 넘긴다. */
    private static final int LIST_Y        = 162;
    private static final int ROW_H         = 22;
    private static final int VISIBLE_ROWS  = 3;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT        = Component.translatable("instant-p2p.custom_room.title");
    private static final Component GAME_MODE_TEXT     = Component.translatable("instant-p2p.custom_room.game_mode");
    private static final Component MAX_PLAYERS_TEXT   = Component.translatable("instant-p2p.custom_room.max_players", MAX_PLAYERS);
    private static final Component ALLOW_COMMANDS_TEXT = Component.translatable("instant-p2p.custom_room.allow_commands");
    private static final Component MANAGE_COMMANDS_TEXT = Component.translatable("instant-p2p.custom_room.manage_commands");
    private static final Component FORCE_RELAY_TEXT   = Component.translatable("instant-p2p.force_relay");
    private static final Component START_TEXT         = Component.translatable("instant-p2p.custom_room.start");
    private static final Component PUBLIC_TEXT        = Component.translatable("instant-p2p.custom_room.public_allow");
    private static final Component TITLE_PLACEHOLDER_TEXT = Component.translatable("instant-p2p.custom_room.title_placeholder");
    *///?} else {
    private static final Text TITLE_TEXT        = Text.translatable("instant-p2p.custom_room.title");
    private static final Text GAME_MODE_TEXT     = Text.translatable("instant-p2p.custom_room.game_mode");
    private static final Text MAX_PLAYERS_TEXT   = Text.translatable("instant-p2p.custom_room.max_players", MAX_PLAYERS);
    private static final Text ALLOW_COMMANDS_TEXT = Text.translatable("instant-p2p.custom_room.allow_commands");
    private static final Text MANAGE_COMMANDS_TEXT = Text.translatable("instant-p2p.custom_room.manage_commands");
    private static final Text FORCE_RELAY_TEXT   = Text.translatable("instant-p2p.force_relay");
    private static final Text START_TEXT         = Text.translatable("instant-p2p.custom_room.start");
    private static final Text PUBLIC_TEXT        = Text.translatable("instant-p2p.custom_room.public_allow");
    private static final Text TITLE_PLACEHOLDER_TEXT = Text.translatable("instant-p2p.custom_room.title_placeholder");
    //?}
    private static final int INVALID_COLOR       = 0xFFFF5555;

    private final Screen parent;
    //? if >=26.1 {
    /*private GameType gameMode    = GameType.ADVENTURE;
    *///?} else {
    private GameMode gameMode    = GameMode.ADVENTURE;
    //?}
    private int maxPlayers       = 8;
    private boolean allowCheats  = false;
    private boolean manageCommands = false;
    private boolean publicRoom   = false;
    private int checkboxScrollIndex = 0;

    //? if >=26.1 {
    /*@Nullable private EditBox maxPlayersField;
    @Nullable private EditBox titleField;
    @Nullable private Button    startButton;
    private final List<Checkbox> optionCheckboxes = new ArrayList<>();
    *///?} else {
    @Nullable private TextFieldWidget maxPlayersField;
    @Nullable private TextFieldWidget titleField;
    @Nullable private ButtonWidget    startButton;
    private final List<CheckboxWidget> optionCheckboxes = new ArrayList<>();
    //?}

    public CustomRoomScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    //? if >=26.1 {
    /*@Override
    protected void init() {
        int cx = this.width / 2;

        // 게임 모드 선택
        this.addRenderableWidget(
                CycleButton.builder(GameType::getShortDisplayName, this.gameMode)
                        .withValues(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR)
                        .create(cx - 155, ROW1_Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> this.gameMode = mode)
        );

        // 최대 인원 입력
        this.maxPlayersField = new EditBox(
                this.font, cx + 5, ROW1_Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setValue(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setResponder(this::validateMaxPlayers);
        this.addRenderableWidget(this.maxPlayersField);

        // Start 버튼
        this.startButton = Button.builder(START_TEXT, btn -> this.onStart())
                .bounds(cx - 155, ROW2_Y, 150, 20)
                .build();
        this.addRenderableWidget(this.startButton);

        // Cancel 버튼
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, btn -> this.onClose())
                        .bounds(cx + 5, ROW2_Y, 150, 20)
                        .build()
        );

        // 공개 허용 — 체크하면 오른쪽에 방 제목 입력란이 나타난다. 비워두면
        // 방 열 때 무작위 제목을 대신 쓴다(onStart 참고).
        this.titleField = new EditBox(this.font, cx - 155 + 130, ROW3_Y, 180, 20, TITLE_PLACEHOLDER_TEXT);
        this.titleField.setMaxLength(32);
        this.titleField.setHint(TITLE_PLACEHOLDER_TEXT);
        this.titleField.visible = this.publicRoom;
        this.addRenderableWidget(this.titleField);

        this.addRenderableWidget(
                Checkbox.builder(PUBLIC_TEXT, this.font)
                        .pos(cx - 155, ROW3_Y)
                        .selected(this.publicRoom)
                        .onValueChange((cb, value) -> {
                            this.publicRoom = value;
                            this.titleField.visible = value;
                        })
                        .build()
        );

        // 옵션 체크박스 — 치트/관리 명령어/중계 강제. 치트와 관리 명령어는 서로
        // 완전히 독립(P2PBanManager 참고). 목록이 늘어날 걸 대비해 스크롤 영역에
        // 담는다(repositionCheckboxes/mouseScrolled 참고).
        this.optionCheckboxes.clear();
        this.optionCheckboxes.add(
                Checkbox.builder(ALLOW_COMMANDS_TEXT, this.font)
                        .pos(cx - 155, LIST_Y)
                        .selected(this.allowCheats)
                        .onValueChange((cb, value) -> this.allowCheats = value)
                        .build()
        );
        this.optionCheckboxes.add(
                Checkbox.builder(MANAGE_COMMANDS_TEXT, this.font)
                        .pos(cx - 155, LIST_Y)
                        .selected(this.manageCommands)
                        .onValueChange((cb, value) -> this.manageCommands = value)
                        .build()
        );
        this.optionCheckboxes.add(
                Checkbox.builder(FORCE_RELAY_TEXT, this.font)
                        .pos(cx - 155, LIST_Y)
                        .selected(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                        .onValueChange((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                        .build()
        );
        for (Checkbox cb : this.optionCheckboxes) this.addRenderableWidget(cb);
        this.checkboxScrollIndex = 0;
        this.repositionCheckboxes();

        this.validateMaxPlayers(this.maxPlayersField.getValue());
    }
    *///?}
    //? if >=1.21.11 <26.1 {
    /*@Override
    protected void init() {
        int cx = this.width / 2;

        // 게임 모드 선택
        this.addDrawableChild(
                CyclingButtonWidget.builder(GameMode::getSimpleTranslatableName, this.gameMode)
                        .values(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)
                        .build(cx - 155, ROW1_Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> this.gameMode = mode)
        );

        // 최대 인원 입력
        this.maxPlayersField = new TextFieldWidget(
                this.textRenderer, cx + 5, ROW1_Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setText(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setChangedListener(this::validateMaxPlayers);
        this.addDrawableChild(this.maxPlayersField);

        // Start 버튼
        this.startButton = ButtonWidget.builder(START_TEXT, btn -> this.onStart())
                .dimensions(cx - 155, ROW2_Y, 150, 20)
                .build();
        this.addDrawableChild(this.startButton);

        // Cancel 버튼
        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, btn -> this.close())
                        .dimensions(cx + 5, ROW2_Y, 150, 20)
                        .build()
        );

        // 공개 허용 — 체크하면 오른쪽에 방 제목 입력란이 나타난다. 비워두면
        // 방 열 때 무작위 제목을 대신 쓴다(onStart 참고).
        this.titleField = new TextFieldWidget(this.textRenderer, cx - 155 + 130, ROW3_Y, 180, 20, TITLE_PLACEHOLDER_TEXT);
        this.titleField.setMaxLength(32);
        this.titleField.setPlaceholder(TITLE_PLACEHOLDER_TEXT);
        this.titleField.visible = this.publicRoom;
        this.addDrawableChild(this.titleField);

        this.addDrawableChild(
                CheckboxWidget.builder(PUBLIC_TEXT, this.textRenderer)
                        .pos(cx - 155, ROW3_Y)
                        .checked(this.publicRoom)
                        .callback((cb, value) -> {
                            this.publicRoom = value;
                            this.titleField.visible = value;
                        })
                        .build()
        );

        // 옵션 체크박스 — 치트/관리 명령어/중계 강제. 치트와 관리 명령어는 서로
        // 완전히 독립(P2PBanManager 참고). 목록이 늘어날 걸 대비해 스크롤 영역에
        // 담는다(repositionCheckboxes/mouseScrolled 참고).
        this.optionCheckboxes.clear();
        this.optionCheckboxes.add(
                CheckboxWidget.builder(ALLOW_COMMANDS_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.allowCheats)
                        .callback((cb, value) -> this.allowCheats = value)
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(MANAGE_COMMANDS_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.manageCommands)
                        .callback((cb, value) -> this.manageCommands = value)
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                        .callback((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                        .build()
        );
        for (CheckboxWidget cb : this.optionCheckboxes) this.addDrawableChild(cb);
        this.checkboxScrollIndex = 0;
        this.repositionCheckboxes();

        this.validateMaxPlayers(this.maxPlayersField.getText());
    }
    *///?}
    //? if <1.21.11 {
    @Override
    protected void init() {
        int cx = this.width / 2;

        // 게임 모드 선택
        this.addDrawableChild(
                CyclingButtonWidget.builder(GameMode::getSimpleTranslatableName)
                        .values(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)
                        .initially(this.gameMode)
                        .build(cx - 155, ROW1_Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> this.gameMode = mode)
        );

        // 최대 인원 입력
        this.maxPlayersField = new TextFieldWidget(
                this.textRenderer, cx + 5, ROW1_Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setText(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setChangedListener(this::validateMaxPlayers);
        this.addDrawableChild(this.maxPlayersField);

        // Start 버튼
        this.startButton = ButtonWidget.builder(START_TEXT, btn -> this.onStart())
                .dimensions(cx - 155, ROW2_Y, 150, 20)
                .build();
        this.addDrawableChild(this.startButton);

        // Cancel 버튼
        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, btn -> this.close())
                        .dimensions(cx + 5, ROW2_Y, 150, 20)
                        .build()
        );

        // 공개 허용 — 체크하면 오른쪽에 방 제목 입력란이 나타난다. 비워두면
        // 방 열 때 무작위 제목을 대신 쓴다(onStart 참고).
        this.titleField = new TextFieldWidget(this.textRenderer, cx - 155 + 130, ROW3_Y, 180, 20, TITLE_PLACEHOLDER_TEXT);
        this.titleField.setMaxLength(32);
        this.titleField.setPlaceholder(TITLE_PLACEHOLDER_TEXT);
        this.titleField.visible = this.publicRoom;
        this.addDrawableChild(this.titleField);

        this.addDrawableChild(
                CheckboxWidget.builder(PUBLIC_TEXT, this.textRenderer)
                        .pos(cx - 155, ROW3_Y)
                        .checked(this.publicRoom)
                        .callback((cb, value) -> {
                            this.publicRoom = value;
                            this.titleField.visible = value;
                        })
                        .build()
        );

        // 옵션 체크박스 — 치트/관리 명령어/중계 강제. 치트와 관리 명령어는 서로
        // 완전히 독립(P2PBanManager 참고). 목록이 늘어날 걸 대비해 스크롤 영역에
        // 담는다(repositionCheckboxes/mouseScrolled 참고).
        this.optionCheckboxes.clear();
        this.optionCheckboxes.add(
                CheckboxWidget.builder(ALLOW_COMMANDS_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.allowCheats)
                        .callback((cb, value) -> this.allowCheats = value)
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(MANAGE_COMMANDS_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(this.manageCommands)
                        .callback((cb, value) -> this.manageCommands = value)
                        .build()
        );
        this.optionCheckboxes.add(
                CheckboxWidget.builder(FORCE_RELAY_TEXT, this.textRenderer)
                        .pos(cx - 155, LIST_Y)
                        .checked(kfc.udp.client.webrtc.P2PConfig.isRelayOnly())
                        .callback((cb, value) -> kfc.udp.client.webrtc.P2PConfig.setRelayOnly(value))
                        .build()
        );
        for (CheckboxWidget cb : this.optionCheckboxes) this.addDrawableChild(cb);
        this.checkboxScrollIndex = 0;
        this.repositionCheckboxes();

        this.validateMaxPlayers(this.maxPlayersField.getText());
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
            } else {
                if (this.maxPlayersField != null)
                    //? if >=26.1 {
                    /*this.maxPlayersField.setTextColor(INVALID_COLOR);
                    *///?} else {
                    this.maxPlayersField.setEditableColor(INVALID_COLOR);
                    //?}
                if (this.startButton != null)
                    this.startButton.active = false;
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
        }
    }

    private void onStart() {
        //? if >=26.1 {
        /*assert this.minecraft != null;
        String title = this.publicRoom ? this.titleField.getValue().trim() : null;
        KfcudpClient.startCustomRoom(this.minecraft, this.gameMode, this.maxPlayers, this.allowCheats, this.manageCommands,
                this.publicRoom, title);
        *///?} else {
        assert this.client != null;
        String title = this.publicRoom ? this.titleField.getText().trim() : null;
        KfcudpClient.startCustomRoom(this.client, this.gameMode, this.maxPlayers, this.allowCheats, this.manageCommands,
                this.publicRoom, title);
        //?}
    }

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;

        context.centeredText(this.font, this.title, cx, TITLE_Y, 0xFFFFFFFF);

        context.centeredText(this.font, GAME_MODE_TEXT,  cx - 80, ROW1_Y - 12, 0xFFA0A0A0);
        context.centeredText(this.font, MAX_PLAYERS_TEXT, cx + 80, ROW1_Y - 12, 0xFFA0A0A0);
    }
    *///?} else {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, TITLE_Y, 0xFFFFFFFF);

        context.drawCenteredTextWithShadow(this.textRenderer, GAME_MODE_TEXT,  cx - 80, ROW1_Y - 12, 0xFFA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, MAX_PLAYERS_TEXT, cx + 80, ROW1_Y - 12, 0xFFA0A0A0);
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
