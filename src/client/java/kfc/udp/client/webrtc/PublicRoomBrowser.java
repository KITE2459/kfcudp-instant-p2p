package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 방 목록 화면(RoomListScreen)이 쓰는 공개 방 목록 관전자.
 * <p>
 * {@link PublicRoomAnnouncer}가 호스트마다 announce해 둔 샤드 lobby들
 * ({@link P2PConfig#publicRoomsLobbyId(int)})에 전부(0..{@link P2PConfig#PUBLIC_ROOM_SHARD_COUNT}-1)
 * "b" 접두사 peer로 동시 접속해서, 서버가 각 lobby마다 브로드캐스트하는 peer
 * 목록 중 "r" 접두사(=공개 방)만 걸러 전부 합친 걸 {@link #getCurrentRooms()}로
 * 노출한다 — 샤드 하나짜리 목록이 아니라 전체 목록을 봐야 하므로 브라우저는
 * 모든 샤드에 다 붙어야 한다(샤딩은 어디까지나 서버 쪽 세션 하나에 몰리는
 * 부하를 나누기 위한 것이지, 조회 범위를 좁히는 게 아니다).
 * <p>
 * <b>연결이 끊긴 peer도 목록에 남아있는 이유와 처리법</b> — villas-signaling
 * 서버는 peer 연결이 끊긴 순간 그 peer가 아직 세션 맵에 남은 채로 control 메시지를
 * 보낼 수 있고, {@code Peer.Marshal()}은 연결이 없으면 {@code remote} 필드 자체를
 * 비워 보낸다(Go 쪽 {@code json:"remote,omitempty"}) — 즉 "그 peer가 목록에 있다"와
 * "그 peer가 지금 연결돼 있다"는 서로 다른 정보이고, 후자는 반드시 remote 필드 유무로
 * 따로 확인해야 한다. {@link WebRtcHost}의 조인 감지, {@link WebRtcClient}의 호스트
 * 감지도 같은 이유로 이 필드를 확인한다.
 * <p>
 * <b>샤드 연결이 끊기면 재접속해야 하는 이유</b> — 예전엔 샤드 하나가(네트워크
 * 순단 등으로) 끊기면 그 샤드는 화면이 열려 있는 동안 다시는 안 붙어서, 그 샤드에
 * 걸린 방들만 목록에서 영원히 빠지거나(=방 목록이 이상해 보임/일부만 보임),
 * 최초 접속 자체가 실패했을 땐 그 샤드 슬롯이 계속 null이라 그쪽 방들이 아예
 * 안 보였다 — {@link PublicRoomAnnouncer}처럼 지수 백오프로 재접속해야 한다.
 */
public final class PublicRoomBrowser {

    /** 공개 방 하나의 표시 정보. channel/version/차단 필터링은 여기서 하지 않는다 —
     * 클래스 주석 참고, RoomListScreen이 매 tick 다시 걸러서 즉시 반응하게 한다.
     * hostUuid/blockedUuids는 개인 차단(=밴) 기능용(P2PBanManager 클래스 주석 참고). */
    public record RoomEntry(String code, String title, String hostNickname, String channel,
                             int currentPlayers, int maxPlayers, String version, String hostUuid,
                             String blockedUuids) {}

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-public");

    private static final long INITIAL_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS     = 30_000;

    /** 재접속 예약용. 예전엔 인스턴스(=방 목록 화면을 열 때마다)마다 스레드 풀을 만들고
     * 안 닫아서 화면을 열 때마다 스레드가 하나씩 쌓였다 — 전 인스턴스가 하나를 같이 쓴다. */
    private static final ScheduledExecutorService SCHEDULER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "public-room-browse-reconnect");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReferenceArray<WebSocketClient> shardWs =
            new AtomicReferenceArray<>(P2PConfig.PUBLIC_ROOM_SHARD_COUNT);
    private final AtomicLongArray backoffMs = new AtomicLongArray(P2PConfig.PUBLIC_ROOM_SHARD_COUNT);
    /** 샤드별 최신 목록 — 하나가 갱신될 때마다 전부 이어붙여 {@link #currentRooms}로 다시 낸다. */
    private final AtomicReferenceArray<List<RoomEntry>> shardRooms =
            new AtomicReferenceArray<>(P2PConfig.PUBLIC_ROOM_SHARD_COUNT);
    private final AtomicReference<List<RoomEntry>> currentRooms = new AtomicReference<>(List.of());

    /**
     * 접속 시작 — 모든 샤드 lobby에 백그라운드로 동시 접속하며 즉시 반환.
     * <p>
     * 이미 시작된 상태에서 다시 불려도 안전하다(먼저 {@link #stop()}) — RoomListScreen의
     * {@code init()}은 창 크기 변경 등으로 화면이 안 닫힌 채 다시 불릴 수 있는데,
     * 예전엔 그때마다 이전 소켓들을 안 끊고 새로 4개씩 더 열어서 연결이 계속
     * 쌓였다(=가끔 시그널링 서버가 죽는 원인 중 하나, 목록도 여러 연결이 서로 다른
     * 타이밍에 갱신하며 뒤섞임).
     */
    public void start() {
        stop();
        running.set(true);
        for (int i = 0; i < P2PConfig.PUBLIC_ROOM_SHARD_COUNT; i++) {
            backoffMs.set(i, INITIAL_BACKOFF_MS);
            connectShard(i);
        }
    }

    public void stop() {
        running.set(false);
        for (int i = 0; i < shardWs.length(); i++) {
            WebSocketClient w = shardWs.getAndSet(i, null);
            if (w != null) w.close();
            shardRooms.set(i, null);
        }
        currentRooms.set(List.of());
    }

    /** 지금 이 순간 열려 있는 공개 방 목록 (모든 샤드를 합친 것, 관전 세션 자신은 제외).
     * 목록이 바뀔 때만 새 리스트로 교체되므로 참조가 같으면 내용도 같다. */
    public List<RoomEntry> getCurrentRooms() {
        return currentRooms.get();
    }

    private void connectShard(int shard) {
        if (!running.get()) return;
        String peerName = PublicRoomAnnouncer.randomBrowserPeerName();
        WebSocketClient client = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + P2PConfig.publicRoomsLobbyId(shard) + "/" + peerName) {
            @Override public void onConnected() {
                backoffMs.set(shard, INITIAL_BACKOFF_MS);
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                // 이미 버려진 연결에서 늦게 온 메시지가 stop()이 비운 목록을 되살리지 않게.
                if (shardWs.get(shard) == this) updateFromControl(shard, json);
            }
            @Override protected int readIdleTimeoutMs() {
                return LIVENESS_TIMEOUT_MS;
            }
            @Override public void onDisconnected() {
                if (shardWs.get(shard) == this) scheduleReconnect(shard);
            }
        };
        WebSocketClient prev = shardWs.getAndSet(shard, client);
        if (prev != null) prev.close();
        Thread t = new Thread(() -> {
            try {
                client.connect();
            } catch (Exception e) {
                LOG.warn("[public-room] browse connect failed (shard={}): {}", shard, e.toString());
                if (shardWs.get(shard) == client) scheduleReconnect(shard);
                return;
            }
            // 접속하는 사이 화면이 닫혔거나 재시작됐으면 방금 붙은 연결을 닫는다 — 안 닫으면
            // 소켓·수신 스레드가 남고, 서버는 그 로비 전원에게 계속 브로드캐스트한다.
            if (!running.get() || shardWs.get(shard) != client) client.close();
        }, "public-room-browse-" + shard);
        t.setDaemon(true);
        t.start();
    }

    private void scheduleReconnect(int shard) {
        if (!running.get()) return;
        long delay = backoffMs.getAndUpdate(shard, b -> Math.min(b * 2, MAX_BACKOFF_MS));
        SCHEDULER.schedule(() -> connectShard(shard), delay, TimeUnit.MILLISECONDS);
    }

    private void updateFromControl(int shard, String json) {
        if (!VillasMsg.has(json, "peers")) return;
        List<RoomEntry> rooms = new ArrayList<>();
        for (String[] p : VillasMsg.peers(json)) {
            String name = p[0], remote = p[1];
            // remote가 없으면 연결이 끊긴 채 아직 세션 맵에서만 안 지워진 잔여 항목 — 방으로 치지 않는다.
            if (name == null || remote == null || !name.startsWith("r")) continue;
            String[] decoded = PublicRoomAnnouncer.decode(name.substring(1));
            if (decoded == null) continue;
            String channel = decoded[3];
            int current, max;
            try {
                current = Integer.parseInt(decoded[4]);
                max = Integer.parseInt(decoded[5]);
            } catch (NumberFormatException e) {
                current = 0;
                max = 0;
            }
            String version = decoded[6], hostUuid = decoded[7], blockedUuids = decoded[8];
            rooms.add(new RoomEntry(decoded[0], decoded[1], decoded[2], channel, current, max,
                    version, hostUuid, blockedUuids));
        }
        this.shardRooms.set(shard, rooms);
        this.recombine();
    }

    /**
     * 샤드별 목록을 전부 이어붙여 {@link #currentRooms}를 다시 만든다 — 방 코드로
     * 정렬해서 순서를 고정한다. villas-signaling 서버(Go)의 peer map은 반복 순서가
     * 매번 랜덤이라(Go 언어 자체의 map 보장 사항), 정렬 없이 그대로 이어붙이면
     * 방 자체는 그대로인데도 브로드캐스트가 올 때마다(다른 관전자가 들고나는 것만
     * 으로도 발생) 목록 순서가 바뀌어 화면에서 방들이 이유 없이 뒤섞여 보였다.
     */
    private void recombine() {
        List<RoomEntry> all = new ArrayList<>();
        for (int i = 0; i < this.shardRooms.length(); i++) {
            List<RoomEntry> r = this.shardRooms.get(i);
            if (r != null) all.addAll(r);
        }
        all.sort(Comparator.comparing(RoomEntry::code));
        currentRooms.set(all);
    }
}
