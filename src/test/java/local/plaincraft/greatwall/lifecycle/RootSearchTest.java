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
    @Test void structureScreenPausesWithoutReadingMoreColumnsAndAllConflictsFailClosed() {
        var domain=new SpawnDomain();domain.authorize(new ChunkKey("minecraft:overworld",-63,-63),11);
        var root=new RootSearch(new Point3i(-1000,64,-1000),BOUNDS,domain.snapshot(),(o,c)->true,true);
        var oracle=new Fixture(false);
        while(!root.needsStructureScreen())root.advance(oracle,64);
        var pending=root.pendingStructureCandidates();
        int columns=root.progress().columns();
        for(int n=0;n<10;n++)assertEquals(WorkState.MORE,root.advance(oracle,64));
        assertEquals(columns,root.progress().columns(),"no oracle or normal tick work while structure votes are unresolved");
        assertTrue(pending.size()>8&&pending.size()<=144);
        var denied=pending.stream().map(c->new RootStructureScreen.Vote(c.radiusIndex(),c.angleIndex(),List.of(),
                java.util.Collections.nCopies(5,"STRUCTURE_CONFLICT:test:registered"))).toList();
        var failure=assertThrows(WallFailure.class,()->root.acceptStructureVotes(denied));
        assertEquals(FailureCode.ROOT_NOT_FOUND,failure.code());
        assertEquals(columns,root.progress().columns());
    }
    @Test void filteringPrecedesFrozenEightCandidateLimitAndPreservesOrder() {
        var domain=new SpawnDomain();domain.authorize(new ChunkKey("minecraft:overworld",-63,-63),11);
        var root=new RootSearch(new Point3i(-1000,64,-1000),BOUNDS,domain.snapshot(),(o,c)->true,true);
        var oracle=new Fixture(false);
        while(!root.needsStructureScreen())root.advance(oracle,64);
        var pending=root.pendingStructureCandidates();
        var votes=new java.util.ArrayList<RootStructureScreen.Vote>();
        for(int i=0;i<pending.size();i++){
            var c=pending.get(i);
            votes.add(new RootStructureScreen.Vote(c.radiusIndex(),c.angleIndex(),i<8?List.of():List.of(0,1,2,3,4),
                    java.util.Collections.nCopies(5,i<8?"STRUCTURE_CONFLICT:test:registered":"CLEAR")));
        }
        root.acceptStructureVotes(votes);
        assertEquals(8,root.progress().shortlisted());
        while(root.advance(oracle,64)!=WorkState.DONE){}
        var selected=root.finish();
        assertEquals(pending.get(8).radiusIndex(),selected.radiusIndex());
        assertEquals(pending.get(8).angleIndex(),selected.angleIndex());
        assertTrue(root.progress().columns()<=150000);
        assertEquals(40,root.audit().stream().filter(a->a.stage().equals("structure")
                &&a.result().startsWith("STRUCTURE_CONFLICT")).count());
    }

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
        assertTrue(search.audit().stream().allMatch(row -> row.stage().equals("coarse")
                && row.wetColumns() == 81 && row.invalidSamples() == 0 && row.result().equals("REJECTED")));
    }

    @Test void unsupportedFootprintCannotBeReplacedWithAnEmptyFort() {
        var search = search((o, c) -> false);
        var error = assertThrows(WallFailure.class, () -> {
            var oracle = new Fixture(false);
            while (search.advance(oracle, 64) != WorkState.DONE) {}
        });
        assertEquals(FailureCode.ROOT_NOT_FOUND, error.code());
        var rejected = search.audit().stream().filter(row -> row.stage().equals("footprint")).toList();
        assertEquals(8, rejected.size());
        assertTrue(rejected.stream().allMatch(row -> row.failedFoundations() == 2304
                && row.wetColumns() == 0 && row.invalidSamples() == 0 && row.result().equals("REJECTED")));
        assertThrows(UnsupportedOperationException.class, () -> search.audit().clear());
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

    @Test void batchGuardStopsBeforeStackingAnotherSlowNativePrimitive() {
        var search = search((o, c) -> true);
        var oracle = new Fixture(false, 21);
        for (int i = 0; i < 200 && search.progress().phase() != 3; i++) {
            search.advance(oracle, 64);
        }
        assertEquals(0, search.progress().over40msBatches());
        assertTrue(search.progress().maxBatchNanos() < 40_000_000L,
                () -> "max batch nanos=" + search.progress().maxBatchNanos());
    }

    @Test void singleOver40msPrimitiveStillRejectsTheCompletedSearch() {
        var search = search((o, c) -> true);
        var oracle = new Fixture(false, 45, 1);
        while (search.advance(oracle, 64) != WorkState.DONE) {}
        assertTrue(search.progress().over40msBatches() >= 1);
        assertTrue(search.progress().maxBatchNanos() > 40_000_000L);
        var failure = assertThrows(WallFailure.class, search::finish);
        assertEquals(FailureCode.UNSUPPORTED_ADAPTER, failure.code());
        assertTrue(failure.getMessage().contains("steady sampling exceeded 40ms"));
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
        private final long delayMillis;
        private int delayedCalls;
        private Fixture(boolean wet) { this(wet, 0); }
        private Fixture(boolean wet, long delayMillis) { this(wet, delayMillis, -1); }
        private Fixture(boolean wet, long delayMillis, int delayedCalls) {
            this.wet = wet; this.delayMillis = delayMillis; this.delayedCalls = delayedCalls;
        }
        private static final class Cursor implements ColumnCursor { private boolean done; public boolean done() { return done; } }
        @Override public ColumnCursor begin(int x, int z, SampleKind kind) { assertEquals(SampleKind.REFERENCE, kind); return new Cursor(); }
        @Override public WorkState advance(ColumnCursor cursor, int limit) {
            if (delayMillis > 0 && delayedCalls != 0) {
                if (delayedCalls > 0) delayedCalls--;
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            ((Cursor) cursor).done = true; return WorkState.DONE;
        }
        @Override public ColumnSample finish(ColumnCursor cursor) {
            assertTrue(cursor.done());
            return new ColumnSample(64, wet ? OptionalInt.of(65) : OptionalInt.empty(), wet ? OptionalInt.of(64) : OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
        }
        @Override public String fingerprint() { return "0".repeat(64); }
        @Override public void close() {}
    }
}
