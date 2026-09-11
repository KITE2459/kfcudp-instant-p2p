package kfc.udp.client.mixin;

import com.mojang.brigadier.tree.CommandNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Predicate;

/**
 * Brigadier의 {@code CommandNode#requirement}는 {@code private final}이라,
 * {@code addChild()}로 병합될 때(바닐라가 이미 등록해 둔 "kick"/"ban"/"whitelist" 같은
 * 이름과 겹칠 때) 우리가 새로 등록하며 넣은 {@code .requires()}가 조용히 무시되고
 * 기존(바닐라) 쪽 requirement가 그대로 남는다 — Brigadier의 {@code addChild()}는
 * {@code command}와 {@code children}만 병합하고 {@code requirement}는 절대 안 건드린다.
 * (Brigadier는 unobfuscated 라이브러리라 버전 분기 불필요.)
 * <p>
 * {@code P2PBanManager}/{@code P2PWhitelistManager}가 명령어를 등록한 직후,
 * 실제 트리에 살아남은 노드({@code dispatcher.getRoot().getChild(name)})를
 * 이 accessor로 강제로 덮어써야 우리 {@code requires()}가 실제로 먹힌다.
 */
@Mixin(CommandNode.class)
public interface CommandNodeAccessor {

    @Mutable
    @Accessor("requirement")
    void kfcudp$setRequirement(Predicate<?> requirement);
}
