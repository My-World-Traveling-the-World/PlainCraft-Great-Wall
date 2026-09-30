package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import org.junit.jupiter.api.Test;

final class RootSearchTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 320, 30_000_000);

    @Test void nativeDomainIncludesAccumulatedRadiusAndTwoChunksOnly() {
        var domain = new SpawnDomain();
        domain.authorize(new ChunkKey("minecraft:overworld", -2, 3), 3);
        assertEquals(121, domain.snapshot().size());
        assertTrue(domain.contains(new ChunkKey("minecraft:overworld", -7, -2)));
        assertFalse(domain.contains(new ChunkKey("minecraft:overworld", -8, -2)));
        assertFalse(domain.contains(new ChunkKey("minecraft:the_nether", -2, 3)));
        domain.authorize(new ChunkKey("minecraft:overworld", -2, 3), 3);
        assertEquals(121, domain.snapshot().size());
    }

    @Test void oversizedSpawnDomainFailsInsteadOfGrantingAllChunks() {
        var domain = new SpawnDomain();
        var error = assertThrows(WallFailure.class, () -> domain.authorize(new ChunkKey("minecraft:overworld", 0, 0), 32));
        assertEquals(FailureCode.ROOT_NOT_FOUND, error.code());
        assertThrows(WallFailure.class, domain::snapshot);
        assertThrows(WallFailure.class, () -> domain.contains(new ChunkKey("minecraft:overworld", 0, 0)));
    }

    @Test void futureSeparationIncludesWholeChunkCornersTemplatesAndMargin() {
        var early = List.of(new ChunkKey("minecraft:overworld", 0, 0));
        assertFalse(SpawnDomain.disjointFuture(new Axis(new Point3i(160, 64, 0), 1 << 30, 0), early));
        assertTrue(SpawnDomain.disjointFuture(new Axis(new Point3i(162, 64, 0), 1 << 30, 0), early));
        assertFalse(SpawnDomain.disjointFuture(new Axis(new Point3i(1000, 64, 0), -(1 << 30), 0), early));
        assertFalse(SpawnDomain.disjointFuture(new Axis(new Point3i(1000, 64, 0), 1 << 30, 0), List.of()));
    }

    @Test void flatLandSelectsFrozenRingAndIsStableAcrossBatchSizes() {
        var a = run(new Fixture(false), 1);
        var b = run(new Fixture(false), 64);
        assertEquals(a.axis(), b.axis());
        assertEquals(a.radiusIndex(), b.radiusIndex());
        assertEquals(a.angleIndex(), b.angleIndex());
        assertEquals(a.axisIndex(), b.axisIndex());
        assertEquals(a.columns(), b.columns());
        long distance = FixedMath.distanceSquared(a.axis().origin().x(), a.axis().origin().z(), -1000, -1000);
        assertTrue(distance >= 640000 && distance <= 2250000);
        assertEquals(0, a.over40msBatches());
        assertTrue(a.columns() > 48 * 48);
        assertTrue(a.columns() <= 150000);
        assertEquals(0, a.radiusIndex());
        assertEquals(0, a.angleIndex());
        assertEquals(0, a.axisIndex());
    }

    @Test void allWetCandidatesFailVisiblyWithoutExpandingRing() {
        var oracle = new Fixture(true);
        var search = search((o, c) -> true);
        var error = assertThrows(WallFailure.class, () -> {
            while (search.advance(oracle, 64) != WorkState.DONE) {}
        });
        assertEquals(FailureCode.ROOT_NOT_FOUND, error.code());
        assertTrue(search.progress().columns() <= 4 * 36 * 81);
    }

    @Test void unsupportedFootprintCannotBeReplacedWithAnEmptyFort() {
        var search = search((o, c) -> false);
        var error = assertThrows(WallFailure.class, () -> {
            var oracle = new Fixture(false);
            while (search.advance(oracle, 64) != WorkState.DONE) {}
        });
        assertEquals(FailureCode.ROOT_NOT_FOUND, error.code());
    }

    @Test void finishAndForeignLaneCannotReadPartialRoot() throws Exception {
        var search = search((o, c) -> true);
        assertThrows(IllegalStateException.class, search::finish);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertInstanceOf(IllegalStateException.class, worker.submit(() -> {
                try { search.advance(new Fixture(false), 1); return null; }
                catch (Throwable error) { return error; }
            }).get());
        }
    }

    private static RootSearch.Selection run(Fixture oracle, int batch) {
        var search = search((o, c) -> true);
        while (search.advance(oracle, batch) != WorkState.DONE) {}
        return search.finish();
    }

    private static RootSearch search(java.util.function.BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> support) {
        var domain = new SpawnDomain();
        domain.authorize(new ChunkKey("minecraft:overworld", -63, -63), 11);
        return new RootSearch(new Point3i(-1000, 64, -1000), BOUNDS, domain.snapshot(), support);
    }

    private static final class Fixture implements NaturalTerrainOracle {
        private final boolean wet;
        private Fixture(boolean wet) { this.wet = wet; }
        private static final class Cursor implements ColumnCursor { private boolean done; public boolean done() { return done; } }
        @Override public ColumnCursor begin(int x, int z, SampleKind kind) { assertEquals(SampleKind.REFERENCE, kind); return new Cursor(); }
        @Override public WorkState advance(ColumnCursor cursor, int limit) { ((Cursor) cursor).done = true; return WorkState.DONE; }
        @Override public ColumnSample finish(ColumnCursor cursor) {
            assertTrue(cursor.done());
            return new ColumnSample(64, wet ? OptionalInt.of(65) : OptionalInt.empty(), wet ? OptionalInt.of(64) : OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
        }
        @Override public String fingerprint() { return "0".repeat(64); }
        @Override public void close() {}
    }
}
