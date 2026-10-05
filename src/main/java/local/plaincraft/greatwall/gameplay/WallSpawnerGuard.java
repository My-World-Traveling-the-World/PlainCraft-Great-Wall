package local.plaincraft.greatwall.gameplay;

import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseSpawner;

/**
 * Vanilla-spawner adapter for certified wall cages. The wall emits no real
 * {@code mob_spawner} block entity; its certified birth source is the
 * {@code SpawnSlot} table recorded in the chunk marker, and
 * {@link GarrisonController#tick()} is the real birth loop. This guard only
 * suppresses a vanilla spawner block that happens to sit on an owned tower/fort
 * slot so the garrison loop owns the birth and vanilla never double-spawns.
 * Every other cage keeps its original behaviour untouched.
 */
public final class WallSpawnerGuard {
    private final GarrisonController garrison;
    private final SiteRuntimeIndex sites;

    public WallSpawnerGuard(GarrisonController garrison, SiteRuntimeIndex sites) {
        this.garrison = Objects.requireNonNull(garrison, "garrison");
        this.sites = Objects.requireNonNull(sites, "sites");
    }

    /**
     * Returns {@code true} when this mod has already handled the tick and the
     * vanilla {@code serverTick} must not run. A spawner is "owned" only when a
     * certified ENEMY_TOWER or FORT spawn slot exists at its position; owned
     * slots suppress vanilla regardless of decay, exactly per the certified-cage
     * contract.
     */
    public boolean tickOwned(BaseSpawner spawner, ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(spawner, "spawner");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        var owned = sites.nearbySlots(new Point3i(pos.getX(), pos.getY(), pos.getZ()), 4);
        for (var slot : owned) {
            if (slot.site().kind() == SiteKind.ENEMY_TOWER || slot.site().kind() == SiteKind.FORT) {
                return true;
            }
        }
        return false; // ordinary cage: keep vanilla behaviour
    }
}
