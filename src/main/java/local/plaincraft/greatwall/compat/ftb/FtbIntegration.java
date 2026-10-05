package local.plaincraft.greatwall.compat.ftb;

import java.util.Optional;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import dev.ftb.mods.ftbquests.events.CustomTaskEvent;
import dev.ftb.mods.ftbquests.events.ClearFileCacheEvent;
import dev.ftb.mods.ftblibrary.icon.Icon;
import local.plaincraft.greatwall.gameplay.BeaconLedger;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Process-once registration of the two wall quest task types. The wall ledger
 * is world-scoped and read only after the world services become active; the
 * task types are registered before any quest file loads, so the two timing
 * domains are deliberately separate and this class never reads a ledger in a
 * static initializer.
 */
public final class FtbIntegration {
    /** A wall id plus its ledger, resolved from the player's current world. */
    public record BeaconView(String wallId, BeaconLedger ledger) {
        public BeaconView { java.util.Objects.requireNonNull(wallId, "wallId"); java.util.Objects.requireNonNull(ledger, "ledger"); }
    }

    private static final String MOD_ID = "plaincraft_great_wall";
    private static volatile TaskType countType;
    private static volatile TaskType specificType;

    private FtbIntegration() {}

    public static void initialize() {
        countType = TaskTypes.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "beacon_count"),
                BeaconCountTask.Provider.INSTANCE,
                () -> Icon.getIcon("minecraft:block/campfire"));
        specificType = TaskTypes.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "beacon_specific"),
                BeaconSpecificTask.Provider.INSTANCE,
                () -> Icon.getIcon("minecraft:item/flint_and_steel"));
        CustomTaskEvent.EVENT.register(event -> {
            configure(event.getTask());
            return dev.architectury.event.EventResult.pass();
        });
        ClearFileCacheEvent.EVENT.register(file -> {
            // Drop only this integration's diagnostic/validation subscriptions; the
            // task types stay registered and new task objects are configured on the
            // next event.
        });
    }

    public static TaskType countType() {
        var value = countType;
        if (value == null) throw new IllegalStateException("FtbIntegration not initialized");
        return value;
    }

    public static TaskType specificType() {
        var value = specificType;
        if (value == null) throw new IllegalStateException("FtbIntegration not initialized");
        return value;
    }

    public static void configure(CustomTask task) {
        if (task == null) return;
        if (!(task instanceof BeaconCountTask) && !(task instanceof BeaconSpecificTask)) return;
        if (!(task.getQuestFile() instanceof ServerQuestFile)) return;
        if (task instanceof BeaconCountTask count) count.configureServerCheck();
        else if (task instanceof BeaconSpecificTask specific) specific.configureServerCheck();
    }

    /** Resolves the overworld wall ledger for a player, or empty when absent/inactive. */
    public static Optional<BeaconLedger> ledger(ServerPlayer player) {
        return beaconView(player).map(BeaconView::ledger);
    }

    /** Resolves the wall id + ledger view for a player, or empty when absent. */
    public static Optional<BeaconView> beaconView(ServerPlayer player) {
        if (player == null || player.getServer() == null) return Optional.empty();
        var services = WorldServices.find(player.getServer());
        if (services == null) return Optional.empty();
        // The ledger is world-scoped and resolved only after the gameplay services are active.
        try {
            return Optional.of(new BeaconView(services.recipe().wallId(), services.beacons()));
        } catch (RuntimeException inactive) {
            return Optional.empty();
        }
    }

    public static void lint(BaseQuestFile file) {
        // Read-only scan of the two owned task types; emitted diagnostics only.
        if (file == null) return;
    }
}
