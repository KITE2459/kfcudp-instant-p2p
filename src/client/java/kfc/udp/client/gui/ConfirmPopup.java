package kfc.udp.client.gui;

//? if >=26.1 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
//?}

import java.util.List;

/**
 * 화면을 바꾸지 않고 지금 화면 위에 띄우는 확인 팝업 — 방 목록의 차단, 차단 목록의 해제, 차단한 유저가 있는
 * 방에 들어갈 때의 확인에 쓴다.
 * <p>
 * 떠 있는 동안 화면은 모든 클릭·키·휠을 여기로 넘기고 자기 처리는 건너뛴다(각 화면의 mouseClicked/keyPressed/
 * mouseScrolled 맨 앞). Enter(키패드 포함)=확인, Esc=취소, 버튼 클릭도 된다. 그리기는 화면 render 맨 끝에서 한다.
 * <p>
 * 차단 유저 입장 확인({@link #openBlockedJoin})은 "네(10)"처럼 10초를 세고, 다 세면 "아니오"로 닫힌다. 차단한
 * 사람이 둘 이상이면 첫 이름만 보여주고 "그 외 n명"에 마우스를 올리면 나머지 이름이 툴팁으로 뜬다.
 */
final class ConfirmPopup {

    private static final int PAD = 8, LINE = 13, BTN_W = 80, BTN_H = 20, BTN_GAP = 8, MIN_W = 200;
    private static final int KEY_ENTER = 257, KEY_KP_ENTER = 335, KEY_ESCAPE = 256;
    private static final int DIM_COLOR = 0xA0000000, BG_COLOR = 0xFF000000, BORDER_COLOR = 0xFFA0A0A0;
    private static final int OTHERS_COLOR = 0xFFFFFF55;
    private static final long BLOCKED_JOIN_TIMEOUT_MS = 10_000;

    /** 일반 확인 — 이름 하나(%s)를 받는 번역 키. */
    private String messageKey, name;
    /** null이 아니면 차단 유저 입장 확인(openBlockedJoin) — 방에 있는 내가 차단한 사람들의 이름. */
    private List<String> blockedNames;
    private long deadlineMs;
    private Runnable onConfirm;
    /** 마지막으로 그린 버튼·"그 외 n명" 위치 — 클릭·호버 판정용(그리기 전이면 -1이라 안 맞는다). */
    private int okX = -1, cancelX = -1, btnY = -1;
    private int othersX = -1, othersY, othersW;

    boolean isOpen() {
        return this.onConfirm != null;
    }

    void open(String messageKey, String name, Runnable onConfirm) {
        this.messageKey = messageKey;
        this.name = name;
        this.blockedNames = null;
        this.onConfirm = onConfirm;
        this.okX = -1;
    }

    void openBlockedJoin(List<String> names, Runnable onConfirm) {
        this.blockedNames = List.copyOf(names);
        this.deadlineMs = System.currentTimeMillis() + BLOCKED_JOIN_TIMEOUT_MS;
        this.onConfirm = onConfirm;
        this.okX = -1;
    }

    /** 떠 있으면 키를 전부 삼킨다(true) — 뒤 화면의 Enter 접속·Esc 닫기·입력란 타이핑이 안 먹게. */
    boolean keyPressed(int keyCode) {
        if (!this.isOpen()) return false;
        if (keyCode == KEY_ENTER || keyCode == KEY_KP_ENTER) this.confirm();
        else if (keyCode == KEY_ESCAPE) this.onConfirm = null;
        return true;
    }

    void mouseClicked(double mouseX, double mouseY) {
        if (this.hit(this.okX, mouseX, mouseY)) {
            RoomListScreen.playClick();
            this.confirm();
        } else if (this.hit(this.cancelX, mouseX, mouseY)) {
            RoomListScreen.playClick();
            this.onConfirm = null;
        }
    }

    private boolean hit(int bx, double mouseX, double mouseY) {
        return bx >= 0 && mouseX >= bx && mouseX < bx + BTN_W && mouseY >= this.btnY && mouseY < this.btnY + BTN_H;
    }

    private boolean overOthers(int mouseX, int mouseY) {
        return this.othersX >= 0 && mouseX >= this.othersX && mouseX < this.othersX + this.othersW
                && mouseY >= this.othersY && mouseY < this.othersY + 10;
    }

    private void confirm() {
        Runnable r = this.onConfirm;
        this.onConfirm = null;
        r.run();
    }

    /** 차단 유저 입장 확인의 남은 초(올림). */
    private int secondsLeft() {
        return (int) Math.max(0, (this.deadlineMs - System.currentTimeMillis() + 999) / 1000);
    }

