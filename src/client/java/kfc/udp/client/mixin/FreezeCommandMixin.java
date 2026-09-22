package kfc.udp.client.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import kfc.udp.client.webrtc.FreezeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=26.1 {
/*import net.minecraft.commands.CommandSourceStack;
*///?} else {
import net.minecraft.server.command.ServerCommandSource;
//?}

/**
 * 얼어있는 플레이어는 명령어를 전혀 실행 못 하게 막는다 — 치트/오피가 있으면 /gamemode 한 번으로
 * 관전 모드를 빠져나가 동결(FreezeManager) 자체가 무의미해지기 때문. Brigadier의 CommandDispatcher는
 * 마인크래프트가 아니라 별도 라이브러리 클래스라 버전(Yarn/Mojang 매핑)을 안 타서, 여기 하나만
 * 막으면 바닐라 명령어든 이 모드가 등록한 명령어든 전부 걸린다 — 각 명령어마다 따로 막을 필요 없음.
 * <p>
 * execute(String, S)는 내부적으로 execute(parse(input, source))를 호출하므로, ParseResults 오버로드
 * 하나만 가로채면 두 진입 경로 다 잡힌다.
 * <p>
 * <b>CommandDispatcher는 이 서버 명령어용 하나만 있는 게 아니다</b> — Fabric API의 클라이언트 전용
 * 명령어(fabric-command-api-v2, ClientCommandInternals)도 자기만의 별도 CommandDispatcher&lt;다른 소스
 * 타입&gt;을 갖고 있는데, mixin은 제네릭 타입 파라미터를 구분 못 하고 CommandDispatcher 클래스 전체에
 * 걸린다. 그래서 source를 곧장 CommandSourceStack으로 캐스팅하면 클라이언트 명령어 디스패처를 통해
 * 들어온 호출에서 ClassCastException이 나 채팅으로 친 명령어가 서버까지 가지도 못하고 죽는다
 * (`/ban`이 먹통이 됐던 원인 — instanceof로 걸러야 한다).
 */
@Mixin(value = CommandDispatcher.class, remap = false)
public abstract class FreezeCommandMixin {

    //? if >=26.1 {
    /*@Inject(method = "execute(Lcom/mojang/brigadier/ParseResults;)I", at = @At("HEAD"), cancellable = true)
    private void kfcudp$blockFrozenCommand(ParseResults<?> parse, CallbackInfoReturnable<Integer> cir) {
        Object source = parse.getContext().getSource();
        if (!(source instanceof CommandSourceStack src)) return;
        if (FreezeManager.shouldBlockCommand(src.getPlayer(), src.getServer(), parse.getReader().getString())) {
            cir.setReturnValue(0);
        }
    }
    *///?} else {
    @Inject(method = "execute(Lcom/mojang/brigadier/ParseResults;)I", at = @At("HEAD"), cancellable = true)
    private void kfcudp$blockFrozenCommand(ParseResults<?> parse, CallbackInfoReturnable<Integer> cir) {
        Object source = parse.getContext().getSource();
        if (!(source instanceof ServerCommandSource src)) return;
        if (FreezeManager.shouldBlockCommand(src.getPlayer(), src.getServer(), parse.getReader().getString())) {
            cir.setReturnValue(0);
        }
    }
    //?}
}
