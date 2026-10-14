package com.hexvane.newlife.spawn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class WorldSpawnStore {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "newlife_spawns.json";

    private Path dataFile;
    private final Map<String, WorldSpawnState> worlds = new ConcurrentHashMap<>();
    /** State for worlds without a start of their own. Kept for the session only and never saved. */
    private final Map<String, WorldSpawnState> temporaryWorlds = new ConcurrentHashMap<>();

    public void initialize(@Nonnull Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            dataFile = dataDirectory.resolve(FILE_NAME);
            load();
        } catch (IOException e) {
            LOGGER.atWarning().log("Could not load New Life spawn data: %s", e.getMessage());
        }
    }

    @Nonnull
    public WorldSpawnState world(@Nonnull World world) {
        Map<String, WorldSpawnState> states = hasOwnStart(world) ? worlds : temporaryWorlds;
        return states.computeIfAbsent(worldKey(world), ignored -> new WorldSpawnState());
    }

    /**
     * Whether a world keeps a start of its own. An instance, or any other world that is thrown away when it closes,
     * does not: every copy is a new world name, so it would look like a world where nobody has chosen yet each time
     * a player enters one.
     */
    public static boolean hasOwnStart(@Nonnull World world) {
        WorldConfig config = world.getWorldConfig();
        return InstanceWorldConfig.get(config) == null && !config.isDeleteOnRemove() && !config.isDeleteOnUniverseStart();
    }

    @Nonnull
    public static String worldKey(@Nonnull World world) {
        return world.getName() + "#" + world.getWorldConfig().getSeed();
    }

    public void updateTickboxes(
        @Nonnull World world,
        @Nonnull UUID playerId,
        boolean randomEveryDeath,
        boolean ignoreBeds
    ) {
        WorldSpawnState state = world(world);
        synchronized (state) {
            state.randomEveryDeath = randomEveryDeath;
            state.ignoreBeds = ignoreBeds;
            PlayerSpawnState playerState = state.player(playerId);
            playerState.randomEveryDeath = randomEveryDeath;
            playerState.ignoreBeds = ignoreBeds;
        }
        save();
    }

    public synchronized void save() {
        if (dataFile == null) {
            return;
        }
        JsonObject root = new JsonObject();
        JsonObject worldsJson = new JsonObject();
        for (Map.Entry<String, WorldSpawnState> entry : worlds.entrySet()) {
            worldsJson.add(entry.getKey(), writeWorld(entry.getValue()));
        }
        root.add("worlds", worldsJson);
        try {
            Files.writeString(dataFile, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.atWarning().log("Could not save New Life spawn data: %s", e.getMessage());
        }
    }

    private void load() throws IOException {
        worlds.clear();
        if (dataFile == null || !Files.exists(dataFile)) {
            return;
        }
        String raw = Files.readString(dataFile, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return;
        }
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("worlds") || !root.get("worlds").isJsonObject()) {
            return;
        }
        JsonObject worldsJson = root.getAsJsonObject("worlds");
        for (String worldName : worldsJson.keySet()) {
            worlds.put(worldName, readWorld(worldsJson.getAsJsonObject(worldName)));
        }
    }

    @Nonnull
    private static JsonObject writeWorld(@Nonnull WorldSpawnState state) {
        JsonObject json = new JsonObject();
        json.addProperty("configured", state.configured);
        json.addProperty("randomPerPlayer", state.randomPerPlayer);
        json.addProperty("randomEveryDeath", state.randomEveryDeath);
        json.addProperty("ignoreBeds", state.ignoreBeds);
        if (state.spawnMode != null) {
            json.addProperty("spawnMode", state.spawnMode.name());
        }
        if (state.region != null) {
            json.addProperty("region", state.region.name());
        }
        if (state.dungeonKind != null) {
            json.addProperty("dungeonKind", state.dungeonKind.name());
        }
        writeTransform(json, "worldSpawn", state.worldSpawn);
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, PlayerSpawnState> entry : state.players.entrySet()) {
            players.add(entry.getKey().toString(), writePlayer(entry.getValue()));
        }
        json.add("players", players);
        return json;
    }

    @Nonnull
    private static WorldSpawnState readWorld(@Nonnull JsonObject json) {
        WorldSpawnState state = new WorldSpawnState();
        state.configured = bool(json, "configured", false);
        state.randomPerPlayer = bool(json, "randomPerPlayer", false);
        state.randomEveryDeath = bool(json, "randomEveryDeath", false);
        state.ignoreBeds = bool(json, "ignoreBeds", false);
        state.spawnMode = enumValue(json, "spawnMode", SpawnMode.class);
        state.region = enumValue(json, "region", SpawnRegion.class);
        state.dungeonKind = enumValue(json, "dungeonKind", DungeonKind.class);
        state.worldSpawn = readTransform(json, "worldSpawn");
        if (json.has("players") && json.get("players").isJsonObject()) {
            JsonObject players = json.getAsJsonObject("players");
            for (String key : players.keySet()) {
                try {
                    state.players.put(UUID.fromString(key), readPlayer(players.getAsJsonObject(key)));
                } catch (IllegalArgumentException ignored) {
                    // Skip bad player ids.
                }
            }
        }
        return state;
    }

    @Nonnull
    private static JsonObject writePlayer(@Nonnull PlayerSpawnState state) {
        JsonObject json = new JsonObject();
        json.addProperty("hasChosen", state.hasChosen);
        json.addProperty("menuShown", state.menuShown);
        json.addProperty("randomEveryDeath", state.randomEveryDeath);
        json.addProperty("ignoreBeds", state.ignoreBeds);
        if (state.spawnMode != null) {
            json.addProperty("spawnMode", state.spawnMode.name());
        }
        if (state.region != null) {
            json.addProperty("region", state.region.name());
        }
        if (state.dungeonKind != null) {
            json.addProperty("dungeonKind", state.dungeonKind.name());
        }
        writeTransform(json, "lastTransform", state.lastTransform);
        return json;
    }

    @Nonnull
    private static PlayerSpawnState readPlayer(@Nonnull JsonObject json) {
        PlayerSpawnState state = new PlayerSpawnState();
        state.hasChosen = bool(json, "hasChosen", false);
        state.menuShown = bool(json, "menuShown", false);
        state.randomEveryDeath = bool(json, "randomEveryDeath", false);
        state.ignoreBeds = bool(json, "ignoreBeds", false);
        state.spawnMode = enumValue(json, "spawnMode", SpawnMode.class);
        state.region = enumValue(json, "region", SpawnRegion.class);
        state.dungeonKind = enumValue(json, "dungeonKind", DungeonKind.class);
        state.lastTransform = readTransform(json, "lastTransform");
        return state;
    }

    private static void writeTransform(@Nonnull JsonObject json, @Nonnull String key, @Nullable Transform transform) {
        if (transform == null) {
            return;
        }
        JsonObject object = new JsonObject();
        object.addProperty("x", transform.getPosition().x);
        object.addProperty("y", transform.getPosition().y);
        object.addProperty("z", transform.getPosition().z);
        json.add(key, object);
    }

    @Nullable
    private static Transform readTransform(@Nonnull JsonObject json, @Nonnull String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            return null;
        }
        JsonObject object = json.getAsJsonObject(key);
        if (!object.has("x") || !object.has("y") || !object.has("z")) {
            return null;
        }
        return new Transform(object.get("x").getAsDouble(), object.get("y").getAsDouble(), object.get("z").getAsDouble());
    }

    private static boolean bool(@Nonnull JsonObject json, @Nonnull String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }

    @Nullable
    private static <T extends Enum<T>> T enumValue(@Nonnull JsonObject json, @Nonnull String key, @Nonnull Class<T> type) {
        if (!json.has(key)) {
            return null;
        }
        try {
            return Enum.valueOf(type, json.get(key).getAsString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
