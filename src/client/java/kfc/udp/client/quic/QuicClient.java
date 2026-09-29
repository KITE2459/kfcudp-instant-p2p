package kfc.udp.client.quic;

import kfc.udp.client.signaling.VillasMsg;
import kfc.udp.client.signaling.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QUIC 접속자 — 로컬 TCP 리스너를 열고, 마크가 붙으면 홀펀칭 후 QUIC으로 방장에게 잇는다.
 * <p>
 * 흐름: 리스너 오픈(마크에 포트 반환) → 마크 접속 → 후보 수집 → 페어 세션에서 방장 등장을 기다려
 * 지문·후보 교환 → 홀펀칭 → <b>수신 루프를 kwik에 넘기고</b> QUIC 연결 → 지문 대조 → 스트림 파이프.
 * <p>
 * <b>방장 등장을 기다리는 이유</b> — 시그널링 서버는 지금 붙어 있는 peer에게만 중계한다. 페어 세션에
 * 먼저 들어가 놓고 후보를 바로 쏘면 방장이 아직 없어서 그대로 버려진다. 그래서 control 메시지로
 * 방장 peer({@code h…})가 보일 때까지 기다린 뒤 보낸다.
 * <p>
 * <b>지문 대조 시점</b> — kwik의 내장 검증은 인증서 이름을 접속 주소와 맞춰 보는데, 홀펀칭으로 고른
 * 주소는 미리 알 수 없어 인증서에 넣을 수 없다(peer-reflexive면 방장도 모른다). 그래서 내장 검증을
 * 끄고 <b>핸드셰이크 직후·데이터 전송 전에</b> 지문을 직접 대조한다. TLS 1.3 핸드셰이크가 끝난
 * 시점에 상대는 이미 개인키 소유를 증명했으므로 동등하게 안전하다. 대가로 연결마다 kwik이 stdout에
 * INSECURE 경고를 한 줄 찍는다 — 후보 IP를 전부 SAN에 넣으면 없앨 수 있지만, peer-reflexive 주소가
 * SAN에 없어 연결이 아예 실패하는 경우가 생기므로 그 교환은 하지 않는다.
 */
public final class QuicClient {

    private static final Logger LOG = LoggerFactory.getLogger("quic-client");

    /**
     * 스트림 수신 버퍼 — kwik 기본값은 250KB 인데 방장 쪽 기본값은 1MB 라 비대칭이다.
     * 청크가 흐르는 방향(방장→접속자)의 상한이 이 값이므로 방장과 같게 맞춘다. 연결 레벨
     * 버퍼는 kwik 이 이 값의 10배로 잡는다(MAX_DATA_FACTOR).
     */
    private static final long STREAM_BUFFER = 1_000_000L;

    /** 첫 후보들의 핸드셰이크 한도 — 안 되면 빨리 포기하고 다음 후보로 넘어간다. */
    private static final long TRY_HANDSHAKE_MS = 1_500;
    /** 마지막 후보는 넉넉히 — 더 갈 곳이 없다. */
    private static final long LAST_HANDSHAKE_MS = 8_000;
    /** initialRtt 바닥값. 실제보다 낮게 주면 손실 판정이 과민해진다(위 handshake 주석). */
    private static final long RTT_FLOOR_MS = 50;

    /** 방장이 페어 세션에 나타나기를 기다리는 한도. */
    private static final long PEER_WAIT_MS = 15_000;
    /**
     * 1차(직결 전용) 한도. 시그널링·coturn 이 다 가까이 있는 배포 환경에서, 홀펀칭이 되는 조합이면
     * 이 안에 거의 항상 판명난다 — 안 되면 더 기다려도 대개 소용없다. 예전 WebRTC 구현이 쓰던
     * 값(2초)을 그대로 가져왔다.
     */
    private static final long DIRECT_ATTEMPT_MS = 2_000;
    /** QUIC PING 간격 — 방장 유휴 시간(30초)의 절반. */
    private static final long PING_MS = 15_000;
    /**
     * 마크 연결이 끝난 뒤 터널을 닫기까지 기다리는 시간 — 마지막 데이터(스트림 FIN)가 방장에게
     * 닿을 여유다. 방장 쪽이 방을 닫을 때 2초 기다리는 것과 같은 이유(KfcudpClient 주석).
     */
    private static final long CLOSE_AFTER_MS = 2_000;
    /** 2차(중계 허용) 한도 — 예전 구현과 같은 30초. */
    private static final long RELAY_ATTEMPT_MS = 30_000;
    /** 페어 세션을 열어 둘 시간의 기준 — 두 단계가 다 끝날 때까지 트리클을 받아야 한다. */
    private static final long PUNCH_MS = DIRECT_ATTEMPT_MS + RELAY_ATTEMPT_MS;

