import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * 버전별 jar 들(1.21.x·26.x 전부)을 한 jar 로 합친다.
 * <p>
 * 버전마다 같은 클래스 이름에 다른 코드가 들어 있으므로(Stonecutter 분기), 컴파일 결과가 같은 버전끼리
 * 묶음(g0, g1, …)으로 모은 뒤 묶음마다 패키지를 옮겨 담는다:
 * <pre>
 *   kfc/udp/client/mixin/**  →  kfc/udp/mixins/gN/**   (믹스인 — 묶음마다 설정·refmap 하나씩)
 *   kfc/udp/**               →  kfc/udp/gN/**           (나머지)
 *   kfc/udp/merged/**        →  그대로                    (런타임에 묶음을 고르는 코드, 한 벌)
 * </pre>
 * 번들된 라이브러리(kwik 등)·에셋은 한 벌만 넣는다. 어느 버전이 어느 묶음인지는
 * instant-p2p-variants.properties 에 적고, kfc.udp.merged.Variants 가 그걸 읽는다.
 *
 * 사용: java -cp asm.jar:asm-commons.jar MergeJars.java out.jar 1.21=a.jar 1.21.1=b.jar ...
 */
public class MergeJars {
    static final String MIXIN_JSON = "instant-p2p.client.mixins.json";

    record Variant(String mc, Map<String, byte[]> entries, String mixinJson, String refmapName) {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<Variant> variants = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            Map<String, byte[]> e = read(Path.of(kv[1]));
            String mj = new String(e.get(MIXIN_JSON), StandardCharsets.UTF_8);
            variants.add(new Variant(kv[0], e, mj, field(mj, "refmap")));
        }

        // 묶음: 우리 클래스 + 믹스인 목록 + refmap 이 같으면 같은 묶음
        Map<String, List<Variant>> groups = new LinkedHashMap<>();
        for (Variant v : variants) groups.computeIfAbsent(groupKey(v), k -> new ArrayList<>()).add(v);

        Map<String, byte[]> merged = new TreeMap<>();
        Properties map = new Properties();
        List<String> configs = new ArrayList<>();
        int n = 0;
        for (List<Variant> members : groups.values()) {
            String g = "g" + n++;
            Variant v = members.get(0);
            for (Variant m : members) map.setProperty(m.mc(), g);
            map.setProperty(g + ".mixins", String.join(",", list(v.mixinJson(), "client")));

            Remapper r = new Remapper() {
                @Override public String map(String name) { return relocate(name, g); }
            };
            for (var e : v.entries().entrySet()) {
                String name = e.getKey();
                if (!ours(name)) continue;
                ClassReader cr = new ClassReader(e.getValue());
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassRemapper(cw, r), 0);
                merged.put(relocate(name.substring(0, name.length() - ".class".length()), g) + ".class", cw.toByteArray());
            }

