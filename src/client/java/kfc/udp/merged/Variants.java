package kfc.udp.merged;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.util.Properties;

/**
 * 시대별 통합 jar 에서 "지금 마크 버전에 맞는 묶음" 을 고른다.
 * <p>
 * 통합 jar 는 버전별 빌드를 묶음(g0, g1, …)으로 패키지를 옮겨 한 jar 에 담는다 — 같은 클래스 이름이
 * 버전마다 다른 코드라서다. 어느 버전이 어느 묶음인지는 병합 때 만든 {@code instant-p2p-variants.properties}
 * 에 있다. 버전별 jar 에는 이 파일이 없고 이 클래스도 쓰이지 않는다.
 */
public final class Variants {

    private static volatile String group;

    private Variants() {}

    /** 이 마크 버전의 묶음 이름(예: g2). 목록에 없으면 예외 — fabric.mod.json 이 먼저 막지만 한 번 더. */
    public static String group() {
        String g = group;
        if (g != null) return g;
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow().getMetadata().getVersion().getFriendlyString();
        Properties p = new Properties();
        try (InputStream in = Variants.class.getClassLoader().getResourceAsStream("instant-p2p-variants.properties")) {
            if (in == null) throw new IllegalStateException("instant-p2p-variants.properties 가 없다");
            p.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        g = p.getProperty(mc);
        if (g == null) throw new IllegalStateException("instant-p2p 가 지원하지 않는 마인크래프트 버전: " + mc);
        group = g;
        return g;
    }

    /** 그 묶음의 믹스인 이름들(그 묶음 설정 패키지 기준, 예: ConnectScreenMixin). */
    static java.util.List<String> mixins(String g) {
        Properties p = new Properties();
        try (InputStream in = Variants.class.getClassLoader().getResourceAsStream("instant-p2p-variants.properties")) {
            p.load(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String m : p.getProperty(g + ".mixins", "").split(",")) {
            if (!m.isBlank()) out.add(m.trim());
        }
        return out;
    }
}
