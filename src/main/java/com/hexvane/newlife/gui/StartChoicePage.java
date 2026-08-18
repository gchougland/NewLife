package com.hexvane.newlife.gui;

import com.hexvane.newlife.NewLifePlugin;
import com.hexvane.newlife.spawn.PlayerSpawnState;
import com.hexvane.newlife.spawn.SpawnApplyService;
import com.hexvane.newlife.spawn.SpawnMode;
import com.hexvane.newlife.spawn.SpawnRegion;
import com.hexvane.newlife.spawn.SpawnRequest;
import com.hexvane.newlife.spawn.WorldSpawnState;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class StartChoicePage extends InteractiveCustomUIPage<StartChoicePage.PageData> {
    private static final String PAGE_TEMPLATE = "NewLife/StartChoicePage.ui";
    private static final String TICK_TEMPLATE = "NewLife/TickboxRow.ui";
    private static final String OPTION_TEMPLATE = "NewLife/StartOptionRow.ui";

    private enum View {
        MAIN,
        ZONE
    }

    @Nonnull
    private final World world;
    private View view = View.MAIN;
    private boolean randomPerPlayer;
    private boolean randomEveryDeath;
    private boolean ignoreBeds;
    private boolean searching;
    private long lastTickboxToggleMs;
    @Nullable
    private String statusKey = "customUI.newLife.status.idle";
    private boolean templateAppended;

    public StartChoicePage(@Nonnull PlayerRef playerRef, @Nonnull World world) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.world = world;
        NewLifePlugin plugin = NewLifePlugin.get();
        if (plugin != null) {
            WorldSpawnState state = plugin.spawnStore().world(world);
            this.randomPerPlayer = state.randomPerPlayer;
            PlayerSpawnState playerState = state.player(playerRef.getUuid());
            if (playerState.hasChosen) {
                this.randomEveryDeath = playerState.randomEveryDeath;
                this.ignoreBeds = playerState.ignoreBeds;
            } else {
                this.randomEveryDeath = state.randomEveryDeath;
                this.ignoreBeds = state.ignoreBeds;
            }
        }
    }

    public void closeFromServer() {
        persistTickboxes();
        close();
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        persistTickboxes();
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        @Nonnull Store<EntityStore> store
    ) {
        if (!templateAppended) {
            commandBuilder.append(PAGE_TEMPLATE);
            templateAppended = true;
        }
        bindAll(commandBuilder, eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        if (data.action == null) {
            return;
        }
        switch (data.action) {
            case "ToggleRandomPerPlayer" -> {
                if (consumeTickboxToggle()) {
                    randomPerPlayer = !randomPerPlayer;
                }
            }
            case "ToggleRandomEveryDeath" -> {
                if (consumeTickboxToggle()) {
                    randomEveryDeath = !randomEveryDeath;
                    persistTickboxes();
                }
            }
            case "ToggleIgnoreBeds" -> {
                if (consumeTickboxToggle()) {
                    ignoreBeds = !ignoreBeds;
                    persistTickboxes();
                }
            }
            case "Back" -> {
                if (!searching) {
                    view = View.MAIN;
                    rebuild(ref, store);
                }
            }
            case "ChooseAnywhere" -> startSearch(ref, store, SpawnMode.ANYWHERE, null);
            case "ChooseAboveGround" -> startSearch(ref, store, SpawnMode.ABOVE_GROUND, null);
            case "ChooseUnderground" -> startSearch(ref, store, SpawnMode.UNDERGROUND, null);
            case "OpenZones" -> {
                if (!searching) {
                    view = View.ZONE;
                    rebuild(ref, store);
                }
            }
            case "ChooseDungeon" -> startSearch(ref, store, SpawnMode.DUNGEON, null);
            case "ChooseIsland" -> startSearch(ref, store, SpawnMode.ISLAND, null);
            case "ChooseZone" -> {
                SpawnRegion region = parseRegion(data.zone);
                if (region != null) {
                    startSearch(ref, store, SpawnMode.ZONE, region);
                }
            }
            default -> {
            }
        }
    }

    private boolean consumeTickboxToggle() {
        long now = System.currentTimeMillis();
        if (now - lastTickboxToggleMs < 250L) {
            return false;
        }
        lastTickboxToggleMs = now;
        return true;
    }

    private void persistTickboxes() {
        NewLifePlugin plugin = NewLifePlugin.get();
        if (plugin == null) {
            return;
        }
        plugin.spawnStore().updateTickboxes(world, playerRef.getUuid(), randomEveryDeath, ignoreBeds);
    }

    private void startSearch(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull SpawnMode mode,
        @Nullable SpawnRegion region
    ) {
        if (searching) {
            return;
        }
        persistTickboxes();
        searching = true;
        statusKey = "customUI.newLife.status.searching";
        rebuild(ref, store);
        SpawnRequest request = new SpawnRequest(
            mode,
            region,
            null,
            randomPerPlayer,
            randomEveryDeath,
            ignoreBeds
        );
        SpawnApplyService.applyChoice(world, playerRef.getUuid(), request, success -> world.execute(() -> {
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef == null || !liveRef.isValid()) {
                return;
            }
            Store<EntityStore> liveStore = liveRef.getStore();
            searching = false;
            if (Boolean.TRUE.equals(success)) {
                close();
                return;
            }
            statusKey = "customUI.newLife.status.failed";
            rebuild(liveRef, liveStore);
        }));
    }

    private void rebuild(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        bindAll(commandBuilder, eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void bindAll(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        bindTickboxes(commandBuilder, eventBuilder);
        bindStatus(commandBuilder);
        bindOptions(commandBuilder, eventBuilder);
        commandBuilder.set("#BackButton.Visible", view == View.ZONE && !searching);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#BackButton",
            EventData.of("Action", "Back"),
            false
        );
    }

    private void bindStatus(@Nonnull UICommandBuilder commandBuilder) {
        String key = statusKey == null ? "customUI.newLife.status.idle" : statusKey;
        commandBuilder.set("#StatusLabel.TextSpans", Message.translation("server." + key));
    }

    private void bindTickboxes(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#TickboxList");
        addTickbox(commandBuilder, eventBuilder, 0, "ToggleRandomPerPlayer", randomPerPlayer, "customUI.newLife.tick.randomPerPlayer");
        addTickbox(commandBuilder, eventBuilder, 1, "ToggleRandomEveryDeath", randomEveryDeath, "customUI.newLife.tick.randomEveryDeath");
        addTickbox(commandBuilder, eventBuilder, 2, "ToggleIgnoreBeds", ignoreBeds, "customUI.newLife.tick.ignoreBeds");
    }

    private void addTickbox(
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        int index,
        @Nonnull String action,
        boolean checked,
        @Nonnull String labelKey
    ) {
        commandBuilder.append("#TickboxList", TICK_TEMPLATE);
        String row = "#TickboxList[" + index + "]";
        commandBuilder.set(row + " #FilterLabel.TextSpans", Message.translation("server." + labelKey));
        commandBuilder.set(row + " #CheckBox.Value", checked);
        commandBuilder.set(row + " #CheckBox.Disabled", searching);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            row + " #CheckBox",
            EventData.of("Action", action),
            false
        );
    }

    private void bindOptions(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#OptionList");
        List<Option> options = view == View.ZONE ? zoneOptions() : mainOptions();
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            commandBuilder.append("#OptionList", OPTION_TEMPLATE);
            String row = "#OptionList[" + i + "]";
            commandBuilder.set(row + ".TextSpans", Message.translation("server." + option.labelKey));
            commandBuilder.set(row + ".Disabled", searching);
            EventData event = new EventData().append("Action", option.action);
            if (option.zone != null) {
                event.append("Zone", option.zone.name());
            }
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, row, event, false);
        }
    }

    @Nonnull
    private static List<Option> mainOptions() {
        List<Option> options = new ArrayList<>();
        options.add(new Option("ChooseAnywhere", "customUI.newLife.option.anywhere", null));
        options.add(new Option("ChooseAboveGround", "customUI.newLife.option.aboveGround", null));
        options.add(new Option("ChooseUnderground", "customUI.newLife.option.underground", null));
        options.add(new Option("OpenZones", "customUI.newLife.option.zone", null));
        options.add(new Option("ChooseDungeon", "customUI.newLife.option.dungeon", null));
        options.add(new Option("ChooseIsland", "customUI.newLife.option.island", null));
        return options;
    }

    @Nonnull
    private static List<Option> zoneOptions() {
        List<Option> options = new ArrayList<>();
        options.add(new Option("ChooseZone", "customUI.newLife.zone.emeraldWilds", SpawnRegion.EMERALD_WILDS));
        options.add(new Option("ChooseZone", "customUI.newLife.zone.howlingSands", SpawnRegion.HOWLING_SANDS));
        options.add(new Option("ChooseZone", "customUI.newLife.zone.whisperfrostFrontiers", SpawnRegion.WHISPERFROST_FRONTIERS));
        options.add(new Option("ChooseZone", "customUI.newLife.zone.devastatedLands", SpawnRegion.DEVASTATED_LANDS));
        return options;
    }

    @Nullable
    private static SpawnRegion parseRegion(@Nullable String name) {
        if (name == null) {
            return null;
        }
        try {
            return SpawnRegion.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record Option(@Nonnull String action, @Nonnull String labelKey, @Nullable SpawnRegion zone) {}

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action)
            .add()
            .append(new KeyedCodec<>("Zone", Codec.STRING), (data, value) -> data.zone = value, data -> data.zone)
            .add()
            .append(new KeyedCodec<>("@Checked", Codec.BOOLEAN), (data, value) -> data.checked = value, data -> data.checked)
            .add()
            .build();

        @Nullable
        private String action;
        @Nullable
        private String zone;
        @Nullable
        private Boolean checked;
    }
}
