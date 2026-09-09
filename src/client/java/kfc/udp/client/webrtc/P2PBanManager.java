package kfc.udp.client.webrtc;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=1.21.9
//import net.minecraft.server.PlayerConfigEntry;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class P2PBanManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-ban");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private static final Path CONFIG_DIR     = Path.of("config", "instant-p2p");
    private static final Path BANNED_PLAYERS = CONFIG_DIR.resolve("banned-players.json");
    private static final Path BANNED_IPS     = CONFIG_DIR.resolve("banned-ips.json");

    private static final Map<String, JsonObject> bannedPlayers = new LinkedHashMap<>();
    private static final Map<String, JsonObject> bannedIps     = new LinkedHashMap<>();

    /**
     * WebRTC 터널을 지나면 모든 조인자가 127.0.0.1 로 보이기 때문에
     * MC 가 보는 로컬 포트 → 실제 원격 IP 매핑을 WebRtcHost 가 등록해 준다.
     */
    private static final Map<Integer, String> tunnelPortToIp = new ConcurrentHashMap<>();
    /** 로그인 시점에 확정된 UUID → 실제 원격 IP (ban-ip 명령에서 사용) */
    private static final Map<UUID, String>    uuidToRealIp   = new ConcurrentHashMap<>();
    /** WebRtcHost가 DataChannel 연결 성사 시 등록 — 실제 IP → 직결/중계 여부 (참여 메시지 접미사용) */
    private static final Map<String, Boolean> connectionTypeByIp = new ConcurrentHashMap<>();

    /** 방 정원 게이트. KfcudpClient 가 방을 열 때 세팅, 닫을 때 0. */
    private static volatile int roomMaxPlayers = 0;

    // -------------------------------------------------------------------------
    // 버전 호환 헬퍼 — 1.21.9에서 GameProfile/isHost/권한 체크 API가 바뀜
    // -------------------------------------------------------------------------

    /** {@code GameProfile#getId()} → {@code id()} (1.21.9+, record화) */
    static UUID profileId(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.id();
        *///?} else {
        return profile.getId();
        //?}
    }

    /** {@code GameProfile#getName()} → {@code name()} (1.21.9+, record화) */
    static String profileName(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.name();
        *///?} else {
        return profile.getName();
        //?}
    }

    /** {@code MinecraftServer#isHost(GameProfile)} → {@code isHost(PlayerConfigEntry)} (1.21.9+) */
    private static boolean isHost(MinecraftServer server, GameProfile profile) {
        if (profile == null) return false;
        //? if >=1.21.9 {
        /*return server.isHost(new PlayerConfigEntry(profile));
        *///?} else {
        return server.isHost(profile);
        //?}
    }

    /** 다른 패키지(예: KfcudpClient)에서 "이 플레이어가 방장인가"를 물을 때 쓰는 공개 버전. */
    public static boolean isHost(MinecraftServer server, ServerPlayerEntity player) {
        return player != null && isHost(server, player.getGameProfile());
    }

    /**
     * {@code ServerCommandSource#hasPermissionLevel(int)} 3단계 변천사:
     *   1.21.5~1.21.8: 인스턴스 메서드 hasPermissionLevel(int)
     *   1.21.9~1.21.10: CommandManager.requirePermissionLevel(int) 정적 팩토리
     *   1.21.11+: CommandManager.requirePermissionLevel(PermissionCheck) — int 오버로드 삭제
     */
    static Predicate<ServerCommandSource> requireAdmin() {
        //? if >=1.21.11 {
        /*return CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK);
        *///?}
        //? if >=1.21.9 <1.21.11 {
        /*return CommandManager.requirePermissionLevel(3);
        *///?}
        //? if <1.21.9 {
        return src -> src.hasPermissionLevel(3);
        //?}
    }

    /**
     * 방장은 op 권한이나 "Allow Commands" 설정과 무관하게 유저 제어 명령을
     * 쓸 수 있어야 한다 — 안 그러면 Allow Commands를 꺼둔 방장 본인도 ban/whitelist를
     * 못 쓰게 되고, 방을 통째로 닫는 것 말곤 할 수 있는 게 없어진다.
     */
    static Predicate<ServerCommandSource> requireAdminOrHost() {
        Predicate<ServerCommandSource> admin = requireAdmin();
        return src -> {
            MinecraftServer server = src.getServer();
            return (server != null && src.getEntity() instanceof ServerPlayerEntity sp && isHost(server, sp))
                    || admin.test(src);
        };
    }

    /** {@link #lookupProfile}의 결과 — 버전 무관하게 uuid+name만 필요할 때 쓰는 최소 표현. */
    public record ProfileLookup(UUID id, String name) {}

    /**
     * 접속 이력 없는(오프라인) 플레이어도 이름으로 찾는다 — 바닐라
     * {@code GameProfileArgumentType}이 쓰는 것과 동일한 usercache.json 캐시 →
     * Mojang API 경로. 서버 스레드에서 블로킹 호출이라(바닐라도 동일) 커맨드
     * 실행 컨텍스트에서만 불러야 한다.
     * <p>
     * 1.21.9부터 캐시 접근 경로가 {@code getUserCache()} → {@code getApiServices().nameToIdCache()}로,
     * 반환 타입이 {@code GameProfile} → {@code PlayerConfigEntry}로 바뀌었다.
     */
    static ProfileLookup lookupProfile(MinecraftServer server, String name) {
        //? if >=1.21.9 {
        /*return server.getApiServices().nameToIdCache().findByName(name)
                .map(e -> new ProfileLookup(e.id(), e.name()))
                .orElse(null);*/
        //?} else {
        return server.getUserCache().findByName(name)
                .map(p -> new ProfileLookup(profileId(p), profileName(p)))
                .orElse(null);
        //?}
    }

    // -------------------------------------------------------------------------
    // 자동완성 제공자
    // -------------------------------------------------------------------------

    /** 현재 접속 중인 플레이어 이름 자동완성 */
    private static final SuggestionProvider<ServerCommandSource> ONLINE_PLAYERS =
            (ctx, builder) -> {
                for (ServerPlayerEntity sp : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                    builder.suggest(profileName(sp.getGameProfile()));
                }
                return builder.buildFuture();
            };

    /** 밴된 플레이어 이름 자동완성 */
    private static final SuggestionProvider<ServerCommandSource> BANNED_PLAYER_NAMES =
            (ctx, builder) -> {
                for (JsonObject o : bannedPlayers.values()) {
                    if (o.has("name")) builder.suggest(o.get("name").getAsString());
                }
                return builder.buildFuture();
            };

    /** 밴된 IP 자동완성 */
    private static final SuggestionProvider<ServerCommandSource> BANNED_IPS_LIST =
            (ctx, builder) -> {
                for (String ip : bannedIps.keySet()) {
                    builder.suggest(ip);
                }
                return builder.buildFuture();
            };

    // -------------------------------------------------------------------------
    // 데이터 로드 / 저장
    // -------------------------------------------------------------------------

    public static void load() {
        bannedPlayers.clear();
        bannedIps.clear();
        loadMap(BANNED_PLAYERS, bannedPlayers, "uuid");
        loadMap(BANNED_IPS,     bannedIps,     "ip");
    }

    private static void loadMap(Path path, Map<String, JsonObject> map, String key) {
        if (!Files.exists(path)) return;
        try (Reader r = new FileReader(path.toFile())) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (o.has(key)) map.put(o.get(key).getAsString(), o);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] load failed: {}", e.getMessage());
        }
    }

    private static void save(Path path, Map<String, JsonObject> map) {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonArray arr = new JsonArray();
            map.values().forEach(arr::add);
            try (Writer w = new FileWriter(path.toFile())) {
                GSON.toJson(arr, w);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] save failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 밴 데이터 조작
    // -------------------------------------------------------------------------

    public static void banPlayer(String uuid, String name, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        o.addProperty("created", FMT.format(Instant.now()));
        o.addProperty("source", "instant-p2p");
        o.addProperty("expires", "forever");
        o.addProperty("reason", reason != null ? reason : "Banned by operator.");
        bannedPlayers.put(uuid, o);
        save(BANNED_PLAYERS, bannedPlayers);
    }

    public static void banIp(String ip, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("ip", ip);
        o.addProperty("created", FMT.format(Instant.now()));
        o.addProperty("source", "instant-p2p");
        o.addProperty("expires", "forever");
        o.addProperty("reason", reason != null ? reason : "Banned by operator.");
        bannedIps.put(ip, o);
        save(BANNED_IPS, bannedIps);
    }

    public static void pardonPlayer(String name) {
        bannedPlayers.entrySet().removeIf(e ->
                e.getValue().get("name").getAsString().equalsIgnoreCase(name));
        save(BANNED_PLAYERS, bannedPlayers);
    }

    public static void pardonIp(String ip) {
        bannedIps.remove(ip);
        save(BANNED_IPS, bannedIps);
    }

    public static boolean isPlayerBanned(String uuid) {
        return bannedPlayers.containsKey(uuid);
    }

    public static boolean isIpBanned(String ip) {
        return bannedIps.containsKey(ip);
    }

    public static String getBanReason(String uuid) {
        JsonObject o = bannedPlayers.get(uuid);
        return o != null ? o.get("reason").getAsString() : null;
    }

    public static String getIpBanReason(String ip) {
        JsonObject o = bannedIps.get(ip);
        return o != null ? o.get("reason").getAsString() : null;
    }

    // -------------------------------------------------------------------------
    // 터널 포트 ↔ 실제 IP 매핑 (WebRtcHost 가 호출)
    // -------------------------------------------------------------------------

    public static void registerTunnelPort(int localPort, String realIp) {
        if (realIp != null && !realIp.isEmpty()) tunnelPortToIp.put(localPort, realIp);
    }

    public static void unregisterTunnelPort(int localPort) {
        tunnelPortToIp.remove(localPort);
    }

    /** WebRTC 터널 뒤의 진짜 IP. 매핑이 없으면 소켓 주소 그대로. */
    private static String resolveRealIp(SocketAddress address) {
        if (!(address instanceof InetSocketAddress isa)) return null;
        String mapped = tunnelPortToIp.get(isa.getPort());
        if (mapped != null) return mapped;
        return isa.getAddress() != null ? isa.getAddress().getHostAddress() : null;
    }

    /** 로그인 시 기록해 둔 실제 IP (없으면 null) */
    public static String realIpOf(UUID uuid) {
        return uuidToRealIp.get(uuid);
    }

    public static void registerConnectionType(String realIp, boolean usesRelay) {
        if (realIp != null) connectionTypeByIp.put(realIp, usesRelay);
    }

    /** 참여 메시지에 (직결 통신)/(중계 통신) 접미사를 붙일 때 사용. null이면 webrtc 터널이 아니거나 아직 모름. */
    public static Boolean connectionTypeOf(UUID uuid) {
        String ip = realIpOf(uuid);
        return ip != null ? connectionTypeByIp.get(ip) : null;
    }

    public static void setRoomMaxPlayers(int max) {
        roomMaxPlayers = max;
    }

    /** 0이면 방이 닫혀 있거나 정원 게이트가 없다는 뜻. */
    public static int getRoomMaxPlayers() {
        return roomMaxPlayers;
    }

    // -------------------------------------------------------------------------
    // 로그인(LOGIN) 단계 밴 체크 — PlayerManagerMixin 에서 호출
    // -------------------------------------------------------------------------

    /**
     * 바닐라 {@code PlayerManager#checkCanJoin} 과 동일한 계약.
     * 거부 사유 Text 를 반환하면 플레이어는 월드에 스폰되기 전에 끊기므로
     * join/left 채팅 메시지가 남지 않는다. 통과면 null.
     */
    public static Text checkCanJoin(MinecraftServer server, SocketAddress address, GameProfile profile) {
        if (server == null || profile == null) return null;
        // 방장(싱글플레이 오너)은 어떤 경우에도 막지 않는다
        if (isHost(server, profile)) return null;

        String realIp = resolveRealIp(address);
        if (realIp != null) uuidToRealIp.put(profileId(profile), realIp);

        // IP는 로그에 남기지 않는다 — 방장이 버그 리포트로 로그를 그대로 공유하면
        // 접속자의 실제 IP가 텍스트로 박제된다. 닉네임만으로 충분히 추적 가능.
        String uuid = profileId(profile).toString();
        if (isPlayerBanned(uuid)) {
            LOG.info("[instant-p2p] login refused (banned): {}", profileName(profile));
            return Text.literal("§cYou are banned: " + getBanReason(uuid));
        }
        if (!P2PWhitelistManager.canJoin(uuid)) {
            LOG.info("[instant-p2p] login refused (not whitelisted): {}", profileName(profile));
            return Text.translatable("kfcudp.msg.not_whitelisted");
        }
        if (realIp != null && isIpBanned(realIp)) {
            LOG.info("[instant-p2p] login refused (ip banned): {}", profileName(profile));
            return Text.literal("§cYour IP is banned: " + getIpBanReason(realIp));
        }
        // 정원 초과도 같은 지점에서 막아야 join/left 로그가 안 남는다
        int max = roomMaxPlayers;
        if (max > 0 && server.getCurrentPlayerCount() >= max) {
            return Text.translatable("kfcudp.msg.room_full", max);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // 커맨드 등록
    // -------------------------------------------------------------------------

    /** openToLan 후 커맨드 매니저 재초기화로 날아간 명령어 재등록 */
    public static void reregisterToDispatcher(MinecraftServer server) {
        var dispatcher = server.getCommandManager().getDispatcher();
        registerBanCommands(dispatcher);
    }

    private static void registerBanCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        // ban <player> [<reason>]
        dispatcher.register(literal("ban")
                .requires(requireAdminOrHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYERS)
                        .executes(ctx -> executeBan(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBan(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason"))))));

        // ban-ip <player|ip> [<reason>]
        dispatcher.register(literal("ban-ip")
                .requires(requireAdminOrHost())
                .then(argument("target", StringArgumentType.word())
                        .executes(ctx -> executeBanIp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "target"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBanIp(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target"),
                                        StringArgumentType.getString(ctx, "reason"))))));

        // pardon <player>
        dispatcher.register(literal("pardon")
                .requires(requireAdminOrHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(BANNED_PLAYER_NAMES)
                        .executes(ctx -> executePardon(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))));

        // pardon-ip <ip>
        dispatcher.register(literal("pardon-ip")
                .requires(requireAdminOrHost())
                .then(argument("ip", StringArgumentType.word())
                        .suggests(BANNED_IPS_LIST)
                        .executes(ctx -> executePardonIp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "ip")))));

        // kick <player> [<reason>]
        dispatcher.register(literal("kick")
                .requires(requireAdminOrHost())
                .then(argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYERS)
                        .executes(ctx -> executeKick(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeKick(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason"))))));
    }

    public static void registerCommands() {
        load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerBanCommands(dispatcher));
    }

    // -------------------------------------------------------------------------
    // 커맨드 실행
    // -------------------------------------------------------------------------

    private static int executeBan(ServerCommandSource src, String name, String reason) {
        MinecraftServer server = src.getServer();

        // LAN이 열려 있지 않으면 ban 불가
        if (!server.isRemote()) {
            src.sendFeedback(() -> Text.literal("§cCannot ban players while not hosting a room."), false);
            return 0;
        }

        // 접속 중이 아니어도 Mojang API/usercache로 조회 (바닐라 ban과 동일)
        ProfileLookup lookup = lookupProfile(server, name);
        if (lookup == null) {
            src.sendFeedback(() -> Text.literal("§cCould not find a player named " + name), false);
            return 0;
        }

        // 서버 오너(호스터)는 ban 불가
        GameProfile syntheticProfile = new GameProfile(lookup.id(), lookup.name());
        if (isHost(server, syntheticProfile)) {
            src.sendFeedback(() -> Text.literal("§cYou cannot ban the room owner."), false);
            return 0;
        }

        String r = reason != null ? reason : "Banned by operator.";
        banPlayer(lookup.id().toString(), lookup.name(), r);
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(lookup.id());
        if (online != null) {
            online.networkHandler.disconnect(Text.literal("§cYou have been banned: " + r));
        }
        src.sendFeedback(() -> Text.literal("§aBanned " + lookup.name()), false);
        return 1;
    }

    private static int executeBanIp(ServerCommandSource src, String target, String reason) {
        MinecraftServer server = src.getServer();

        // LAN이 열려 있지 않으면 ban-ip 불가
        if (!server.isRemote()) {
            src.sendFeedback(() -> Text.literal("§cCannot ban players while not hosting a room."), false);
            return 0;
        }

        // 플레이어 이름으로 입력된 경우 IP로 변환, 아니면 직접 IP로 취급
        String ip = target;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(target);
        if (p != null) {
            // 서버 오너(호스터)는 ban-ip 불가
            if (isHost(server, p.getGameProfile())) {
                src.sendFeedback(() -> Text.literal("§cYou cannot ban the room owner."), false);
                return 0;
            }
            // p.getIp() 는 터널 때문에 항상 127.0.0.1 → 로그인 때 기록한 실제 IP 사용
            ip = realIpOf(p.getUuid());
            if (ip == null || ip.startsWith("127.")) {
                src.sendFeedback(() -> Text.literal("§cCannot resolve the real IP of " + target + "."), false);
                return 0;
            }
        }

        String r = reason != null ? reason : "Banned by operator.";
        banIp(ip, r);
        final String finalIp = ip;
        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            if (isHost(server, sp.getGameProfile())) continue;
            if (finalIp.equals(realIpOf(sp.getUuid()))) {
                sp.networkHandler.disconnect(Text.literal("§cYour IP has been banned: " + r));
            }
        }
        src.sendFeedback(() -> Text.literal("§aBanned IP: " + finalIp), false);
        return 1;
    }

    private static int executePardon(ServerCommandSource src, String name) {
        pardonPlayer(name);
        src.sendFeedback(() -> Text.literal("§aUnbanned " + name), false);
        return 1;
    }

    private static int executePardonIp(ServerCommandSource src, String ip) {
        pardonIp(ip);
        src.sendFeedback(() -> Text.literal("§aUnbanned IP: " + ip), false);
        return 1;
    }

    private static int executeKick(ServerCommandSource src, String name, String reason) {
        MinecraftServer server = src.getServer();

        if (!server.isRemote()) {
            src.sendFeedback(() -> Text.literal("§cCannot kick players while not hosting a room."), false);
            return 0;
        }

        ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
        if (target == null) {
            src.sendFeedback(() -> Text.literal("§cCould not find a player named " + name), false);
            return 0;
        }
        if (isHost(server, target.getGameProfile())) {
            src.sendFeedback(() -> Text.literal("§cYou cannot kick the room owner."), false);
            return 0;
        }

        String r = reason != null ? reason : "Kicked by an operator.";
        target.networkHandler.disconnect(Text.literal("§c" + r));
        src.sendFeedback(() -> Text.literal("§aKicked " + profileName(target.getGameProfile())), false);
        return 1;
    }
}