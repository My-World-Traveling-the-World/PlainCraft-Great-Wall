package local.plaincraft.greatwall.gametest;

import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.FallingBlock;

/** Full Cartesian product for isolated-component native survival; not an adjacency certificate. */
public final class ComponentSurvivalMatrixTests implements FabricGameTest {
    private record Case(SiteKind kind, DecayState state, int heading, int drop) {}
    private static final int[] HEADINGS = {0, 15, 30, 45, 60, 90};
    private static final int[] DROPS = {0, 4, 5, 12, 13};

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-survival-matrix")
    public void allComponentStateHeadingDropSurvival(GameTestHelper helper) {
        var cases = new ArrayList<Case>();
        for (var kind : SiteKind.values()) for (var state : DecayState.values())
            for (int heading : HEADINGS) for (int drop : DROPS) cases.add(new Case(kind, state, heading, drop));
        helper.assertTrue(cases.size() == 960, "all 8 x 4 x 6 x 5 cases are scheduled");
        runCase(helper, List.copyOf(cases), 0, new ArrayList<>());
    }

    private static void runCase(GameTestHelper helper, List<Case> cases, int index, List<String> failures) {
        if (index == cases.size()) {
            System.out.println("S1B_SURVIVAL_MATRIX attempted=" + index + " failed=" + failures.size());
            for (var failure : failures) System.out.println("S1B_SURVIVAL_FAILURE " + failure);
            helper.assertTrue(failures.isEmpty(), "survival matrix failures=" + failures.size() + ": "
                    + failures.stream().limit(2).toList());
            helper.succeed();
            return;
        }
        var test = cases.get(index);
        try {
            var input = input(test);
            var c = template(test.kind()).compile(input);
            if (test.drop() == 13) {
                if (!c.voxels().isEmpty()) throw new AssertionError("drop13 must not build");
            } else {
                if (c.voxels().isEmpty() && (test.state() == DecayState.INTACT || test.state() == DecayState.PARTIAL))
                    throw new AssertionError("supported functional component unexpectedly empty");
                var states = new HashMap<Point3i, net.minecraft.world.level.block.state.BlockState>();
                for (var voxel : c.voxels()) states.put(voxel.pos(), BlockCatalog.resolveState(voxel.state()));
                var view = ComponentFixtures.view(Collections.unmodifiableMap(states));
                for (var entry : states.entrySet()) {
                    var p = entry.getKey(); var state = entry.getValue();
                    if (state.getBlock() instanceof FallingBlock) throw new AssertionError("gravity block at " + p);
                    if (!state.canSurvive(view, new BlockPos(p.x(), p.y(), p.z())))
                        throw new AssertionError("cannot survive: " + state + " at " + p);
                }
            }
        } catch (Exception | AssertionError failure) {
            failures.add(test + " " + failure);
        }
        helper.runAfterDelay(1, () -> runCase(helper, cases, index + 1, failures));
    }

    private static ComponentTemplate template(SiteKind kind) {
        var d = new DecayField(0); var p = ComponentFixtures.PALETTE;
        return switch (kind) {
            case WALL -> new WallSection(d, p); case ENEMY_TOWER -> new EnemyTower(d, p);
            case BEACON -> new BeaconTower(d, p); case GATE -> new Gate(d, p);
            case WATER_GATE -> new WaterGate(d, p); case END_CAP -> new EndCap(d, p);
            case RAMP -> new Ramp(d, p); case FORT -> new FortPlaceholder(d, p);
        };
    }

    private static ComponentInput input(Case test) {
        var bounds = switch (test.kind()) {
            case WALL -> new Bounds(-32,-32,-5,32,3,5);
            case ENEMY_TOWER -> new Bounds(-5,-33,-5,6,8,6);
            case BEACON -> new Bounds(-5,-32,-5,5,7,5);
            case GATE, END_CAP -> new Bounds(-30,-33,-15,30,4,15);
            case WATER_GATE -> new Bounds(-40,-32,-6,40,3,6);
            case RAMP -> new Bounds(-30,-33,4,-6,3,15);
            case FORT -> new Bounds(-24,-33,-24,24,8,24);
        };
        var base = ComponentFixtures.input(test.kind(), bounds, 0, 0, test.heading(), test.state());
        var supports = new HashMap<Point3i, ProbeResult>();
        int ground = 64 - test.drop();
        for (var point : base.supports().keySet()) {
            boolean wet = test.kind() == SiteKind.WATER_GATE
                    && Rasterizer.localCell(base.site().frame(), point).x() >= -24
                    && Rasterizer.localCell(base.site().frame(), point).x() < 25;
            supports.put(point, new ProbeResult(test.drop() > 12 ? ProbeKind.NO_SUPPORT : wet ? ProbeKind.WATER : ProbeKind.SUPPORTED,
                    ground, wet ? OptionalInt.of(66) : OptionalInt.empty(), wet ? OptionalInt.of(ground) : OptionalInt.empty(),
                    test.drop(), ComponentFixtures.H));
        }
        List<WaterEvidence> waters = List.of();
        if (test.kind() == SiteKind.WATER_GATE) {
            var f = base.site().frame();
            var left = Projection.local(f, -25*256L, 0); var right = Projection.local(f, 25*256L, 0);
            waters = List.of(new WaterEvidence(new ArcInterval((1024-24)*256L, (1024+25)*256L),
                    WaterClass.NARROW, 49*256L, 49*256L, 66,
                    List.of(new Point3i((int)Math.floorDiv(left.xQ8(),256),ground,(int)Math.floorDiv(left.zQ8(),256)),
                            new Point3i((int)Math.floorDiv(right.xQ8(),256),ground,(int)Math.floorDiv(right.zQ8(),256))),
                    List.of(new Point3i(0,ground,0)), ComponentFixtures.H));
        }
        return new ComponentInput(base.site(), base.stations(), supports, base.decay(), base.family(), false, waters, List.of());
    }
}
