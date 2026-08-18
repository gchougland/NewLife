package com.hexvane.newlife.spawn;

import com.hexvane.newlife.NewLifePlugin;
import com.hexvane.newlife.gui.StartChoicePage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SpawnApplyService {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private SpawnApplyService() {}

    @Nonnull
    public static CompletableFuture<Transform> applyChoice(
        @Nonnull World world,
        @Nonnull UUID playerId,
        @Nonnull SpawnRequest request,
        @Nullable Consumer<Boolean> onComplete
    ) {
        NewLifePlugin plugin = NewLifePlugin.get();
        if (plugin == null) {
            if (onComplete != null) {
                onComplete.accept(false);
            }
            return CompletableFuture.completedFuture(null);
        }
        SpawnRequest resolved = resolveDungeon(request);
        return SpawnSearchService.search(world, resolved).thenApplyAsync(transform -> {
            if (transform == null) {
                if (onComplete != null) {
                    onComplete.accept(false);
                }
                return null;
            }
            persistAndApply(plugin, world, playerId, resolved, transform);
            teleportPlayer(world, playerId, transform);
            if (onComplete != null) {
                onComplete.accept(true);
            }
            return transform;
        }, world).exceptionally(error -> {
            LOGGER.atWarning().log("Could not apply start choice: %s", error.getMessage());
            if (onComplete != null) {
                onComplete.accept(false);
            }
            return null;
        });
    }

    public static void teleportPlayer(@Nonnull World world, @Nonnull UUID playerId, @Nonnull Transform transform) {
        world.execute(() -> {
            Ref<EntityStore> ref = world.getEntityRef(playerId);
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(transform));
        });
    }

    public static boolean openMenu(@Nonnull World world, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return false;
        }
        CustomUIPage open = player.getPageManager().getCustomPage();
        if (open instanceof StartChoicePage) {
            return true;
        }
        if (open != null) {
            return false;
        }
        player.getPageManager().openCustomPage(ref, store, new StartChoicePage(playerRef, world));
        return true;
    }

    public static void closeOtherMenus(@Nonnull World world, @Nonnull UUID keepPlayerId) {
        world.execute(() -> {
            for (PlayerRef playerRef : world.getPlayerRefs()) {
                if (playerRef == null || keepPlayerId.equals(playerRef.getUuid())) {
                    continue;
                }
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                Store<EntityStore> store = ref.getStore();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    continue;
                }
                CustomUIPage page = player.getPageManager().getCustomPage();
                if (page instanceof StartChoicePage startPage) {
                    startPage.closeFromServer();
                }
            }
        });
    }

    @Nonnull
    private static SpawnRequest resolveDungeon(@Nonnull SpawnRequest request) {
        if (request.mode != SpawnMode.DUNGEON || request.dungeonKind != null) {
            return request;
        }
        DungeonKind[] kinds = DungeonKind.values();
        DungeonKind kind = kinds[java.util.concurrent.ThreadLocalRandom.current().nextInt(kinds.length)];
        return new SpawnRequest(
            request.mode,
            request.region,
            kind,
            request.randomPerPlayer,
            request.randomEveryDeath,
            request.ignoreBeds
        );
    }

    private static void persistAndApply(
        @Nonnull NewLifePlugin plugin,
        @Nonnull World world,
        @Nonnull UUID playerId,
        @Nonnull SpawnRequest request,
        @Nonnull Transform transform
    ) {
        WorldSpawnStore store = plugin.spawnStore();
        WorldSpawnState state = store.world(world);
        synchronized (state) {
            boolean lockWorld = !request.randomPerPlayer;
            if (!state.configured || lockWorld) {
                state.configured = true;
                state.randomPerPlayer = request.randomPerPlayer;
                state.randomEveryDeath = request.randomEveryDeath;
                state.ignoreBeds = request.ignoreBeds;
                state.spawnMode = request.mode;
                state.region = request.region;
                state.dungeonKind = request.dungeonKind;
                state.worldSpawn = new Transform(transform);
            } else if (state.randomPerPlayer) {
                state.randomEveryDeath = request.randomEveryDeath;
                state.ignoreBeds = request.ignoreBeds;
            }
            PlayerSpawnState playerState = state.player(playerId);
            playerState.hasChosen = true;
            playerState.spawnMode = request.mode;
            playerState.region = request.region;
            playerState.dungeonKind = request.dungeonKind;
            playerState.randomEveryDeath = request.randomEveryDeath;
            playerState.ignoreBeds = request.ignoreBeds;
            playerState.lastTransform = new Transform(transform);
            if (lockWorld) {
                world.getWorldConfig().setSpawnProvider(new GlobalSpawnProvider(transform));
            }
        }
        store.save();
        if (!request.randomPerPlayer) {
            closeOtherMenus(world, playerId);
        }
    }
}
