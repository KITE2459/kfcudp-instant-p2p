package kfc.udp.merged;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 통합 jar 는 묶음마다 믹스인 설정이 하나씩 있고(kfc.udp.mixins.gN, 각자 refmap), 목록은 비어 있다.
 * 이 플러그인이 <b>지금 마크 버전의 묶음일 때만</b> 믹스인을 내놓는다 — 다른 묶음 것은 로드조차 안 된다
 * (버전이 다른 대상에 걸면 크래시다).
 */
public final class MixinSelect implements IMixinConfigPlugin {
    private List<String> mine = List.of();

    @Override public void onLoad(String mixinPackage) {
        String g = mixinPackage.substring(mixinPackage.lastIndexOf('.') + 1);
        if (g.equals(Variants.group())) mine = Variants.mixins(g);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return mine; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
