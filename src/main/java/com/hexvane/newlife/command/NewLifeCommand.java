package com.hexvane.newlife.command;

import com.hexvane.newlife.spawn.SpawnApplyService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hexvane.newlife.gui.StartChoicePage;
import javax.annotation.Nonnull;

public final class NewLifeCommand extends AbstractPlayerCommand {
    public NewLifeCommand() {
        super("newlife", "Open the start menu");
        this.setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        CustomUIPage open = player.getPageManager().getCustomPage();
        if (open != null && !(open instanceof StartChoicePage)) {
            context.sendMessage(Message.translation("server.customUI.newLife.command.busy"));
            return;
        }
        if (open instanceof StartChoicePage) {
            context.sendMessage(Message.translation("server.customUI.newLife.command.opened"));
            return;
        }
        SpawnApplyService.openMenu(world, ref, store);
        context.sendMessage(Message.translation("server.customUI.newLife.command.opened"));
    }
}