    private final boolean relayOnly;
    private final String turnUrl;
    private final String turnUser;
    private final String turnPass;
    private final String signalingUrl;
    private final String stunUrl;
    private final String roomId;
    private final int localPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket listener;
    private volatile QuicClientConnection quic;
    private volatile QuicIce ice;
    /** 이 연결이 확정한 경로 — 직결/중계 표시는 이 후보로 판정한다. */
    private volatile QuicIce.Candidate path;
    /** 열려 있는 랑데부 연결 — 중계 폴백에서 후보를 추가로 흘려보낼 때 쓴다. */
    private volatile WebSocketClient pairSession;
    /** 방장이 협상 때 준 접속 표 — 모든 스트림 맨 앞에 싣는다(QuicHost.tickets 주석). */
    private volatile byte[] ticket;
    /** 정품 토큰(있으면) — 랑데부 접속에 실어 서버의 접속 기록에 검증된 UUID 가 남게 한다(없어도 접속은 된다). */
    volatile String authToken;

    /** URL 을 넘겨받는 이유는 {@link QuicHost} 생성자 주석과 같다(게임 밖에서도 돌아야 한다). */
    public QuicClient(String roomId, int localPort, String signalingUrl, String stunUrl,
                      String turnUrl, String turnUser, String turnPass, boolean relayOnly) {
        this.relayOnly = relayOnly;
        this.turnUrl = turnUrl;
        this.turnUser = turnUser;
        this.turnPass = turnPass;
        this.signalingUrl = signalingUrl;
        this.stunUrl = stunUrl;
        this.roomId = roomId;
        this.localPort = localPort;
    }

    /**
     * 리스너를 열고 <b>협상을 곧바로 시작한다</b>. 둘 다 즉시 반환한다.
     * <p>
     * 협상을 첫 TCP accept 까지 미루면 마크가 접속을 기다리는 시간에 시그널링·홀펀칭·QUIC
     * 핸드셰이크가 <b>직렬로</b> 얹혀 체감 지연이 그만큼 길어진다. 예전 WebRTC 클라이언트도
     * {@code start()} 에서 {@code connectPairSignaling()} 을 먼저 불러 마크 접속과 겹쳐 놨었다.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress("127.0.0.1", localPort));
        listener = ss;
        LOG.debug("[quic-client] local listener 127.0.0.1:{}", localPort);

        Thread t = new Thread(this::acceptLoop, "quic-client-accept");
        t.setDaemon(true);
        t.start();

        // 마크가 붙기 전에 미리 붙여 둔다. 실패해도 여기서 죽이지 않고 serve() 에서 한 번 더
        // 시도한다 — 방이 아직 안 열렸는데 먼저 들어온 경우 등.
        Thread warm = new Thread(() -> {
            try {
                ensureConnected();
            } catch (Exception e) {
                LOG.debug("[quic-client] 선행 협상 실패(접속 시 재시도): {}", e.getMessage());
            }
        }, "quic-client-warmup");
        warm.setDaemon(true);
        warm.start();
    }

    /**
     * 이 세션의 연결 방식 — {@code null} = 아직 안 붙음(= quic 세션이 아님).
     * <b>중계는 3단계에서 붙는다</b>: 지금은 홀펀칭만 있으므로 붙었으면 항상 직결이다.
     * 호출부가 {@code != null} 을 "우리 방의 접속자 세션인지" 판별에 쓰므로(KfcudpClient의
     * isGuestSession) 붙기 전에는 반드시 null 이어야 한다.
     */
    public Boolean usesRelay() {
        QuicClientConnection c = quic;
        QuicIce agent = ice;
        QuicIce.Candidate p = path;
        if (c == null || !c.isConnected() || agent == null || p == null) return null;
        return agent.usesRelay(p);
    }

