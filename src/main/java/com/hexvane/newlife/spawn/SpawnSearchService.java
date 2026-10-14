package com.hexvane.newlife.spawn;

import com.hypixel.hytale.builtin.locate.PrefabPatternSearchUtil;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.worldgen.cave.Cave;
import com.hypixel.hytale.server.worldgen.cave.CaveGenerator;
import com.hypixel.hytale.server.worldgen.cave.CaveType;
import com.hypixel.hytale.server.worldgen.cave.element.CaveNode;
import com.hypixel.hytale.server.worldgen.cave.element.CavePrefab;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult;
import com.hypixel.hytale.server.worldgen.zone.Zone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

public final class SpawnSearchService {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int SEARCH_RADIUS = 12000;
    private static final int SAMPLE_ATTEMPTS = 500;
    private static final int NEIGHBOR_SCAN = 6;
    private static final long SEARCH_TIMEOUT_MS = 30000L;
    private static final int PREFAB_RADIUS = 8000;
    private static final int SMALL_ISLAND_MAX_CELLS = 64;
    private static final int SMALL_ISLAND_MIN_CELLS = 4;
    private static final int ISLAND_STEP = 16;
    private static final int[] ISLAND_DISTS = {48, 96, 144};
    private static final int[][] ISLAND_DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private SpawnSearchService() {}

    @Nonnull
    public static CompletableFuture<Transform> search(@Nonnull World world, @Nonnull SpawnRequest request) {
        return CompletableFuture.supplyAsync(() -> findValidated(world, request))
            .orTimeout(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .exceptionally(error -> {
                LOGGER.atWarning().log("Spawn search failed: %s", error.getMessage());
                return null;
            });
    }

    @Nullable
    private static Transform findValidated(@Nonnull World world, @Nonnull SpawnRequest request) {
        IWorldGen worldGen = world.getChunkStore().getGenerator();
        if (!(worldGen instanceof ChunkGenerator generator)) {
            return null;
        }
        ChunkGenerator.getResource().init(generator);
        int tries = switch (request.mode) {
            case UNDERGROUND, ISLAND -> 16;
            case DUNGEON -> 3;
            default -> 8;
        };
        for (int i = 0; i < tries; i++) {
            Candidate candidate = findCandidate(world, request);
            if (candidate == null) {
                continue;
            }
            try {
                Transform transform = validateCandidate(world, candidate).join();
                if (transform != null) {
                    return transform;
                }
            } catch (RuntimeException e) {
                LOGGER.atFine().log("Spawn validate failed: %s", e.getMessage());
            }
        }
        return null;
    }

    @Nullable
    private static Candidate findCandidate(@Nonnull World world, @Nonnull SpawnRequest request) {
        IWorldGen worldGen = world.getChunkStore().getGenerator();
        if (!(worldGen instanceof ChunkGenerator generator)) {
            return null;
        }
        int seed = (int) world.getWorldConfig().getSeed();
        return switch (request.mode) {
            case ANYWHERE, ABOVE_GROUND -> randomLand(world, generator, seed);
            case UNDERGROUND -> randomUnderground(world, generator, seed);
            case ZONE -> randomInRegion(world, generator, seed, request.region);
            case DUNGEON -> findDungeon(world, generator, seed, request.dungeonKind);
            case ISLAND -> findSmallIsland(world, generator, seed);
        };
    }

    @Nullable
    private static Candidate randomLand(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < SAMPLE_ATTEMPTS; i++) {
            int x = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            int z = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            if (SpawnSafety.isOceanColumn(world, x, z)) {
                continue;
            }
            int y = generator.getHeight(seed, x, z);
            if (y < 4) {
                continue;
            }
            return new Candidate(x, y + 1, z, false, false);
        }
        return null;
    }

