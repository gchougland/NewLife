package com.hexvane.newlife.spawn;

import com.hypixel.hytale.math.vector.Transform;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PlayerSpawnState {
    public boolean hasChosen;
    public boolean menuShown;
    @Nullable
    public SpawnMode spawnMode;
    @Nullable
    public SpawnRegion region;
    @Nullable
    public DungeonKind dungeonKind;
    public boolean randomEveryDeath;
    public boolean ignoreBeds;
    @Nullable
    public Transform lastTransform;

    public PlayerSpawnState copy() {
        PlayerSpawnState copy = new PlayerSpawnState();
        copy.hasChosen = hasChosen;
        copy.menuShown = menuShown;
        copy.spawnMode = spawnMode;
        copy.region = region;
        copy.dungeonKind = dungeonKind;
        copy.randomEveryDeath = randomEveryDeath;
        copy.ignoreBeds = ignoreBeds;
        copy.lastTransform = lastTransform == null ? null : new Transform(lastTransform);
        return copy;
    }

    @Nonnull
    public SpawnRequest toRequest(boolean randomPerPlayer) {
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
