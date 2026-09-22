package kfc.udp.client.gui;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
//?}

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 화면을 완전히 바꾸는 대신, RoomListScreen의 "차단하시겠습니까?" 확인창(ConfirmPopup)과 똑같은
 * 스타일(어둡게 깔고 그 위에 테두리 있는 박스 하나)로 그려서 "화면이 전환됐다"는 느낌 없이
 * 팝업처럼 보이게 하는 범용 경고/확인 화면 — 커스텀 방 기능 진입 경고(빨강), 방송 비허용 방
 * 접속 확인(노랑)이 이 클래스 하나를 색만 바꿔 재사용한다. ConfirmPopup은 이미 열려 있는 화면
 * 위에 얹는 보조 클래스라 "아직 어떤 화면도 열기 전(버튼을 누른 시점)"에는 못 쓴다 — 그래서
 * 화면 자체이면서도 위젯을 하나도 안 쓰고 ConfirmPopup처럼 손으로 다 그리고 클릭·키 판정도
 * 직접 한다.
 * <p>
 * "다시 보지 않기"를 체크한 채로 계속하기를 눌러야만 {@code setDismissed}가 불린다 — 체크만
 * 하고 취소를 누르면(즉 실제로 그 액션을 하지 않았으면) 저장하지 않는다. 계속하기를 누르면
 * {@code onAccept} 실행 *전에* 먼저 부모 화면으로 돌아간다 — 예를 들어 방 접속은 그 시점의
 * "현재 화면"이 RoomListScreen이어야만 차단 유저 확인 팝업이 뜨는데(KfcudpClient.joinRoomByCode
 * 참고), 이 화면이 떠 있는 채로 접속을 시작하면 그 확인이 깨진다.
 */
public class SafetyWarningScreen extends Screen {

    private static final int PAD = 10, LINE_H = 12, GAP = 10;
    private static final int BTN_W = 100, BTN_H = 20, BTN_GAP = 8;
    // 바닐라 CheckboxWidget.getCheckboxSize()가 실제로 돌려주는 값(9+8=17)과 라벨 간격(getX()+size+4)을
    // 그대로 맞췄다 — 우리가 이미 게임에서 보던 체크박스와 같은 크기로 보이게.
    private static final int BOX_SIZE = 17, BOX_LABEL_GAP = 4;
    /** 라벨 텍스트(9px 높이)를 박스 안에서 세로로 가운데 맞추는 오프셋. */
    private static final int BOX_LABEL_Y_OFFSET = (BOX_SIZE - 9) / 2;
    private static final int DIM_COLOR = 0xC0000000;
    // 손으로 테두리·체크를 그려서는 CustomRoomScreen 등에서 이미 보던 진짜 바닐라 체크박스와
    // 눈에 띄게 달라 보였다(CheckboxWidget 정적 초기화 블록에서 확인한 실제 스프라이트 이름) —
    // 버튼(widget/button)처럼 이것도 RoomListScreen.drawSprite로 바닐라 텍스처를 그대로 쓴다.
    private static final String CHECKBOX_SPRITE = "widget/checkbox", CHECKBOX_SELECTED_SPRITE = "widget/checkbox_selected";
    // 26.3에서 키 번호 체계가 통째로 바뀌었다(ConfirmPopup 클래스 주석 참고) — 26.x는 바닐라 상수를 그대로 쓴다.
    //? if >=26.1 {
    /*private static final int KEY_ENTER = com.mojang.blaze3d.platform.InputConstants.KEY_RETURN,
            KEY_KP_ENTER = com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER,
            KEY_ESCAPE = com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
    *///?} else {
    private static final int KEY_ENTER = 257, KEY_KP_ENTER = 335, KEY_ESCAPE = 256;
    //?}

