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
        saveRelayOnly(value);
    }

    private static boolean loadRelayOnly() {
        if (!Files.exists(SETTINGS_FILE)) return false;
        try (Reader r = new FileReader(SETTINGS_FILE.toFile())) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            return o != null && o.has("relayOnly") && o.get("relayOnly").getAsBoolean();
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings load failed: {}", e.getMessage());
            return false;
        }
    }

    private static void saveRelayOnly(boolean value) {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject o = new JsonObject();
            o.addProperty("relayOnly", value);
            try (Writer w = new FileWriter(SETTINGS_FILE.toFile())) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings save failed: {}", e.getMessage());
        }
    }

    /**
     * <b>공개 방 목록 카테고리(내부용, UI 없음 — 필요하면 settings.json을 직접 편집).</b>
     * <p>
     * 하나의 값이 호스팅/조회 양쪽에 다 쓰인다: 방을 공개로 열면 이 값이 그 방의
     * 카테고리로 태그되고(PublicRoomAnnouncer), 방 목록을 볼 때도 이 값으로
     * 필터링된다(PublicRoomBrowser) — 서로 다른 커뮤니티가 같은 공유 시그널링
     * relay lobby를 나눠 써도 목록이 섞이지 않게 하기 위함.
     * <ul>
     *   <li>0 = 모든 방 보기 (카테고리 무관)</li>
     *   <li>1 = 1(A그룹)로 호스팅된 방만 (기본값)</li>
     *   <li>2 = 2(B그룹)로 호스팅된 방만</li>
     * </ul>
     */
    private static final int roomCategory = loadRoomCategory();

    public static int getRoomCategory() {
        return roomCategory;
    }

    private static int loadRoomCategory() {
        if (!Files.exists(SETTINGS_FILE)) return 1;
        try (Reader r = new FileReader(SETTINGS_FILE.toFile())) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            if (o == null || !o.has("roomCategory")) return 1;
            int v = o.get("roomCategory").getAsInt();
            return v == 0 || v == 1 || v == 2 ? v : 1;
        } catch (Exception e) {
            LOG.warn("[instant-p2p] settings load failed: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * 공개 방 목록용 고정 lobby 경로("roomId" 자리에 들어가는 특수값).
     * 실제 초대 코드({@link kfc.udp.client.KfcudpClient} 참고, 대문자+숫자
     * 10자)와 절대 겹치지 않도록 소문자+밑줄로 구성했다. 공개 방을 연 호스트는
     * 전부 이 lobby에도 접속해서(자기 원래 방 lobby와는 별개) 자신을
     * peer로 announce하고, 방 목록 화면은 이 lobby에 접속해서 현재 peer
     * 목록만 읽어 공개 방들을 나열한다 — 새 서버 인프라 없이 기존
     * VILLASframework signaling relay의 peer 목록 브로드캐스트를 그대로
     * 재사용하는 방식(PublicRoomAnnouncer/RoomListScreen 참고).
     */
    public static final String PUBLIC_ROOMS_LOBBY_ID = "__instant_p2p_public_rooms__";

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