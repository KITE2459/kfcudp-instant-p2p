package kfc.udp.client.webrtc;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

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

    // -------------------------------------------------------------------------
    // 자동완성 제공자
    // -------------------------------------------------------------------------

    /** 현재 접속 중인 플레이어 이름 자동완성 */
    private static final SuggestionProvider<ServerCommandSource> ONLINE_PLAYERS =
            (ctx, builder) -> {
                for (ServerPlayerEntity sp : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                    builder.suggest(sp.getGameProfile().getName());
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
    // 접속 시 밴 체크
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    public static void checkOnJoin(ServerPlayerEntity player) {
        String uuid = player.getUuidAsString();
        String ip   = player.getIp();
        if (isPlayerBanned(uuid)) {
            player.networkHandler.disconnect(
                    Text.literal("§cYou are banned: " + getBanReason(uuid)));
            return;
        }
        if (isIpBanned(ip)) {
            player.networkHandler.disconnect(
                    Text.literal("§cYour IP is banned: " + getIpBanReason(ip)));
        }
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
                .requires(src -> src.hasPermissionLevel(3))
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
                .requires(src -> src.hasPermissionLevel(3))
                .then(argument("target", StringArgumentType.word())
                        .executes(ctx -> executeBanIp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "target"), null))
                        .then(argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBanIp(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target"),
                                        StringArgumentType.getString(ctx, "reason"))))));

        // pardon <player>
        dispatcher.register(literal("pardon")
                .requires(src -> src.hasPermissionLevel(3))
                .then(argument("player", StringArgumentType.word())
                        .suggests(BANNED_PLAYER_NAMES)
                        .executes(ctx -> executePardon(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))));

        // pardon-ip <ip>
        dispatcher.register(literal("pardon-ip")
                .requires(src -> src.hasPermissionLevel(3))
                .then(argument("ip", StringArgumentType.word())
                        .suggests(BANNED_IPS_LIST)
                        .executes(ctx -> executePardonIp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "ip")))));
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

        ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
        if (target == null) {
            src.sendFeedback(() -> Text.literal("§cPlayer not found: " + name), false);
            return 0;
        }

        // 서버 오너(호스터)는 ban 불가
        if (server.isHost(target.getGameProfile())) {
            src.sendFeedback(() -> Text.literal("§cYou cannot ban the room owner."), false);
            return 0;
        }

        String r = reason != null ? reason : "Banned by operator.";
        banPlayer(target.getUuidAsString(), name, r);
        target.networkHandler.disconnect(Text.literal("§cYou have been banned: " + r));
        src.sendFeedback(() -> Text.literal("§aBanned " + name), false);
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
            if (server.isHost(p.getGameProfile())) {
                src.sendFeedback(() -> Text.literal("§cYou cannot ban the room owner."), false);
                return 0;
            }
            ip = p.getIp();
        }

        String r = reason != null ? reason : "Banned by operator.";
        banIp(ip, r);
        final String finalIp = ip;
        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            if (sp.getIp().equals(finalIp)) {
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
}