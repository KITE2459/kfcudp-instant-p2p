package kfc.udp.client.quic;

import kfc.udp.client.signaling.MojangAuth;
import kfc.udp.client.signaling.P2PBanManager;
import kfc.udp.client.signaling.P2PConfig;
import kfc.udp.client.signaling.PublicRoomAnnouncer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * P2P 전송 파사드 — 주소 프리픽스({@code quic.} / {@code kcp.})를 보고 갈라지는 지점.
 * <p>
 * 예전 {@code WebRtcBridge}를 대체한다. WebRTC(libwebrtc)는 QUIC이 검증된 뒤 통째로 지웠고,
 * 그때 이 클래스가 겸하고 있던 <b>공개 방 목록</b>과 <b>kcp 주소 파싱</b>도 같이 넘어왔다 —
 * 둘 다 전송 방식과 무관한 기능이라 남아야 했다.
 * <p>
 * 이름이 {@code Quic}으로 시작하는데 공개 방 목록까지 들고 있는 건 어색하지만, 호출부 30여 곳을
 * 다시 고치는 값어치는 없다고 보고 그대로 뒀다.
 */
public final class QuicBridge {

    public static final Logger LOG = LoggerFactory.getLogger("quic-bridge");

    /** 마크가 붙을 로컬 루프백 포트. 이미 쓰이고 있으면 findFreePort 가 임의 포트로 넘어간다. */
    private static final int LOCAL_PORT = 25566;

    private static volatile QuicClient client;
    private static volatile QuicHost host;

    /** 공개 방 목록 announcer — 방 열 때 "공개 허용"이면 같이 시작하고 닫을 때 같이 멈춘다. */
    private static final PublicRoomAnnouncer publicRoomAnnouncer = new PublicRoomAnnouncer();

    /** QuicHost 가 게임에 기대는 부분 — QuicHost 를 마인크래프트에서 떼어 두려고 여기서 잇는다. */
    private static final HostHooks GAME_HOOKS = new HostHooks() {
        @Override public boolean relayOnlyNow() { return P2PConfig.isRelayOnly(); }
        @Override public String authToken() { return MojangAuth.ensureToken(); }
        @Override public void registerTunnelPort(int localPort, String realIp) {
            P2PBanManager.registerTunnelPort(localPort, realIp);
        }
        @Override public void unregisterTunnelPort(int localPort) {
            P2PBanManager.unregisterTunnelPort(localPort);
        }
        @Override public void registerConnectionType(String realIp, boolean usesRelay) {
            P2PBanManager.registerConnectionType(realIp, usesRelay);
        }
        @Override public String onlinePlayerHashes(String roomId) {
            return P2PBanManager.encodeOnlinePlayerHashes(roomId);
        }
    };

    private QuicBridge() {}