    private final Screen parent;
    private final Runnable onAccept;
    private final Consumer<Boolean> setDismissed;
    private final int accentColor;
    private final int bgColor;
    /** "§l"이 박혀 있는 원문 그대로("경고!") — 굵게는 이 §l 코드가 렌더 시점에 알아서 처리해 준다,
     * 코드에서 따로 스타일을 입힐 필요가 없다. 2배로 그려서 본문보다 눈에 띄게 한다(draw* 쪽 참고). */
    private final String heading;
    private final String[] lines;
    private final String checkboxLabel;
    private boolean dontShowAgain;

    // render()에서 매 프레임 다시 계산해 기록해 두는 클릭 판정 좌표 — 그리기 전이면 -1이라 안 맞는다.
    private int okX = -1, cancelX = -1, btnY = -1;
    private int checkboxX = -1, checkboxY = -1;

    /**
     * @param parent       취소 시(또는 계속하기 실행 직전) 돌아갈 화면
     * @param headingKey   굵고 2배 크게 그릴 첫 줄의 번역 키(예: "§l경고!")
     * @param messageKey   본문 번역 키 — "\n"으로 줄바꿈
     * @param accentColor  테두리·글자색(빨강/노랑 등)
     * @param bgColor      패널 배경색(accentColor와 어울리는 어두운 톤)
     * @param setDismissed "다시 보지 않기"를 체크한 채 계속하기를 눌렀을 때만 호출된다
     * @param onAccept     계속하기 실행 — parent로 먼저 돌아간 *다음에* 실행된다
     */
    public SafetyWarningScreen(Screen parent, String headingKey, String messageKey, int accentColor, int bgColor,
                                Consumer<Boolean> setDismissed, Runnable onAccept) {
        //? if >=26.1 {
        /*super(Component.translatable(headingKey));
        this.heading = Component.translatable(headingKey).getString();
        this.lines = Component.translatable(messageKey).getString().split("\n");
        this.checkboxLabel = Component.translatable("instant-p2p.safety_warning.dont_show_again").getString();
        *///?} else {
        super(Text.translatable(headingKey));
        this.heading = Text.translatable(headingKey).getString();
        this.lines = Text.translatable(messageKey).getString().split("\n");
        this.checkboxLabel = Text.translatable("instant-p2p.safety_warning.dont_show_again").getString();
        //?}
        this.parent = parent;
        this.accentColor = accentColor;
        this.bgColor = bgColor;
        this.setDismissed = setDismissed;
        this.onAccept = onAccept;
    }

    private void onAcceptClicked() {
        if (this.dontShowAgain) this.setDismissed.accept(true);
        this.onCancel(); // parent로 먼저 돌아간 뒤에(클래스 주석 참고)
        this.onAccept.run();
    }

    private void onCancel() {
        //? if >=26.1 {
        /*Objects.requireNonNull(this.minecraft).setScreenAndShow(this.parent);
        *///?} else {
        Objects.requireNonNull(this.client).setScreen(this.parent);
        //?}
    }

    private boolean hitButton(int bx, double mouseX, double mouseY) {
        return bx >= 0 && mouseX >= bx && mouseX < bx + BTN_W && mouseY >= this.btnY && mouseY < this.btnY + BTN_H;
    }

    private boolean hitCheckbox(double mouseX, double mouseY) {
        return this.checkboxX >= 0 && mouseX >= this.checkboxX && mouseX < this.checkboxX + BOX_SIZE
                && mouseY >= this.checkboxY && mouseY < this.checkboxY + BOX_SIZE;
    }

    private void handleClick(double mouseX, double mouseY) {
        if (this.hitCheckbox(mouseX, mouseY)) {
            this.dontShowAgain = !this.dontShowAgain;
            RoomListScreen.playClick();
        } else if (this.hitButton(this.okX, mouseX, mouseY)) {
            RoomListScreen.playClick();
            this.onAcceptClicked();
        } else if (this.hitButton(this.cancelX, mouseX, mouseY)) {
            RoomListScreen.playClick();
            this.onCancel();
        }
    }

    private boolean handleKey(int keyCode) {
        if (keyCode == KEY_ENTER || keyCode == KEY_KP_ENTER) { this.onAcceptClicked(); return true; }
        if (keyCode == KEY_ESCAPE) { this.onCancel(); return true; }
        return false;
    }

