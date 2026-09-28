package kfc.udp.client.quic;

import kfc.udp.client.signaling.VillasMsg;
import kfc.udp.client.signaling.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnection;
import tech.kwik.core.server.ServerConnector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QUIC 방장 — 접속자의 QUIC 스트림을 열린 LAN 포트로 잇는다.
 * <p>
 * <b>시그널링은 랑데부 서버가 짝을 맺어 준다</b>({@code /rv/{roomId}/host}, 서버 rendezvous.go).
 * 접속자가 오면 서버가 sid 를 정해 {@code join} 으로 알려 주고, 그 접속자와의 메시지는 sid 를 붙여
 * 오간다. 우리는 {@code description("quic-answer", 지문)} 과 후보({@code "ip port typ"} 한 줄)를
 * 보내고, 접속자 후보는 트리클로 받는다. 다른 접속자는 이 대화를 볼 수도 끼어들 수도 없다.
 * <b>소켓은 하나다.</b> 접속자마다 따로 열지 않는다 — UDP 소켓 하나로 여러 상대를 받을 수 있고
 * QUIC이 connection ID로 알아서 구분한다. 그 소켓을 {@link QuicIce} 가 STUN용으로 같이 쓰므로
 * 홀펀칭으로 뚫은 5-tuple 위에서 QUIC이 그대로 돈다.
 * <p>
 * <b>방장은 {@code punch()} 결과를 쓰지 않는다</b> — 접속자의 QUIC Initial이 어느 주소로 오든
 * ServerConnector가 받으므로, 방장 쪽 할 일은 자기 NAT에 구멍을 내는 것(= 접속자 후보로 체크를
 * 쏘는 것)과 접속자의 체크에 응답하는 것뿐이다.
 */
public final class QuicHost {

    private static final Logger LOG = LoggerFactory.getLogger("quic-host");

    /** ALPN — QUIC은 응용 프로토콜 이름이 필수다. 양쪽이 같아야 한다. */
    static final String ALPN = "instant-p2p";
    static final String MSG_ANSWER = "quic-answer";
    static final String MSG_OFFER = "quic-offer";
    /** 방장이 「중계 통신 강제」인데 중계 계정이 없다 — 알릴 후보가 없으니 접속자가 30초 기다리지 않게 바로 알린다. */
    static final String MSG_NO_RELAY = "quic-no-relay";

    private static final int DIAL_TIMEOUT_MS = 5_000;
    /** 접속자의 2단계 시도(직결 2초 + 중계 30초)보다 길어야 방장이 먼저 포기하지 않는다. */
    private static final long PUNCH_MS = 35_000;
    /** 랑데부가 끊겼을 때 재접속 간격 — 끊긴 채로 두면 방이 조용히 접속 불가가 된다. */
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final HostHooks hooks;

    private final String turnUrl;
    private final String turnUser;
    private final String turnPass;
    private final String signalingUrl;
    private final String stunUrl;
    private final String roomId;
    private final String targetHost;
    private final int targetPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerConnector server;
    private volatile QuicIce ice;
    private volatile WebSocketClient lobby;
    private volatile String fingerprint;
    private volatile List<QuicIce.Candidate> candidates = List.of();
    private volatile long backoffMs = INITIAL_BACKOFF_MS;

    /**
     * 지금 붙어 있는 접속자 연결. 방을 닫을 때 <b>우리가 먼저 닫아</b> kwik 의 종료 대기를 비운다
     * ({@link #close()} 주석). 끊기면 리스너가 빼 주므로 오래 살아도 쌓이지 않는다.
     */
    private final java.util.Set<QuicConnection> live = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 협상 중인 접속자(서버가 붙여 준 sid) → 그 접속자가 보낸 후보. 후보는 sid 로 여기 모인다. */
    private final Map<String, List<QuicIce.Candidate>> joiners = new ConcurrentHashMap<>();

    /** sid → 진행 중인 협상. 접속자가 떠나면(leave) 끊는다 — 안 끊으면 떠난 접속자에게 35초 동안 체크를
     *  보내며 협상 자리(negotiating)를 붙잡아, 그 사이 다시 들어온 사람이 "동시 협상이 너무 많다"로 막혔다. */
    private final Map<String, java.util.concurrent.Future<?>> negotiations = new ConcurrentHashMap<>();

