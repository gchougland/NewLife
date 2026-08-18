package com.hexvane.newlife.systems;

import com.hexvane.newlife.NewLifePlugin;
import com.hexvane.newlife.spawn.PlayerSpawnState;
import com.hexvane.newlife.spawn.SpawnApplyService;
import com.hexvane.newlife.spawn.SpawnRequest;
import com.hexvane.newlife.spawn.WorldSpawnState;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.RespawnSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

public final class NewLifeRespawnSystem extends RespawnSystems.OnRespawnSystem {
    @Nonnull
    private static final Query<EntityStore> QUERY = PlayerRef.getComponentType();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void onComponentRemoved(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull DeathComponent component,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        NewLifePlugin plugin = NewLifePlugin.get();
        if (plugin == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        UUID playerId = playerRef.getUuid();
        WorldSpawnState worldState = plugin.spawnStore().world(world);
        PlayerSpawnState playerState = worldState.player(playerId);

        boolean randomEveryDeath = playerState.hasChosen ? playerState.randomEveryDeath : worldState.randomEveryDeath;
        boolean ignoreBeds = playerState.ignoreBeds && worldState.ignoreBeds;
        if (!randomEveryDeath && !ignoreBeds) {
            return;
        }

        SpawnRequest request = playerState.hasChosen
            ? playerState.toRequest(worldState.randomPerPlayer)
            : worldState.worldRequest();

        if (randomEveryDeath) {
            SpawnApplyService.applyChoice(world, playerId, request, null);
            return;
        }

        Transform target = playerState.lastTransform != null ? playerState.lastTransform : worldState.worldSpawn;
        if (target != null) {
            SpawnApplyService.teleportPlayer(world, playerId, target);
        }
    }
}
