package kfc.udp.client.webrtc;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 접속자 목록(BlockedPlayersScreen, online=true)에서 별도로 표시할 "즐겨찾기" 플레이어 —
 * 밴/화이트리스트와 달리 서버(방)에 아무 영향도 주지 않는 순수 로컬 취향 표시다. 즐겨찾기한
 * 유저는 그 목록에서 항상 맨 앞에 고정된다(BlockedPlayersScreen.refreshGrid 정렬 참고).
 * <p>
 * 저장 방식은 {@link P2PBanManager}의 banned-players.json과 같은 패턴(UUID 키의
 * JsonObject 맵, config/instant-p2p/favorite-players.json)이다 — 서버 명령어나 로그인
 * 단계 체크가 없다는 점만 다르다.
 */
public class P2PFavoriteManager {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-favorite");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_DIR        = Path.of("config", "instant-p2p");
    private static final Path FAVORITE_PLAYERS  = CONFIG_DIR.resolve("favorite-players.json");

    private static final Map<String, JsonObject> favorites = new LinkedHashMap<>();

    /** 즐겨찾기 목록이 바뀔 때마다 오른다 — BlockedPlayersScreen이 매 tick 다시 정렬하지 않고
     * 이 값이 바뀌었을 때만 새로 정렬하는 데 쓴다(P2PBanManager.banListVersion과 같은 용도). */
    private static volatile int favoritesVersion = 0;

    public static int favoritesVersion() {
        return favoritesVersion;
    }

    public static synchronized void load() {
        favorites.clear();
        if (!Files.exists(FAVORITE_PLAYERS)) return;
        try (Reader r = new FileReader(FAVORITE_PLAYERS.toFile())) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("uuid")) favorites.put(o.get("uuid").getAsString(), o);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] favorite list load failed: {}", e.getMessage());
        }
        favoritesVersion++;
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonArray arr = new JsonArray();
            favorites.values().forEach(arr::add);
            try (Writer w = new FileWriter(FAVORITE_PLAYERS.toFile())) {
                GSON.toJson(arr, w);
            }
        } catch (Exception e) {
            LOG.warn("[instant-p2p] favorite list save failed: {}", e.getMessage());
        }
    }

    public static synchronized boolean isFavorite(String uuid) {
        return favorites.containsKey(uuid);
    }

    /** 즐겨찾기 등록 — 이름은 목록에 표시할 용도로만 쓰고(밴 목록과 같은 이유), 이미 즐겨찾기면
     * 아무 일도 안 한다(같은 사람을 이름이 바뀐 채 다시 눌러도 uuid 기준이라 안전). */
    public static synchronized void addFavorite(String uuid, String name) {
        if (favorites.containsKey(uuid)) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        favorites.put(uuid, o);
        favoritesVersion++;
        save();
    }

    public static synchronized void removeFavorite(String uuid) {
        if (favorites.remove(uuid) == null) return;
        favoritesVersion++;
        save();
    }

    public static void toggleFavorite(String uuid, String name) {
        if (isFavorite(uuid)) {
            removeFavorite(uuid);
        } else {
            addFavorite(uuid, name);
        }
    }

    public record FavoriteEntry(String uuid, String name) {}

    public static synchronized List<FavoriteEntry> listFavorites() {
        List<FavoriteEntry> list = new ArrayList<>();
        for (JsonObject o : favorites.values()) {
            list.add(new FavoriteEntry(o.get("uuid").getAsString(), o.has("name") ? o.get("name").getAsString() : ""));
        }
        return list;
    }
}