    /** 카운트다운이 끝났으면 "아니오"로 닫고 true — 매 프레임 render 맨 앞에서 확인한다. */
    private boolean expired() {
        if (this.blockedNames == null || this.secondsLeft() > 0) return false;
        this.onConfirm = null;
        return true;
    }

    /** 글 줄 수 — 메시지 + (차단 입장 확인이면 "접속하시겠습니까?") + 조작 안내. */
    private int height() {
        int lines = this.blockedNames != null ? 3 : 2;
        return PAD + lines * LINE - (LINE - 9) + PAD + BTN_H + PAD;
    }

    /** 화면 가운데 패널 위치를 정하고 버튼 좌표를 기록한다 — {패널 x, 패널 y, 패널 폭}. */
    private int[] layout(int textW, int screenW, int screenH) {
        int w = Math.max(MIN_W, textW + 2 * PAD);
        int h = this.height();
        int x = (screenW - w) / 2, y = (screenH - h) / 2;
        this.btnY = y + h - PAD - BTN_H;
        this.okX = screenW / 2 - BTN_GAP / 2 - BTN_W;
        this.cancelX = screenW / 2 + BTN_GAP / 2;
        return new int[]{x, y, w};
    }

    // 뒤 화면의 글자·스프라이트보다 확실히 위에 그려야 해서 레이어를 올린다 — 26.x nextStratum,
    // 1.21.6~ createNewRootLayer, 그 전은 즉시 그리기라 툴팁처럼 z를 400 앞으로 민다.
    // 첫 줄은 "차단된 유저 A" / "그 외 n명"(노란 밑줄, 호버 영역) / "의 …" 조각으로 나눠 이어 그린다.
    //? if >=26.1 {
    /*void render(GuiGraphicsExtractor ctx, int screenW, int screenH, int mouseX, int mouseY) {
        if (!this.isOpen() || this.expired()) return;
        Font font = Minecraft.getInstance().font;
        List<String> names = this.blockedNames;
        String head, others = null, tail = null, ask = null;
        if (names == null) {
            head = Component.translatable(this.messageKey, this.name).getString();
        } else if (names.size() == 1) {
            head = Component.translatable("instant-p2p.join_blocked.one", names.get(0)).getString();
        } else {
            head = Component.translatable("instant-p2p.join_blocked.many_head", names.get(0)).getString();
            others = Component.translatable("instant-p2p.join_blocked.many_others", names.size() - 1).getString();
            tail = Component.translatable("instant-p2p.join_blocked.many_tail").getString();
        }
        if (names != null) ask = Component.translatable("instant-p2p.join_blocked.message").getString();
        String hint = Component.translatable("instant-p2p.confirm.hint").getString();

        int line1W = font.width(head) + (others != null ? font.width(others) + font.width(tail) : 0);
        int textW = Math.max(line1W, Math.max(font.width(hint), ask != null ? font.width(ask) : 0));
        int[] p = this.layout(textW, screenW, screenH);
        int cx = screenW / 2, h = this.height();

        ctx.nextStratum();
        ctx.fill(0, 0, screenW, screenH, DIM_COLOR);
        ctx.fill(p[0], p[1], p[0] + p[2], p[1] + h, BORDER_COLOR);
        ctx.fill(p[0] + 1, p[1] + 1, p[0] + p[2] - 1, p[1] + h - 1, BG_COLOR);

        int y = p[1] + PAD, x = cx - line1W / 2;
        ctx.text(font, head, x, y, 0xFFFFFFFF);
        this.othersX = -1;
        if (others != null) {
            x += font.width(head);
            this.othersX = x;
            this.othersY = y;
            this.othersW = font.width(others);
            ctx.text(font, others, x, y, OTHERS_COLOR);
            ctx.fill(x, y + 9, x + this.othersW, y + 10, OTHERS_COLOR);
            ctx.text(font, tail, x + this.othersW, y, 0xFFFFFFFF);
        }
        y += LINE;
        if (ask != null) {
            ctx.text(font, ask, cx - font.width(ask) / 2, y, 0xFFFFFFFF);
            y += LINE;
        }
        ctx.text(font, hint, cx - font.width(hint) / 2, y, 0xFFA0A0A0);

        this.button(ctx, font, this.okX, names != null
                ? Component.translatable("instant-p2p.join_blocked.yes_countdown", this.secondsLeft()).getString()
                : Component.translatable("gui.ok").getString(), mouseX, mouseY);
        this.button(ctx, font, this.cancelX, Component.translatable(names != null ? "instant-p2p.join_blocked.no" : "gui.cancel").getString(),
                mouseX, mouseY);

        if (names != null && this.overOthers(mouseX, mouseY)) {
            ctx.setComponentTooltipForNextFrame(font,
                    names.subList(1, names.size()).stream().<Component>map(Component::literal).toList(), mouseX, mouseY);
        }
    }

    private void button(GuiGraphicsExtractor ctx, Font font, int bx, String label, int mouseX, int mouseY) {
        RoomListScreen.drawSprite(ctx, this.hit(bx, mouseX, mouseY) ? "widget/button_highlighted" : "widget/button", bx, this.btnY, BTN_W, BTN_H);
        ctx.text(font, label, bx + (BTN_W - font.width(label)) / 2, this.btnY + (BTN_H - 8) / 2, 0xFFFFFFFF);
    }
    *///?} else {
    void render(DrawContext ctx, int screenW, int screenH, int mouseX, int mouseY) {
        if (!this.isOpen() || this.expired()) return;
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        List<String> names = this.blockedNames;
        String head, others = null, tail = null, ask = null;
        if (names == null) {
            head = Text.translatable(this.messageKey, this.name).getString();
        } else if (names.size() == 1) {
            head = Text.translatable("instant-p2p.join_blocked.one", names.get(0)).getString();
        } else {
            head = Text.translatable("instant-p2p.join_blocked.many_head", names.get(0)).getString();
            others = Text.translatable("instant-p2p.join_blocked.many_others", names.size() - 1).getString();
            tail = Text.translatable("instant-p2p.join_blocked.many_tail").getString();
        }
        if (names != null) ask = Text.translatable("instant-p2p.join_blocked.message").getString();
        String hint = Text.translatable("instant-p2p.confirm.hint").getString();

        int line1W = font.getWidth(head) + (others != null ? font.getWidth(others) + font.getWidth(tail) : 0);
        int textW = Math.max(line1W, Math.max(font.getWidth(hint), ask != null ? font.getWidth(ask) : 0));
        int[] p = this.layout(textW, screenW, screenH);
        int cx = screenW / 2, h = this.height();

        //? if >=1.21.6 {
        /*ctx.createNewRootLayer();
        *///?} else {
        ctx.draw();
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, 0f, 400f);
        //?}
        ctx.fill(0, 0, screenW, screenH, DIM_COLOR);
        ctx.fill(p[0], p[1], p[0] + p[2], p[1] + h, BORDER_COLOR);
        ctx.fill(p[0] + 1, p[1] + 1, p[0] + p[2] - 1, p[1] + h - 1, BG_COLOR);

