package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;

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

    // 공개 방 목록 announcer — 방 열 때 "공개 허용" 체크돼 있으면 같이 시작,
    // 방 닫힐 때 같이 멈춘다(publishPublicRoom/unpublishPublicRoom).
    private static final PublicRoomAnnouncer publicRoomAnnouncer = new PublicRoomAnnouncer();

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

    /**
     * WebRtcClient 자신이 (서버 disconnect 등으로) 스스로 닫힐 때 호출 — token이 여전히
     * 현재 활성 클라이언트일 때만 참조를 지운다({@link #stopHostIfCurrent} 참고).
     * <p>
     * 이게 없으면(예전 상태) webrtc 방에 접속했다가 나간 뒤 이 필드가 그대로 남아있어서,
     * 그 뒤에 여는 관계없는 싱글플레이 월드에서도 {@link #getActiveConnectionUsesRelay()}가
     * 옛 연결의 직결/중계 값을 그대로 돌려줘 "싱글인데 직결/중계 연결 메시지가 뜬다"는
     * 버그가 있었다 — 예전엔 JVM 종료 시점에만 stop()이 불렸다.
     */
    public static void clearClientIfCurrent(WebRtcClient token) {
        if (token != null && token == webRtcClient) {
            webRtcClient = null;
        }
    }

    /** 현재 진행 중인 webrtc 조인 세션의 연결 방식. null = webrtc 세션이 없거나 아직 안 정해짐. */
    public static Boolean getActiveConnectionUsesRelay() {
        WebRtcClient client = webRtcClient;
        return client != null ? client.usesRelay() : null;
    }

    // ── Host (Java 네이티브 — WebRtcHost) ─────────────────────────────────────

    public static void startHost(String roomId, String target) {
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
        unpublishPublicRoom();
    }

    /** 방을 공개 목록에 올리거나 이미 올라와 있으면 정보를 갱신한다 — PublicRoomAnnouncer 클래스
     * 주석 참고(방 코드·채널이 그대로면 재접속 없이 메시지만 보낸다). hostUuid는 개인 차단(=밴)
     * 기능용(P2PBanManager 클래스 주석 참고). */
    public static void publishPublicRoom(String roomCode, String title, String hostNickname, String hostUuid,
                                          int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.publish(roomCode, title, hostNickname, hostUuid, currentPlayers, maxPlayers);
    }

    public static void unpublishPublicRoom() {
        publicRoomAnnouncer.stop();
    }

    /** 밴(=차단) 목록이 바뀌었을 때 P2PBanManager가 호출 — 지금 공개된 방이 있으면
     * 즉시 새 밴 목록을 실어 재공지한다(PublicRoomAnnouncer.republishNow 참고). */
    public static void republishPublicRoomIfActive() {
        publicRoomAnnouncer.republishNow();
    }

    /** 공개 방 인원(현재/최대)이 바뀔 때마다 호출 — 재접속 없이 메시지만 보낸다(PublicRoomAnnouncer 참고). */
    public static void updatePublicRoomPlayerCount(int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.updatePlayerCount(currentPlayers, maxPlayers);
    }

    /** 지금 활성화된 호스트 인스턴스를 식별하는 토큰(단순 참조). */
    public static Object currentHostToken() {
        return webRtcHost;
    }

    /**
     * token이 여전히 현재 활성 호스트일 때만 중지한다. 방을 연달아 열면
     * 이전 방을 닫으려던 지연 종료 스레드가 그 사이 새로 열린 방을
     * 대신 죽이는 걸 막기 위한 것 — {@link #startHost}가 이미 이전 인스턴스를
     * 동기적으로 닫으므로, 지연 종료 시점엔 그게 여전히 활성 호스트일 때만 유효하다.
     */
    public static void stopHostIfCurrent(Object token) {
        if (token != null && token == webRtcHost) {
            stopHost();
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(LOCAL_PORT)) { return LOCAL_PORT; }
        catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
            catch (IOException ex) { return LOCAL_PORT; }
        }
    }
}