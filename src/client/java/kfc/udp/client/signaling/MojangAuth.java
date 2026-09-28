package kfc.udp.client.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 공개 방을 올리기 위한 모장 계정 확인.
 * <p>
 * 진짜 마인크래프트 서버가 쓰는 흐름 그대로다:
 * <ol>
 *   <li>서버에서 난수 challenge 를 받는다.</li>
 *   <li>{@code joinServer(uuid, accessToken, challenge)} 로 <b>모장에</b> 그 challenge 를 등록한다.</li>
 *   <li>서버에 닉네임 + challenge 를 보내면, 서버가 모장의 {@code hasJoined} 로 직접 확인하고
 *       내 UUID 에 묶인 게시 토큰을 내려준다.</li>
 * </ol>
 * 그 토큰을 공개 방 공지 연결의 쿼리 파라미터로 실어 보낸다({@link PublicRoomAnnouncer}) —
 * 방은 로비의 peer 존재로 만들어지므로 연결 시점에 막아야 하고, 그래서 메시지 형식은 안 건드린다.
 * <p>
 * <b>증명이 모드가 아니라 모장에서 온다</b>는 게 핵심이다. 모드를 고쳐 이 과정을 건너뛰면 토큰이
 * 없어 게시가 거부되고, 프로토콜을 뜯어 봇을 만들려면 실제 계정이 필요해 UUID 로 영구 밴할 수 있다.
 * <p>
 * 오프라인(크랙) 계정은 인증이 불가능하므로 <b>공개 방·중계를 쓸 수 없다</b> — 서버의 정품 인증 예외
 * 목록({@code offline-hosts.json})에 닉네임이 있을 때만 예외다(운영자 혼자 하는 실 테스트용). 그 스위치를
 * 클라이언트에 두면 그게 곧 우회 수단이 되므로 서버 쪽에만 있다.
 */
public final class MojangAuth {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-auth");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** 토큰을 미리 이만큼 남겨두고 갱신한다 — 공지 중에 만료되지 않게. */
    private static final long RENEW_BEFORE_MS = 30 * 60 * 1000L;

    private static volatile String token;
    private static volatile long expiresAtMs;
    /** 마지막 실패 이유 — 방을 못 올릴 때 사용자에게 보여줄 값. null 이면 문제 없음. */
    private static volatile String lastError;

    private MojangAuth() {}

    /** 지금 쓸 수 있는 토큰, 없으면 null. */
    public static String tokenOrNull() {
        String t = token;
        return (t != null && System.currentTimeMillis() < expiresAtMs) ? t : null;
    }

    public static String lastError() {
        return lastError;
    }

    // ── TURN 계정 ───────────────────────────────────────────────────────────

    private static volatile String[] turn;
    private static volatile long turnReuseUntilMs;
    /**
     * 받은 계정을 만료 몇 시간 전까지 쓸지. coturn 은 갱신 요청마다 만료를 다시 보므로, 판 도중에
     * 만료되면 중계가 그 자리에서 끊긴다 — 한 판 길이보다 넉넉히 남아 있을 때만 재사용한다.
     */
    private static final long TURN_REUSE_MARGIN_MS = 6 * 60 * 60 * 1000L;

