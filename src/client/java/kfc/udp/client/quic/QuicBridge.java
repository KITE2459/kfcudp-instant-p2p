package kfc.udp.client.quic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * QUIC 전송 파사드 — {@code WebRtcBridge}와 같은 모양이라 호출부가 프리픽스만 보고 갈라진다.
 * <p>
 * <b>WebRTC와 병행해서 돈다</b>(시그널링 경로가 {@code -q}로 갈려 있다). 같은 방을
 * {@code webrtc.CODE}와 {@code quic.CODE} 양쪽으로 열어 비교할 수 있게 한 것이고,
 * QUIC이 충분히 검증되면 WebRTC 쪽을 지운다.
 */
public final class QuicBridge {

    private static final Logger LOG = LoggerFactory.getLogger("quic-bridge");

    /** WebRTC 쪽과 겹치지 않는 기본 포트 — 둘을 동시에 띄워 비교할 수 있어야 한다. */
    private static final int LOCAL_PORT = 25567;

    private static volatile QuicClient client;
    private static volatile QuicHost host;

    private QuicBridge() {}

    public static String parseRoomId(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        return trimmed.startsWith("quic.") ? trimmed.substring("quic.".length()).trim() : null;
    }

    /** @return 마크 클라이언트가 접속할 로컬 포트 */
    public static int start(String roomId) throws Exception {
        stop();
        int port = findFreePort();
        LOG.info("[QUIC] starting client room={} port={}", roomId, port);
        QuicClient c = new QuicClient(roomId, port);
        client = c;
        c.start();
        return port;
    }

    public static void stop() {
        QuicClient c = client;
        if (c != null) {
            c.close();
            client = null;
        }
    }

    public static void startHost(String roomId, String target) throws Exception {
        stopHost();
        LOG.info("[QUIC] starting host room={} target={}", roomId, target);
        QuicHost h = new QuicHost(roomId, target);
        host = h;
        h.start();
    }

    public static void stopHost() {
        QuicHost h = host;
        if (h != null) {
            h.close();
            host = null;
        }
    }

    public static boolean isHostActive() {
        return host != null;
    }

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(LOCAL_PORT)) { return LOCAL_PORT; }
        catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
            catch (IOException ex) { return LOCAL_PORT; }
        }
    }
}
