package local.plaincraft.greatwall.decay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import local.plaincraft.greatwall.component.BeaconTower;
import local.plaincraft.greatwall.component.ComponentTemplate;
import local.plaincraft.greatwall.component.EnemyTower;
import local.plaincraft.greatwall.decay.PaletteTypes.*;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * S4 JUnit acceptance: every palette table sums to 100 with positive weights, only real
 * registry blocks/properties bind (hay_bale / mossy_deepslate / lantern.lit are rejected),
 * and all four material families preserve occupancy while differing only in block state.
 */
class PaletteTest {
    private static final String HASH = "a".repeat(64);
    private static final long SEED = 0x123456789abcdefL;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyTableSumsTo100() {
        var catalog = PaletteResolver.defaults();
        int count = 0;
        for (var variant : Variant.values()) {
            for (var decay : DecayState.values()) {
                for (var part : Part.values()) {
                    var table = catalog.tables().get(variant).get(decay).get(part);
                    assertEquals(variant, table.variant(), variant + "/" + decay + "/" + part);
                    assertEquals(decay, table.decay(), variant + "/" + decay + "/" + part);
                    assertEquals(part, table.part(), variant + "/" + decay + "/" + part);
                    assertFalse(table.entries().isEmpty(), variant + "/" + decay + "/" + part);
                    assertTrue(table.entries().stream().allMatch(e -> e.weight() > 0),
                            variant + "/" + decay + "/" + part + " must have positive weights");
                    assertEquals(100, table.entries().stream().mapToInt(WeightedState::weight).sum(),
                            variant + "/" + decay + "/" + part + " must sum to 100");
                    count++;
                }
            }
        }
        assertEquals(320, count, "4 families x 4 states x 20 parts must all be present");
        assertEquals(catalog.hash(), PaletteResolver.defaults().hash());
    }

    @Test
    void onlyRegisteredBlocksAndProperties() {
        // The resource id is hay_block, not hay_bale; mossy_deepslate does not exist.
        invalid(() -> BlockCatalog.resolveState(new BlockSpec("minecraft:hay_bale", Map.of())));
        invalid(() -> BlockCatalog.resolveState(new BlockSpec("minecraft:mossy_deepslate", Map.of())));
        // lantern has no lit property in 1.21.1.
        invalid(() -> BlockCatalog.resolveState(new BlockSpec("minecraft:lantern", Map.of("lit", "false"))));
        invalid(() -> BlockCatalog.resolveState(new BlockSpec("minecraft:stone_bricks", Map.of("unknown", "false"))));
        // Positive controls: the real ids and properties parse to a non-null BlockState.
        assertNotNull(BlockCatalog.resolveState(new BlockSpec("minecraft:hay_block", Map.of("axis", "y"))));
        assertNotNull(BlockCatalog.resolveState(new BlockSpec("minecraft:lantern", Map.of("hanging", "true"))));
        assertNotNull(BlockCatalog.resolveState(new BlockSpec("minecraft:stone_brick_stairs",
                Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"))));
    }

    @Test
    void allFamiliesPreserveOccupancy() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        // Decay is forced to INTACT so the full component is compiled for every family;
        // only the material family (and thus the bound block id) is allowed to vary.
        for (var kind : List.of(SiteKind.ENEMY_TOWER, SiteKind.BEACON)) {
            ComponentTemplate template = kind == SiteKind.ENEMY_TOWER
                    ? new EnemyTower(new DecayField(0), resolver)
                    : new BeaconTower(new DecayField(0), resolver);
            ComponentVoxels baseline = null;
            for (var variant : Variant.values()) {
                var component = template.compile(input(kind, DecayState.INTACT, variant, 0));
                assertFalse(component.voxels().isEmpty(), kind + "/" + variant);
                if (baseline == null) {
                    baseline = component;
                    continue;
                }
                assertEquals(occupancy(baseline), occupancy(component),
                        kind + "/" + variant + " solid/void must match");
                assertEquals(baseline.requiredRoutes(), component.requiredRoutes(),
                        kind + "/" + variant + " routes must match");
                assertEquals(baseline.blockEntities(), component.blockEntities(),
                        kind + "/" + variant + " block entities must match");
                assertEquals(baseline.spawnSlots(), component.spawnSlots(),
                        kind + "/" + variant + " spawn slots must match");
                assertEquals(baseline.realizedState(), component.realizedState(),
                        kind + "/" + variant + " realized state must match");
                // Materials differ: the same voxel positions bind different block ids per family.
                assertNotEquals(stateIds(baseline), stateIds(component),
                        kind + "/" + variant + " must differ only in block state");
            }
        }
    }

    private static ComponentInput input(SiteKind kind, DecayState state, Variant variant, int heading) {
        double radians = heading * StrictMath.PI / 180;
        var frame = new Frame(new Point2q(16L * 256, -17L * 256),
                (int) StrictMath.round(StrictMath.cos(radians) * (1L << 30)),
                (int) StrictMath.round(StrictMath.sin(radians) * (1L << 30)), 84, false);
        var local = kind == SiteKind.BEACON ? new Bounds(-5, -32, -5, 5, 7, 5) : new Bounds(-5, -32, -5, 6, 8, 6);
        var bounds = Rasterizer.worldBounds(frame, local);
        var site = new SiteRecord(new SiteId("palette-fixture", kind, 1), 1024L * 256, frame,
                bounds, SiteState.PLANNED, Optional.empty(), Optional.empty());
        var supports = new HashMap<Point3i, ProbeResult>();
        for (int z = bounds.minZ(); z < bounds.maxZExclusive(); z++) {
            for (int x = bounds.minX(); x < bounds.maxXExclusive(); x++) {
                supports.put(new Point3i(x, 84, z), new ProbeResult(ProbeKind.SUPPORTED, 64,
                        OptionalInt.empty(), OptionalInt.empty(), 0, HASH));
            }
        }
        var amount = switch (state) {
            case INTACT -> 6554;
            case PARTIAL -> 27853;
            case RUIN -> 43581;
            case BURIED -> 58982;
        };
        var height = switch (state) {
            case INTACT -> 20;
            case PARTIAL -> 12;
            case RUIN -> 4;
            case BURIED -> 2;
        };
        return new ComponentInput(site, List.of(
                new Station(0, Projection.local(frame, -1024L * 256, 0), 84, 0),
                new Station(2048L * 256, Projection.local(frame, 1024L * 256, 0), 84, 0)),
                supports, new DecaySample(state, amount, height), PaletteFamily.STONE, false, List.of(),
                List.of(new BiomeStation(0, "minecraft:plains", variant),
                        new BiomeStation(2048L * 256, "minecraft:plains", variant)));
    }

    private static List<List<Object>> occupancy(ComponentVoxels component) {
        return component.voxels().stream().map(voxel -> List.<Object>of(voxel.pos(), voxel.kind(),
                voxel.part(), voxel.required(), voxel.walkable())).toList();
    }

    private static List<String> stateIds(ComponentVoxels component) {
        return component.voxels().stream().map(voxel -> voxel.state().id()).toList();
    }

    private static void invalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(FailureCode.INVALID_RESOURCE, assertThrows(WallFailure.class, action).code());
    }
}