    /**
     * 중계(TURN) 계정을 시그널링에서 받는다 — jar 에는 계정이 없다(서버 {@code turncreds.go} 주석).
     * <b>네트워크를 기다리므로 블로킹</b>이다. 받아 둔 계정이 충분히 남아 있으면 그대로 쓴다.
     * {@code -Dkfcudp.turn.user/pass} 가 있으면 그걸 쓴다(테스트용).
     *
     * @return {@code {user, pass}}, 못 받으면 null — 그러면 중계 없이(직결만) 간다.
     */
    public static synchronized String[] turnCredentials() {
        if (P2PConfig.TURN_USERNAME != null && P2PConfig.TURN_CREDENTIAL != null) {
            return new String[]{P2PConfig.TURN_USERNAME, P2PConfig.TURN_CREDENTIAL};
        }
        String[] c = turn;
        if (c != null && System.currentTimeMillis() < turnReuseUntilMs) return c;
        try {
            ensureToken(); // 실패해도 부른다 — 인증을 안 쓰는 서버면 토큰 없이도 준다
            HttpResponse<String> res = get(withToken(P2PConfig.SIGNALING_HTTP_URL + "/api/v1/turn/credentials"));
            if (res.statusCode() != 200) {
                LOG.warn("[auth] 중계 계정을 못 받았다(HTTP {}) — 중계 없이 진행한다{}", res.statusCode(),
                        res.statusCode() == 401 ? " (정품 인증 필요)" : "");
                return null;
            }
            String u = VillasMsg.field(res.body(), "username");
            String p = VillasMsg.field(res.body(), "password");
            if (u == null || u.isBlank() || p == null || p.isBlank()) return null;
            long exp = 0;
            try {
                exp = Long.parseLong(VillasMsg.field(res.body(), "expires"));
            } catch (Exception ignored) {}
            // 만료가 없는 계정(서버가 과도기 고정 계정을 줄 때)도 2시간마다 다시 물어본다 —
            // 서버가 임시 계정 방식으로 바뀌면 그걸 따라가야 한다.
            long now = System.currentTimeMillis();
            turnReuseUntilMs = exp > 0 ? exp * 1000L - TURN_REUSE_MARGIN_MS : now + 2 * 60 * 60 * 1000L;
            turn = new String[]{u, p};
            return turn;
        } catch (Exception e) {
            LOG.warn("[auth] 중계 계정 요청 실패 — 중계 없이 진행한다: {}", e.toString());
            return null;
        }
    }

