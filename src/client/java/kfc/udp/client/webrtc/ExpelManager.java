package kfc.udp.client.webrtc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
*///?} else {
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//?}

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 개발자·서포터·방송인이 인게임에서 "차단"(BlockedPlayersScreen의 ❌ 버튼, 곧 P2PBanManager.banPlayer)한
 * 상대를, 차단자가 그 방에 있는 동안 강제로 추방(즉시 킥 + 재입장 거부)한다. 투표도, 단계적 제재도
 * 없다 — 등급이 충분하면 1명의 판단만으로 즉시 무력화된다(요구사항: "가해자가 즉시 그곳에서
 * 쫓겨나는게 최선").
 * <p>
 * <b>이 방식을 다시 "접속은 유지하고 묶어두는" 쪽으로 되돌리지 말 것</b> — 예전엔 관전 모드·위치
 * 고정·명령어 차단으로 묶어뒀는데, 명령어를 전부 막는 전용 믹스인과 매 틱 위치 강제가 필요했고
 * 그래도 순수 GUI 동작(예: 방장의 CustomRoomScreen)이나 권한 우회(예: 방장이 자기 대신 공범에게
 * /op) 같은 구멍이 계속 나왔다. 서버에서 내보내 버리면 그 사람은 애초에 접속해 있지 않으니 그런
 * 구멍 자체가 성립하지 않는다.
 * <p>
 * <b>등급</b> — 개발자(3) &gt; 서포터(2) &gt; 방송인(1) &gt; 무등급(0). 내 등급이 상대 등급보다 "엄격히"
 * 높아야만 내 차단이 추방을 건다 — 낮으면 당연히 안 통하고, 같아도(방송인이 방송인을 차단하는 등)
 * 안 통한다(동급끼리는 서로 못 쫓아낸다) — {@link #priority}.
 * <p>
 * <b>대상이 방장이면 추방하지 않는다</b> — 방장은 그 방의 서버 그 자체라 내보낼 방법이 없다(방장을
 * 끊으면 방 전체가 끝난다). 그 경우엔 이 클래스가 아무 것도 안 하고, 차단이 원래 하는 일반적인
 * 개인 효과(P2PBanManager.banPlayer의 ChatHideSync — 서로 채팅만 안 보이게)만 그대로 적용된다.
 * <p>
 * <b>차단은 원래 클라이언트 로컬 동작이다</b>(P2PBanManager 클래스 주석 참고 — 각자 자기 파일만 고친다).
 * 그런데 추방은 그 방의 실제 서버(=방장의 통합 서버)만 걸 수 있는 상태 변화라, 내가 접속자로 남의
 * 방에서 차단 버튼을 눌렀을 때는 그 요청이 방장에게 전달돼야 한다 — 새 패킷을 만드는 대신 이미
 * 연결된 바닐라 채팅 경로에 숨겨서 보낸다(KfcudpClient의 CAPACITY_MARKER와 같은 요령). 방장 자신이
 * 차단한 경우도 자기 자신에게 이 메시지를 보내는 것으로 통일해서, 두 경로가 서버(방장) 쪽에서
 * 완전히 같은 코드를 타게 한다.
 * <p>
 * <b>상태는 대상 UUID 기준으로 서버(방장)가 들고 있다</b> — 세션이 아니라 UUID라서 재접속으로는
 * 못 풀린다(재입장 자체가 checkCanJoin에서 거부됨). 1명을 여러 등급자가 동시에 차단했으면 각자의
 * 추방은 독립적으로 기록되고(holders), 그 차단자 전원이 나가거나 해제해야 실제로 재입장이
 * 허용된다.
 * <p>
 * <b>"킥"은 추방과 별개의, 훨씬 가벼운 기능이다</b>({@link #kick}) - holders에 아무 것도 남기지
 * 않는 1회성 강퇴라 재입장을 막지 않는다("물갈이"·경고 목적). 차단(BlockedPlayersScreen)과는
 * 연동되지 않고, BlockedPlayersScreen의 온라인 목록에서만 따로 건다. 등급·방장 예외 규칙은
 * 추방과 동일하게 그대로 적용된다.
 */
public final class ExpelManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-expel");


    // -------------------------------------------------------------------------
    // 추방 목록 — 접속자(등급자)가 "내가 추방 요청을 보낸 대상"을 내 로컬 파일에 독립적으로
    // 보관한다. P2PBanManager(banned-players.json)와 같은 저장 방식(uuid 키 JsonObject 맵을
    // JSON 배열 파일로)이지만 완전히 별개 파일이다 — 차단은 "영구히 다시 안 만남" 목적, 추방은
    // "지금 당장 못 있게 함" 목적으로 서로 다른 생명주기를 가지므로, 하나를 풀어도 다른 하나는
    // 그대로 남아야 한다(BlockedPlayersScreen에서 차단은 유지한 채 추방만 해제하는 것도 이
    // 분리 덕분에 가능). 다만 발동 경로는 지금대로 유지한다 — 차단(❌ 버튼)을 누르면 항상 같이
    // 추방 요청도 보낸다(BlockedPlayersScreen 참고), 이 목록을 직접 추가하는 별도 UI는 없다.
    // <p>
    // 서버가 실제로 추방을 걸었는지 확인 응답은 안 오므로 정확한 진실은 아니지만(요청이 등급
    // 부족·대상이 방장이라서 조용히 무시됐을 수도 있음), 관리용 목록으로는 충분하다.
    private static final Path CONFIG_DIR = Path.of("config", "instant-p2p");
    private static final Path EXPELLED_TARGETS_FILE = CONFIG_DIR.resolve("expelled-players.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private static final Map<String, JsonObject> myExpelledTargets = new LinkedHashMap<>();

    /** KfcudpClient.onInitializeClient에서 register()와 함께 1회 부른다. */
    public static synchronized void load() {
        myExpelledTargets.clear();
        if (!Files.exists(EXPELLED_TARGETS_FILE)) return;
        try (Reader r = new FileReader(EXPELLED_TARGETS_FILE.toFile())) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("uuid")) myExpelledTargets.put(o.get("uuid").getAsString(), o);
            }
        } catch (Exception e) {
            LOG.warn("[expel] failed to load expelled targets: {}", e.getMessage());
        }
    }

    private static synchronized void saveExpelledTargets() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonArray arr = new JsonArray();
            myExpelledTargets.values().forEach(arr::add);
            try (Writer w = new FileWriter(EXPELLED_TARGETS_FILE.toFile())) {
                GSON.toJson(arr, w);
            }
        } catch (Exception e) {
            LOG.warn("[expel] failed to save expelled targets: {}", e.getMessage());
        }
    }

    private static synchronized void rememberExpelledTarget(UUID target, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", target.toString());
        o.addProperty("name", name != null ? name : "");
        o.addProperty("created", FMT.format(Instant.now()));
        myExpelledTargets.put(target.toString(), o);
        saveExpelledTargets();
    }

    private static synchronized void forgetExpelledTarget(UUID target) {
        if (myExpelledTargets.remove(target.toString()) != null) saveExpelledTargets();
    }

    /** UUID → 마지막으로 알려진 이름(P2PBanManager.BannedEntry와 같은 모양). 재접속 때 목록을
     * 다시 흘려보내는 register()의 ALLOW_GAME 핸들러만 쓰는 내부용이다 — 예전엔 별도 추방 목록
     * 화면이 이걸 읽었지만 그 화면은 BlockedPlayersScreen으로 합쳐지며 없어졌다. */
    private record ExpelledTarget(UUID uuid, String name) {}

    private static synchronized java.util.List<ExpelledTarget> myExpelledTargets() {
        return myExpelledTargets.values().stream()
                .map(o -> new ExpelledTarget(UUID.fromString(o.get("uuid").getAsString()),
                        o.has("name") ? o.get("name").getAsString() : ""))
                .sorted(java.util.Comparator.comparing(ExpelledTarget::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** 추방 대상 UUID → 그 추방을 건 사람들(등급자) UUID 집합. 비어있으면(또는 키가 없으면) 추방
     * 상태 아님 — P2PBanManager.checkCanJoin이 재입장을 거부할 때, 그리고 onDisconnect가 나가는
     * 사람이 걸어둔 추방을 풀 때 이 맵을 본다. */
    private static final Map<UUID, Set<UUID>> holders = new ConcurrentHashMap<>();

    private ExpelManager() {}

    /** 방장은 등급과 무관하게 최상위다 — 자기 방이라 개발자·서포터·방송인 누구든 강퇴·차단할 수
     * 있어야 한다. 등급 최고치(개발자 3)보다 커야 {@code senderPriority <= priority(target)}를
     * 항상 통과한다. 반대로 <b>방장이 대상일 때</b>는 이 값과 무관하게 expel/kick이 먼저 거른다
     * (방장을 내보낼 방법이 없다 — 클래스 주석 참고). */
    public static final int HOST_PRIORITY = 4;

    /** 개발자 3 &gt; 서포터 2 &gt; 방송인 1 &gt; 무등급 0. 역할 판정·우선순위는 DevBadge.roleSuffix
     * 하나만 쓴다 — 순서를 여기 한 벌 더 적어두면 또 어긋난다(그쪽 주석 참고). */
    public static int priority(UUID id) {
        if (id == null) return 0;
        String suffix = kfc.udp.client.DevBadge.roleSuffix(id);
        if (suffix == null) return 0;
        return switch (suffix) {
            case "dev" -> 3;
            case "supporter" -> 2;
            default -> 1;
        };
    }

    /**
     * 지금 붙어 있는 방에서 실제로 먹히는 등급 — 방송인(1)의 추방·강퇴 권한은 "방송 허용" 방에서만
     * 산다(스트리머 보호가 목적이니 방송을 안 하는 방에선 줄 이유가 없다). 비허용 방에 들어간
     * 방송인은 무등급(0)과 똑같이 취급되어 화면에도 강퇴 버튼이 안 뜬다.
     * <p>
     * <b>화면 표시 전용이다</b> — 실제 차단은 아래 handleRequest가 방장(서버) 자신의 설정으로 다시
     * 한다. 이게 없으면 비허용 방의 방송인에게 강퇴 버튼이 뻔히 보이는데 눌러도 아무 일도 안 난다.
     * 개발자·서포터는 방송 허용과 무관하다.
     */
    public static int effectivePriority(UUID id) {
        // 방장은 등급이 없어도 최상위 — 화면에도 강퇴 열이 뜨고 아무도 면역이 아니게 된다.
        if (kfc.udp.client.DevBadge.isHostPlayer(id)) return HOST_PRIORITY;
        int p = priority(id);
        return p == 1 && !kfc.udp.client.KfcudpClient.isBroadcastAllowedHere() ? 0 : p;
    }

    /** 지금 이 UUID가 추방 상태라 재입장이 막혀야 하는지 — P2PBanManager.checkCanJoin에서 부른다. */
    public static boolean isExpelled(UUID id) {
        Set<UUID> h = holders.get(id);
        return h != null && !h.isEmpty();
    }

    // ── 클라이언트 쪽: BlockedPlayersScreen의 차단/해제 버튼에서 호출 ──────────────────────────

    /** 차단/해제 버튼을 눌렀을 때 — 내가 등급자면 전용 패킷으로 서버(방장)에 추방을 요청한다.
     * name은 expelled-players.json에 같이 적어 BlockedPlayersScreen이 이름을 보여줄 때 쓴다. */
    public static void requestExpel(UUID target, String name) {
        rememberExpelledTarget(target, name);
        request(P2PNet.ACTION_EXPEL, target);
    }

    public static void requestReadmit(UUID target) {
        forgetExpelledTarget(target);
        request(P2PNet.ACTION_READMIT, target);
    }

    /** 1회성 강퇴 — 추방과 달리 아무 것도 기록하지 않는다(재접속 제한 없음, 재접속 시 다시 보낼 것도 없음). */
    public static void requestKick(UUID target) {
        request(P2PNet.ACTION_KICK, target);
    }

    /**
     * 방장이면 내 서버에서 바로 처리하고, 접속자면 {@link P2PNet.Moderation} 패킷으로 방장에게 보낸다.
     * <p>
     * <b>방장의 추방(EXPEL)은 보내지 않는다</b> — 방장의 차단은 이미 P2PBanManager.banPlayer(영구)
     * + KfcudpClient.kickBlockedPlayer로 임시밴보다 강하게 처리되고, 그쪽이 내보내는 것과 holders
     * 등록이 겹치면 "온라인이 아닌데 holders에만 남는" 유령 추방이 생긴다. 강퇴(KICK)는 방장도
     * 최상위 권한으로 쓸 수 있어야 하므로 그대로 실행한다.
     */
    private static void request(int action, UUID target) {
        if (kfc.udp.client.KfcudpClient.isRoomActive()) {
            MinecraftServer server = activeServer;
            UUID me = myUuid();
            if (server == null || me == null || action == P2PNet.ACTION_EXPEL) return;
            server.execute(() -> dispatch(server, me, action, target));
            return;
        }
        if (!kfc.udp.client.DevBadge.isP2pSessionActive()) return;
        UUID me = myUuid();
        if (me == null || priority(me) == 0) return;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new P2PNet.Moderation(action, target));
    }

    //? if >=26.1 {
    /*private static UUID myUuid() {
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        return p == null ? null : p.getUUID();
    }
    *///?} else {
    private static UUID myUuid() {
        net.minecraft.client.network.ClientPlayerEntity p = net.minecraft.client.MinecraftClient.getInstance().player;
        return p == null ? null : p.getUuid();
    }
    //?}

    // ── 서버(방장) 쪽: 등록은 KfcudpClient에서 register() 1회 호출 ──────────────────────────

    /** ServerPlayerEntity → MinecraftServer로 가는 메서드 이름이 1.21.6~1.21.8 구간에서만 달라서
     * (getEntityWorld/getWorld 등, Yarn 리매핑이 그 사이에 흔들렸다) 매번 그걸 쫓아가는 대신,
     * ServerPlayConnectionEvents가 이미 넘겨주는 server 참조(버전 안 가리고 항상 동일한 시그니처)를
     * 여기 캐시해두고 재사용한다. */
    private static volatile MinecraftServer activeServer;

    public static void register() {
        load(); // expelled-players.json을 메모리로 읽어온다 — P2PBanManager.registerCommands()와 같은 타이밍.
        // 요청 수신 — 누가 보냈는지는 연결이 보증하므로 예전처럼 세션 토큰으로 증명할 필요가 없다
        // (P2PNet 클래스 주석 참고). 패킷은 네트워크 스레드에서 올 수 있어 서버 스레드로 넘긴다.
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                P2PNet.Moderation.ID, (payload, context) -> {
                    MinecraftServer server = activeServer;
                    if (server == null) return;
                    var sender = context.player();
                    server.execute(() -> handleRequest(server, sender, payload.action(), payload.target()));
                });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> activeServer = server);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(server, handler.player));
        // 내가 (재)접속한 순간, 로컬에 갖고 있는 추방 목록(expelled-players.json) 전체를 다시 추방
        // 요청으로 흘려보낸다 — 안 그러면 "추방을 건 등급자가 나갔다 다시 들어와도 예전 대상이 다시
        // 안 쫓겨나는" 문제가 생긴다(나가면 그 즉시 풀리는데, 이 목록엔 그대로 남아있는데도 재접속이
        // 그걸 다시 발동시켜주는 계기가 없었다). 차단 목록(P2PBanManager)이 아니라 추방 목록을 그대로
        // 쓴다 — 차단은 유지한 채 BlockedPlayersScreen에서 추방만 해제한 대상은 재접속해도 다시 안
        // 쫓겨나야 한다. 온라인이 아니거나 등급이 부족한 대상은 방장이 알아서 조용히 무시하니 전체를
        // 다시 보내도 안전하다. 예전엔 방장이 보내주는 세션 토큰의 도착이 이 계기였다.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            for (ExpelledTarget t : myExpelledTargets()) {
                request(P2PNet.ACTION_EXPEL, t.uuid()); // rememberExpelledTarget을 다시 부르지 않는다 — 이미 기록돼 있음
            }
        });
    }

    /** 요청 하나를 등급 검사 후 실행한다 — 서버(방장) 스레드에서만 불린다. */
    //? if >=26.1 {
    /*private static void handleRequest(MinecraftServer server, ServerPlayer sender, int action, UUID target) {
    *///?} else {
    private static void handleRequest(MinecraftServer server, ServerPlayerEntity sender, int action, UUID target) {
    //?}
        // 방 상태 요청은 등급과 무관하다 — 누구나 자기 화면을 맞추려고 보낼 수 있다.
        if (action == P2PNet.ACTION_REQUEST_STATE) {
            RoomRoles.broadcast(server);
            return;
        }
        // 내 등급이 상대보다 "엄격히" 높아야만 통과 — <=로 걸어서 동급끼리(둘 다 방송인끼리 등)
        // 서로 추방하는 것도 막는다. 등급 0(무등급)은 상대가 몇 등급이든 항상 0<=priority(target)이라
        // 자동으로 걸러진다(따로 0 체크를 안 해도 됨). 방장은 등급과 무관하게 최상위다.
        int senderPriority = P2PBanManager.isHost(server, sender) ? HOST_PRIORITY : priority(P2PBanManager.profileId(sender.getGameProfile()));
        if (senderPriority <= priority(target)) return;
        // 스트리머 등급(1)의 추방·강퇴 권한은 방송 허용 방에서만 유효 — 방송 중인 스트리머 보호가
        // 목적이다(CustomRoomScreen의 스트리머 보호 안내 팝업 참고). 해제는 막지 않는다 — 방송
        // 허용을 중간에 껐다고 이미 추방한 사람을 영영 못 풀게 되면 안 된다. 개발자·서포터는 무관하게 그대로.
        if (action != P2PNet.ACTION_READMIT && senderPriority == 1 && !P2PConfig.isAllowBroadcast()) return;
        dispatch(server, P2PBanManager.profileId(sender.getGameProfile()), action, target);
    }

    /** 등급 검사를 통과한(또는 방장 본인의) 요청을 실제로 실행한다. */
    private static void dispatch(MinecraftServer server, UUID senderUuid, int action, UUID target) {
        switch (action) {
            case P2PNet.ACTION_EXPEL -> expel(senderUuid, target, server);
            case P2PNet.ACTION_READMIT -> readmit(senderUuid, target, server);
            case P2PNet.ACTION_KICK -> kick(senderUuid, target, server);
            default -> { }
        }
    }

    private static void expel(UUID expellerUuid, UUID targetUuid, MinecraftServer server) {
        //? if >=26.1 {
        /*ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        // 대상이 방장이면 아무 것도 안 한다 — 방장을 추방할 방법이 없다(클래스 주석 참고). 방장은
        // 서버가 도는 한 항상 온라인이라 target==null이면 방장일 수 없다(온라인 아닌 대상은 애초에
        // 방장이 아니라는 뜻) — 그래서 target이 있을 때만 방장 여부를 확인하면 충분하다.
        if (target != null && P2PBanManager.isHost(server, target)) return;
        boolean alreadyExpelled = isExpelled(targetUuid);
        holders.computeIfAbsent(targetUuid, k -> ConcurrentHashMap.newKeySet()).add(expellerUuid);
        if (alreadyExpelled) return; // 이미 추방 상태 — 킥은 한 번만
        if (target == null) return; // 온라인이 아니면 holders 기록만 남기고 끝 — 재입장은 checkCanJoin이 막는다.
        ServerPlayer expeller = server.getPlayerList().getPlayer(expellerUuid);
        target.connection.disconnect(Component.translatable("instant-p2p.msg.expelled_by",
                expeller != null ? expeller.getName() : Component.literal("?")));
        *///?} else {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target != null && P2PBanManager.isHost(server, target)) return;
        boolean alreadyExpelled = isExpelled(targetUuid);
        holders.computeIfAbsent(targetUuid, k -> ConcurrentHashMap.newKeySet()).add(expellerUuid);
        if (alreadyExpelled) return;
        if (target == null) return;
        ServerPlayerEntity expeller = server.getPlayerManager().getPlayer(expellerUuid);
        target.networkHandler.disconnect(Text.translatable("instant-p2p.msg.expelled_by",
                expeller != null ? expeller.getName() : Text.literal("?")));
        //?}
        LOG.info("[expel] {} expelled by {}", targetUuid, expellerUuid);
    }

    private static void readmit(UUID expellerUuid, UUID targetUuid, MinecraftServer server) {
        Set<UUID> h = holders.get(targetUuid);
        if (h == null) return;
        h.remove(expellerUuid);
        if (!h.isEmpty()) return; // 다른 차단자가 아직 남아있음
        holders.remove(targetUuid);
        LOG.info("[expel] {} readmitted", targetUuid);
    }

    /** 추방과 달리 holders에 아무 것도 남기지 않는 1회성 강퇴 — 재입장은 막지 않는다
     * (BlockedPlayersScreen 3번째 버튼, "물갈이"용). 방장은 추방과 같은 이유로 대상에서 제외. */
    private static void kick(UUID kickerUuid, UUID targetUuid, MinecraftServer server) {
        //? if >=26.1 {
        /*ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        if (target == null) return; // 온라인이 아니면 할 일 없음
        if (P2PBanManager.isHost(server, target)) return;
        ServerPlayer kicker = server.getPlayerList().getPlayer(kickerUuid);
        target.connection.disconnect(Component.translatable("instant-p2p.msg.kicked_by",
                kicker != null ? kicker.getName() : Component.literal("?")));
        *///?} else {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target == null) return;
        if (P2PBanManager.isHost(server, target)) return;
        ServerPlayerEntity kicker = server.getPlayerManager().getPlayer(kickerUuid);
        target.networkHandler.disconnect(Text.translatable("instant-p2p.msg.kicked_by",
                kicker != null ? kicker.getName() : Text.literal("?")));
        //?}
        LOG.info("[expel] {} kicked by {}", targetUuid, kickerUuid);
    }

    //? if >=26.1 {
    /*private static void onDisconnect(MinecraftServer server, ServerPlayer player) {
        UUID leaving = player.getUUID();
        for (UUID target : java.util.List.copyOf(holders.keySet())) {
            readmit(leaving, target, server);
        }
    }
    *///?} else {
    private static void onDisconnect(MinecraftServer server, ServerPlayerEntity player) {
        UUID leaving = player.getUuid();
        for (UUID target : java.util.List.copyOf(holders.keySet())) {
            readmit(leaving, target, server);
        }
    }
    //?}
}
