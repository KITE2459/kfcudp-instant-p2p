package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 공개 방 목록 — 새 서버 인프라 없이 기존 시그널링 relay의 lobby/peer 메커니즘을 재사용한다.
 * <p>
 * {@link WebRtcHost}가 방마다 여는 {@code /{roomId}} lobby(조인 감지용)와는 별개로, 공개 방을 연
 * 호스트는 자기 채널마다 결정되는 샤드 lobby({@link P2PConfig#publicRoomsLobbyId(String, int)},
 * {@link P2PConfig#publicRoomShardFor(String)} 참고)에 {@code "r" + 방코드}라는 짧고 고정된 이름의
 * peer로 접속해 둔다. 방 목록 화면({@link PublicRoomBrowser})은 자기 채널들의 lobby에 동시 접속해서
 * "r" 접두사 peer들(=지금 열려 있는 공개 방들)을 훑어본다 — 호스트가 방을 닫으면 이 WebSocket
 * 연결도 끊어지므로 자동으로 목록에서 사라진다.
 * <p>
 * <b>정보는 메시지로, 재접속은 정체성이 바뀔 때만</b> — 예전엔(villas-signaling) peer 이름 자체에
 * 제목·인원·핑 등 방 정보를 전부 인코딩해서 실었다. peer 이름은 접속 시점에 고정이라, 정보가 하나라도
 * 바뀌면(인원 변화·핑 흔들림 등, 방 하나가 떠 있는 동안 아주 잦음) 재접속(TCP+TLS+WS 핸드셰이크 전부
 * 다시)해야 했고, 재접속은 그 로비 전원에게 서버가 전체 스냅샷을 다시 뿌리게 만든다 — 로비를 보는
 * 사람이 늘수록, 그리고 인원/핑이 자주 흔들릴수록 낭비가 커지는 구조였다.
 * <p>
 * mc-signaling은 서버가 peer가 보낸 임의 메시지를 세션의 다른 peer들에게 그대로 중계하는 기존 통로
 * (session.go의 handleMessage)를 그대로 쓰는 {@code room_update} 메시지 타입을 하나 얹었다(서버는
 * 내용을 해석하지 않는다 — VillasMsg.roomUpdate 클래스 주석 참고). 그래서 이제:
 * <ul>
 *   <li>정보만 바뀌면(인원·핑·제목·정원 등) — 접속을 유지한 채 작은 메시지 하나만 보낸다. 재접속이
 *       없으니 디바운스도 필요 없다({@link #publish}가 곧장 반영).</li>
 *   <li>새로 이 로비에 들어온 관전자가 있으면(=이 peer 자신이 받는 control 메시지의 peer 수가 바뀜)
 *       그쪽은 아직 이 방의 정보를 못 받았을 수 있으니 마지막 값을 한 번 다시 보낸다({@link #onMessage}).</li>
 *   <li>정체성이 실제로 바뀔 때만(방 코드가 바뀜 = 새 방/초대코드 재생성, 채널 구성이 바뀜) 재접속한다
 *       — 이때는 어차피 다른 로비로 옮겨가야 하니 재접속이 불가피하다.</li>
 * </ul>
 * <p>
 * <b>스레드</b> — 접속(TCP+핸드셰이크, 서버가 느리면 수 초)과 메시지 전송은 전부 {@link #scheduler}
 * 스레드 하나에서만 한다. 예전엔 {@code publish()}가 부른 쪽 스레드에서 곧장 접속해서, 방을 열거나
 * 밴할 때 게임 렌더/서버 스레드가 최대 수십 초 멈출 수 있었다. 이 인스턴스는 WebRtcBridge의 싱글턴이라
 * scheduler를 절대 종료하지 않는다.
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

    private volatile boolean running = false;
    /** 접속(재접속) 세대 — 이전 세대에서 예약된 재접속/재연결 시도는 무시한다. */
    private final AtomicInteger generation = new AtomicInteger();
    /** 채널마다 하나씩 — 같은 방 정보를 각 채널 lobby에 올린다. 교체는 통째로(불변 리스트). */
    private volatile List<WebSocketClient> ws = List.of();
    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    /** 지금 ws가 물려 있는 채널 구성 — publish()가 이게 최신 채널과 다르면 재접속을 건다. */
    private List<String> connectedChannels = List.of();

    private volatile String roomCode;
    private volatile String title;
    private volatile String hostNickname;
    private volatile String hostUuid;
    private volatile int currentPlayers;
    private volatile int maxPlayers;

    /** 마지막 발표에 실은 방장 RTT — 막대 수가 달라질 만큼 변했을 때만 재발표(메시지)한다. */
    private volatile long announcedRttMs = -1;

    /** 방을 연 시각(방장 시계, epoch ms) — 방 목록 정렬용. publish() 참고. */
    private volatile long openedAtMs;

    PublicRoomAnnouncer() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (running && SignalingRtt.bars(SignalingRtt.currentMs()) != SignalingRtt.bars(announcedRttMs)) {
                sendUpdate();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** 방을 공개 목록에 올리거나(처음 호출) 이미 올라와 있으면 정보를 갱신한다. 방 코드·채널 구성이
     * 지난 접속과 같으면 재접속 없이 메시지만 보낸다 — hostUuid는 개인 차단(=밴) 기능용(P2PBanManager
     * 클래스 주석 참고). 접속/재접속은 백그라운드에서 진행되며 즉시 반환. */
    synchronized void publish(String roomCode, String title, String hostNickname, String hostUuid,
                               int currentPlayers, int maxPlayers) {
        boolean firstTime = !running;
        running = true;
        // 방 목록은 연 시각 순(오래된 방이 앞)이다 — 같은 방을 설정 변경으로 갱신할 땐 처음 연 시각을
        // 유지해야 목록에서 자리가 안 바뀐다. 코드가 바뀌면(새 방·초대코드 재생성) 새로 잡는다.
        if (!roomCode.equals(this.roomCode)) this.openedAtMs = System.currentTimeMillis();
        this.roomCode = roomCode;
        this.title = title;
        this.hostNickname = hostNickname;
        this.hostUuid = hostUuid;
        this.currentPlayers = currentPlayers;
        this.maxPlayers = maxPlayers;

        List<String> channels = P2PConfig.getEffectiveChannels();
        if (firstTime || !channels.equals(connectedChannels)) {
            this.backoffMs = INITIAL_BACKOFF_MS;
            int gen = generation.incrementAndGet();
            LOG.info("[public-room] announcing: code={} title={}", roomCode, title);
            scheduler.execute(() -> connect(gen, channels));
        } else {
            LOG.debug("[public-room] updating (no reconnect): code={} title={}", roomCode, title);
            sendUpdate();
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        generation.incrementAndGet();
        LOG.info("[public-room] un-announcing");
        List<WebSocketClient> old = ws;
        ws = List.of();
        connectedChannels = List.of();
        for (WebSocketClient w : old) w.close();
    }

    /** 인원 또는 최대 인원이 바뀔 때마다 호출 — 이제 재접속이 아니라 메시지 하나라 디바운스가
     * 필요 없다. 방 설정에서 정원만 바꾼 경우(현재 인원은 그대로)도 여기로 들어온다. */
    void updatePlayerCount(int current, int max) {
        if (current == this.currentPlayers && max == this.maxPlayers) return;
        this.currentPlayers = current;
        this.maxPlayers = max;
        if (running) sendUpdate();
    }

    /** 밴(=차단) 목록이 바뀌었을 때 P2PBanManager가 호출 — 방이 공개돼 있지 않으면 아무 것도 안 한다. */
    void republishNow() {
        if (running) sendUpdate();
    }

    /** scheduler 스레드에서 현재 연결(들)에 최신 정보를 보낸다. */
    private void sendUpdate() {
        scheduler.execute(() -> {
            if (!running) return;
            long rtt = SignalingRtt.currentMs();
            announcedRttMs = rtt;
            String msg = VillasMsg.roomUpdate(roomCode, title, hostNickname, P2PConfig.getChannel(),
                    P2PConfig.isChannelAnd(), currentPlayers, maxPlayers, P2PConfig.MC_VERSION, hostUuid,
                    P2PBanManager.encodeBannedPlayerHashes(roomCode), rtt, openedAtMs);
            for (WebSocketClient client : ws) client.send(msg);
        });
    }

    private boolean isCurrent(int gen) {
        return running && gen == generation.get();
    }

    /** scheduler 스레드에서만 호출된다 — 이전 연결을 닫고(채널 구성이 바뀌었을 수 있음) 채널마다
     * 새로 붙는다. 하나라도 실패하거나 끊기면 묶음 전체를 다시 붙인다(재접속 자체가 드문 이벤트라
     * 채널별로 따로 관리할 이유가 없다). */
    private void connect(int gen, List<String> channels) {
        if (!isCurrent(gen)) return;
        List<WebSocketClient> old = ws;
        ws = List.of();
        for (WebSocketClient w : old) w.close();
        connectedChannels = channels;

        int shard = P2PConfig.publicRoomShardFor(roomCode);
        List<WebSocketClient> clients = new ArrayList<>();
        for (String channel : channels) {
            clients.add(newClient(gen, P2PConfig.publicRoomsLobbyId(channel, shard)));
        }
        ws = List.copyOf(clients);
        for (WebSocketClient client : clients) {
            try {
                client.connect();
            } catch (Exception e) {
                LOG.warn("[public-room] connect failed: {}", e.toString());
                if (ws.contains(client)) scheduleReconnect(gen);
                return;
            }
            if (!isCurrent(gen) || !ws.contains(client)) {
                client.close();
                return;
            }
        }
        // 채널 전부가 다 붙은 뒤 한 번만 — 채널마다 onConnected에서 따로 보내면 매번 ws 전체(그때까지
        // 붙은 채널 전부)에 보내서 채널 수만큼 중복 전송된다(N개 채널이면 최대 N번 중복).
        sendUpdate();
    }

    private WebSocketClient newClient(int gen, String lobbyId) {
        return new WebSocketClient(P2PConfig.SIGNALING_URL + "/" + lobbyId + "/r" + roomCode) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS;
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                // control(=이 로비의 peer 구성이 바뀜) 말고는 받을 게 없다. 누가 새로 들어왔을 수
                // 있으니(그쪽은 아직 내 방 정보를 못 받았음) 마지막 값을 다시 보낸다 — 나가는
                // 경우에도 걸리지만 그냥 한 번 더 보내는 것뿐이라 해될 게 없다.
                if (isCurrent(gen) && VillasMsg.has(json, "peers")) sendUpdate();
            }
            @Override protected int readIdleTimeoutMs() {
                return LIVENESS_TIMEOUT_MS;
            }
            @Override public void onDisconnected() {
                // ws가 이미 다른(더 최신) 묶음으로 넘어갔으면 새 연결이 진행 중이거나 끝났다.
                if (ws.contains(this)) scheduleReconnect(gen);
            }
        };
    }

    private void scheduleReconnect(int gen) {
        if (!isCurrent(gen)) return;
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        try {
            scheduler.schedule(() -> connect(gen, connectedChannels), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    /** 방 목록 화면이 자기 관전 세션을 구분하기 위한 무작위 "b" peer 이름. */
    static String randomBrowserPeerName() {
        return "b" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0x100000));
    }
}
