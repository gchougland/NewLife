package com.hexvane.newlife.spawn;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult;
import com.hypixel.hytale.server.worldgen.zone.Zone;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SpawnSafety {
    public static final int MAX_UNDERGROUND_Y = 70;
    public static final int MIN_UNDERGROUND_Y = 2;

    private SpawnSafety() {}

    public static boolean isOceanName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.equals("oceans") || lower.contains("ocean");
    }

    public static boolean isOceanColumn(@Nonnull World world, int x, int z) {
        ZoneBiomeResult result = zoneBiomeAt(world, x, z);
        if (result == null || result.getZoneResult() == null || result.getZoneResult().getZone() == null) {
            return true;
        }
        Zone zone = result.getZoneResult().getZone();
        if (isOceanName(zone.name())) {
            return true;
        }
        if (zone.discoveryConfig() != null && isOceanName(zone.discoveryConfig().zone())) {
            return true;
        }
        return false;
    }

    @Nullable
    public static ZoneBiomeResult zoneBiomeAt(@Nonnull World world, int x, int z) {
        IWorldGen worldGen = world.getChunkStore().getGenerator();
        if (!(worldGen instanceof ChunkGenerator generator)) {
            return null;
        }
        int seed = (int) world.getWorldConfig().getSeed();
        try {
            return generator.getZoneBiomeResultAt(seed, x, z);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Reads loaded chunks only, so the caller loads the column first. A block with no section in memory is not safe. */
    public static boolean isSafeStand(@Nonnull World world, int x, int feetY, int z) {
        if (feetY < 1) {
            return false;
        }
        if (isOceanColumn(world, x, z)) {
            return false;
        }
        BlockType feet = readBlock(world, x, feetY, z);
        BlockType head = readBlock(world, x, feetY + 1, z);
        BlockType floor = readBlock(world, x, feetY - 1, z);
        if (feet == null || head == null || floor == null) {
            return false;
        }
        if (!isOpenAir(feet) || !isOpenAir(head) || !isSolidGround(floor)) {
            return false;
        }
        return !hasFluid(world, x, feetY - 1, z)
            && !hasFluid(world, x, feetY, z)
            && !hasFluid(world, x, feetY + 1, z);
    }

    @Nullable
    public static Integer findSafeFeetY(
        @Nonnull World world,
        int x,
        int z,
        int preferredY,
        boolean underground
    ) {
        int surfaceY = surfaceHeight(world, x, z);
        if (surfaceY < 1) {
            return null;
        }
        if (!underground) {
            int start = preferredY > 0 ? preferredY : surfaceY + 1;
            int minY = Math.max(1, surfaceY - 4);
            int maxY = surfaceY + 8;
            return scanRange(world, x, z, start, minY, maxY);
        }
        int maxY = Math.min(MAX_UNDERGROUND_Y - 1, surfaceY - 1);
        if (maxY < MIN_UNDERGROUND_Y) {
            return null;
        }
        int start = preferredY > 0 ? preferredY : Math.min(40, maxY);
        return scanRange(world, x, z, start, MIN_UNDERGROUND_Y, maxY);
    }

    @Nullable
    public static Integer findSafeFeetNear(
        @Nonnull World world,
        int x,
        int z,
        int preferredY,
        boolean underground
    ) {
        int surfaceY = surfaceHeight(world, x, z);
        if (surfaceY < 1 || preferredY < 1) {
            return null;
        }
        int minY = Math.max(1, preferredY - 12);
        int maxY = preferredY + 8;
        if (underground) {
            maxY = Math.min(maxY, Math.min(MAX_UNDERGROUND_Y - 1, surfaceY - 1));
            minY = Math.max(MIN_UNDERGROUND_Y, minY);
            if (maxY < minY) {
                return null;
            }
        } else {
            minY = Math.max(minY, surfaceY - 6);
            maxY = Math.min(maxY, surfaceY + 10);
        }
        return scanRange(world, x, z, preferredY, minY, maxY);
    }

    @Nullable
    private static Integer scanRange(
        @Nonnull World world,
        int x,
        int z,
        int start,
        int minY,
        int maxY
    ) {
        int clamped = Math.max(minY, Math.min(maxY, start));
        if (tryY(world, x, clamped, z)) {
            return clamped;
        }
        int span = Math.max(maxY - clamped, clamped - minY);
        for (int delta = 1; delta <= span; delta++) {
            int up = clamped + delta;
            if (up <= maxY && tryY(world, x, up, z)) {
                return up;
            }
            int down = clamped - delta;
            if (down >= minY && tryY(world, x, down, z)) {
                return down;
            }
        }
        return null;
    }

    private static boolean tryY(@Nonnull World world, int x, int y, int z) {
        return isSafeStand(world, x, y, z);
    }

    /** Highest opaque block of a loaded column, or {@link HeightmapColumn#NO_HEIGHT} when it is empty or not loaded. */
    private static int surfaceHeight(@Nonnull World world, int x, int z) {
        HeightmapColumn heightmap = world.getChunkStore()
            .getChunkComponent(ChunkUtil.indexChunkFromBlock(x, z), HeightmapColumn.getComponentType());
        return heightmap == null ? HeightmapColumn.NO_HEIGHT : heightmap.getHeight(x, z);
    }

    @Nullable
    private static BlockType readBlock(@Nonnull World world, int x, int y, int z) {
        if (y < ChunkUtil.MIN_Y) {
            return null;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        BlockSection blockSection = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        return blockSection == null ? null : BlockType.getAssetMap().getAsset(blockSection.get(x, y, z));
    }

    private static boolean isOpenAir(@Nonnull BlockType type) {
        if (type == BlockType.EMPTY) {
            return true;
        }
        return type.getMaterial() == BlockMaterial.Empty;
    }

    private static boolean isSolidGround(@Nonnull BlockType type) {
        if (type == BlockType.EMPTY) {
            return false;
        }
        return type.getMaterial() == BlockMaterial.Solid;
    }

    private static boolean hasFluid(@Nonnull World world, int x, int y, int z) {
        if (y < ChunkUtil.MIN_Y) {
            return true;
        }
        try {
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                return true;
            }
            var sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return false;
            }
            FluidSection fluidSection = chunkStore.getStore().getComponent(sectionRef, FluidSection.getComponentType());
            return fluidSection != null && fluidSection.getFluidId(x, y, z) != Fluid.EMPTY_ID;
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
