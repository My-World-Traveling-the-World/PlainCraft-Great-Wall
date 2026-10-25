package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class WallFoundationPlannerTest {
    private static WallFinePlanner.FineColumn col(int x, int z, int lateral, int ground) {
        return new WallFinePlanner.FineColumn(x, z, lateral,
                new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false));
    }

    @Test void baseIsHighestColumnAndLowerColumnsFill() {
        // Centre at x=1000,z=0, cross-section: centre=90, -5 side=60 (drops 30), +5 side=85.
        var samples = List.of(
                col(1000, 0, 0, 90),
                col(1000, -5, -5, 60),
                col(1000, -4, -4, 62),
                col(1000, -3, -3, 65),
                col(1000, -2, -2, 70),
                col(1000, -1, -1, 80),
                col(1000, 1, 1, 88),
                col(1000, 2, 2, 87),
                col(1000, 3, 3, 86),
                col(1000, 4, 4, 85),
                col(1000, 5, 5, 85));
        var plan = WallFoundationPlanner.plan(samples);
        assertEquals(1, plan.size());
        var foundation = plan.get(0);
        assertEquals(90, foundation.baseY(), "base = highest surface");
        // Fills for every column below 90 (all except the centre at 90).
        assertTrue(foundation.fills().size() >= 9, "all lower columns filled");
        for (var fill : foundation.fills()) {
            assertTrue(fill.surfaceY() < foundation.baseY(), "fill only when below base");
        }
    }

    @Test void flatRidgeHasNoFill() {
        var samples = List.of(
                col(1000, 0, 0, 80),
                col(1000, -5, -5, 80),
                col(1000, -4, -4, 80),
                col(1000, 5, 5, 80));
        var plan = WallFoundationPlanner.plan(samples);
        assertEquals(1, plan.size());
        assertTrue(plan.get(0).fills().isEmpty(), "flat cross-section needs no fill");
        assertEquals(80, plan.get(0).baseY());
    }

    @Test void productionIdentityKeepsCentreSectionsSeparateAndCarriesBaseOnEveryFill() {
        var samples = List.of(
                new WallFinePlanner.FineColumn(1000, 0, 0, sample(90), 5000, 1000, 0),
                new WallFinePlanner.FineColumn(1000, -5, -5, sample(60), 5000, 1000, 0),
                new WallFinePlanner.FineColumn(1000, 5, 5, sample(85), 5000, 1000, 0),
                new WallFinePlanner.FineColumn(1001, 0, 0, sample(88), 5256, 1001, 0),
                new WallFinePlanner.FineColumn(1001, -5, -5, sample(70), 5256, 1001, 0),
                new WallFinePlanner.FineColumn(1001, 5, 5, sample(86), 5256, 1001, 0));
        var plan = WallFoundationPlanner.plan(samples);
        assertEquals(2, plan.size());
        assertEquals(5000, plan.get(0).globalSQ8());
        assertEquals(5256, plan.get(1).globalSQ8());
        assertEquals(90, plan.get(0).baseY());
        assertEquals(60, plan.get(0).fills().getFirst().surfaceY());
        assertEquals(90, plan.get(0).fills().getFirst().baseY());
        assertEquals(88, plan.get(1).baseY());
    }

    @Test void negativeSideIsNotLostAndOutOfOrderInputDoesNotMergeSections() {
        var a = new WallFinePlanner.FineColumn(1000, -5, -5, sample(60), 5000, 1000, 0);
        var b = new WallFinePlanner.FineColumn(1000, 0, 0, sample(90), 5000, 1000, 0);
        var c = new WallFinePlanner.FineColumn(1000, 5, 5, sample(85), 5000, 1000, 0);
        var d = new WallFinePlanner.FineColumn(1001, -5, -5, sample(70), 5256, 1001, 0);
        var e = new WallFinePlanner.FineColumn(1001, 0, 0, sample(88), 5256, 1001, 0);
        var f = new WallFinePlanner.FineColumn(1001, 5, 5, sample(86), 5256, 1001, 0);
        var plan = WallFoundationPlanner.plan(List.of(c, a, e, b, f, d));
        assertEquals(2, plan.size());
        assertEquals(2, plan.get(0).fills().size(), "both negative and positive lower columns kept");
        assertEquals(2, plan.get(1).fills().size());
    }

    private static ColumnSample sample(int ground) {
        return new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(),
                "minecraft:plains", Quality.REFERENCE, false);
    }

    @Test void multipleCentresKeepOrderAndBase() {
        var samples = List.of(
                col(1000, 0, 0, 90), col(1000, -5, -5, 60), col(1000, 5, 5, 85),
                col(1001, 0, 0, 88), col(1001, -5, -5, 70), col(1001, 5, 5, 86));
        var plan = WallFoundationPlanner.plan(samples);
        assertEquals(2, plan.size());
        assertEquals(90, plan.get(0).baseY());
        assertEquals(88, plan.get(1).baseY());
        assertEquals(1000, plan.get(0).centreX());
        assertEquals(1001, plan.get(1).centreX());
    }
}
