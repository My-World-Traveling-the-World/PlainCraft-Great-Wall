package local.plaincraft.greatwall.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ImmutablePointSetTest {
    @Test
    void largeDenseCoordinateSnapshotRetainsEveryPointAndItsOriginalSetHash() {
        var source = new HashSet<Point3i>();
        for (int i = 0; i < 140000; i++) source.add(point(i));
        int expectedHash = source.hashCode();
        var frozen = ImmutablePointSet.copyOf(source);
        assertEquals(source, frozen);
        assertEquals(expectedHash, frozen.hashCode());
        source.clear();
        assertEquals(140000, frozen.size());
        for (int i = 0; i < 140000; i++) assertTrue(frozen.contains(point(i)));
        assertSame(frozen, ImmutablePointSet.copyOf(frozen));
    }

    @Test
    void everyMutationIsRejectedIncludingNoOpAndEmptySetOperations() {
        assertSame(Set.of(), ImmutablePointSet.copyOf(new HashSet<>()));
        for (Set<Point3i> frozen : List.of(ImmutablePointSet.copyOf(Set.of()),
                ImmutablePointSet.copyOf(Set.of(point(0))))) {
            assertThrows(UnsupportedOperationException.class, () -> frozen.add(point(0)));
            assertThrows(UnsupportedOperationException.class, () -> frozen.addAll(List.of()));
            assertThrows(UnsupportedOperationException.class, () -> frozen.remove(point(1)));
            assertThrows(UnsupportedOperationException.class, () -> frozen.removeAll(List.of()));
            assertThrows(UnsupportedOperationException.class, () -> frozen.retainAll(frozen));
            assertThrows(UnsupportedOperationException.class, () -> frozen.removeIf(p -> false));
            assertThrows(UnsupportedOperationException.class, frozen::clear);
            assertThrows(NullPointerException.class, () -> frozen.contains(null));
            if (!frozen.isEmpty()) {
                var iterator = frozen.iterator();
                iterator.next();
                assertThrows(UnsupportedOperationException.class, iterator::remove);
            }
        }
    }

    @Test
    void nullSourceAndNullMembersRemainRejected() {
        assertThrows(NullPointerException.class, () -> ImmutablePointSet.copyOf(null));
        var invalid = new HashSet<Point3i>();
        invalid.add(null);
        assertThrows(NullPointerException.class, () -> ImmutablePointSet.copyOf(invalid));
    }

    @Test
    void metadataUsesDetachedSnapshotsAndReusesOnlyTrustedFrozenValues() {
        var source = new HashSet<>(Set.of(point(0), point(1)));
        var coverage = new Coverage(new ChunkKey("minecraft:overworld", 0, 0),
                List.of(0), new Bounds(0, 0, 0, 16, 128, 16), "a".repeat(64));
        var metadata = new CaptureMetadata(coverage, List.of(), List.of(), source, source, "b".repeat(64));
        source.clear();
        assertEquals(Set.of(point(0), point(1)), metadata.blockEntities());
        assertEquals(metadata.blockEntities(), metadata.immutableBlocks());
        var copied = new CaptureMetadata(coverage, List.of(), List.of(), metadata.blockEntities(),
                metadata.immutableBlocks(), metadata.hash());
        assertSame(metadata.blockEntities(), copied.blockEntities());
        assertSame(metadata.immutableBlocks(), copied.immutableBlocks());
        assertEquals(metadata, copied);
        assertThrows(UnsupportedOperationException.class, metadata.immutableBlocks()::clear);
    }

    private static Point3i point(int i) {
        return new Point3i(i % 16, i / 256, i / 16 % 16);
    }
}
