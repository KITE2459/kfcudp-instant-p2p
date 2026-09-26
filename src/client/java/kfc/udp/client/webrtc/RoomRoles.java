package kfc.udp.client.webrtc;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//?}

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 방 안에서 통용되는 등급 — <b>방장이 자기 {@link Roles} 사본으로 계산해 접속자에게 내려보낸 값</b>이다.
 * <p>
 * <b>왜 필요한가</b> — roles.json은 방장이 방을 열 때와 접속자가 들어올 때 각자 따로 받아온다
 * ({@code WebRtcBridge.startHost}/{@code start}의 {@link Roles#refreshAsync()}). 그 사이에 roles.json이
 * 바뀌면 두 사본이 어긋나는데, 실제 효력은 전부 방장 쪽에서 판정한다:
 * <ul>
 *   <li>{@code P2PBanManager.checkCanJoin} — 정원 무시 입장 허용</li>
 *   <li>{@code ExpelManager.handleRequest} — 강퇴·추방 수락 여부</li>
 * </ul>
 * 그래서 접속자가 자기 사본으로 화면을 그리면 "⚡가 보이는데 눌러도 아무 일이 안 남"(접속자 쪽이 더
 * 새로움), 반대로 "방장은 수락할 생각인데 {@code ExpelManager.sendMarker}가 조기 return해서 요청 자체가
 * 안 나감"(접속자 쪽이 더 낡음)이 생긴다. 판정하는 쪽과 그리는 쪽이 같은 값을 보게 만드는 게 이 클래스다.
 * <p>
 * <b>전달 방법</b> — 새 패킷을 만들지 않고 이미 쓰는 숨은 채팅 마커에 실어 보낸다
 * ({@code KfcudpClient.CAPACITY_MARKER}, {@link ExpelManager} TOKEN_MARKER와 같은 요령).
 * 내용은 <b>접속 중인 등급자만</b> {@code uuid=등급} 목록이라 보통 0~3명, 수십 바이트다. 등급 0은 아예
 * 안 실어 보내고 목록에 없으면 0으로 본다.
 * <p>
 * <b>방장은 이 값을 쓰지 않는다</b> — 방장이 곧 판정 주체라 자기 {@link Roles}를 그대로 봐야 한다
 * ({@link #rankOrNull}의 isRoomActive 분기). 안 그러면 방장이 자기가 뿌린 값을 다시 읽어서, 나중에
 * {@link Roles}가 새로 갱신돼도 첫 스냅샷에 머물러 버린다.
 */
public final class RoomRoles {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-roles");

    private static final String MARKER = "kfcudp:roles:";

    /** 방장이 알려준 등급 — uuid → 1~3. 등급 0은 안 실려오므로 키가 없으면 0이다. */
    private static volatile Map<UUID, Integer> ranks = Map.of();
    /** 마커를 한 번이라도 받았는지. 등급자가 아무도 없는 방(빈 목록)과 "아직 못 받음"을 구분해야 한다 —
     * 못 받았으면 로컬 {@link Roles}로 폴백해야 하고, 빈 목록이면 전원 0으로 확정해야 한다. */
    private static volatile boolean received = false;

    private RoomRoles() {}

    /**
     * 방장이 알려준 등급, 또는 <b>아직 모르면 null</b>(로컬 {@link Roles}를 쓰라는 뜻).
     * {@code DevBadge.roleSuffix}가 이걸 먼저 보고, null일 때만 로컬 사본으로 넘어간다.
     */
    public static Integer rankOrNull(UUID id) {
        // 방장은 판정 주체라 항상 자기 사본을 쓴다(클래스 주석 참고).
        if (id == null || !received || kfc.udp.client.KfcudpClient.isRoomActive()) return null;
        Integer r = ranks.get(id);
        return r == null ? 0 : r;
    }

    /** 접속이 끊기면 다음 방에서 남의 등급을 물려쓰지 않게 비운다. */
    public static void clear() {
        ranks = Map.of();
        received = false;
    }

    // ── 접속자 쪽: 마커를 채팅에 안 띄우고 가로채 저장 ───────────────────────────

    /** KfcudpClient.onInitializeClient에서 ExpelManager.register()와 함께 1회 부른다. */
    public static void register() {
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String s = message.getString();
            if (!s.startsWith(MARKER)) return true;
            apply(s.substring(MARKER.length()));
            return false;
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());

        // 방장 쪽: 접속자 구성이 바뀌었으니 전원에게 새로 뿌린다. 목록이 "접속 중인 등급자"라서
        // 새로 들어온 사람을 기존 접속자들도 알아야 한다. roles.json 새로고침은 이 시점이 아니라
        // 더 앞(LOGIN, ensureFreshForLogin)에서 이미 끝나 있다.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (kfc.udp.client.KfcudpClient.isRoomActive()) broadcast(server);
        });
    }

    /** LOGIN 직전 새로고침을 얼마까지 기다릴지 — 시그널링 서버는 같은 배포라 보통 수십 ms면
     * 끝난다. 넘으면 캐시값으로 진행하고 뒤늦게 도착한 값으로 다시 맞춘다. */
    private static final long LOGIN_REFRESH_TIMEOUT_MS = 700;

    /**
     * 방장이 접속 요청(LOGIN)을 처리하기 <b>직전에</b> roles.json을 최신으로 맞춘다 —
     * {@code P2PBanManager.checkCanJoin}이 부른다.
     * <p>
     * 이게 없으면 새로고침이 비동기라 LOGIN 판정(정원 무시 입장 허용)과 그 순간 계산돼 캐시되는
     * 탭 목록 배지가 낡은 값으로 굳는다 — 방을 연 뒤 roles.json에서 등급을 뺏긴 사람이 첫 접속엔
     * 그대로 개발자로 보이고, 두 번째 접속부터야 맞게 보이던 원인이다.
     */
    public static void ensureFreshForLogin(MinecraftServer server) {
        if (!kfc.udp.client.KfcudpClient.isRoomActive()) return;
        Roles.refreshBlocking(LOGIN_REFRESH_TIMEOUT_MS, () -> server.execute(() -> {
            broadcast(server);
            // 탭 목록 이름은 접속 시점에 한 번만 계산돼 전송되므로(DevBadgeMixin 주석) 등급이
            // 바뀌면 다시 보내야 옛 배지가 안 남는다.
            kfc.udp.client.KfcudpClient.kfcudp$refreshTabList(server);
        }));
    }

    private static void apply(String payload) {
        Map<UUID, Integer> parsed = new LinkedHashMap<>();
        for (String entry : payload.split(",")) {
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                parsed.put(UUID.fromString(entry.substring(0, eq)), Integer.parseInt(entry.substring(eq + 1)));
            } catch (Exception ignored) {
                // 한 줄이 깨졌다고 나머지까지 버리지 않는다(Roles.parseUuids와 같은 이유).
            }
        }
        ranks = Map.copyOf(parsed);
        received = true;
        // 접속마다 여러 번 오는 값이라 INFO로 찍으면 로그만 지저분해진다 — roles.json이 실제로
        // 바뀌었는지는 Roles의 "[roles] updated"가 알려주므로 여기선 DEBUG로 남긴다.
        LOG.debug("[roles] room ranks from host: {}", ranks);
    }

    // ── 방장 쪽: 목록을 만들어 접속자 전원에게 뿌린다 ────────────────────────────

    /** {@code uuid=등급,uuid=등급} — 등급 0은 넣지 않는다(없으면 0으로 읽힌다). */
    private static String payload(java.util.List<UUID> online) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : online) {
            int rank = ExpelManager.priority(id); // 방장이므로 자기 Roles 사본으로 계산된다
            if (rank <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id).append('=').append(rank);
        }
        return sb.toString();
    }

    //? if >=26.1 {
    /*private static void broadcast(MinecraftServer server) {
        java.util.List<UUID> online = new java.util.ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            online.add(P2PBanManager.profileId(sp.getGameProfile()));
        }
        String body = MARKER + payload(online);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            // 방장은 이 값을 쓰지 않으니(rankOrNull의 isRoomActive 분기) 보낼 필요도 없다.
            if (P2PBanManager.isHost(server, sp)) continue;
            sp.sendSystemMessage(Component.literal(body));
        }
    }
    *///?} else {
    private static void broadcast(MinecraftServer server) {
        java.util.List<UUID> online = new java.util.ArrayList<>();
        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            online.add(P2PBanManager.profileId(sp.getGameProfile()));
        }
        String body = MARKER + payload(online);
        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            // 방장은 이 값을 쓰지 않으니(rankOrNull의 isRoomActive 분기) 보낼 필요도 없다.
            if (P2PBanManager.isHost(server, sp)) continue;
            sp.sendMessage(Text.literal(body), false);
        }
    }
    //?}
}
