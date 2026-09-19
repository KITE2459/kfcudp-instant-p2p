package kfc.udp.client.gui;

import kfc.udp.client.KfcudpClient;
import kfc.udp.client.webrtc.P2PConfig;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?}

import java.util.List;

/**
 * 채널 설정 — 방 목록(접속자)과 방 설정(방장) 어디서 열어도 같은 화면. 채널은 쉼표로 여러 개("normal,kite2459,loggamja,minedapple")를
 * 적고, 아래에 칸으로 나뉘어 보인다. or면 채널 하나하나가 각각 채널이고, and면 전부를 묶은 채널 하나로 친다 — 방장이든
 * 접속자든 실제 채널이 하나라도 겹쳐야 서로 보인다(P2PConfig.effectiveChannels/roomVisible).
 * <p>
 * 채널 값은 이 화면 안에서만 보인다 — 방 목록·방 설정 화면에는 "채널 설정" 버튼만 있어 방송 화면에 채널이 드러나지
 * 않는다(그래서 예전의 "채널 가리기" 옵션은 없앴다).
 * <p>
 * 적용을 누르면 저장하고, 공개 중인 방이 있으면 새 채널로 다시 공지한다(KfcudpClient.republishForChannelChange).
 */
public class ChannelScreen extends Screen {

    private static final int PANEL_W = 260;
    /** 위에서부터: 제목, 설명(2줄), 입력란, 채널 칸(2줄), 상한 안내, 규칙 버튼, 규칙 설명(2줄), 완료/취소. */
    private static final int DESC_Y = 32, FIELD_Y = 60, CHIPS_Y = 88, MAX_HINT_Y = 124, RULE_Y = 140, RULE_DESC_Y = 164, BUTTONS_Y = 190;
    private static final int LINE_H = 10;
    private static final int CHIP_H = 14, CHIP_PAD = 4, CHIP_GAP = 4;
    /** 채널 하나가 칸에 보이는 폭 한도(한글 11자·영문 15자쯤)와 전체 입력 길이 한도. */
    private static final int CHANNEL_MAX_W = 99, TEXT_MAX_LENGTH = 120;
    private static final int CHIP_BG = 0xFF303030, CHIP_BORDER = 0xFFA0A0A0;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT = Component.translatable("instant-p2p.channel.title");
    private static final Component DESC_TEXT = Component.translatable("instant-p2p.channel.desc");
    private static final Component RULE_TOOLTIP_TEXT = Component.translatable("instant-p2p.channel.rule.tooltip");
    private static final Component MAX_HINT_TEXT = Component.translatable("instant-p2p.channel.max_hint", P2PConfig.MAX_CHANNELS);
    *///?} else {
    private static final Text TITLE_TEXT = Text.translatable("instant-p2p.channel.title");
    private static final Text DESC_TEXT = Text.translatable("instant-p2p.channel.desc");
    private static final Text RULE_TOOLTIP_TEXT = Text.translatable("instant-p2p.channel.rule.tooltip");
    private static final Text MAX_HINT_TEXT = Text.translatable("instant-p2p.channel.max_hint", P2PConfig.MAX_CHANNELS);
    //?}

    private final Screen parent;
    private String text = P2PConfig.getChannel();
    private boolean and = P2PConfig.isChannelAnd();
    //? if >=26.1 {
    /*private EditBox field;
    private Button ruleButton;
    *///?} else {
    private TextFieldWidget field;
    private ButtonWidget ruleButton;
    //?}

    public ChannelScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    private int panelX() {
        return this.width / 2 - PANEL_W / 2;
    }

    private List<String> chips() {
        return P2PConfig.parseChannels(this.text);
    }

    private void apply() {
        P2PConfig.setChannels(this.text);
        P2PConfig.setChannelAnd(this.and);
        //? if >=26.1 {
        /*assert this.minecraft != null;
        KfcudpClient.republishForChannelChange(this.minecraft);
        *///?} else {
        assert this.client != null;
        KfcudpClient.republishForChannelChange(this.client);
        //?}
        if (this.parent instanceof RoomListScreen list) list.onChannelsChanged();
        this.closeScreen();
    }

    // 닫기 메서드 이름이 Yarn(close)과 Mojang(onClose)에서 다르다.
    private void closeScreen() {
        //? if >=26.1 {
        /*this.onClose();
        *///?} else {
        this.close();
        //?}
    }

    private void toggleRule() {
        this.and = !this.and;
        this.updateRuleLabel();
    }

