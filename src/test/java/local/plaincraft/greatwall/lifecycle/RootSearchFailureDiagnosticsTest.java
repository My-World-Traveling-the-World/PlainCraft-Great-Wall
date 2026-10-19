package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import org.junit.jupiter.api.Test;

/**
 * 2026-10-19：选址失败必须自报计数。
 *
 * <p>真机那一局唯一的失败行是 "all frozen 200..2500 ring/footprint/axis candidates rejected"，
 * 既没有走到哪个阶段、也没有各门计数，只能靠人把 15 条进度行和 10 条拒绝行拼起来读。
 */
final class RootSearchFailureDiagnosticsTest {

    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 320, 30_000_000);

    @Test
    void theFailureMessageCarriesTheCountsNeededToReadIt() {
        var domain = new SpawnDomain();
        domain.authorize(new ChunkKey("minecraft:overworld", -63, -63), 11);
        // No pad can carry a fort in this fixture, so the search ends without a site.
        var search = new RootSearch(new Point3i(-1000, 64, -1000), BOUNDS,
                domain.snapshot(), (o, c) -> false);
        var oracle = new DryFixture();

        WallFailure failure = assertThrows(WallFailure.class, () -> {
            while (search.advance(oracle, 64) != WorkState.DONE) { }
        });
        String message = failure.getMessage();

        assertTrue(message.contains("phase="), message);
        assertTrue(message.contains("ranked="), message);
        assertTrue(message.contains("coarseRejected="), message);
        assertTrue(message.contains("pads={"), message);
        // Every pad in this fixture is unsupported, so the tally must name support - not all zeros.
        assertTrue(!message.contains("support=0"),
                "a pad was refused for support but the tally says zero: " + message);
    }

    /** A flat, dry world; only the foundation predicate decides. */
    private static final class DryFixture implements NaturalTerrainOracle {
        private static final class Cursor implements ColumnCursor {
            private boolean done;
            public boolean done() { return done; }
        }

        @Override public ColumnCursor begin(int x, int z, SampleKind kind) {
            return new Cursor();
        }

        @Override public WorkState advance(ColumnCursor cursor, int limit) {
            ((Cursor) cursor).done = true;
            return WorkState.DONE;
        }

        @Override public ColumnSample finish(ColumnCursor cursor) {
            return new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
        }

        @Override public String fingerprint() { return "0".repeat(64); }

        @Override public void close() { }
    }

    @Test
    void aRingWithNoAdmissibleCandidateStillYieldsTheBestOfWhatItMeasured() {
        var domain = new SpawnDomain();
        domain.authorize(new ChunkKey("minecraft:overworld", -63, -63), 11);
        var search = new RootSearch(new Point3i(-1000, 64, -1000), BOUNDS,
                domain.snapshot(), (o, c) -> true);
        // Every 8-block band steps 120 blocks: no 64x64 window on this ring is within the relief bound,
        // which is exactly the shape that ended two real sessions with no wall at all.
        var oracle = new SteepFixture();

        while (search.advance(oracle, 64) != WorkState.DONE) { }
        var selection = search.finish();

        assertTrue(selection.axis().origin().y() > 0, "the fallback must still produce a site");
    }

    /** Dry, fully supported, but stepped far beyond any relief bound. */
    private static final class SteepFixture implements NaturalTerrainOracle {
        private static final class Cursor implements ColumnCursor {
            private boolean done; private int x;
            public boolean done() { return done; }
        }

        @Override public ColumnCursor begin(int x, int z, SampleKind kind) {
            var cursor = new Cursor(); cursor.x = x; return cursor;
        }

        @Override public WorkState advance(ColumnCursor cursor, int limit) {
            ((Cursor) cursor).done = true;
            return WorkState.DONE;
        }

        @Override public ColumnSample finish(ColumnCursor cursor) {
            int height = 64 + Math.floorMod(((Cursor) cursor).x / 8, 2) * 120;
            return new ColumnSample(height, OptionalInt.empty(), OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
        }

        @Override public String fingerprint() { return "0".repeat(64); }

        @Override public void close() { }
    }
}