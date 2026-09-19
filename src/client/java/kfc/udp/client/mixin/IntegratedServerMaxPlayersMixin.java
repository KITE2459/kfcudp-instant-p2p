package kfc.udp.client.mixin;

import kfc.udp.client.webrtc.P2PBanManager;
//? if >=26.1 {
/*import net.minecraft.client.server.IntegratedServer;
*///?} else {
import net.minecraft.server.integrated.IntegratedServer;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.9부터 {@code IntegratedServer#getMaxPlayerCount()}가 {@code PlayerManager}의
 * {@code maxPlayers} 필드를 안 보고 그냥 {@code 8}을 하드코딩해서 반환한다
 * ({@link PlayerManagerAccessor}가 더 이상 안 통하는 이유). Custom Room에서 고른
 * 정원을 여기서 가로채 돌려준다.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMaxPlayersMixin {

    //? if >=26.1 {
    /*@Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
    private void kfcudp$getMaxPlayerCount(CallbackInfoReturnable<Integer> cir) {
        int max = P2PBanManager.getRoomMaxPlayers();
        if (max > 0) cir.setReturnValue(max);
    }
    *///?}
    // 26.3부터 LAN 접속자 게임 모드 필드(gameTypeForOtherPlayers)가 없어졌다 — 방이 열려 있으면 26.2처럼
    // 방 게임 모드를 강제 게임 모드로 돌려준다. 새 접속자에게 적용되고, 명령어 권한이 있는 접속자는 바닐라가 뺀다.
    //? if >=26.3 {
    /*@Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void kfcudp$roomGameMode(CallbackInfoReturnable<net.minecraft.world.level.GameType> cir) {
        IntegratedServer self = (IntegratedServer) (Object) this;
        if (P2PBanManager.getRoomMaxPlayers() > 0 && self.isPublished() && !self.isHardcore()) {
            cir.setReturnValue(kfc.udp.client.KfcudpClient.getActiveGameMode());
        }
    }
    *///?}
    // 26.3은 접속자 치트 권한이 월드 설정(level.dat의 allowCommands)에 묶여 있다 — 방 옵션 때문에 월드를
    // 건드리면 싱글 월드의 치트 설정이 영구히 바뀐다. 방이 열려 있는 동안엔 방 옵션만 보고 답한다.
    // getCustomPermissionLevel()은 인자가 없어(누구 요청인지 모름) 여기서 가로채면 op 여부와 무관하게
    // 전원이 같은 권한을 받는다 — /op로 실제 오프 목록에 올라간 손님까지 방 옵션(치트 허용 꺼짐)에
    // 깔려서 ALL(권한 없음)로 굳어버렸다. 그래서 대상을 아는 getProfilePermissions에서 먼저 op 여부부터
    // 보고, op면 오프 목록의 진짜 권한을 그대로 돌려준다(바닐라 fallback과 동일) — 방 옵션은 op가
    // *아닌* 손님에게만 적용된다. 방장(싱글플레이 오너)에겐 이 메서드가 안 불리므로 방장 치트는
    // 월드 설정 그대로 유지된다.
    //? if >=26.3 {
    /*@Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void kfcudp$roomGuestPermissions(net.minecraft.server.players.NameAndId id,
            CallbackInfoReturnable<net.minecraft.server.permissions.LevelBasedPermissionSet> cir) {
        IntegratedServer self = (IntegratedServer) (Object) this;
        if (P2PBanManager.getRoomMaxPlayers() <= 0 || self.isSingleplayerOwner(id)) return;
        net.minecraft.server.players.PlayerList playerList = self.getPlayerList();
        if (playerList.isOp(id)) {
            net.minecraft.server.players.ServerOpListEntry entry =
                    (net.minecraft.server.players.ServerOpListEntry) playerList.getOps().get(id);
            cir.setReturnValue(entry != null ? entry.permissions()
                    : net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
            return;
        }
        cir.setReturnValue(kfc.udp.client.KfcudpClient.isActiveAllowCheats()
                ? net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER
                : net.minecraft.server.permissions.LevelBasedPermissionSet.ALL);
    }
    *///?}
    // 바닐라 LAN 설정 화면에서 접속자 명령어 허용·게임 모드를 바꾸면 방 설정도 같은 값으로 맞춘다
    // (26.1은 랜을 연 뒤엔 그 화면에서 바꿀 수단이 없어 대상이 없다).
    //? if >=26.3 {
    /*@Inject(method = "setGuestCommandAccess", at = @At("TAIL"))
    private void kfcudp$syncGuestCheats(boolean allowCheats, CallbackInfo ci) {
        kfc.udp.client.KfcudpClient.syncGuestCheatsFromVanilla(allowCheats);
    }

    @Inject(method = "setWorldGameType", at = @At("TAIL"))
    private void kfcudp$syncGuestGameMode(net.minecraft.world.level.GameType gameMode, CallbackInfo ci) {
        kfc.udp.client.KfcudpClient.syncGuestGameModeFromVanilla(gameMode);
    }
    *///?}
    //? if >=26.2 <26.3 {
    /*@Inject(method = "setCommandsAllowedForOtherPlayers", at = @At("TAIL"))
    private void kfcudp$syncGuestCheats(boolean allowCheats, CallbackInfo ci) {
        kfc.udp.client.KfcudpClient.syncGuestCheatsFromVanilla(allowCheats);
    }

    @Inject(method = "setGameTypeForOtherPlayers", at = @At("TAIL"))
    private void kfcudp$syncGuestGameMode(net.minecraft.world.level.GameType gameMode, CallbackInfo ci) {
        kfc.udp.client.KfcudpClient.syncGuestGameModeFromVanilla(gameMode);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Inject(method = "getMaxPlayerCount", at = @At("HEAD"), cancellable = true)
    private void kfcudp$getMaxPlayerCount(CallbackInfoReturnable<Integer> cir) {
        int max = P2PBanManager.getRoomMaxPlayers();
        if (max > 0) cir.setReturnValue(max);
    }
    *///?}
}
