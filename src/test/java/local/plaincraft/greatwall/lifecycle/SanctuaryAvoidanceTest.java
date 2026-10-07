package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import org.junit.jupiter.api.Test;

/**
 * Pins the Golden Erdtree sanctuary avoidance, which had no test.
 *
 * <h2>Why this exists</h2>
 * The behaviour is user-authorized and safety-critical: on 2026-10-06 the user changed the rule to
 * "make the Golden Tree sanctuary fully avoided", so the radius is the Golden project's
 * {@code MAX_RADIUS = 2600} (core 2000 plus the transition band and outer rivers) rather than the
 * core 2000 alone. {@code SanctuaryAvoidance.of} reads the centre reflectively and fails closed when
 * the mod is present but unreadable, so the only part reachable without the mod installed is the
 * geometry that decides whether a candidate axis is rejected.
 *
 * <p>{@link RootSearch#crossesSanctuary} is that geometry: it measures the perpendicular distance
 * from the sanctuary centre to the candidate axis and rejects it when the distance is under
 * {@code radius + 384 + 512 + 64}. A sign error, a wrong margin, or dropping the
 * {@code active()} short-circuit would let the wall route straight through the sanctuary, and
 * nothing else in the suite would notice because no test has ever supplied a non-empty avoidance.
 */
class SanctuaryAvoidanceTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 320, 30_000_000);
    private static final Point3i SPAWN = new Point3i(-1000, 64, -1000);

    @Test void noneIsInactiveAndTheZeroCentreIsMeaningless() {
        var none = SanctuaryAvoidance.none();
        assertFalse(none.active(), "none() must be inactive, otherwise every search would try to avoid (0,0)");
        assertEquals(0L, none.radiusBlocks());
        assertFalse(new SanctuaryAvoidance(1234L, -5678L, 0L).active(),
                "a zero radius is inactive regardless of the centre");
        assertTrue(new SanctuaryAvoidance(0L, 0L, 1L).active(), "any positive radius is active");
    }

    @Test void theRadiusIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new SanctuaryAvoidance(0L, 0L, -1L),
                "a negative avoidance radius must be rejected rather than treated as inactive");
    }

    @Test void anEmptyAvoidanceRejectsNothingForSanctuaryReasons() {
        // The control: without an avoidance, no axis may be rejected for this reason. If this ever
        // fails, the tests below would be measuring something other than the sanctuary rule.
        var search = driveToAxisStage(SanctuaryAvoidance.none());
        assertTrue(search.audit().stream().anyMatch(row -> "axis".equals(row.stage())),
                "the control must actually reach the axis stage, otherwise the comparison is vacuous");
        assertEquals(0, countSanctuaryRejections(search),
                "an inactive avoidance must never produce REJECTED_SANCTUARY");
    }

    @Test void anAxisThroughTheSanctuaryIsRejected() {
        // Place the sanctuary exactly on the spawn, so every candidate axis passes through it.
        var search = driveToAxisStage(new SanctuaryAvoidance(SPAWN.x(), SPAWN.z(), 2600L));
        assertTrue(search.audit().stream().anyMatch(row -> "axis".equals(row.stage())),
                "the search must reach the axis stage for this assertion to mean anything");
        assertTrue(countSanctuaryRejections(search) > 0,
                "an axis crossing a sanctuary centred on the spawn must be rejected; found "
                        + countSanctuaryRejections(search) + " rejections in "
                        + search.audit().size() + " audit rows");
    }

    @Test void aDistantSanctuaryRejectsLessThanOneCentredOnTheSpawn() {
        // Distance sensitive: moving the sanctuary far away must not reject as much as one sitting on
        // top of the spawn. This is what catches a dropped distance comparison.
        long nearRejections = countSanctuaryRejections(driveToAxisStage(
                new SanctuaryAvoidance(SPAWN.x(), SPAWN.z(), 2600L)));
        long farRejections = countSanctuaryRejections(driveToAxisStage(
                new SanctuaryAvoidance(SPAWN.x(), SPAWN.z() + 2_000_000L, 2600L)));
        assertTrue(nearRejections > 0, "the centred case must reject something for this comparison to mean anything");
        assertEquals(0L, farRejections,
                "a sanctuary two million blocks away must not reject any candidate axis, but saw "
                        + farRejections + " of " + nearRejections + " rejections");
    }

    @Test void theAuthorizedRadiusIsTwoThousandSixHundred() {
        // 2600 is the user-authorized value (Golden project's MAX_RADIUS, covering the transition
        // band). The constant is private, so this pins it through the observable rule instead: the
        // rejection must be recorded with the sanctuary reason at the axis stage, so a future change
        // to the radius or the 384+512+64 margin cannot pass by rejecting for another reason.
        var search = driveToAxisStage(new SanctuaryAvoidance(SPAWN.x(), SPAWN.z(), 2600L));
        var sanctuaryRows = search.audit().stream()
                .filter(row -> "REJECTED_SANCTUARY".equals(row.result()))
                .toList();
        assertFalse(sanctuaryRows.isEmpty(), "expected at least one REJECTED_SANCTUARY row");
        assertTrue(sanctuaryRows.stream().allMatch(row -> row.stage().equals("axis")),
                "sanctuary rejections are recorded at the axis stage");
    }

    /**
     * Drives the search until the axis stage has actually been reached.
     *
     * <p>Sanctuary rejection happens in {@code axisStep}, which is phase 2: a candidate must first pass
     * the footprint screen, and that screen only audits after {@code 48 * 48 = 2304} column samples.
     * Stopping at a fixed small number of audit rows therefore never reaches the code under test —
     * the first version of this test did exactly that and saw 0 rejections in 10 rows.
     */
    private static RootSearch driveToAxisStage(SanctuaryAvoidance avoidance) {
        var search = search(avoidance);
        // advance() caps its batch limit at 1..64, so 64 is the largest legal step; the footprint
        // screen needs 2304 samples per shortlisted candidate, hence the generous iteration count.
        for (int i = 0; i < 200_000; i++) {
            if (search.audit().stream().anyMatch(row -> "axis".equals(row.stage()))) return search;
            if (search.needsStructureScreen()) return search;
            if (search.advance(new Fixture(), 64) == WorkState.DONE) return search;
        }
        return search;
    }

    private static long countSanctuaryRejections(RootSearch search) {
        return search.audit().stream().filter(row -> "REJECTED_SANCTUARY".equals(row.result())).count();
    }

    private static RootSearch search(SanctuaryAvoidance avoidance) {
        var domain = new SpawnDomain();
        domain.authorize(new ChunkKey("minecraft:overworld", -63, -63), 11);
        return new RootSearch(SPAWN, BOUNDS, domain.snapshot(), (oracle, cursor) -> true, false, avoidance);
    }

    /** Minimal oracle whose single column always finishes; the geometry under test needs no terrain. */
    private static final class Fixture implements NaturalTerrainOracle {
        private static final class Cursor implements ColumnCursor {
            private boolean done;
            @Override public boolean done() { return done; }
        }

        @Override public ColumnCursor begin(int x, int z, SampleKind kind) {
            assertEquals(SampleKind.REFERENCE, kind);
            return new Cursor();
        }

        @Override public WorkState advance(ColumnCursor cursor, int limit) {
            ((Cursor) cursor).done = true;
            return WorkState.DONE;
        }

        @Override public ColumnSample finish(ColumnCursor cursor) {
            assertTrue(cursor.done());
            return new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
        }

        @Override public String fingerprint() { return "0".repeat(64); }

        @Override public void close() {}
    }
}
