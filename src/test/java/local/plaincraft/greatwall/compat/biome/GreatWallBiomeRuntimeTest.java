package local.plaincraft.greatwall.compat.biome;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import local.plaincraft.greatwall.generation.CorridorIndex;
import local.plaincraft.greatwall.generation.VegetationGuard;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

/**
 * Pins the biome runtime contract: overworld-only, identity-gated, fail-closed, and drainable.
 *
 * <p>The identity holder is a Minecraft registry object and is intentionally not fabricated here;
 * tests that need a bound identity are covered by the holder-free paths plus the absence paths,
 * which are the ones that decide whether generation can silently degrade.
 */
final class GreatWallBiomeRuntimeTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final Executor DIRECT = Runnable::run;

    private static GreatWallBiomeRuntime runtime(String dimension) {
        return new GreatWallBiomeRuntime(SESSION, dimension, PlanningFixtures.HASH, 1L, DIRECT);
    }

    @Test void onlyTheOverworldMayOwnAnIdentity() {
        assertNotNull(runtime(OVERWORLD));
        // Dimension safety is enforced at construction, so no later code path can leak an identity
        // into the nether, the end or a modded dimension.
        assertThrows(IllegalArgumentException.class, () -> runtime("minecraft:the_nether"));
        assertThrows(IllegalArgumentException.class, () -> runtime("minecraft:the_end"));
        assertThrows(IllegalArgumentException.class, () -> runtime("some_mod:custom"));
    }

    @Test void unboundIdentityFailsClosedInsteadOfReturningNoBiome() {
        var runtime = runtime(OVERWORLD);
        assertFalse(runtime.identityBound());
        // A missing identity must be loud: silently returning nothing would look like "no wall here".
        assertThrows(WallFailure.class, runtime::requireIdentity);
    }

    @Test void missingMaskFailsClosedRatherThanLookingLikeNoCorridor() {
        var runtime = runtime(OVERWORLD);
        var owner = new ChunkKey(OVERWORLD, 3, 7);
        assertTrue(runtime.find(owner).isEmpty(), "an unpublished owner has no mask");
        assertThrows(WallFailure.class, () -> runtime.require(owner));
    }

    @Test void foreignDimensionNeverResolvesAMask() {
        var runtime = runtime(OVERWORLD);
        // Even if another dimension asked with the same coordinates, it must not see this mask.
        assertTrue(runtime.find(new ChunkKey("minecraft:the_nether", 3, 7)).isEmpty());
        assertEquals(0, runtime.leases());
    }

    @Test void publishingTwiceForOneOwnerIsAProtocolError() {
        var runtime = runtime(OVERWORLD);
        var owner = new ChunkKey(OVERWORLD, 0, 0);
        runtime.publish(owner, plans(), 1);
        assertEquals(1, runtime.leases());
        assertThrows(WallFailure.class, () -> runtime.publish(owner, plans(), 1),
                "a second lease for the same owner must not silently replace the first");
    }

    @Test void preparedCorridorIsSharedWhileEachOwnerKeepsItsOwnMaskAndLease() {
        var runtime = runtime(OVERWORLD);
        var guard = new VegetationGuard();
        var first = new ChunkKey(OVERWORLD, 62, 0);
        var second = new ChunkKey(OVERWORLD, 63, 0);
        var corridor = CorridorIndex.prepareStartupWindow(plans(), OVERWORLD);
        var a = runtime.publishPrepared(first, corridor, 1);
        var b = runtime.publishPrepared(second, corridor, 1);
        guard.publishPrepared(first, corridor);
        guard.publishPrepared(second, corridor);
        assertSame(corridor.boxes(), a.hardBoxes());
        assertSame(a.hardBoxes(), b.hardBoxes());
        assertNotEquals(a.domain(), b.domain());
        // 释放一个区块不能扯掉另一区块使用的完整保护范围。
        guard.release(first);
        runtime.release(first);
        var canopy = new Bounds(1000, 0, 50, 1001, 8, 66);
        assertFalse(guard.intersects(first, canopy));
        assertTrue(guard.intersects(second, canopy));
        assertSame(b, runtime.require(second));
        assertTrue(b.touchesHard(canopy));
    }

    @Test void corruptOwnerKeyStillFailsClosed() {
        var runtime = runtime(OVERWORLD);
        // A right-dimension owner that was simply never published must fail loudly, never resolve to
        // an empty mask that would read as "no wall here".
        assertTrue(runtime.find(new ChunkKey(OVERWORLD, 99, 99)).isEmpty());
        assertThrows(WallFailure.class, () -> runtime.require(new ChunkKey(OVERWORLD, 99, 99)));
    }

    @Test void planWindowFromAnotherDimensionIsRejected() {
        var runtime = runtime(OVERWORLD);
        var owner = new ChunkKey(OVERWORLD, 0, 0);
        // The shared box builder refuses a plan whose own dimension disagrees, so a mismatched
        // window can never be compiled into an overworld mask.
        var foreign = new PlanWindow(List.of(planIn("minecraft:the_nether")));
        assertThrows(IllegalArgumentException.class, () -> runtime.publish(owner, foreign, 1));
        assertEquals(0, runtime.leases(), "a rejected window must not leave a lease behind");
    }

    @Test void releaseAndCloseAreIdempotentAndDrain() throws Exception {
        var runtime = runtime(OVERWORLD);
        var owner = new ChunkKey(OVERWORLD, 0, 0);
        runtime.publish(owner, plans(), 1);
        runtime.release(owner);
        assertEquals(0, runtime.leases());

        runtime.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(runtime.closed());
        assertEquals(0, runtime.leases());
        // Closing twice must be harmless.
        runtime.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(runtime.closed());
        assertThrows(WallFailure.class, () -> runtime.publish(owner, plans(), 1));
    }

    private static PlanWindow plans() {
        return new PlanWindow(List.of(planIn(OVERWORLD)));
    }

    /** A single-segment plan whose geometry claims the given dimension. */
    private static SegmentPlan planIn(String dimension) {
        var recipe = PlanningFixtures.recipe(0, dimension);
        var geometry = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        var stations = new StationTable(List.of(new Station(0, new Point2q(1000L * 256, 0), 84, 0),
                new Station(4 * 256, new Point2q(1004L * 256, 0), 84, 0)), List.of(), PlanningFixtures.HASH);
        return new SegmentPlan(geometry,
                new PrefixSummary(0, 0, Optional.empty(), PlanningFixtures.HASH), stations, List.of(),
                PlanningFixtures.HASH);
    }
}
