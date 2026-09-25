package kfc.udp.client.gui;

import kfc.udp.client.webrtc.ChzzkLink;
//? if >=26.1 {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
*///?} else {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?}

/**
 * 방 목록 화면의 "방송인 역할 받기" 버튼으로 연다. 실제 OAuth 로그인·소유권 확인·팔로워 수
 * 재확인은 전부 mc-signaling이 처리한다(ChzzkLink/mc-signaling의 chzzk.go 클래스 주석 참고) —
 * 이 화면은 로그인 URL을 받아 기본 브라우저로 여는 것과, 지금 연동 상태를 보여주는 것만 한다.
 * <p>
 * 로그인은 브라우저에서 끝나고 이 화면으로 자동으로 안 돌아오므로(치지직이 우리 서버로 리다이렉트할
 * 뿐, 게임 클라이언트와는 별개), "새로고침" 버튼으로 유저가 직접 완료 여부를 확인하게 한다.
 * <p>
 * 연동 상태일 때만 뒤로가기/새로고침과 같은 줄(제목 텍스트 위, 높이도 그 둘과 동일)에 "연동 해제"
 * 버튼이 가운데로 뜬다(refreshStatus에서 visible 토글) — 서버(mc-signaling)의 chzzkLinks 맵에서
 * 이 UUID 항목을 지운다(ChzzkLink.requestUnlink, chzzk.go의 handleChzzkLinkUnlink 참고).
 * <p>
 * 뒤로가기/새로고침은 화면 좌우 상단 모서리에 고정 — 아래쪽은 로그인 수단 2×2 칸(치지직·유튜브,
 * 그 아래 트위치·씨미 — SOOP은 지원 안 해서 자리도 안 둔다)만 남긴다. 유튜브·트위치·씨미는 아직
 * mc-signaling에 로그인 URL 발급/콜백이 없어 비활성 버튼 + "준비중" 툴팁만 걸어둔다 — 실제
 * OAuth 연동은 나중에 붙인다.
 * <p>
 * 아이콘은 로고 원본을 정사각형(64x64)으로 다듬어(ProcessIcons 스크립트, 흰 배경은 투명 처리 후
 * 여백 잘라내고 패딩) assets/instant-p2p/textures/gui/sprites/에 넣었다 — 이 경로에 두면 바닐라
 * ping 막대(RoomListScreen.drawPingSprite)와 같은 GUI 스프라이트 아틀라스로 자동 편입돼, 같은
 * draw 호출을 네임스페이스만 바꿔 재사용할 수 있다.
 * <p>
 * 설명 문구는 ChannelScreen의 drawWrapped와 같은 요령으로 패널 폭에 맞춰 줄바꿈해서 그린다 —
 * TextWidget 한 줄짜리로는 폭을 넘으면 "..."로 잘렸다.
 */
