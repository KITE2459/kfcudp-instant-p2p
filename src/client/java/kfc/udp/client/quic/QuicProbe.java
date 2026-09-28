package kfc.udp.client.quic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 게임 없이 QUIC 터널만 돌리는 진단 하니스 — <b>혼자서 NAT 통과를 검증</b>하려고 만들었다.
 * <p>
 * {@link QuicHost}/{@link QuicClient}를 <b>그대로</b> 쓴다(복사본이 아니다). 그래서 여기서 통하면
 * 게임에서도 같은 코드가 통한다. 게임 밖에서 도는 건 두 클래스가 {@code P2PConfig} 대신
 * 시그널링·STUN 주소를 인자로 받기 때문이다 — P2PConfig 는 정적 초기화에서 FabricLoader 를
 * 건드려 게임 밖에서 로드가 실패한다.
 * <p>
 * <b>쓰는 법</b> — 공인 IP 가 있는 서버(예: 오라클 박스)에서 host 를 띄우고, NAT 뒤의 집 PC 에서
 * join 을 띄운다. 그러면 사람 둘이 없어도 실제 NAT 를 넘는 경로가 검증된다:
 * <pre>
 *   서버:  java -cp instant-p2p.jar kfc.udp.client.quic.QuicProbe host TESTROOM
 *   집PC:  java -cp instant-p2p.jar kfc.udp.client.quic.QuicProbe join TESTROOM
 * </pre>
 * host 는 echo 서버를 띄우고 거기로 터널을 잇는다. join 은 터널로 문자열을 보내고 그대로
 * 돌아오는지 확인한다 — 왕복이 되면 ICE·QUIC·지문 대조·파이프가 전부 동작한 것이다.
 * 로그의 {@code [ice] 경로 확정: 경로=...} 가 {@code srflx} 면 공인 주소로 통한 것이다.
 * <p>
 * 주소는 {@code -Dkfcudp.signaling=} / {@code -Dkfcudp.stun=} 으로 바꾼다(기본값은 배포 서버).
 */
public final class QuicProbe {

    private static final String DEFAULT_SIGNALING =
            System.getProperty("kfcudp.signaling", "wss://kite-private-cloud.kro.kr");
    private static final String DEFAULT_STUN =
            System.getProperty("kfcudp.stun", "stun:kite-private-cloud.kro.kr:3490");
    private static final String DEFAULT_TURN =
            System.getProperty("kfcudp.turn", "turn:kite-private-cloud.kro.kr:3490");
    // 계정은 jar 에 두지 않는다(P2PConfig.TURN_USERNAME 주석) — 중계를 시험할 땐 -D 로 넘긴다.
    private static final String TURN_USER = System.getProperty("kfcudp.turn.user");
    private static final String TURN_PASS = System.getProperty("kfcudp.turn.pass");
    /** 게임에서는 GUI 체크박스(P2PConfig.isRelayOnly)가 이 값을 정한다. 하니스는 설정 파일을
     * 읽지 않으므로 JVM 속성으로 받는다. */
    private static final boolean RELAY_ONLY = Boolean.getBoolean("kfcudp.ice.relayonly");

