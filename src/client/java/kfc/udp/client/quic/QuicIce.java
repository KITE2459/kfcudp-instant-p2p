package kfc.udp.client.quic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 최소한의 ICE — 후보 수집, 홀펀칭, NAT 바인딩 유지.
 * <p>
 * <b>소켓 하나를 kwik과 공유한다.</b> 홀은 5-tuple 단위로 뚫리므로 QUIC이 반드시 같은 소켓·같은
 * 포트로 나가야 한다. {@link IceSocket}이 {@code receive()}를 가로채 STUN은 여기로 돌리고 나머지만
 * 호출자에게 넘긴다 — 그래서 ICE keepalive와 QUIC이 연결 내내 한 소켓에 공존한다.
 * <p>
 * <b>수신 루프의 주인이 바뀐다</b> — 뚫는 동안은 우리가 {@code receive()}를 돌리고, 경로가 확정되면
 * 손을 떼고 kwik이 돌린다(방장은 ServerConnector, 접속자는 connect 이후). 둘이 동시에 돌면 패킷을
 * 서로 훔쳐가므로 {@link #stopOwnLoop()}로 확실히 넘긴다.
 * <p>
 * <b>진짜 ICE와 다른 점</b> — 후보 우선순위 계산·ICE 재시작·MESSAGE-INTEGRITY가 없고, 응답이 온
 * 첫 경로를 그냥 채택한다(aggressive nomination). 마크 터널엔 이 정도로 충분하고, 위조된 체크가
 * 통해도 QUIC 핸드셰이크에서 걸러진다({@link Stun} 주석).
 */
final class QuicIce {

    private static final Logger LOG = LoggerFactory.getLogger("quic-ice");

    /** 후보 수집에 쓸 STUN 왕복 한도. 시그널링과 같은 호스트라 보통 수십 ms다. */
    private static final int GATHER_TIMEOUT_MS = 1_500;
    /** 체크를 다시 쏘는 간격 — 상대가 아직 후보를 안 보냈을 수도 있어 반복해야 한다. */
    private static final long CHECK_INTERVAL_MS = 200;
    /** 응답을 확인하는 간격. <b>재전송 간격과 분리해야 한다</b> — 예전엔 200ms 를 자고 나서야
     * 응답을 봐서, 같은 기기끼리도 홀펀칭에 수백 ms 가 걸렸다(실측 703ms). */
    private static final long POLL_INTERVAL_MS = 5;
    /** NAT 바인딩 유지 간격. 흔한 UDP 타임아웃(30초)보다 넉넉히 짧게. */
    private static final long KEEPALIVE_MS = 10_000;
    /**
     * 접속자의 경로 감시 — 이 간격으로 지금 경로에 체크를 보내고, {@link #WEAK_MS} 동안 상대에게서 아무것도
     * 못 받으면 다른 후보로 옮긴다({@link #startPathMonitor}). libwebrtc 는 안정된 경로에 2.5초마다 체크하고
     * 2.5초 무수신이면 약해진 것으로 본다(WEAK_CONNECTION_RECEIVE_TIMEOUT). 카트 레이스처럼 몇 초 멈춤도
     * 큰 게임이라 감시는 더 촘촘히 하고, 판정은 비슷하게 둔다.
     */
    private static final long MONITOR_MS = 1_000;
    private static final long WEAK_MS = 3_000;
    /** <b>테스트용</b> — 상대 후보를 이 주소들로 바꿔 넣는다(가짜 NAT 를 끼워 경로 변경을 시험). {@code -Dkfcudp.test.remotes=ip:port,ip:port} */
    private static final String TEST_REMOTES = System.getProperty("kfcudp.test.remotes");

    /** 후보 하나 — 시그널링에 "ip port typ" 한 줄로 실려 간다. */
    record Candidate(String ip, int port, String type) {
        String line() { return ip + " " + port + " " + type; }

        /**
         * 시그널링으로 받은 한 줄을 후보로 만든다. <b>여기가 신뢰 경계다</b> — 페어 세션에는 방 코드를
         * 아는 누구나 들어올 수 있고 보낸 사람이 표시되지 않는다. 받은 주소로는 체크(1200바이트)를
         * 200ms 마다 보내므로, 검증 없이 받으면 남의 IP 를 후보로 넣어 방장을 반사 공격에 쓸 수 있다.
         * <p>
         * 그래서 <b>숫자 IPv4 만</b> 받는다(도메인 이름이면 DNS 조회가 일어난다), 루프백·멀티캐스트·
         * 브로드캐스트·0.0.0.0/8 은 거부, 포트는 1~65535, 종류는 host/srflx/relay 셋뿐.
         * 개수 제한은 부르는 쪽이 상대마다 건다({@link #MAX_PER_PEER}).
         */
        static Candidate parse(String line) {
            if (line == null) return null;
            String[] p = line.trim().split("\\s+");
            if (p.length < 3) return null;
            if (!"host".equals(p[2]) && !"srflx".equals(p[2]) && !"relay".equals(p[2])) return null;
            int[] ip = ipv4(p[0]);
            if (ip == null) return null;
            if (ip[0] == 0 || ip[0] == 127 || ip[0] >= 224) return null;
            int port;
            try {
                port = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (port < 1 || port > 65535) return null;
            return new Candidate(p[0], port, p[2]);
        }

        /** 상대 한 명에게서 받는 후보 상한 — 정상은 host 2~3 + srflx 1 + relay 1 정도다. */
        static final int MAX_PER_PEER = 8;

        private static int[] ipv4(String s) {
            String[] o = s.split("\\.", -1);
            if (o.length != 4) return null;
            int[] v = new int[4];
            for (int i = 0; i < 4; i++) {
                if (o[i].isEmpty() || o[i].length() > 3) return null;
                for (char ch : o[i].toCharArray()) if (ch < '0' || ch > '9') return null;
                v[i] = Integer.parseInt(o[i]);
                if (v[i] > 255) return null;
            }
            return v;
        }

        InetSocketAddress address() { return new InetSocketAddress(ip, port); }
    }

    private final IceSocket socket;
    private final AtomicBoolean ownsLoop = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 우리가 보낸 체크의 트랜잭션 ID → 보낸 대상. 응답을 짝지어 어느 경로가 열렸는지 안다. */
    private final Map<String, InetSocketAddress> pending = new ConcurrentHashMap<>();
    /** 상대가 알려준 후보 — 여기 없는 주소에서 온 응답은 무시한다. */
    private final List<Candidate> remote = new CopyOnWriteArrayList<>();
    /** 응답이 와서 열린 것이 확인된 경로. 첫 성공을 그대로 쓴다. */
    private volatile InetSocketAddress validated;
    /** 확정된 경로의 상대 후보 종류(host/srflx). 주소를 로그에 남기지 않고도 같은 LAN인지
     * NAT를 뚫은 것인지 구분하려면 이게 있어야 한다 — srflx 면 공인 주소로 통한 것이다. */
    private volatile String validatedType = "?";
    /**
     * 체크에 응답한 상대 주소들. <b>방장은 소켓·ICE 를 접속자 전원과 공유</b>하므로 전역
     * {@code validated} 하나로 판정하면 두 번째 접속자부터 punch 가 즉시 끝나 체크를 아예 안
     * 보낸다(그러면 그 접속자 쪽 경로가 안 열린다). 그래서 응답 사실만 여기 모으고, 판정은
     * {@link #punch} 호출마다 자기 후보 목록으로 따로 한다.
     */
    private final Set<String> responsive = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 체크 → 응답 왕복 시간. QUIC 핸드셰이크의 initialRtt 로 넘겨 쓸모가 있다. */
    private volatile long rttMs = 0;
    private final Map<String, Long> sentAt = new ConcurrentHashMap<>();
    /** 경로 확인·감시용 체크 — 트랜잭션 → (보낸 곳, 응답이 그 주소에서 오면 할 일). 홀펀칭 체크와 따로 둔다. */
    private record Probe(InetSocketAddress to, Runnable onOk, long at) {}
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    /** 새 주소 확인을 너무 자주 보내지 않게 — 주소 → 마지막으로 확인을 보낸 시각. */
    private final Map<String, Long> moveTried = new ConcurrentHashMap<>();

    /**
     * 소켓 송·수신 버퍼 목표치({@link IceSocket#enlargeBuffers} 주석). 4MB 면 1252 바이트
     * 데이터그램 약 3300 개 — 청크 로딩 한 번을 버티기에 충분하고, 커널 메모리를 과하게
     * 잡지도 않는다.
     */
    private static final int SOCKET_BUFFER = 4 * 1024 * 1024;

    /** 수신 루프를 깨우려고 자기에게 보내는 패킷(stopOwnLoop). 4바이트라 TCP 중계 메시지와 헷갈릴 일이 없다. */
    private static final byte[] WAKE = {'W', 'A', 'K', 'E'};

    private volatile Thread loopThread;
    private volatile Thread keepaliveThread;

    private final String stunUrl;
    /** 방장만 채운다 — 접속자는 방장의 relayed 주소로 그냥 보내면 되므로 allocation 이 필요 없다
     * ({@link TurnAllocation} 클래스 주석). */
    private volatile TurnAllocation turn;

    /**
     * <b>테스트용</b> — host 후보를 양방향으로 버려서 공인 주소(srflx)로만 붙게 만든다. 혼자서
     * NAT 통과 경로를 시험할 때 쓴다: 같은 기기·같은 LAN이라도 자기 공인 주소로 돌아 들어와야
     * 하므로 라우터의 NAT 헤어핀이 되면 srflx 경로가 그대로 검증된다(안 되는 라우터도 많다).
     * 되돌리려면 이 속성을 빼면 된다. {@code -Dkfcudp.quic.srflxonly=true}
     */
    private static final boolean SRFLX_ONLY = Boolean.getBoolean("kfcudp.quic.srflxonly");

    /**
     * 중계 강제 — <b>내 로컬 후보를 relay 하나로만</b> 제한한다. 상대 후보는 실주소를 그대로 쓴다
     * (WebRTC 의 {@code iceTransportPolicy: 'relay'} 와 같은 의미다).
     * <p>
     * 이러면 내 트래픽이 전부 내 allocation 을 거쳐 나가므로 상대는 coturn 주소만 보게 된다 —
     * 「중계 통신 강제」 체크박스의 "내 IP를 숨깁니다" 가 이걸 말한다.
     * <p>
     * <b>상대의 relay 후보까지 쓰려고 하면 안 된다</b> — coturn 은 자기 relayed 주소를 peer 로
     * 등록하는 요청을 400 으로 거부한다(중계 루프 방지). 즉 relay→relay 경로는 존재하지 않는다.
     * <p>
     * 값은 {@link QuicBridge} 가 {@code P2PConfig.isRelayOnly()}(= GUI 체크박스, 설정 파일에 저장)에서
     * 넣어 준다. 여기서 직접 읽지 않는 건 이 클래스가 게임 밖에서도 돌아야 하기 때문이다.
     */
    private final boolean relayOnly;

    /** @param stunUrl {@code stun:HOST:PORT}. P2PConfig 를 직접 읽지 않는 건 이 클래스가
     *                 게임 밖(독립 하니스)에서도 돌아야 하기 때문이다 — P2PConfig 는 정적
     *                 초기화에서 FabricLoader 를 건드려 게임 밖에서 로드가 실패한다. */
    QuicIce(String stunUrl, boolean relayOnly) throws SocketException {
        this.stunUrl = stunUrl;
        this.relayOnly = relayOnly;
        this.socket = new IceSocket(this);
    }

    /**
     * TURN allocation 을 잡아 relay 후보를 만든다. 방장은 항상 부르고(대칭 NAT 접속자를 받으려면
     * 필요), 접속자는 중계 강제일 때만 부른다 — 평소엔 방장의 relay 후보로 보내면 되고,
     * 중계 강제일 때는 자기 IP 를 감추려고 자기 allocation 이 있어야 한다.
     * {@code turnUrl} 이 null 이면 중계 없이 동작한다(홀펀칭만).
     */
    void enableTurn(String turnUrl, String user, String pass) {
        InetSocketAddress server = turnUrl != null ? TurnAllocation.parseUrl(turnUrl) : null;
        if (server == null || user == null || pass == null) return; // 계정을 못 받았으면 중계 없이
        TurnAllocation alloc = new TurnAllocation(socket, server, user, pass);
        if (alloc.allocate() != null) {
            turn = alloc;
            socket.turn = alloc;
        }
    }

    DatagramSocket socket() { return socket; }
    boolean isRelayOnly() { return relayOnly; }
    /** 홀펀칭 체크의 왕복 시간(ms) — QUIC 의 initialRtt 로 넘긴다. 못 재면 0. */
    long lastRttMs() { return rttMs; }
    int localPort() { return socket.getLocalPort(); }
    InetSocketAddress validated() { return validated; }
    String validatedType() { return validatedType; }
    /** host 후보가 아니라 공인 주소로 통했는지 — 진짜 NAT 통과 여부. */
    boolean traversedNat() { return !"host".equals(validatedType); }
    /**
     * 중계(TURN)를 거치는 경로인지. 상대 후보 타입이 아니라 <b>우리가 allocation 을 거쳐 보내는지</b>로
     * 판단한다 — 중계 강제일 때 상대 후보는 실주소(srflx)이지만 경로는 중계이기 때문이다.
     */

    /**
     * 그 후보로 붙은 경로가 중계 경유인지 — <b>반드시 상대별로</b> 판정한다.
     * <p>
     * 전역 플래그를 쓰면 안 된다: 소켓은 접속자 전원이 공유하고 채널 목록은 한 번 채워지면
     * 비워지지 않으므로, 중계 접속자를 한 번 받은 뒤에는 그 다음 <b>직결</b> 접속자도 중계로
     * 보고된다(방을 다시 열면 소켓이 새로 생겨 초기화되니 "재생성하면 정상"으로 보인다).
     */
    boolean usesRelay(Candidate c) {
        if (c == null) return false;
        // 상대가 relay 후보면 그 경로 자체가 중계고, 우리가 그 상대에게 채널을 붙였으면
        // 우리 트래픽이 allocation 을 거친다. 둘 중 하나라도 해당하면 중계다.
        return "relay".equals(c.type()) || socket.hasRelayChannel(c.address());
    }

    // ── 후보 수집 ────────────────────────────────────────────────────────────

    /**
     * 우리 후보 목록 — 사설 IPv4(host)와 STUN으로 알아낸 공인 주소(srflx).
     * STUN이 실패해도 host 후보만으로 같은 LAN은 되므로 예외를 올리지 않는다.
     */
    List<Candidate> gather() {
        Set<Candidate> out = new LinkedHashSet<>();
        int port = socket.getLocalPort();
        if (!SRFLX_ONLY) {
            for (String ip : localAddresses()) out.add(new Candidate(ip, port, "host"));
        }

        InetSocketAddress srflx = discoverReflexive();
        if (srflx != null) {
            out.add(new Candidate(srflx.getAddress().getHostAddress(), srflx.getPort(), "srflx"));
        } else {
            LOG.info("[ice] srflx 수집 실패 — host 후보로만 진행(같은 LAN 한정)");
        }

        // relay 후보 — 방장만 갖는다. 접속자는 이 주소로 보내기만 하면 된다.
        TurnAllocation alloc = turn;
        if (alloc != null && alloc.relayedAddress() != null) {
            InetSocketAddress r = alloc.relayedAddress();
            out.add(new Candidate(r.getAddress().getHostAddress(), r.getPort(), "relay"));
        }
        if (SRFLX_ONLY) LOG.info("[ice] srflxonly — host 후보를 버린다(공인 주소 경로만 시험)");
        List<Candidate> list = new ArrayList<>(out);
        // 후보 IP는 방장의 공인 IP일 수 있으니 로그에 주소를 남기지 않는다 — 개수만.
        LOG.debug("[ice] 후보 {}개 수집(host {}, srflx {})", list.size(),
                list.stream().filter(c -> c.type().equals("host")).count(),
                list.stream().filter(c -> c.type().equals("srflx")).count());
        return list;
    }

    /** coturn에 Binding Request를 한 번 보내 우리 공인 주소를 알아낸다. */
    private InetSocketAddress discoverReflexive() {
        try {
            InetSocketAddress stunServer = parseStunUrl(stunUrl);
            if (stunServer == null) return null;
            byte[] txId = Stun.newTransactionId();
            DatagramPacket req = Stun.packet(Stun.bindingRequest(txId), stunServer);

            // 응답이 없으면 같은 요청을 다시 보낸다(RFC 5389 §7.2.1) — 한 번만 보내면 패킷 하나 유실로
            // srflx 후보가 빠져 같은 LAN 밖에서는 직결이 안 된다.
            long deadline = System.currentTimeMillis() + GATHER_TIMEOUT_MS;
            long rto = 200, nextSend = 0;
            byte[] buf = new byte[1500];
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextSend) {
                    socket.send(req);
                    nextSend = now + rto;
                    rto *= 2;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.setSoTimeout((int) Math.max(1, Math.min(nextSend, deadline) - System.currentTimeMillis()));
                try {
                    socket.receiveRaw(p); // 수집 단계는 우리가 직접 읽는다(가로채기 우회)
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (!Stun.looksLikeStun(p.getData(), p.getOffset(), p.getLength())) continue;
                if (!Stun.isSuccess(p.getData(), p.getOffset())) continue;
                if (!java.util.Arrays.equals(txId, Stun.transactionId(p.getData(), p.getOffset()))) continue;
                return Stun.mappedAddress(p.getData(), p.getOffset(), p.getLength());
            }
        } catch (Exception e) {
            LOG.debug("[ice] srflx 수집 중 예외: {}", e.getMessage());
        } finally {
            try { socket.setSoTimeout(0); } catch (SocketException ignored) {}
        }
        return null;
    }

    /** {@code stun:HOST:PORT} 파싱. */
    private static InetSocketAddress parseStunUrl(String url) {
        String s = url.startsWith("stun:") ? url.substring(5) : url;
        int colon = s.lastIndexOf(':');
        if (colon <= 0) return null;
        try {
            return new InetSocketAddress(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) out.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) out.add("127.0.0.1"); // 같은 기기에서 시험하는 경우
        return out;
    }

    // ── 홀펀칭 ───────────────────────────────────────────────────────────────

    /**
     * 상대에게 알릴 후보만 골라낸다. <b>수집은 항상 전부 해 두고 알릴 때 거른다</b> — 방장이 방을
     * 연 뒤 「중계 통신 강제」를 켜거나 끄면 그때그때 반영돼야 하기 때문이다(수집 단계에서 버리면
     * 다시 켤 때 host/srflx 가 없어서 직결로 돌아갈 수 없다).
     */
    static List<Candidate> advertised(List<Candidate> all, boolean relayOnly, boolean peerUsesRelay) {
        if (relayOnly) {
            // 내 IP 를 숨기는 게 목적이니 relay 후보만 내놓는다.
            List<Candidate> out = new ArrayList<>();
            for (Candidate c : all) if ("relay".equals(c.type())) out.add(c);
            return out;
        }
        if (peerUsesRelay) {
            // 상대가 자기 allocation 밖으로만 보내면 내 host 후보(사설 IP)로는 절대 닿을 수 없다 —
            // coturn 이 site-local peer 를 거부하기 때문이다. 알려주면 상대가 헛된 왕복만 쓴다.
            // srflx·relay 는 남긴다: srflx 로 붙으면 중계 한 홉으로 끝나고, relay 는 최후 수단이다.
            List<Candidate> out = new ArrayList<>();
            for (Candidate c : all) if (!"host".equals(c.type())) out.add(c);
            return out;
        }
        return all;
    }

    /**
     * 후보 목록의 relay 를 <b>지금</b> allocation 주소로 바꿔 돌려준다 — 중계 서버가 재시작돼 allocation 을
     * 새로 잡으면(TurnAllocation.reallocate) 방을 열 때 모은 relay 주소는 죽은 주소다.
     */
    List<Candidate> withCurrentRelay(List<Candidate> base) {
        TurnAllocation alloc = turn;
        InetSocketAddress r = alloc != null ? alloc.relayedAddress() : null;
        if (r == null) return base;
        Candidate now = new Candidate(r.getAddress().getHostAddress(), r.getPort(), "relay");
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : base) out.add("relay".equals(c.type()) ? now : c);
        return out;
    }

    /** 상대 후보를 추가한다(트리클이라 여러 번 불린다). */
    void addRemote(Candidate c) {
        if (c == null) return;
        if (TEST_REMOTES != null && !"relay".equals(c.type())) { // 테스트: 받은 후보 대신 가짜 NAT 주소들을 넣는다(relay 는 그대로 — 중계로 갈아타기 시험)
            for (String s : TEST_REMOTES.split(",")) {
                String[] hp = s.trim().split(":");
                Candidate t = new Candidate(hp[0], Integer.parseInt(hp[1]), "host");
                if (!remote.contains(t)) remote.add(t);
            }
            return;
        }
        if (SRFLX_ONLY && "host".equals(c.type())) return;  // 테스트 플래그 — 공인 주소로만 붙는다
        if (!remote.contains(c)) remote.add(c);

        // allocation 이 있으면 상대 IP 에 권한을 걸어 둔다 — 없으면 coturn 이 상대의 첫 패킷을
        // 버린다. 권한은 IP 단위라 상대가 대칭 NAT 로 포트를 바꿔도 통과한다(TurnAllocation 주석).
        TurnAllocation alloc = turn;
        if (alloc == null) return;
        // 권한은 후보마다 걸어 둔다(IP 단위라 싸고 멱등이다). 채널 바인딩은 하나만 할 수 있으므로
        // punch 에서 상대를 하나 골라 한 번만 붙인다 — 여기서 매번 붙이면 같은 채널 번호를 다른
        // peer 에 재지정하려다 coturn 이 거부한다.
        // host 후보는 사설 IP라 coturn 이 site-local peer 를 항상 거부한다
        // ({@code ioa_addr_is_internal_deny_default}). 권한을 걸어 봐야 왕복만 버리고 실패한다.
        if ("host".equals(c.type())) return;
        final Candidate cand = c;
        Thread perm = new Thread(() -> alloc.createPermission(cand.address()), "quic-turn-perm");
        perm.setDaemon(true); // 데몬이 아니면 게임이 끝나도 JVM 이 이 스레드를 기다린다
        perm.start();
    }

    /**
     * 열린 경로가 나올 때까지 모든 상대 후보로 체크를 반복해 쏜다.
     * 우리가 쏘는 것과 상대가 쏘는 것이 <b>양방향으로</b> 필요하다 — 한쪽만 쏘면 그쪽 NAT에만
     * 구멍이 나고 상대 NAT는 우리 패킷을 여전히 버린다.
     *
     * @param viaRelay 우리 트래픽을 allocation 을 거쳐 보낼지(= 「중계 통신 강제」). 방장은
     *                 접속자마다 <b>지금</b> 설정을 읽어 넘긴다.
     * @param ownLoop 수신 루프를 우리가 돌릴지. 방장은 {@code false} — ServerConnector가 이미
     *                돌리고 있어서 켜면 패킷을 서로 훔쳐간다. 접속자는 connect 전이라 {@code true}.
     * @return 확정된 <b>후보</b>, 시간 내 못 뚫으면 null. 주소만이 아니라 후보를 돌려주는 건 중계
     *         경유 여부가 후보 타입에만 있어서다 — 방장은 접속자마다 이걸로 직결/중계를 판정한다.
     */
    Candidate punch(long timeoutMs, boolean ownLoop) {
        return punch(remote, timeoutMs, ownLoop, relayOnly);
    }

    /**
     * 직결 후보(host/srflx)로만 뚫어 본다 — <b>1차 시도용</b>.
     * <p>
     * relay 후보를 섞으면 중계가 홀펀칭보다 먼저 성사돼서, 직결이 가능한데도 중계로 확정돼
     * 버린다. 예전 WebRTC 구현이 1차에서 TURN 후보를 <b>아예 만들지 않았던</b> 이유가 이것이다.
     * 우리는 후보를 이미 받아 뒀으니, 대신 1차 대상에서 빼서 같은 효과를 낸다.
     */
    Candidate punchDirectOnly(long timeoutMs, boolean ownLoop) {
        List<Candidate> direct = new ArrayList<>();
        for (Candidate c : remote) {
            if (!"relay".equals(c.type())) direct.add(c);
        }
        if (direct.isEmpty()) return null;
        return punch(direct, timeoutMs, ownLoop, false);
    }

    /** 상대가 relay 후보만 내놨는지 — 그러면 저쪽이 중계 강제라 1차(직결)는 해 볼 의미가 없다. */
    boolean peerIsRelayOnly() {
        if (remote.isEmpty()) return false;
        for (Candidate c : remote) {
            if (!"relay".equals(c.type())) return false;
        }
        return true;
    }

    /**
     * 이 상대에게 우리 allocation 을 거쳐 보내고 있는지. 연결 방식 판정은 <b>양쪽 중 하나라도 중계면
     * 중계</b>이므로(예전 {@code WebRtcStats} 규칙), 우리 쪽 방향도 이걸로 확인해야 한다.
     * <p>
     * IP 만으로 찾으면 안 된다 — 같은 사람이 재접속하면 IP 는 같고 포트만 바뀌는데, 전 세션의 채널이
     * 남아 있어 직결로 붙은 새 세션까지 중계로 표시됐다.
     */
    boolean sendsViaRelayTo(InetSocketAddress peer) {
        return socket.hasRelayChannel(peer);
    }

    /**
     * 주어진 후보들로만 뚫는다 — 방장은 접속자마다 자기 후보 목록을 넘겨야 한다(클래스의
     * {@code responsive} 주석 참고).
     */
    Candidate punch(List<Candidate> cands, long timeoutMs, boolean ownLoop, boolean viaRelay) {
        // 목록이 비어 있어도 <b>기다린다</b>. 방장은 접속을 감지하자마자 빈 목록으로 들어오고
        // 접속자 후보는 트리클로 나중에 채워진다(CopyOnWriteArrayList) — 여기서 빈 목록을 실패로
        // 치면 방장이 접속자 쪽으로 체크를 한 번도 안 보내서, 방장 NAT 가 접속자의 relay 주소를
        // 막고 중계 채널도 안 붙는다. 실제로 "한쪽이라도 중계 강제면 아예 안 붙는" 회귀가 났다.
        if (ownLoop) startOwnLoop();
        long deadline = System.currentTimeMillis() + timeoutMs;
        Candidate hit = null;
        Candidate bound = viaRelay ? bindRelayFor(cands) : null;
        long nextSend = 0;
        while (hit == null && System.currentTimeMillis() < deadline && !closed.get()) {
            long now = System.currentTimeMillis();
            if (now >= nextSend) {
                if (viaRelay && bound == null) bound = bindRelayFor(cands);
                // 중계로 보내야 하면 채널이 붙은 상대만 때린다. 다른 후보로도 쏘면 그 패킷이
                // allocation 을 안 거치고 생으로 나가 우리 실주소가 드러난다.
                if (bound != null) {
                    sendCheck(bound.address());
                } else if (!viaRelay) {
                    for (Candidate c : cands) sendCheck(c.address());
                }
                // viaRelay 인데 채널이 아직 없으면 <b>아무것도 보내지 않는다</b>(다음 주기에 다시 붙인다).
                // 예전엔 여기서 생으로 쏴서, 채널 바인딩이 실패하면 중계 강제인 방장이 직결(host)로
                // 확정되고 실주소가 드러났다.
                nextSend = now + CHECK_INTERVAL_MS;
            }
            // 응답은 자주 본다 — 한 왕복이면 끝나는 일을 재전송 주기만큼 기다릴 이유가 없다.
            hit = firstResponsive(bound != null ? List.of(bound) : viaRelay ? List.of() : cands);
            if (hit != null) break;
            try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
        }
        if (hit != null) {
            long took = timeoutMs - (deadline - System.currentTimeMillis());
            boolean relayed = usesRelay(hit);
            LOG.debug("[ice] 경로 확정: 경로={} (상대 후보 {}) — {}ms, 상대 후보 {}개 중 — keepalive 시작",
                    relayed ? "relay" : ("host".equals(hit.type()) ? "host(같은 LAN)" : "srflx(NAT 통과)"),
                    hit.type(), took, cands.size());
            if (validated == null) { validated = hit.address(); validatedType = hit.type(); }
            startKeepalive();
            return hit;
        }
        LOG.warn("[ice] 경로를 못 뚫었다 — 상대 후보 {}개 전부 무응답", cands.size());
        return null;
    }

    /**
     * STUN 체크에 응답한 후보를 선호 순서로 전부 돌려준다.
     * <p>
     * <b>첫 응답 후보에 커밋하면 안 된다</b> — 체크는 통했는데 QUIC 이 그 경로로 못 붙는 경우가
     * 실제로 있다(실측: srflx 가 213ms 에 응답했지만 핸드셰이크가 10초 타임아웃까지 끌었고,
     * relay 로 바꾸니 즉시 붙었다). 그래서 호출부가 차례로 시도할 수 있게 목록으로 준다.
     */
    List<Candidate> responsiveCandidates() {
        List<Candidate> out = new ArrayList<>();
        for (String type : new String[]{"srflx", "relay", "host"}) {
            for (Candidate c : remote) {
                if (type.equals(c.type()) && responsive.contains(addrKey(c.address())) && !out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** 우리가 스스로 닫는 중인지 — kwik 의 "Socket closed" 경고를 노이즈로 걸러내는 데 쓴다. */
    boolean isClosing() { return closed.get(); }

    private Candidate firstResponsive(List<Candidate> cands) {
        for (Candidate c : cands) {
            if (responsive.contains(addrKey(c.address()))) return c;
        }
        return null;
    }

    private static String addrKey(InetSocketAddress a) {
        return a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    /**
     * 이 상대에게 우리 allocation 을 거쳐 보내도록 채널을 붙인다 — 「중계 통신 강제」일 때만.
     * <p>
     * 평소엔 붙이지 않는다: allocation 은 <b>상대가 우리에게 닿는 통로</b>로만 두고 우리는 생으로
     * 보낸다. 늘 붙이면 직결로 붙을 수 있는 상대에게도 우리 트래픽이 중계를 타서 의미가 없어진다.
     * 상대가 우리 relay 주소를 골랐다면 그쪽 패킷이 Data indication 으로 도착하면서 알아서 잡힌다.
     * <p>
     * <b>상대의 relay 후보에도 붙인다</b> — 양쪽이 다 중계 강제면 상대 후보가 relay 하나뿐이라
     * 그걸 빼면 보낼 곳이 없어진다(WebRTC 의 ICE 도 relay↔relay 를 그대로 했다). 예전에 이걸
     * 건너뛰었던 건 coturn 의 {@code multiplex-peer} 가 그 조합을 400 으로 거부했기 때문인데,
     * 그 옵션을 빼면 정상 동작한다.
     * <p>
     * <b>host 후보는 건너뛴다</b> — 사설 IP 라 coturn 이 site-local peer 를 항상 거부한다
     * ({@code ioa_addr_is_internal_deny_default}). 중계로는 애초에 닿을 수 없는 주소다.
     *
     * @return 붙인 상대(= 이후 이 상대만 때려야 한다), 못 붙였으면 null
     */
    Candidate bindRelayFor(List<Candidate> cands) {
        TurnAllocation alloc = turn;
        if (alloc == null || cands.isEmpty()) return null;
        // 한 홉으로 끝나는 srflx 를 먼저, 그다음 relay(두 홉). 실패하면 다음 후보로 넘어간다.
        for (String type : new String[]{"srflx", "relay"}) {
            for (Candidate c : cands) {
                if (!type.equals(c.type())) continue;
                int channel = alloc.bindChannel(c.address());
                if (channel < 0) continue;
                socket.addRelayPeer(c.address(), channel);
                LOG.debug("[turn] 중계 경로 준비 — 상대 후보 typ={}", c.type());
                return c;
            }
        }
        return null;
    }

    private void sendCheck(InetSocketAddress to) {
        try {
            byte[] txId = Stun.newTransactionId();
            pending.put(key(txId), to);
            sentAt.put(key(txId), System.currentTimeMillis());
            // QUIC Initial 과 같은 크기로 보낸다 — 작은 패킷만 통과하는 경로를 열렸다고
            // 판정하지 않으려면 이래야 한다(Stun.paddedBindingRequest 주석).
            socket.send(Stun.packet(Stun.paddedBindingRequest(txId), to));
        } catch (IOException e) {
            LOG.debug("[ice] 체크 전송 실패: {}", e.getMessage());
        }
    }

    /** {@link IceSocket}이 가로챈 STUN을 넘겨준다 — 우리 루프에서든 kwik 루프에서든 같은 경로다. */
    void onStun(DatagramPacket p) {
        byte[] buf = p.getData();
        int off = p.getOffset();
        InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();

        if (Stun.isRequest(buf, off)) {
            // 상대의 연결성 확인 — 응답해 줘야 상대도 자기 경로를 확정할 수 있다.
            byte[] resp = Stun.bindingSuccess(Stun.transactionId(buf, off), from);
            if (resp != null) {
                try { socket.send(Stun.packet(resp, from)); } catch (IOException ignored) {}
            }
            return;
        }
        if (!Stun.isSuccess(buf, off)) return;

        String tx = key(Stun.transactionId(buf, off));
        Probe pr = probes.remove(tx);
        if (pr != null) {
            // 보낸 그 주소에서 온 응답만 인정한다 — 다른 곳에서 온 응답으로 경로를 옮기면 가로채기가 된다.
            if (addrKey(pr.to()).equals(addrKey(from))) pr.onOk().run();
            return;
        }
        Long sent = sentAt.remove(tx);
        if (sent != null) rttMs = Math.max(1, System.currentTimeMillis() - sent);
        InetSocketAddress target = pending.remove(tx);
        if (target == null) return;                 // 우리가 보낸 체크가 아니다
        String type = candidateType(from);
        if (type == null) return;                   // 시그널링으로 받은 후보가 아닌 곳에서 온 응답은 무시
        responsive.add(addrKey(target));
        if (validated == null) {
            validatedType = type;
            validated = target;                     // 첫 성공 채택
        }
    }

    /** 상대 후보 목록에 있으면 그 종류, 없으면 null. */
    private String candidateType(InetSocketAddress from) {
        for (Candidate c : remote) {
            if (c.port() == from.getPort() && c.ip().equals(from.getAddress().getHostAddress())) return c.type();
        }
        return null;
    }

    // ── 수신 루프 주인 넘기기 ────────────────────────────────────────────────

    private void startOwnLoop() {
        if (loopThread != null) return;
        ownsLoop.set(true); // stopOwnLoop 뒤에 다시 켤 수 있어야 한다(QuicClient 2차 시도)
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (ownsLoop.get() && !closed.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p); // STUN은 receive 안에서 처리되고, 그 외에는 여기로 떨어진다
                } catch (IOException e) {
                    if (!closed.get()) LOG.debug("[ice] 루프 종료: {}", e.getMessage());
                    return;
                }
            }
        }, "quic-ice-loop");
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }

    /**
     * kwik에 소켓을 넘기기 직전에 부른다 — 둘이 동시에 receive하면 패킷을 서로 훔쳐간다.
     * <p>
     * <b>깨우기 패킷을 보내는 이유</b> — 루프 스레드는 블로킹 {@code DatagramSocket.receive()} 에
     * 걸려 있고 {@code interrupt()} 로는 깨지 않는다. 그래서 예전에는 {@code join(500)} 이 타임아웃을
     * 꽉 써서 접속마다 0.5초를 그냥 버렸다(실측: 홀펀칭 자체는 7ms 인데 이 단계가 508ms).
     * 자기 주소로 한 통 보내면 receive 가 즉시 돌아오고 루프가 플래그를 보고 나간다.
     */
    void stopOwnLoop() {
        ownsLoop.set(false);
        Thread t = loopThread;
        if (t == null) return;
        try {
            socket.send(new DatagramPacket(WAKE, WAKE.length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort())));
        } catch (IOException ignored) {
            // 못 보내도 아래 join 이 타임아웃으로 빠져나간다.
        }
        try { t.join(500); } catch (InterruptedException ignored) {}
        loopThread = null;
    }

    /** 경로 확인 체크를 보낸다 — 그 주소에서 응답이 오면 {@code onOk}. 5초 넘게 답 없는 건 버린다. */
    private void probe(InetSocketAddress to, Runnable onOk) {
        long now = System.currentTimeMillis();
        probes.values().removeIf(p -> now - p.at() > 5_000);
        try {
            byte[] txId = Stun.newTransactionId();
            probes.put(key(txId), new Probe(to, onOk, now));
            socket.send(Stun.packet(Stun.bindingRequest(txId), to));
        } catch (IOException e) {
            LOG.debug("[ice] 경로 확인 전송 실패: {}", e.getMessage());
        }
    }

    /**
     * 아는 연결의 패킷이 새 주소에서 왔다 — 상대 NAT 가 포트를 바꿨거나 망이 바뀌었다. 그 주소로 체크를 보내
     * <b>응답이 오면</b> 그때 보낼 곳을 옮긴다(libwebrtc 가 모르는 주소에서 온 체크로 prflx 후보를 만들어
     * 확인한 뒤 갈아타는 것과 같다). 확인 없이 옮기면 연결 ID 를 본 누군가가 경로를 가로챌 수 있다.
     */
    void validateMove(InetSocketAddress logical, InetSocketAddress to) {
        long now = System.currentTimeMillis();
        String k = addrKey(to);
        Long last = moveTried.get(k);
        if (last != null && now - last < 500) return;
        if (moveTried.size() > 256) moveTried.clear();
        moveTried.put(k, now);
        probe(to, () -> {
            if (addrKey(socket.actual(logical)).equals(k)) return;
            socket.moveTo(logical, to);
            // 주소는 남기지 않는다 — 상대의 공인 IP 다.
            LOG.info("[ice] 상대 주소가 바뀌어 새 주소로 옮겼다 — 연결은 그대로");
        });
    }

    /**
     * 접속자 쪽 경로 감시 — libwebrtc 의 연결 확인·페어 전환에 해당한다.
     * <p>
     * {@link #MONITOR_MS} 마다 지금 경로로 체크를 보낸다. 상대에게서 {@link #WEAK_MS} 동안 아무것도(QUIC 이든
     * 체크 응답이든) 못 받으면 알고 있는 다른 후보(방장의 relay 주소 포함)를 모두 두드려 <b>먼저 답한 곳으로</b>
     * 보낼 곳을 옮긴다. kwik 은 주소가 바뀐 걸 모른다 — 보내는 곳만 {@link IceSocket} 에서 바뀐다.
     * 옮긴 뒤 {@code onMove}(QUIC PING)로 kwik 을 깨워 밀린 재전송이 새 경로로 바로 나가게 한다.
     * <p>
     * 방장 쪽은 감시하지 않는다 — 접속자가 새 경로로 보내면 방장은 {@link #validateMove} 로 따라온다.
     */
    void startPathMonitor(InetSocketAddress logical, Runnable onMove) {
        String lk = addrKey(logical);
        socket.lastRecv.put(lk, System.currentTimeMillis());
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                try { Thread.sleep(MONITOR_MS); } catch (InterruptedException e) { return; }
                InetSocketAddress cur = socket.actual(logical);
                probe(cur, () -> {}); // 응답이 오면 수신 쪽에서 lastRecv 가 갱신된다
                if (System.currentTimeMillis() - socket.lastRecv.getOrDefault(lk, 0L) < WEAK_MS) continue;
                LOG.debug("[ice] 경로가 약하다 — 다른 후보를 확인한다: {}", remote.stream().map(Candidate::type).toList());
                for (Candidate c : remote) {
                    if (relayOnly && "host".equals(c.type())) continue; // 중계 강제면 사설 주소로는 못 간다
                    InetSocketAddress a = c.address();
                    if (addrKey(a).equals(addrKey(cur))) continue;
                    if (relayOnly && !socket.hasRelayChannel(a) && bindRelayFor(List.of(c)) == null) continue;
                    probe(a, () -> {
                        if (System.currentTimeMillis() - socket.lastRecv.getOrDefault(lk, 0L) < WEAK_MS) return;
                        socket.moveTo(logical, a);
                        LOG.info("[ice] 경로가 끊겨 다른 경로로 옮겼다 — 상대 후보 typ={}{}", c.type(),
                                usesRelay(c) ? " (중계)" : "");
                        onMove.run();
                    });
                }
            }
        }, "quic-ice-monitor");
        t.setDaemon(true);
        t.start();
    }

    /** 확정된 경로로 주기적으로 체크를 보내 NAT 바인딩을 살려 둔다. */
    private void startKeepalive() {
        if (keepaliveThread != null) return;
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                try { Thread.sleep(KEEPALIVE_MS); } catch (InterruptedException e) { return; }
                InetSocketAddress v = validated;
                if (v != null) sendCheck(v);
                pending.clear(); // 응답 없는 keepalive가 쌓이지 않게
            }
        }, "quic-ice-keepalive");
        t.setDaemon(true);
        keepaliveThread = t;
        t.start();
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        TurnAllocation alloc = turn;
        if (alloc != null) alloc.close();
        stopOwnLoop();
        Thread k = keepaliveThread;
        if (k != null) k.interrupt();
        socket.close();
    }

    private static String key(byte[] txId) {
        return java.util.HexFormat.of().formatHex(txId);
    }

    /**
     * STUN을 가로채는 소켓 — kwik이 {@code receive()}를 돌리는 동안에도 ICE가 계속 동작하게 한다.
     * {@code DatagramSocket}이 클래스라서 상속으로 끼어들 수 있는 것이고, 이게 kwik이 NIO
     * {@code DatagramChannel} 대신 이 API를 쓰는 덕을 보는 지점이다.
     */
    static final class IceSocket extends DatagramSocket {
        private final QuicIce ice;

        /** 방장 쪽 allocation. null 이면 중계를 쓰지 않는다. */
        volatile TurnAllocation turn;
        /**
         * 채널이 붙은 상대들 — 이 주소로 보내는 건 ChannelData 로 감싼다.
         * <b>상대마다 채널이 다르다</b>: 중계 접속자가 둘 이상인 방에서 채널 하나만 쓰면 나머지
         * 트래픽이 생으로 나가 상대 NAT 에서 버려진다.
         */
        private final Map<String, Integer> relayChannels = new ConcurrentHashMap<>();

        /**
         * 우리 allocation 을 거쳐 들어온 상대. 이 상대에게는 <b>온 길 그대로</b> allocation 으로만
         * 답한다(채널이 붙기 전에는 보내지 않는다) — 생으로 답하면 우리 실주소가 드러난다.
         * <p>
         * 방장이 IP 를 숨기는 근거가 이것이다. 예전엔 방을 열 때의 「중계 통신 강제」 값을 소켓에
         * 박아 두고 전부 막았는데, 방을 연 채로 설정을 끄면 후보는 직결로 알리면서 소켓은 여전히
         * 직결을 막아서(STUN 응답까지) 방을 다시 열 때까지 아무도 못 들어왔다.
         */
        private final Set<String> viaRelayPeers = ConcurrentHashMap.newKeySet();

        /**
         * 경로가 바뀐 상대 — kwik 이 아는 주소(처음 주소) → 지금 실제로 보낼 주소. kwik 은 연결을 만들 때의
         * 주소로만 보내므로({@code SenderImpl}), 상대가 옮겨 가도 kwik 에는 처음 주소로 보이게 하고 보낼 곳만
         * 여기서 바꾼다. 반대 방향 표({@code reverse})로 새 주소에서 온 패킷을 처음 주소에서 온 것처럼 고친다.
         */
        private final Map<String, InetSocketAddress> alias = new ConcurrentHashMap<>();
        private final Map<String, InetSocketAddress> reverse = new ConcurrentHashMap<>();
        /** QUIC 연결 ID(받는 쪽 ID) → 그 연결의 상대(처음 주소). 새 주소에서 온 패킷이 누구 것인지 여기서 안다. */
        private final Map<String, InetSocketAddress> cidOwner = new ConcurrentHashMap<>();
        /** 상대(처음 주소) → 마지막으로 뭐든 받은 시각 — 경로 감시가 끊김을 판정한다. */
        final Map<String, Long> lastRecv = new ConcurrentHashMap<>();
        /** 우리 연결 ID 길이 — kwik 서버 기본값, 접속자는 QuicClient 가 같은 값으로 맞춘다. short header 에는 길이가 안 실린다. */
        static final int CID_LENGTH = 8;

        InetSocketAddress actual(InetSocketAddress logical) {
            InetSocketAddress a = alias.get(addrKey(logical));
            return a != null ? a : logical;
        }

        void moveTo(InetSocketAddress logical, InetSocketAddress to) {
            String lk = addrKey(logical);
            InetSocketAddress old = alias.remove(lk);
            if (old != null) reverse.remove(addrKey(old));
            if (!addrKey(to).equals(lk)) {
                alias.put(lk, to);
                reverse.put(addrKey(to), logical);
            }
            lastRecv.put(lk, System.currentTimeMillis());
        }

        /** QUIC 패킷의 받는 쪽 연결 ID(hex), QUIC 이 아니면 null. */
        private static String dcid(byte[] b, int off, int len) {
            if (len < 1 || (b[off] & 0x40) == 0) return null;            // fixed bit
            if ((b[off] & 0x80) != 0) {                                   // long header: 길이가 실려 있다
                if (len < 6) return null;
                int n = b[off + 5] & 0xff;
                if (n == 0 || n > 20 || len < 6 + n) return null;
                return java.util.HexFormat.of().formatHex(b, off + 6, off + 6 + n);
            }
            if (len < 1 + CID_LENGTH) return null;                        // short header
            return java.util.HexFormat.of().formatHex(b, off + 1, off + 1 + CID_LENGTH);
        }

        void addRelayPeer(InetSocketAddress peer, int channel) {
            relayChannels.put(addrKey(peer), channel);
        }

        /** 이 상대에게 우리 allocation 을 거쳐 보내는지 — 상대별로 물어야 한다(클래스 주석). */
        boolean hasRelayChannel(InetSocketAddress peer) {
            return turn != null && relayChannels.containsKey(addrKey(actual(peer)));
        }

        private int channelFor(InetSocketAddress to) {
            Integer c = relayChannels.get(addrKey(to));
            return c != null ? c : -1;
        }


        IceSocket(QuicIce ice) throws SocketException {
            super(new InetSocketAddress(0));
            this.ice = ice;
            enlargeBuffers();
        }

        /**
         * 소켓 버퍼를 키운다. <b>여기가 끊김의 원인이었다.</b>
         * <p>
         * 이 환경의 {@link DatagramSocket} 기본 수신 버퍼는 64KB 다(실측) — QUIC 데이터그램이
         * 1252 바이트니 약 50개, 청크가 쏟아지는 순간에는 수십 밀리초 분량밖에 안 된다. 수신
         * 스레드가 GC 나 스케줄링으로 잠깐 늦으면 그 초과분을 <b>OS 가 그냥 버리고</b>, QUIC 은
         * 그걸 혼잡으로 읽어 창을 줄이고 재전송한다. 그래서 움직임이 튀었다.
         * <p>
         * 예전 WebRTC 경로에는 이 문제가 없었다 — libwebrtc 가 자기 소켓 버퍼를 알아서 크게
         * 잡는다. 우리가 소켓을 직접 만들면서 그 몫이 사라진 것이다.
         * <p>
         * 실패는 무시한다 — 버퍼를 못 키워도 통신은 되고, 못 키우는 환경에서 예외를 던져 방을
         * 못 열게 할 이유가 없다. 실제로 적용된 값을 로그에 남긴다(요청값이 그대로 들어간다는
         * 보장이 없다).
         */
        private void enlargeBuffers() {
            try {
                setReceiveBufferSize(SOCKET_BUFFER);
                setSendBufferSize(SOCKET_BUFFER);
                LOG.debug("[ice] 소켓 버퍼 수신 {}KB / 송신 {}KB",
                        getReceiveBufferSize() / 1024, getSendBufferSize() / 1024);
            } catch (SocketException e) {
                LOG.warn("[ice] 소켓 버퍼를 키우지 못했다 — 부하가 몰리면 끊길 수 있다: {}", e.getMessage());
            }
        }

        /**
         * 중계를 거쳐야 하는 상대면 4바이트 채널 헤더로 감싸 TURN 서버로 보낸다.
         * <b>kwik 은 이걸 전혀 모른다</b> — 평소처럼 상대 주소로 send 하면 여기서 경로가 바뀐다.
         */
        @Override public void send(DatagramPacket p) throws IOException {
            TurnAllocation alloc = turn;
            InetSocketAddress to = (InetSocketAddress) p.getSocketAddress();
            InetSocketAddress moved = alias.isEmpty() ? to : actual(to); // 경로가 바뀐 상대면 지금 주소로
            if (moved != to) {
                to = moved;
                p = new DatagramPacket(p.getData(), p.getOffset(), p.getLength(), to);
            }
            int channel = channelFor(to);
            if (channel >= 0 && alloc != null) {
                byte[] framed = Turn.wrapChannelData(channel, p.getData(), p.getOffset(), p.getLength());
                alloc.sendToServer(framed, 0, framed.length); // UDP, 또는 UDP 가 막혔으면 TCP
                return;
            }
            // 중계 강제인데 allocation 을 안 거치는 목적지면 보내지 않는다. 그냥 내보내면 우리
            // 실주소가 상대에게 드러나 기능이 무의미해진다(TURN 서버로 가는 요청은 예외).
            // 루프백도 예외다 — 수신 루프를 깨우는 자기 앞 패킷은 밖으로 나가지 않는다
            // (stopOwnLoop 주석). 막으면 그 단계가 다시 타임아웃을 꽉 쓴다.
            if ((ice.relayOnly || viaRelayPeers.contains(addrKey(to))) && alloc != null
                    && !TurnAllocation.sameAddress(to, alloc.server())
                    && !to.getAddress().isLoopbackAddress()) {
                return;
            }
            super.send(p);
        }

        @Override public void receive(DatagramPacket p) throws IOException {
            while (true) {
                super.receive(p);
                TurnAllocation alloc = turn;
                InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();

                // 수신 루프를 깨우는 자기 앞 패킷은 그대로 돌려준다(stopOwnLoop). TCP 중계에서는
                // 서버 메시지도 자기 주소로 들어오므로 이걸 먼저 걸러야 한다 — 안 그러면 삼켜져서
                // 루프가 안 멈추고 kwik 의 패킷을 계속 가로챈다.
                if (isWake(p, from)) return;

                // TURN 서버에서 온 것이면 껍데기를 벗겨 상대가 직접 보낸 것처럼 만든다.
                if (alloc != null && alloc.fromServer(from)) {
                    if (!unwrapRelayed(p, alloc)) continue; // 우리가 다룰 게 아니면 버린다
                }

                InetSocketAddress src = (InetSocketAddress) p.getSocketAddress();
                InetSocketAddress logical = reverse.isEmpty() ? null : reverse.get(addrKey(src));
                if (Stun.looksLikeStun(p.getData(), p.getOffset(), p.getLength())) {
                    lastRecv.put(addrKey(logical != null ? logical : src), System.currentTimeMillis());
                    ice.onStun(p); // 출처는 그대로 둔다 — 체크 응답은 실제 주소로 짝을 맞춘다
                    continue; // QUIC에는 넘기지 않는다
                }
                String cid = dcid(p.getData(), p.getOffset(), p.getLength());
                if (logical == null && cid != null) {
                    InetSocketAddress owner = cidOwner.get(cid);
                    if (owner != null && !addrKey(owner).equals(addrKey(src))) {
                        logical = owner;                    // 아는 연결이 새 주소에서 왔다
                        ice.validateMove(owner, src);       // 확인되면 보낼 곳도 옮긴다
                    }
                }
                if (logical != null) p.setSocketAddress(logical); // kwik 에는 처음 주소로 보인다
                InetSocketAddress who = logical != null ? logical : src;
                if (cid != null) {
                    if (cidOwner.size() > 4096) cidOwner.clear(); // ponytail: 오래된 연결 ID 를 하나씩 치우는 대신 통째로 비운다
                    cidOwner.putIfAbsent(cid, who);
                }
                lastRecv.put(addrKey(who), System.currentTimeMillis());
                return;
            }
        }

        /**
         * ChannelData / Data indication 을 평문 페이로드로 바꾸고 출처를 상대 주소로 고친다.
         * <p>
         * Data indication 은 채널이 붙기 <b>전</b>에 오는 형태다. 여기서 상대의 실제 주소를 처음
         * 알게 되므로 그때 채널을 붙인다 — 대칭 NAT 상대면 시그널링으로 받은 후보와 포트가
         * 다르기 때문에 이 값이어야 맞다.
         *
         * @return QUIC/STUN 으로 넘길 내용이 담겼으면 true
         */
        private boolean unwrapRelayed(DatagramPacket p, TurnAllocation alloc) {
            byte[] buf = p.getData();
            int off = p.getOffset(), len = p.getLength();

            if (Turn.isChannelData(buf, off, len)) {
                InetSocketAddress peer = alloc.peerForChannel(Turn.channelOf(buf, off));
                if (peer == null) return false; // 우리가 붙인 채널이 아니다
                viaRelayPeers.add(addrKey(peer));
                int dataLen = Math.min(Turn.channelDataLength(buf, off), len - 4);
                System.arraycopy(buf, off + 4, buf, off, dataLen);
                p.setLength(dataLen);
                p.setSocketAddress(peer);
                return true;
            }

            if (Turn.isDataIndication(buf, off, len)) {
                byte[] txId = Stun.transactionId(buf, off);
                InetSocketAddress peer = Turn.dataPeer(buf, off, len, txId);
                int[] payload = Turn.dataPayload(buf, off, len);
                if (peer == null || payload == null) return false;
                viaRelayPeers.add(addrKey(peer));
                if (channelFor(peer) < 0) {
                    // 주소는 로그에 남기지 않는다 — 접속자의 공인 IP 다.
                    LOG.debug("[turn] 중계로 상대 도착 — 채널을 붙인다");
                    // 응답을 기다려야 ChannelData 를 쓸 수 있다. QUIC 이 Initial 을 재전송하므로
                    // 이 한 왕복만큼 핸드셰이크가 늦어질 뿐 실패하지는 않는다.
                    Thread bind = new Thread(() -> {
                        int ch = alloc.bindChannel(peer);
                        if (ch >= 0) addRelayPeer(peer, ch);
                    }, "quic-turn-bind");
                    bind.setDaemon(true);
                    bind.start();
                }
                int dataLen = Math.min(payload[1], len - (payload[0] - off));
                System.arraycopy(buf, payload[0], buf, off, dataLen);
                p.setLength(dataLen);
                p.setSocketAddress(peer);
                return true;
            }

            // Allocate/CreatePermission/ChannelBind/Refresh 응답 — 기다리는 쪽에 넘긴다.
            // TURN 응답은 STUN 형식이라 그냥 두면 아래 STUN 가로채기가 먹어버린다.
            alloc.onResponse(buf, off, len);
            return false;
        }

        /** 후보 수집 단계 전용 — 가로채기 없이 그대로 읽는다. */
        void receiveRaw(DatagramPacket p) throws IOException {
            super.receive(p);
        }

        /** 가로채기 없이 그대로 보낸다 — TURN 서버로 가는 것과 TCP 중계 메시지를 자기에게 넣을 때. */
        void sendRaw(DatagramPacket p) throws IOException {
            super.send(p);
        }

        private boolean isWake(DatagramPacket p, InetSocketAddress from) {
            if (p.getLength() != WAKE.length || !from.getAddress().isLoopbackAddress()
                    || from.getPort() != getLocalPort()) return false;
            return java.util.Arrays.equals(WAKE, 0, WAKE.length, p.getData(), p.getOffset(), p.getOffset() + WAKE.length);
        }
    }
}