    /** 방 목록·방 열기 화면에서 미리 받아 둔다 — 실제 접속 때 기다리지 않게. */
    public static void prefetchTurnAsync() {
        Thread t = new Thread(MojangAuth::turnCredentials, "instant-p2p-turn-creds");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 시그널링 WS 주소에 게시 토큰을 붙인다 — 토큰이 없으면 주소를 그대로 준다.
     * <p>
     * 서버는 연결 시점에 신원을 봐야 하므로(방은 로비의 peer 존재로 만들어진다) 토큰이 쿼리로
     * 간다. 방을 <b>올리는</b> 쪽과 <b>보는</b> 쪽 둘 다 붙여야 한다 — 공방을 테스터 한정으로
     * 돌릴 때 서버가 "이 사람에게 목록을 보여줄지"를 판단하는 근거가 이것뿐이다.
     */
    public static String withToken(String url) {
        String t = tokenOrNull();
        if (t == null) return url;
        return url + (url.indexOf('?') < 0 ? '?' : '&') + "token="
                + java.net.URLEncoder.encode(t, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 토큰을 확보한다(이미 있고 여유가 있으면 그대로 쓴다). <b>네트워크를 기다리므로 블로킹</b> —
     * 방을 여는 경로에서 부른다.
     *
     * @return 성공하면 토큰, 실패하면 null({@link #lastError}에 이유)
     */
    public static synchronized String ensureToken() {
        String t = token;
        if (t != null && System.currentTimeMillis() < expiresAtMs - RENEW_BEFORE_MS) return t;

        try {
            String challenge = fetchChallenge();
            if (challenge == null) return null;

            if (!registerWithMojang(challenge)) {
                // 오프라인 계정이어도 서버에 한 번 묻는다 — 서버의 정품 인증 예외 목록(offline-hosts.json)에
                // 있으면 토큰이 나온다(offline=1 이면 서버는 모장에 묻지 않는다). 아니면 401 이고, 보여줄 이유는 위의 것 그대로다.
                String why = lastError;
                String offlineToken = verify(challenge, true);
                if (offlineToken == null) lastError = why;
                return offlineToken;
            }
            return verify(challenge, false);
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            LOG.warn("[auth] 계정 확인 실패: {}", lastError);
            return null;
        }
    }

    private static String fetchChallenge() throws Exception {
        HttpResponse<String> res = get(P2PConfig.SIGNALING_HTTP_URL + "/api/v1/auth/challenge");
        if (res.statusCode() == 503) {
            // 서버가 인증을 안 쓰는 상태 — 토큰 없이도 게시가 된다. 조용히 넘어간다.
            lastError = null;
            LOG.debug("[auth] 서버가 인증을 쓰지 않는다 — 토큰 없이 진행");
            return null;
        }
        if (res.statusCode() != 200) {
            lastError = "challenge 발급 실패(" + res.statusCode() + ")";
            return null;
        }
        String c = VillasMsg.field(res.body(), "challenge");
        if (c == null || c.isBlank()) {
            lastError = "challenge 응답 형식 오류";
            return null;
        }
        return c;
    }

    /** 이 challenge 로 모장에 joinServer 를 등록한다 — 서버가 hasJoined 로 확인할 수 있게. */
    private static boolean registerWithMojang(String challenge) {
        UUID id = sessionUuid();
        String accessToken = sessionAccessToken();
        if (id == null || accessToken == null || accessToken.isBlank()) {
            lastError = "정품 계정으로 로그인되어 있지 않습니다";
            return false;
        }
        try {
            joinServer(id, accessToken, challenge);
            return true;
        } catch (Exception e) {
            // 오프라인 계정·토큰 만료 등. 사용자에게 보여줄 문구는 위에서 정해진다.
            lastError = "모장 서버에 계정 확인을 등록하지 못했습니다";
            LOG.debug("[auth] joinServer 실패: {}", e.toString());
            return false;
        }
    }

    private static String verify(String challenge, boolean offline) throws Exception {
        String name = sessionName();
        if (name == null || name.isBlank()) {
            lastError = "닉네임을 알 수 없습니다";
            return null;
        }
        HttpResponse<String> res = get(P2PConfig.SIGNALING_HTTP_URL + "/api/v1/auth/verify"
                + "?username=" + enc(name) + "&challenge=" + enc(challenge) + (offline ? "&offline=1" : ""));
        if (res.statusCode() == 403) {
            lastError = "이 계정은 공개 방 등록이 차단되었습니다";
            return null;
        }
        if (res.statusCode() != 200) {
            lastError = "계정 확인 실패(" + res.statusCode() + ")";
            return null;
        }
        String t = VillasMsg.field(res.body(), "token");
        if (t == null || t.isBlank()) {
            lastError = "토큰 응답 형식 오류";
            return null;
        }
        long ttl = 0;
        try {
            ttl = Long.parseLong(VillasMsg.field(res.body(), "expires_in"));
        } catch (Exception ignored) {}
        if (ttl <= 0) ttl = 3600;

        token = t;
        expiresAtMs = System.currentTimeMillis() + ttl * 1000L;
        lastError = null;
        // UUID·토큰은 로그에 남기지 않는다.
        LOG.info("[auth] 계정 확인 완료 — 공개 방 등록 가능 ({}분)", ttl / 60);
        return t;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── 버전별로 갈리는 것만 아래로 모은다 ──────────────────────────────────

    //? if >=26.1 {
    /*private static UUID sessionUuid() {
        return net.minecraft.client.Minecraft.getInstance().getUser().getProfileId();
    }

    private static String sessionAccessToken() {
        return net.minecraft.client.Minecraft.getInstance().getUser().getAccessToken();
    }

    private static String sessionName() {
        return net.minecraft.client.Minecraft.getInstance().getUser().getName();
    }

    private static void joinServer(UUID id, String accessToken, String challenge) throws Exception {
        net.minecraft.client.Minecraft.getInstance().services().sessionService()
                .joinServer(id, accessToken, challenge);
    }
    *///?}
    //? if >=1.21.9 <26.1 {
    /*private static UUID sessionUuid() {
        return net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
    }

    private static String sessionAccessToken() {
        return net.minecraft.client.MinecraftClient.getInstance().getSession().getAccessToken();
    }

    private static String sessionName() {
        return net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername();
    }

    // getSessionService()가 1.21.9에서 getApiServices().sessionService()로 옮겨갔다
    // (BlockedPlayersScreen.fetchProfileAsync 와 같은 경계).
    private static void joinServer(UUID id, String accessToken, String challenge) throws Exception {
        net.minecraft.client.MinecraftClient.getInstance().getApiServices().sessionService()
                .joinServer(id, accessToken, challenge);
    }
    *///?}
    //? if <1.21.9 {
    private static UUID sessionUuid() {
        return net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
    }

    private static String sessionAccessToken() {
        return net.minecraft.client.MinecraftClient.getInstance().getSession().getAccessToken();
    }

    private static String sessionName() {
        return net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername();
    }

    private static void joinServer(UUID id, String accessToken, String challenge) throws Exception {
        net.minecraft.client.MinecraftClient.getInstance().getSessionService()
                .joinServer(id, accessToken, challenge);
    }
    //?}
}
