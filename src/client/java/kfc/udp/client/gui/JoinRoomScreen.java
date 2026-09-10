package kfc.udp.client.gui;

import java.util.Objects;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
//?}

public class JoinRoomScreen extends Screen {

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT      = Component.translatable("instant-p2p.join_room.title");
    private static final Component CODE_LABEL_TEXT = Component.translatable("instant-p2p.join_room.code_label");
    private static final Component JOIN_TEXT       = Component.translatable("instant-p2p.join_room.join");
    *///?} else {
    private static final Text TITLE_TEXT      = Text.translatable("instant-p2p.join_room.title");
    private static final Text CODE_LABEL_TEXT = Text.translatable("instant-p2p.join_room.code_label");
    private static final Text JOIN_TEXT       = Text.translatable("instant-p2p.join_room.join");
    //?}

    private final Screen parent;
    //? if >=26.1 {
    /*private EditBox codeField;
    private Button joinButton;
    *///?} else {
    private TextFieldWidget codeField;
    private ButtonWidget joinButton;
    //?}

    public JoinRoomScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    //? if >=26.1 {
    /*@Override
    protected void init() {
        int cx = this.width / 2;

        this.codeField = new EditBox(
                this.font, cx - 100, this.height / 2 - 10, 200, 20, CODE_LABEL_TEXT);
        this.codeField.setMaxLength(20);
        this.codeField.setHint(Component.translatable("instant-p2p.join_room.code_placeholder").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        this.codeField.setResponder(text -> this.joinButton.active = !text.trim().isEmpty());
        this.addRenderableWidget(this.codeField);

        this.joinButton = Button.builder(JOIN_TEXT, btn -> this.onJoin())
                .bounds(cx - 100, this.height / 2 + 15, 95, 20)
                .build();
        this.joinButton.active = false;
        this.addRenderableWidget(this.joinButton);

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, btn -> this.onClose())
                        .bounds(cx + 5, this.height / 2 + 15, 95, 20)
                        .build()
        );

        this.setInitialFocus(this.codeField);
    }
    *///?} else {
    @Override
    protected void init() {
        int cx = this.width / 2;

        this.codeField = new TextFieldWidget(
                this.textRenderer, cx - 100, this.height / 2 - 10, 200, 20, CODE_LABEL_TEXT);
        this.codeField.setMaxLength(20);
        this.codeField.setPlaceholder(Text.translatable("instant-p2p.join_room.code_placeholder").formatted(net.minecraft.util.Formatting.DARK_GRAY));
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
    //?}

    //? if >=26.1 {
    /*private void onJoin() {
        String code = this.codeField.getValue().trim();
        if (code.isEmpty()) return;

        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parseString(address);
        ServerData serverInfo = new ServerData(Component.translatable("instant-p2p.join_room.server_name").getString(), address, ServerData.Type.OTHER);

        ConnectScreen.startConnecting(this.parent, Objects.requireNonNull(this.minecraft), serverAddress, serverInfo, false, null);
    }
    *///?} else {
    private void onJoin() {
        String code = this.codeField.getText().trim();
        if (code.isEmpty()) return;

        String address = "webrtc." + code;
        ServerAddress serverAddress = ServerAddress.parse(address);
        ServerInfo serverInfo = new ServerInfo(Text.translatable("instant-p2p.join_room.server_name").getString(), address, ServerInfo.ServerType.OTHER);

        ConnectScreen.connect(this.parent, Objects.requireNonNull(this.client), serverAddress, serverInfo, false, null);
    }
    //?}

    //? if >=26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        if (input.key() == 257 && this.joinButton.active) { // Enter
            this.onJoin();
            return true;
        }
        return super.keyPressed(input);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == 257 && this.joinButton.active) { // Enter
            this.onJoin();
            return true;
        }
        return super.keyPressed(input);
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && this.joinButton.active) { // Enter
            this.onJoin();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        context.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
        context.text(this.font, CODE_LABEL_TEXT,
                this.width / 2 - 100, this.height / 2 - 22, 0xFFA0A0A0);
    }
    *///?} else {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, CODE_LABEL_TEXT,
                this.width / 2 - 100, this.height / 2 - 22, 0xFFA0A0A0);
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
