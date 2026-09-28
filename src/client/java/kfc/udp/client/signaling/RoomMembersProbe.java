package kfc.udp.client.signaling;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 입장 전 확인 — 방에 내가 차단한 유저가 있는지 접속을 시작하기 전에 알아낸다.
 * <p>
 * 랑데부 서버에 probe 로 붙으면 방장이 현재 접속자 해시만 이 연결로 보내 준다({@code QuicHost}
 * onJoin, 서버 rendezvous.go). 해시는 방 코드로 솔팅돼 있어 받는 쪽은 자기 차단 목록과 대조만 할 수
 * 있다({@link P2PBanManager#blockedPlayerNamesIn}).
 */
public final class RoomMembersProbe {

    private static final long TIMEOUT_MS = 10_000;

    private RoomMembersProbe() {}

    /** 방에 있는 내가 차단한 유저들의 이름 — 없거나, 방장이 없거나 답이 없으면 빈 목록(그대로 접속을 진행해
     * 원래의 실패 처리("호스트를 찾을 수 없음" 등)를 탄다). 네트워크를 기다리므로 블로킹. */
    public static List<String> blockedPlayerNames(String roomCode) {
        if (!P2PBanManager.hasBannedPlayers()) return List.of(); // 차단 목록이 비었으면 물어볼 것도 없다
        CompletableFuture<String> members = new CompletableFuture<>();
        WebSocketClient probe = new WebSocketClient(P2PConfig.SIGNALING_URL + "/rv/" + roomCode + "/join?probe=1") {
            @Override public void onConnected() {}
            @Override public void onMessage(String type, String json) {
                if (VillasMsg.field(json, "error") != null) {
                    members.complete(null); // 방장이 없다 — 원래 접속 흐름이 실패를 알려 준다
                    return;
                }
                String desc = VillasMsg.object(json, "description");
                if (desc != null && "members".equals(VillasMsg.field(desc, "type"))) {
                    members.complete(VillasMsg.field(desc, "spd"));
                }
            }
        };
        try {
            probe.connect();
            String m = members.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return m == null ? List.of() : P2PBanManager.blockedPlayerNamesIn(m, roomCode);
        } catch (Exception e) {
            return List.of();
        } finally {
            probe.close();
        }
    }
}
