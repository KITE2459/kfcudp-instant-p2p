package kfc.udp.client.mixin;

import kfc.udp.client.DevBadge;
//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
*///?} else {
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 제작자·서포터 표시를 플레이어 표시 이름에 붙인다(DevBadge 클래스 주석). 서버 쪽에선 채팅·입장/퇴장·사망 메시지가,
 * 각 클라이언트에선 머리 위 이름표가 이 이름을 쓴다. 팀 접두/접미어와 색은 바닐라가 이미 입힌 뒤라 그대로 남는다.
 * <p>
 * 매번 새로 확인한다(캐시 안 함) — DevBadge.shouldDecorate은 이제 Set.contains 몇 번이라 매 프레임 불려도
 * 싸다. 예전엔 결과를 한 번 memoize했는데, Roles가 서버에서 비동기로 늦게 채워질 수 있는 지금 구조에선
 * 플레이어 접속 시점에 딱 한 번 먼저 평가돼서 false로 굳어버리면(캐시 갱신이 그 뒤에 와도) 영영 안 붙는
 * 문제가 있었다.
 */
//? if >=26.1 {
/*@Mixin(Player.class)
*///?} else {
@Mixin(PlayerEntity.class)
//?}
public abstract class DevNameMixin {

    //? if >=26.1 {
    /*@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void kfcudp$devBadge(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        // 배지 대상이어도 실제로 instant-p2p 방(내 호스팅 또는 webrtc 접속)에서만 붙인다 — 클래스 주석 참고.
        if (DevBadge.shouldDecorate(self.getUUID()) && DevBadge.isP2pSessionActive()) cir.setReturnValue(DevBadge.decorate(self.getUUID(), cir.getReturnValue()));
    }
    *///?} else {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void kfcudp$devBadge(CallbackInfoReturnable<Text> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        // 배지 대상이어도 실제로 instant-p2p 방(내 호스팅 또는 webrtc 접속)에서만 붙인다 — 클래스 주석 참고.
        if (DevBadge.shouldDecorate(self.getUuid()) && DevBadge.isP2pSessionActive()) cir.setReturnValue(DevBadge.decorate(self.getUuid(), cir.getReturnValue()));
    }
    //?}
}
