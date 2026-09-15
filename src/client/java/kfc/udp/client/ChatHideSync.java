package kfc.udp.client;

import kfc.udp.client.webrtc.P2PBanManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=26.1 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
//?}

import java.util.UUID;

/**
 * 모드 차단 ↔ 바닐라 "채팅에서 숨기기"(P키 소셜 상호작용 화면) 연동.
 * <p>
 * 모드에서 차단·해제하면 바닐라 숨기기도 같이 켜고 끈다({@link #apply}, P2PBanManager의 차단·해제가 부른다).
 * 반대로 바닐라에서 숨기기·보이기를 누르면 모드 차단·해제가 된다(mixin.SocialHideMixin). 서로 상태가 다를 때만
 * 바꾸므로 한쪽 변경이 다른 쪽을 다시 부르는 무한 반복은 없다.
 * <p>
 * 바닐라 숨김 목록은 게임을 끄면 사라지고 모드 차단 목록은 파일에 남는다 — 그래서 월드·서버에 들어갈 때마다
 * 모드 차단 목록을 바닐라 쪽에 다시 채운다({@link #register}). 마이크로소프트 계정 "차단"은 게임 밖 계정 설정이라
 * 연동하지 않는다.
 */
public final class ChatHideSync {

    private ChatHideSync() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                P2PBanManager.listBannedPlayers().forEach(e -> apply(e.uuid(), true)));
    }

    private static UUID parse(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null; // 손으로 고친 파일 등 UUID가 아닌 키
        }
    }

    // 차단 목록은 서버 스레드(/ban)에서도 바뀌므로 바닐라 숨김 목록은 클라이언트 스레드로 넘겨서 만진다.
    //? if >=26.1 {
    /*public static void apply(String uuid, boolean hidden) {
        UUID id = parse(uuid);
        Minecraft client = Minecraft.getInstance();
        if (id == null || client == null) return;
        client.execute(() -> {
            PlayerSocialManager social = client.getPlayerSocialManager();
            if (social == null || social.isHidden(id) == hidden) return;
            if (hidden) social.hidePlayer(id);
            else social.showPlayer(id);
        });
    }

    public static String nameOf(UUID id) {
        Minecraft client = Minecraft.getInstance();
        var info = client.getConnection() != null ? client.getConnection().getPlayerInfo(id) : null;
        return info != null ? P2PBanManager.profileName(info.getProfile()) : id.toString().substring(0, 8);
    }
    *///?} else {
    public static void apply(String uuid, boolean hidden) {
        UUID id = parse(uuid);
        MinecraftClient client = MinecraftClient.getInstance();
        if (id == null || client == null) return;
        client.execute(() -> {
            SocialInteractionsManager social = client.getSocialInteractionsManager();
            if (social == null || social.isPlayerHidden(id) == hidden) return;
            if (hidden) social.hidePlayer(id);
            else social.showPlayer(id);
        });
    }

    public static String nameOf(UUID id) {
        MinecraftClient client = MinecraftClient.getInstance();
        var entry = client.getNetworkHandler() != null ? client.getNetworkHandler().getPlayerListEntry(id) : null;
        return entry != null ? P2PBanManager.profileName(entry.getProfile()) : id.toString().substring(0, 8);
    }
    //?}
}
