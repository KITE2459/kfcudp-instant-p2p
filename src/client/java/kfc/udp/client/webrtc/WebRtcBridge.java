package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * WebRTC/KCP 브리지 관리. (외부 바이너리 의존 없음 — 전부 Java 네이티브)
 * <p>
 * webrtc.ROOM → WebRtcClient (dev.onvoid.webrtc)
 * kcp.ADDR    → KCP 네이티브 (ClientConnectionMixin)
 * startHost() → WebRtcHost (dev.onvoid.webrtc)
 */
public class WebRtcBridge {

    public static final Logger LOG = LoggerFactory.getLogger("webrtc-bridge");

    private static final int LOCAL_PORT = 25566;

    // webrtc. 네이티브 클라이언트
    private static volatile WebRtcClient webRtcClient;

    // 네이티브 호스트
    private static volatile WebRtcHost webRtcHost;

    // 핑 후 접속 시 roomId 전달용
    private static volatile int activeLocalPort = LOCAL_PORT;

    private WebRtcBridge() {}

    // ── 주소 파싱 ──────────────────────────────────────────────────────────────

    public static String parseRoomId(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        return trimmed.startsWith("webrtc.") ? trimmed.substring("webrtc.".length()).trim() : null;
    }

    public static String parseKcpAddress(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        return trimmed.startsWith("kcp.") ? trimmed.substring("kcp.".length()).trim() : null;
    }

    // ── WebRTC 클라이언트 (Java 네이티브) ──────────────────────────────────────

    /**
     * WebRTC P2P 연결 시작.
     *
     * @return 로컬 포트 (MC 클라이언트가 연결할 포트)
     */
    public static int start(String roomId) throws Exception {
        stop();

        activeLocalPort = findFreePort();
        LOG.info("[WebRTC] Starting native WebRTC, room={} port={}", roomId, activeLocalPort);

        WebRtcClient client = new WebRtcClient(roomId, activeLocalPort);
        webRtcClient = client;

        // start()는 시그널링 연결 + TCP 서버 오픈 후 즉시 반환
        // MC 클라이언트 접속 후 백그라운드에서 WebRTC 협상 진행
        client.start();

        return activeLocalPort;
    }

    public static void stop() {
        WebRtcClient client = webRtcClient;
        if (client != null) {
            LOG.info("[WebRTC] Stopping native client");
            client.close();
            webRtcClient = null;
        }
    }

    // ── Host (Java 네이티브 — WebRtcHost) ─────────────────────────────────────

    public static void startHost(String roomId, String target) throws IOException {
        stopHost();

        LOG.info("[WebRTC] Starting native host: room={} target={}", roomId, target);
        WebRtcHost host = new WebRtcHost(roomId, target);
        webRtcHost = host;
        host.start();
    }

    public static void stopHost() {
        WebRtcHost host = webRtcHost;
        if (host != null) {
            LOG.info("[WebRTC] Stopping native host");
            host.close();
            webRtcHost = null;
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    /**
     * 과거 버전이 데이터 폴더에 추출해 둔 openfriend 바이너리 잔재 삭제.
     * (현재 버전은 외부 바이너리를 일절 사용하지 않음)
     */
    public static void cleanup() {
        Path dir = getDataDir();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith("openfriend"))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static Path getDataDir() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData != null && !appData.isEmpty())
                    ? Path.of(appData, "kfcudp") : Path.of(home, "AppData", "Roaming", "kfcudp");
        }
        if (os.contains("mac") || os.contains("darwin"))
            return Path.of(home, "Library", "Application Support", "kfcudp");
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isEmpty()) ? Path.of(xdg, "kfcudp") : Path.of(home, ".local", "share", "kfcudp");
    }

    // startProtocol / stopProtocol — KCP는 이제 Java 네이티브이므로 불필요
    // ConnectScreenMixin이 KcpAddressRegistry를 통해 직접 처리
    public static void stopProtocol() {}

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(LOCAL_PORT)) { return LOCAL_PORT; }
        catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
            catch (IOException ex) { return LOCAL_PORT; }
        }
    }
}