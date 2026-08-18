package com.hexvane.newlife.spawn;

import com.hypixel.hytale.math.vector.Transform;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class WorldSpawnState {
    public boolean configured;
    public boolean randomPerPlayer;
    public boolean randomEveryDeath;
    public boolean ignoreBeds;
    @Nullable
    public SpawnMode spawnMode;
    @Nullable
    public SpawnRegion region;
    @Nullable
    public DungeonKind dungeonKind;
    @Nullable
    public Transform worldSpawn;
    @Nonnull
    public final Map<UUID, PlayerSpawnState> players = new ConcurrentHashMap<>();

    @Nonnull
    public PlayerSpawnState player(@Nonnull UUID playerId) {
        return players.computeIfAbsent(playerId, ignored -> new PlayerSpawnState());
    }

    public boolean needsChoice(@Nonnull UUID playerId) {
        if (!configured) {
            return true;
        }
        if (randomPerPlayer) {
            return !player(playerId).hasChosen;
        }
        return false;
    }

    @Nonnull
    public SpawnRequest worldRequest() {
        return new SpawnRequest(
            spawnMode == null ? SpawnMode.ABOVE_GROUND : spawnMode,
            region,
            dungeonKind,
            randomPerPlayer,
            randomEveryDeath,
            ignoreBeds
        );
    }
}
