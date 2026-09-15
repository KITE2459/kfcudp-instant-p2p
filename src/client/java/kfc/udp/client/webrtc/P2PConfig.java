package kfc.udp.client.webrtc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P2P 인프라 접속 설정 (시그널링/STUN/TURN) — 호스트·조인 공용.
 * <p>
 * 서버 배치가 바뀌면 이 파일만 고치면 된다. 빌드 없이 JVM 프로퍼티로도 재정의 가능:
 * <pre>
 *   -Dkfcudp.signaling=ws://HOST:PORT
 *   -Dkfcudp.stun=stun:HOST:3478
 *   -Dkfcudp.turn=turn:HOST:3478
 *   -Dkfcudp.turn.user=USER  -Dkfcudp.turn.pass=PASS
 * </pre>
 * 현재 배치 (오라클 클라우드 kite-private-cloud.kro.kr):
 * villas-signaling → *:8088, coturn → *:3478
 */
public final class P2PConfig {

    /**
     * 지금 실행 중인 마인크래프트 버전 문자열(예: "1.21.5", "26.2") — 공개 방 목록을
     * 서로 접속 자체가 안 되는 버전끼리 자동으로 분리하는 데 쓴다(PublicRoomAnnouncer/
     * PublicRoomBrowser 참고). Fabric Loader API로 구하므로 Yarn/Mojang 매핑 어느
     * 쪽으로 빌드해도 동일하게 동작 — 버전별 분기가 필요 없다. 채널(사용자가 직접
     * 고르는 값)과는 완전히 별개 축이라, 채널이 같아도 버전이 다르면 목록에 안
     * 뜬다(반대도 마찬가지) — 애초에 접속이 안 되는 상대를 목록에 보여줄 이유가
     * 없어서 사용자 입력 없이 항상 강제 적용한다.
     */
    public static final String MC_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    /** villas-signaling WebSocket 주소 */
    public static final String SIGNALING_URL =
            System.getProperty("kfcudp.signaling", "ws://kite-private-cloud.kro.kr:8088");

    /** coturn STUN (무인증) */
    public static final String STUN_URL =
            System.getProperty("kfcudp.stun", "stun:kite-private-cloud.kro.kr:3478");

    /** coturn TURN (정적 계정 인증) */
    public static final String TURN_URL =
            System.getProperty("kfcudp.turn", "turn:kite-private-cloud.kro.kr:3478");

    /** 실사용 검증된 coturn 정적 계정 */
    public static final String TURN_USERNAME =
            System.getProperty("kfcudp.turn.user", "minecraft");
    public static final String TURN_CREDENTIAL =
            System.getProperty("kfcudp.turn.pass", "minecraft");

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR     = Path.of("config", "instant-p2p");
    private static final Path SETTINGS_FILE  = CONFIG_DIR.resolve("settings.json");

    /**
     * <b>TURN 전용(relay-only) 스위치.</b>
     * <p>
     * true 면 ICE 후보를 relay 만 수집한다(=직결/홀펀칭 없음, 100% TURN 경유,
     * 상대에게 내 실제 IP가 노출되지 않는다). 기본값 false, 설정 파일에 저장돼
     * 세션을 넘어 유지된다. Custom Room(호스팅)과 Join Room/방 목록(접속) 화면의
     * "중계 통신 강제" 체크박스가 전부 이 하나의 값을 그대로 읽고 쓴다 —
     * 호스트는 강제인데 접속자는 아니게(또는 그 반대로) 어긋나는 걸 막기 위해
     * 화면마다 따로 상태를 들고 있지 않는다.
     */
    private static volatile boolean relayOnly = loadRelayOnly();

    public static boolean isRelayOnly() {
        return relayOnly;
    }

    public static void setRelayOnly(boolean value) {
        if (relayOnly == value) return;
        relayOnly = value;
        updateSettingsFile(o -> o.addProperty("relayOnly", value));
    }

