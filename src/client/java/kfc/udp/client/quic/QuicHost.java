package kfc.udp.client.quic;

import kfc.udp.client.webrtc.P2PConfig;
import kfc.udp.client.webrtc.VillasMsg;
import kfc.udp.client.webrtc.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnector;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QUIC 방장 — 접속자의 QUIC 스트림을 열린 LAN 포트로 잇는다.
 * <p>
 * <b>시그널링 구조는 WebRTC 쪽과 같은 2단</b>이지만 경로를 {@code /{roomId}-q} 로 분리해
 * {@code WebRtcHost} 와 완전히 독립적으로 돈다 — 두 전송을 같은 방 코드로 동시에 띄워 비교할 수
 * 있어야 하기 때문이다. 경로만 다를 뿐이라 시그널링 서버에는 아무 변경이 없다.
 * <ol>
 *   <li><b>로비</b> {@code /{roomId}-q}: 방장이 상주. 접속자가 {@code j?{16hex}} 로 잠깐 붙어 알린다.</li>
 *   <li><b>페어</b> {@code /{roomId}-q-{sid}}: 방장이 주소·포트·인증서 지문을 한 번 보내고 끝.
 *       SDP/ICE 왕복이 없다 — QUIC은 핸드셰이크를 자기가 하므로 접속자는 "어디로 갈지"만 알면 된다.</li>
 * </ol>
 * <b>1단계 한계</b> — 아직 ICE가 없어서 <b>같은 LAN(또는 같은 기기)에서만</b> 된다. 방장은 자기
 * 사설 IP를 그대로 알려준다. STUN(홀펀칭)과 TURN(중계) 후보는 2·3단계에서 붙인다.
 */
public final class QuicHost {

    private static final Logger LOG = LoggerFactory.getLogger("quic-host");

    /** ALPN — QUIC은 응용 프로토콜 이름이 필수다. 양쪽이 같아야 한다. */
    static final String ALPN = "instant-p2p";
    /** 페어 세션에 실어 보내는 description 타입. 서버는 내용을 해석하지 않고 그대로 중계한다. */
    static final String MSG_TYPE = "quic-host";

    private static final int DIAL_TIMEOUT_MS = 5_000;

    private final String roomId;
    private final String targetHost;
    private final int targetPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerConnector server;
    private volatile DatagramSocket socket;
    private volatile WebSocketClient lobby;
    private volatile String fingerprint;

    /** 이미 응답한 조인 알림 — 로비 control 메시지는 접속자 목록을 반복해서 내려준다. */
    private final Map<String, Long> handled = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quic-host-worker");
        t.setDaemon(true);
        return t;
    });

    public QuicHost(String roomId, String target) {
        this.roomId = roomId;
        int colon = target.lastIndexOf(':');
        this.targetHost = colon > 0 ? target.substring(0, colon) : target;
        this.targetPort = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 25565;
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;

        QuicCert.Identity id = QuicCert.generate();
        fingerprint = id.fingerprint();

        socket = new DatagramSocket(new InetSocketAddress(0)); // 포트는 OS가 고른다
        server = ServerConnector.builder()
                .withSocket(socket)
                .withKeyStore(id.keyStore(), QuicCert.ALIAS, QuicCert.PASSWORD)
                .withSupportedVersion(QuicConnection.QuicVersion.V1)
                .withLogger(new NullLogger())   // 기본값이 없어서 빼면 build()에서 NPE가 난다
                .build();
        server.registerApplicationProtocol(ALPN, new TunnelFactory());
        server.start();

        LOG.info("[quic-host] listening room={} port={} target={}:{}",
                roomId, socket.getLocalPort(), targetHost, targetPort);

        openLobby();
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        WebSocketClient l = lobby;
        if (l != null) l.close();
        ServerConnector s = server;
        if (s != null) s.close();
        DatagramSocket sock = socket;
        if (sock != null) sock.close();
        worker.shutdownNow();
        LOG.info("[quic-host] stopped room={}", roomId);
    }

    // ── 로비: 조인 감지 ──────────────────────────────────────────────────────

    private void openLobby() throws Exception {
        WebSocketClient ws = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "-q/h" + (int) (Math.random() * 9000 + 1000)) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
                LOG.info("[quic-host] lobby joined room={}", roomId);
            }
            @Override public void onMessage(String type, String json) {
                handleLobby(json);
            }
        };
        lobby = ws;
        ws.connect();
    }

    private void handleLobby(String json) {
        if (!VillasMsg.has(json, "control")) return;
        long now = System.currentTimeMillis();
        handled.values().removeIf(t -> now - t > 600_000);

        for (String[] peer : VillasMsg.peers(json)) {
            String name = peer[0], remote = peer[1];
            // WebRtcHost 와 같은 이름 규약: "j" + 강제 글자 + 16hex = 18자. 지금 붙어 있는(remote 있는) 것만.
            if (name == null || remote == null) continue;
            if (name.length() != 18 || !name.startsWith("j")) continue;
            if (handled.putIfAbsent(name, now) != null) continue;

            String sid = name.substring(2);
            // IP는 로그에 남기지 않는다 — 방장이 로그를 공유하면 접속자 IP가 박제된다. sid로 충분.
            LOG.info("[quic-host] join detected sid={}", sid);
            worker.execute(() -> sendHostInfo(sid));
        }
    }

    /** 페어 세션에 붙어 "주소|포트|지문"을 한 번 보내고 끊는다. */
    private void sendHostInfo(String sid) {
        WebSocketClient pair = null;
        try {
            String payload = localAddress() + "|" + socket.getLocalPort() + "|" + fingerprint;
            WebSocketClient ws = new WebSocketClient(
                    P2PConfig.SIGNALING_URL + "/" + roomId + "-q-" + sid + "/h" + sid) {
                @Override public void onConnected() {
                    send(VillasMsg.hello());
                    send(VillasMsg.description(MSG_TYPE, payload));
                }
                @Override public void onMessage(String type, String json) {}
            };
            pair = ws;
            ws.connect();
            Thread.sleep(1_000); // 전송이 나갈 시간만 준다 — 접속자는 먼저 세션에 들어가 기다린다
            LOG.info("[quic-host] host info sent sid={}", sid);
        } catch (Exception e) {
            LOG.warn("[quic-host] host info 전송 실패 sid={}: {}", sid, e.getMessage());
        } finally {
            if (pair != null) pair.close();
        }
    }

    /** 접속자에게 알려줄 주소 — 1단계는 같은 LAN 전제라 사설 IPv4를 그대로 쓴다. */
    private static String localAddress() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1"; // 같은 기기에서 테스트하는 경우
    }

    // ── 들어온 QUIC 스트림 → LAN 포트 ────────────────────────────────────────

    private final class TunnelFactory implements ApplicationProtocolConnectionFactory {
        @Override public ApplicationProtocolConnection createConnection(String protocol, QuicConnection conn) {
            // acceptPeerInitiatedStream 이 default 메서드라 함수형 인터페이스가 아니다 — 익명 클래스로.
            return new ApplicationProtocolConnection() {
                @Override public void acceptPeerInitiatedStream(QuicStream stream) {
                    worker.execute(() -> bridge(stream));
                }
            };
        }
    }

    private void bridge(QuicStream stream) {
        try {
            Socket tcp = new Socket();
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(targetHost, targetPort), DIAL_TIMEOUT_MS);
            QuicPump.wire(tcp, stream.getInputStream(), stream.getOutputStream(), "host");
        } catch (IOException e) {
            LOG.warn("[quic-host] target dial 실패 {}:{} — {}", targetHost, targetPort, e.getMessage());
            try { stream.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }
}
