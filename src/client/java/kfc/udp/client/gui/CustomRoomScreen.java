package kfc.udp.client.gui;

import kfc.udp.client.KfcudpClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class CustomRoomScreen extends Screen {

    private static final Text TITLE_TEXT        = Text.translatable("kfcudp.custom_room.title");
    private static final Text GAME_MODE_TEXT     = Text.translatable("kfcudp.custom_room.game_mode");
    private static final Text MAX_PLAYERS_TEXT   = Text.translatable("kfcudp.custom_room.max_players");
    private static final Text ALLOW_COMMANDS_TEXT = Text.translatable("kfcudp.custom_room.allow_commands");
    private static final Text START_TEXT         = Text.translatable("kfcudp.custom_room.start");
    private static final int INVALID_COLOR       = 0xFF5555;
    private static final int MIN_PLAYERS         = 2;
    private static final int MAX_PLAYERS         = 20;

    private final Screen parent;
    private GameMode gameMode    = GameMode.ADVENTURE;
    private int maxPlayers       = 8;
    private boolean allowCheats  = false;

    @Nullable private TextFieldWidget maxPlayersField;
    @Nullable private ButtonWidget    startButton;

    public CustomRoomScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int row1Y = 100;
        int row2Y = 130;

        // 게임 모드 선택
        this.addDrawableChild(
                CyclingButtonWidget.builder(GameMode::getSimpleTranslatableName)
                        .values(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)
                        .initially(this.gameMode)
                        .build(cx - 155, row1Y, 150, 20, GAME_MODE_TEXT,
                                (btn, mode) -> this.gameMode = mode)
        );

        // 최대 인원 입력
        this.maxPlayersField = new TextFieldWidget(
                this.textRenderer, cx + 5, row1Y, 150, 20, MAX_PLAYERS_TEXT);
        this.maxPlayersField.setText(String.valueOf(this.maxPlayers));
        this.maxPlayersField.setChangedListener(this::validateMaxPlayers);
        this.addDrawableChild(this.maxPlayersField);

        // Allow Commands 토글 (LAN 서버와 동일한 방식)
        this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(this.allowCheats)
                        .build(cx - 155, row2Y, 310, 20, ALLOW_COMMANDS_TEXT,
                                (btn, value) -> this.allowCheats = value)
        );

        // Start 버튼
        this.startButton = ButtonWidget.builder(START_TEXT, btn -> this.onStart())
                .dimensions(cx - 155, this.height - 28, 150, 20)
                .build();
        this.addDrawableChild(this.startButton);

        // Cancel 버튼
        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, btn -> this.close())
                        .dimensions(cx + 5, this.height - 28, 150, 20)
                        .build()
        );

        this.validateMaxPlayers(this.maxPlayersField.getText());
    }

    private void validateMaxPlayers(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            if (v >= MIN_PLAYERS && v <= MAX_PLAYERS) {
                this.maxPlayers = v;
                if (this.maxPlayersField != null)
                    this.maxPlayersField.setEditableColor(0xFFFFFF);
                if (this.startButton != null)
                    this.startButton.active = true;
            } else {
                if (this.maxPlayersField != null)
                    this.maxPlayersField.setEditableColor(INVALID_COLOR);
                if (this.startButton != null)
                    this.startButton.active = false;
            }
        } catch (NumberFormatException e) {
            if (this.maxPlayersField != null)
                this.maxPlayersField.setEditableColor(INVALID_COLOR);
            if (this.startButton != null)
                this.startButton.active = false;
        }
    }

    private void onStart() {
        assert this.client != null;
        KfcudpClient.startCustomRoom(this.client, this.gameMode, this.maxPlayers, this.allowCheats);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int cx = this.width / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 50, 0xFFFFFF);

        context.drawCenteredTextWithShadow(this.textRenderer, GAME_MODE_TEXT,  cx - 80, 88, 0xA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, MAX_PLAYERS_TEXT, cx + 80, 88, 0xA0A0A0);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(MIN_PLAYERS + " ~ " + MAX_PLAYERS).formatted(Formatting.DARK_GRAY),
                cx + 80, 152, 0xFFFFFF);
    }

    @Override
    public void close() {
        Objects.requireNonNull(this.client).setScreen(this.parent);
    }
}