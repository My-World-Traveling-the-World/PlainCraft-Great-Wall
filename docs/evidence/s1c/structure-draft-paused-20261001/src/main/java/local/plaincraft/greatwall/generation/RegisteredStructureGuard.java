package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Registered structures exclude the wall's entire vertical column, not just intersecting blocks. */
public final class RegisteredStructureGuard {
    private static final Comparator<Bounds> ORDER = Comparator.comparingInt(Bounds::minX)
            .thenComparingInt(Bounds::minZ).thenComparingInt(Bounds::maxXExclusive)
            .thenComparingInt(Bounds::maxZExclusive).thenComparingInt(Bounds::minY)
            .thenComparingInt(Bounds::maxYExclusive);

    private RegisteredStructureGuard() {}

    public static long boxUpperBound(ChunkAccess owner, Map<ChunkPos, ChunkAccess> prepared,
            Set<Structure> registered) {
        Objects.requireNonNull(owner); Objects.requireNonNull(prepared); Objects.requireNonNull(registered);
        long count = 0;
        for (var entry : owner.getAllStarts().entrySet())
            if (registered.contains(entry.getKey())) count = Math.addExact(count, count(entry.getValue()));
        for (var entry : owner.getAllReferences().entrySet()) if (registered.contains(entry.getKey())) {
            for (long reference : entry.getValue()) {
                var chunk = prepared.get(new ChunkPos(reference));
                var start = chunk == null ? null : chunk.getStartForStructure(entry.getKey());
                count = Math.addExact(count, valid(start) ? count(start) : 1);
            }
        }
        return count;
    }

    public static List<Bounds> columns(ChunkAccess owner, Map<ChunkPos, ChunkAccess> prepared,
            Set<Structure> registered) {
        if (!owner.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))
            throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN, "structure exclusion requires completed FEATURES");
        CompiledAllocation.charge(Math.addExact(512, Math.multiplyExact(128,
                boxUpperBound(owner, prepared, registered))));
        var boxes = new ArrayList<Bounds>();
        int minY = owner.getMinBuildHeight(), maxY = owner.getMaxBuildHeight();
        for (var entry : owner.getAllStarts().entrySet())
            if (registered.contains(entry.getKey())) append(entry.getValue(), minY, maxY, boxes);
        for (var entry : owner.getAllReferences().entrySet()) if (registered.contains(entry.getKey())) {
            for (long reference : entry.getValue()) {
                var chunk = prepared.get(new ChunkPos(reference));
                var start = chunk == null ? null : chunk.getStartForStructure(entry.getKey());
                if (valid(start)) append(start, minY, maxY, boxes);
                else {
                    int x = owner.getPos().getMinBlockX(), z = owner.getPos().getMinBlockZ();
                    boxes.add(new Bounds(x, minY, z, Math.addExact(x, 16), maxY, Math.addExact(z, 16)));
                }
            }
        }
        return boxes.stream().distinct().sorted(ORDER).toList();
    }

    private static boolean valid(StructureStart start) { return start != null && start.isValid(); }
    private static long count(StructureStart start) { return valid(start) ? 1L + start.getPieces().size() : 0; }
    private static void append(StructureStart start, int minY, int maxY, List<Bounds> boxes) {
        if (!valid(start)) return;
        boxes.add(column(start.getBoundingBox(), minY, maxY));
        for (var piece : start.getPieces()) boxes.add(column(piece.getBoundingBox(), minY, maxY));
    }
    private static Bounds column(BoundingBox box, int minY, int maxY) {
        return new Bounds(box.minX(), minY, box.minZ(), Math.addExact(box.maxX(), 1),
                maxY, Math.addExact(box.maxZ(), 1));
    }
}
