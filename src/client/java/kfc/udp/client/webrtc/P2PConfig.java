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
import java.util.ArrayList;
import java.util.List;

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
 * mc-signaling → *:8090(villas-signaling에서 갈라져 나온 실험용 사본 — mc-signaling/deploy-mc-signaling.sh
 * 참고, 8088의 villas-signaling은 그대로 둔 채 별도 포트로 떠 있다), coturn → *:3478(양쪽이 같이 쓴다).
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

    /** mc-signaling WebSocket 주소 — room_update 델타 메시지 최적화가 여기(8090)에만 있고
     * 8088의 villas-signaling은 옛 프로토콜 그대로다(클래스 주석 참고). */
    public static final String SIGNALING_URL =
            System.getProperty("kfcudp.signaling", "ws://kite-private-cloud.kro.kr:8090");

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

    /** 커스텀 방 생성·접속 전에 뜨는 안전 경고 팝업의 "다시 보지 않기" — 기본은 매번 뜬다. */
    private static volatile boolean safetyWarningDismissed = loadSafetyWarningDismissed();

    public static boolean isSafetyWarningDismissed() {
        return safetyWarningDismissed;
    }

    public static void setSafetyWarningDismissed(boolean value) {
        if (safetyWarningDismissed == value) return;
        safetyWarningDismissed = value;
        updateSettingsFile(o -> o.addProperty("safetyWarningDismissed", value));
    }

    private static boolean loadSafetyWarningDismissed() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("safetyWarningDismissed") && o.get("safetyWarningDismissed").getAsBoolean();
    }

    /** 방송 비허용 방에 접속하려 할 때 뜨는 확인 팝업의 "다시 보지 않기" — 기본은 매번 뜬다.
     * safetyWarningDismissed와는 별개(하나를 껐다고 다른 하나까지 같이 안 뜨면 안 된다). */
    private static volatile boolean broadcastJoinWarningDismissed = loadBroadcastJoinWarningDismissed();

    public static boolean isBroadcastJoinWarningDismissed() {
        return broadcastJoinWarningDismissed;
    }

    public static void setBroadcastJoinWarningDismissed(boolean value) {
        if (broadcastJoinWarningDismissed == value) return;
        broadcastJoinWarningDismissed = value;
        updateSettingsFile(o -> o.addProperty("broadcastJoinWarningDismissed", value));
    }

    private static boolean loadBroadcastJoinWarningDismissed() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("broadcastJoinWarningDismissed") && o.get("broadcastJoinWarningDismissed").getAsBoolean();
    }

    /** 방 목록에서 내 마인크래프트 버전과 다른 방을 숨길지 — 기본은 보여준다(회색으로). */
    private static volatile boolean hideOtherVersions = loadHideOtherVersions();

    public static boolean isHideOtherVersions() {
        return hideOtherVersions;
    }

    public static void setHideOtherVersions(boolean value) {
        if (hideOtherVersions == value) return;
        hideOtherVersions = value;
        updateSettingsFile(o -> o.addProperty("hideOtherVersions", value));
    }

    private static boolean loadHideOtherVersions() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("hideOtherVersions") && o.get("hideOtherVersions").getAsBoolean();
    }

    /**
     * <b>방송(인터넷 스트리밍) 노출 허용.</b>
     * <p>
     * 새 신호 필드 없이 채널 시스템을 그대로 재사용한다 — 이 값이 켜져 있으면 공지되는 채널 문자열에
     * {@link #BROADCAST_TAG}가 하나 더 붙을 뿐이고(PublicRoomAnnouncer.sendUpdate 참고), 실제 접속 lobby
     * (getEffectiveChannels)는 안 건드린다 — 즉 이 방을 원래 볼 수 있는 사람에게만(채널이 겹치는 사람)
     * "방송 허용" 여부가 추가로 드러날 뿐, 못 보던 사람이 새로 보이게 되는 건 아니다. 기본값은 꺼짐
     * (노출에 동의하는 사람만 켠다).
     */
    private static volatile boolean allowBroadcast = loadAllowBroadcast();

    public static boolean isAllowBroadcast() {
        return allowBroadcast;
    }

    public static void setAllowBroadcast(boolean value) {
        if (allowBroadcast == value) return;
        allowBroadcast = value;
        updateSettingsFile(o -> o.addProperty("allowBroadcast", value));
    }

    private static boolean loadAllowBroadcast() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("allowBroadcast") && o.get("allowBroadcast").getAsBoolean();
    }

    /** 방 목록에서 "방송 허용" 여부로 방을 어떻게 거를지 — 전체(기본)/허용만/비허용만 세 상태.
     * 체크박스(2상태)로는 "비방송만 보기"를 표현할 수 없어서(꺼짐이 "전체"만 뜻함) 순환 버튼으로 둔다. */
    public enum BroadcastFilter { ALL, ALLOWED_ONLY, DISALLOWED_ONLY }

    private static volatile BroadcastFilter broadcastFilter = loadBroadcastFilter();

    public static BroadcastFilter getBroadcastFilter() {
        return broadcastFilter;
    }

    public static void setBroadcastFilter(BroadcastFilter value) {
        if (broadcastFilter == value) return;
        broadcastFilter = value;
        updateSettingsFile(o -> o.addProperty("broadcastFilter", value.name()));
    }

    private static BroadcastFilter loadBroadcastFilter() {
        JsonObject o = readSettingsFile();
        if (o == null || !o.has("broadcastFilter")) return BroadcastFilter.ALL;
        try {
            return BroadcastFilter.valueOf(o.get("broadcastFilter").getAsString());
        } catch (IllegalArgumentException e) {
            return BroadcastFilter.ALL;
        }
    }

    /** 채널 문자열에 붙는 예약 태그 — 사용자가 직접 만드는 채널 목록(최대 {@link #MAX_CHANNEL_LENGTH}자,
     * 쉼표 구분)과는 별개로 공지 시에만 덧붙는다(setChannels로 저장되는 목록엔 안 들어간다). */
    private static final String BROADCAST_TAG = "broadcast";

    /** 공지용 채널 문자열 — {@link #isAllowBroadcast()}가 켜져 있으면 {@link #BROADCAST_TAG}를 덧붙인다.
     * and 모드(여러 채널을 하나로 묶어 보내는 규칙)에서도 그냥 붙인다 — {@link #roomVisible}로 실제 채널
     * 매칭에 쓰기 전에 {@link #stripBroadcastTag}로 먼저 떼어내는 쪽(PublicRoomBrowser)이 책임진다.
     * 방송 허용 여부는 사용자가 정한 채널 목록·and/or 규칙과는 독립적인 별개의 태그로 취급한다. */
    public static String announcedChannel() {
        String base = getChannel();
        return allowBroadcast ? base + "," + BROADCAST_TAG : base;
    }

    /** 방 목록에서 받은 채널 문자열에 방송 허용 태그가 있는지 — {@link #isBroadcastOnly()} 필터용. */
    public static boolean isBroadcastTagged(String channelStr) {
        if (channelStr == null) return false;
        for (String part : channelStr.split(",")) {
            if (part.trim().equalsIgnoreCase(BROADCAST_TAG)) return true;
        }
        return false;
    }

    /** {@link #roomVisible}에 넘기기 전에 방송 태그를 떼어낸 채널 문자열 — and 묶음 채널 매칭이 방송
     * 태그 때문에 깨지지 않게(붙인 채로 넘기면 묶인 값 자체가 달라져 원래 겹쳐야 할 채널까지 안 보인다). */
    public static String stripBroadcastTag(String channelStr) {
        if (channelStr == null) return null;
        StringBuilder out = new StringBuilder();
        for (String part : channelStr.split(",", -1)) {
            if (part.trim().equalsIgnoreCase(BROADCAST_TAG)) continue;
            if (out.length() > 0) out.append(',');
            out.append(part);
        }
        return out.toString();
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
    // 채널은 여러 개다 — 쉼표로 구분해 저장하고("normal,minedapple"), 방을 공개할 때 목록 전체와 or/and 규칙이
    // 같이 실려 나간다. 보는 쪽은 자기 채널 목록으로 그 규칙을 판정한다(roomVisible).
    private static volatile List<String> channels = loadChannels();
    /** 내 방을 공개할 때의 규칙 — true(and)면 접속자가 내 채널을 전부 갖고 있어야 보이고, false(or)면 하나만 겹쳐도 보인다. */
    private static volatile boolean channelAnd = loadChannelAnd();

    /** 내 채널 목록 — 비어 있지 않다(기본 "normal"). */
    public static List<String> getChannels() {
        return channels;
    }

    /** 방 공지에 실리는 채널 문자열("a,b") — 입력란 표시에도 그대로 쓴다. */
    public static String getChannel() {
        return String.join(",", channels);
    }

    /** 채널 목록 + 규칙을 한 문자열로 — 방 설정이 바뀌었는지 비교할 때 쓴다. */
    public static String getChannelKey() {
        return getChannel() + (channelAnd ? "&" : "|");
    }

    public static boolean isChannelAnd() {
        return channelAnd;
    }

    public static void setChannelAnd(boolean value) {
        if (channelAnd == value) return;
        channelAnd = value;
        updateSettingsFile(o -> o.addProperty("channelAnd", value));
    }

    /** 채널 개수 상한 — 채널마다 lobby에 접속(방장 1개, 구경꾼 샤드 수만큼)하므로 소켓 수를 묶어 둔다. */
    public static final int MAX_CHANNELS = 5;
    /** 채널 하나의 글자 수 상한 — 부하와는 무관(채널 이름은 고정 크기 해시로만 쓰인다), 칩 표시가 줄바꿈
     * 없이 들어가고 공지 payload 크기를 예측 가능하게 두기 위함. */
    public static final int MAX_CHANNEL_LENGTH = 24;

    /** 쉼표로 구분한 입력을 목록으로 — 공백은 걷어내고, 빈 항목("a,,b"의 가운데, 맨 앞)은 기본 채널로 친다.
     * 맨 끝 쉼표 하나("a,")는 아직 입력 중인 것으로 보고 무시한다. 대소문자 무시 중복 제거, MAX_CHANNELS개까지,
     * 채널 하나당 MAX_CHANNEL_LENGTH자까지(넘으면 자른다). */
    public static List<String> parseChannels(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = (text == null ? "" : text).split(",", -1);
        int n = parts.length;
        if (n > 1 && parts[n - 1].isBlank()) n--;
        for (int i = 0; i < n; i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) t = DEFAULT_CHANNEL;
            if (t.length() > MAX_CHANNEL_LENGTH) t = t.substring(0, MAX_CHANNEL_LENGTH);
            if (out.stream().anyMatch(t::equalsIgnoreCase)) continue;
            if (out.size() >= MAX_CHANNELS) break;
            out.add(t);
        }
        return List.copyOf(out);
    }

    public static void setChannels(String text) {
        List<String> parsed = parseChannels(text);
        if (parsed.equals(channels)) return;
        channels = parsed;
        updateSettingsFile(o -> o.addProperty("channels", String.join(",", parsed)));
    }

    /** 규칙을 적용한 "실제 채널" 목록 — or면 적은 채널 하나하나가 각각 채널이고, and면 전부를 묶은 채널 하나다
     * (소문자·정렬·입력 불가능한 구분 문자로 이어서, "a,b"를 and로 쓰는 사람끼리만 같은 값이 된다). lobby 이름과
     * 보임 판정이 둘 다 이 목록을 쓴다 — 채널이 하나라도 겹쳐야 서로 보인다. */
    public static List<String> effectiveChannels(List<String> channels, boolean and) {
        if (!and || channels.size() <= 1) return channels;
        return List.of(channels.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).sorted()
                .collect(java.util.stream.Collectors.joining(String.valueOf(AND_SEPARATOR))));
    }

    public static List<String> getEffectiveChannels() {
        return effectiveChannels(channels, channelAnd);
    }

    /** 이 방이 내게 보이는지 — 방장의 실제 채널과 내 실제 채널이 하나라도 겹치면. 대소문자 구분 없음. */
    public static boolean roomVisible(String hostChannels, boolean hostAnd, List<String> mine, boolean mineAnd) {
        List<String> host = effectiveChannels(parseChannels(hostChannels), hostAnd);
        List<String> me = effectiveChannels(mine, mineAnd);
        return host.stream().anyMatch(h -> me.stream().anyMatch(h::equalsIgnoreCase));
    }

    /** and 묶음 채널의 구분 문자 — 입력란으로는 칠 수 없는 제어 문자라 "a&b" 같은 채널 이름과 안 겹친다. */
    private static final char AND_SEPARATOR = '\u001F';

    private static final String DEFAULT_CHANNEL = "normal";

    private static List<String> loadChannels() {
        JsonObject o = readSettingsFile();
        return parseChannels(o == null || !o.has("channels") ? null : o.get("channels").getAsString());
    }

    private static boolean loadChannelAnd() {
        JsonObject o = readSettingsFile();
        return o != null && o.has("channelAnd") && o.get("channelAnd").getAsBoolean();
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

    /** 채널 하나의 shard(0..{@link #PUBLIC_ROOM_SHARD_COUNT}-1)번 공개 방 목록 lobby의 실제 경로. 마인크래프트
     * 버전마다 로비가 따로라, 서로 접속도 못 하는 다른 버전의 방 공지·목록 갱신은 아예 오가지 않는다
     * (예전엔 전 버전이 로비 4개에 섞여 서로의 브로드캐스트를 받아 놓고 화면에서 버렸다). */
    public static String publicRoomsLobbyId(String channel, int shard) {
        // lobby는 채널마다 따로다 — 내 채널의 방만 받으니 서버가 보내는 명단이 채널 단위로 작아진다. 채널 이름은
        // 해시로만 나간다(서버 로그·URL에 채널 이름이 안 남게). 버전은 lobby 이름에 넣지 않는다 — 다른 버전의 방도
        // 목록에 보이게(회색 표시) 하고, 버전은 방 정보로 판단한다.
        return PUBLIC_ROOMS_LOBBY_PREFIX + "_" + channelTag(channel) + "_" + shard;
    }

    /** 채널 이름(대소문자 무시)의 SHA-256 앞 8바이트 — lobby 이름용. */
    private static String channelTag(String channel) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(channel.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

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