    @Nullable
    private static Candidate randomUnderground(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < SAMPLE_ATTEMPTS; i++) {
            int x = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            int z = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            if (SpawnSafety.isOceanColumn(world, x, z)) {
                continue;
            }
            int surfaceY = generator.getHeight(seed, x, z);
            if (surfaceY <= SpawnSafety.MIN_UNDERGROUND_Y + 2) {
                continue;
            }
            int y = Math.min(40, surfaceY - 3);
            if (y >= SpawnSafety.MAX_UNDERGROUND_Y) {
                y = SpawnSafety.MAX_UNDERGROUND_Y - 8;
            }
            if (y < SpawnSafety.MIN_UNDERGROUND_Y) {
                continue;
            }
            return new Candidate(x, y, z, true, false);
        }
        return null;
    }

    @Nullable
    private static Candidate randomInRegion(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed,
        @Nullable SpawnRegion region
    ) {
        if (region == null) {
            return randomLand(world, generator, seed);
        }
        int[] point = sampleLandInRegion(world, generator, seed, region, SAMPLE_ATTEMPTS * 2);
        if (point == null) {
            return null;
        }
        return new Candidate(point[0], point[1], point[2], false, false);
    }

    @Nullable
    private static int[] sampleLandInRegion(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed,
        @Nonnull SpawnRegion region,
        int attempts
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < attempts; i++) {
            int x = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            int z = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            if (SpawnSafety.isOceanColumn(world, x, z)) {
                continue;
            }
            if (!inRegion(generator, seed, x, z, region)) {
                continue;
            }
            int y = generator.getHeight(seed, x, z);
            if (y < 4) {
                continue;
            }
            return new int[] {x, y + 1, z};
        }
        return null;
    }

    private static boolean inRegion(
        @Nonnull ChunkGenerator generator,
        int seed,
        int x,
        int z,
        @Nonnull SpawnRegion region
    ) {
        ZoneBiomeResult result = generator.getZoneBiomeResultAt(seed, x, z);
        if (result == null || result.getZoneResult() == null || result.getZoneResult().getZone() == null) {
            return false;
        }
        Zone zone = result.getZoneResult().getZone();
        String discovery = zone.discoveryConfig() == null ? null : zone.discoveryConfig().zone();
        return region.matchesZone(zone.name(), discovery);
    }

    @Nullable
    private static Candidate findDungeon(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed,
        @Nullable DungeonKind preferred
    ) {
        List<DungeonKind> order = new ArrayList<>(Arrays.asList(DungeonKind.values()));
        Collections.shuffle(order, ThreadLocalRandom.current());
        if (preferred != null) {
            order.remove(preferred);
            order.add(0, preferred);
        }
        for (DungeonKind kind : order) {
            Candidate found = findOneDungeon(world, generator, seed, kind);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static Candidate findOneDungeon(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed,
        @Nonnull DungeonKind kind
    ) {
        if (kind.isSurfaceDungeon()) {
            return searchPrefabDungeon(world, generator, seed, kind);
        }
        Candidate fromCave = searchCaveDungeon(world, generator, seed, kind);
        if (fromCave != null) {
            return fromCave;
        }
        return searchPrefabDungeon(world, generator, seed, kind);
    }

    @Nullable
    private static Candidate searchPrefabDungeon(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed,
        @Nonnull DungeonKind kind
    ) {
        List<String> keys = kind.prefabKeys();
        if (keys.isEmpty()) {
            return null;
        }
        int startX = 0;
        int startZ = 0;
        int[] origin = sampleLandInRegion(world, generator, seed, kind.preferredRegion(), 80);
        if (origin != null) {
            startX = origin[0];
            startZ = origin[2];
        }
        try {
            for (String key : keys) {
                List<Map.Entry<String, Vector3i>> results = PrefabPatternSearchUtil.search(
                    generator,
                    seed,
                    startX,
                    startZ,
                    PREFAB_RADIUS,
                    key,
                    8
                );
                Candidate picked = pickPrefabResult(results, kind);
                if (picked != null) {
                    return picked;
                }
            }
        } catch (RuntimeException | Error e) {
            LOGGER.atFine().log("Prefab dungeon search failed: %s", e.getMessage());
        }
        return null;
    }

    @Nullable
    private static Candidate pickPrefabResult(
        @Nullable List<Map.Entry<String, Vector3i>> results,
        @Nonnull DungeonKind kind
    ) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Map.Entry<String, Vector3i> chosen = results.get(ThreadLocalRandom.current().nextInt(results.size()));
        Vector3i pos = chosen.getValue();
        return new Candidate(pos.x, pos.y + 1, pos.z, !kind.isSurfaceDungeon(), true);
    }

    @Nullable
    private static Candidate searchCaveDungeon(
        @Nonnull World world,
        @Nonnull ChunkGenerator generator,
        int seed,
        @Nonnull DungeonKind kind
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int attempts = 80;
        for (int i = 0; i < attempts; i++) {
            int x;
            int z;
            if (i < attempts / 2) {
                int[] origin = sampleLandInRegion(world, generator, seed, kind.preferredRegion(), 20);
                if (origin != null) {
                    x = origin[0];
                    z = origin[2];
                } else {
                    x = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
                    z = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
                }
            } else {
                x = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
                z = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            }
            if (SpawnSafety.isOceanColumn(world, x, z)) {
                continue;
            }
            ZoneBiomeResult result = generator.getZoneBiomeResultAt(seed, x, z);
            if (result == null || result.getZoneResult() == null || result.getZoneResult().getZone() == null) {
                continue;
            }
            CaveGenerator caveGenerator = result.getZoneResult().getZone().caveGenerator();
            if (caveGenerator == null) {
                continue;
            }
            for (CaveType caveType : caveGenerator.getCaveTypes()) {
                if (!kind.matchesCaveType(caveType.getName())) {
                    continue;
                }
                Cave cave = generator.getCave(caveType, seed, x, z);
                Candidate fromCave = candidateFromCave(cave, kind);
                if (fromCave == null) {
                    continue;
                }
                if (fromCave.y < SpawnSafety.MAX_UNDERGROUND_Y || kind.isSurfaceDungeon()) {
                    return fromCave;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Candidate candidateFromCave(@Nullable Cave cave, @Nonnull DungeonKind kind) {
        if (cave == null || cave.getNodeCount() <= 0) {
            return null;
        }
        try {
            cave.compile();
        } catch (RuntimeException ignored) {
            // Already compiled or not ready.
        }
        Candidate best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            var bounds = cave.getBounds();
            if (bounds == null || bounds.getLowBoundX() == Integer.MAX_VALUE) {
                return null;
            }
            int minChunkX = Math.floorDiv(bounds.getLowBoundX(), ChunkUtil.SIZE);
            int maxChunkX = Math.floorDiv(bounds.getHighBoundX(), ChunkUtil.SIZE);
            int minChunkZ = Math.floorDiv(bounds.getLowBoundZ(), ChunkUtil.SIZE);
            int maxChunkZ = Math.floorDiv(bounds.getHighBoundZ(), ChunkUtil.SIZE);
            if (maxChunkX - minChunkX > 48) {
                maxChunkX = minChunkX + 48;
            }
            if (maxChunkZ - minChunkZ > 48) {
                maxChunkZ = minChunkZ + 48;
            }
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    long index = ChunkUtil.indexChunkFromBlock(cx * ChunkUtil.SIZE, cz * ChunkUtil.SIZE);
                    if (!cave.contains(index)) {
                        continue;
                    }
                    CaveNode[] nodes = cave.getCaveNodes(index);
                    if (nodes == null) {
                        continue;
                    }
                    for (CaveNode node : nodes) {
                        Candidate fromNode = candidateFromNode(node, kind);
                        if (fromNode == null) {
                            continue;
                        }
                        int score = scoreDungeonSpot(node, fromNode, kind);
                        if (score > bestScore) {
                            bestScore = score;
                            best = fromNode;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.atFine().log("Cave inspect failed: %s", e.getMessage());
        }
        return best;
    }

    @Nullable
    private static Candidate candidateFromNode(@Nonnull CaveNode node, @Nonnull DungeonKind kind) {
        CavePrefab[] prefabs = node.getCavePrefabs();
        if (prefabs != null) {
            for (CavePrefab prefab : prefabs) {
                if (prefab == null) {
                    continue;
                }
                String key = prefab.getPrefab() == null ? null : prefab.getPrefab().getPrefabKey();
                if (kind.prefabHints().length > 0 && !kind.matchesPrefab(key) && !kind.matchesPrefab(nodeName(node))) {
                    continue;
                }
                return new Candidate(prefab.getX(), prefab.getY() + 1, prefab.getZ(), true, true);
            }
        }
        if (kind.prefabHints().length > 0 && !kind.matchesPrefab(nodeName(node))) {
            return null;
        }
        var end = node.getEnd();
        if (end == null) {
            return null;
        }
        return new Candidate((int) Math.floor(end.x), (int) Math.floor(end.y), (int) Math.floor(end.z), true, true);
    }

    @Nonnull
    private static String nodeName(@Nonnull CaveNode node) {
        return node.getCaveNodeType() == null ? "" : node.getCaveNodeType().getName();
    }

    private static int scoreDungeonSpot(@Nonnull CaveNode node, @Nonnull Candidate candidate, @Nonnull DungeonKind kind) {
        int score = 10;
        String nodeName = nodeName(node);
        if (kind.matchesPrefab(nodeName)) {
            score += 60;
        }
        CavePrefab[] prefabs = node.getCavePrefabs();
        if (prefabs != null) {
            for (CavePrefab prefab : prefabs) {
                if (prefab == null || prefab.getPrefab() == null) {
                    continue;
                }
                String key = prefab.getPrefab().getPrefabKey();
                if (kind.matchesPrefab(key)) {
                    score += 80;
                }
                if (isDeepName(key) || isDeepName(nodeName)) {
                    score += 20;
                }
            }
        }
        return score;
    }

    private static boolean isDeepName(@Nullable String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("boss") || lower.contains("stage_3") || lower.contains("stage3") || lower.contains("hive_base");
    }

    @Nullable
    private static Candidate findSmallIsland(@Nonnull World world, @Nonnull ChunkGenerator generator, int seed) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < SAMPLE_ATTEMPTS * 3; i++) {
            int x = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            int z = random.nextInt(-SEARCH_RADIUS, SEARCH_RADIUS + 1);
            if (SpawnSafety.isOceanColumn(world, x, z)) {
                int dx = random.nextBoolean() ? 16 : -16;
                int dz = random.nextBoolean() ? 16 : -16;
                boolean foundLand = false;
                for (int step = 0; step < 20; step++) {
                    x += dx;
                    z += dz;
                    if (!SpawnSafety.isOceanColumn(world, x, z)) {
                        foundLand = true;
                        break;
                    }
                }
                if (!foundLand) {
                    continue;
                }
            } else if (!nearOcean(world, x, z)) {
                continue;
            }
            IslandResult island = floodFillIsland(world, x, z);
            if (island == null) {
                continue;
            }
            int[] cell = island.cells.get(random.nextInt(island.cells.size()));
            int y = generator.getHeight(seed, cell[0], cell[1]);
            if (y < 4) {
                continue;
            }
            return new Candidate(cell[0], y + 1, cell[1], false, false);
        }
        return null;
    }

    @Nullable
    private static IslandResult floodFillIsland(@Nonnull World world, int startX, int startZ) {
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<int[]> cells = new ArrayList<>();
        boolean oceanNearby = false;
        queue.add(new long[] {startX, startZ});
        while (!queue.isEmpty() && cells.size() <= SMALL_ISLAND_MAX_CELLS) {
            long[] point = queue.removeFirst();
            int x = (int) point[0];
            int z = (int) point[1];
            long key = (((long) x) << 32) ^ (z & 0xffffffffL);
            if (!seen.add(key)) {
                continue;
            }
            if (SpawnSafety.isOceanColumn(world, x, z)) {
                oceanNearby = true;
                continue;
            }
            cells.add(new int[] {x, z});
            queue.add(new long[] {x + ISLAND_STEP, z});
            queue.add(new long[] {x - ISLAND_STEP, z});
            queue.add(new long[] {x, z + ISLAND_STEP});
            queue.add(new long[] {x, z - ISLAND_STEP});
        }
        if (!oceanNearby || cells.size() < SMALL_ISLAND_MIN_CELLS || cells.size() > SMALL_ISLAND_MAX_CELLS) {
            return null;
        }
        int oceanDirs = 0;
        for (int d = 0; d < 4; d++) {
            for (int dist : ISLAND_DISTS) {
                int nx = startX + ISLAND_DIRS[d][0] * dist;
                int nz = startZ + ISLAND_DIRS[d][1] * dist;
                if (SpawnSafety.isOceanColumn(world, nx, nz)) {
                    oceanDirs++;
                    break;
                }
            }
        }
        if (oceanDirs < 3) {
            return null;
        }
        return new IslandResult(cells);
    }

    private static boolean nearOcean(@Nonnull World world, int x, int z) {
        for (int dist : ISLAND_DISTS) {
            for (int d = 0; d < 4; d++) {
                if (SpawnSafety.isOceanColumn(world, x + ISLAND_DIRS[d][0] * dist, z + ISLAND_DIRS[d][1] * dist)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record IslandResult(@Nonnull List<int[]> cells) {}

    @Nonnull
    private static CompletableFuture<Transform> validateCandidate(
        @Nonnull World world,
        @Nonnull Candidate candidate
    ) {
        List<CompletableFuture<Transform>> checks = new ArrayList<>();
        for (int dx = -NEIGHBOR_SCAN; dx <= NEIGHBOR_SCAN; dx += 2) {
            for (int dz = -NEIGHBOR_SCAN; dz <= NEIGHBOR_SCAN; dz += 2) {
                int x = candidate.x + dx;
                int z = candidate.z + dz;
                long index = ChunkUtil.indexChunkFromBlock(x, z);
                checks.add(world.getChunkStore().getChunkReferenceAsync(index, GetChunkFlags.SET_TICKING).thenApplyAsync(chunkRef -> {
                    if (chunkRef == null || !chunkRef.isValid()) {
                        return null;
                    }
                    return standTransform(world, x, z, candidate);
                }, world));
            }
        }
        return firstNonNull(checks);
    }

    @Nullable
    private static Transform standTransform(
        @Nonnull World world,
        int x,
        int z,
        @Nonnull Candidate candidate
    ) {
        if (SpawnSafety.isOceanColumn(world, x, z)) {
            return null;
        }
        Integer feetY;
        if (candidate.stayNearY) {
            feetY = SpawnSafety.findSafeFeetNear(world, x, z, candidate.y, candidate.underground);
        } else {
            feetY = SpawnSafety.findSafeFeetY(world, x, z, candidate.y, candidate.underground);
        }
        if (feetY == null) {
            return null;
        }
        if (candidate.underground && feetY >= SpawnSafety.MAX_UNDERGROUND_Y) {
            return null;
        }
        return new Transform(new Vector3d(x + 0.5, feetY, z + 0.5));
    }

    @Nonnull
    private static CompletableFuture<Transform> firstNonNull(@Nonnull List<CompletableFuture<Transform>> futures) {
        CompletableFuture<Transform> result = new CompletableFuture<>();
        if (futures.isEmpty()) {
            result.complete(null);
            return result;
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            for (CompletableFuture<Transform> future : futures) {
                Transform transform = future.getNow(null);
                if (transform != null) {
                    result.complete(transform);
                    return;
                }
            }
            result.complete(null);
        });
        return result;
    }

    private record Candidate(int x, int y, int z, boolean underground, boolean stayNearY) {}
}
