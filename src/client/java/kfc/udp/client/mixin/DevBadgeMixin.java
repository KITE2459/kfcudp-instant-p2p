package kfc.udp.client.mixin;

import kfc.udp.client.DevBadge;
//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 탭 목록의 제작자·서포터 표시 — 탭 목록은 표시 이름(getDisplayName)이 아니라 서버가 따로 보내는 이름을 쓴다(없으면
 * 각 클라이언트가 닉네임+팀으로 직접 만듦). 그래서 표시 대상이면 이미 🛠·💬이 붙은 표시 이름(DevNameMixin)을 그대로 넘겨
 * 모든 곳의 표기를 한 방식으로 맞춘다. 바닐라는 이 이름을 접속할 때 탭 목록 패킷을 만들며 물어서 사실상 접속 시 1회다.
 * 다른 모드가 이미 탭 목록 이름을 정했으면 건드리지 않는다.
 */
//? if >=26.1 {
/*@Mixin(ServerPlayer.class)
*///?} else {
@Mixin(ServerPlayerEntity.class)
//?}
public abstract class DevBadgeMixin {

    //? if >=26.1 {
    /*@Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void kfcudp$devBadge(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (cir.getReturnValue() == null && DevBadge.hasBadge(self.getUUID())) cir.setReturnValue(self.getDisplayName());
    }
    *///?} else {
    @Inject(method = "getPlayerListName", at = @At("RETURN"), cancellable = true)
    private void kfcudp$devBadge(CallbackInfoReturnable<Text> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (cir.getReturnValue() == null && DevBadge.hasBadge(self.getUuid())) cir.setReturnValue(self.getDisplayName());
    }
    //?}
}
