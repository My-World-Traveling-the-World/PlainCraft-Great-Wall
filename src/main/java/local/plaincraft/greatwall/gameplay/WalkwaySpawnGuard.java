package local.plaincraft.greatwall.gameplay;

import java.util.Objects;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.shapes.Shapes;

/** Read-only natural-spawn veto for certified, still-owned walkway floors. */
public final class WalkwaySpawnGuard {
    private WalkwaySpawnGuard() {}

    public static boolean blocksNaturalSpawn(ServerLevel level, BlockPos feet) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(feet, "feet");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("walkway query requires the server owner");
        }
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        var chunk = level.getChunkSource().getChunkNow(
                Math.floorDiv(feet.getX(), 16), Math.floorDiv(feet.getZ(), 16));
        if (chunk == null) return false;
        var marker = chunk.getAttached(TerrainMarkers.PRODUCTION);
        var point = new Point3i(feet.getX(), feet.getY(), feet.getZ());
        if (marker == null || !marker.complete() || !marker.walkwayFeet().contains(point)) {
            return false;
        }
        var floor = new Point3i(point.x(), point.y() - 1, point.z());
        var ownership = chunk.getAttached(TerrainMarkers.OWNERSHIP);
        if (ownership == null || ownership.revision() != marker.gateRevision()
                || !ownership.mask().contains(floor)) {
            return false;
        }
        var tombstones = chunk.getAttached(TerrainMarkers.TOMBSTONES);
        if (tombstones != null && (tombstones.contains(floor) || tombstones.contains(point))) {
            return false;
        }
        BlockPos floorPos = feet.below();
        var below = chunk.getBlockState(floorPos).getCollisionShape(chunk, floorPos);
        // The veto needs floor support, not a second walkway-clearance inspection.
        return WalkwayCollisionCheck.inspect(Shapes.empty(), Shapes.empty(), below).supported();
    }
}
