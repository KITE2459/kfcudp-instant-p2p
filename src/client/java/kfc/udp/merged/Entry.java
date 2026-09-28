package kfc.udp.merged;

import net.fabricmc.api.ClientModInitializer;

/** 통합 jar 의 진입점 — 이 마크 버전의 묶음에 든 KfcudpClient 로 넘긴다(Variants 주석). */
public final class Entry implements ClientModInitializer {
    @Override public void onInitializeClient() {
        try {
            ((ClientModInitializer) Class.forName("kfc.udp." + Variants.group() + ".client.KfcudpClient")
                    .getDeclaredConstructor().newInstance()).onInitializeClient();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("instant-p2p 초기화 실패", e);
        }
    }
}
