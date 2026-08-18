package com.hexvane.newlife.spawn;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public enum DungeonKind {
    CURSED_CRYPT(
        false,
        SpawnRegion.EMERALD_WILDS,
        List.of(),
        new String[] {"dungeons"},
        new String[] {"cursed", "crypt"}
    ),
    OUTLANDER_CAMP(
        true,
        SpawnRegion.WHISPERFROST_FRONTIERS,
        List.of("Npc.Outlander.Camps.Tier3", "Npc.Outlander.Camps.Tier2", "Npc.Outlander.Camps.Tier1"),
        new String[] {},
        new String[] {"outlander"}
    ),
    TRORK_FORTRESS(
        true,
        SpawnRegion.EMERALD_WILDS,
        List.of(
            "Npc.Trork.Tier_3.Encampment.Castle",
            "Npc.Trork.Tier_2.Encampment",
            "Npc.Trork.Tier_1.Encampment"
        ),
        new String[] {},
        new String[] {"trork"}
    ),
    ANCIENT_CITY(
        true,
        SpawnRegion.DEVASTATED_LANDS,
        List.of("Monuments.Encounter.City_Ruins"),
        new String[] {},
        new String[] {"city_ruins", "city ruins"}
    ),
    WIND_TEMPLE(
        false,
        SpawnRegion.HOWLING_SANDS,
        List.of(),
        new String[] {"dungeons"},
        new String[] {"labyrinth"}
    ),
    SCARAK_NEST(
        false,
        SpawnRegion.HOWLING_SANDS,
        List.of(),
        new String[] {"scarak", "hive"},
        new String[] {"scarak", "hive"}
    ),
    GOBLIN_DUNGEON(
        false,
        SpawnRegion.EMERALD_WILDS,
        List.of(),
        new String[] {"goblin", "dungeons"},
        new String[] {"goblin"}
    ),
    MINESHAFT(
        false,
        SpawnRegion.EMERALD_WILDS,
        List.of(),
        new String[] {"mineshaft"},
        new String[] {"mineshaft", "mine"}
    );

    private final boolean surface;
    @Nonnull
    private final SpawnRegion preferredRegion;
    @Nonnull
    private final List<String> prefabKeys;
    @Nonnull
    private final String[] caveTypeHints;
    @Nonnull
    private final String[] prefabHints;

    DungeonKind(
        boolean surface,
        @Nonnull SpawnRegion preferredRegion,
        @Nonnull List<String> prefabKeys,
        @Nonnull String[] caveTypeHints,
        @Nonnull String[] prefabHints
    ) {
        this.surface = surface;
        this.preferredRegion = preferredRegion;
        this.prefabKeys = prefabKeys;
        this.caveTypeHints = caveTypeHints;
        this.prefabHints = prefabHints;
    }

    public boolean isSurfaceDungeon() {
        return surface;
    }

    @Nonnull
    public SpawnRegion preferredRegion() {
        return preferredRegion;
    }

    @Nonnull
    public List<String> prefabKeys() {
        return prefabKeys;
    }

    @Nonnull
    public String[] caveTypeHints() {
        return caveTypeHints;
    }

    @Nonnull
    public String[] prefabHints() {
        return prefabHints;
    }

    public boolean matchesCaveType(@Nullable String name) {
        return matchesAny(name, caveTypeHints);
    }

    public boolean matchesPrefab(@Nullable String name) {
        return matchesAny(name, prefabHints);
    }

    private static boolean matchesAny(@Nullable String name, @Nonnull String[] hints) {
        if (name == null || hints.length == 0) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String hint : hints) {
            if (lower.contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
