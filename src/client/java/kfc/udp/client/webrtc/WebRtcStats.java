package kfc.udp.client.webrtc;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

import java.util.Map;

/** WebRtcHost/WebRtcClient 공용 — 활성 ICE candidate pair가 TURN 릴레이를 타는지 판별. */
final class WebRtcStats {

    private WebRtcStats() {}

    /** 활성 candidate pair를 못 찾으면 null. */
    static Boolean usesRelay(RTCStatsReport report) {
        for (RTCStats stats : report.getStats().values()) {
            if (stats.getType() != RTCStatsType.CANDIDATE_PAIR) continue;
            Map<String, Object> attrs = stats.getAttributes();
            boolean active = Boolean.TRUE.equals(attrs.get("nominated"))
                    || "succeeded".equals(String.valueOf(attrs.get("state")));
            if (!active) continue;

            String localType = candidateType(report, attrs.get("localCandidateId"));
            String remoteType = candidateType(report, attrs.get("remoteCandidateId"));
            if (localType == null && remoteType == null) continue;
            return "relay".equals(localType) || "relay".equals(remoteType);
        }
        return null;
    }

    private static String candidateType(RTCStatsReport report, Object candidateId) {
        if (candidateId == null) return null;
        RTCStats c = report.getStats().get(candidateId.toString());
        if (c == null) return null;
        Object t = c.getAttributes().get("candidateType");
        return t != null ? t.toString() : null;
    }
}
