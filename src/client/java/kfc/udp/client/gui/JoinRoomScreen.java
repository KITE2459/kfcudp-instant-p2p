package kfc.udp.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.Objects;

public class JoinRoomScreen extends Screen {

    private static final Text TITLE_TEXT      = Text.translatable("kfcudp.join_room.title");
    private static final Text CODE_LABEL_TEXT = Text.translatable("kfcudp.join_room.code_label");
    private static final Text JOIN_TEXT       = Text.translatable("kfcudp.join_room.join");

    private final Screen parent;
    private TextFieldWidget codeField;
    private ButtonWidget joinButton;

    public JoinRoomScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        this.codeField = new TextFieldWidget(
                this.textRenderer, cx - 100, this.height / 2 - 10, 200, 20, CODE_LABEL_TEXT);
        this.codeField.setMaxLength(20);
        this.codeField.setPlaceholder(Text.translatable("kfcudp.join_room.code_placeholder").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        this.codeField.setChangedListener(text -> this.joinButton.active = !text.trim().isEmpty());
        this.addDrawableChild(this.codeField);

        this.joinButton = ButtonWidget.builder(JOIN_TEXT, btn -> this.onJoin())
                .dimensions(cx - 100, this.height / 2 + 15, 95, 20)
                .build();
        this.joinButton.active = false;
        this.addDrawableChild(this.joinButton);

        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, btn -> this.close())
                        .dimensions(cx + 5, this.height / 2 + 15, 95, 20)
                        .build()
        );

        this.setInitialFocus(this.codeField);
    }

    private void onJoin() {
        String code = this.codeField.getText().trim();
        if (code.isEmpty()) return;

        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parse(address);
        ServerInfo serverInfo = new ServerInfo(Text.translatable("kfcudp.join_room.server_name").getString(), address, ServerInfo.ServerType.OTHER);

        ConnectScreen.connect(this.parent, Objects.requireNonNull(this.client), serverAddress, serverInfo, false, null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && this.joinButton.active) { // Enter
            this.onJoin();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, CODE_LABEL_TEXT,
                this.width / 2 - 100, this.height / 2 - 22, 0xA0A0A0);
    }

    @Override
    public void close() {
        Objects.requireNonNull(this.client).setScreen(this.parent);
    }
}