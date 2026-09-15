package kfc.udp.client.mixin;

import kfc.udp.client.ChatHideSync;
import kfc.udp.client.KfcudpClient;
import kfc.udp.client.webrtc.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.client.gui.screens.social.PlayerSocialManager;
*///?} else {
import net.minecraft.client.network.SocialInteractionsManager;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 바닐라 "채팅에서 숨기기"(P키 소셜 상호작용 화면)를 누르면 모드 차단도 같이 한다 — ChatHideSync 클래스 주석.
 * 숨기기 = 모드 차단(방장이면 지금 방에서도 내보냄, BlockedPlayersScreen의 차단과 똑같이), 보이기 = 차단 해제.
 * 이미 같은 상태면 아무 것도 안 해서, 모드 쪽 차단이 바닐라 숨기기를 부른 경우 다시 되돌아오지 않는다.
 */
//? if >=26.1 {
/*@Mixin(PlayerSocialManager.class)
*///?} else {
@Mixin(SocialInteractionsManager.class)
//?}
public abstract class SocialHideMixin {

    @Inject(method = "hidePlayer", at = @At("TAIL"))
    private void kfcudp$blockOnHide(UUID id, CallbackInfo ci) {
        String uuid = id.toString();
        if (P2PBanManager.isPlayerBanned(uuid)) return;
        P2PBanManager.banPlayer(uuid, ChatHideSync.nameOf(id), "Hidden in chat.");
        KfcudpClient.kickBlockedPlayer(uuid);
    }

    @Inject(method = "showPlayer", at = @At("TAIL"))
    private void kfcudp$unblockOnShow(UUID id, CallbackInfo ci) {
        String uuid = id.toString();
        if (P2PBanManager.isPlayerBanned(uuid)) P2PBanManager.pardonPlayerByUuid(uuid);
    }
}
