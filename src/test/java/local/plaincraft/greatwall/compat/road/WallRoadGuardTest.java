package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for the guard's two cache/coverage failures. */
class WallRoadGuardTest {
    @BeforeEach
    void reset() {
        WallRoadGuard.clear();
    }

    @Test
    void emptyLookupThenMarkIsVisibleImmediately() {
        assertFalse(WallRoadGuard.isMarked(5, 5));
        WallRoadGuard.markColumn(5, 5);
        assertTrue(WallRoadGuard.isMarked(5, 5));
        assertTrue(WallRoadGuard.coarseMayAffect(5, 5));
    }

    @Test
    void clearThenRemarkSameChunkStillMarksTheCurrentMap() {
        WallRoadGuard.markColumn(7, 7);
        WallRoadGuard.clear();
        WallRoadGuard.markColumn(7, 7);
        assertTrue(WallRoadGuard.isMarked(7, 7));
        assertTrue(WallRoadGuard.blocks(7, 64, 7));
    }

    @Test
    void coarseKeepsTheTwoBlockMarginAcrossAChunkBoundary() {
        WallRoadGuard.markColumn(15, 0);
        assertTrue(WallRoadGuard.blocks(16, 0, 0), "exact guard includes its MARGIN");
        assertTrue(WallRoadGuard.coarseMayAffect(16, 0),
                "coarse must be a conservative superset, including the neighbour chunk");
        assertTrue(WallRoadGuard.coarseMayAffect(14, 0));
    }

    @Test
    void coarseDoesNotBecomeAGlobalBoundingBox() {
        WallRoadGuard.markColumn(0, 0);
        assertFalse(WallRoadGuard.coarseMayAffect(400, 400));
        assertFalse(WallRoadGuard.blocks(400, 64, 400));
    }
}
