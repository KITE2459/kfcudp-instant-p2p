package kfc.udp.client.quic;

import kfc.udp.client.webrtc.P2PConfig;
import kfc.udp.client.webrtc.VillasMsg;
import kfc.udp.client.webrtc.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.NullLogger;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QUIC 접속자 — 로컬 TCP 리스너를 열고, 마크가 붙으면 QUIC으로 방장에게 잇는다.
 * <p>
 * 흐름: 로컬 리스너 오픈(마크에 포트 반환) → 마크가 접속 → 그 때 시그널링으로 방장 주소·지문을
 * 받아 QUIC 연결 → 지문 대조 → 스트림 하나 열어 파이프.
 * <p>
 * <b>지문 대조 시점이 중요하다</b> — kwik의 내장 검증은 인증서 이름을 접속 주소와 맞춰 보는데,
 * ICE로 고른 주소는 미리 알 수 없어 인증서에 넣을 수 없다. 그래서 내장 검증을 끄고
 * <b>핸드셰이크 직후·데이터 전송 전에</b> 지문을 직접 대조한다. TLS 1.3 핸드셰이크가 끝난 시점에
 * 상대는 이미 개인키 소유를 증명했으므로, 그 인증서의 지문이 맞으면 상대가 확정된다.
 * 대가로 연결마다 kwik이 stdout에 INSECURE 경고를 한 줄 찍는다(후보 IP를 전부 SAN에 넣으면
 * 없앨 수 있으나 ICE가 붙은 뒤의 일이다).
 */
public final class QuicClient {

    private static final Logger LOG = LoggerFactory.getLogger("quic-client");

    private static final long SIGNAL_TIMEOUT_MS = 10_000;

    private final String roomId;
    private final int localPort;
    private final String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket listener;
    private volatile QuicClientConnection quic;

    public QuicClient(String roomId, int localPort) {
        this.roomId = roomId;
        this.localPort = localPort;
    }

    /** 리스너만 열고 즉시 반환 — 실제 협상은 마크가 접속한 뒤에 한다. */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress("127.0.0.1", localPort));
        listener = ss;
        LOG.info("[quic-client] local listener 127.0.0.1:{} room={}", localPort, roomId);

        Thread t = new Thread(this::acceptLoop, "quic-client-accept");
        t.setDaemon(true);
        t.start();
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        ServerSocket ss = listener;
        if (ss != null) try { ss.close(); } catch (IOException ignored) {}
        QuicClientConnection q = quic;
        if (q != null) q.close();
        LOG.info("[quic-client] stopped room={}", roomId);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket tcp = listener.accept();
                tcp.setTcpNoDelay(true);
                new Thread(() -> serve(tcp), "quic-client-conn").start();
            } catch (IOException e) {
                if (running.get()) LOG.debug("[quic-client] accept 종료: {}", e.getMessage());
                return;
            }
        }
    }

    private void serve(Socket tcp) {
        try {
            QuicClientConnection conn = quic;
            if (conn == null || !conn.isConnected()) {
                conn = connectToHost();
                quic = conn;
            }
            QuicStream stream = conn.createStream(true);
            QuicPump.wire(tcp, stream.getInputStream(), stream.getOutputStream(), "client");
        } catch (Exception e) {
            LOG.warn("[quic-client] 연결 실패: {}", e.getMessage());
            try { tcp.close(); } catch (IOException ignored) {}
        }
    }

    // ── 시그널링으로 방장 주소·지문 받기 ─────────────────────────────────────

    private QuicClientConnection connectToHost() throws Exception {
        String[] info = fetchHostInfo();
        String host = info[0];
        int port = Integer.parseInt(info[1]);
        String expectedFp = info[2];

        // 우리가 직접 bind 한 소켓 — 3단계에서 여기에 ICE로 뚫은 소켓을 그대로 넘기게 된다.
        DatagramSocket sock = new DatagramSocket(new InetSocketAddress(0));

        QuicClientConnection conn = QuicClientConnection.newBuilder()
                .host(host).port(port)
                .applicationProtocol(QuicHost.ALPN)
                .socketFactory(dest -> sock)
                .noServerCertificateCheck()     // 대신 아래에서 지문을 직접 대조한다(클래스 주석 참고)
                .connectTimeout(Duration.ofSeconds(10))
                .logger(new NullLogger())       // 기본값이 없어서 빼면 build()에서 NPE가 난다
                .build();
        conn.connect();

        // ── 지문 대조: 여기까지 데이터는 한 바이트도 안 보냈다.
        List<X509Certificate> chain = conn.getServerCertificateChain();
        String actual = chain.isEmpty() ? "" : QuicCert.fingerprint(chain.get(0));
        if (!expectedFp.equals(actual)) {
            conn.close();
            throw new IOException("인증서 지문 불일치 — 중간자 의심");
        }
        LOG.info("[quic-client] connected room={} (지문 확인됨)", roomId);
        return conn;
    }

    /** 페어 세션에 먼저 들어가 기다린 뒤 로비에 알린다 — 순서가 바뀌면 방장의 응답을 놓친다. */
    private String[] fetchHostInfo() throws Exception {
        CompletableFuture<String> payload = new CompletableFuture<>();
        WebSocketClient pair = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "-q-" + sid + "/j" + sid) {
            @Override public void onConnected() { send(VillasMsg.hello()); }
            @Override public void onMessage(String type, String json) {
                String desc = VillasMsg.object(json, "description");
                if (desc != null && QuicHost.MSG_TYPE.equals(VillasMsg.field(desc, "type"))) {
                    payload.complete(VillasMsg.field(desc, "spd"));
                }
            }
        };
        // 이름 규약은 WebRtcHost 와 공유한다: "j" + 강제 글자 + 16hex = 18자.
        // 1단계는 중계가 없으니 'd'(직결) 고정.
        WebSocketClient lobby = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "-q/jd" + sid) {
            @Override public void onConnected() { send(VillasMsg.hello()); }
            @Override public void onMessage(String type, String json) {}
        };
        try {
            pair.connect();
            lobby.connect();
            String raw = payload.get(SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String[] parts = raw.split("\\|");
            if (parts.length != 3) throw new IOException("방장 정보 형식 오류");
            return parts;
        } finally {
            lobby.close();
            pair.close();
        }
    }
}
