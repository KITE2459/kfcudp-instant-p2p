package kfc.udp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * WebRTC/KCP 브리지 관리.
 * <p>
 * webrtc.ROOM → WebRtcClient (Java 네이티브, dev.onvoid.webrtc)
 * kcp.ADDR    → KCP 네이티브 (ClientConnectionMixin)
 * startHost() → openfriend Go 바이너리 (서버 사이드)
 */
public class WebRtcBridge {

    public static final Logger LOG = LoggerFactory.getLogger("webrtc-bridge");

    private static final int LOCAL_PORT = 25566;

    // webrtc. 네이티브 클라이언트
    private static volatile WebRtcClient webRtcClient;

    // 서버 사이드 host 프로세스 (Go 바이너리)
    private static volatile Process hostProcess;

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
     * openfriend 바이너리 없이 dev.onvoid.webrtc로 직접 연결.
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

    // ── Host (서버 사이드 — Go 바이너리 유지) ─────────────────────────────────

    public static void startHost(String roomId, String target) throws IOException {
        stopHost();
        killZombies(getHostBinaryName());

        Path corePath = extractHostBinary();
        List<String> command = new ArrayList<>();
        command.add(corePath.toAbsolutePath().toString());
        command.add("--room");
        command.add(roomId);
        command.add("--target");
        command.add(target);
        command.add("--signaling");
        command.add("ws://193.122.114.163:8765");
        command.add("--no-proxy");

        LOG.info("[WebRTC] Starting Host Core: room={} target={}", roomId, target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().put("GOGC", "off");
        Process proc = pb.start();
        hostProcess = proc;

        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) LOG.info("[HostCore] {}", line);
            } catch (IOException ignored) {}
        }, "webrtc-host-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    public static void stopHost() {
        Process proc = hostProcess;
        if (proc != null && proc.isAlive()) {
            LOG.info("[WebRTC] Stopping Host Core");
            proc.destroy();
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly();
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            hostProcess = null;
        }
    }

    // ── 바이너리 추출 (Host 전용) ──────────────────────────────────────────────

    private static Path extractHostBinary() throws IOException {
        String name   = getHostBinaryName();
        Path   dir    = getDataDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        if (!Files.exists(target)) {
            try (InputStream in = WebRtcBridge.class.getResourceAsStream("/openfriend/" + name)) {
                if (in == null) return extractCoreBinary(); // fallback
                Files.copy(in, target);
            }
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
            target.toFile().setExecutable(true);
        return target;
    }

    private static Path extractCoreBinary() throws IOException {
        String name   = getBinaryName();
        Path   dir    = getDataDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        if (!Files.exists(target)) {
            try (InputStream in = WebRtcBridge.class.getResourceAsStream("/openfriend/" + name)) {
                if (in == null) throw new IOException("Binary not found: " + name);
                Files.copy(in, target);
            }
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
            target.toFile().setExecutable(true);
        return target;
    }

    private static String getHostBinaryName() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win"))                        return "openfriend-host-windows-amd64.exe";
        if (os.contains("mac") || os.contains("darwin")) {
            return (arch.contains("aarch64") || arch.contains("arm"))
                    ? "openfriend-host-darwin-arm64" : "openfriend-host-darwin-amd64";
        }
        return (arch.contains("aarch64") || arch.contains("arm"))
                ? "openfriend-host-linux-arm64" : "openfriend-host-linux-amd64";
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private static void killZombies(String binaryName) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/IM", binaryName});
            else                    Runtime.getRuntime().exec(new String[]{"pkill", "-f", binaryName});
            Thread.sleep(500);
        } catch (Exception ignored) {}
    }

    public static void cleanup() {
        Path dir = getDataDir();
        try { Files.deleteIfExists(dir.resolve(getBinaryName())); } catch (IOException ignored) {}
    }


    private static String getBinaryName() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win"))                        return "openfriend-windows-amd64.exe";
        if (os.contains("mac") || os.contains("darwin")) {
            return (arch.contains("aarch64") || arch.contains("arm"))
                    ? "openfriend-darwin-arm64" : "openfriend-darwin-amd64";
        }
        return (arch.contains("aarch64") || arch.contains("arm"))
                ? "openfriend-linux-arm64" : "openfriend-linux-amd64";
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