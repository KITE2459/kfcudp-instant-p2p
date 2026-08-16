package kfc.udp.client.mixin;

import com.mojang.authlib.GameProfile;
import kfc.udp.client.webrtc.P2PBanManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
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
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Shadow
    public abstract MinecraftServer getServer();

    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void kfcudp$checkBanOnLogin(SocketAddress address, GameProfile profile,
                                        CallbackInfoReturnable<Text> cir) {
        Text deny = P2PBanManager.checkCanJoin(this.getServer(), address, profile);
        if (deny != null) {
            cir.setReturnValue(deny);
        }
    }
}
