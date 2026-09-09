package kfc.udp.client.mixin;

import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code IntegratedServer#forcedGameMode}는 {@code openToLan()}에서만 정해지는
 * private 필드라, 바닐라 Open to LAN이 이미 열려 있어 {@code openToLan()}을 다시
 * 부를 수 없는 경우(재바인드 실패) Custom Room에서 고른 게임모드를 적용할 방법이 없다.
 * 이 accessor로 직접 덮어쓴다. 필드명은 1.21.5~1.21.11 전 버전에서 동일해 분기 불필요.
 */
@Mixin(IntegratedServer.class)
public interface IntegratedServerAccessor {

    @Mutable
    @Accessor("forcedGameMode")
    void kfcudp$setForcedGameMode(GameMode gameMode);
}
