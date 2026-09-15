package kfc.udp.client.webrtc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 입장 전 확인 — 방에 내가 차단한 유저가 있는지 접속을 시작하기 전에 알아낸다.
 * <p>
 * 페어 세션을 먼저 열어 두고 로비에 "jq{sid}"로 알리면, 방장이 그 세션에 잠깐 붙어 현재 접속자
 * 해시만 보내고 나간다({@link WebRtcHost} sendMembers). 해시는 방 코드로 솔팅돼 있어 받는 쪽은 자기
 * 차단 목록과 대조만 할 수 있다({@link P2PBanManager#blockedPlayerNamesIn}).
 */
public final class RoomMembersProbe {

    private static final long TIMEOUT_MS = 10_000;

    private RoomMembersProbe() {}

    /** 방에 있는 내가 차단한 유저들의 이름 — 없거나, 방장이 없거나 답이 없으면 빈 목록(그대로 접속을 진행해
     * 원래의 실패 처리("호스트를 찾을 수 없음" 등)를 탄다). 네트워크를 기다리므로 블로킹. */
    public static List<String> blockedPlayerNames(String roomCode) {
        if (!P2PBanManager.hasBannedPlayers()) return List.of(); // 차단 목록이 비었으면 물어볼 것도 없다
        String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        CompletableFuture<String> members = new CompletableFuture<>();
        WebSocketClient pair = new WebSocketClient(P2PConfig.SIGNALING_URL + "/" + roomCode + "-" + sid + "/p" + sid) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                String desc = VillasMsg.object(json, "description");
                if (desc != null && "members".equals(VillasMsg.field(desc, "type"))) {
                    members.complete(VillasMsg.field(desc, "spd"));
                }
            }
        };
        WebSocketClient lobby = new WebSocketClient(P2PConfig.SIGNALING_URL + "/" + roomCode + "/jq" + sid) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {}
        };
        try {
            pair.connect(); // 방장이 붙어 보내기 전에 먼저 세션에 들어가 있어야 메시지를 받는다
            lobby.connect();
            return P2PBanManager.blockedPlayerNamesIn(members.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), roomCode);
        } catch (Exception e) {
            return List.of();
        } finally {
            lobby.close();
            pair.close();
        }
    }
}