    /** 방장 쪽과 같은 이유로 정리를 뒤로 넘긴다({@code QuicHost.close} 주석) — 부르는 쪽을 막지 않는다. */
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        ServerSocket ss = listener;
        // 리스너는 바로 닫는다 — 이건 로컬 소켓이라 즉시 끝나고, 닫아야 새 접속이 안 들어온다.
        if (ss != null) try { ss.close(); } catch (IOException ignored) {}
        // 랑데부도 바로 닫는다 — 방장이 leave 를 받아 이 접속 협상을 끊는다(QuicHost.negotiations).
        WebSocketClient ps = pairSession;
        if (ps != null) ps.close();

        QuicClientConnection q = quic;
        QuicIce agent = ice;
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            if (q != null) q.close();
            if (agent != null) agent.close();
            LOG.debug("[quic-client] stopped ({}ms)", System.currentTimeMillis() - t0);
        }, "quic-client-close");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket tcp = listener.accept();
                tcp.setTcpNoDelay(true);
                Thread conn = new Thread(() -> serve(tcp), "quic-client-conn");
                conn.setDaemon(true);
                conn.start();
            } catch (IOException e) {
                if (running.get()) LOG.debug("[quic-client] accept 종료: {}", e.getMessage());
                return;
            }
        }
    }

    /**
     * QUIC PING 을 주기적으로 보낸다 — 마크 트래픽이 뜸한 순간에도 연결과 NAT·중계 경로가 살아 있게.
     * libwebrtc 가 연결마다 체크를 보내 경로를 유지하던 것과 같은 역할이다.
     * <p>
     * kwik 의 {@code keepAlive()} 는 쓰지 않는다. 그게 만드는 스케줄러 스레드
     * ({@code KeepAliveActor.createScheduler}: 기본 팩토리)가 <b>데몬이 아니라서</b>, 연결이 남은 채
     * 게임을 끄면 JVM 이 종료되지 못하고 마크 종료 감시가 15초 뒤 크래시 리포트를 남겼다
     * ("Client shutdown from post-main"). 여기선 데몬 스레드로 직접 보낸다.
     */
    /** PING 한 번 — 경로를 옮긴 직후 밀린 재전송이 새 경로로 바로 나가게 kwik 을 깨운다. */
    private static void pingNow(QuicClientConnection conn) {
        try {
            conn.getClass().getMethod("ping").invoke(conn);
        } catch (Exception ignored) {
            // 끊겼거나 kwik 에 ping() 이 없다 — 다음 전송 때 어차피 새 경로로 나간다
        }
    }

    private void startPing(QuicClientConnection conn) {
        // ping() 은 구현 클래스에만 공개돼 있다. 그 클래스를 직접 참조하면 부모 타입이 든 agent15 가
        // 컴파일 경로에 있어야 하는데 kwik POM 은 runtime 스코프라, 의존성을 늘리지 않고 리플렉션으로 부른다.
        java.lang.reflect.Method ping;
        try {
            ping = conn.getClass().getMethod("ping");
        } catch (NoSuchMethodException e) {
            LOG.warn("[quic-client] kwik 에 ping() 이 없다 — PING 없이 진행한다");
            return;
        }
        Thread t = new Thread(() -> {
            while (running.get() && conn.isConnected()) {
                try {
                    Thread.sleep(PING_MS);
                    ping.invoke(conn);
                } catch (Exception e) {
                    return; // 끊겼거나 닫히는 중
                }
            }
        }, "quic-client-ping");
        t.setDaemon(true);
        t.start();
    }

    /** 마크 연결이 끝나면 터널도 닫는다 — 한 번만(펌프는 방향마다 부른다). */
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * 마크가 서버를 나가면 터널(QUIC 연결·중계 할당·PING)을 닫는다. 예전엔 닫지 않아서 다음 접속이나
     * 게임 종료 때까지 방장과의 연결과 coturn allocation 을 붙잡고 있었다(40분 남은 사례가 있었다).
     */
    private void closeSoon() {
        if (!closing.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(CLOSE_AFTER_MS); } catch (InterruptedException ignored) {}
            LOG.debug("[quic-client] 마크 연결이 끝나 터널을 닫는다");
            close();
        }, "quic-client-close-soon");
        t.setDaemon(true);
        t.start();
    }

    /** 연결은 한 번만 세우고 이후 TCP 접속은 스트림만 새로 연다 — QUIC이 다중화를 해 준다. */
    private void serve(Socket tcp) {
        try {
            QuicClientConnection conn = ensureConnected();
            QuicStream stream = conn.createStream(true);
            stream.getOutputStream().write(ticket); // 표 없는 스트림은 방장이 연결째 끊는다
            QuicPump.wire(tcp, stream.getInputStream(), stream.getOutputStream(), "client", this::closeSoon);
        } catch (Exception e) {
            LOG.warn("[quic-client] 연결 실패: {}", e.getMessage() != null ? e.getMessage() : e.toString());
            try { tcp.close(); } catch (IOException ignored) {}
        }
    }

    /** 연결을 한 번만 세운다 — start() 의 선행 협상과 첫 accept 가 겹쳐도 한 번만 붙는다. */
    private synchronized QuicClientConnection ensureConnected() throws Exception {
        QuicClientConnection conn = quic;
        if (conn != null && conn.isConnected()) return conn;
        // 닫힌 뒤엔 새로 붙지 않는다. 선행 협상이 실패하면 기다리던 serve() 가 여기로 들어오는데, 그 사이
        // close() 됐으면(재접속으로 새 클라이언트가 생김) 유령 협상이 방장 자리와 coturn 할당을 붙잡았다.
        if (!running.get()) throw new IOException("이미 닫힌 연결");
        conn = connectToHost();
        quic = conn;
        return conn;
    }

    // ── ICE → QUIC ───────────────────────────────────────────────────────────

    private QuicClientConnection connectToHost() throws Exception {
        // 단계별 소요를 남긴다 — "접속이 느리다" 를 추측이 아니라 숫자로 좁히려면 이게 필요하다.
        long t0 = System.currentTimeMillis();
        QuicIce agent = new QuicIce(stunUrl, relayOnly);
        ice = agent;
        // 중계 강제일 때만 접속자도 allocation 을 잡는다 — 그래야 방장에게 내 IP 대신 coturn
        // 주소만 알려줄 수 있다. 평소엔 방장의 relay 후보로 보내면 되므로 잡지 않는다(coturn 쿼터 절약).
        if (relayOnly) agent.enableTurn(turnUrl, turnUser, turnPass);
        // 중계 강제면 relay 후보만 알린다 — 그게 "내 IP 를 숨긴다" 의 실제 내용이다.
        // 방장이 중계를 쓰는지는 후보를 받아 봐야 알므로, 알리는 시점엔 모른다(false).
        // 우리가 강제면 어차피 relay 후보만 나간다.
        List<QuicIce.Candidate> mine = QuicIce.advertised(agent.gather(), relayOnly, false);

        long tGather = System.currentTimeMillis();
        String expectedFp = exchange(agent, mine);
        long tExchange = System.currentTimeMillis();

        // ── 2단계 시도. 예전 WebRTC 구현의 순서를 그대로 옮겼다.
        //
        // 1차는 <b>직결 후보만</b> 쓴다(2초). 중계를 섞으면 TURN 이 홀펀칭보다 먼저 성사돼서,
        // 직결이 되는 상대인데도 중계로 확정돼 버린다 — 실제로 그렇게 붙은 보고가 있었다.
        // 2차는 우리 allocation 까지 잡고 중계를 허용한다. 「중계 통신 강제」를 켜지 않아도
        // 그렇게 한다. 그게 "자동" 의 뜻이다: 직결이 되면 직결, 안 되면 중계.
        //
        // 어느 한쪽이라도 중계 강제면 1차는 건너뛴다 — 직결 쌍이 아예 만들어지지 않으므로
        // 2초를 그냥 버리는 셈이다("중계를 켜면 접속이 더 늘어진다"의 원인).
        //
        // ownLoop=true — connect 전이라 kwik이 아직 소켓을 읽지 않는다.
        boolean skipDirect = relayOnly || agent.peerIsRelayOnly();
        QuicIce.Candidate picked = null;
        if (!skipDirect) {
            picked = agent.punchDirectOnly(DIRECT_ATTEMPT_MS, true);
            if (picked == null) LOG.debug("[quic-client] 1차 직결 {}ms 안에 안 됨 — 중계 포함으로 넘어간다", DIRECT_ATTEMPT_MS);
        }

        if (picked == null) {
            // VPN·대칭 NAT 뒤 접속자는 광고한 후보와 실제 출처가 달라 방장이 권한을 못 걸고, 그래서
            // 방장의 relay 후보로도 못 간다 — 우리가 allocation 을 잡으면 나가는 방향만 쓰므로 그
            // 문제가 사라진다(보고: "중계 강제를 끄고 들어가니 서버 연결 실패").
            if (!relayOnly && turnUrl != null) {
                // 1차에서 돌던 수신 루프를 먼저 멈춘다. allocate 는 응답을 직접 읽는데, 루프가 살아
                // 있으면 그 응답을 가로채 버려서 "allocate 무응답"이 났다 — 즉 자동 모드에서 직결이
                // 안 될 때 중계로 넘어가는 길이 통째로 막혀 있었다. punch 가 루프를 다시 켠다.
                agent.stopOwnLoop();
                agent.enableTurn(turnUrl, turnUser, turnPass);
                WebSocketClient ps = pairSession;
                if (ps != null) {
                    for (QuicIce.Candidate c : QuicIce.advertised(agent.gather(), true, false)) {
                        ps.send(VillasMsg.candidate(c.line(), "0"));
                    }
                }
            }
            picked = agent.punch(RELAY_ATTEMPT_MS, true);
        }

        if (picked == null) {
            agent.close();
            // 중계 강제인데 못 뚫었으면 원인이 거의 정해져 있다 — 상대도 중계 강제라 양쪽 relay
            // 후보만 오간 경우다. coturn 의 multiplex-peer 가 그 조합을 막는다(TurnAllocation 주석).
            throw new IOException(relayOnly
                    ? "중계 경로를 열지 못했습니다 (상대도 중계 강제면 현재 서버 설정에서는 불가)"
                    : "경로를 뚫지 못했습니다 (양쪽이 대칭 NAT면 중계 통신 강제를 켜보세요)");
        }
        // 여기서 반드시 손을 떼야 한다. 우리 루프와 kwik 루프가 같이 돌면 패킷을 서로 훔쳐간다.
        agent.stopOwnLoop();
        long tPunch = System.currentTimeMillis();

        // 응답한 후보를 차례로 시도한다. 첫 후보에 커밋하면 안 된다 — STUN 체크는 통했는데 QUIC 이
        // 그 경로로 못 붙는 경우가 실제로 있다(실측: srflx 가 213ms 에 응답했지만 핸드셰이크가
        // 10초 타임아웃까지 끌었고 relay 로 바꾸니 즉시 붙었다). 그때 10초를 버리는 게 체감 지연의
        // 가장 큰 덩어리였다.
        List<QuicIce.Candidate> tries = new java.util.ArrayList<>();
        tries.add(picked);
        for (QuicIce.Candidate c : agent.responsiveCandidates()) {
            if (!tries.contains(c)) tries.add(c);
        }

        IOException last = null;
        for (int i = 0; i < tries.size(); i++) {
            QuicIce.Candidate cand = tries.get(i);
            boolean lastTry = i == tries.size() - 1;
            try {
                QuicClientConnection conn = handshake(agent, cand, expectedFp, lastTry);
                this.path = cand;
                // 게임 중 경로가 바뀌어도 이어지게 — 끊기면 다른 후보로 옮기고 PING 으로 kwik 을 깨운다(QuicIce 주석).
                agent.startPathMonitor(cand.address(), () -> pingNow(conn));
                long tDone = System.currentTimeMillis();
                // 경로는 상대 후보 종류가 아니라 <b>실제로 중계를 타는지</b>다 — 내가 중계 강제면 상대의
                // srflx 로 보내도 내 allocation 을 거친다.
                LOG.info("[quic-client] connected (지문 확인됨) 경로={} — 후보수집 {}ms,"
                                + " 시그널링 {}ms, 홀펀칭 {}ms, QUIC 핸드셰이크 {}ms, 합계 {}ms",
                        agent.usesRelay(cand) ? "relay" : cand.type(), tGather - t0, tExchange - tGather, tPunch - tExchange,
                        tDone - tPunch, tDone - t0);
                // 붙었으면 랑데부는 더 쓸 일이 없다 — 바로 닫아 서버의 방당 동시 접속 자리를 돌려준다.
                WebSocketClient ps = pairSession;
                if (ps != null) ps.close();
                return conn;
            } catch (IOException e) {
                last = e;
                LOG.debug("[quic-client] typ={} 경로로는 QUIC 이 안 붙는다 ({}) — 다음 후보로",
                        cand.type(), e.getMessage());
            }
        }
        agent.close();
        throw last != null ? last : new IOException("QUIC 핸드셰이크 실패");
    }

    /**
     * 후보 하나로 QUIC 핸드셰이크 + 지문 대조.
     *
     * @param lastTry 마지막 후보면 넉넉히 기다린다. 그 앞 후보들은 <b>짧게 끊고 다음으로 넘긴다</b> —
     *                안 되는 경로에서 10초를 버리는 것이 체감 지연의 주범이었다.
     */
    private QuicClientConnection handshake(QuicIce agent, QuicIce.Candidate cand,
                                           String expectedFp, boolean lastTry) throws Exception {
        InetSocketAddress to = cand.address();
        // noServerCertificateCheck 를 쓰면 kwik 이 연결마다 "SECURITY WARNING: INSECURE" 를 stdout 에
        // 찍는다. 여기선 잘못된 경보다 — 인증서 검증은 끄는 게 아니라 아래 지문 대조로 대신한다
        // (데이터를 보내기 전에 끊는다). kwik 이 이 경고만 끄라고 열어 둔 속성이다.
        System.setProperty("tech.kwik.core.no-security-warnings", "true");
        QuicClientConnection conn = QuicClientConnection.newBuilder()
                .host(to.getAddress().getHostAddress()).port(to.getPort())
                .applicationProtocol(QuicHost.ALPN)
                .socketFactory(dest -> agent.socket())   // 뚫어 놓은 그 소켓 그대로
                .noServerCertificateCheck()              // 대신 아래에서 지문을 직접 대조(클래스 주석)
                .connectTimeout(Duration.ofMillis(lastTry ? LAST_HANDSHAKE_MS : TRY_HANDSHAKE_MS))
                // 방장 기본값(1MB)과 같게 — 기본 250KB 면 청크 방향이 먼저 막힌다(상단 주석).
                .defaultStreamReceiveBufferSize(STREAM_BUFFER)
                // kwik 의 기본 initialRtt 는 500ms 다(RttEstimator) — 첫 손실 판정이 그만큼 늦다.
                // 홀펀칭에서 잰 왕복을 넘기되 <b>바닥을 둔다</b> — 실제보다 낮게 주면 손실 판정이
                // 과민해져 불필요한 재전송이 늘어난다(KcpCore 의 RTO_MIN 과 같은 이유).
                .initialRtt((int) Math.max(RTT_FLOOR_MS, agent.lastRttMs()))
                // 연결 ID 길이를 고정한다 — 경로가 바뀐 패킷을 연결 ID 로 알아보는데(QuicIce.IceSocket),
                // short header 에는 길이가 안 실려서 양쪽이 약속한 값이어야 한다(방장 kwik 기본값 8).
                .connectionIdLength(QuicIce.IceSocket.CID_LENGTH)
                .logger(KwikLog.quiet())                 // 필수(기본값 없음)
                .build();
        conn.connect();

        // ── 지문 대조: 여기까지 데이터는 한 바이트도 안 보냈다.
        List<X509Certificate> chain = conn.getServerCertificateChain();
        String actual = chain.isEmpty() ? "" : QuicCert.fingerprint(chain.get(0));
        if (!expectedFp.equals(actual)) {
            conn.close();
            throw new IOException("인증서 지문 불일치 — 중간자 의심");
        }
        startPing(conn);
        return conn;
    }

    /**
     * 랑데부 서버를 통해 방장의 지문을 받고 후보를 주고받는다(서버 rendezvous.go).
     * 서버가 이 연결을 방장에게만 이어 주므로, 여기로 오는 지문·후보는 방장이 보낸 것뿐이다 —
     * 예전 페어 세션처럼 제3자가 가짜 지문을 먼저 넣을 수 없다.
     * 연결은 {@code punch()} 가 끝날 때까지 열어 둔다 — 트리클로 늦게 오는 후보도 받아야 한다.
     *
     * @return 방장 인증서 지문
     */
    private String exchange(QuicIce agent, List<QuicIce.Candidate> mine) throws Exception {
        CompletableFuture<String> hostFp = new CompletableFuture<>();

        java.util.concurrent.atomic.AtomicInteger hostCandidates = new java.util.concurrent.atomic.AtomicInteger();
        // relay=1 이면 방장이 곧장 중계로 간다(QuicHost.onJoin 주석).
        WebSocketClient pair = new WebSocketClient(
                signalingUrl + "/rv/" + roomId + "/join?relay=" + (relayOnly ? 1 : 0)
                        + (authToken != null ? "&token=" + java.net.URLEncoder.encode(authToken, java.nio.charset.StandardCharsets.UTF_8) : "")) {
            // 서버가 방장에게 join 을 먼저 알린 뒤 우리 메시지를 넘기므로 바로 보내도 된다.
            @Override public void onConnected() {
                for (QuicIce.Candidate c : mine) send(VillasMsg.candidate(c.line(), "0"));
            }

            @Override public void onMessage(String type, String json) {
                String error = VillasMsg.field(json, "error");
                if (error != null) {
                    // no-host: 방이 없다(닫혔거나 코드가 틀림) — 예전엔 15초를 다 기다린 뒤에야 알았다.
                    hostFp.completeExceptionally(new IOException("no-host".equals(error)
                            ? "방장이 시그널링에 나타나지 않았습니다 (방이 닫혔거나 코드가 틀렸을 수 있습니다)"
                            : "방장이 방을 닫았습니다"));
                    return;
                }
                if (VillasMsg.has(json, "description")) {
                    String desc = VillasMsg.object(json, "description");
                    String t = desc != null ? VillasMsg.field(desc, "type") : null;
                    if (QuicHost.MSG_ANSWER.equals(t)) {
                        hostFp.complete(VillasMsg.field(desc, "spd"));
                    } else if (QuicHost.MSG_NO_RELAY.equals(t)) {
                        hostFp.completeExceptionally(new IOException(
                                "방장이 중계 통신 강제인데 중계 서버를 쓸 수 없는 상태입니다 (방장이 정품 인증을 받았는지 확인하세요)"));
                    }
                    return;
                }
                if (VillasMsg.has(json, "candidate")) {
                    String cand = VillasMsg.object(json, "candidate");
                    if (cand == null) return;
                    QuicIce.Candidate c = QuicIce.Candidate.parse(VillasMsg.field(cand, "spd"));
                    // 방장 한 명분 상한 — 방장이 이상하게 굴어도 여기서 멈춘다(Candidate.parse 주석).
                    if (c != null && hostCandidates.incrementAndGet() <= QuicIce.Candidate.MAX_PER_PEER) {
                        agent.addRemote(c);
                    }
                }
            }
        };
        try {
            pairSession = pair;
            pair.connect();
            String fp;
            try {
                fp = hostFp.get(PEER_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                throw (IOException) e.getCause();
            } catch (java.util.concurrent.TimeoutException e) {
                // 방장은 있는데 답이 없다 — 방장 쪽 협상 자리가 꽉 찼거나(동시 4명) 방장이 멈췄다.
                // TimeoutException 은 메시지가 null 이라 그대로 올리면 "연결 실패: null" 만 남는다.
                throw new IOException("방장이 응답하지 않습니다 (잠시 뒤 다시 시도해 보세요)");
            }
            if (fp == null || fp.isEmpty()) throw new IOException("방장 지문을 받지 못했습니다");
            // "<지문> <접속 표>" — 표는 방장이 이 접속자에게만 준 일회용 값이다.
            String[] parts = fp.split(" ");
            try {
                if (parts.length != 2 || parts[1].length() != QuicHost.TICKET_BYTES * 2) throw new IllegalArgumentException();
                ticket = java.util.HexFormat.of().parseHex(parts[1]);
            } catch (IllegalArgumentException e) {
                throw new IOException("방장 접속 표를 받지 못했습니다 (방장이 옛 버전일 수 있습니다)");
            }
            return parts[0];
        } finally {
            // 랑데부 연결은 punch 가 끝난 뒤에 닫는다 — 늦게 오는 후보를 놓치지 않게.
            Thread t = new Thread(() -> {
                // 중계 폴백까지 도는 동안 열려 있어야 한다 — 닫히면 폴백 후보를 못 보낸다.
                try { Thread.sleep(PUNCH_MS * 2 + 2_000); } catch (InterruptedException ignored) {}
                pairSession = null;
                pair.close();
            }, "quic-client-pair-close");
            t.setDaemon(true);
            t.start();
        }
    }
}
