package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ComponentComposer#merge}, the internal composition step, which had no test.
 *
 * <h2>Why this exists</h2>
 * Composition combines already-built children into one component. The rule its own javadoc gives is
 * the one worth protecting - "all children must already belong to the same complete site" - and its
 * failure mode is silent rather than loud: the voxel merge is a priority resolution, so if foreign
 * children were accepted or the priority order were wrong, the result would still be a plausible
 * component built from another site's pieces, and nothing downstream compares identities again.
 *
 * <p>The cases below drive the two explicit guards (empty composition, foreign identity) and the
 * observable consequences of correct merging (bounds union, voxel deduplication by position, priority
 * order, and the empty-child short circuit). Child payloads are built directly rather than by running
 * the assembly, so a failure points at the composer rather than at the assembler.
 */
class ComponentComposerTest {
    private static final Bounds BOUNDS = new Bounds(-2, -32, -2, 3, 4, 3);

    private static ComponentInput input(SiteKind kind) {
        return TemplateFixtures.input(kind, BOUNDS);
    }

    private static VoxelIntent voxel(int x, int y, int z, IntentKind kind, int priority,
            boolean required, boolean walkable) {
        return new VoxelIntent(new Point3i(x, y, z), kind, Part.CORE,
                new BlockSpec("minecraft:stone", Map.of()), input(SiteKind.ENEMY_TOWER).site().id(),
                priority, required, walkable);
    }

    /** A child carrying the given voxels, with an explicit identity and bounds. */
    private static ComponentVoxels child(SiteId id, Bounds bounds, VoxelIntent... voxels) {
        return new ComponentVoxels(id, bounds, List.of(voxels), List.of(), List.of(), List.of(),
                "0".repeat(64));
    }

    private static ComponentVoxels childOf(ComponentInput input, Bounds bounds, VoxelIntent... voxels) {
        return child(input.site().id(), bounds, voxels);
    }

