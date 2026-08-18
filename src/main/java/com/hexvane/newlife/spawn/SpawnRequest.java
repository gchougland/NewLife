package com.hexvane.newlife.spawn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SpawnRequest {
    @Nonnull
    public final SpawnMode mode;
    @Nullable
    public final SpawnRegion region;
    @Nullable
    public final DungeonKind dungeonKind;
    public final boolean randomPerPlayer;
    public final boolean randomEveryDeath;
    public final boolean ignoreBeds;

    public SpawnRequest(
        @Nonnull SpawnMode mode,
        @Nullable SpawnRegion region,
        @Nullable DungeonKind dungeonKind,
        boolean randomPerPlayer,
        boolean randomEveryDeath,
        boolean ignoreBeds
    ) {
        this.mode = mode;
        this.region = region;
        this.dungeonKind = dungeonKind;
        this.randomPerPlayer = randomPerPlayer;
        this.randomEveryDeath = randomEveryDeath;
        this.ignoreBeds = ignoreBeds;
    }
}
