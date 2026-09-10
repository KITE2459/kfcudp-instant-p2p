package kfc.udp.client.mixin;

//? if >=26.1 {
/*import net.minecraft.server.players.PlayerList;
*///?} else {
import net.minecraft.server.PlayerManager;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code PlayerManager#maxPlayers}는 생성자에서만 정해지는 {@code final} 필드라
 * 바닐라 API로는 room 열기 이후 정원을 바꿀 수 없다. Open to LAN이 이 필드를
 * 건드리지 않으므로(IntegratedServer 기본값 8 그대로) Custom Room에서 고른
 * 정원을 실제로 적용하려면 이 accessor로 직접 덮어써야 한다.
 * <p>
 * 1.21.9부터 이 필드 자체가 사라졌다 — {@code IntegratedServer#getMaxPlayerCount()}가
 * 8을 하드코딩해서 반환하는 방식으로 바뀌었으므로 그쪽은
 * {@link IntegratedServerMaxPlayersMixin}이 대신 처리한다.
 */
//? if >=26.1 {
/*@Mixin(PlayerList.class)
*///?} else {
@Mixin(PlayerManager.class)
//?}
public interface PlayerManagerAccessor {

    //? if <1.21.9 {
    @Mutable
    @Accessor("maxPlayers")
    void kfcudp$setMaxPlayers(int maxPlayers);
    //?}
}
