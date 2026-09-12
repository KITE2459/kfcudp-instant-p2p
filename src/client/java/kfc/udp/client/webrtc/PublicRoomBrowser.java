package kfc.udp.client.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 방 목록 화면(RoomListScreen)이 쓰는 공개 방 목록 관전자.
 * <p>
 * {@link PublicRoomAnnouncer}가 호스트마다 announce해 둔 고정 lobby
 * ({@link P2PConfig#PUBLIC_ROOMS_LOBBY_ID})에 "b" 접두사 peer로 잠깐 접속해서,
 * 서버가 브로드캐스트하는 peer 목록 중 "r" 접두사(=공개 방)만 걸러
 * {@link #getCurrentRooms()}로 노출한다. 호스트가 방을 닫으면 그쪽 WebSocket도
 * 끊기므로 다음 peer 목록 갱신 때 자동으로 빠진다 — 화면이 열려 있는 동안
 * 실시간으로 갱신된다.
 */
public final class PublicRoomBrowser {

    /** 공개 방 하나의 표시 정보. */
    public record RoomEntry(String code, String title, String hostNickname, int category) {}

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-public");

    private volatile WebSocketClient ws;
    private final AtomicReference<List<RoomEntry>> currentRooms = new AtomicReference<>(List.of());

    /** 접속 시작 — 백그라운드에서 진행되며 즉시 반환. */
    public void start() {
        String peerName = PublicRoomAnnouncer.randomBrowserPeerName();
        WebSocketClient client = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + P2PConfig.PUBLIC_ROOMS_LOBBY_ID + "/" + peerName) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
            }
            @Override public void onMessage(String type, String json) {
                updateFromControl(json);
            }
        };
        this.ws = client;
        Thread t = new Thread(() -> {
            try {
                client.connect();
            } catch (Exception e) {
                LOG.warn("[public-room] browse connect failed: {}", e.toString());
            }
        }, "public-room-browse");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        WebSocketClient w = ws;
        ws = null;
        if (w != null) w.close();
    }

    /** 지금 이 순간 열려 있는 공개 방 목록 (생성 순서 그대로, 관전 세션 자신은 제외). */
    public List<RoomEntry> getCurrentRooms() {
        return currentRooms.get();
    }

    private void updateFromControl(String json) {
        if (!VillasMsg.has(json, "peers")) return;
        int wanted = P2PConfig.getRoomCategory();
        List<RoomEntry> rooms = new ArrayList<>();
        for (String[] p : VillasMsg.peers(json)) {
            String name = p[0];
            if (name == null || !name.startsWith("r")) continue;
            String[] decoded = PublicRoomAnnouncer.decode(name.substring(1));
            if (decoded == null) continue;
            int category;
            try {
                category = Integer.parseInt(decoded[3]);
            } catch (NumberFormatException e) {
                category = 1;
            }
            if (wanted != 0 && category != wanted) continue; // 0=전체, 그 외엔 같은 카테고리만
            rooms.add(new RoomEntry(decoded[0], decoded[1], decoded[2], category));
        }
        currentRooms.set(rooms);
    }
}
