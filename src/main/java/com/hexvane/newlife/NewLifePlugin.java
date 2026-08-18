package com.hexvane.newlife;

import com.hexvane.newlife.command.NewLifeCommand;
import com.hexvane.newlife.spawn.SpawnApplyService;
import com.hexvane.newlife.spawn.WorldSpawnStore;
import com.hexvane.newlife.systems.NewLifeRespawnSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class NewLifePlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static NewLifePlugin instance;

    @Nonnull
    private final WorldSpawnStore spawnStore = new WorldSpawnStore();

    public NewLifePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Nullable
    public static NewLifePlugin get() {
        return instance;
    }

    @Nonnull
    public WorldSpawnStore spawnStore() {
        return spawnStore;
    }

    @Override
    protected void setup() {
        instance = this;
        spawnStore.initialize(getDataDirectory());
        getCommandRegistry().registerCommand(new NewLifeCommand());
        getEntityStoreRegistry().registerSystem(new NewLifeRespawnSystem());
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        LOGGER.atInfo().log("New Life loaded.");
    }

    private void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        Ref<EntityStore> ref = event.getPlayerRef();
        if (world == null || ref == null || !ref.isValid()) {
            return;
        }
        world.execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            var worldState = spawnStore.world(world);
            if (!worldState.needsChoice(playerRef.getUuid())) {
                return;
            }
            var playerState = worldState.player(playerRef.getUuid());
            if (playerState.menuShown) {
                return;
            }
            if (!SpawnApplyService.openMenu(world, ref, store)) {
                world.execute(() -> tryOpenMenu(world, ref));
                return;
            }
            playerState.menuShown = true;
            spawnStore.save();
        });
    }

    private void tryOpenMenu(@Nonnull World world, @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        var worldState = spawnStore.world(world);
        if (!worldState.needsChoice(playerRef.getUuid())) {
            return;
        }
        var playerState = worldState.player(playerRef.getUuid());
        if (playerState.menuShown) {
            return;
        }
        if (!SpawnApplyService.openMenu(world, ref, store)) {
            return;
        }
        playerState.menuShown = true;
        spawnStore.save();
    }
}
