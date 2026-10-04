package local.plaincraft.greatwall.compat.ftb;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import local.plaincraft.greatwall.gameplay.BeaconLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * FTB quest task that mirrors the durable wall beacon-credit count. It never
 * pushes progress on ignition; it only reads {@link BeaconLedger#progress()}
 * on FTB's own 20-tick check, so completed tasks are never un-credited or
 * re-rewarded by a target edit.
 */
public final class BeaconCountTask extends CustomTask {
    private static final long DEFAULT_TARGET = 10L;
    private static final long MIN_TARGET = 1L;
    private static final long MAX_TARGET = 1_000_000L;

    private long targetCount = DEFAULT_TARGET;

    public enum Provider implements TaskType.Provider {
        INSTANCE;
        @Override public Task create(long id, Quest quest) { return new BeaconCountTask(id, quest); }
    }

    public BeaconCountTask(long id, Quest quest) {
        super(id, quest);
        if (getQuestFile() instanceof ServerQuestFile) configureServerCheck();
    }

    @Override public TaskType getType() { return FtbIntegration.countType(); }
    @Override public long getMaxProgress() { return targetCount; }
    @Override public boolean checkOnLogin() { return true; }
    public long targetCount() { return targetCount; }

    @Override public void writeData(CompoundTag tag, HolderLookup.Provider lookup) {
        super.writeData(tag, lookup);
        tag.putLong("target_count", targetCount);
    }

    @Override public void readData(CompoundTag tag, HolderLookup.Provider lookup) {
        super.readData(tag, lookup);
        targetCount = tag.contains("target_count") ? tag.getLong("target_count") : DEFAULT_TARGET;
        if (targetCount < MIN_TARGET || targetCount > MAX_TARGET) {
            throw new IllegalArgumentException("target_count out of range for task " + getId() + ": " + targetCount);
        }
        if (getQuestFile() instanceof ServerQuestFile) configureServerCheck();
    }

    @Override public void writeNetData(RegistryFriendlyByteBuf buffer) {
        super.writeNetData(buffer);
        buffer.writeLong(targetCount);
    }

    @Override public void readNetData(RegistryFriendlyByteBuf buffer) {
        super.readNetData(buffer);
        targetCount = buffer.readLong();
        if (targetCount < MIN_TARGET || targetCount > MAX_TARGET) {
            throw new IllegalArgumentException("target_count out of range for task " + getId() + ": " + targetCount);
        }
        // Only the field is assigned here: no setCheckTimer/setMaxProgress/setEnableButton.
    }

    @Override public void fillConfigGroup(ConfigGroup config) {
        super.fillConfigGroup(config);
        config.addLong("target_count", targetCount, v -> targetCount = v, DEFAULT_TARGET, MIN_TARGET, MAX_TARGET);
    }

    @Override public void editedFromGUIOnServer() {
        super.editedFromGUIOnServer();
        if (targetCount < MIN_TARGET || targetCount > MAX_TARGET) {
            throw new IllegalArgumentException("target_count out of range for task " + getId() + ": " + targetCount);
        }
        configureServerCheck();
    }

    public void configureServerCheck() {
        if (!(getQuestFile() instanceof ServerQuestFile)) return;
        long target = targetCount;
        setEnableButton(false);
        setCheckTimer(20);
        setMaxProgress(target);
        setCheck((data, player) -> {
            var ledger = FtbIntegration.ledger(player).orElse(null);
            if (ledger == null) return;
            var progress = ledger.progress();
            if (!progress.ready()) return;
            long next = Math.min(progress.credited(), target);
            if (data.getProgress() != next) data.setProgress(next);
        });
    }
}
