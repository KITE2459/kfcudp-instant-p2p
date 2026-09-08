package kfc.udp.client.webrtc;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * 바닐라 화이트리스트(whitelist on/off/add/remove/list)를 그대로 옮겨온 것.
 * {@code add}는 {@link P2PBanManager#lookupProfile}로 접속 이력 없는 플레이어도
 * Mojang API/usercache.json 캐시를 통해 이름만으로 찾는다(바닐라와 동일).
 * <p>
 * 방장은 {@link P2PBanManager#checkCanJoin}에서 이미 모든 체크보다 먼저 통과되므로
 * 화이트리스트가 켜져 있어도 항상 들어올 수 있다.
 */
public class P2PWhitelistManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-whitelist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_DIR      = Path.of("config", "instant-p2p");
    private static final Path WHITELIST_FILE  = CONFIG_DIR.resolve("whitelist.json");

    /** uuid → {uuid,name} */
    private static final Map<String, JsonObject> whitelist = new LinkedHashMap<>();
    /** 명단은 저장되지만 on/off는 방 열 때마다 꺼진 상태로 시작한다 — 세션 간 지속 안 함. */
    private static volatile boolean enabled = false;

    // -------------------------------------------------------------------------
    // 자동완성 제공자
    // -------------------------------------------------------------------------

    private static final SuggestionProvider<ServerCommandSource> ONLINE_PLAYERS =
            (ctx, builder) -> {
                for (ServerPlayerEntity sp : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                    builder.suggest(P2PBanManager.profileName(sp.getGameProfile()));
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<ServerCommandSource> WHITELISTED_PLAYER_NAMES =
            (ctx, builder) -> {
                for (JsonObject o : whitelist.values()) {
                    if (o.has("name")) builder.suggest(o.get("name").getAsString());
                }
                return builder.buildFuture();
            };

    // -------------------------------------------------------------------------
    // 데이터 로드 / 저장
    // -------------------------------------------------------------------------

    public static void load() {
        whitelist.clear();
        enabled = false;
        if (!Files.exists(WHITELIST_FILE)) return;
        try (Reader r = new FileReader(WHITELIST_FILE.toFile())) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.has("uuid")) whitelist.put(o.get("uuid").getAsString(), o);
                }
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] whitelist load failed: {}", e.getMessage());
        }
    }

    private static void saveWhitelist() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonArray arr = new JsonArray();
            whitelist.values().forEach(arr::add);
            try (Writer w = new FileWriter(WHITELIST_FILE.toFile())) {
                GSON.toJson(arr, w);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] whitelist save failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 화이트리스트 조작
    // -------------------------------------------------------------------------

    public static void addPlayer(String uuid, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        whitelist.put(uuid, o);
        saveWhitelist();
    }

    public static boolean removePlayer(String name) {
        boolean removed = whitelist.entrySet().removeIf(e ->
                e.getValue().get("name").getAsString().equalsIgnoreCase(name));
        if (removed) saveWhitelist();
        return removed;
    }

    public static boolean isWhitelisted(String uuid) {
        return whitelist.containsKey(uuid);
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** {@link P2PBanManager#checkCanJoin}에서 호출 — 화이트리스트가 꺼져 있으면 항상 통과. */
    public static boolean canJoin(String uuid) {
        return !enabled || whitelist.containsKey(uuid);
    }

    // -------------------------------------------------------------------------
    // 커맨드 등록
    // -------------------------------------------------------------------------

    /** openToLan 후 커맨드 매니저 재초기화로 날아간 명령어 재등록 */
    public static void reregisterToDispatcher(MinecraftServer server) {
        registerCommands(server.getCommandManager().getDispatcher());
    }

    public static void registerCommands() {
        load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("whitelist")
                .requires(P2PBanManager.requireAdminOrHost())
                .then(literal("on").executes(ctx -> executeSetEnabled(ctx.getSource(), true)))
                .then(literal("off").executes(ctx -> executeSetEnabled(ctx.getSource(), false)))
                .then(literal("list").executes(ctx -> executeList(ctx.getSource())))
                .then(literal("add")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .executes(ctx -> executeAdd(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(literal("remove")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(WHITELISTED_PLAYER_NAMES)
                                .executes(ctx -> executeRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"))))));
    }

    // -------------------------------------------------------------------------
    // 커맨드 실행
    // -------------------------------------------------------------------------

    private static int executeSetEnabled(ServerCommandSource src, boolean value) {
        setEnabled(value);
        src.sendFeedback(() -> Text.literal(value
                ? "§aWhitelist is now enabled."
                : "§aWhitelist is now disabled."), false);
        return 1;
    }

    private static int executeList(ServerCommandSource src) {
        if (whitelist.isEmpty()) {
            src.sendFeedback(() -> Text.literal("§7There are no whitelisted players."), false);
            return 0;
        }
        StringBuilder names = new StringBuilder();
        for (JsonObject o : whitelist.values()) {
            if (names.length() > 0) names.append(", ");
            names.append(o.get("name").getAsString());
        }
        src.sendFeedback(() -> Text.literal(
                "§7There are " + whitelist.size() + " whitelisted player(s): " + names), false);
        return whitelist.size();
    }

    private static int executeAdd(ServerCommandSource src, String name) {
        MinecraftServer server = src.getServer();
        // 접속 중이 아니어도 Mojang API/usercache로 조회 (바닐라 whitelist add와 동일)
        P2PBanManager.ProfileLookup lookup = P2PBanManager.lookupProfile(server, name);
        if (lookup == null) {
            src.sendFeedback(() -> Text.literal("§cCould not find a player named " + name), false);
            return 0;
        }

        addPlayer(lookup.id().toString(), lookup.name());
        src.sendFeedback(() -> Text.literal("§aAdded " + lookup.name() + " to the whitelist"), false);
        return 1;
    }

    private static int executeRemove(ServerCommandSource src, String name) {
        boolean removed = removePlayer(name);
        if (removed) {
            src.sendFeedback(() -> Text.literal("§aRemoved " + name + " from the whitelist"), false);
            return 1;
        } else {
            src.sendFeedback(() -> Text.literal("§cPlayer is not whitelisted: " + name), false);
            return 0;
        }
    }
}
