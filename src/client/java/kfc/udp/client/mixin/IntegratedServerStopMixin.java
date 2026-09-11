package kfc.udp.client.mixin;

import kfc.udp.client.KfcudpClient;
//? if >=26.1 {
/*import net.minecraft.client.server.IntegratedServer;
*///?} else {
import net.minecraft.server.integrated.IntegratedServer;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 바닐라 {@code IntegratedServer#stop}(Yarn)/{@code #halt}(Mojang) (boolean)는
 * 방장이 아닌 접속자를 {@code disconnect()}(정상 Disconnect 패킷 발송) 없이
 * {@code PlayerManager#remove()}로 그냥 지워버린다 — "Save and Quit to Title"로
 * 월드를 닫으면 접속자한테 안내 메시지 하나 없는 날것의 "연결 끊김"이 뜨는 원인이다.
 * <p>
 * {@code ServerLifecycleEvents.SERVER_STOPPING}은 이 crude 제거보다 한참 뒤
 * ({@code shutdown()} 안)에 불려서 손쓸 도리가 없다 — 그 제거가 일어나기 전인
 * 이 메서드 HEAD에서 먼저 {@code KfcudpClient}의 정상 방 종료 로직을 태워야 한다.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerStopMixin {

    //? if >=26.1 {
    /*@Inject(method = "halt", at = @At("HEAD"))
    private void kfcudp$closeRoomBeforeStop(boolean save, CallbackInfo ci) {
        KfcudpClient.kfcudp$onIntegratedServerStopping((IntegratedServer) (Object) this);
    }
    *///?} else {
    @Inject(method = "stop", at = @At("HEAD"))
    private void kfcudp$closeRoomBeforeStop(boolean save, CallbackInfo ci) {
        KfcudpClient.kfcudp$onIntegratedServerStopping((IntegratedServer) (Object) this);
    }
    //?}
}
