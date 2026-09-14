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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 공개 방 목록 — 새 서버 인프라 없이 기존 시그널링 relay의 lobby/peer 목록
 * 브로드캐스트를 재사용한다.
 * <p>
 * {@link WebRtcHost}가 방마다 여는 {@code /{roomId}} lobby(조인 감지용)와는
 * 별개로, 공개 방을 연 호스트는 방 코드로 결정되는 샤드 lobby
 * ({@link P2PConfig#publicRoomsLobbyId(int)}, {@link P2PConfig#publicRoomShardFor(String)}
 * 참고 — 샤딩하는 이유는 그쪽 클래스 주석에)에도 접속해서 peer 이름에
 * "초대 코드/방 제목/방장 닉네임/인원"을 인코딩해 넣어 둔다. 방 목록 화면
 * ({@link PublicRoomBrowser})은 모든 샤드 lobby에 동시 접속해서 현재 peer
 * 목록(=지금 열려 있는 공개 방들)을 합쳐 읽어온다 — 호스트가 방을 닫으면
 * 이 WebSocket 연결도 끊어지므로 자동으로 목록에서 사라진다.
 * <p>
 * peer 이름은 URL 경로의 한 조각이라 임의 텍스트(한글 제목 등)를 안전하게
 * 못 담는다 — URL-safe Base64로 인코딩해서 "r" 접두사를 붙인다. 방 목록을
 * 훑어보기만 하는 {@code RoomListScreen} 쪽도 같은 lobby에 잠깐 접속하는데,
 * 그쪽은 "b" 접두사를 써서 서로 구분한다(관전자는 방이 아니므로 무시).
 * <p>
 * <b>인원(현재/최대) 표기 — 왜 이벤트+디바운스인가</b> — peer 이름은 접속 시점에
 * 고정이라 인원이 바뀔 때마다 반영하려면 재접속해야 하고, 재접속은 그 샤드
 * lobby 전원에게 다시 브로드캐스트가 나간다. 방 등록/해제(각 방 생애주기당
 * 1번뿐)와 달리 사람 들고남은 방 하나가 열려 있는 동안 훨씬 잦을 수 있어서,
 * 인원이 바뀔 때마다 그대로 재접속하면 등록/해제보다 부하가 커진다. 그래서
 * {@link #updatePlayerCount(int, int)}는 매번 즉시 재접속하지 않고
 * {@link #DEBOUNCE_MS} 간격으로만 실제로 재접속한다 — 그 사이 인원이 여러 번
 * 바뀌어도 마지막 값 하나로 합쳐지고, 디바운스가 발동하는 시점에 최종 값이
 * 직전에 발표한 값과 같으면(예: 들어왔다 바로 나가서 도로 원래 인원) 그마저도
 * 재접속을 안 한다.
 * <p>
 * <b>스레드</b> — 접속(TCP+핸드셰이크, 서버가 느리면 수 초)은 전부 {@link #scheduler}
 * 스레드 하나에서만 한다. 예전엔 {@code start()}/{@code republishNow()}가 부른 쪽
 * 스레드에서 곧장 접속해서, 방을 열거나 밴할 때 게임 렌더/서버 스레드가 최대 수십 초
 * 멈출 수 있었다. 이 인스턴스는 WebRtcBridge의 싱글턴이라 scheduler를 절대 종료하지
 * 않는다 — 예전엔 {@code stop()}에서 종료해서, 방을 한 번 닫거나 방 설정을 한 번
 * 적용(unpublish→publish)한 뒤로는 인원 갱신·재접속 예약이 전부 거부됐다.
 */
final class PublicRoomAnnouncer {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-public");

    private static final long INITIAL_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS     = 30_000;

    /** 클래스 주석 참고 — 인원 변경 재발행의 최소 간격. */
    private static final long DEBOUNCE_MS = 5_000;

    private final ScheduledExecutorService scheduler =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "public-room-announce");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** start/stop마다 바뀐다 — 이전 방에서 예약된 작업은 무시하고, 접속이 끝났을 때
     * 이미 멈췄거나 새로 시작됐으면 방금 붙은 연결을 닫는다(안 닫으면 유령 방이 남는다). */
    private final AtomicInteger generation = new AtomicInteger();
    private volatile WebSocketClient ws;
    private volatile long backoffMs = INITIAL_BACKOFF_MS;

    private volatile String roomCode;
    private volatile String title;
    private volatile String hostNickname;
    private volatile String hostUuid;
    private volatile int currentPlayers;
    private volatile int maxPlayers;

    /** 마지막으로 실제 발표(재접속)했던 현재/최대 인원 — 디바운스/중복 재접속 판단용.
     * 둘 다 봐야 한다 — 최대 인원만 바뀌고 현재 인원은 그대로인 경우(방 설정에서
     * 정원만 조정)도 재발행이 필요한데, 예전엔 현재 인원만 비교해서 그런 변경이
     * 그냥 씹혔다(우연히 다른 이유로 재접속이 걸릴 때만 같이 반영됨). 전용 락으로 보호. */
    private int lastAnnouncedPlayers = -1;
    private int lastAnnouncedMax = -1;
    private boolean debouncePending = false;
    private long lastReconnectAtMs = 0;
    private final Object debounceLock = new Object();

    /** 방을 공개 목록에 올린다. 접속/재접속은 백그라운드에서 진행되며 즉시 반환.
     * hostUuid는 개인 차단(=밴) 기능용 — 방장을 차단한 사람 목록에서 걸러내려면
     * (또는 반대로) 방장의 UUID가 필요하다(P2PBanManager 클래스 주석 참고). */
    void start(String roomCode, String title, String hostNickname, String hostUuid, int currentPlayers, int maxPlayers) {
        if (!running.compareAndSet(false, true)) return;
        this.roomCode = roomCode;
        this.title = title;
        this.hostNickname = hostNickname;
        this.hostUuid = hostUuid;
        this.currentPlayers = currentPlayers;
        this.maxPlayers = maxPlayers;
        this.backoffMs = INITIAL_BACKOFF_MS;
        synchronized (debounceLock) {
            lastAnnouncedPlayers = currentPlayers;
            lastAnnouncedMax = maxPlayers;
            lastReconnectAtMs = System.currentTimeMillis();
            debouncePending = false;
        }
        int gen = generation.incrementAndGet();
        LOG.info("[public-room] announcing: code={} title={}", roomCode, title);
        scheduler.execute(() -> connect(gen));
    }

    void stop() {
        if (!running.compareAndSet(true, false)) return;
        generation.incrementAndGet();
        LOG.info("[public-room] un-announcing");
        WebSocketClient w = ws;
        ws = null;
        if (w != null) w.close();
    }

    /** 인원 또는 최대 인원이 바뀔 때마다 호출 — 실제 재접속은 클래스 주석 설명대로
     * 디바운스된다. 방 설정에서 정원만 바꾼 경우(현재 인원은 그대로)도 여기로
     * 들어오므로 max도 같이 비교해야 한다. */
    void updatePlayerCount(int current, int max) {
        this.currentPlayers = current;
        this.maxPlayers = max;
        if (!running.get()) return;
        synchronized (debounceLock) {
            if ((current == lastAnnouncedPlayers && max == lastAnnouncedMax) || debouncePending) return;
            long waitMs = Math.max(0, DEBOUNCE_MS - (System.currentTimeMillis() - lastReconnectAtMs));
            debouncePending = true;
            int gen = generation.get();
            try {
                scheduler.schedule(() -> flushPlayerCountUpdate(gen), waitMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                debouncePending = false;
            }
        }
    }

    /** 밴(=차단) 목록이 바뀌었을 때 P2PBanManager가 호출 — 인원 변경과 달리 사람이
     * 명령어/아이콘을 직접 눌러 만든, 드물고 의도적인 변경이라 디바운스 없이 즉시
     * 재접속한다. 방이 공개돼 있지 않으면(running=false) 아무 것도 안 한다. */
    void republishNow() {
        if (!running.get()) return;
        int gen = generation.get();
        scheduler.execute(() -> connect(gen));
    }

    private void flushPlayerCountUpdate(int gen) {
        synchronized (debounceLock) {
            debouncePending = false;
            if (!isCurrent(gen) || (currentPlayers == lastAnnouncedPlayers && maxPlayers == lastAnnouncedMax)) return;
            lastAnnouncedPlayers = currentPlayers;
            lastAnnouncedMax = maxPlayers;
            lastReconnectAtMs = System.currentTimeMillis();
        }
        // 재접속 자체는 락 밖에서 — 소켓 I/O를 debounceLock 아래에서 하면 그동안
        // updatePlayerCount 호출이 다 막힌다.
        connect(gen);
    }

    private boolean isCurrent(int gen) {
        return running.get() && gen == generation.get();
    }

    /** scheduler 스레드에서만 호출된다 — 이전 연결을 닫고(peer 이름이 바뀌었을 수 있음) 새로 붙는다. */
    private void connect(int gen) {
        if (!isCurrent(gen)) return;
        WebSocketClient old = ws;
        ws = null;
        if (old != null) old.close();

        String peerName = "r" + encode(roomCode, title, hostNickname, P2PConfig.getChannel(), currentPlayers, maxPlayers,
                P2PConfig.MC_VERSION, hostUuid, P2PBanManager.encodeBannedPlayerUuids());
        String lobbyId = P2PConfig.publicRoomsLobbyId(P2PConfig.publicRoomShardFor(roomCode));
        WebSocketClient client = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + lobbyId + "/" + peerName) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS;
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                // 방 목록 쪽에서 peer 목록을 읽어가는 것뿐 — 여기선 받을 게 없다.
            }
            @Override protected int readIdleTimeoutMs() {
                return LIVENESS_TIMEOUT_MS;
            }
            @Override public void onDisconnected() {
                // ws가 이미 다른(더 최신) 연결로 넘어갔으면 새 연결이 진행 중이거나 끝났다.
                if (ws == this) scheduleReconnect(gen);
            }
        };
        ws = client;
        try {
            client.connect();
        } catch (Exception e) {
            LOG.warn("[public-room] connect failed: {}", e.toString());
            if (ws == client) scheduleReconnect(gen);
            return;
        }
        if (!isCurrent(gen) || ws != client) client.close();
    }

    private void scheduleReconnect(int gen) {
        if (!isCurrent(gen)) return;
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        try {
            scheduler.schedule(() -> connect(gen), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    /** "r"/"b" 다음에 오는 페이로드: code|title|nickname|channel|current|max|version|hostUuid|blockedUuids
     * (UTF-8, URL-safe Base64, 패딩 없음). version/hostUuid/blockedUuids는 뒤에 새로 붙인 필드라
     * 앞의 6개와 순서가 바뀌면 안 된다(이미 떠 있는 예전 클라이언트와의 파싱 호환 때문은
     * 아니고 — 어차피 이 모드는 그런 걸 신경 안 씀 — 그냥 필드 늘어난 순서 기록용). */
    static String encode(String code, String title, String nickname, String channel, int currentPlayers, int maxPlayers,
                          String version, String hostUuid, String blockedUuids) {
        String raw = code + "|" + sanitize(title) + "|" + sanitize(nickname) + "|" + sanitize(channel)
                + "|" + currentPlayers + "|" + maxPlayers
                + "|" + sanitize(version) + "|" + sanitize(hostUuid) + "|" + sanitize(blockedUuids);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** {code, title, nickname, channel, current, max, version, hostUuid, blockedUuids} 또는
     * 파싱 실패 시 null — 숫자들도 문자열 그대로 담아 둔다. */
    static String[] decode(String payload) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 9);
            return parts.length == 9 ? parts : null;
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
