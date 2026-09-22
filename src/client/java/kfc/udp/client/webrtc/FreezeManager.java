package kfc.udp.client.webrtc;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//? if >=26.1 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
*///?} else {
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
//?}

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 개발자·서포터·방송인이 인게임에서 "차단"(BlockedPlayersScreen의 x 버튼, 곧 P2PBanManager.banPlayer)한
 * 상대를 그 자리에서 즉시 동결한다. 투표도, 단계적 제재도 없다 — 등급이 충분하면 1명의 판단만으로
 * 즉시·완전히 무력화된다(요구사항: "가해자가 즉시 그곳에 동결되는게 최선").
 * <p>
 * <b>등급</b> — 개발자(3) &gt; 서포터(2) &gt; 방송인(1) &gt; 무등급(0). 내 등급이 상대 등급보다 낮으면
 * 내 차단은 동결을 못 건다(방송인이 서포터를 차단해도 서포터는 안 얼어붙는다) — {@link #priority}.
 * <p>
 * <b>차단은 원래 클라이언트 로컬 동작이다</b>(P2PBanManager 클래스 주석 참고 — 각자 자기 파일만 고친다).
 * 그런데 동결은 그 방의 실제 서버(=방장의 통합 서버)만 걸 수 있는 상태 변화라, 내가 접속자로 남의
 * 방에서 차단 버튼을 눌렀을 때는 그 요청이 방장에게 전달돼야 한다 — 새 패킷을 만드는 대신 이미
 * 연결된 바닐라 채팅 경로에 숨겨서 보낸다(KfcudpClient의 CAPACITY_MARKER와 같은 요령). 방장 자신이
 * 차단한 경우도 자기 자신에게 이 메시지를 보내는 것으로 통일해서, 두 경로가 서버(방장) 쪽에서
 * 완전히 같은 코드를 타게 한다.
 * <p>
 * <b>상태는 대상 UUID 기준으로 서버(방장)가 들고 있다</b> — 세션이 아니라 UUID라서 재접속으로는
 * 못 풀린다. 1명을 여러 등급자가 동시에 차단했으면 각자의 차단은 독립적으로 기록되고(holders),
 * 전부 풀려야(차단 해제 또는 그 차단자가 나감) 실제로 해제된다 — 그런데 효과 자체(관전 모드·위치
 * 고정)는 겹쳐 걸리지 않고 하나만 적용된다.
 */
public final class FreezeManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-freeze");

    private static final String FREEZE_MARKER = "kfcudp:freeze:";
    private static final String UNFREEZE_MARKER = "kfcudp:unfreeze:";
    /** 접속 시 서버(방장)가 발급해 이 마커로 숨겨 보내는 세션 토큰 — 이걸 모르면 동결 요청 자체가
     * 안 먹힌다. UUID만 아는 채로 채팅창에 kfcudp:freeze:대상UUID를 손으로 쳐서 등급 검사만 우연히
     * 통과하면 동결이 걸리던 문제(등급 있는 사람이 "차단" 버튼을 실제로 안 눌러도 발동 가능했음)를
     * 막는다 — 이 토큰은 매 접속마다 무작위로 새로 발급되고 마커로만 오가서, 진짜 이 모드의 차단
     * 버튼을 거치지 않고는 알아낼 방법이 없다. */
    private static final String TOKEN_MARKER = "kfcudp:freezetoken:";
    /** 접속자 UUID → 그 접속에 발급한 토큰(서버=방장 쪽에서만 씀). */
    private static final Map<UUID, String> sessionTokens = new ConcurrentHashMap<>();
    /** 이 클라이언트가 이번 접속에서 받은 자기 토큰 — 동결 요청을 보낼 때 같이 실어 보낸다. */
    private static volatile String mySessionToken;

    /** 얼어있는 동안엔 명령어 자체를 막는다 — 안 그러면 치트/오피가 있는 상대는 /gamemode 한 번으로
     * 관전 모드를 빠져나가 동결이 무의미해진다. 단, 방장이 얼려진 경우엔(자기보다 등급 높은 사람한테
     * 차단당하면 방장도 얼 수 있다) 자기 방에 대한 최소한의 통제력은 남겨준다 — P2PBanManager/
     * P2PWhitelistManager가 등록하는 명령어 이름 그대로. */
    private static final Set<String> HOST_ALLOWED_COMMANDS =
            Set.of("ban", "ban-ip", "pardon", "pardon-ip", "kick", "op", "deop", "whitelist");

    /** 동결 대상 UUID → 그 동결을 건 사람들(등급자) UUID 집합. 비어있으면(또는 키가 없으면) 안 얼어있음. */
    private static final Map<UUID, Set<UUID>> holders = new ConcurrentHashMap<>();
    /** 처음 얼릴 때의 위치 — 매 틱 이 좌표로 되돌린다(관전 모드라 자유비행이 가능해서 틱마다 강제해야 함). */
    private static final Map<UUID, double[]> lockedPos = new ConcurrentHashMap<>();

    //? if >=26.1 {
    /*private static final Map<UUID, GameType> savedGameMode = new ConcurrentHashMap<>();
    *///?} else {
    private static final Map<UUID, GameMode> savedGameMode = new ConcurrentHashMap<>();
    //?}

    private FreezeManager() {}

    /** 개발자 3 &gt; 서포터 2 &gt; 방송인 1 &gt; 무등급 0. */
    public static int priority(UUID id) {
        if (id == null) return 0;
        if (Roles.isDev(id)) return 3;
        if (Roles.isSupporter(id)) return 2;
        if (Roles.isStreamer(id)) return 1;
        return 0;
    }

    public static boolean isFrozen(UUID id) {
        Set<UUID> h = holders.get(id);
        return h != null && !h.isEmpty();
    }

    /** FreezeCommandMixin이 모든 명령어 실행(Brigadier CommandDispatcher.execute) HEAD에서 부른다 —
     * true면 그 명령어를 아예 실행하지 않고 취소한다. */
    //? if >=26.1 {
    /*public static boolean shouldBlockCommand(ServerPlayer player, MinecraftServer server, String rawCommand) {
        if (player == null || !isFrozen(player.getUUID())) return false;
        if (server != null && P2PBanManager.isHost(server, player) && HOST_ALLOWED_COMMANDS.contains(firstWord(rawCommand))) {
            return false;
        }
        return true;
    }
    *///?} else {
    public static boolean shouldBlockCommand(ServerPlayerEntity player, MinecraftServer server, String rawCommand) {
        if (player == null || !isFrozen(player.getUuid())) return false;
        if (server != null && P2PBanManager.isHost(server, player) && HOST_ALLOWED_COMMANDS.contains(firstWord(rawCommand))) {
            return false;
        }
        return true;
    }
    //?}

    private static String firstWord(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    // ── 클라이언트 쪽: BlockedPlayersScreen의 차단/해제 버튼에서 호출 ──────────────────────────

    /** 차단/해제 버튼을 눌렀을 때 — 내가 등급자면 숨은 채팅 마커로 서버(방장)에 동결을 요청한다. */
    public static void requestFreeze(UUID target) { sendMarker(FREEZE_MARKER, target); }
    public static void requestUnfreeze(UUID target) { sendMarker(UNFREEZE_MARKER, target); }

    //? if >=26.1 {
    /*private static void sendMarker(String prefix, UUID target) {
        // 방장이 차단하면 kickBlockedPlayer가 이미 즉시 내보낸다 — 방장은 이미 방에 대한
        // 전권이 있어 동결이 필요 없고, 내보내는 것과 동시에 요청을 보내면 그 사이에 target이
        // 사라져 "온라인이 아닌데 holders에만 등록"되는 유령 동결이 남을 수 있어 아예 안 보낸다.
        if (kfc.udp.client.KfcudpClient.isRoomActive()) return;
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client.player == null || !kfc.udp.client.DevBadge.isP2pSessionActive()) return;
        if (priority(client.player.getUUID()) == 0) return;
        String token = mySessionToken;
        if (token == null) return; // 접속 직후 토큰이 아직 안 왔으면(거의 없는 타이밍) 조용히 포기
        client.player.connection.sendChat(prefix + token + ":" + target);
    }
    *///?} else {
    private static void sendMarker(String prefix, UUID target) {
        if (kfc.udp.client.KfcudpClient.isRoomActive()) return;
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null || !kfc.udp.client.DevBadge.isP2pSessionActive()) return;
        if (priority(client.player.getUuid()) == 0) return;
        String token = mySessionToken;
        if (token == null) return;
        client.player.networkHandler.sendChatMessage(prefix + token + ":" + target);
    }
    //?}

    // ── 서버(방장) 쪽: 등록은 KfcudpClient에서 register() 1회 호출 ──────────────────────────

    /** ServerPlayerEntity → MinecraftServer로 가는 메서드 이름이 1.21.6~1.21.8 구간에서만 달라서
     * (getEntityWorld/getWorld 등, Yarn 리매핑이 그 사이에 흔들렸다) 매번 그걸 쫓아가는 대신,
     * ServerPlayConnectionEvents가 이미 넘겨주는 server 참조(버전 안 가리고 항상 동일한 시그니처)를
     * 여기 캐시해두고 재사용한다. */
    private static volatile MinecraftServer activeServer;

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FreezeManager::onChatMessage);
        ServerTickEvents.END_SERVER_TICK.register(FreezeManager::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            activeServer = server;
            onJoin(server, handler.player);
            issueSessionToken(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(server, handler.player));
        // 방장이 접속마다 몰래 발급해 보내는 토큰을 채팅에 안 띄우고 가로채 저장한다(KfcudpClient의
        // CAPACITY_MARKER와 같은 요령) — 나(호스트 자신 포함, 자기 세션에도 JOIN이 불린다)도 이걸
        // 받아야 동결 요청을 보낼 수 있다. 토큰을 받은 시점 = 방금 (재)접속했다는 뜻이라, 그 김에
        // 내가 로컬에 갖고 있는 차단 목록 전체를 다시 동결 요청으로 흘려보낸다 — 안 그러면
        // "차단한 등급자가 나갔다 다시 들어와도 예전 대상이 다시 안 얼려지는" 문제가 생긴다
        // (나가면 그 즉시 풀리는데, 차단 자체는 로컬에 그대로 남아있는데도 재접속이 그걸 다시
        // 발동시켜주는 계기가 없었다). 온라인이 아니거나 등급이 부족한 대상은 서버가 알아서
        // 조용히 무시하니 그냥 전체를 다시 보내도 안전하다.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String s = message.getString();
            if (!s.startsWith(TOKEN_MARKER)) return true;
            mySessionToken = s.substring(TOKEN_MARKER.length());
            for (P2PBanManager.BannedEntry e : P2PBanManager.listBannedPlayers()) {
                try {
                    requestFreeze(UUID.fromString(e.uuid()));
                } catch (IllegalArgumentException ignored) {}
            }
            return false;
        });
    }

    //? if >=26.1 {
    /*private static void issueSessionToken(ServerPlayer player) {
        String token = UUID.randomUUID().toString();
        sessionTokens.put(player.getUUID(), token);
        player.sendSystemMessage(Component.literal(TOKEN_MARKER + token));
    }
    *///?} else {
    private static void issueSessionToken(ServerPlayerEntity player) {
        String token = UUID.randomUUID().toString();
        sessionTokens.put(player.getUuid(), token);
        player.sendMessage(Text.literal(TOKEN_MARKER + token), false);
    }
    //?}

    //? if >=26.1 {
    /*private static boolean onChatMessage(net.minecraft.network.chat.PlayerChatMessage message, ServerPlayer sender,
                                         net.minecraft.network.chat.ChatType.Bound params) {
        String content = message.signedContent();
        if (content == null) return true;
        if (isFrozen(sender.getUUID()) && !content.startsWith(FREEZE_MARKER) && !content.startsWith(UNFREEZE_MARKER)) {
            return false; // 동결 중엔 실제 채팅 자체를 막는다
        }
        if (content.startsWith(FREEZE_MARKER)) {
            handleRequest(sender, content.substring(FREEZE_MARKER.length()), true);
            return false;
        }
        if (content.startsWith(UNFREEZE_MARKER)) {
            handleRequest(sender, content.substring(UNFREEZE_MARKER.length()), false);
            return false;
        }
        return true;
    }

    private static void handleRequest(ServerPlayer sender, String body, boolean freeze) {
        MinecraftServer server = activeServer;
        if (server == null) return;
        int sep = body.indexOf(':');
        if (sep < 0) return;
        // 접속 때 발급한 토큰과 다르면 무시 — 채팅창에 손으로 kfcudp:freeze:UUID를 쳐서 등급 검사만
        // 우연히 통과시키는 걸 막는다(TOKEN_MARKER 필드 주석 참고). 진짜 "차단" 버튼을 거쳐야만
        // 이 토큰을 붙여 보낼 수 있다.
        String token = body.substring(0, sep);
        if (!token.equals(sessionTokens.get(sender.getUUID()))) return;
        UUID target;
        try {
            target = UUID.fromString(body.substring(sep + 1));
        } catch (Exception e) {
            return;
        }
        // 등급 0(무등급)은 무조건 거부 — priority(sender) < priority(target)만 보면 둘 다 0일 때
        // 0 < 0이 거짓이라 통과해버려서, 아무 역할도 없는 사람끼리도 서로 얼릴 수 있었다.
        int senderPriority = priority(sender.getUUID());
        if (senderPriority == 0 || senderPriority < priority(target)) return;
        if (freeze) freeze(sender.getUUID(), target, server);
        else unfreezeHolder(sender.getUUID(), target, server);
    }
    *///?} else {
    private static boolean onChatMessage(net.minecraft.network.message.SignedMessage message, ServerPlayerEntity sender,
                                         net.minecraft.network.message.MessageType.Parameters params) {
        String content = message.getSignedContent();
        if (content == null) return true;
        if (isFrozen(sender.getUuid()) && !content.startsWith(FREEZE_MARKER) && !content.startsWith(UNFREEZE_MARKER)) {
            return false; // 동결 중엔 실제 채팅 자체를 막는다
        }
        if (content.startsWith(FREEZE_MARKER)) {
            handleRequest(sender, content.substring(FREEZE_MARKER.length()), true);
            return false;
        }
        if (content.startsWith(UNFREEZE_MARKER)) {
            handleRequest(sender, content.substring(UNFREEZE_MARKER.length()), false);
            return false;
        }
        return true;
    }

    private static void handleRequest(ServerPlayerEntity sender, String body, boolean freeze) {
        MinecraftServer server = activeServer;
        if (server == null) return;
        int sep = body.indexOf(':');
        if (sep < 0) return;
        // 접속 때 발급한 토큰과 다르면 무시 — 채팅창에 손으로 kfcudp:freeze:UUID를 쳐서 등급 검사만
        // 우연히 통과시키는 걸 막는다(TOKEN_MARKER 필드 주석 참고). 진짜 "차단" 버튼을 거쳐야만
        // 이 토큰을 붙여 보낼 수 있다.
        String token = body.substring(0, sep);
        if (!token.equals(sessionTokens.get(sender.getUuid()))) return;
        UUID target;
        try {
            target = UUID.fromString(body.substring(sep + 1));
        } catch (Exception e) {
            return;
        }
        // 등급 0(무등급)은 무조건 거부 — priority(sender) < priority(target)만 보면 둘 다 0일 때
        // 0 < 0이 거짓이라 통과해버려서, 아무 역할도 없는 사람끼리도 서로 얼릴 수 있었다.
        int senderPriority = priority(sender.getUuid());
        if (senderPriority == 0 || senderPriority < priority(target)) return;
        if (freeze) freeze(sender.getUuid(), target, server);
        else unfreezeHolder(sender.getUuid(), target, server);
    }
    //?}

    private static void freeze(UUID freezerUuid, UUID targetUuid, MinecraftServer server) {
        boolean alreadyFrozen = isFrozen(targetUuid);
        //? if >=26.1 {
        /*ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        ServerPlayer freezer = server.getPlayerList().getPlayer(freezerUuid);
        // 온라인이 아니면(이미 나갔거나 다른 이유로 못 찾으면) holders에 아무것도 안 남긴다 —
        // 실제로 얼린 적도 없는데 "유령 동결 예약"이 남아 나중에 재접속만으로 갑자기 얼면 안 된다.
        if (target == null) return;
        holders.computeIfAbsent(targetUuid, k -> ConcurrentHashMap.newKeySet()).add(freezerUuid);
        if (alreadyFrozen) return; // 이미 얼어있음 — 효과는 한 번만 건다
        savedGameMode.put(targetUuid, target.gameMode.getGameModeForPlayer());
        lockedPos.put(targetUuid, new double[]{target.getX(), target.getY(), target.getZ(), target.getYRot()});
        target.closeContainer();
        target.setGameMode(GameType.SPECTATOR);
        target.sendSystemMessage(Component.translatable("instant-p2p.msg.frozen_by",
                freezer != null ? freezer.getName() : Component.literal("?")));
        *///?} else {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        ServerPlayerEntity freezer = server.getPlayerManager().getPlayer(freezerUuid);
        if (target == null) return;
        holders.computeIfAbsent(targetUuid, k -> ConcurrentHashMap.newKeySet()).add(freezerUuid);
        if (alreadyFrozen) return; // 이미 얼어있음 — 효과는 한 번만 건다
        savedGameMode.put(targetUuid, target.interactionManager.getGameMode());
        lockedPos.put(targetUuid, new double[]{target.getX(), target.getY(), target.getZ(), target.getYaw()});
        target.closeHandledScreen();
        target.changeGameMode(GameMode.SPECTATOR);
        target.sendMessage(Text.translatable("instant-p2p.msg.frozen_by",
                freezer != null ? freezer.getName() : Text.literal("?")), false);
        //?}
        LOG.info("[freeze] {} frozen by {}", targetUuid, freezerUuid);
    }

    private static void unfreezeHolder(UUID freezerUuid, UUID targetUuid, MinecraftServer server) {
        Set<UUID> h = holders.get(targetUuid);
        if (h == null) return;
        h.remove(freezerUuid);
        if (!h.isEmpty()) return; // 다른 차단자가 아직 남아있음
        holders.remove(targetUuid);
        restoreIfOnline(targetUuid, server);
    }

    /** 마지막 차단자까지 풀렸을 때 부른다. 대상이 지금 오프라인이면(예: 얼린 채로 나간 사람을 그
     * 사이에 차단 해제한 경우) gamemode를 못 돌려주니, savedGameMode/lockedPos를 지우지 않고
     * 남겨둔다 — 다음 접속(onJoin) 때 "더 이상 얼려있진 않은데 되돌릴 gamemode가 남아있다"로
     * 보고 그때 마저 되돌린다. 여기서 무조건 지워버리면 그 복구 기회 자체가 사라져서 다음 접속에도
     * 관전 모드 그대로 남는다. */
    private static void restoreIfOnline(UUID targetUuid, MinecraftServer server) {
        //? if >=26.1 {
        /*ServerPlayer sp = server.getPlayerList().getPlayer(targetUuid);
        if (sp == null) return;
        GameType prev = savedGameMode.remove(targetUuid);
        lockedPos.remove(targetUuid);
        sp.setGameMode(prev != null ? prev : GameType.SURVIVAL);
        *///?} else {
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(targetUuid);
        if (sp == null) return;
        GameMode prev = savedGameMode.remove(targetUuid);
        lockedPos.remove(targetUuid);
        sp.changeGameMode(prev != null ? prev : GameMode.SURVIVAL);
        //?}
        LOG.info("[freeze] {} unfrozen", targetUuid);
    }

    /** 매 서버 틱 — 관전 모드는 자유비행이 가능해서, 얼어있는 동안은 매 틱 고정 좌표+하늘 보기로 되돌린다. */
    private static void onServerTick(MinecraftServer server) {
        if (holders.isEmpty()) return;
        for (Map.Entry<UUID, Set<UUID>> e : holders.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            double[] pos = lockedPos.get(e.getKey());
            if (pos == null) continue;
            //? if >=26.1 {
            /*ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp == null) continue;
            if (sp.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) sp.setGameMode(GameType.SPECTATOR);
            sp.teleportTo(server.overworld(), pos[0], pos[1], pos[2], java.util.Set.of(), (float) pos[3], -90f, false);
            // closeContainer()를 매 틱 부르면 안 된다 — "컨테이너(상자 등) 화면만" 닫는 게 아니라
            // 클라이언트가 지금 띄운 화면 자체를 닫아버려서, ESC/채팅 입력창까지 열리자마자 바로
            // 닫혀버렸다(호스트가 얼렸을 때 대응할 수단 자체가 없어지는 원인). freeze() 시점에
            // 한 번만 닫는 걸로 충분 — 그 이후 인벤토리 재접근은 FreezeCommandMixin이 명령어
            // 자체를 막아서 이미 차단된다.
            *///?} else {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(e.getKey());
            if (sp == null) continue;
            if (sp.interactionManager.getGameMode() != GameMode.SPECTATOR) sp.changeGameMode(GameMode.SPECTATOR);
            sp.networkHandler.requestTeleport(pos[0], pos[1], pos[2], (float) pos[3], -90f);
            // closeHandledScreen()을 매 틱 부르면 안 된다 — 컨테이너 화면만 닫는 게 아니라 클라이언트가
            // 지금 띄운 화면 자체를 닫아버려서, ESC/채팅 입력창까지 열리자마자 바로 닫혀버렸다(호스트가
            // 얼렸을 때 대응할 수단 자체가 없어지는 원인). freeze() 시점에 한 번만 닫는 걸로 충분 —
            // 그 이후 인벤토리 재접근은 FreezeCommandMixin이 명령어 자체를 막아서 이미 차단된다.
            //?}
        }
    }

    //? if >=26.1 {
    /*private static void onJoin(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        if (isFrozen(id)) {
            // putIfAbsent — 얼린 채로 나갔다 들어오면 지금 gamemode는 이미 SPECTATOR(로그아웃 시점
            // 그대로 저장돼 있음)라, 여기서 무조건 덮어쓰면 원래(동결 전) gamemode를 영영 잃어버린다.
            savedGameMode.putIfAbsent(id, player.gameMode.getGameModeForPlayer());
            lockedPos.putIfAbsent(id, new double[]{player.getX(), player.getY(), player.getZ(), player.getYRot()});
            player.setGameMode(GameType.SPECTATOR);
        } else if (savedGameMode.containsKey(id)) {
            // 오프라인 상태에서 마지막 차단이 풀려 gamemode를 못 돌려준 경우 — restoreIfOnline 주석 참고.
            GameType prev = savedGameMode.remove(id);
            lockedPos.remove(id);
            player.setGameMode(prev);
        }
    }

    private static void onDisconnect(MinecraftServer server, ServerPlayer player) {
        UUID leaving = player.getUUID();
        sessionTokens.remove(leaving); // 다음 접속 때 새로 발급받는다
        for (UUID target : java.util.List.copyOf(holders.keySet())) {
            unfreezeHolder(leaving, target, server);
        }
    }
    *///?} else {
    private static void onJoin(MinecraftServer server, ServerPlayerEntity player) {
        UUID id = player.getUuid();
        if (isFrozen(id)) {
            savedGameMode.putIfAbsent(id, player.interactionManager.getGameMode());
            lockedPos.putIfAbsent(id, new double[]{player.getX(), player.getY(), player.getZ(), player.getYaw()});
            player.changeGameMode(GameMode.SPECTATOR);
        } else if (savedGameMode.containsKey(id)) {
            GameMode prev = savedGameMode.remove(id);
            lockedPos.remove(id);
            player.changeGameMode(prev);
        }
    }

    private static void onDisconnect(MinecraftServer server, ServerPlayerEntity player) {
        UUID leaving = player.getUuid();
        sessionTokens.remove(leaving); // 다음 접속 때 새로 발급받는다
        for (UUID target : java.util.List.copyOf(holders.keySet())) {
            unfreezeHolder(leaving, target, server);
        }
    }
    //?}
}