            // 묶음 믹스인 설정: 목록은 비우고 플러그인이 고른다(MixinSelect). refmap 은 키(믹스인 이름)만 옮긴다.
            String cfg = "instant-p2p." + g + ".mixins.json";
            String json = v.mixinJson()
                    .replace("\"kfc.udp.client.mixin\"", "\"kfc.udp.mixins." + g + "\"")
                    .replaceFirst("\"client\"\\s*:\\s*\\[[^\\]]*\\]", "\"client\": [], \"plugin\": \"kfc.udp.merged.MixinSelect\"");
            if (v.refmapName() != null) {
                String refName = "instant-p2p-" + g + "-refmap.json";
                String ref = new String(v.entries().get(v.refmapName()), StandardCharsets.UTF_8)
                        .replace("kfc/udp/client/mixin/", "kfc/udp/mixins/" + g + "/");
                merged.put(refName, ref.getBytes(StandardCharsets.UTF_8));
                json = json.replace("\"" + v.refmapName() + "\"", "\"" + refName + "\"");
            }
            merged.put(cfg, json.getBytes(StandardCharsets.UTF_8));
            configs.add(cfg);
            System.out.println("  " + g + ": " + members.stream().map(Variant::mc).toList());
        }

        // 공유 항목: 번들 라이브러리·에셋·런타임 선택 코드 — 앞 jar 가 우선이다. 입력은 1.21.x 가 먼저 오므로
        // kfc/udp/merged 는 Java 21 바이트코드가 들어간다(26.x 는 Java 25 라 1.21.x 에서 못 읽는다).
        // kwik 등은 시대마다 바이트가 조금 다르지만(1.21.x 는 Loom remapJar 를 한 번 거친다) 같은 0.11 이다.
        // module-info 는 뺀다 — 클래스패스 jar 에선 쓰이지 않는다.
        Variant first = variants.get(0);
        for (Variant v : variants) {
            for (var e : v.entries().entrySet()) {
                String name = e.getKey();
                if (ours(name) || name.equals(MIXIN_JSON) || name.endsWith("-refmap.json") || name.endsWith("module-info.class")
                        || name.equals("fabric.mod.json") || name.equals("META-INF/MANIFEST.MF") || name.endsWith("/")) continue;
                if (name.startsWith("LICENSE_")) name = "LICENSE"; // 빌드가 버전 이름을 붙인 같은 파일 17벌 → 하나
                byte[] had = merged.putIfAbsent(name, e.getValue());
                if (had != null && name.startsWith("assets/") && !Arrays.equals(had, e.getValue())) {
                    System.out.println("  경고: " + name + " 이 버전마다 다르다(" + v.mc() + ") — 앞 jar 것을 쓴다");
                }
            }
        }
        byte[] entry = merged.get("kfc/udp/merged/Entry.class");
        if (entry == null || entry[7] > 65) throw new IllegalStateException("런타임 선택 코드가 Java 21 바이트코드가 아니다 — 1.21.x jar 를 먼저 넘길 것");

        ByteArrayOutputStream props = new ByteArrayOutputStream();
        map.store(props, "instant-p2p: minecraft version -> group");
        merged.put("instant-p2p-variants.properties", props.toByteArray());
        merged.put("fabric.mod.json", fabricModJson(variants, configs).getBytes(StandardCharsets.UTF_8));

        Manifest mf = new Manifest(new java.io.ByteArrayInputStream(first.entries().get("META-INF/MANIFEST.MF")));
        // 특정 버전·빌드에 묶인 표시는 뺀다(Fabric-Mapping-Namespace 는 개발 환경에서만 쓰인다 — FabricLoaderImpl.setup).
        for (String k : List.of("Fabric-Minecraft-Version", "Fabric-Loom-Client-Only-Entries", "Stonecutter-Node-Project", "Stonecutter-Node-Version")) {
            mf.getMainAttributes().remove(new Attributes.Name(k));
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(out), mf)) {
            for (var e : merged.entrySet()) {
                jo.putNextEntry(new ZipEntry(e.getKey()));
                jo.write(e.getValue());
                jo.closeEntry();
            }
        }
        System.out.println("  → " + out + " (" + Files.size(out) / 1024 + "KB, 묶음 " + groups.size() + "개)");
    }

    /** kfc/udp 아래 클래스 중 옮길 것(런타임 선택 코드 kfc/udp/merged 는 제외). */
    static boolean ours(String name) {
        return name.startsWith("kfc/udp/") && !name.startsWith("kfc/udp/merged/") && name.endsWith(".class");
    }

    static String relocate(String internal, String g) {
        if (internal.startsWith("kfc/udp/client/mixin/")) return "kfc/udp/mixins/" + g + "/" + internal.substring("kfc/udp/client/mixin/".length());
        if (internal.startsWith("kfc/udp/") && !internal.startsWith("kfc/udp/merged/")) return "kfc/udp/" + g + "/" + internal.substring("kfc/udp/".length());
        return internal;
    }

    static String groupKey(Variant v) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (var e : new TreeMap<>(v.entries()).entrySet()) {
            if (!ours(e.getKey())) continue;
            md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            md.update(e.getValue());
        }
        md.update(String.join(",", list(v.mixinJson(), "client")).getBytes(StandardCharsets.UTF_8));
        if (v.refmapName() != null) md.update(v.entries().get(v.refmapName()));
        return HexFormat.of().formatHex(md.digest());
    }

    /** 첫 jar 의 fabric.mod.json 에서 진입점·믹스인·마크 버전·로더 버전만 바꾼다. */
    static String fabricModJson(List<Variant> variants, List<String> configs) {
        String json = new String(variants.get(0).entries().get("fabric.mod.json"), StandardCharsets.UTF_8);
        String loader = variants.stream()
                .map(v -> field(new String(v.entries().get("fabric.mod.json"), StandardCharsets.UTF_8), "fabricloader"))
                .max((a, b) -> compare(versionKey(a), versionKey(b))).orElseThrow();
        StringBuilder mixins = new StringBuilder("\"mixins\": [");
        for (int i = 0; i < configs.size(); i++) {
            mixins.append(i == 0 ? "" : ",").append("\n\t\t{ \"config\": \"").append(configs.get(i)).append("\", \"environment\": \"client\" }");
        }
        mixins.append("\n\t]");
        String mcs = String.join(", ", variants.stream().map(v -> "\"" + v.mc() + "\"").toList());
        return json
                .replace("\"kfc.udp.client.KfcudpClient\"", "\"kfc.udp.merged.Entry\"")
                .replaceFirst("(?s)\"mixins\"\\s*:\\s*\\[.*?\\]", Matcher.quoteReplacement(mixins.toString()))
                .replaceFirst("\"minecraft\"\\s*:\\s*\"[^\"]*\"", Matcher.quoteReplacement("\"minecraft\": [" + mcs + "]"))
                .replaceFirst("\"fabricloader\"\\s*:\\s*\"[^\"]*\"", Matcher.quoteReplacement("\"fabricloader\": \"" + loader + "\""));
    }

    static List<Integer> versionKey(String s) {
        List<Integer> k = new ArrayList<>();
        for (String p : s.replaceAll("[^0-9.]", "").split("\\.")) if (!p.isEmpty()) k.add(Integer.parseInt(p));
        return k;
    }

    static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0, y = i < b.size() ? b.get(i) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static List<String> list(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        List<String> out = new ArrayList<>();
        if (!m.find()) return out;
        Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (s.find()) out.add(s.group(1));
        return out;
    }

    static Map<String, byte[]> read(Path jar) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
            Manifest mf = in.getManifest();
            if (mf != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                mf.write(b);
                m.put("META-INF/MANIFEST.MF", b.toByteArray());
            }
            for (JarEntry e; (e = in.getNextJarEntry()) != null; ) m.put(e.getName(), in.readAllBytes());
        }
        return m;
    }
}
