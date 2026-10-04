package local.plaincraft.greatwall.gameplay;

import java.util.Objects;
import java.util.Optional;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.DecayState;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.SiteRecord;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Pure loot-table choice for wall containers. The seed is derived from the
 * recipe and site identity, never a random UUID, so a freshly generated chest
 * is reproducible and a looted chest never refills on reload. The four chest
 * tables and the garrison-entity table are frozen JSON resources.
 */
public final class LootInitializer {
    /** Six hundred blocks in Q8, the supplies-vs-ordinary distance threshold. */
    private static final long NEAR_FORT_Q8 = 600L * 256;

    public LootInitializer() {}

    public Optional<GameplayTypes.LootAssignment> assignment(WorldRecipe recipe, SiteRecord site,
            DecayState decay, int containerId, Point3i pos, long nearestFortDistanceQ8) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(decay, "decay");
        Objects.requireNonNull(pos, "pos");
        if (containerId < 0) throw new IllegalArgumentException("container >= 0 required");
        // Ruined or buried sites keep no chest; only intact/partial carry loot.
        if (decay == DecayState.RUIN || decay == DecayState.BURIED) return Optional.empty();
        String table;
        if (site.id().kind() == SiteKind.FORT) {
            table = switch (containerId) {
                case 0 -> "armory";
                case 1 -> "supplies";
                default -> "ordinary";
            };
        } else if (site.id().kind() == SiteKind.ENEMY_TOWER) {
            // Most towers are deliberately empty; a stable 35% carries a chest.
            long roll = DeterministicHash.fields(recipe.seed(), "loot-present",
                    site.id().ordinal(), site.id().kind().ordinal());
            if (DeterministicHash.bounded(roll, 100) >= 35) return Optional.empty();
            if (decay == DecayState.INTACT && nearestFortDistanceQ8 <= NEAR_FORT_Q8) table = "supplies";
            else if (decay == DecayState.PARTIAL) table = "weathered";
            else table = "ordinary";
        } else {
            return Optional.empty();
        }
        long seed = DeterministicHash.fields(recipe.seed(), "loot-seed",
                site.id().ordinal(), containerId, site.id().kind().ordinal());
        // The caller (ChunkWallWriter) supplies the real component proof when the
        // block entity is first initialized; here the input hash is the recipe
        // identity, which is stable and 64-hex, never a per-call random value.
        String inputHash = recipe.recipeHash();
        var id = ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "chests/" + table);
        return Optional.of(new GameplayTypes.LootAssignment(site.id(), containerId, pos,
                id.toString(), seed, inputHash));
    }

    public CompoundTag initialNbt(GameplayTypes.LootAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        var tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        tag.putInt("x", assignment.pos().x());
        tag.putInt("y", assignment.pos().y());
        tag.putInt("z", assignment.pos().z());
        tag.putString("LootTable", assignment.table());
        tag.putLong("LootTableSeed", assignment.seed());
        return tag;
    }

    public void initializeNew(RandomizableContainerBlockEntity chest, GameplayTypes.LootAssignment assignment) {
        Objects.requireNonNull(chest, "chest");
        Objects.requireNonNull(assignment, "assignment");
        var id = ResourceLocation.tryParse(assignment.table());
        if (id == null) throw new IllegalArgumentException("invalid loot table: " + assignment.table());
        chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
        chest.setLootTableSeed(assignment.seed());
        chest.setChanged();
    }
}