    // ---- 입력 (마우스·키 콜백 시그니처가 버전마다 달라 RoomListScreen/BlockedPlayersScreen과 같은 3분기) ----

    //? if >=26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        return this.handleKey(input.key()) || super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) this.handleClick(click.x(), click.y());
        return true;
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return this.handleKey(input.key()) || super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
        if (click.button() == 0) this.handleClick(click.x(), click.y());
        return true;
    }
    *///?}
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return this.handleKey(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) this.handleClick(mouseX, mouseY);
        return true;
    }
    //?}

    // ---- 렌더 — ConfirmPopup과 같은 스타일(전체 화면을 어둡게 깔고 그 위에 테두리 있는 박스 하나) ----

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
        Font font = this.font;
        int cx = this.width / 2;
        int contentW = Math.max(0, font.width(this.heading) * 2);
        for (String line : this.lines) contentW = Math.max(contentW, font.width(line));
        int checkboxRowW = BOX_SIZE + BOX_LABEL_GAP + font.width(this.checkboxLabel);
        contentW = Math.max(contentW, Math.max(checkboxRowW, 2 * BTN_W + BTN_GAP));
        int panelW = contentW + 2 * PAD;
        int panelH = PAD + LINE_H * 2 + GAP + this.lines.length * LINE_H + GAP + BOX_SIZE + GAP + BTN_H + PAD;
        int px = cx - panelW / 2, py = (this.height - panelH) / 2;

        context.fill(0, 0, this.width, this.height, DIM_COLOR);
        context.fill(px, py, px + panelW, py + panelH, this.accentColor);
        context.fill(px + 1, py + 1, px + panelW - 1, py + panelH - 1, this.bgColor);

        // 헤딩은 2배 크기로 — §l(굵게)은 문자열에 이미 박혀 있어 렌더러가 알아서 처리한다.
        int headingY = py + PAD;
        context.pose().pushMatrix();
        context.pose().scale(2f, 2f);
        context.centeredText(font, net.minecraft.network.chat.Component.literal(this.heading), cx / 2, headingY / 2, this.accentColor);
        context.pose().popMatrix();

        int y = headingY + LINE_H * 2 + GAP;
        for (String line : this.lines) {
            context.centeredText(font, net.minecraft.network.chat.Component.literal(line), cx, y, this.accentColor);
            y += LINE_H;
        }

        y += GAP;
        this.checkboxX = cx - checkboxRowW / 2;
        this.checkboxY = y;
        this.drawCheckbox(context, font, y);
        y += BOX_SIZE + GAP;

        this.btnY = py + panelH - PAD - BTN_H;
        this.okX = cx - BTN_GAP / 2 - BTN_W;
        this.cancelX = cx + BTN_GAP / 2;
        this.button(context, font, this.okX,
                net.minecraft.network.chat.Component.translatable("instant-p2p.safety_warning.continue").getString(), mouseX, mouseY);
        this.button(context, font, this.cancelX,
                net.minecraft.network.chat.Component.translatable("gui.cancel").getString(), mouseX, mouseY);
    }

    private void drawCheckbox(GuiGraphicsExtractor context, Font font, int y) {
        RoomListScreen.drawSprite(context, this.dontShowAgain ? CHECKBOX_SELECTED_SPRITE : CHECKBOX_SPRITE,
                this.checkboxX, y, BOX_SIZE, BOX_SIZE);
        context.text(font, this.checkboxLabel, this.checkboxX + BOX_SIZE + BOX_LABEL_GAP, y + BOX_LABEL_Y_OFFSET, 0xFFFFFFFF);
    }

    private void button(GuiGraphicsExtractor context, Font font, int bx, String label, int mouseX, int mouseY) {
        RoomListScreen.drawSprite(context, this.hitButton(bx, mouseX, mouseY) ? "widget/button_highlighted" : "widget/button", bx, this.btnY, BTN_W, BTN_H);
        context.text(font, label, bx + (BTN_W - font.width(label)) / 2, this.btnY + (BTN_H - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.onCancel();
    }
    *///?} else {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        TextRenderer font = this.textRenderer;
        int cx = this.width / 2;
        int contentW = Math.max(0, font.getWidth(this.heading) * 2);
        for (String line : this.lines) contentW = Math.max(contentW, font.getWidth(line));
        int checkboxRowW = BOX_SIZE + BOX_LABEL_GAP + font.getWidth(this.checkboxLabel);
        contentW = Math.max(contentW, Math.max(checkboxRowW, 2 * BTN_W + BTN_GAP));
        int panelW = contentW + 2 * PAD;
        int panelH = PAD + LINE_H * 2 + GAP + this.lines.length * LINE_H + GAP + BOX_SIZE + GAP + BTN_H + PAD;
        int px = cx - panelW / 2, py = (this.height - panelH) / 2;

        //? if >=1.21.6 {
        /*context.createNewRootLayer();
        *///?} else {
        context.draw();
        context.getMatrices().push();
        context.getMatrices().translate(0f, 0f, 400f);
        //?}
        context.fill(0, 0, this.width, this.height, DIM_COLOR);
        context.fill(px, py, px + panelW, py + panelH, this.accentColor);
        context.fill(px + 1, py + 1, px + panelW - 1, py + panelH - 1, this.bgColor);

        // 헤딩은 2배 크기로 — §l(굵게)은 문자열에 이미 박혀 있어 렌더러가 알아서 처리한다.
        int headingY = py + PAD;
        //? if >=1.21.6 {
        /*context.getMatrices().pushMatrix();
        context.getMatrices().scale(2f, 2f);
        *///?} else {
        context.getMatrices().push();
        context.getMatrices().scale(2f, 2f, 1f);
        //?}
        context.drawCenteredTextWithShadow(font, Text.literal(this.heading), cx / 2, headingY / 2, this.accentColor);
        //? if >=1.21.6 {
        /*context.getMatrices().popMatrix();
        *///?} else {
        context.getMatrices().pop();
        //?}

        int y = headingY + LINE_H * 2 + GAP;
        for (String line : this.lines) {
            context.drawCenteredTextWithShadow(font, Text.literal(line), cx, y, this.accentColor);
            y += LINE_H;
        }

        y += GAP;
        this.checkboxX = cx - checkboxRowW / 2;
        this.checkboxY = y;
        this.drawCheckbox(context, font, y);
        y += BOX_SIZE + GAP;

        this.btnY = py + panelH - PAD - BTN_H;
        this.okX = cx - BTN_GAP / 2 - BTN_W;
        this.cancelX = cx + BTN_GAP / 2;
        this.button(context, font, this.okX, Text.translatable("instant-p2p.safety_warning.continue").getString(), mouseX, mouseY);
        this.button(context, font, this.cancelX, Text.translatable("gui.cancel").getString(), mouseX, mouseY);
        //? if <1.21.6 {
        context.getMatrices().pop();
        //?}
    }

    private void drawCheckbox(DrawContext context, TextRenderer font, int y) {
        RoomListScreen.drawSprite(context, this.dontShowAgain ? CHECKBOX_SELECTED_SPRITE : CHECKBOX_SPRITE,
                this.checkboxX, y, BOX_SIZE, BOX_SIZE);
        context.drawTextWithShadow(font, this.checkboxLabel, this.checkboxX + BOX_SIZE + BOX_LABEL_GAP, y + BOX_LABEL_Y_OFFSET, 0xFFFFFFFF);
    }

    private void button(DrawContext context, TextRenderer font, int bx, String label, int mouseX, int mouseY) {
        RoomListScreen.drawSprite(context, this.hitButton(bx, mouseX, mouseY) ? "widget/button_highlighted" : "widget/button", bx, this.btnY, BTN_W, BTN_H);
        context.drawTextWithShadow(font, label, bx + (BTN_W - font.getWidth(label)) / 2, this.btnY + (BTN_H - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        this.onCancel();
    }
    //?}
}
