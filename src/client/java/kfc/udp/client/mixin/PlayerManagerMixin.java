package kfc.udp.client.mixin;

import com.mojang.authlib.GameProfile;
import kfc.udp.client.webrtc.P2PBanManager;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.NameAndId;
*///?} else {
import net.minecraft.server.PlayerManager;
//? if >=1.21.9
//import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.text.Text;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * 바닐라와 동일한 지점에서 밴을 검사한다.
 *
 * <p>{@code PlayerManager#checkCanJoin} 은 LOGIN 단계에서, 즉 {@code ServerPlayerEntity} 가
 * 생성되고 {@code onPlayerConnect} 가 호출되기 <b>전에</b> 불린다. 여기서 Text 를 반환하면
 * 클라이언트는 로그인 화면에서 바로 끊기므로
 * "xxx joined the game" / "xxx left the game" 채팅 로그가 전혀 남지 않는다.
 *
 * <p>{@code IntegratedPlayerManager} 가 이 메서드를 오버라이드하지만 내부에서 super 를
 * 호출하므로 리슨(LAN) 서버에서도 그대로 동작한다.
 */
//? if >=26.1 {
/*@Mixin(PlayerList.class)
*///?} else {
@Mixin(PlayerManager.class)
//?}
public abstract class PlayerManagerMixin {

    @Shadow
    public abstract MinecraftServer getServer();

    //? if >=26.1 {
    /*@Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void kfcudp$checkBanOnLogin(SocketAddress address, NameAndId configEntry,
                                        CallbackInfoReturnable<Component> cir) {
        // 1.21.9부터 checkCanJoin이 GameProfile 대신 PlayerConfigEntry(id+name만 있음)를 받는다.
        // P2PBanManager는 여전히 GameProfile 기준이라 최소 정보로 하나 합성해서 넘긴다.
        GameProfile profile = configEntry == null ? null : new GameProfile(configEntry.id(), configEntry.name());
        Component deny = P2PBanManager.checkCanJoin(this.getServer(), address, profile);
        if (deny != null) {
            cir.setReturnValue(deny);
        }
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*@Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void kfcudp$checkBanOnLogin(SocketAddress address, PlayerConfigEntry configEntry,
                                        CallbackInfoReturnable<Text> cir) {
        // 1.21.9부터 checkCanJoin이 GameProfile 대신 PlayerConfigEntry(id+name만 있음)를 받는다.
        // P2PBanManager는 여전히 GameProfile 기준이라 최소 정보로 하나 합성해서 넘긴다.
        GameProfile profile = configEntry == null ? null : new GameProfile(configEntry.id(), configEntry.name());
        Text deny = P2PBanManager.checkCanJoin(this.getServer(), address, profile);
        if (deny != null) {
            cir.setReturnValue(deny);
        }
    }
    *///?}
    //? if <1.21.9 {
    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void kfcudp$checkBanOnLogin(SocketAddress address, GameProfile profile,
                                        CallbackInfoReturnable<Text> cir) {
        Text deny = P2PBanManager.checkCanJoin(this.getServer(), address, profile);
        if (deny != null) {
            cir.setReturnValue(deny);
        }
    }
    //?}
}
