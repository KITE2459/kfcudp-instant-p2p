package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 공개 방 목록 — 새 서버 인프라 없이 기존 시그널링 relay의 lobby/peer 목록
 * 브로드캐스트를 재사용한다.
 * <p>
 * {@link WebRtcHost}가 방마다 여는 {@code /{roomId}} lobby(조인 감지용)와는
 * 별개로, 공개 방을 연 호스트는 전부 고정 경로
 * {@link P2PConfig#PUBLIC_ROOMS_LOBBY_ID} lobby에도 접속해서 peer 이름에
 * "초대 코드/방 제목/방장 닉네임"을 인코딩해 넣어 둔다. 방 목록 화면
 * ({@code RoomListScreen})은 그 lobby에 접속해서 현재 peer 목록(=지금 열려
 * 있는 공개 방들)만 읽어온다 — 호스트가 방을 닫으면 이 WebSocket 연결도
 * 끊어지므로 자동으로 목록에서 사라진다.
 * <p>
 * peer 이름은 URL 경로의 한 조각이라 임의 텍스트(한글 제목 등)를 안전하게
 * 못 담는다 — URL-safe Base64로 인코딩해서 "r" 접두사를 붙인다. 방 목록을
 * 훑어보기만 하는 {@code RoomListScreen} 쪽도 같은 lobby에 잠깐 접속하는데,
 * 그쪽은 "b" 접두사를 써서 서로 구분한다(관전자는 방이 아니므로 무시).
 */
final class PublicRoomAnnouncer {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-public");

    private static final long INITIAL_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS     = 30_000;

    private final ScheduledExecutorService scheduler =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "public-room-announce");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocketClient ws;
    private volatile long backoffMs = INITIAL_BACKOFF_MS;

    private volatile String roomCode;
    private volatile String title;
    private volatile String hostNickname;

    /** 방을 공개 목록에 올린다. 접속/재접속은 백그라운드에서 진행되며 즉시 반환. */
    void start(String roomCode, String title, String hostNickname) {
        if (!running.compareAndSet(false, true)) return;
        this.roomCode = roomCode;
        this.title = title;
        this.hostNickname = hostNickname;
        LOG.info("[public-room] announcing: code={} title={}", roomCode, title);
        connect();
    }

    void stop() {
        if (!running.compareAndSet(true, false)) return;
        LOG.info("[public-room] un-announcing");
        WebSocketClient w = ws;
        ws = null;
        if (w != null) w.close();
        scheduler.shutdownNow();
    }

    private void connect() {
        if (!running.get()) return;
        String peerName = "r" + encode(roomCode, title, hostNickname, P2PConfig.getRoomCategory());
        WebSocketClient client = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + P2PConfig.PUBLIC_ROOMS_LOBBY_ID + "/" + peerName) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS;
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                // 방 목록 쪽에서 peer 목록을 읽어가는 것뿐 — 여기선 받을 게 없다.
            }
            @Override public void onDisconnected() {
                scheduleReconnect();
            }
        };
        ws = client;
        try {
            client.connect();
        } catch (Exception e) {
            LOG.warn("[public-room] connect failed: {}", e.toString());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    /** "r"/"b" 다음에 오는 페이로드: code|title|nickname|category (UTF-8, URL-safe Base64, 패딩 없음). */
    static String encode(String code, String title, String nickname, int category) {
        String raw = code + "|" + sanitize(title) + "|" + sanitize(nickname) + "|" + category;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** {code, title, nickname, category} 또는 파싱 실패 시 null — category는 문자열 그대로 담아 둔다. */
    static String[] decode(String payload) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            return parts.length == 4 ? parts : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replace("|", " ");
    }

    /** 방 목록 화면이 자기 관전 세션을 구분하기 위한 무작위 "b" peer 이름. */
    static String randomBrowserPeerName() {
        return "b" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0x100000));
    }
}
