package kfc.udp.client.webrtc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 개발자·서포터·방송인 UUID 목록 — mc-signaling의 {@code GET /api/v1/roles}에서 받아온다.
 * 예전엔 DevBadge 클래스에 UUID가 하드코딩돼 있어서 한 명 추가하려면 모드를 다시 빌드·배포해야
 * 했다 — 이제 운영자가 서버의 roles.json만 SSH로 고치면(재시작도 필요 없다, mc-signaling이
 * 요청마다 파일을 새로 읽는다) 다음 새로고침 때 바로 반영된다.
 * <p>
 * DevNameMixin/DevBadgeMixin이 이름 하나 그릴 때마다 {@link #isDev}/{@link #isSupporter}를
 * 부르지만(매 프레임 가능) 이건 그냥 메모리 Set.contains라 네트워크와 무관 — 실제 새로고침은
 * 주기적 타이머가 아니라 {@link #refreshAsync()} 호출로만 일어난다. 배지가 실제로 쓰이는 시점은
 * instant-p2p 방을 열거나(WebRtcBridge.startHost) 들어갈 때(WebRtcBridge.start)뿐이라, 그 두
 * 지점에서만 새로고침을 걸어 방을 안 켜고 있는 동안은 네트워크를 아예 안 탄다.
 * <p>
 * 로컬 파일 캐시는 일부러 안 둔다 — 새로고침이 실패해도(성공했을 때만 덮어쓰므로) 그 세션
 * 안에서는 마지막 성공값이 메모리에 그대로 남아 있어서, 파일로 남기는 이득은 "이번 실행에서
 * 첫 새로고침이 하필 실패하는" 딱 그 경우뿐이다(다음 방을 열면 곧 복구된다) — 그 정도를 아끼자고
 * 로컬에 사람이 편집 가능한 파일을 남기고 싶지 않다는 게 이 설계의 요지.
 */
public final class Roles {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-roles");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /**
     * mc-signaling의 개인키(-roles-sign-key)로 서명한 응답만 믿는다 — SIGNALING_URL 기본값이
     * ws://(평문)라 이 REST 호출도 평문 http라서, 같은 네트워크의 중간자가 응답을 가로채 자기
     * UUID를 dev/streamer로 끼워 넣을 수 있다. 대칭키(HMAC)로는 못 막는다 — 클라이언트에 박아 넣는
     * 값은 jar를 풀면 누구나 꺼낼 수 있어 "비밀"이 아니기 때문. 그래서 개인키는 서버에만 두고
     * 공개키만 여기 박아, 서명 없는(혹은 검증 실패한) 응답은 통째로 버린다(이전 캐시 값 유지).
     * 서버가 아직 서명 안 하면(개인키 미설정) 이 검증에 걸려 새 값이 전혀 반영되지 않으니 —
     * roles.json을 실제로 쓰려면 서버에도 -roles-sign-key를 설정해야 한다.
     */
    private static final String PUBLIC_KEY_B64 = "bBrZee5a/YT/UyoXRY5DiHAhvRGfaeKtOekGVBhMEeo=";
    private static final byte[] ED25519_SPKI_PREFIX =
            {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final PublicKey SIGNING_KEY = loadPublicKey();

    private static volatile Set<UUID> dev = Set.of();
    private static volatile Set<UUID> supporter = Set.of();
    private static volatile Set<UUID> streamer = Set.of();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-roles");
        t.setDaemon(true);
        return t;
    });

    private Roles() {}

    public static boolean isDev(UUID id) {
        return id != null && dev.contains(id);
    }

    public static boolean isSupporter(UUID id) {
        return id != null && supporter.contains(id);
    }

    public static boolean isStreamer(UUID id) {
        return id != null && streamer.contains(id);
    }

    /** 개발자 또는 서포터 — 방 정원 무시 같은 기존 DevBadge 특혜 대상. */
    public static boolean hasBadge(UUID id) {
        return isDev(id) || isSupporter(id);
    }

    /** instant-p2p 방을 열거나(WebRtcBridge.startHost) 들어갈 때(WebRtcBridge.start)만 부른다 —
     * 그 외엔 배지가 어차피 안 쓰이니 네트워크를 탈 이유가 없다. 백그라운드 스레드에서 돌고 즉시
     * 리턴하므로 호출부를 막지 않는다. */
    public static void refreshAsync() {
        EXECUTOR.execute(Roles::refreshNow);
    }

    /** 새로고침 결과가 <b>실제로 달라졌을 때만</b> onChanged를 부른다 — 방장이 접속자에게 등급을
     * 다시 뿌리는 데 쓴다(RoomRoles 참고). 콜백은 이 클래스의 전용 스레드에서 돌므로, 서버/클라
     * 상태를 만지려면 호출부가 알맞은 스레드로 넘겨야 한다. */
    public static void refreshAsync(Runnable onChanged) {
        EXECUTOR.execute(() -> {
            if (refreshNow()) onChanged.run();
        });
    }

    /** @return 목록이 실제로 바뀌었으면 true(실패·무변화는 false). */
    private static boolean refreshNow() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.SIGNALING_HTTP_URL + "/api/v1/roles"))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("[roles] fetch failed: HTTP {}", resp.statusCode());
                return false;
            }
            String sigB64 = resp.headers().firstValue("X-Roles-Signature").orElse(null);
            if (!verifySignature(resp.body(), sigB64)) {
                LOG.warn("[roles] response signature missing or invalid, ignoring (possible tampering)");
                return false;
            }
            return apply(resp.body());
        } catch (Exception e) {
            LOG.warn("[roles] fetch failed: {}", e.getMessage());
            return false;
        }
    }

    /** @return 세 목록 중 하나라도 실제로 달라졌으면 true. */
    private static boolean apply(String json) {
        JsonObject o;
        try {
            o = GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            LOG.warn("[roles] malformed response, keeping previous values: {}", e.getMessage());
            return false;
        }
        if (o == null) return false;
        Set<UUID> newDev = parseUuids(o, "dev");
        Set<UUID> newSupporter = parseUuids(o, "supporter");
        Set<UUID> newStreamer = parseUuids(o, "streamer");
        boolean changed = !newDev.equals(dev) || !newSupporter.equals(supporter) || !newStreamer.equals(streamer);
        dev = newDev;
        supporter = newSupporter;
        streamer = newStreamer;
        if (changed) {
            LOG.info("[roles] updated: dev={} supporter={} streamer={}", dev.size(), supporter.size(), streamer.size());
        }
        return changed;
    }

    private static PublicKey loadPublicKey() {
        try {
            byte[] raw = Base64.getDecoder().decode(PUBLIC_KEY_B64);
            byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception e) {
            LOG.error("[roles] failed to load embedded signing public key: {}", e.getMessage());
            return null;
        }
    }

    private static boolean verifySignature(String body, String sigB64) {
        if (sigB64 == null || SIGNING_KEY == null) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(SIGNING_KEY);
            verifier.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            LOG.warn("[roles] signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private static Set<UUID> parseUuids(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return Set.of();
        Set<UUID> out = new HashSet<>();
        for (var el : o.getAsJsonArray(key)) {
            try {
                out.add(UUID.fromString(el.getAsString()));
            } catch (Exception ignored) {
                // 잘못 적힌 UUID 한 줄 때문에 나머지 목록까지 버리지 않는다.
            }
        }
        return Set.copyOf(out);
    }
}
