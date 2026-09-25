package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 방 목록 화면(RoomListScreen)이 쓰는 공개 방 목록 관전자.
 * <p>
 * lobby는 채널마다 따로 있다({@link P2PConfig#publicRoomsLobbyId(String, int)} — 채널 해시 + 샤드 번호).
 * {@link PublicRoomAnnouncer}는 방장의 채널마다 그 채널 lobby에 방을 올리고, 여기서는 내 채널들의 lobby
 * (채널 수 × {@link P2PConfig#PUBLIC_ROOM_SHARD_COUNT})에 "b" 접두사 peer로 동시 접속한다. 그 lobby에
 * 지금 어떤 방("r" 접두사 peer)이 떠 있는지는 서버가 브로드캐스트하는 peer 목록으로 알고, 각 방의 실제
 * 정보(제목·인원 등)는 방장이 따로 보내는 {@code room_update} 메시지로 받는다(PublicRoomAnnouncer 클래스
 * 주석 참고) — 이 둘을 합쳐 {@link #getCurrentRooms()}로 노출한다. 관심 없는 채널의 방은 애초에 안 받는다
 * — 서버가 보내는 명단이 채널 단위로 작아진다. 같은 방이 여러 채널 lobby에서 오면 코드로 하나로 합친다.
 * and는 채널 전부를 묶은 채널 하나로 친다(P2PConfig.effectiveChannels) — lobby 구독 자체가 필터라서,
 * RoomListScreen의 roomVisible 판정은 같은 규칙의 재확인일 뿐이다.
 * <p>
 * 채널은 채널 설정 화면에서 "적용"을 눌러야 바뀌므로, 그때 {@link #restart()}로 lobby를 다시 구독한다.
 * <p>
 * <b>연결이 끊긴 peer도 목록에 남아있는 이유와 처리법</b> — villas-signaling
 * 서버는 peer 연결이 끊긴 순간 그 peer가 아직 세션 맵에 남은 채로 control 메시지를
 * 보낼 수 있고, {@code Peer.Marshal()}은 연결이 없으면 {@code remote} 필드 자체를
 * 비워 보낸다(Go 쪽 {@code json:"remote,omitempty"}) — 즉 "그 peer가 목록에 있다"와
 * "그 peer가 지금 연결돼 있다"는 서로 다른 정보이고, 후자는 반드시 remote 필드 유무로
 * 따로 확인해야 한다. {@link WebRtcHost}의 조인 감지, {@link WebRtcClient}의 호스트
 * 감지도 같은 이유로 이 필드를 확인한다.
 * <p>
 * <b>연결이 끊기면 재접속해야 하는 이유</b> — 예전엔 lobby 하나가(네트워크
 * 순단 등으로) 끊기면 화면이 열려 있는 동안 다시는 안 붙어서, 거기 걸린 방들만 목록에서
 * 영원히 빠졌다 — {@link PublicRoomAnnouncer}처럼 지수 백오프로 재접속해야 한다.
 */
public final class PublicRoomBrowser {

    /** 공개 방 하나의 표시 정보. channel/version/차단 필터링은 여기서 하지 않는다 —
     * 클래스 주석 참고, RoomListScreen이 매 tick 다시 걸러서 즉시 반응하게 한다.
     * hostUuid/bannedHashes는 개인 차단(=밴) 기능용(P2PBanManager 클래스 주석 참고). */
    public record RoomEntry(String code, String title, String hostNickname, String channel, boolean channelAnd,
                             int currentPlayers, int maxPlayers, String version, String hostUuid,
                             String bannedHashes, long hostRttMs, long openedAtMs) {
        /** 내 마인크래프트 버전과 같은 방인지 — 다르면 목록에 회색으로 뜨고 들어갈 수 없다. 모드
         * 버전은 여기서 다시 안 본다 — P2PConfig.publicRoomsLobbyId가 모드 버전을 lobby 자체에
         * 섞어서, 모드 버전이 다른 방은 애초에 이 목록에 도착하지도 않는다(중복 검사였다가
         * 걷어냄 — 전송 중 빈 문자열이 되는 버그까지 겹쳐서 "로비는 통과했는데 화면엔 다르다고
         * 뜨는" 모순이 있었다). */
        public boolean sameVersion() {
            return P2PConfig.MC_VERSION.equals(version);
        }

        /** 내 RTT + 방장 RTT(둘 다 시그널링 서버까지), 아직 모르면 -1 — SignalingRtt 클래스 주석 참고. */
        public long estimatedPingMs() {
            long mine = SignalingRtt.currentMs();
            return mine < 0 || hostRttMs < 0 ? -1 : mine + hostRttMs;
        }
    }

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

    /**
     * 한 번의 start()가 만든 연결 묶음 — lobby(채널×샤드)마다 소켓·백오프·그 lobby에 지금 떠 있는 방
     * 코드 집합. 채널이 바뀌면 묶음을 통째로 갈아끼운다(restart). 콜백은 자기가 속한 묶음만 만지므로,
     * 버려진 묶음의 늦은 메시지가 새 묶음의 목록을 되살리거나 크기가 다른 배열을 잘못 건드리는 일이 없다.
     * <p>
     * 방의 실제 정보(제목·인원 등)는 peer 목록이 아니라 {@code room_update} 메시지로 따로 온다
     * (PublicRoomAnnouncer 클래스 주석 참고) — {@link #liveCodesByLobby}는 "지금 이 lobby에 어떤
     * 방 코드가 떠 있는지"만, {@link #infoByCode}는 "그 코드의 마지막으로 받은 정보"만 담당한다.
     */
    private static final class Session {
        final String[] lobbyIds;
        final AtomicReferenceArray<WebSocketClient> ws;
        final AtomicLongArray backoffMs;
        /** lobby별로 지금 살아 있는(peer 목록에 있는) "r" 코드 집합. null = 아직 control을 못 받음. */
        final AtomicReferenceArray<Set<String>> liveCodesByLobby;
        /** 코드 → 마지막으로 받은 방 정보. 여러 lobby(채널)에 걸친 방이면 아무 lobby에서 온 갱신이든 반영. */
        final java.util.Map<String, RoomEntry> infoByCode = new ConcurrentHashMap<>();

        Session(List<String> channels) {
            int n = channels.size() * P2PConfig.PUBLIC_ROOM_SHARD_COUNT;
            this.lobbyIds = new String[n];
            for (int c = 0; c < channels.size(); c++) {
                for (int s = 0; s < P2PConfig.PUBLIC_ROOM_SHARD_COUNT; s++) {
                    this.lobbyIds[c * P2PConfig.PUBLIC_ROOM_SHARD_COUNT + s] = P2PConfig.publicRoomsLobbyId(channels.get(c), s);
                }
            }
            this.ws = new AtomicReferenceArray<>(n);
            this.backoffMs = new AtomicLongArray(n);
            this.liveCodesByLobby = new AtomicReferenceArray<>(n);
            for (int i = 0; i < n; i++) this.backoffMs.set(i, INITIAL_BACKOFF_MS);
        }

        void close() {
            for (int i = 0; i < this.ws.length(); i++) {
                WebSocketClient w = this.ws.getAndSet(i, null);
                if (w != null) w.close();
                this.liveCodesByLobby.set(i, null);
            }
        }
    }

    /** null이면 멈춘 상태. */
    private volatile Session session;
    private final AtomicReference<List<RoomEntry>> currentRooms = new AtomicReference<>(List.of());

    /**
     * 접속 시작 — 내 채널들의 lobby에 백그라운드로 동시 접속하며 즉시 반환.
     * <p>
     * 이미 돌고 있으면 아무것도 안 한다 — RoomListScreen의 {@code init()}은 창 크기 변경으로
     * 화면이 안 닫힌 채 다시 불린다. 아주 예전엔 그때마다 소켓을 4개씩 더 열어 쌓였고, 그다음엔
     * 매번 끊고 다시 붙어서 목록이 잠깐 비고 서버에도 퇴장·입장 브로드캐스트가 몰렸다.
     */
    public synchronized void start() {
        if (this.session != null) return;
        Session s = new Session(P2PConfig.getEffectiveChannels());
        this.session = s;
        for (int i = 0; i < s.lobbyIds.length; i++) connect(s, i);
    }

    public synchronized void stop() {
        Session s = this.session;
        this.session = null;
        if (s != null) s.close();
        currentRooms.set(List.of());
    }

    /** 채널이 바뀌었을 때 — 새 채널들의 lobby로 다시 구독한다. */
    public synchronized void restart() {
        stop();
        start();
    }

    /** 지금 이 순간 열려 있는 공개 방 목록 (모든 lobby를 합친 것, 관전 세션 자신은 제외).
     * 목록이 바뀔 때만 새 리스트로 교체되므로 참조가 같으면 내용도 같다. */
    public List<RoomEntry> getCurrentRooms() {
        List<RoomEntry> real = currentRooms.get();
        if (FAKE_ROOMS <= 0) return real;
        // 참조가 같으면 내용도 같다는 약속을 지키려고, 실제 목록·채널이 그대로면 같은 합본을 돌려준다.
        String channel = P2PConfig.getChannel();
        if (real != fakeSource || !channel.equals(fakeChannel)) {
            List<RoomEntry> all = new ArrayList<>(real);
            for (int i = 0; i < FAKE_ROOMS; i++) {
                String title = FAKE_TITLES[i % FAKE_TITLES.length];
                // 다섯 개마다 하나는 다른 버전으로 — 회색 표시·버전 숨기기 확인용.
                all.add(new RoomEntry(String.format("FAKE%06d", i), title, "Dummy" + i, channel, false,
                        1 + i % 8, 8, i % 5 == 4 ? "1.21.1" : P2PConfig.MC_VERSION,
                        "00000000-0000-0000-0000-" + String.format("%012d", i),
                        "", new long[]{15, 90, 200, 450, 800, 1500, -1}[i % 7], i));
            }
            fakeRooms = all;
            fakeSource = real;
            fakeChannel = channel;
        }
        return fakeRooms;
    }

    /** 디버그: 방 목록 레이아웃 확인용 가짜 방 수(-Dkfcudp.debug.fakeRooms=84). 누르면 없는 코드라 접속은 실패한다. */
    private static final int FAKE_ROOMS = Integer.getInteger("kfcudp.debug.fakeRooms", 0);
    /** 폭 제한 끝까지 찬 제목(한글 12자·영문 18자)과 짧은 제목을 섞는다. */
    private static final String[] FAKE_TITLES = {
            "야생 같이해요", "가나다라마바사아자차카타", "Survival SMP", "ABCDEFGHIJKLMNOPQR", "건축", "Room - Dummy"};
    private List<RoomEntry> fakeRooms = List.of();
    private List<RoomEntry> fakeSource;
    private String fakeChannel;

    private boolean isLive(Session s, int idx, WebSocketClient client) {
        return this.session == s && s.ws.get(idx) == client;
    }

    private void connect(Session s, int idx) {
        if (this.session != s) return;
        String peerName = PublicRoomAnnouncer.randomBrowserPeerName();
        WebSocketClient client = new WebSocketClient(P2PConfig.SIGNALING_URL + "/" + s.lobbyIds[idx] + "/" + peerName) {
            @Override public void onConnected() {
                s.backoffMs.set(idx, INITIAL_BACKOFF_MS);
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                // 이미 버려진 연결(또는 묶음)에서 늦게 온 메시지가 목록을 되살리지 않게.
                if (!isLive(s, idx, this)) return;
                if (VillasMsg.has(json, "delta")) updateFromDelta(s, idx, json);
                else if (VillasMsg.has(json, "room_update")) updateFromRoomUpdate(s, json);
            }
            @Override protected int readIdleTimeoutMs() {
                return LIVENESS_TIMEOUT_MS;
            }
            @Override public void onDisconnected() {
                if (!isLive(s, idx, this)) return;
                // 끊긴 동안 이 lobby 목록은 더는 안 갱신된다 — 그대로 두면 그 사이 닫힌 방이 계속
                // 보이고 눌러도 접속이 안 된다. 다시 붙으면 서버가 최신 목록을 곧장 보낸다.
                s.liveCodesByLobby.set(idx, null);
                recombine(s);
                scheduleReconnect(s, idx);
            }
        };
        WebSocketClient prev = s.ws.getAndSet(idx, client);
        if (prev != null) prev.close();
        Thread t = new Thread(() -> {
            try {
                client.connect();
            } catch (Exception e) {
                LOG.warn("[public-room] browse connect failed (lobby={}): {}", idx, e.toString());
                if (isLive(s, idx, client)) scheduleReconnect(s, idx);
                return;
            }
            // 접속하는 사이 화면이 닫혔거나 재시작됐으면 방금 붙은 연결을 닫는다 — 안 닫으면
            // 소켓·수신 스레드가 남고, 서버는 그 로비 전원에게 계속 브로드캐스트한다.
            if (!isLive(s, idx, client)) client.close();
        }, "public-room-browse-" + idx);
        t.setDaemon(true);
        t.start();
    }

    private void scheduleReconnect(Session s, int idx) {
        if (this.session != s) return;
        long delay = s.backoffMs.getAndUpdate(idx, b -> Math.min(b * 2, MAX_BACKOFF_MS));
        SCHEDULER.schedule(() -> connect(s, idx), delay, TimeUnit.MILLISECONDS);
    }

    /** control(peer 목록) 메시지 — "이 lobby에 지금 어떤 방 코드가 떠 있는지"만 갱신한다. 방의
     * 실제 정보는 여기 안 실려 있다(room_update로 따로 온다) — remote가 없으면 연결이 끊긴 채 아직
     * 세션 맵에서만 안 지워진 잔여 항목이라 방으로 치지 않는다. */
    /** delta 메시지 — mc-signaling이 공개 방 목록 lobby에 한해 control(전체 목록) 대신 이걸
     * 보낸다(입/퇴장마다 전체를 다시 보내면 로비 인원수의 제곱만큼 비용이 커지기 때문 —
     * VillasMsg 클래스 주석, mc-signaling의 connection.go 참고). full=true면 joined가 "지금
     * 전원"이라 이전 집합을 버리고 통째로 갈아끼우고(최초 접속 직후·재접속 직후·서버의 주기적
     * keyframe), 아니면 이전 집합에 joined를 더하고 left를 뺀다. */
    private void updateFromDelta(Session s, int idx, String json) {
        boolean full = VillasMsg.isFullDelta(json);
        Set<String> prev = s.liveCodesByLobby.get(idx);
        Set<String> codes = full || prev == null ? new java.util.HashSet<>() : new java.util.HashSet<>(prev);
        for (String[] p : VillasMsg.joined(json)) {
            String name = p[0], remote = p[1];
            if (name == null || remote == null || !name.startsWith("r")) continue;
            codes.add(name.substring(1));
        }
        for (String name : VillasMsg.left(json)) {
            if (name != null && name.startsWith("r")) codes.remove(name.substring(1));
        }
        s.liveCodesByLobby.set(idx, codes);
        this.recombine(s);
    }

    /** room_update 메시지 — 코드 하나의 정보를 갱신한다. code는 본문에 직접 실려 있다(릴레이엔
     * 보낸 peer 이름이 안 실리므로 — PublicRoomAnnouncer 클래스 주석 참고). */
    private void updateFromRoomUpdate(Session s, String json) {
        String obj = VillasMsg.object(json, "room_update");
        if (obj == null) return;
        String code = VillasMsg.field(obj, "code");
        if (code == null || code.isEmpty()) return;
        int current = parseInt(VillasMsg.field(obj, "current"), 0);
        int max = parseInt(VillasMsg.field(obj, "max"), 0);
        long hostRtt = parseLong(VillasMsg.field(obj, "host_rtt_ms"), -1);
        long openedAt = parseLong(VillasMsg.field(obj, "opened_at_ms"), Long.MAX_VALUE);
        boolean channelAnd = "true".equals(VillasMsg.field(obj, "channel_and"));
        s.infoByCode.put(code, new RoomEntry(code,
                nullToEmpty(VillasMsg.field(obj, "title")), nullToEmpty(VillasMsg.field(obj, "nickname")),
                nullToEmpty(VillasMsg.field(obj, "channel")), channelAnd, current, max,
                nullToEmpty(VillasMsg.field(obj, "version")),
                nullToEmpty(VillasMsg.field(obj, "host_uuid")),
                nullToEmpty(VillasMsg.field(obj, "banned_hashes")), hostRtt, openedAt));
        this.recombine(s);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }
    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s); } catch (Exception e) { return fallback; }
    }

    /**
     * 모든 lobby에서 지금 살아 있는 코드를 합쳐 {@link #currentRooms}를 다시 만든다 — 아직 정보
     * (room_update)를 못 받은 코드는 잠깐(대개 한 왕복 이내) 목록에서 빠져 있다가 정보가 도착하면
     * 나타난다. 연 시각 순으로 정렬해 순서를 고정한다(같은 시각이면 코드 순) — 그래야 다른 관전자가
     * 들고나며 브로드캐스트가 올 때마다 화면에서 방 순서가 이유 없이 뒤섞이지 않는다. 더는 어느
     * lobby에도 없는 코드는 {@code infoByCode}에서도 지운다(그대로 두면 세션이 오래갈수록 계속 쌓인다).
     * <p>
     * lobby마다 수신 스레드가 달라 동시에 불릴 수 있어 직렬화한다.
     * <p>
     * <b>내용이 실제로 그대로면 새 리스트를 내보내지 않는다</b> — 서버는 로비에 "b"(관전) peer가
     * 들고나기만 해도 로비 전원에게 다시 뿌린다(session.go의 SendControlMessageToAllConnectedPeers).
     * 즉 방 목록 화면을 보는 사람이 느는 것 자체가 다른 모든 시청자에게 재계산을 유발한다.
     * {@link #currentRooms}를 구독하는 쪽(RoomListScreen)은 참조가 같으면 다시 거르지도 다시
     * 그리지도 않는 캐시를 이미 갖고 있는데, 매번 새 리스트를 내보내면 그 캐시가 무력화된다 —
     * 시청자 수만큼 낭비가 늘어나는 구조라 RoomEntry(record라 equals가 내용 비교)로 비교해서
     * 바뀐 게 없으면 참조를 그대로 둔다.
     */
    private synchronized void recombine(Session s) {
        if (this.session != s) return;
        Set<String> liveCodes = new java.util.HashSet<>();
        for (int i = 0; i < s.liveCodesByLobby.length(); i++) {
            Set<String> codes = s.liveCodesByLobby.get(i);
            if (codes != null) liveCodes.addAll(codes);
        }
        s.infoByCode.keySet().retainAll(liveCodes);

        List<RoomEntry> all = new ArrayList<>();
        for (String code : liveCodes) {
            RoomEntry e = s.infoByCode.get(code);
            if (e != null) all.add(e);
        }
        // 연 시각 순(오래된 방이 앞) — 새 방은 뒤에 붙어 이미 보던 방들을 밀지 않는다. 같은 시각이면 코드 순.
        all.sort(Comparator.comparingLong(RoomEntry::openedAtMs).thenComparing(RoomEntry::code));
        if (!all.equals(currentRooms.get())) currentRooms.set(all);
    }
}
