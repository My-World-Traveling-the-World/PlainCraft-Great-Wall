package local.plaincraft.greatwall.compat.ftb;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * FTB quest task bound to one specific beacon ordinal on the current overworld.
 * It resolves the ordinal lazily through the wall ledger so a fresh team, an
 * offline login or a team switch all read the same durable count; a locked
 * prerequisite is not bypassed and the unlock is caught up on the next check.
 */
public final class BeaconSpecificTask extends CustomTask {
    private static final long DEFAULT_ORDINAL = 1L;
    private static final long MIN_ORDINAL = 1L;
    private static final long MAX_ORDINAL = 1_000_000L;
    private static final String DEFAULT_SELECTOR = "current_overworld";

    private long targetOrdinal = DEFAULT_ORDINAL;
    private String wallSelector = DEFAULT_SELECTOR;

    public enum Provider implements TaskType.Provider {
        INSTANCE;
        @Override public Task create(long id, Quest quest) { return new BeaconSpecificTask(id, quest); }
    }

    public BeaconSpecificTask(long id, Quest quest) {
        super(id, quest);
        if (getQuestFile() instanceof ServerQuestFile) configureServerCheck();
    }

    @Override public TaskType getType() { return FtbIntegration.specificType(); }
    @Override public boolean checkOnLogin() { return true; }
    public long targetOrdinal() { return targetOrdinal; }
    public String wallSelector() { return wallSelector; }

    @Override public void writeData(CompoundTag tag, HolderLookup.Provider lookup) {
        super.writeData(tag, lookup);
        tag.putLong("target_beacon_ordinal", targetOrdinal);
        tag.putString("wall_selector", wallSelector);
    }

    @Override public void readData(CompoundTag tag, HolderLookup.Provider lookup) {
        super.readData(tag, lookup);
        targetOrdinal = tag.contains("target_beacon_ordinal") ? tag.getLong("target_beacon_ordinal") : DEFAULT_ORDINAL;
        wallSelector = tag.contains("wall_selector") ? tag.getString("wall_selector") : DEFAULT_SELECTOR;
        validate();
        if (getQuestFile() instanceof ServerQuestFile) configureServerCheck();
    }

    @Override public void writeNetData(RegistryFriendlyByteBuf buffer) {
        super.writeNetData(buffer);
        buffer.writeLong(targetOrdinal);
        buffer.writeUtf(wallSelector, 64);
    }

    @Override public void readNetData(RegistryFriendlyByteBuf buffer) {
        super.readNetData(buffer);
        targetOrdinal = buffer.readLong();
        wallSelector = buffer.readUtf(64);
        validate();
        // No synchronous setter here; the client only reflects the field.
    }

    @Override public void fillConfigGroup(ConfigGroup config) {
        super.fillConfigGroup(config);
        config.addLong("target_beacon_ordinal", targetOrdinal, v -> targetOrdinal = v, DEFAULT_ORDINAL, MIN_ORDINAL, MAX_ORDINAL);
        config.addString("wall_selector", wallSelector, v -> wallSelector = v, DEFAULT_SELECTOR);
    }

    @Override public void editedFromGUIOnServer() {
        super.editedFromGUIOnServer();
        validate();
        configureServerCheck();
    }

    private void validate() {
        if (targetOrdinal < MIN_ORDINAL || targetOrdinal > MAX_ORDINAL) {
            throw new IllegalArgumentException("target_beacon_ordinal out of range for task " + getId() + ": " + targetOrdinal);
        }
        if (!DEFAULT_SELECTOR.equals(wallSelector)) {
            throw new IllegalArgumentException("wall_selector must be current_overworld for task " + getId() + ": " + wallSelector);
        }
    }

    public void configureServerCheck() {
        if (!(getQuestFile() instanceof ServerQuestFile)) return;
        setEnableButton(false);
        setCheckTimer(20);
        setMaxProgress(1L);
        setCheck((data, player) -> {
            var view = FtbIntegration.beaconView(player);
            if (view.isEmpty()) return;
            var wallId = view.get().wallId();
            var ledger = view.get().ledger();
            var progress = ledger.progress();
            if (!progress.ready()) return;
            var id = new BeaconId(wallId, targetOrdinal);
            ledger.known(id).ifPresent(known -> {
                long next = known ? 1L : 0L;
                if (data.getProgress() != next) data.setProgress(next);
            });
        });
    }
}