    //? if >=26.1 {
    /*private void updateRuleLabel() {
        this.ruleButton.setMessage(Component.translatable(this.and ? "instant-p2p.channel.rule.and" : "instant-p2p.channel.rule.or"));
    }

    @Override
    protected void init() {
        int x = this.panelX();
        this.field = new EditBox(this.font, x, FIELD_Y, PANEL_W, 20, TITLE_TEXT);
        this.field.setMaxLength(TEXT_MAX_LENGTH);
        this.field.setValue(this.text);
        this.field.setHint(Component.literal("normal,kite2459,loggamja,minedapple").withStyle(ChatFormatting.DARK_GRAY));
        this.field.setResponder(t -> this.text = t);
        this.addRenderableWidget(this.field);

        this.ruleButton = Button.builder(Component.empty(), b -> this.toggleRule()).bounds(x, RULE_Y, PANEL_W, 20).build();
        this.ruleButton.setTooltip(Tooltip.create(RULE_TOOLTIP_TEXT));
        this.updateRuleLabel();
        this.addRenderableWidget(this.ruleButton);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.apply())
                .bounds(x, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(x + PANEL_W / 2 + 4, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
        this.setInitialFocus(this.field);
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        int x = this.panelX();
        context.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        this.drawWrapped(context, DESC_TEXT, x, DESC_Y, 0xFFA0A0A0);
        this.drawWrapped(context, Component.translatable(this.and ? "instant-p2p.channel.rule.desc.and" : "instant-p2p.channel.rule.desc.or"),
                x, RULE_DESC_Y, 0xFFA0A0A0);
        // 채널 칸 — 폭이 넘치면 다음 줄로.
        int cx = x, cy = CHIPS_Y;
        for (String chip : this.chips()) {
            String label = this.font.plainSubstrByWidth(chip, CHANNEL_MAX_W);
            int w = this.font.width(label) + 2 * CHIP_PAD;
            if (cx + w > x + PANEL_W && cx > x) { cx = x; cy += CHIP_H + CHIP_GAP; }
            context.fill(cx, cy, cx + w, cy + CHIP_H, CHIP_BG);
            context.outline(cx, cy, w, CHIP_H, CHIP_BORDER);
            context.text(this.font, label, cx + CHIP_PAD, cy + 3, 0xFFFFFFFF);
            cx += w + CHIP_GAP;
        }
        // 다섯 개째가 되는 순간부터 상한을 알린다 — 그 뒤로 더 적어도 무시된다.
        if (this.chips().size() >= P2PConfig.MAX_CHANNELS) {
            context.text(this.font, MAX_HINT_TEXT, x, MAX_HINT_Y, 0xFFFFFF55);
        }
    }

    // 패널 폭에 맞춰 줄바꿈해서 그린다 — 설명 문장이 언어에 따라 한 줄을 넘는다.
    private void drawWrapped(GuiGraphicsExtractor context, Component text, int x, int y, int color) {
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(text, PANEL_W)) {
            context.text(this.font, line, x, y, color);
            y += LINE_H;
        }
    }
    *///?} else {
    private void updateRuleLabel() {
        this.ruleButton.setMessage(Text.translatable(this.and ? "instant-p2p.channel.rule.and" : "instant-p2p.channel.rule.or"));
    }

    @Override
    protected void init() {
        int x = this.panelX();
        this.field = new TextFieldWidget(this.textRenderer, x, FIELD_Y, PANEL_W, 20, TITLE_TEXT);
        this.field.setMaxLength(TEXT_MAX_LENGTH);
        this.field.setText(this.text);
        this.field.setPlaceholder(Text.literal("normal,kite2459,loggamja,minedapple").formatted(Formatting.DARK_GRAY));
        this.field.setChangedListener(t -> this.text = t);
        this.addDrawableChild(this.field);

        this.ruleButton = ButtonWidget.builder(Text.empty(), b -> this.toggleRule()).dimensions(x, RULE_Y, PANEL_W, 20).build();
        this.ruleButton.setTooltip(Tooltip.of(RULE_TOOLTIP_TEXT));
        this.updateRuleLabel();
        this.addDrawableChild(this.ruleButton);

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> this.apply())
                .dimensions(x, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> this.close())
                .dimensions(x + PANEL_W / 2 + 4, BUTTONS_Y, PANEL_W / 2 - 4, 20).build());
        this.setInitialFocus(this.field);
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int x = this.panelX();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFFFF);
        this.drawWrapped(context, DESC_TEXT, x, DESC_Y, 0xFFA0A0A0);
        this.drawWrapped(context, Text.translatable(this.and ? "instant-p2p.channel.rule.desc.and" : "instant-p2p.channel.rule.desc.or"),
                x, RULE_DESC_Y, 0xFFA0A0A0);
        // 채널 칸 — 폭이 넘치면 다음 줄로.
        int cx = x, cy = CHIPS_Y;
        for (String chip : this.chips()) {
            String label = this.textRenderer.trimToWidth(chip, CHANNEL_MAX_W);
            int w = this.textRenderer.getWidth(label) + 2 * CHIP_PAD;
            if (cx + w > x + PANEL_W && cx > x) { cx = x; cy += CHIP_H + CHIP_GAP; }
            context.fill(cx, cy, cx + w, cy + CHIP_H, CHIP_BG);
            drawBox(context, cx, cy, w, CHIP_H, CHIP_BORDER);
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), cx + CHIP_PAD, cy + 3, 0xFFFFFFFF);
            cx += w + CHIP_GAP;
        }
        // 다섯 개째가 되는 순간부터 상한을 알린다 — 그 뒤로 더 적어도 무시된다.
        if (this.chips().size() >= P2PConfig.MAX_CHANNELS) {
            context.drawTextWithShadow(this.textRenderer, MAX_HINT_TEXT, x, MAX_HINT_Y, 0xFFFFFF55);
        }
    }

    // 패널 폭에 맞춰 줄바꿈해서 그린다 — 설명 문장이 언어에 따라 한 줄을 넘는다.
    private void drawWrapped(DrawContext context, Text text, int x, int y, int color) {
        for (net.minecraft.text.OrderedText line : this.textRenderer.wrapLines(text, PANEL_W)) {
            context.drawTextWithShadow(this.textRenderer, line, x, y, color);
            y += LINE_H;
        }
    }

    // 테두리 그리기 이름이 1.21.9에서 drawBorder → drawStrokedRectangle로 바뀌었다.
    private static void drawBox(DrawContext context, int x, int y, int w, int h, int color) {
        //? if >=1.21.9 {
        /*context.drawStrokedRectangle(x, y, w, h, color);
        *///?} else {
        context.drawBorder(x, y, w, h, color);
        //?}
    }
    //?}
}
