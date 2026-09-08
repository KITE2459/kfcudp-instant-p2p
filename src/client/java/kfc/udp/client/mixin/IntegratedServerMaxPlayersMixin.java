package kfc.udp.client.mixin;

import kfc.udp.client.webrtc.P2PBanManager;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.9부터 {@code IntegratedServer#getMaxPlayerCount()}가 {@code PlayerManager}의
 * {@code maxPlayers} 필드를 안 보고 그냥 {@code 8}을 하드코딩해서 반환한다
 * ({@link PlayerManagerAccessor}가 더 이상 안 통하는 이유). Custom Room에서 고른
 * 정원을 여기서 가로채 돌려준다.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMaxPlayersMixin {

    //? if >=1.21.9 {
    /*@Inject(method = "getMaxPlayerCount", at = @At("HEAD"), cancellable = true)
    private void kfcudp$getMaxPlayerCount(CallbackInfoReturnable<Integer> cir) {
        int max = P2PBanManager.getRoomMaxPlayers();
        if (max > 0) cir.setReturnValue(max);
    }
    *///?}
}
