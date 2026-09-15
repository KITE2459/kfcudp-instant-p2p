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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 제작자 표시를 플레이어 표시 이름에 붙인다(DevBadge 클래스 주석). 서버 쪽에선 채팅·입장/퇴장·사망 메시지가,
 * 각 클라이언트에선 머리 위 이름표가 이 이름을 쓴다. 팀 접두/접미어와 색은 바닐라가 이미 입힌 뒤라 그대로 남는다.
 * UUID 대조는 플레이어마다 처음 한 번만 하고 결과를 기억한다 — 이름표 때문에 매 프레임 불리는 메서드라서.
 */
//? if >=26.1 {
/*@Mixin(Player.class)
*///?} else {
@Mixin(PlayerEntity.class)
//?}
public abstract class DevNameMixin {

    /** null = 아직 대조 전. */
    @Unique
    private Boolean kfcudp$isDev;

    //? if >=26.1 {
    /*@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void kfcudp$devBadge(CallbackInfoReturnable<Component> cir) {
        if (this.kfcudp$isDev == null) this.kfcudp$isDev = DevBadge.isDev(((Player) (Object) this).getUUID());
        if (this.kfcudp$isDev) cir.setReturnValue(DevBadge.decorate(cir.getReturnValue()));
    }
    *///?} else {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void kfcudp$devBadge(CallbackInfoReturnable<Text> cir) {
        if (this.kfcudp$isDev == null) this.kfcudp$isDev = DevBadge.isDev(((PlayerEntity) (Object) this).getUuid());
        if (this.kfcudp$isDev) cir.setReturnValue(DevBadge.decorate(cir.getReturnValue()));
    }
    //?}
}
