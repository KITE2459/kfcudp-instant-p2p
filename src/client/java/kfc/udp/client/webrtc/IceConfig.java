package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 호스트/조인 공용 ICE 구성 빌더.
 *
 * <p><b>TURN 전용 모드</b> ({@code -Dkfcudp.ice.relayonly=true}):
 * {@link P2PConfig#isRelayOnly()} 가 true 면
 * <ul>
 *   <li>{@code iceTransportPolicy = RELAY} — host/srflx 후보를 아예 수집하지 않는다.
 *       즉 직결(Direct)·홀펀칭 경로가 생성되지 않고 모든 트래픽이 TURN 릴레이를 통과한다.</li>
 *   <li>시그널링 서버가 내려준 relay 목록에서 STUN 항목을 제거한다.
 *       (RELAY 정책에서 STUN 은 후보를 만들지 못하므로 무의미)</li>
 *   <li>TURN 이 하나도 없으면 {@link P2PConfig#TURN_URL} 기본값을 채워 넣는다.
 *       relay-only 에서 TURN 이 없으면 연결 자체가 불가능하기 때문.</li>
 * </ul>
 *
 * <p>양쪽(호스트·조인자) 모두 같은 모드로 동작해야 의미가 있다.
 * 한쪽만 RELAY 면 상대의 host 후보와 페어링되어 결국 릴레이를 지나긴 하지만
 * 경로 선택이 비대칭이 된다.
 *
 * <p><b>직결 우선 재시도</b> ({@code allowRelay} 파라미터): TURN allocate가 STUN
 * 홀펀칭보다 먼저 성사돼버리면 ICE는 (더 나은 경로를 기다리지 않고) 먼저 성공한
 * pair를 그냥 채택한다 — 즉 직결이 가능한데도 릴레이로 확정되는 경우가 생긴다.
 * 이를 피하려고 {@link WebRtcClient}/{@link WebRtcHost}는 1차로 {@code allowRelay=false}
 * (TURN 후보 자체가 없음)로 시도해서 릴레이 pair가 아예 생길 수 없게 하고, 짧은
 * 시간 안에 안 되면(2차) {@code allowRelay=true}로 재시도한다. 이때도 양쪽이
 * 같은 단계로 맞춰서 재시도해야 한다 — 조인자가 보내는 OFFER 재협상 횟수로
 * 호스트가 단계를 유추한다({@link WebRtcHost.PairSignal} 참고).
 * <p>
 * 예외: 나 또는 상대가 이미 중계 강제({@link P2PConfig#isRelayOnly()})라면 1차는
 * 어차피 실패가 확정이므로 아예 건너뛴다. 호스트는 페어 세션 peer 이름에 자기
 * 강제 여부를 실어 보내고, 조인자는 그 값을 호스트 등장 감지와 동시에 읽어서
 * {@code WebRtcClient.acceptAndBridge}에서 1차 없이 바로 릴레이 허용으로 1번만
 * 시도한다 — 짧은 타임아웃(2초)짜리 승산 없는 시도를 반복 생성/폐기하는 낭비를 없앤다.
 */
final class IceConfig {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-ice");

    private IceConfig() {}

    /**
     * @param config     채워 넣을 RTCConfiguration
     * @param relays     시그널링 서버가 내려준 {url, username, credential} 목록 (없으면 빈 리스트)
     * @param tag        로그 태그 ("host" / "client")
     * @param allowRelay false면 TURN 후보를 아예 만들지 않는다 — "직결 우선 시도" 1단계용.
     *                    host/srflx 후보만 만들어지므로 릴레이 pair가 애초에 존재할 수 없다.
     *                    {@link P2PConfig#isRelayOnly()}가 true면 이 값과 무관하게 강제로 릴레이 전용이 된다
     *                    (사용자가 명시적으로 지정한 디버그 모드라 우선한다).
     */
    static void apply(RTCConfiguration config, List<String[]> relays, String tag, boolean allowRelay) {
        final boolean relayOnly = P2PConfig.isRelayOnly();
        if (relayOnly) allowRelay = true;

        List<RTCIceServer> chosen = new ArrayList<>();
        Set<String> urls = new LinkedHashSet<>();
        int droppedStun = 0;
        int droppedTurn = 0;

        if (relays != null) {
            for (String[] r : relays) {
                if (r == null || r[0] == null || r[0].isEmpty()) continue;
                boolean isTurnUrl = isTurn(r[0]);
                if (relayOnly && !isTurnUrl) { droppedStun++; continue; }
                if (!allowRelay && isTurnUrl) { droppedTurn++; continue; }
                if (!urls.add(r[0])) continue;
                RTCIceServer s = new RTCIceServer();
                s.urls.add(r[0]);
                addTcpFallback(s, r[0]);
                if (r[1] != null) s.username = r[1];
                if (r[2] != null) s.password = r[2];
                chosen.add(s);
            }
        }

        // 서버 relay 가 없거나(=기본값 사용) relay-only 인데 TURN 이 안 내려온 경우
        boolean hasTurn = urls.stream().anyMatch(IceConfig::isTurn);
        if (chosen.isEmpty() || (relayOnly && !hasTurn)) {
            if (!relayOnly && urls.add(P2PConfig.STUN_URL)) {
                RTCIceServer stun = new RTCIceServer();
                stun.urls.add(P2PConfig.STUN_URL);
                chosen.add(stun);
            }
            if (allowRelay && urls.add(P2PConfig.TURN_URL)) {
                RTCIceServer turn = new RTCIceServer();
                turn.urls.add(P2PConfig.TURN_URL);
                addTcpFallback(turn, P2PConfig.TURN_URL);
                turn.username = P2PConfig.TURN_USERNAME;
                turn.password = P2PConfig.TURN_CREDENTIAL;
                chosen.add(turn);
            }
        }

        config.iceServers.addAll(chosen);

        if (relayOnly) {
            config.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
            LOG.info("[{}] ICE relay-only mode: {} TURN server(s){}",
                    tag, chosen.size(),
                    droppedStun > 0 ? " (" + droppedStun + " STUN entr(ies) dropped)" : "");
        } else if (!allowRelay) {
            LOG.info("[{}] ICE direct-only mode: {} server(s){}",
                    tag, chosen.size(),
                    droppedTurn > 0 ? " (" + droppedTurn + " TURN entr(ies) dropped)" : "");
        } else {
            LOG.info("[{}] ICE normal mode: {} server(s)", tag, chosen.size());
        }
    }

    private static boolean isTurn(String url) {
        String u = url.toLowerCase();
        return u.startsWith("turn:") || u.startsWith("turns:");
    }

    /**
     * TURN URL에 transport 지정이 없으면(=UDP 기본) 같은 서버에 대해
     * {@code ?transport=tcp} 후보를 추가로 얹는다.
     * <p>
     * 일부 유저 네트워크(학교·회사망, 특정 통신사 등)는 임의 UDP 포트를 막아
     * coturn이 정상 동작해도 relay 후보가 아예 안 잡힌다 — TCP 3478이 열려
     * 있으면(coturn 기본 동작) 이 fallback으로 우회할 수 있다.
     */
    private static void addTcpFallback(RTCIceServer s, String url) {
        if (!isTurn(url)) return;
        String lower = url.toLowerCase();
        if (lower.contains("transport=")) return; // 이미 명시된 경우 중복 추가 안 함
        s.urls.add(url + (url.contains("?") ? "&" : "?") + "transport=tcp");
    }
}
