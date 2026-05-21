package kfc.udp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebRtcBridge {

    public static final Logger LOG = LoggerFactory.getLogger("webrtc-bridge");

    private static final String SIGNALING_URL = "ws://193.122.114.163:8765";
    private static final int LOCAL_PORT = 25566;

    private static volatile Process coreProcess;
    private static volatile Process hostProcess;
    private static volatile Process quicProcess;
    // 핑 후 접속 시 roomId 전달용 (originalAddress → roomId)
    private static final java.util.concurrent.ConcurrentHashMap<String, String> pendingRoomIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static int activeLocalPort = LOCAL_PORT;

    private WebRtcBridge() {}

    public static String getAndClearPendingRoomId(String address) {
        if (address == null) return null;
        // webrtc. 없이 들어온 address로 저장된 roomId 찾기
        for (String key : pendingRoomIds.keySet()) {
            String stripped = key.startsWith("webrtc.") ? key.substring("webrtc.".length()) : key;
            if (stripped.equals(address)) {
                return pendingRoomIds.remove(key);
            }
        }
        return null;
    }

    public static String parseQuicAddress(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        if (trimmed.startsWith("quic.")) {
            return trimmed.substring("quic.".length()).trim();
        }
        return null;
    }

    public static int startQuic(String serverAddr) throws IOException, InterruptedException {
        stopQuic();
        killZombies(getQuicBinaryName());

        activeLocalPort = findFreePort();

        Path corePath = extractQuicBinary();

        List<String> command = new ArrayList<>();
        command.add(corePath.toAbsolutePath().toString());
        command.add("--join");
        command.add("--server");
        command.add(serverAddr);
        command.add("--listen");
        command.add("127.0.0.1:" + activeLocalPort);

        LOG.info("[QUIC] Starting Core: server={} listen=127.0.0.1:{}", serverAddr, activeLocalPort);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        quicProcess = proc;

        CountDownLatch readyLatch = new CountDownLatch(1);

        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    LOG.info("[QuicCore] {}", line);
                    if (line.contains("WEBRTC_READY")) {
                        readyLatch.countDown();
                    }
                }
            } catch (IOException ignored) {}
        }, "quic-core-log");
        logThread.setDaemon(true);
        logThread.start();

        boolean ready = readyLatch.await(30, TimeUnit.SECONDS);
        if (!ready) {
            LOG.warn("[QUIC] Timed out waiting for READY");
        }

        return activeLocalPort;
    }

    public static void stopQuic() {
        Process proc = quicProcess;
        if (proc != null && proc.isAlive()) {
            LOG.info("[QUIC] Stopping Core");
            proc.destroy();
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    proc.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            quicProcess = null;
            LOG.info("[QUIC] Core stopped");
        }
    }

    public static String parseRoomId(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        if (trimmed.startsWith("webrtc.")) {
            return trimmed.substring("webrtc.".length()).trim();
        }
        return null;
    }

    public static int start(String roomId) throws IOException, InterruptedException {
        stop();
        // hostProcess가 실행 중이면 killZombies 스킵 (Host Core를 죽이면 안 됨)
        if (hostProcess == null || !hostProcess.isAlive()) {
            killZombies(getBinaryName());
        }

        activeLocalPort = findFreePort();

        Path corePath = extractCoreBinary();

        List<String> command = new ArrayList<>();
        command.add(corePath.toAbsolutePath().toString());
        command.add("--join");
        command.add("--room");
        command.add(roomId);
        command.add("--signaling");
        command.add(SIGNALING_URL);
        command.add("--listen");
        command.add("127.0.0.1:" + activeLocalPort);

        LOG.info("[WebRTC] Starting Core: room={} listen=127.0.0.1:{}", roomId, activeLocalPort);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        coreProcess = proc;

        CountDownLatch readyLatch = new CountDownLatch(1);

        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    LOG.info("[Core] {}", line);
                    if (line.contains("WEBRTC_READY")) {
                        readyLatch.countDown();
                    }
                }
            } catch (IOException ignored) {}
        }, "webrtc-core-log");
        logThread.setDaemon(true);
        logThread.start();

        boolean ready = readyLatch.await(30, TimeUnit.SECONDS);
        if (!ready) {
            LOG.warn("[WebRTC] Timed out waiting for WEBRTC_READY");
        }

        return activeLocalPort;
    }

    /**
     * Core를 host 모드로 실행
     */
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
        command.add(SIGNALING_URL);
        command.add("--no-proxy"); // 싱글플레이 LAN 서버는 PROXY protocol 미지원

        LOG.info("[WebRTC] Starting Host Core: room={} target={}", roomId, target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        hostProcess = proc;

        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    LOG.info("[HostCore] {}", line);
                }
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
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            hostProcess = null;
        }
    }

    public static void stop() {
        Process proc = coreProcess;
        if (proc != null && proc.isAlive()) {
            LOG.info("[WebRTC] Stopping Core");
            proc.destroy();
            try {
                // 최대 3초 대기
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    proc.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            coreProcess = null;
            LOG.info("[WebRTC] Core stopped");
        }
    }

    private static Path extractQuicBinary() throws IOException {
        String binaryName = getQuicBinaryName();
        Path dataDir = getDataDir();
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(binaryName);

        // 파일이 이미 존재하면 복사 스킵 (실행 중인 프로세스가 잠글 수 있음)
        if (!Files.exists(target)) {
            try (InputStream in = WebRtcBridge.class.getResourceAsStream("/openfriend-quic/" + binaryName)) {
                if (in == null) {
                    throw new IOException("QUIC binary not found in resources: " + binaryName);
                }
                Files.copy(in, target);
            }
        }

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            target.toFile().setExecutable(true);
        }

        LOG.info("[QUIC] Core binary ready: {}", target);
        return target;
    }

    private static String getQuicBinaryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "openfriend-quic-windows-amd64.exe";
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm")
                    ? "openfriend-quic-darwin-arm64"
                    : "openfriend-quic-darwin-amd64";
        }
        return arch.contains("aarch64") || arch.contains("arm")
                ? "openfriend-quic-linux-arm64"
                : "openfriend-quic-linux-amd64";
    }

    private static Path extractHostBinary() throws IOException {
        String binaryName = getHostBinaryName();
        Path dataDir = getDataDir();
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(binaryName);

        if (!Files.exists(target)) {
            try (InputStream in = WebRtcBridge.class.getResourceAsStream("/openfriend/" + binaryName)) {
                if (in == null) {
                    // host 바이너리가 없으면 join 바이너리로 폴백
                    return extractCoreBinary();
                }
                Files.copy(in, target);
            }
        }

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            target.toFile().setExecutable(true);
        }

        LOG.info("[WebRTC] Host binary ready: {}", target);
        return target;
    }

    private static Path extractCoreBinary() throws IOException {
        String binaryName = getBinaryName();
        Path dataDir = getDataDir();
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(binaryName);

        if (!Files.exists(target)) {
            try (InputStream in = WebRtcBridge.class.getResourceAsStream("/openfriend/" + binaryName)) {
                if (in == null) {
                    throw new IOException("Core binary not found in resources: " + binaryName);
                }
                Files.copy(in, target);
            }
        }

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            target.toFile().setExecutable(true);
        }

        LOG.info("[WebRTC] Core binary ready: {}", target);
        return target;
    }

    private static String getBinaryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "openfriend-windows-amd64.exe";
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm")
                    ? "openfriend-darwin-arm64"
                    : "openfriend-darwin-amd64";
        }
        return arch.contains("aarch64") || arch.contains("arm")
                ? "openfriend-linux-arm64"
                : "openfriend-linux-amd64";
    }

    private static String getHostBinaryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "openfriend-host-windows-amd64.exe";
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm")
                    ? "openfriend-host-darwin-arm64"
                    : "openfriend-host-darwin-amd64";
        }
        return arch.contains("aarch64") || arch.contains("arm")
                ? "openfriend-host-linux-arm64"
                : "openfriend-host-linux-amd64";
    }

    private static Path getDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty())
                return Paths.get(appData, "kfcudp");
            return Paths.get(home, "AppData", "Roaming", "kfcudp");
        }
        if (os.contains("mac") || os.contains("darwin"))
            return Paths.get(home, "Library", "Application Support", "kfcudp");
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) return Paths.get(xdg, "kfcudp");
        return Paths.get(home, ".local", "share", "kfcudp");
    }

    private static void killZombies(String binaryName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/IM", binaryName});
            } else {
                Runtime.getRuntime().exec(new String[]{"pkill", "-f", binaryName});
            }
            Thread.sleep(500);
        } catch (Exception ignored) {}
    }

    public static void cleanup() {
        Path dataDir = getDataDir();
        String[] binaries = {
                getBinaryName(),
                getQuicBinaryName()
        };
        for (String name : binaries) {
            try {
                Files.deleteIfExists(dataDir.resolve(name));
                LOG.info("[kfcudp] Deleted binary: {}", name);
            } catch (IOException e) {
                LOG.warn("[kfcudp] Failed to delete binary: {}", name);
            }
        }
    }

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(WebRtcBridge.LOCAL_PORT)) {
            return WebRtcBridge.LOCAL_PORT;
        } catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) {
                return s.getLocalPort();
            } catch (IOException ex) {
                return WebRtcBridge.LOCAL_PORT;
            }
        }
    }
}