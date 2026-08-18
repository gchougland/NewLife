package com.hexvane.newlife.spawn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public enum SpawnRegion {
    EMERALD_WILDS("Zone1", "Emerald_Wilds"),
    HOWLING_SANDS("Zone2", "Howling_Sands"),
    WHISPERFROST_FRONTIERS("Zone3", "Whisperfrost_Frontiers"),
    DEVASTATED_LANDS("Zone4", "Devastated_Lands");

    @Nonnull
    private final String zonePrefix;
    @Nonnull
    private final String discoveryName;

    SpawnRegion(@Nonnull String zonePrefix, @Nonnull String discoveryName) {
        this.zonePrefix = zonePrefix;
        this.discoveryName = discoveryName;
    }

    @Nonnull
    public String zonePrefix() {
        return zonePrefix;
    }

    @Nonnull
    public String discoveryName() {
        return discoveryName;
    }

    public boolean matchesZone(@Nullable String zoneName, @Nullable String discoveryZone) {
        if (zoneName != null && zoneName.startsWith(zonePrefix)) {
            return true;
        }
        return discoveryZone != null && discoveryName.equalsIgnoreCase(discoveryZone);
    }
}