    private QuicProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("사용법: QuicProbe <host|join|closetest> <방코드> [대기초]");
            System.exit(1);
        }
        String mode = args[0];
        String room = args[1];
        System.out.println("signaling=" + DEFAULT_SIGNALING);
        System.out.println("stun=" + DEFAULT_STUN);
        System.out.println("turn=" + DEFAULT_TURN);

        // closetest: 방을 열어 두고 N초 뒤에 스스로 닫으며 그 시간을 잰다 — 월드를 닫을 때
        // 렌더 스레드가 멈추던 문제(kwik 의 종료 대기 30초)를 수치로 확인하려고 만들었다.
        if ("closetest".equals(mode)) {
            int waitSec = args.length > 2 ? Integer.parseInt(args[2]) : 20;
            int echoPort = startEchoServer();
            QuicHost host = new QuicHost(room, "127.0.0.1:" + echoPort, DEFAULT_SIGNALING, DEFAULT_STUN,
                    DEFAULT_TURN, TURN_USER, TURN_PASS,
                    new HostHooks() {
                        @Override public boolean relayOnlyNow() { return RELAY_ONLY; }
                        @Override public void registerTunnelPort(int p, String ip) {}
                        @Override public void unregisterTunnelPort(int p) {}
                        @Override public void registerConnectionType(String ip, boolean relay) {}
                        @Override public String onlinePlayerHashes(String r) { return ""; }
                    });
            host.start();
            System.out.println("[probe] host 대기 " + waitSec + "초 — 그 사이 join 을 붙이세요");
            Thread.sleep(waitSec * 1000L);
            long t0 = System.currentTimeMillis();
            host.close();
            long blocked = System.currentTimeMillis() - t0;
            System.out.println("[probe] close() 가 호출자를 막은 시간: " + blocked + "ms");
            Thread.sleep(6000); // 뒤에서 도는 정리 로그를 받아 본다
            System.out.println(blocked < 200 ? "=== 호출자 블로킹 없음 ===" : "=== 여전히 막는다 ===");
            System.exit(blocked < 200 ? 0 : 1);
        }

        if ("host".equals(mode)) {
            int echoPort = startEchoServer();
            System.out.println("[probe] echo 서버 포트 " + echoPort + " — 터널 대상으로 쓴다");
            QuicHost host = new QuicHost(room, "127.0.0.1:" + echoPort, DEFAULT_SIGNALING, DEFAULT_STUN,
                    DEFAULT_TURN, TURN_USER, TURN_PASS,
                    new HostHooks() {
                        @Override public boolean relayOnlyNow() { return RELAY_ONLY; }
                        @Override public void registerTunnelPort(int p, String ip) {}
                        @Override public void unregisterTunnelPort(int p) {}
                        @Override public void registerConnectionType(String ip, boolean relay) {}
                        @Override public String onlinePlayerHashes(String r) { return ""; }
                    });
            host.start();
            System.out.println("[probe] host 대기 중 (room=" + room + "). Ctrl+C 로 종료.");
            Thread.currentThread().join();
            return;
        }

        if (!"join".equals(mode) && !"soak".equals(mode)) {
            System.out.println("모드는 host / join / soak");
            System.exit(1);
        }

        int localPort = 25599;
        QuicClient client = new QuicClient(room, localPort, DEFAULT_SIGNALING, DEFAULT_STUN,
                DEFAULT_TURN, TURN_USER, TURN_PASS, RELAY_ONLY);
        client.start();

        // soak: 연결을 N초 붙잡고 50ms 마다 주고받으며 "echo 가 안 온 가장 긴 공백"을 잰다 — 게임 중 경로가
        // 바뀌어도(가짜 NAT 로 포트를 바꾸거나 끊음) 연결이 이어지는지, 몇 초 만에 되살아나는지 본다.
        if ("soak".equals(mode)) {
            int sec = args.length > 2 ? Integer.parseInt(args[2]) : 30;
            try (Socket s = new Socket("127.0.0.1", localPort)) {
                s.setTcpNoDelay(true);
                long[] last = {System.currentTimeMillis()}, maxGap = {0}, got = {0};
                Thread reader = new Thread(() -> {
                    try {
                        InputStream in = s.getInputStream();
                        byte[] b = new byte[8];
                        while (in.readNBytes(b, 0, 8) == 8) {
                            long now = System.currentTimeMillis();
                            if (got[0] > 0) maxGap[0] = Math.max(maxGap[0], now - last[0]);
                            last[0] = now;
                            got[0]++;
                        }
                    } catch (IOException ignored) {}
                }, "probe-soak-read");
                reader.setDaemon(true);
                reader.start();
                OutputStream out = s.getOutputStream();
                long end = System.currentTimeMillis() + sec * 1000L;
                for (long i = 0; System.currentTimeMillis() < end; i++) {
                    out.write(java.nio.ByteBuffer.allocate(8).putLong(i).array());
                    out.flush();
                    Thread.sleep(50);
                }
                Thread.sleep(3000);
                long silent = System.currentTimeMillis() - last[0];
                boolean ok = got[0] > 0 && silent < 3500;
                System.out.println();
                System.out.println("받은 echo " + got[0] + "개, 가장 긴 공백 " + maxGap[0] + "ms, 끝난 뒤 무응답 " + silent + "ms");
                System.out.println(ok ? "=== 연결 유지 ===" : "=== 끊김 ===");
                System.exit(ok ? 0 : 1);
            } finally {
                client.close();
            }
        }

        // 로컬 리스너에 붙는 순간 QuicClient 가 협상을 시작한다 — 마크가 하는 일과 같다.
        try (Socket s = new Socket("127.0.0.1", localPort)) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30_000);
            String sent = "instant-p2p-quic-probe";
            s.getOutputStream().write(sent.getBytes("UTF-8"));
            s.getOutputStream().flush();

            byte[] buf = new byte[sent.length()];
            int read = s.getInputStream().readNBytes(buf, 0, buf.length);
            String got = new String(buf, 0, read, "UTF-8");

            boolean ok = sent.equals(got);
            System.out.println();
            System.out.println("보낸 값 : " + sent);
            System.out.println("받은 값 : " + got);
            System.out.println(ok ? "=== 터널 왕복 성공 ===" : "=== 실패 ===");
            System.exit(ok ? 0 : 1);
        } finally {
            client.close();
        }
    }

    /** 받은 바이트를 그대로 돌려주는 최소 서버 — 터널이 양방향으로 흐르는지만 보면 된다. */
    private static int startEchoServer() throws IOException {
        ServerSocket ss = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    new Thread(() -> {
                        try (Socket sock = c; InputStream in = sock.getInputStream();
                             OutputStream out = sock.getOutputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
                        } catch (IOException ignored) {}
                    }, "probe-echo-conn").start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "probe-echo");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }
}