    /**
     * 랑데부 서버에서 이 방 코드를 잡아 두는 비밀. 방장이 잠깐 끊겼다 다시 붙어도 같은 key 여야 방을
     * 되찾을 수 있다 — 코드만 아는 남이 그 사이 방장 자리를 가로채지 못하게(서버 rendezvous.go).
     */
    private final String hostKey = java.util.UUID.randomUUID().toString().replace("-", "");

    /** 동시에 진행하는 접속 협상 수 상한(onJoin 주석). */
    private final java.util.concurrent.Semaphore negotiating = new java.util.concurrent.Semaphore(4);

    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quic-host-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param signalingUrl 시그널링 WS 루트, @param stunUrl {@code stun:HOST:PORT}.
     *        {@code P2PConfig} 를 직접 읽지 않는 건 이 클래스가 게임 밖(독립 하니스)에서도
     *        돌아야 하기 때문이다 — P2PConfig 는 정적 초기화에서 FabricLoader 를 건드린다.
     *        게임 안에서는 {@link QuicBridge} 가 P2PConfig 값을 넣어 준다.
     * @param hooks 게임 연동점(IP 밴 매핑·접속자 해시) — 같은 이유로 주입받는다({@link HostHooks}).
     */
    public QuicHost(String roomId, String target, String signalingUrl, String stunUrl,
                    String turnUrl, String turnUser, String turnPass, HostHooks hooks) {
        this.turnUrl = turnUrl;
        this.turnUser = turnUser;
        this.turnPass = turnPass;
        this.hooks = hooks != null ? hooks : HostHooks.NONE;
        this.signalingUrl = signalingUrl;
        this.stunUrl = stunUrl;
        this.roomId = roomId;
        int colon = target.lastIndexOf(':');
        this.targetHost = colon > 0 ? target.substring(0, colon) : target;
        this.targetPort = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 25565;
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;

        // kwik 의 ServerConnector.close() 는 모든 연결의 종료 핸드셰이크를 기다리는데, 그 기본
        // 상한이 30초다(DEFAULT_CLOSE_TIMEOUT_IN_SECONDS). 접속자 중 이미 끊긴 사람이나 중계로
        // 붙은 사람이 있으면 각각 타임아웃을 써서, 월드를 닫을 때 31초가 걸렸다(실측).
        // 그건 오래 사는 서버에는 맞는 기본값이지만 "플레이어가 월드를 닫았다" 에는 안 맞는다.
        // public static 이고 final 이 아니라 조정하도록 열어 둔 값이다.
        tech.kwik.core.server.impl.ServerConnectorImpl.DEFAULT_CLOSE_TIMEOUT_IN_SECONDS = 2;

        QuicCert.Identity id = QuicCert.generate();
        fingerprint = id.fingerprint();

        // 순서가 중요하다 — 후보 수집은 소켓을 직접 읽으므로 ServerConnector 를 띄우기 전에 끝내야
        // 한다. 띄운 뒤엔 kwik 이 수신 루프를 가져가고, 그 다음부터 STUN 은 IceSocket 이 가로챈다.
        // 소켓 전체를 막는 relayOnly 는 끈다(false). 방장의 중계 여부는 <b>접속자마다</b> 그 순간
        // 설정(relayNow)으로 정하고, 중계로 들어온 상대에겐 중계로만 답한다(IceSocket.viaRelayPeers).
        // 방을 연 시점의 값을 박으면 방을 연 채 설정을 바꿨을 때 직결이 통째로 막힌다.
        QuicIce agent = new QuicIce(stunUrl, false);
        ice = agent;
        // TURN 은 후보 수집보다 먼저 — allocation 이 relay 후보의 재료다. 실패하면 홀펀칭만 쓴다.
        agent.enableTurn(turnUrl, turnUser, turnPass);
        candidates = agent.gather();

        server = ServerConnector.builder()
                .withSocket(agent.socket())
                .withKeyStore(id.keyStore(), QuicCert.ALIAS, QuicCert.PASSWORD)
                .withSupportedVersion(QuicConnection.QuicVersion.V1)
                .withLogger(KwikLog.quiet())   // 필수(기본값 없음). NullLogger 로 두면 kwik 경고를 다 버린다
                .build();
        server.registerApplicationProtocol(ALPN, new TunnelFactory());
        server.start();

        LOG.info("[quic-host] listening port={} target={}:{}", agent.localPort(), targetHost, targetPort);

        openLobby();
    }

