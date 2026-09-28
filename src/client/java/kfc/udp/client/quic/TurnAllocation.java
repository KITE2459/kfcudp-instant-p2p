package kfc.udp.client.quic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * coturn 에 잡아 둔 TURN allocation 하나 — 홀펀칭이 안 되는 상대를 위한 우회로.
 * <p>
 * <b>방장만 잡는다.</b> 접속자는 allocation 없이 방장의 relayed 주소로 그냥 보내면 된다. 이유:
 * <ul>
 *   <li>접속자 쪽은 <b>바깥으로 나가는</b> 트래픽뿐이라 대칭 NAT 여도 문제가 없다.</li>
 *   <li>방장 쪽도 allocation 자체가 바깥으로 맺은 매핑이라 대칭 NAT 여도 산다.</li>
 *   <li>양쪽이 각자 allocation 을 잡으면 coturn 을 <b>두 번</b> 지나 대역폭이 두 배가 된다.</li>
 * </ul>
 * 즉 한쪽 allocation 으로 두 대칭 NAT 경우를 다 덮는다.
 * <p>
 * <b>권한(permission)은 IP 단위다</b>(RFC 5766 §9) — 포트는 보지 않는다. 그래서 접속자가 대칭
 * NAT 라 포트가 달라져도, 시그널링으로 받은 후보의 IP 로 권한을 미리 걸어 두면 첫 패킷이 통과한다.
 * 그 첫 패킷(Data indication)에서 <b>접속자의 실제 주소</b>를 알게 되면 채널을 붙여, 이후는
 * 패킷당 36바이트 대신 4바이트로 흐른다.
 */
final class TurnAllocation {

    private static final Logger LOG = LoggerFactory.getLogger("quic-turn");

    /**
     * 요청하는 allocation 수명. <b>짧게 잡는다</b> — 어떤 이유로 해제를 못 하고 끝났을 때
     * (프로세스 강제 종료 등) 서버에 남는 시간이 그만큼 짧아진다. 실제로 이게 쌓여
     * {@code 486 Allocation Quota Reached} 로 중계가 통째로 막힌 적이 있다.
     */
    private static final int LIFETIME_SEC = 300;
    /**
     * 갱신 주기. allocation 수명만 보면 되는 게 아니다 — <b>권한(300초)과 채널 바인딩(600초)에
     * 각자 수명이 있고, allocation Refresh 는 그것들을 갱신하지 않는다</b>(RFC 5766 §8, §11.2).
     * 가장 짧은 권한 수명의 절반보다 짧게 잡아 셋 다 여유를 둔다.
     */
    private static final long REFRESH_MS = 120_000;
    /** 요청 하나를 기다리는 총 시간(재전송 포함). coturn 은 첫 서명 요청의 키 조회를 비동기로 해서 답이 늦을 수 있다. */
    private static final int RTT_TIMEOUT_MS = 5_000;
    /** 첫 재전송까지의 시간 — 이후 두 배씩(200, 400, 800…). pion/turn 의 defaultRTO 와 같다. */
    private static final long RTO_MS = 200;
    /** 438(Stale Nonce) 재시도 횟수 — pion/turn 의 {@code maxRetryAttempts} 와 같다. */
    private static final int MAX_RETRY = 3;
    private static final int TCP_CONNECT_TIMEOUT_MS = 3_000;
    /** 테스트 플래그 — UDP 를 건너뛰고 바로 TCP 로 잡는다(UDP 가 막힌 망을 흉내낼 방법이 없어서). */
    private static final boolean FORCE_TCP = Boolean.getBoolean("kfcudp.turn.forcetcp");

    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final String username;
    private final String password;

    private volatile Turn.Credentials cred;
    private volatile InetSocketAddress relayed;

