package kfc.udp.client.mixin;

import kfc.udp.client.KfcudpClient;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
*///?} else {
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.server.network.ServerCommonNetworkHandler;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Save and Quit to Title"로 방장 본인의 연결이 끊기면, 바닐라가
 * {@code onDisconnected}(Yarn)/{@code onDisconnect}(Mojang) 안에서 곧바로
 * {@code MinecraftServer#stop}/{@code #halt}{@code (false)}를 불러버린다 —
 * {@code join} 없이 즉시(비동기) 실행되는 버전이라 {@link IntegratedServerStopMixin}이
 * 거는 {@code stop}/{@code halt(boolean)} HEAD 후킹보다 사실상 먼저 효력을 낸다.
 * 그 결과 접속자는 우리 쪽 정상 disconnect가 미처 끝나기도 전에 바닐라의
 * {@code disconnectAllPlayers()}(사유 없는 강제 종료)로 먼저 끊겨서, "호스트가 방을
 * 닫았습니다" 대신 원인 불명의 "연결 끊김"을 보게 된다.
 * <p>
 * 그 {@code stop}/{@code halt(false)} 호출보다 먼저, 이 메서드 HEAD에서 방장인지
 * 확인하고(바닐라와 동일한 조건) 우리 쪽 정상 방 종료를 먼저 태운다.
 */
//? if >=26.1 {
/*@Mixin(ServerCommonPacketListenerImpl.class)
*///?} else {
@Mixin(ServerCommonNetworkHandler.class)
//?}
public abstract class ServerDisconnectStopMixin {

    //? if >=26.1 {
    /*@Shadow
    protected MinecraftServer server;

    @Shadow
    protected abstract boolean isSingleplayerOwner();

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void kfcudp$closeRoomBeforeHostDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        if (isSingleplayerOwner()) {
            KfcudpClient.kfcudp$onWorldStopping((net.minecraft.client.server.IntegratedServer) server);
        }
    }
    *///?} else {
    @Shadow
    protected MinecraftServer server;

    @Shadow
    protected abstract boolean isHost();

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void kfcudp$closeRoomBeforeHostDisconnect(DisconnectionInfo info, CallbackInfo ci) {
        if (isHost()) {
            KfcudpClient.kfcudp$onWorldStopping((net.minecraft.server.integrated.IntegratedServer) server);
        }
    }
    //?}
}
