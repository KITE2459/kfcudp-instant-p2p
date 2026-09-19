package kfc.udp.client.mixin;

//? if >=26.1 {
/*import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
*///?} else {
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code IntegratedServer#forcedGameMode}는 {@code openToLan()}에서만 정해지는
 * private 필드라, 바닐라 Open to LAN이 이미 열려 있어 {@code openToLan()}을 다시
 * 부를 수 없는 경우(재바인드 실패) Custom Room에서 고른 게임모드를 적용할 방법이 없다.
 * 이 accessor로 직접 덮어쓴다. 필드명은 1.21.5~1.21.11 전 버전에서 동일해 분기 불필요.
 * <p>
 * <b>"방 닫기"가 랜 서버까지 완전히 닫아야 하는 이유</b> — {@code ServerNetworkIo#stop()}
 * (Yarn)/{@code ServerConnectionListener#stop()}(Mojang)은 새 접속만 막을 뿐, "Open to
 * LAN" 상태({@code isRemote()}/{@code isPublished()})나 로컬망에 계속 존재를 알리는
 * {@code LanServerPinger} 브로드캐스트는 안 건드린다. 그리고 이 상태는 단순히 "다른
 * 사람이 아직 들어올 수 있어 보인다"는 것만의 문제가 아니다 — {@code MinecraftClient}의
 * 매 프레임 일시정지 판정(바이트코드로 확인)이 정확히
 * {@code isIntegratedServerRunning() && (일시정지 화면 열림) && !server.isRemote()}
 * 라서, 이 상태가 안 풀리면 방을 닫아도 ESC를 눌렀을 때 게임이 더 이상 멈추지
 * 않는(싱글플레이 본연의) 동작이 영영 안 돌아온다.
 * <p>
 * 26.2는 바닐라 자체에 이 전체를 처리하는 {@code teardownPublishedState()}(pinger
 * 정지+null화, 포트 -1, {@code MultiplayerScope.OFF}, 명령어 권한 재동기화까지 다
 * 함)가 생겼지만 private이라 {@link Invoker}로 직접 불러 쓴다 — 그게 없는
 * 26.1 이하에서는 직접 조립한다: pinger는 원래 있던 {@code interrupt()}(public,
 * 재정의돼 있어 소켓도 같이 닫음)로 멈추고 필드를 null로, 포트 필드는 -1로 되돌려
 * {@code isRemote}/{@code isPublished}가 다시 false가 되게 한다(바이트코드로 검증 —
 * 이 두 값만으로 위 일시정지 판정이 완전히 결정됨. {@code MultiplayerScope}/명령어
 * 권한 재동기화는 26.2에만 있는 별개 개념이라 이전 버전엔 대응할 게 없다).
 */
@Mixin(IntegratedServer.class)
public interface IntegratedServerAccessor {

    // 26.3은 gameTypeForOtherPlayers 필드가 없어졌다 — 접속자 게임 모드는 IntegratedServerMaxPlayersMixin이 처리한다.
    //? if >=26.3 {
    /*@Invoker("teardownPublishedState")
    void kfcudp$teardownPublishedState();
    *///?}
    //? if >=26.2 <26.3 {
    /*@Mutable
    @Accessor("gameTypeForOtherPlayers")
    void kfcudp$setForcedGameMode(GameType gameMode);

    // teardownPublishedState()를 그대로 호출 — pinger 정지+null화, 포트 -1,
    // MultiplayerScope.OFF, 명령어 권한 재동기화까지 바닐라가 다 처리한다.
    @Invoker("teardownPublishedState")
    void kfcudp$teardownPublishedState();
    *///?}
    //? if <26.2 {
    //? if >=26.1 <26.2 {
    /*@Mutable
    @Accessor("publishedGameType")
    void kfcudp$setForcedGameMode(GameType gameMode);

    @Mutable
    @Accessor("publishedPort")
    void kfcudp$setLanPort(int port);

    @Accessor("lanPinger")
    net.minecraft.client.server.LanServerPinger kfcudp$getLanPinger();

    @Mutable
    @Accessor("lanPinger")
    void kfcudp$setLanPinger(net.minecraft.client.server.LanServerPinger pinger);
    *///?} else {
    @Mutable
    @Accessor("forcedGameMode")
    void kfcudp$setForcedGameMode(GameMode gameMode);

    @Mutable
    @Accessor("lanPort")
    void kfcudp$setLanPort(int port);

    @Accessor("lanPinger")
    net.minecraft.client.network.LanServerPinger kfcudp$getLanPinger();

    @Mutable
    @Accessor("lanPinger")
    void kfcudp$setLanPinger(net.minecraft.client.network.LanServerPinger pinger);
    //?}
    //?}
}