    @Test void anEmptyCompositionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ComponentComposer.merge(input(SiteKind.ENEMY_TOWER), List.of()),
                "composing nothing must fail loudly rather than yield an empty component");
    }

    @Test void aForeignChildIdentityIsRejected() {
        var input = input(SiteKind.ENEMY_TOWER);
        var foreign = child(new SiteId("some-other-wall", SiteKind.ENEMY_TOWER, 0), BOUNDS,
                voxel(0, 0, 0, IntentKind.SOLID, 0, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentComposer.merge(input, List.of(foreign)),
                "a child from another site must not be absorbed silently: " + foreign.id());
    }

    @Test void theBoundsAreTheUnionOfEveryChild() {
        var input = input(SiteKind.ENEMY_TOWER);
        // Two fixture constraints decide where a voxel may sit for it to survive validate():
        // composer roots a voxel only when supports() has an entry at (x, frame.walkY(), z) AND the
        // voxel's y is at or below that ProbeResult's groundTopY, and TemplateFixtures reports
        // groundTopY = 64 over the frame's world columns at walkY = 84. A voxel at y = 84 therefore
        // gets no root and is pruned, which is what made the first two versions of this test fail with
        // an empty result rather than a wrong bounds. y = 64 satisfies both.
        var world = input.site().bounds();
        int y = 64;
        var left = childOf(input,
                new Bounds(world.minX(), y, world.minZ(), world.minX() + 1, y + 1, world.minZ() + 1),
                voxel(world.minX(), y, world.minZ(), IntentKind.SOLID, 0, false, false));
        var right = childOf(input,
                new Bounds(world.maxXExclusive() - 1, y, world.maxZExclusive() - 1,
                        world.maxXExclusive(), y + 1, world.maxZExclusive()),
                voxel(world.maxXExclusive() - 1, y, world.maxZExclusive() - 1, IntentKind.SOLID, 0, false, false));

        var merged = ComponentComposer.merge(input, List.of(left, right));

        assertFalse(merged.voxels().isEmpty(),
                "the fixture must survive validation for the bounds assertion to mean anything; world=" + world);
        assertEquals(world.minX(), merged.bounds().minX(), "the union must reach the leftmost child");
        assertEquals(world.maxXExclusive(), merged.bounds().maxXExclusive(),
                "the union must reach the rightmost child");
    }

    @Test void voxelsAtTheSamePositionAreDeduplicatedRatherThanDuplicated() {
        var input = input(SiteKind.ENEMY_TOWER);
        var a = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.SOLID, 0, true, false));
        var b = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.SOLID, 1, true, false));

        var merged = ComponentComposer.merge(input, List.of(a, b));

        assertEquals(1L, merged.voxels().stream()
                        .filter(v -> v.pos().equals(new Point3i(0, 0, 0))).count(),
                "two children claiming the same position must resolve to one voxel, not two");
    }

    @Test void mergingIsNotOrderDependentForTheSameChildren() {
        // The composer may not depend on the order children arrive in: the same set must produce the
        // same component either way, or the result becomes sensitive to construction order.
        var input = input(SiteKind.ENEMY_TOWER);
        var a = childOf(input, new Bounds(-4, -1, -1, 0, 1, 1),
                voxel(-2, 0, 0, IntentKind.SOLID, 0, true, false));
        var b = childOf(input, new Bounds(0, -1, -1, 4, 1, 1),
                voxel(2, 0, 0, IntentKind.SOLID, 0, true, false));

        assertEquals(ComponentComposer.merge(input, List.of(a, b)),
                ComponentComposer.merge(input, List.of(b, a)),
                "the merged component must be identical regardless of child order");
    }

    @Test void aChildWithNoVoxelsYieldsAnEmptyComponentRatherThanAPartialOne() {
        // The short circuit returns empty for the whole composition as soon as one child is empty, so a
        // half-built site can never be published as if it were finished.
        var input = input(SiteKind.ENEMY_TOWER);
        var full = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.SOLID, 0, true, false));
        var empty = childOf(input, BOUNDS);

        var merged = ComponentComposer.merge(input, List.of(full, empty));

        assertTrue(merged.voxels().isEmpty(),
                "one empty child must collapse the composition instead of publishing the other child");
    }

    @Test void anOwnedRequiredVoidWinsOverAnyOtherClaimAtTheSamePosition() {
        // An owned required void is what keeps a doorway open. If a solid voxel could outrank it, the
        // opening would be filled in and the entrance would silently disappear.
        var input = input(SiteKind.ENEMY_TOWER);
        var solid = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.SOLID, 9, true, false));
        var voidRequired = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.OWNED_VOID, 0, true, false));

        var merged = ComponentComposer.merge(input, List.of(solid, voidRequired));

        var atOrigin = merged.voxels().stream()
                .filter(v -> v.pos().equals(new Point3i(0, 0, 0))).findFirst().orElseThrow();
        assertEquals(IntentKind.OWNED_VOID, atOrigin.kind(),
                "a required owned void must survive a higher-priority solid claim");
    }

    @Test void aWalkableVoxelWinsOverANonWalkableOneAtTheSamePosition() {
        // Walkability is what the route and spawn checks read, so losing it would close a passage the
        // component was built to provide.
        var input = input(SiteKind.ENEMY_TOWER);
        var blocked = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.SOLID, 0, false, false));
        var passable = childOf(input, BOUNDS, voxel(0, 0, 0, IntentKind.SOLID, 0, false, true));

        var merged = ComponentComposer.merge(input, List.of(blocked, passable));

        var atOrigin = merged.voxels().stream()
                .filter(v -> v.pos().equals(new Point3i(0, 0, 0))).findFirst().orElseThrow();
        assertTrue(atOrigin.walkable(), "the walkable claim must win regardless of child order");
    }

    @Test void reframingReplacesTheFrameAndKeepsEverythingElse() {
        var input = input(SiteKind.ENEMY_TOWER);
        var newFrame = new Frame(new Point2q(4096L, 8192L), 1 << 30, 0, 96, false);

        var reframed = ComponentComposer.frame(input, newFrame, 42L);

        assertEquals(newFrame, reframed.site().frame(), "the frame must be replaced");
        assertEquals(42L, reframed.site().globalSQ8(), "the arc offset must be the one supplied");
        assertEquals(input.site().id(), reframed.site().id(), "the identity must not change");
        assertEquals(input.site().bounds(), reframed.site().bounds(), "the bounds must not change");
        assertEquals(input.site().state(), reframed.site().state(), "the state must not change");
        assertEquals(input.stations(), reframed.stations(), "stations must be carried over untouched");
        assertEquals(input.supports(), reframed.supports(), "supports must be carried over untouched");
    }

    @Test void reframingLeavesTheOriginalInputUnchanged() {
        // ComponentInput is shared, so reframing must build a new record rather than mutate in place.
        var input = input(SiteKind.ENEMY_TOWER);
        var originalFrame = input.site().frame();
        var replacement = new Frame(new Point2q(1L, 2L), 1 << 30, 0, 100, false);

        ComponentComposer.frame(input, replacement, 7L);

        assertEquals(originalFrame, input.site().frame(),
                "the caller's input must keep its own frame after a reframe");
        assertFalse(originalFrame.equals(replacement),
                "the replacement frame must actually differ, otherwise this test proves nothing");
    }
}
