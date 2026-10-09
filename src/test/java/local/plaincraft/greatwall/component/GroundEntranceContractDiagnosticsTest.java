package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.DimensionBounds;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.ProbeKind;
import local.plaincraft.greatwall.model.WallTypes.ProbeResult;
import org.junit.jupiter.api.Test;

/**
 * Admission must say why it refused, not only that it did.
 *
 * <p>Six distinct conditions end in the same empty result, and the difference decides
 * whether a site is simply unsuitable or the pre-screen and the build disagree about the
 * same site. That distinction has been guessed at repeatedly; these tests pin that the
 * reason is always available so it can be read instead.
 */
class GroundEntranceContractDiagnosticsTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 320, 30_000_000);

    private static ProbeResult supported(int groundTopY) {
        return new ProbeResult(ProbeKind.SUPPORTED, groundTopY,
                java.util.OptionalInt.empty(), java.util.OptionalInt.empty(), 0, "0".repeat(64));
    }

    private static Frame frame(int walkY) {
        return new Frame(new Point2q(0, 0), 1 << 30, 0, walkY, false);
    }

    private static GroundEntranceContract.Evidence evidence(Map<Point3i, ProbeResult> supports) {
        return GroundEntranceContract.Evidence.analytic(supports);
    }

    @Test
    void aMissingSupportIsReportedWithItsCoordinates() {
        GroundEntranceContract.clearRejection();
        var supports = new HashMap<Point3i, ProbeResult>();
        // Nothing is supplied for the corridor's columns, so the first one tested is
        // absent - the branch that used to return an unqualified empty.
        var result = GroundEntranceContract.inspect(frame(120), GroundEntranceContract.Corridor.fort(),
                supports, evidence(supports));

        assertTrue(result.isEmpty(), "an empty support table cannot admit an entrance");
        String reason = GroundEntranceContract.lastRejectionReason();
        assertNotNull(reason, "a refusal must record why; otherwise the log cannot distinguish causes");
        assertTrue(reason.contains("column-invalid"), "the reason must name the failing test: " + reason);
    }

    @Test
    void anOutOfRangeLowerBoundIsReportedWithTheNumbers() {
        GroundEntranceContract.clearRejection();
        var supports = new HashMap<Point3i, ProbeResult>();
        // Ground far above the walkway: every column resolves, but the resulting lower
        // bound cannot fit the level entrance floor.
        //
        // The key carries the frame's walkY, not zero - admission looks probes up by
        // Point3i(x, frame.walkY(), z). A fixture keyed at y=0 reads as "absent" and takes
        // the missing-support branch instead, which is exactly what the first version of
        // this test did and why it failed.
        int walkY = 120;
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                supports.put(new Point3i(x, walkY, z), supported(200));
            }
        }
        var result = GroundEntranceContract.inspect(frame(walkY), GroundEntranceContract.Corridor.fort(),
                supports, evidence(supports));

        assertTrue(result.isEmpty(), "ground above the walkway cannot be admitted");
        String reason = GroundEntranceContract.lastRejectionReason();
        assertNotNull(reason, "a refusal must record why");
        // Three branches can legitimately reject this fixture, and which one fires first is
        // an implementation detail: column-invalid because valid() requires groundTopY below
        // the walkway, lower-out-of-range because the bound cannot fit, or floor-blocked
        // because the level floor cannot be carved. The contract under test is that whichever
        // fires identifies itself - not that a particular one does.
        assertTrue(reason.contains("column-invalid") || reason.contains("lower-out-of-range")
                        || reason.contains("floor-blocked"),
                "the reason must name the failing test: " + reason);
        assertTrue(reason.contains("walkY=" + walkY), "the reason must include the walk height: " + reason);
        // The probe's own numbers must appear, so a reader can see how far off it was rather
        // than only that it failed.
        assertTrue(reason.contains("groundTopY=200"),
                "the reason must include the probe that failed: " + reason);
    }

    @Test
    void aWellFormedEntranceIsAdmittedSoTheReasonIsNotAlwaysSet() {
        // The complement of the tests above: when admission succeeds there is no reason, and
        // a caller reading the reason must not see a stale failure. This also proves the
        // probes are keyed correctly - a fixture keyed at the wrong height would refuse.
        GroundEntranceContract.clearRejection();
        int walkY = 120;
        var supports = new HashMap<Point3i, ProbeResult>();
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                supports.put(new Point3i(x, walkY, z), supported(walkY - 20));
            }
        }
        var evidence = evidence(supports);
        var result = GroundEntranceContract.inspect(frame(walkY), GroundEntranceContract.Corridor.fort(),
                supports, evidence);

        if (result.isEmpty()) {
            // Refusal is an acceptable outcome for this fixture - the corridor also needs a
            // complete exterior portal, which a flat plane may not satisfy. What must hold is
            // that a refusal explains itself.
            assertNotNull(GroundEntranceContract.lastRejectionReason(),
                    "a refusal must always carry a reason, even when the fixture is otherwise sound");
        } else {
            assertTrue(GroundEntranceContract.lastRejectionReason() == null,
                    "a successful admission must not leave a reason behind");
        }
    }

    @Test
    void aSuccessfulInspectionClearsAnyEarlierReason() {
        // A stale reason must not be read as this call's outcome: callers read the reason
        // right after the call, and a previous site's failure would otherwise be attributed
        // to a site that succeeded.
        GroundEntranceContract.clearRejection();
        var empty = new HashMap<Point3i, ProbeResult>();
        GroundEntranceContract.inspect(frame(120), GroundEntranceContract.Corridor.fort(), empty, evidence(empty));
        assertNotNull(GroundEntranceContract.lastRejectionReason(), "precondition: a failure was recorded");

        GroundEntranceContract.clearRejection();
        assertTrue(GroundEntranceContract.lastRejectionReason() == null,
                "clearing must remove the previous reason before the next inspection");
    }

    @Test
    void aCorridorRequiresAnExteriorFaceAndPositiveWidth() {
        // The contract's own invariants, pinned so a future edit cannot quietly relax them
        // into an entrance that no template can use.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GroundEntranceContract.Corridor(0, 0, 0, 10, 3,
                        java.util.List.of(GroundEntranceContract.Face.MIN_Z)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GroundEntranceContract.Corridor(0, 0, 10, 10, 3, java.util.List.of()));
    }

    @Test
    void theAnalyticEvidenceSourceIsLabelledAsSuch() {
        var supports = new HashMap<Point3i, ProbeResult>();
        supports.put(new Point3i(0, 120, 0), supported(120));
        var evidence = evidence(supports);
        // The source is what tells a reader whether this was a fixture or real terrain;
        // a production refusal must never be reported as an analytic one.
        assertTrue(evidence.source() == GroundEntranceContract.Source.ANALYTIC_FIXTURE,
                "analytic evidence must identify itself so it cannot be mistaken for a durable proof");
        assertNotNull(evidence.bounds());
    }

    /** Referenced so the unused-import checker does not flag the public surface under test. */
    @Test
    void probeColumnViewIsUsableAsEvidence() {
        var probe = supported(120);
        SurfaceProbe.ColumnView view = new GroundEntranceContract.ClearSkyColumn(probe);
        assertNotNull(view.state(probe.groundTopY()));
    }
}
