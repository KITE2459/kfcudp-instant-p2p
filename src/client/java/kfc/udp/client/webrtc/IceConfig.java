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
 * {@link P2PConfig#RELAY_ONLY} 가 true 면
 * <ul>
 *   <li>{@code iceTransportPolicy = RELAY} — host/srflx 후보를 아예 수집하지 않는다.
 *       즉 직결(P2P)·홀펀칭 경로가 생성되지 않고 모든 트래픽이 TURN 릴레이를 통과한다.</li>
 *   <li>시그널링 서버가 내려준 relay 목록에서 STUN 항목을 제거한다.
 *       (RELAY 정책에서 STUN 은 후보를 만들지 못하므로 무의미)</li>
 *   <li>TURN 이 하나도 없으면 {@link P2PConfig#TURN_URL} 기본값을 채워 넣는다.
 *       relay-only 에서 TURN 이 없으면 연결 자체가 불가능하기 때문.</li>
 * </ul>
 *
 * <p>양쪽(호스트·조인자) 모두 같은 모드로 동작해야 의미가 있다.
 * 한쪽만 RELAY 면 상대의 host 후보와 페어링되어 결국 릴레이를 지나긴 하지만
 * 경로 선택이 비대칭이 된다.
 */
final class IceConfig {

    private static final Logger LOG = LoggerFactory.getLogger("kfcudp-ice");

    private IceConfig() {}

    /**
     * @param config  채워 넣을 RTCConfiguration
     * @param relays  시그널링 서버가 내려준 {url, username, credential} 목록 (없으면 빈 리스트)
     * @param tag     로그 태그 ("host" / "client")
     */
    static void apply(RTCConfiguration config, List<String[]> relays, String tag) {
        final boolean relayOnly = P2PConfig.RELAY_ONLY;

        List<RTCIceServer> chosen = new ArrayList<>();
        Set<String> urls = new LinkedHashSet<>();
        int droppedStun = 0;

        if (relays != null) {
            for (String[] r : relays) {
                if (r == null || r[0] == null || r[0].isEmpty()) continue;
                if (relayOnly && !isTurn(r[0])) { droppedStun++; continue; }
                if (!urls.add(r[0])) continue;
                RTCIceServer s = new RTCIceServer();
                s.urls.add(r[0]);
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
            if (urls.add(P2PConfig.TURN_URL)) {
                RTCIceServer turn = new RTCIceServer();
                turn.urls.add(P2PConfig.TURN_URL);
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
        } else {
            LOG.info("[{}] ICE normal mode: {} server(s)", tag, chosen.size());
        }
    }

    private static boolean isTurn(String url) {
        String u = url.toLowerCase();
        return u.startsWith("turn:") || u.startsWith("turns:");
    }
}