        int y = p[1] + PAD, x = cx - line1W / 2;
        ctx.drawTextWithShadow(font, head, x, y, 0xFFFFFFFF);
        this.othersX = -1;
        if (others != null) {
            x += font.getWidth(head);
            this.othersX = x;
            this.othersY = y;
            this.othersW = font.getWidth(others);
            ctx.drawTextWithShadow(font, others, x, y, OTHERS_COLOR);
            ctx.fill(x, y + 9, x + this.othersW, y + 10, OTHERS_COLOR);
            ctx.drawTextWithShadow(font, tail, x + this.othersW, y, 0xFFFFFFFF);
        }
        y += LINE;
        if (ask != null) {
            ctx.drawTextWithShadow(font, ask, cx - font.getWidth(ask) / 2, y, 0xFFFFFFFF);
            y += LINE;
        }
        ctx.drawTextWithShadow(font, hint, cx - font.getWidth(hint) / 2, y, 0xFFA0A0A0);

        this.button(ctx, font, this.okX, names != null
                ? Text.translatable("instant-p2p.join_blocked.yes_countdown", this.secondsLeft()).getString()
                : Text.translatable("gui.ok").getString(), mouseX, mouseY);
        this.button(ctx, font, this.cancelX, Text.translatable(names != null ? "instant-p2p.join_blocked.no" : "gui.cancel").getString(),
                mouseX, mouseY);

        if (names != null && this.overOthers(mouseX, mouseY)) {
            ctx.drawTooltip(font, names.subList(1, names.size()).stream().<Text>map(Text::literal).toList(), mouseX, mouseY);
        }
        //? if <1.21.6 {
        ctx.getMatrices().pop();
        //?}
    }

    private void button(DrawContext ctx, TextRenderer font, int bx, String label, int mouseX, int mouseY) {
        RoomListScreen.drawSprite(ctx, this.hit(bx, mouseX, mouseY) ? "widget/button_highlighted" : "widget/button", bx, this.btnY, BTN_W, BTN_H);
        ctx.drawTextWithShadow(font, label, bx + (BTN_W - font.getWidth(label)) / 2, this.btnY + (BTN_H - 8) / 2, 0xFFFFFFFF);
    }
    //?}
}