    /**
     * 초대 코드로 쓸 수 있는 글자 — {@code KfcudpClient.CODE_CHARS} 가 대문자·숫자만 쓰므로
     * 그 범위로만 받는다. 길이는 넉넉히 둔다(코드 규칙이 바뀌어도 여기서 막히지 않게).
     */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Z0-9]{4,32}");

    /**
     * {@code quic.<코드>} 에서 방 코드를 뽑는다. 코드가 아니면 <b>null</b> — 그러면 바닐라가
     * 평소처럼 주소로 취급해 자기 오류를 띄운다.
     * <p>
     * 검사를 여기서 하는 이유 — 코드는 시그널링 WS 경로에 그대로 들어간다. 예전엔 걸러지지 않아
     * 공백이 섞인 값("I go")이 들어오면 {@code Illegal character in path} 예외가 접속 실패 사유로
     * 그대로 노출됐다. 진입점이 여럿이라(초대 코드 화면·방 목록·직접 연결) 소비하는 이 한 곳에서
     * 막는 게 확실하다.
     * <p>
     * 소문자는 대문자로 올려 준다 — 코드는 대문자로만 만들어지므로 붙여넣기 실수로 실패할 이유가 없다.
     */
    public static String parseRoomId(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        if (!trimmed.startsWith("quic.")) return null;
        String code = trimmed.substring("quic.".length()).trim()
                .toUpperCase(java.util.Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            LOG.warn("[QUIC] 초대 코드 형식이 아니다 — 일반 주소로 넘긴다 (길이 {})", code.length());
            return null;
        }
        return code;
    }

    /** @return 마크 클라이언트가 접속할 로컬 포트 */
    public static int start(String roomId) throws Exception {
        stop();
        int port = findFreePort();
        LOG.debug("[QUIC] starting client port={}", port);
        String[] turn = MojangAuth.turnCredentials(); // 보통 미리 받아 둔 값이라 바로 온다
        QuicClient c = new QuicClient(roomId, port, P2PConfig.SIGNALING_URL, P2PConfig.STUN_URL,
                turn != null ? P2PConfig.TURN_URL : null, turn != null ? turn[0] : null, turn != null ? turn[1] : null,
                P2PConfig.isRelayOnly());   // 「중계 통신 강제」 체크박스 값
        c.authToken = MojangAuth.tokenOrNull(); // 위 turnCredentials 가 토큰을 이미 받아 둔다
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
        LOG.debug("[QUIC] starting host target={}", target);
        String[] turn = MojangAuth.turnCredentials();
        // TURN 주소는 계정이 없어도 넘긴다 — 들어온 연결이 중계를 거쳤는지 주소로 판정하는 데 쓴다
        // (접속자 쪽 allocation 으로 오면 방장에게 계정이 없어도 출처는 coturn 이다). 계정이 없으면 enableTurn 이 건너뛴다.
        QuicHost h = new QuicHost(roomId, target, P2PConfig.SIGNALING_URL, P2PConfig.STUN_URL,
                P2PConfig.TURN_URL, turn != null ? turn[0] : null, turn != null ? turn[1] : null,
                GAME_HOOKS);   // 「중계 통신 강제」는 접속자마다 GAME_HOOKS.relayOnlyNow() 로 읽는다
        host = h;
        h.start();
    }

    public static void stopHost() {
        QuicHost h = host;
        if (h != null) {
            h.close();
            host = null;
        }
        unpublishPublicRoom();
    }

    public static boolean isHostActive() {
        return host != null;
    }

    /** kcp. 주소 — ClientConnectionMixin 이 소비하는 한 번짜리 플래그를 세우기 전에 쓴다. */
    public static String parseKcpAddress(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        return trimmed.startsWith("kcp.") ? trimmed.substring("kcp.".length()).trim() : null;
    }

    /** 지금 진행 중인 quic 조인 세션의 연결 방식. null = quic 세션이 없거나 아직 안 붙음.
     * 호출부는 {@code != null}을 "우리 방의 접속자 세션인지" 판별에도 쓴다(KfcudpClient). */
    public static Boolean getActiveConnectionUsesRelay() {
        QuicClient c = client;
        return c != null ? c.usesRelay() : null;
    }

    /** 지금 활성화된 호스트 인스턴스를 식별하는 토큰(단순 참조). */
    public static Object currentHostToken() {
        return host;
    }

    /**
     * token이 여전히 현재 활성 호스트일 때만 중지한다. 방을 연달아 열면 이전 방을 닫으려던
     * 지연 종료 스레드가 그 사이 새로 열린 방을 대신 죽이는 걸 막기 위한 것이다.
     */
    public static void stopHostIfCurrent(Object token) {
        if (token != null && token == host) stopHost();
    }

    // ── 공개 방 목록 (전송 방식과 무관) ───────────────────────────────────────

    /** 방을 공개 목록에 올리거나 이미 올라와 있으면 정보를 갱신한다 — 방 코드·채널이 그대로면
     * 재접속 없이 메시지만 보낸다(PublicRoomAnnouncer 클래스 주석). */
    public static void publishPublicRoom(String roomCode, String title, String hostNickname, String hostUuid,
                                          int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.publish(roomCode, title, hostNickname, hostUuid, currentPlayers, maxPlayers);
    }

    public static void unpublishPublicRoom() {
        publicRoomAnnouncer.stop();
    }

    /** 밴(=차단) 목록이 바뀌면 P2PBanManager가 부른다 — 공개된 방이 있으면 즉시 재공지한다. */
    public static void republishPublicRoomIfActive() {
        publicRoomAnnouncer.republishNow();
    }

    /** 공개 방 인원(현재/최대)이 바뀔 때마다 — 재접속 없이 메시지만 보낸다. */
    public static void updatePublicRoomPlayerCount(int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.updatePlayerCount(currentPlayers, maxPlayers);
    }

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(LOCAL_PORT)) { return LOCAL_PORT; }
        catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
            catch (IOException ex) { return LOCAL_PORT; }
        }
    }
}
