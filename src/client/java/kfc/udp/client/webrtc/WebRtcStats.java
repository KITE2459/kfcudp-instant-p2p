package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

import java.util.Map;

/** WebRtcHost/WebRtcClient 공용 — 활성 ICE candidate pair가 TURN 릴레이를 타는지 판별. */
final class WebRtcStats {

    private WebRtcStats() {}

    /**
     * 활성 candidate pair를 못 찾으면 null.
     * <p>
     * report에는 과거 pair(재협상 등으로 이미 안 쓰는 succeeded pair 포함)가 남아있을
     * 수 있어 nominated pair를 최우선으로 찾는다 — 실제로 지금 쓰이는 pair는 그것
     * 하나뿐이다. nominated가 없으면(관측 타이밍 문제 등) succeeded pair로 대체한다.
     */
    static Boolean usesRelay(RTCStatsReport report) {
        Boolean succeededFallback = null;
        for (RTCStats stats : report.getStats().values()) {
            if (stats.getType() != RTCStatsType.CANDIDATE_PAIR) continue;
            Map<String, Object> attrs = stats.getAttributes();
            boolean nominated = Boolean.TRUE.equals(attrs.get("nominated"));
            boolean succeeded = "succeeded".equals(String.valueOf(attrs.get("state")));
            if (!nominated && !succeeded) continue;

            String localType = candidateType(report, attrs.get("localCandidateId"));
            String remoteType = candidateType(report, attrs.get("remoteCandidateId"));
            if (localType == null && remoteType == null) continue;
            boolean relay = "relay".equals(localType) || "relay".equals(remoteType);

            if (nominated) return relay;
            if (succeededFallback == null) succeededFallback = relay;
        }
        return succeededFallback;
    }

    private static String candidateType(RTCStatsReport report, Object candidateId) {
        if (candidateId == null) return null;
        RTCStats c = report.getStats().get(candidateId.toString());
        if (c == null) return null;
        Object t = c.getAttributes().get("candidateType");
        return t != null ? t.toString() : null;
    }
}