    /**
     * UDP 가 막힌 망(학교·회사 등)에서 중계 서버와 말하는 TCP 연결 — null 이면 UDP 를 쓴다
     * ({@link #allocate} 주석). 클라이언트↔TURN 서버 구간만 TCP 이고, 서버가 상대에게 보내는 쪽은
     * 그대로 UDP 다(RFC 5766 §2.1).
     * <p>
     * TCP 로 받은 메시지는 <b>같은 UDP 소켓의 자기 루프백 주소로 다시 넣는다</b>({@link #startTcpReader}).
     * 그러면 kwik 과 기존 수신 경로({@code IceSocket.receive})를 하나도 안 바꾸고 그대로 탄다.
     * 다른 프로그램은 우리 포트에서 보낼 수 없으니 이 출처는 위조할 수 없다.
     */
    private volatile java.net.Socket tcp;
    private volatile java.io.OutputStream tcpOut;
    /** 마지막 allocate 시도에서 UDP 응답이 아예 없었는지 — 그때만 TCP 로 넘어간다. */
    private volatile boolean udpSilent;
    private volatile Thread refresher;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 보낸 요청의 응답 대기소. <b>TURN 응답은 STUN 형식</b>이라({@code 0x0103} 등 첫 2비트가 00)
     * {@link QuicIce.IceSocket} 의 STUN 가로채기에 먹힌다 — 그래서 소켓을 직접 읽는 대신
     * 가로채는 쪽이 {@link #onResponse} 로 넘겨주고 여기서 기다린다. kwik 이 수신 루프를
     * 가진 뒤에도 같은 방식으로 동작한다(패킷을 서로 훔쳐가지 않는다).
     */
    private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

    /** 권한을 건 상대 — 채널이 없어도 갱신해야 한다(권한 수명 300초, RFC 5766 §8). */
    private final java.util.Set<String> permitted = ConcurrentHashMap.newKeySet();

    /** 상대 → 배정된 채널 번호. */
    private final Map<String, Integer> channels = new ConcurrentHashMap<>();
    /** 채널 번호에서 상대 주소를 되찾기 위한 보관소. */
    private final Map<String, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextChannel =
            new java.util.concurrent.atomic.AtomicInteger(Turn.CHANNEL_MIN);

    TurnAllocation(DatagramSocket socket, InetSocketAddress server, String username, String password) {
        this.socket = socket;
        this.server = server;
        this.username = username;
        this.password = password;
    }

    InetSocketAddress server() { return server; }
    /** 상대가 우리에게 보낼 주소. allocate 성공 전에는 null. */
    InetSocketAddress relayedAddress() { return relayed; }

    /**
     * allocation 을 잡는다. <b>이 메서드는 소켓을 직접 읽으므로 다른 수신 루프가 돌기 전에 불러야 한다</b>
     * ({@link QuicIce#gather} 와 같은 단계).
     * <p>
     * UDP 로 <b>응답이 아예 없으면</b>(재전송까지 다 해도) UDP 가 막힌 망으로 보고 같은 포트의 TCP 로
     * 다시 잡는다. 거절 응답(401·486 등)이 왔다면 UDP 는 통하는 것이므로 TCP 로 가지 않는다.
     * libwebrtc(TurnPort PROTO_TCP)·Safra 도 같은 우회를 둔다. (80 으로도 받으려 했지만 80 은 인증서
     * 발급·갱신에 필요해 Caddy 가 가진다 — deploy-caddy.sh 주석.)
     *
     * @return 성공하면 relayed 주소, 실패하면 null(그 경우 중계 없이 진행한다)
     */
    InetSocketAddress allocate() {
        InetSocketAddress r = null;
        if (FORCE_TCP) {
            udpSilent = true; // 테스트: UDP 가 막힌 망을 흉내낸다
        } else {
            r = allocateOnce();
        }
        if (r != null || !udpSilent || closed.get()) return r;
        if (!openTcp(server.getPort())) return null;
        LOG.info("[turn] UDP 로는 응답이 없다 — TCP {} 로 다시 잡는다", server.getPort());
        r = allocateOnce();
        if (r == null) closeTcp();
        return r;
    }

    private boolean openTcp(int port) {
        try {
            java.net.Socket s = new java.net.Socket(java.net.Proxy.NO_PROXY);
            s.connect(new InetSocketAddress(server.getAddress(), port), TCP_CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            tcpOut = s.getOutputStream();
            tcp = s;
            startTcpReader(s);
            return true;
        } catch (IOException e) {
            LOG.debug("[turn] TCP {} 연결 실패: {}", port, e.getMessage());
            return false;
        }
    }

    private void closeTcp() {
        java.net.Socket s = tcp;
        tcp = null;
        tcpOut = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    /**
     * TCP 스트림을 메시지로 잘라 UDP 소켓의 자기 주소로 넣는다. 자르는 규칙은 pion/turn
     * {@code consumeSingleTURNFrame} 과 같다 — STUN 은 헤더 20 + 길이, ChannelData 는 4 + 길이를
     * 4 바이트 단위로 올린 만큼(패딩은 떼고 넘긴다).
     */
    private void startTcpReader(java.net.Socket s) {
        Thread t = new Thread(() -> {
            InetSocketAddress self = new InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), socket.getLocalPort());
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(s.getInputStream()))) {
                byte[] head = new byte[4];
                while (tcp == s) {
                    in.readFully(head);
                    int len = ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                    boolean stun = (head[0] & 0xC0) == 0;
                    if (!stun && (head[0] & 0xC0) != 0x40) throw new IOException("스트림이 어긋났다");
                    int total = stun ? 20 + len : 4 + len + (4 - len % 4) % 4;
                    byte[] frame = new byte[total];
                    System.arraycopy(head, 0, frame, 0, 4);
                    in.readFully(frame, 4, total - 4);
                    ((QuicIce.IceSocket) socket).sendRaw(new DatagramPacket(frame, stun ? total : 4 + len, self));
                }
            } catch (IOException e) {
                if (tcp == s && !closed.get()) LOG.warn("[turn] TCP 연결이 끊겼다: {}", e.getMessage());
            }
        }, "quic-turn-tcp");
        t.setDaemon(true);
        t.start();
    }

    /** 서버로 보낸다 — UDP 거나, UDP 가 막혔으면 TCP(tcp 주석). */
    void sendToServer(byte[] msg, int off, int len) throws IOException {
        java.io.OutputStream o = tcpOut;
        if (o == null) {
            ((QuicIce.IceSocket) socket).sendRaw(new DatagramPacket(msg, off, len, server));
            return;
        }
        // TCP 위의 ChannelData 는 4 바이트 단위로 채운다(RFC 5766 §11.5, pion ChannelData.Encode).
        int pad = Turn.isChannelData(msg, off, len) ? (4 - len % 4) % 4 : 0;
        synchronized (o) {
            o.write(msg, off, len);
            if (pad > 0) o.write(new byte[pad]);
            o.flush();
        }
    }

    /** 이 패킷이 TURN 서버에서 온 것인지 — TCP 면 우리가 자기 주소로 넣은 것이 그것이다. */
    boolean fromServer(InetSocketAddress from) {
        if (tcp == null) return sameAddress(from, server);
        return from.getAddress().isLoopbackAddress() && from.getPort() == socket.getLocalPort();
    }

    private InetSocketAddress allocateOnce() {
        udpSilent = false;
        try {
            int oldTimeout = socket.getSoTimeout();
            try {
                socket.setSoTimeout(RTT_TIMEOUT_MS);

                // 1차: 서명 없이 보내 REALM/NONCE 를 받아온다(TURN 은 이 왕복이 규약이다).
                byte[] tx = Stun.newTransactionId();
                Turn.Credentials anon = new Turn.Credentials(username, password, null, null);
                byte[] resp = request(Turn.allocate(tx, anon, LIFETIME_SEC), tx, true);
                if (resp == null) {
                    udpSilent = tcp == null; // UDP 로 아무 답도 없었다 — allocate 가 TCP 로 넘어간다
                    LOG.debug("[turn] allocate 무응답{}", tcp == null ? "" : " (TCP)");
                    return null;
                }
                Turn.Challenge ch = Turn.challenge(resp, 0, resp.length);
                if (ch == null) {
                    LOG.warn("[turn] REALM/NONCE 를 못 받았다(code={}) — 중계 없이 진행",
                            Turn.errorCode(resp, 0, resp.length));
                    return null;
                }

                // 2차: 서명해서 다시.
                cred = new Turn.Credentials(username, password, ch.realm(), ch.nonce());
                byte[] tx2 = Stun.newTransactionId();
                byte[] ok = request(Turn.allocate(tx2, cred, LIFETIME_SEC), tx2, true);
                if (ok == null || !Turn.isSuccess(ok, 0)) {
                    int code = ok != null ? Turn.errorCode(ok, 0, ok.length) : -1;
                    if (code == 486) {
                        // 486 = Allocation Quota Reached (RFC 5766 §15). 서버 자리가 없다는 뜻이고,
                        // 원인은 대개 해제되지 않고 남은 allocation 이다(close 주석). 남았어도
                        // 수명이 지나면 사라지므로, 몇 분 뒤에는 다시 된다.
                        LOG.warn("[turn] 중계 서버 할당 한도에 걸렸다(486) — 중계 없이 진행."
                                + " 남은 할당이 만료되면 다시 된다");
                    } else {
                        LOG.warn("[turn] allocate 실패(code={}) — 중계 없이 진행", code);
                    }
                    return null;
                }
                relayed = Turn.relayedAddress(ok, 0, ok.length, tx2);
                if (relayed == null) {
                    LOG.warn("[turn] relayed 주소를 못 읽었다 — 중계 없이 진행");
                    return null;
                }
                // 주소는 로그에 남기지 않는다(포트만) — 공인 IP 가 로그로 새는 걸 막는다.
                LOG.debug("[turn] allocation 확보 — relayed port={}{}", relayed.getPort(),
                        tcp != null ? " (TCP " + tcp.getPort() + " 경유 — UDP 가 막힌 망)" : "");
                startRefresher();
                return relayed;
            } finally {
                socket.setSoTimeout(oldTimeout);
            }
        } catch (Exception e) {
            LOG.warn("[turn] allocate 중 예외: {} — 중계 없이 진행", e.toString());
            return null;
        }
    }

    /**
     * 상대 IP 에 권한을 건다 — 이게 없으면 coturn 이 상대의 첫 패킷을 그냥 버린다.
     * 권한은 IP 단위라 포트는 아무 값이나 넣어도 된다.
     */
    boolean createPermission(InetSocketAddress peer) {
        if (cred == null || relayed == null) return false;
        addresses.put(key(peer), peer);
        permitted.add(key(peer));
        byte[] r = signed((tx, c) -> Turn.createPermission(tx, c, peer));
        boolean ok = r != null && Turn.isSuccess(r, 0);
        if (!ok) {
            int code = code(r);
            // 400 은 거의 항상 coturn 의 multiplex-peer 제약이다("Peer address already used by
            // another multiplex-peer allocation"). 그 옵션은 peer 소켓을 공유하며 peer 주소로
            // demux 하므로 한 주소가 allocation 하나에만 속할 수 있다 — 양쪽이 다 중계를
            // 쓰거나, 서로 다른 방의 접속자가 공인 IP 를 공유하면 여기서 걸린다.
            if (code == 400) {
                LOG.warn("[turn] 권한 등록 거부(400) — coturn 의 multiplex-peer 제약으로 보인다"
                        + " (그 주소가 다른 allocation 에 이미 묶여 있음)");
            } else {
                LOG.debug("[turn] createPermission 실패 code={}", code);
            }
        }
        return ok;
    }

    /**
     * 상대의 실제 주소에 채널을 붙이고 배정된 번호를 돌려준다(실패하면 -1).
     * <b>상대마다 다른 번호</b>를 쓴다 — 한 번호를 다른 상대로 재지정하면 coturn 이 거부하고,
     * 중계 접속자가 둘 이상인 방에서 하나만 쓰면 나머지 트래픽이 생으로 나가 버린다.
     * <p>
     * synchronized — punch 와 "중계로 상대 도착" 수신 쪽이 같은 상대를 동시에 붙이면 번호를 둘 받아,
     * 한 상대에 두 번째 번호를 거는 셈이라 coturn 이 400 으로 거부했다. 두 번째 호출은 첫 번호를 그대로 받는다.
     */
    synchronized int bindChannel(InetSocketAddress peer) {
        if (cred == null || relayed == null) return -1;
        Integer existing = channels.get(key(peer));
        if (existing != null) return existing;
        int channel = nextChannel.getAndIncrement();
        if (channel > Turn.CHANNEL_MAX) {
            LOG.warn("[turn] 채널 번호가 고갈됐다");
            return -1;
        }
        addresses.put(key(peer), peer);
        byte[] r = signed((tx, c) -> Turn.channelBind(tx, c, channel, peer));
        boolean ok = r != null && Turn.isSuccess(r, 0);
        if (!ok) {
            LOG.info("[turn] channelBind 실패 code={}", code(r));
            return -1;
        }
        LOG.debug("[turn] channelBind 성공 — 이후 4바이트 헤더로 중계");
        channels.put(key(peer), channel);
        return channel;
    }

    /**
     * 서명된 요청을 보내고 응답을 기다린다. <b>438 Stale Nonce 면 응답에 실린 새 nonce 로 바꿔 다시
     * 보낸다</b>(최대 {@link #MAX_RETRY}회) — pion/turn {@code allocation.refreshAllocation} /
     * {@code UDPConn.bind} 와 같은 처리다.
     * <p>
     * coturn 은 nonce 를 <b>기본 600초 뒤 만료</b>시킨다({@code stale-nonce}, mainrelay.c). 예전엔 처음
     * 받은 nonce 를 끝까지 써서, 방을 연 지 10분이 지나면 갱신·권한·채널 요청이 전부 438 로 거부됐다
     * ("직결로 11분 놀다 방장이 중계로 바꾸니 안 붙는다", 그리고 중계로 10분 넘게 놀면 끊기는 것).
     * <p>
     * 응답은 소켓을 읽는 쪽(kwik 또는 우리 수신 루프)이 {@link #onResponse} 로 넘겨준다 — 여기서
     * 소켓을 직접 읽거나 소켓 타임아웃을 건드리면 kwik 의 수신을 방해한다.
     */
    private byte[] signed(java.util.function.BiFunction<byte[], Turn.Credentials, byte[]> build) {
        for (int i = 0; i < MAX_RETRY; i++) {
            Turn.Credentials c = cred;
            if (c == null) return null;
            byte[] tx = Stun.newTransactionId();
            byte[] r;
            try {
                r = request(build.apply(tx, c), tx, false);
            } catch (IOException e) {
                return null;
            }
            if (r == null || Turn.isSuccess(r, 0) || code(r) != 438) return r;
            String nonce = Turn.nonce(r, 0, r.length);
            if (nonce == null) return r;
            cred = new Turn.Credentials(c.username(), c.password(), c.realm(), nonce);
            LOG.debug("[turn] nonce 만료(438) — 새 nonce 로 다시 보낸다");
        }
        return null;
    }

    private static int code(byte[] r) {
        return r != null ? Turn.errorCode(r, 0, r.length) : -1;
    }

    /** 이 상대에게 배정된 채널 번호, 없으면 -1. */
    int channelFor(InetSocketAddress peer) {
        Integer c = channels.get(key(peer));
        return c != null ? c : -1;
    }

    /** 이 채널 번호에 붙은 상대, 없으면 null. */
    InetSocketAddress peerForChannel(int channel) {
        for (Map.Entry<String, Integer> e : channels.entrySet()) {
            if (e.getValue() == channel) return addresses.get(e.getKey());
        }
        return null;
    }

    private static String key(InetSocketAddress a) {
        return a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    /**
     * allocation 을 <b>서버에서 지운다</b>. 단순히 소켓을 닫는 것으로는 안 된다 — UDP 라
     * coturn 은 우리가 사라진 걸 모르고, 수명이 다할 때까지 자리를 잡고 있는다.
     * <p>
     * 실제로 이것 때문에 사고가 났다: 접속 시도가 실패할 때마다 allocation 이 하나씩 남아
     * 몇 분 만에 {@code 486 Allocation Quota Reached} 가 떠서, 그 뒤로는 중계가 필요한
     * 사람 전원이 못 붙었다("되던 게 갑자기 안 된다"). 해제는 수명 0 의 Refresh 다(RFC 5766 §7).
     * <p>
     * 응답은 기다리지 않는다 — 이 시점엔 kwik 이 소켓을 읽고 있을 수 있고, 어차피 우리가
     * 확인할 게 없다. 실패해도 수명이 지나면 사라진다.
     */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        Thread t = refresher;
        if (t != null) t.interrupt();

        Turn.Credentials c = cred;
        if (c != null && relayed != null) {
            try {
                byte[] msg = Turn.refresh(Stun.newTransactionId(), c, 0);
                sendToServer(msg, 0, msg.length);
                LOG.debug("[turn] allocation 해제 요청 전송");
            } catch (IOException e) {
                // 소켓이 이미 닫혔을 수 있다 — 그러면 수명(LIFETIME_SEC)이 지나 자동으로 사라진다.
                LOG.debug("[turn] allocation 해제 실패(무시): {}", e.getMessage());
            }
        }
        // TCP 면 연결을 닫는다 — coturn 은 TCP 가 끊기면 그 allocation 을 바로 지운다.
        closeTcp();
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    /** {@link QuicIce.IceSocket} 이 TURN 서버에서 온 응답을 넘겨준다. */
    void onResponse(byte[] buf, int off, int len) {
        CompletableFuture<byte[]> f = pending.remove(key(Stun.transactionId(buf, off)));
        if (f != null) f.complete(Arrays.copyOfRange(buf, off, off + len));
    }

    /**
     * 요청을 보내고 응답을 기다린다. <b>응답이 없으면 같은 요청(같은 트랜잭션 ID)을 다시 보낸다</b> —
     * UDP 라 요청이나 응답 하나가 사라질 수 있다. STUN 규격(RFC 5389 §7.2.1)이 재전송을 요구하고
     * libwebrtc({@code StunRequest})·pion({@code transaction}) 모두 그렇게 한다.
     * <p>
     * 예전엔 한 번 보내고 끝이었다. 게다가 {@code pump} 모드에서 소켓 타임아웃이 나면 읽기를 멈추고
     * 나머지 시간을 <b>아무도 소켓을 읽지 않는 채로</b> 기다려서, 조금 늦게 온 응답도 못 받았다.
     * 그 결과가 간헐적인 "allocate 무응답" — 중계 후보가 통째로 빠져 중계 강제 방장이 후보 0개가 됐다.
     *
     * @param pump 우리가 소켓을 직접 읽어야 하는지. allocate 단계는 아직 아무도 수신 루프를
     *             돌리지 않으니 {@code true}, 그 뒤(권한·채널)는 다른 쪽이 읽어서 넘겨주므로 {@code false}.
     */
    private byte[] request(byte[] msg, byte[] txId, boolean pump) throws IOException {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        pending.put(key(txId), f);
        try {
            long deadline = System.currentTimeMillis() + RTT_TIMEOUT_MS;
            long rto = RTO_MS;
            long nextSend = 0;
            byte[] buf = pump ? new byte[1500] : null;
            while (!f.isDone()) {
                long now = System.currentTimeMillis();
                if (now >= deadline) break;
                if (now >= nextSend) {
                    sendToServer(msg, 0, msg.length);
                    nextSend = now + rto;
                    rto *= 2;
                }
                long wait = Math.max(1, Math.min(nextSend, deadline) - System.currentTimeMillis());
                if (pump) {
                    // 다음 재전송 시각까지만 읽는다 — 타임아웃이 나도 멈추지 않고 다시 보낸 뒤 계속 읽는다.
                    socket.setSoTimeout((int) wait);
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        ((QuicIce.IceSocket) socket).receiveRaw(p);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    if (p.getLength() >= 20) onResponse(p.getData(), p.getOffset(), p.getLength());
                } else {
                    try {
                        f.get(wait, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        // 다음 재전송으로
                    }
                }
            }
            return f.getNow(null);
        } catch (Exception e) {
            return null;
        } finally {
            pending.remove(key(txId));
        }
    }

    private static String key(byte[] txId) {
        return java.util.HexFormat.of().formatHex(txId);
    }

    /**
     * allocation·권한·채널 바인딩을 계속 갱신한다. 하나라도 만료되면 중계가 <b>조용히</b> 끊긴다.
     * <p>
     * 예전엔 allocation 만 갱신했다. 그래서 중계로 붙은 사람이 <b>정확히 10분 30초</b>쯤에
     * "시간 초과"로 튕겼다 — 채널 바인딩 수명이 600초인데(RFC 5766 §11.2) allocation Refresh 로는
     * 갱신되지 않기 때문이다. 같은 ChannelBind 를 다시 보내면 갱신되고, 그게 권한도 같이
     * 갱신해 준다(§11).
     */
    private void startRefresher() {
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                try { Thread.sleep(REFRESH_MS); } catch (InterruptedException e) { return; }
                if (closed.get()) return;

                // 응답을 기다린다 — 그래야 438 을 보고 nonce 를 바꿀 수 있다(signed 주석).
                // 예전엔 보내고 끝이라, nonce 가 만료된 뒤로는 갱신이 전부 조용히 실패했다.
                byte[] r = signed((tx, c) -> Turn.refresh(tx, c, LIFETIME_SEC));
                if (r == null || !Turn.isSuccess(r, 0)) {
                    LOG.warn("[turn] allocation 갱신 실패 code={}", code(r));
                }

                // 채널 바인딩 갱신 — 같은 ChannelBind 를 다시 보내면 채널(600초)과 그 권한(300초)이
                // 같이 갱신된다(RFC 5766 §11). allocation Refresh 로는 갱신되지 않는다.
                for (Map.Entry<String, Integer> e : channels.entrySet()) {
                    InetSocketAddress peer = addresses.get(e.getKey());
                    if (peer == null) continue;
                    int ch = e.getValue();
                    byte[] b = signed((tx, c) -> Turn.channelBind(tx, c, ch, peer));
                    if (b == null || !Turn.isSuccess(b, 0)) {
                        LOG.warn("[turn] 채널 갱신 실패 code={}", code(b));
                    }
                }

                // 채널 없이 권한만 건 상대 — pion/turn 처럼 CreatePermission 을 다시 보낸다.
                for (String k : permitted) {
                    if (channels.containsKey(k)) continue;
                    InetSocketAddress peer = addresses.get(k);
                    if (peer != null) signed((tx, c) -> Turn.createPermission(tx, c, peer));
                }
            }
        }, "quic-turn-refresh");
        t.setDaemon(true);
        refresher = t;
        t.start();
    }

    /** {@code turn:HOST:PORT} 파싱. */
    static InetSocketAddress parseUrl(String url) {
        String s = url.startsWith("turn:") ? url.substring(5) : url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int colon = s.lastIndexOf(':');
        if (colon <= 0) return null;
        try {
            return new InetSocketAddress(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean sameAddress(InetSocketAddress a, InetSocketAddress b) {
        return a != null && b != null && a.getPort() == b.getPort()
                && Arrays.equals(a.getAddress().getAddress(), b.getAddress().getAddress());
    }
}