    /**
     * 방을 닫는다. <b>정리는 별도 스레드에서 한다 — 부르는 쪽(월드 종료 경로의 렌더 스레드)을
     * 막으면 게임이 그대로 멈춘다.</b>
     * <p>
     * 실제로 그랬다: 접속자 5명(여러 명 중계)인 방을 닫을 때 렌더 스레드가 31초 블로킹돼
     * "월드 저장 중에 팅긴다"로 보였다. 시그널링 WS 닫기와 kwik 의 연결별 종료 핸드셰이크가
     * 각각 네트워크 대기를 하기 때문이고, 그건 게임 종료를 기다릴 이유가 없는 일이다.
     * <p>
     * 어느 단계가 느린지 알 수 있게 단계별 소요를 남긴다 — 추측으로 고치지 않으려면 필요하다.
     */
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        WebSocketClient l = lobby;
        ServerConnector s = server;
        QuicIce agent = ice;

        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            if (l != null) l.close();
            long tLobby = System.currentTimeMillis();
            // 연결을 먼저 우리가 닫는다(기다리지 않는 close). 이러면 아래 server.close() 의
            // waitForAllConnectionsToClose 가 기다릴 대상이 없어 바로 지나간다.
            for (QuicConnection c : live) {
                try { c.close(); } catch (Exception ignored) {}
            }
            live.clear();
            if (s != null) s.close();
            long tServer = System.currentTimeMillis();
            if (agent != null) agent.close();
            long tIce = System.currentTimeMillis();
            worker.shutdownNow();
            LOG.debug("[quic-host] stopped — 랑데부 {}ms, QUIC 서버 {}ms, ICE {}ms, 합계 {}ms",
                    tLobby - t0, tServer - tLobby, tIce - tServer,
                    System.currentTimeMillis() - t0);
        }, "quic-host-close");
        t.setDaemon(true); // 게임이 먼저 끝나면 같이 사라져도 된다 — 소켓은 OS 가 정리한다
        t.start();
    }

    // ── 랑데부: 서버가 맺어 주는 접속자 ────────────────────────────────────────

    /**
     * 랑데부 서버에 방장으로 붙는다. 접속자가 오면 서버가 {@code join}(서버가 정한 sid)을 보내고,
     * 그 접속자가 보낸 후보는 sid 를 붙여 여기로만 온다. 우리가 sid 를 붙여 보낸 것은 그 접속자에게만
     * 간다(서버 rendezvous.go). 예전 VILLAS 로비·페어 세션은 아무나 들어와 가짜 지문·후보를 넣거나
     * 남의 후보(IP)를 엿볼 수 있었다.
     */
    private void openLobby() throws Exception {
        // 토큰은 붙을 때마다 다시 받는다 — 방이 토큰 수명(12시간)보다 오래 열려 있어도 재접속이 막히지 않게
        // (ensureToken 은 남은 시간이 넉넉하면 네트워크 없이 그대로 준다).
        String token = hooks.authToken();
        WebSocketClient ws = new WebSocketClient(signalingUrl + "/rv/" + roomId + "/host?key=" + hostKey
                + (token != null ? "&token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8) : "")) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS; // 붙었으니 다음 끊김은 다시 짧게 재시도
                LOG.debug("[quic-host] 랑데부 연결됨");
            }
            @Override public void onMessage(String type, String json) {
                handleRendezvous(json);
            }
            @Override public void onDisconnected() {
                scheduleLobbyReconnect();
            }
        };
        lobby = ws;
        ws.connect();
    }

    /**
     * 랑데부가 끊기면 다시 붙는다(같은 key 라 방 코드를 되찾는다). <b>이게 없으면 시그널링이 한 번 끊긴 뒤로 조인 감지가 영구히
     * 멈춰</b> 방장은 방이 열려 있다고 믿는데 아무도 못 들어오는 상태가 된다(예전 WebRTC 호스트도
     * 같은 이유로 백오프 재접속을 갖고 있었다).
     */
    private void scheduleLobbyReconnect() {
        if (!running.get()) return;
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        worker.execute(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
            if (!running.get()) return;
            try {
                LOG.info("[quic-host] 랑데부 재접속 시도");
                openLobby();
            } catch (Exception e) {
                LOG.warn("[quic-host] 랑데부 재접속 실패: {}", e.getMessage());
                scheduleLobbyReconnect();
            }
        });
    }

    /** 서버가 보낸 것: join(새 접속자) / leave(접속자가 떠남) / sid 가 붙은 후보. */
    private void handleRendezvous(String json) {
        String join = VillasMsg.object(json, "join");
        if (join != null) {
            onJoin(join);
            return;
        }
        String leave = VillasMsg.object(json, "leave");
        if (leave != null) {
            String sid = VillasMsg.field(leave, "sid");
            if (sid != null) {
                joiners.remove(sid);
                java.util.concurrent.Future<?> f = negotiations.remove(sid);
                if (f != null) f.cancel(true); // punch 는 인터럽트되면 멈춘다
            }
            return;
        }
        String cand = VillasMsg.object(json, "candidate");
        String sid = VillasMsg.field(json, "sid");
        if (cand == null || sid == null) return;
        List<QuicIce.Candidate> theirs = joiners.get(sid);
        QuicIce agent = ice;
        if (theirs == null || agent == null) return;
        QuicIce.Candidate c = QuicIce.Candidate.parse(VillasMsg.field(cand, "spd"));
        // 접속자 한 명당 상한 — 넘치는 건 버린다(Candidate.parse 주석: 반사 공격 방지).
        if (c != null && !theirs.contains(c) && theirs.size() < QuicIce.Candidate.MAX_PER_PEER) {
            agent.addRemote(c);
            theirs.add(c);
        }
    }

    private void onJoin(String join) {
        String sid = VillasMsg.field(join, "sid");
        // sid 는 서버가 만들지만 우리 쪽 맵 키로 쓰므로 형식은 확인한다(16 hex).
        if (sid == null || !sid.matches("[0-9a-f]{16}")) return;
        // 입장 전 확인(RoomMembersProbe) — 연결하지 않고 지금 접속자 해시만 알려주고 끝.
        if ("true".equals(VillasMsg.field(join, "probe"))) {
            send(sid, VillasMsg.description("members", hooks.onlinePlayerHashes(roomId)));
            return;
        }
        // relay=true: 접속자가 이미 「중계 통신 강제」를 켠 상태다. 그러면 방장도 곧장 중계로 간다 —
        // 접속자는 어차피 자기 allocation 밖으로만 보내므로, 방장이 host/srflx 후보를 내놓고
        // 직결부터 시도해 봐도 그 경로는 성립하지 않는다. 그 헛된 왕복이 접속 지연으로 남는다.
        // (예전 WebRTC 호스트도 같은 신호를 보고 clientRelayForced 로 판단했다.)
        final boolean clientRelayForced = "true".equals(VillasMsg.field(join, "relay"));
        // IP는 로그에 남기지 않는다 — 방장이 로그를 공유하면 접속자 IP가 박제된다. sid로 충분.
        LOG.debug("[quic-host] join detected sid={} clientRelayForced={}", sid, clientRelayForced);
        // 동시 협상 상한 — 협상 하나가 최대 PUNCH_MS 동안 체크를 보내므로, 가짜 접속을 잔뜩 만들면
        // 그 수만큼 증폭된다(서버도 방·IP 단위로 막지만 한 겹 더). 성공한 협상은 1초 안에 끝나
        // 자리를 돌려준다. 넘치면 접속자가 재시도하면 된다.
        if (!negotiating.tryAcquire()) {
            LOG.warn("[quic-host] 동시 협상이 너무 많다 — sid={} 는 건너뛴다", sid);
            return;
        }
        // 후보 목록은 <b>지금</b> 만든다 — 이 접속자의 후보가 바로 뒤이어 같은 연결로 들어온다.
        List<QuicIce.Candidate> theirs = new java.util.concurrent.CopyOnWriteArrayList<>();
        joiners.put(sid, theirs);
        negotiations.put(sid, worker.submit(() -> {
            try {
                negotiate(sid, clientRelayForced, theirs);
            } finally {
                joiners.remove(sid);
                negotiations.remove(sid);
                negotiating.release();
            }
        }));
    }

    /** 이 접속자에게만 간다 — 서버가 sid 로 라우팅한다. */
    private void send(String sid, String msg) {
        WebSocketClient ws = lobby;
        if (ws != null) ws.send("{\"sid\":\"" + sid + "\"," + msg.substring(1));
    }

    /**
     * 지문·후보를 보내고 홀을 뚫는다. {@code theirs} 는 이 접속자의 후보만 모인 목록이다 — 소켓과
     * ICE 는 접속자 전원이 공유하므로, 뚫을 때는 반드시 자기 후보로만 판정해야 한다(QuicIce 의
     * responsive 주석). 후보는 트리클로 채워진다(handleRendezvous).
     */
    private void negotiate(String sid, boolean clientRelayForced, List<QuicIce.Candidate> theirs) {
        QuicIce agent = ice;
        if (agent == null) return;
        // 「중계 통신 강제」를 이 접속자를 맞이하는 지금 읽는다 — 방을 연 뒤 체크박스를 켜도
        // 다음 접속자부터 먹어야 한다(HostHooks.relayOnlyNow 주석).
        //
        // 접속자가 켜 놓은 것과 내가 켠 것은 <b>역할이 다르다</b>:
        //  - 내가 켰으면 내 트래픽이 내 allocation 을 거쳐야 한다(= relayNow, 내 IP 를 숨긴다).
        //  - 접속자가 켰으면 내 트래픽은 그대로 보내도 되고, 대신 <b>내 host 후보를 알리지 않는다</b>
        //    (사설 IP 는 상대의 allocation 에서 절대 닿을 수 없어 헛된 왕복만 된다).
        // 둘을 OR 로 묶어 방장까지 중계로 밀었더니 relay→relay 2홉이 되어 오히려 느려졌다
        // (실측 622ms → 7878ms). 그래서 분리한다.
        final boolean relayNow = hooks.relayOnlyNow();
        List<QuicIce.Candidate> mine = QuicIce.advertised(candidates, relayNow, clientRelayForced);
        if (mine.isEmpty()) {
            // 중계 강제인데 relay 후보가 없다(중계 계정을 못 받음 — 정품 인증 안 된 계정 등). 실주소를 대신
            // 내놓을 수는 없으니 접속자에게 바로 알린다. 예전엔 후보 0개로 접속자가 30초를 기다리다 실패했다.
            LOG.warn("[quic-host] 중계 통신 강제인데 중계 서버를 못 쓴다(중계 계정 없음) — sid={} 를 받을 수 없다", sid);
            send(sid, VillasMsg.description(MSG_NO_RELAY, ""));
            return;
        }
        try {
            send(sid, VillasMsg.description(MSG_ANSWER, fingerprint));
            // 중계 강제면 relay 후보만 알려줘 접속자가 우리 실주소를 아예 모르게 한다.
            for (QuicIce.Candidate c : mine) {
                send(sid, VillasMsg.candidate(c.line(), "0"));
            }
            // ownLoop=false — ServerConnector 가 이미 수신 루프를 돌리고 있다(클래스 주석).
            QuicIce.Candidate picked = agent.punch(theirs, PUNCH_MS, false, relayNow);
            // 통신방식 기록은 여기서 하지 않는다 — QUIC 연결이 실제로 성립한 주소로 판정한다
            // (createConnection 주석). punch 가 실패해도 접속자는 자기 쪽 경로로 붙을 수 있다.
            LOG.debug("[quic-host] punch 종료 sid={} 결과={}", sid,
                    picked != null ? picked.type() : "없음(접속자 쪽 경로로 붙을 수 있다)");
        } catch (Exception e) {
            LOG.warn("[quic-host] 협상 실패 sid={}: {}", sid, e.getMessage());
        }
    }

    /**
     * 이 주소가 우리 TURN 서버인지 — 그러면 중계를 거쳐 온 것이다.
     * 주소로 판정하므로 punch 결과와 무관하게 항상 맞는다.
     */
    private boolean isRelayAddress(String ip) {
        if (turnUrl == null) return false;
        java.net.InetSocketAddress turn = TurnAllocation.parseUrl(turnUrl);
        if (turn == null || turn.getAddress() == null) return false;
        return turn.getAddress().getHostAddress().equals(ip);
    }

    // ── 들어온 QUIC 스트림 → LAN 포트 ────────────────────────────────────────

    /**
     * kwik 은 이 값들을 안 정해 주면 경고를 남기고 기본 설정으로 넘어간다
     * ({@code ServerConnectionImpl.configure}). 값 자체는 기본 설정과 같게 두되 명시해서
     * <b>의도한 값</b>임을 남긴다 — merge() 가 여기 최소치로 버퍼를 끌어올리므로, 잘못 적으면
     * 흐름 제어가 조용히 좁아진다.
     */
    private final class TunnelFactory implements ApplicationProtocolConnectionFactory {
        /** 마크는 접속자당 TCP 연결 하나 = 스트림 하나지만, 재접속 중 겹칠 수 있어 여유를 둔다. */
        @Override public int maxConcurrentPeerInitiatedBidirectionalStreams() { return 100; }
        /** 단방향 스트림은 쓰지 않는다. */
        @Override public int maxConcurrentPeerInitiatedUnidirectionalStreams() { return 0; }
        /** 접속자→방장 방향 흐름 제어 하한. 기본 설정(1MB)과 같게 둔다. */
        @Override public int minBidirectionalStreamReceiverBufferSize() { return 1_000_000; }

        @Override public ApplicationProtocolConnection createConnection(String protocol, QuicConnection conn) {
            // 상대 주소는 여기서만 얻을 수 있다. QuicConnection 자체에는 접근자가 없고
            // ServerConnection 이 그걸 가지고 있다 — kwik 이 넘겨주는 객체가 그 구현이다.
            live.add(conn);
            conn.setConnectionListener(event -> live.remove(conn));

            final java.net.InetSocketAddress peerAddr = (conn instanceof ServerConnection sc)
                    ? sc.getInitialRemoteAddress() : null;
            final String peerIp = peerAddr != null ? peerAddr.getAddress().getHostAddress() : null;
            if (peerIp == null) {
                LOG.warn("[quic-host] 상대 주소를 못 잡았다 — 이 세션엔 IP 밴이 적용되지 않는다");
            } else {
                // 직결/중계는 <b>QUIC 이 실제로 어디서 왔는지</b>로 판정한다.
                // 예전에는 방장 쪽 punch 결과를 썼는데, punch 는 "방장→접속자" 방향 검증이라
                // VPN 뒤 접속자처럼 방장이 그쪽 후보에 못 닿으면 null 이 됐다. 그러면 접속자는
                // 자기 쪽에서 경로를 찾아 멀쩡히 붙었는데도 참여 메시지에 통신방식이 안 붙었다.
                // 출처 주소는 연결이 성립한 이상 항상 있고, 그게 곧 사실이다.
                // 예전 {@code WebRtcStats} 규칙 그대로: <b>양쪽 중 하나라도 relay 면 중계</b>다.
                // 출처 주소만 보면 "방장이 자기 allocation 으로 내보내는" 방향을 놓쳐서, 중계
                // 강제인 방인데도 직결로 표시된다.
                QuicIce agent = ice;
                boolean relayed = isRelayAddress(peerIp)
                        || (agent != null && agent.sendsViaRelayTo(peerAddr));
                hooks.registerConnectionType(peerIp, relayed);

                // 2초 뒤 한 번 다시 본다. 우리 쪽 채널 바인딩은 punch 와 나란히 돌아서 연결이
                // 먼저 성립할 수 있고, 그러면 위에서 직결로 읽힌다. 예전 WebRTC 구현도 같은
                // 이유로 2초 뒤 재확인해서 값을 고쳤다.
                if (!relayed) {
                    worker.execute(() -> {
                        try { Thread.sleep(2_000); } catch (InterruptedException e) { return; }
                        QuicIce a2 = ice;
                        if (a2 != null && a2.sendsViaRelayTo(peerAddr)) {
                            LOG.debug("[quic-host] 통신방식 정정: 직결 → 중계");
                            hooks.registerConnectionType(peerIp, true);
                        }
                    });
                }
            }
            // acceptPeerInitiatedStream 이 default 메서드라 함수형 인터페이스가 아니다 — 익명 클래스로.
            return new ApplicationProtocolConnection() {
                @Override public void acceptPeerInitiatedStream(QuicStream stream) {
                    worker.execute(() -> bridge(stream, peerIp));
                }
            };
        }
    }

    private void bridge(QuicStream stream, String peerIp) {
        try {
            Socket tcp = new Socket();
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(targetHost, targetPort), DIAL_TIMEOUT_MS);
            // 터널을 지나면 모든 접속자가 127.0.0.1 로 보이므로, IP 밴이 먹으려면 이 매핑이 필요하다
            // (P2PBanManager 클래스 주석). 포트는 통합 서버 쪽에서 보이는 그 포트다.
            int tunnelPort = tcp.getLocalPort();
            if (peerIp != null) hooks.registerTunnelPort(tunnelPort, peerIp);
            QuicPump.wire(tcp, stream.getInputStream(), stream.getOutputStream(), "host",
                    peerIp != null ? () -> hooks.unregisterTunnelPort(tunnelPort) : null);
        } catch (IOException e) {
            LOG.warn("[quic-host] target dial 실패 {}:{} — {}", targetHost, targetPort, e.getMessage());
            try { stream.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }
}