public class ChzzkLinkScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int CORNER_BTN_W = 70, CORNER_BTN_H = 20, CORNER_MARGIN = 8;
    private static final int TITLE_Y = 34;
    private static final int DESC_Y = 50;
    private static final int LINE_H = 10;
    private static final int LOGIN_BTN_H = 20, ROW_GAP = 4;
    private static final int BTN_W = PANEL_W / 2 - 4;
    private static final int ICON_SIZE = 14, ICON_PAD = 5;
    private static final int STATUS_ICON_SIZE = 9, STATUS_ICON_GAP = 4;
    private static final int UNLINK_BTN_W = 120;

    //? if >=26.1 {
    /*private static final Component TITLE_TEXT = Component.translatable("instant-p2p.chzzk_link.title");
    private static final Component DESC_TEXT = Component.translatable("instant-p2p.chzzk_link.desc").withStyle(ChatFormatting.GRAY);
    private static final Component DESC2_TEXT = Component.translatable("instant-p2p.chzzk_link.desc2").withStyle(ChatFormatting.GRAY);
    private static final Component LOGIN_TEXT = Component.translatable("instant-p2p.chzzk_link.login");
    private static final Component LOGIN_TOOLTIP_TEXT = Component.translatable("instant-p2p.chzzk_link.login_tooltip");
    private static final Component TWITCH_TEXT = Component.translatable("instant-p2p.chzzk_link.twitch_login");
    private static final Component CIME_TEXT = Component.translatable("instant-p2p.chzzk_link.cime_login");
    private static final Component REFRESH_TEXT = Component.translatable("instant-p2p.chzzk_link.refresh");
    private static final Component CHECKING_TEXT = Component.translatable("instant-p2p.chzzk_link.checking").withStyle(ChatFormatting.GRAY);
    private static final Component OPENING_TEXT = Component.translatable("instant-p2p.chzzk_link.opening").withStyle(ChatFormatting.GRAY);
    private static final Component UNLINKED_TEXT = Component.translatable("instant-p2p.chzzk_link.unlinked").withStyle(ChatFormatting.GRAY);
    private static final Component OPEN_FAILED_TEXT = Component.translatable("instant-p2p.chzzk_link.open_failed").withStyle(ChatFormatting.RED);
    private static final Component COMING_SOON_TEXT = Component.translatable("instant-p2p.chzzk_link.coming_soon");
    private static final Component UNLINK_TEXT = Component.translatable("instant-p2p.chzzk_link.unlink").withStyle(ChatFormatting.RED);
    *///?} else {
    private static final Text TITLE_TEXT = Text.translatable("instant-p2p.chzzk_link.title");
    private static final Text DESC_TEXT = Text.translatable("instant-p2p.chzzk_link.desc").formatted(Formatting.GRAY);
    private static final Text DESC2_TEXT = Text.translatable("instant-p2p.chzzk_link.desc2").formatted(Formatting.GRAY);
    private static final Text LOGIN_TEXT = Text.translatable("instant-p2p.chzzk_link.login");
    private static final Text LOGIN_TOOLTIP_TEXT = Text.translatable("instant-p2p.chzzk_link.login_tooltip");
    private static final Text TWITCH_TEXT = Text.translatable("instant-p2p.chzzk_link.twitch_login");
    private static final Text CIME_TEXT = Text.translatable("instant-p2p.chzzk_link.cime_login");
    private static final Text REFRESH_TEXT = Text.translatable("instant-p2p.chzzk_link.refresh");
    private static final Text CHECKING_TEXT = Text.translatable("instant-p2p.chzzk_link.checking").formatted(Formatting.GRAY);
    private static final Text OPENING_TEXT = Text.translatable("instant-p2p.chzzk_link.opening").formatted(Formatting.GRAY);
    private static final Text UNLINKED_TEXT = Text.translatable("instant-p2p.chzzk_link.unlinked").formatted(Formatting.GRAY);
    private static final Text OPEN_FAILED_TEXT = Text.translatable("instant-p2p.chzzk_link.open_failed").formatted(Formatting.RED);
    private static final Text COMING_SOON_TEXT = Text.translatable("instant-p2p.chzzk_link.coming_soon");
    private static final Text UNLINK_TEXT = Text.translatable("instant-p2p.chzzk_link.unlink").formatted(Formatting.RED);
    //?}

    private final Screen parent;
    private int loginY;
    private boolean linked;
    //? if >=26.1 {
    /*private StringWidget statusText;
    private Button unlinkButton;
    *///?} else {
    private TextWidget statusText;
    private ButtonWidget unlinkButton;
    //?}

    public ChzzkLinkScreen(Screen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    private int panelX() {
        return this.width / 2 - PANEL_W / 2;
    }

    //? if >=26.1 {
    /*@Override
    protected void init() {
        int x = this.panelX();
        int desc1Lines = this.font.split(DESC_TEXT, PANEL_W).size();
        int desc2Y = DESC_Y + desc1Lines * LINE_H + 4;
        int desc2Lines = this.font.split(DESC2_TEXT, PANEL_W).size();
        int statusY = desc2Y + desc2Lines * LINE_H + 8;
        this.loginY = statusY + 9 + 12;
        int col2X = x + PANEL_W / 2 + 4;
        int row2Y = this.loginY + LOGIN_BTN_H + ROW_GAP;

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .bounds(CORNER_MARGIN, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());
        this.addRenderableWidget(Button.builder(REFRESH_TEXT, b -> this.refreshStatus())
                .bounds(this.width - CORNER_MARGIN - CORNER_BTN_W, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());

        // 연동 해제 — 뒤로가기/새로고침과 같은 줄(제목 텍스트 위), 높이도 그 둘과 같다(CORNER_BTN_H).
        // 지금 연동된 상태일 때만 보인다(refreshStatus에서 visible 토글).
        this.unlinkButton = Button.builder(UNLINK_TEXT, b -> this.onUnlinkClicked())
                .bounds(this.width / 2 - UNLINK_BTN_W / 2, CORNER_MARGIN, UNLINK_BTN_W, CORNER_BTN_H).build();
        this.unlinkButton.visible = false;
        this.addRenderableWidget(this.unlinkButton);

        this.statusText = new StringWidget(x, statusY, PANEL_W, 9, CHECKING_TEXT, this.font);
        this.addRenderableWidget(this.statusText);

        // 치지직(왼쪽 위, 실제 동작)·트위치(오른쪽 위)·씨미(왼쪽 아래)는 아직 준비 중 — 유튜브는 뺐다.
        Button loginButton = Button.builder(LOGIN_TEXT, b -> this.onLoginClicked())
                .bounds(x, this.loginY, BTN_W, LOGIN_BTN_H).build();
        loginButton.setTooltip(Tooltip.create(LOGIN_TOOLTIP_TEXT));
        this.addRenderableWidget(loginButton);
        this.addRenderableWidget(this.comingSoonButton(TWITCH_TEXT, col2X, this.loginY));
        this.addRenderableWidget(this.comingSoonButton(CIME_TEXT, x, row2Y));

        this.refreshStatus();
    }

    private Button comingSoonButton(Component text, int x, int y) {
        Button b = Button.builder(text, btn -> {}).bounds(x, y, BTN_W, LOGIN_BTN_H).build();
        b.active = false;
        b.setTooltip(Tooltip.create(COMING_SOON_TEXT));
        return b;
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
        context.centeredText(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        int desc1Lines = this.font.split(DESC_TEXT, PANEL_W).size();
        this.drawWrapped(context, DESC_TEXT, x, DESC_Y, 0xFFA0A0A0);
        this.drawWrapped(context, DESC2_TEXT, x, DESC_Y + desc1Lines * LINE_H + 4, 0xFFA0A0A0);
        int col2X = x + PANEL_W / 2 + 4;
        int row2Y = this.loginY + LOGIN_BTN_H + ROW_GAP;
        int iconYOff = (LOGIN_BTN_H - ICON_SIZE) / 2;
        drawIcon(context, "chzzk", x + BTN_W - ICON_PAD - ICON_SIZE, this.loginY + iconYOff, ICON_SIZE, ICON_SIZE);
        drawIcon(context, "twitch", col2X + BTN_W - ICON_PAD - ICON_SIZE, this.loginY + iconYOff, ICON_SIZE, ICON_SIZE);
        drawIcon(context, "cime", x + BTN_W - ICON_PAD - ICON_SIZE, row2Y + iconYOff, ICON_SIZE, ICON_SIZE);
        // 연동된 채널 이름 왼쪽에 어느 플랫폼인지 아이콘으로 표기 — 지금은 치지직만 실제로 연동
        // 가능해서 항상 chzzk 아이콘이다(트위치·씨미가 붙으면 어느 쪽이 연동됐는지에 따라 갈라야 함).
        if (this.linked) {
            drawIcon(context, "chzzk", this.statusText.getX() - STATUS_ICON_GAP - STATUS_ICON_SIZE, this.statusText.getY(),
                    STATUS_ICON_SIZE, STATUS_ICON_SIZE);
        }
    }

    private void drawWrapped(GuiGraphicsExtractor context, Component text, int x, int y, int color) {
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(text, PANEL_W)) {
            context.text(this.font, line, x, y, color);
            y += LINE_H;
        }
    }

    private static void drawIcon(GuiGraphicsExtractor context, String sprite, int x, int y, int w, int h) {
        context.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("instant-p2p", sprite), x, y, w, h);
    }
    *///?} else {
    @Override
    protected void init() {
        int x = this.panelX();
        int desc1Lines = this.textRenderer.wrapLines(DESC_TEXT, PANEL_W).size();
        int desc2Y = DESC_Y + desc1Lines * LINE_H + 4;
        int desc2Lines = this.textRenderer.wrapLines(DESC2_TEXT, PANEL_W).size();
        int statusY = desc2Y + desc2Lines * LINE_H + 8;
        this.loginY = statusY + 9 + 12;
        int col2X = x + PANEL_W / 2 + 4;
        int row2Y = this.loginY + LOGIN_BTN_H + ROW_GAP;

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> this.close())
                .dimensions(CORNER_MARGIN, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());
        this.addDrawableChild(ButtonWidget.builder(REFRESH_TEXT, b -> this.refreshStatus())
                .dimensions(this.width - CORNER_MARGIN - CORNER_BTN_W, CORNER_MARGIN, CORNER_BTN_W, CORNER_BTN_H).build());

        // 연동 해제 — 뒤로가기/새로고침과 같은 줄(제목 텍스트 위), 높이도 그 둘과 같다(CORNER_BTN_H).
        // 지금 연동된 상태일 때만 보인다(refreshStatus에서 visible 토글).
        this.unlinkButton = ButtonWidget.builder(UNLINK_TEXT, b -> this.onUnlinkClicked())
                .dimensions(this.width / 2 - UNLINK_BTN_W / 2, CORNER_MARGIN, UNLINK_BTN_W, CORNER_BTN_H).build();
        this.unlinkButton.visible = false;
        this.addDrawableChild(this.unlinkButton);

        this.statusText = new TextWidget(x, statusY, PANEL_W, 9, CHECKING_TEXT, this.textRenderer);
        this.addDrawableChild(this.statusText);

        // 치지직(왼쪽 위, 실제 동작)·트위치(오른쪽 위)·씨미(왼쪽 아래)는 아직 준비 중 — 유튜브는 뺐다.
        ButtonWidget loginButton = ButtonWidget.builder(LOGIN_TEXT, b -> this.onLoginClicked())
                .dimensions(x, this.loginY, BTN_W, LOGIN_BTN_H).build();
        loginButton.setTooltip(Tooltip.of(LOGIN_TOOLTIP_TEXT));
        this.addDrawableChild(loginButton);
        this.addDrawableChild(this.comingSoonButton(TWITCH_TEXT, col2X, this.loginY));
        this.addDrawableChild(this.comingSoonButton(CIME_TEXT, x, row2Y));

        this.refreshStatus();
    }

    private ButtonWidget comingSoonButton(Text text, int x, int y) {
        ButtonWidget b = ButtonWidget.builder(text, btn -> {}).dimensions(x, y, BTN_W, LOGIN_BTN_H).build();
        b.active = false;
        b.setTooltip(Tooltip.of(COMING_SOON_TEXT));
        return b;
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        int desc1Lines = this.textRenderer.wrapLines(DESC_TEXT, PANEL_W).size();
        this.drawWrapped(context, DESC_TEXT, x, DESC_Y, 0xFFA0A0A0);
        this.drawWrapped(context, DESC2_TEXT, x, DESC_Y + desc1Lines * LINE_H + 4, 0xFFA0A0A0);
        int col2X = x + PANEL_W / 2 + 4;
        int row2Y = this.loginY + LOGIN_BTN_H + ROW_GAP;
        int iconYOff = (LOGIN_BTN_H - ICON_SIZE) / 2;
        drawIcon(context, "chzzk", x + BTN_W - ICON_PAD - ICON_SIZE, this.loginY + iconYOff, ICON_SIZE, ICON_SIZE);
        drawIcon(context, "twitch", col2X + BTN_W - ICON_PAD - ICON_SIZE, this.loginY + iconYOff, ICON_SIZE, ICON_SIZE);
        drawIcon(context, "cime", x + BTN_W - ICON_PAD - ICON_SIZE, row2Y + iconYOff, ICON_SIZE, ICON_SIZE);
        // 연동된 채널 이름 왼쪽에 어느 플랫폼인지 아이콘으로 표기 — 지금은 치지직만 실제로 연동
        // 가능해서 항상 chzzk 아이콘이다(트위치·씨미가 붙으면 어느 쪽이 연동됐는지에 따라 갈라야 함).
        if (this.linked) {
            drawIcon(context, "chzzk", this.statusText.getX() - STATUS_ICON_GAP - STATUS_ICON_SIZE, this.statusText.getY(),
                    STATUS_ICON_SIZE, STATUS_ICON_SIZE);
        }
    }

    private void drawWrapped(DrawContext context, Text text, int x, int y, int color) {
        for (net.minecraft.text.OrderedText line : this.textRenderer.wrapLines(text, PANEL_W)) {
            context.drawTextWithShadow(this.textRenderer, line, x, y, color);
            y += LINE_H;
        }
    }

    // 아이콘(트위치·유튜브) 그리기 — 텍스처 API가 1.21.2(RenderLayer)·1.21.6(RenderPipeline)에서
    // 바뀌어 RoomListScreen.drawPingSprite/drawSprite와 같은 요령으로 나눈다. 다만 저건 바닐라
    // 네임스페이스 고정(Identifier.ofVanilla)이라 우리 모드 텍스처엔 못 쓰고, 네임스페이스를 지정하는
    // Identifier.of로 새로 만든다.
    //? if >=1.21.6 <26.1 {
    /*private static void drawIcon(DrawContext context, String sprite, int x, int y, int w, int h) {
        context.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                net.minecraft.util.Identifier.of("instant-p2p", sprite), x, y, w, h);
    }
    *///?}
    //? if >=1.21.2 <1.21.6 {
    private static void drawIcon(DrawContext context, String sprite, int x, int y, int w, int h) {
        context.drawGuiTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,
                net.minecraft.util.Identifier.of("instant-p2p", sprite), x, y, w, h);
    }
    //?}
    //? if <1.21.2 {
    /*private static void drawIcon(DrawContext context, String sprite, int x, int y, int w, int h) {
        context.drawGuiTexture(net.minecraft.util.Identifier.of("instant-p2p", sprite), x, y, w, h);
    }
    *///?}
    //?}

    private void onLoginClicked() {
        // 이 화면은 방 목록(접속 전)에서 오는 거라 client.player가 아직 없다 — 로그인 세션 UUID를
        // 써야 한다(KfcudpClient.capacityMarker와 같은 이유). player.getUUID()를 쓰면 null이라
        // 여기서 조용히 return돼서 버튼을 눌러도 로그 한 줄 없이 아무 반응이 없었다.
        //? if >=26.1 {
        /*java.util.UUID me = net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
        *///?} else {
        java.util.UUID me = net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
        //?}
        if (me == null) return;
        this.statusText.setMessage(OPENING_TEXT); // 요청 끝날 때까지(최대 몇 초) 아무 반응도 없어 보이는 것 방지
        ChzzkLink.requestAuthUrl(me).thenAccept(url -> runOnClientThread(() -> {
            if (url == null) {
                this.statusText.setMessage(OPEN_FAILED_TEXT);
                return;
            }
            try {
                // 버전마다 브라우저 여는 메서드 이름이 다르다 — 26.3에서 openUri가 open으로
                // 또 바뀜(IntegratedServerMaxPlayersMixin의 26.3 전용 분기들과 같은 요령으로,
                // 서로 안 겹치는 범위의 독립된 //? if 블록 3개로 나눴다. else 체인은 Stitcher가
                // 못 알아들어서 26.3 빌드에서 엉뚱한 분기가 살아남는 버그가 있었다).
                //? if >=26.3 {
                /*com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
                *///?}
                //? if >=26.1 <26.3 {
                /*net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(url));
                *///?}
                //? if <26.1 {
                net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
                //?}
            } catch (Exception e) {
                this.statusText.setMessage(OPEN_FAILED_TEXT);
            }
        }));
    }

    private void refreshStatus() {
        this.linked = false;
        this.unlinkButton.visible = false;
        this.statusText.setX(this.panelX());
        this.statusText.setMessage(CHECKING_TEXT);
        //? if >=26.1 {
        /*java.util.UUID me = net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
        *///?} else {
        java.util.UUID me = net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
        //?}
        if (me == null) return;
        ChzzkLink.fetchStatus(me).thenAccept(status -> runOnClientThread(() -> {
            if (!status.linked()) {
                this.statusText.setMessage(UNLINKED_TEXT);
                return;
            }
            // 연동됨 — 아이콘이 들어갈 자리만큼 텍스트를 오른쪽으로 밀고, 그 앞자리에 플랫폼
            // 아이콘을 그린다(render/extractRenderState의 this.linked 분기).
            this.linked = true;
            this.unlinkButton.visible = true;
            this.statusText.setX(this.panelX() + STATUS_ICON_SIZE + STATUS_ICON_GAP);
            //? if >=26.1 {
            /*Component line = Component.translatable("instant-p2p.chzzk_link.linked", status.channelName(), status.followerCount())
                    .withStyle(status.qualifies() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            *///?} else {
            Text line = Text.translatable("instant-p2p.chzzk_link.linked", status.channelName(), status.followerCount())
                    .formatted(status.qualifies() ? Formatting.GREEN : Formatting.GRAY);
            //?}
            this.statusText.setMessage(line);
        }));
    }

    /** 연동 해제 — 성공하든 실패하든 새로고침해서 실제 서버 상태를 다시 보여준다(낙관적으로
     * 미리 안 지운다 — 실패했는데 지워진 것처럼 보이면 헷갈린다). */
    private void onUnlinkClicked() {
        //? if >=26.1 {
        /*java.util.UUID me = net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
        *///?} else {
        java.util.UUID me = net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
        //?}
        if (me == null) return;
        this.statusText.setMessage(CHECKING_TEXT);
        ChzzkLink.requestUnlink(me).thenAccept(ok -> runOnClientThread(this::refreshStatus));
    }

    private void runOnClientThread(Runnable r) {
        //? if >=26.1 {
        /*net.minecraft.client.Minecraft.getInstance().execute(r);
        *///?} else {
        net.minecraft.client.MinecraftClient.getInstance().execute(r);
        //?}
    }
}
