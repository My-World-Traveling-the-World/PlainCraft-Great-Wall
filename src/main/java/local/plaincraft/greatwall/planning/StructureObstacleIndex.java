package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.generation.CorridorStructureScan.OwnedBox;
import local.plaincraft.greatwall.geometry.FixedMath;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Grid-hash spatial index over resolved structure boxes, for the detour stage's hot path.
 *
 * <p>{@link #blocked} answers "does this Q8-fixed point fall inside (or within {@code marginBlocks}
 * of) any structure footprint". The wall's ridge planner samples thousands of candidate offsets, so
 * the query is kept to a single grid-cell lookup plus a linear scan of the few boxes in that cell —
 * never a scan over every structure in the corridor.
 */
public final class StructureObstacleIndex {
    private static final int CELL_BITS = 6; // 64-block grid cells
    private static final int CELL_SIZE = 1 << CELL_BITS;
    private final Map<Long, List<BoundingBox>> cells = new HashMap<>();
    private final int marginBlocks;

    private StructureObstacleIndex(int marginBlocks) {
        this.marginBlocks = marginBlocks;
    }

    public static StructureObstacleIndex of(List<OwnedBox> boxes, int marginBlocks) {
        var index = new StructureObstacleIndex(marginBlocks);
        if (boxes != null) {
            for (OwnedBox owned : boxes) index.add(owned.box());
        }
        return index;
    }

    public static StructureObstacleIndex empty(int marginBlocks) {
        return new StructureObstacleIndex(marginBlocks);
    }

    private void add(BoundingBox box) {
        if (box == null) return;
        int minCellX = Math.floorDiv(box.minX() - marginBlocks, CELL_SIZE);
        int maxCellX = Math.floorDiv(box.maxX() + marginBlocks, CELL_SIZE);
        int minCellZ = Math.floorDiv(box.minZ() - marginBlocks, CELL_SIZE);
        int maxCellZ = Math.floorDiv(box.maxZ() + marginBlocks, CELL_SIZE);
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                cells.computeIfAbsent(cellKey(cx, cz), ignored -> new ArrayList<>()).add(box);
            }
        }
    }

    /** Whether the fixed-point column {@code (xQ8, zQ8)} collides with any structure footprint,
     *  ignoring height: any structure whose XZ bounds cover the column blocks it. */
    public boolean blocked(long xQ8, long zQ8) {
        return blocked(xQ8, zQ8, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Same-horizontal-layer collision, mirroring RoadWeaver's
     * {@code PieceOccupancy.intersects(x, z, minY, maxY, ...)}: a structure only blocks the wall when
     * its Y-range overlaps the wall's own height band. Underground mines and floating structures are
     * ignored, so the wall runs over/under them instead of needlessly detouring.
     */
    public boolean blocked(long xQ8, long zQ8, int minY, int maxY) {
        if (cells.isEmpty()) return false;
        int blockX = FixedMath.floorBlock(xQ8);
        int blockZ = FixedMath.floorBlock(zQ8);
        var cell = cells.get(cellKey(Math.floorDiv(blockX, CELL_SIZE), Math.floorDiv(blockZ, CELL_SIZE)));
        if (cell == null) return false;
        for (BoundingBox box : cell) {
            if (blockX >= box.minX() - marginBlocks && blockX <= box.maxX() + marginBlocks
                    && blockZ >= box.minZ() - marginBlocks && blockZ <= box.maxZ() + marginBlocks
                    && maxY >= box.minY() && minY <= box.maxY()) {
                return true;
            }
        }
        return false;
    }

    public int boxCount() {
        int count = 0;
        for (var cell : cells.values()) count += cell.size();
        return count;
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
