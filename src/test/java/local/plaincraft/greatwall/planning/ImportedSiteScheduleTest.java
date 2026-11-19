package local.plaincraft.greatwall.planning;

import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImportedSiteScheduleTest {
    static WorldRecipe recipe() {
        var old = PlanningFixtures.recipe(9282026);
        var c = old.config();
        return new WorldRecipe(old.instance(), old.wallId(), old.seed(), old.dimension(), old.frozenSpawn(), old.axis(), old.bounds(),
                new GenerationConfig(c.algorithmVersion(), 2, c.segmentBlocks(), c.stationBlocks(), c.columnBudget(),
                        c.carrierAmplitude(), c.corridorHalfWidth(), c.maxGeometryInflight(), c.segmentBytes(), c.terrainBytes(), c.compiledBytes(),
                        c.parametersHash()), old.terrainHash(), old.paletteHash(), old.templatesHash(), old.recipeHash());
    }

    @Test void allScheduledBuildingsStayCardinalOnDiagonalAndReversedRoutes() {
        var r = recipe();
        for (int dx : new int[] {-1, 1}) for (int dz : new int[] {-1, 1}) {
            var points = List.of(new Point2q(128, 128), new Point2q(dx * 6000L * 256 + 128, dz * 3000L * 256 + 128));
            var arcs = ArcLength.accumulate(points);
            var g = new SegmentGeometry(new SegmentKey(r.recipeHash(), r.dimension(), 0), points, arcs, List.of(), Optional.empty(),
                    new Boundary(0, points.getFirst(), 84, PlanningFixtures.HASH),
                    new Boundary(1, points.getLast(), 84, PlanningFixtures.HASH), 0, 0, PlanningFixtures.HASH);
            var sites = new SiteScheduler(r).schedule(new GeometryWindow(List.of(g), List.of(PrefixSummaryIndex.origin())),
                    0, arcs.getLast());
            assertTrue(sites.size() > 20, "先证明调度确实产生站点，避免空集合让轴向断言空转");
            assertEquals(5, sites.stream().map(s -> ImportedSiteGeometry.asset(s.id())).distinct().count());
            assertTrue(sites.stream().anyMatch(s -> s.state() == SiteState.PLANNED && ImportedSiteGeometry.asset(s.id()) == Asset.GATE));
            for (var site : sites) {
                var f = site.frame();
                assertEquals(1L << 30, Math.abs((long) f.txQ30()) + Math.abs((long) f.tzQ30()));
                assertEquals(0L, (long) f.txQ30() * f.tzQ30());
                assertEquals(128, Math.floorMod(f.center().xQ8(), 256));
                assertEquals(128, Math.floorMod(f.center().zQ8(), 256));
                assertEquals(ImportedSiteGeometry.bounds(site), site.bounds());
                if (site.state() == SiteState.SUPPRESSED) assertEquals(BreakReason.CONFLICT, site.reason().orElseThrow());
            }
        }
    }
}