    private static boolean loadRelayOnly() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("relayOnly") && o.get("relayOnly").getAsBoolean();
    }

    /** 방 목록 실시간 갱신(RoomListScreen) — 끄면 1분마다·새로고침 버튼으로만 갱신한다. 기본 켜짐. */
    private static volatile boolean liveRoomList = loadLiveRoomList();

    public static boolean isLiveRoomList() {
        return liveRoomList;
    }

    public static void setLiveRoomList(boolean value) {
        if (liveRoomList == value) return;
        liveRoomList = value;
        updateSettingsFile(o -> o.addProperty("liveRoomList", value));
    }

    private static boolean loadLiveRoomList() {
        JsonObject o = readSettingsFile();
        return o == null || !o.has("liveRoomList") || o.get("liveRoomList").getAsBoolean();
    }

    /**
     * settings.json의 다른 키(예: channel)를 안 지우고 이 키만 갈아끼운다 — 예전엔
     * 각 설정이 자기 값 하나만 담은 JsonObject를 통째로 새로 만들어 파일 전체를
     * 덮어썼다. relayOnly/roomCategory처럼 서로 다른 설정이 같은 파일을 같이
     * 쓰는 상황에서, 하나를 바꾸면 다른 하나가 (다음 로드 때) 조용히 기본값으로
     * 되돌아가는 버그가 있었다 — UI 없이 파일을 직접 편집하던 옛 카테고리
     * 설정은 거의 안 드러났지만, 이제 채널처럼 화면에서 바로 바꾸는 설정이
     * 늘면서 실제로 부딫힐 수 있어 여기서 고쳤다.
     */
    private static void updateSettingsFile(java.util.function.Consumer<JsonObject> mutate) {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject o = readSettingsFile();
            if (o == null) o = new JsonObject();
            mutate.accept(o);
            try (Writer w = new FileWriter(SETTINGS_FILE.toFile())) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings save failed: {}", e.getMessage());
        }
    }

    private static JsonObject readSettingsFile() {
        if (!Files.exists(SETTINGS_FILE)) return null;
        try (Reader r = new FileReader(SETTINGS_FILE.toFile())) {
            return GSON.fromJson(r, JsonObject.class);
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings load failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * <b>공개 방 채널.</b>
     * <p>
     * 하나의 값이 호스팅/조회 양쪽에 다 쓰인다: 방을 공개로 열면 이 값이 그 방의
     * 채널로 태그되고(PublicRoomAnnouncer), 방 목록을 볼 때도 이 값으로 필터링된다
     * (RoomListScreen이 매 tick 다시 읽어 적용 — PublicRoomBrowser 자체는 필터링
     * 없이 모든 채널의 방을 다 들고 있다가, 화면이 그중 지금 채널과 일치하는
     * 것만 추려서 보여준다. 필터를 여기로 뺀 이유는, 그렇게 안 하면 채널을
     * 입력란에서 바로 바꿔도 다음 서버 브로드캐스트가 올 때까지 목록이 안
     * 바뀌어 보였기 때문) — 서로 다른 커뮤니티가 같은 공유 시그널링 relay
     * lobby를 나눠 써도 목록이 섞이지 않게 하기 위함. 기본값 "normal", 대소문자는
     * 구분하지 않는다(오타로 채널이 갈리는 걸 막기 위한 선택 — 필요하면 나중에
     * 구분하게 바꿀 수 있다). 빈 문자열은 저장 시 기본값으로 되돌린다.
     */
    // 채널은 두 칸(채널 1·2)이다 — 둘 다 같은 방끼리만 서로 보인다. 방 공지와 목록 필터는 두 칸을 합친 값
    // 하나(getChannel)만 주고받아서, 공지 형식·필터 코드는 채널이 한 칸일 때와 똑같다.
    private static volatile String channel = loadChannel("channel");
    private static volatile String channel2 = loadChannel("channel2");

    /** 방 공지·목록 필터가 쓰는 채널 키 — 채널 1과 2를 합친 값. */
    public static String getChannel() {
        return composeChannel(channel, channel2);
    }

    /** "채널 가리기" — 켜면 채널 입력란 대신 "채널 보안 활성됨" 상자를 보여준다(방송 화면 등에 채널 이름이 드러나지
     * 않게). 화면에서만 가리고 채널 값 자체는 그대로 쓰인다. */
    private static volatile boolean hideChannel = loadHideChannel();

    public static boolean isHideChannel() {
        return hideChannel;
    }

    public static void setHideChannel(boolean value) {
        if (hideChannel == value) return;
        hideChannel = value;
        updateSettingsFile(o -> o.addProperty("hideChannel", value));
    }

    private static boolean loadHideChannel() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("hideChannel") && o.get("hideChannel").getAsBoolean();
    }

    /** 입력란용 — part 0 = 채널 1, 1 = 채널 2. */
    public static String getChannelPart(int part) {
        return part == 0 ? channel : channel2;
    }

    public static void setChannelPart(int part, String value) {
        String normalized = normalizeChannel(value);
        if (getChannelPart(part).equals(normalized)) return;
        String key = part == 0 ? "channel" : "channel2";
        if (part == 0) channel = normalized;
        else channel2 = normalized;
        updateSettingsFile(o -> o.addProperty(key, normalized));
    }

    /** 채널 1·2를 getChannel과 같은 형식으로 합친다 — 방 설정 화면이 저장 전 값끼리 비교할 때 쓴다. */
    public static String composeChannel(String part1, String part2) {
        return normalizeChannel(part1) + CHANNEL_SEPARATOR + normalizeChannel(part2);
    }

    /** 두 채널 키가 같은 채널을 가리키는지 — 대소문자 구분 없이 비교. */
    public static boolean channelMatches(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static final String DEFAULT_CHANNEL = "normal";
    /** 두 칸을 합칠 때 사이에 넣는 문자 — 입력란으로는 칠 수 없는 제어 문자라 "a"+"b/c"와 "a/b"+"c" 같은 충돌이 없다. */
    private static final char CHANNEL_SEPARATOR = '';

    private static String normalizeChannel(String value) {
        return value == null || value.isBlank() ? DEFAULT_CHANNEL : value.trim();
    }

    private static String loadChannel(String key) {
        JsonObject o = readSettingsFile();
        return o == null || !o.has(key) ? DEFAULT_CHANNEL : normalizeChannel(o.get(key).getAsString());
    }

    /**
     * 공개 방 목록용 고정 lobby 경로 접두사("roomId" 자리에 들어가는 특수값).
     * 실제 초대 코드({@link kfc.udp.client.KfcudpClient} 참고, 대문자+숫자
     * 10자)와 절대 겹치지 않도록 소문자+밑줄로 구성했다. 공개 방을 연 호스트는
     * 전부 이 lobby들 중 하나에도 접속해서(자기 원래 방 lobby와는 별개) 자신을
     * peer로 announce하고, 방 목록 화면은 모든 샤드 lobby에 동시 접속해서 현재
     * peer 목록만 읽어 합친 걸 공개 방들로 나열한다 — 새 서버 인프라 없이 기존
     * VILLASframework signaling relay의 peer 목록 브로드캐스트를 그대로
     * 재사용하는 방식(PublicRoomAnnouncer/PublicRoomBrowser 참고).
     * <p>
     * <b>왜 하나가 아니라 여러 개로 샤딩하는가</b> — signaling 서버(session.go)는
     * 세션 하나에 모인 peer가 접속/해제될 때마다 그 세션에 모인 "모든" peer에게
     * 전체 목록을 다시 뿌린다({@code SendControlMessageToAllConnectedPeers}) —
     * 세션 하나에 몰리는 peer(호스트+관전자)가 많아질수록 이벤트 하나당 비용이
     * 같이 커지고, 그 세션은 자기 전용 goroutine 하나가 순차 처리하므로 코어를
     * 하나만 쓴다. lobby를 {@link #PUBLIC_ROOM_SHARD_COUNT}개로 쪼개면 각 lobby가
     * 별도 goroutine으로 독립적으로 돌아 여러 코어에 자연히 분산되고, lobby 하나당
     * peer 수도 대략 1/N로 줄어든다 — 서버 코드를 안 건드리고 클라이언트가 접속
     * 경로만 나눠 쓰는 것만으로 되는 개선이라 서버 재배포가 필요 없다.
     */
    private static final String PUBLIC_ROOMS_LOBBY_PREFIX = "__instant_p2p_public_rooms__";

    /** 공개 방 목록 lobby 샤드 개수 — 클래스 주석 참고. 서버 재배포 없이 이 상수만 바꾸면 된다. */
    public static final int PUBLIC_ROOM_SHARD_COUNT = 4;

    /** 방 코드를 이 개수의 샤드 중 하나로 결정적으로 배정한다(같은 코드는 항상 같은 샤드). */
    public static int publicRoomShardFor(String roomCode) {
        return Math.floorMod(roomCode.hashCode(), PUBLIC_ROOM_SHARD_COUNT);
    }

    /** shard(0..{@link #PUBLIC_ROOM_SHARD_COUNT}-1)번 공개 방 목록 lobby의 실제 경로. 마인크래프트
     * 버전마다 로비가 따로라, 서로 접속도 못 하는 다른 버전의 방 공지·목록 갱신은 아예 오가지 않는다
     * (예전엔 전 버전이 로비 4개에 섞여 서로의 브로드캐스트를 받아 놓고 화면에서 버렸다). */
    public static String publicRoomsLobbyId(int shard) {
        return PUBLIC_ROOMS_LOBBY_PREFIX + "_" + LOBBY_VERSION_TAG + "_" + shard;
    }

    /** URL 경로 한 조각에 들어가도록 버전 문자열에서 안전한 문자만 남긴다. */
    private static final String LOBBY_VERSION_TAG = MC_VERSION.replaceAll("[^A-Za-z0-9._-]", "_");

    // ── 파이프 버퍼 한도 (지연 ↔ 처리량 트레이드오프) ─────────────────────────

    /**
     * <b>DataChannel 송신 버퍼 상한(바이트).</b> bufferedAmount가 이 값을 넘으면
     * TCP→DC 송신 스레드가 대기한다(백프레셔).
     * <p>
     * 예전 기본값은 16MB였는데, 이건 처리량이 아니라 <b>지연</b>을 망가뜨린다.
     * 청크 로딩으로 링크가 포화되면 이동·keepalive 같은 작은 패킷이 앞서 쌓인
     * 수 MB 뒤에 줄을 선다 — 10Mbps 기준 16MB면 12초치 큐다. SCTP 자체 혼잡제어가
     * 이미 in-flight를 관리하므로, 상한은 BDP를 조금 넘기는 선이면 충분하고
     * 1MB로도 처리량 손해는 거의 없다.
     * <p>되돌리려면 {@code -Dkfcudp.pipe.dchigh=16777216}.
     */
    public static final long DC_BUF_HIGH =
            Long.getLong("kfcudp.pipe.dchigh", 1024 * 1024L);

    /** 이 아래로 빠지면 송신 재개 (히스테리시스). */
    public static final long DC_BUF_LOW =
            Long.getLong("kfcudp.pipe.dclow", 256 * 1024L);

    /**
     * DC→TCP writer 큐 길이(64KB 청크 개수).
     * 예전 512(=32MB)는 위 DC 버퍼와 합쳐 최대 48MB의 버퍼블로트를 만들었다.
     * 64(=4MB)면 배칭 효과는 유지하면서 최악 큐 지연이 8배 줄어든다.
     * <p>되돌리려면 {@code -Dkfcudp.pipe.queuechunks=512}.
     */
    public static final int PIPE_QUEUE_CHUNKS =
            Integer.getInteger("kfcudp.pipe.queuechunks", 64);

    private P2PConfig() {}
}