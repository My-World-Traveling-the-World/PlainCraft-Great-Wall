package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;

class ScheduleContractTest {
    private static SegmentGeometry straight(WorldRecipe recipe, long index, int startY, int endY) {
        var points = List.of(new Point2q(index * 2048 * 256, 0), new Point2q((index + 1) * 2048 * 256, 0));
        var g = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), index), points,
                ArcLength.accumulate(points), List.of(), Optional.empty(),
                new Boundary(index, points.getFirst(), startY, "b" + index),
                new Boundary(index + 1, points.getLast(), endY, "b" + (index + 1)), 2, 0, PlanningFixtures.HASH);
        return new SegmentGeometry(g.key(), g.points(), g.localSQ8(), g.water(), g.terminal(), g.start(), g.end(), 2, 0,
                DeterministicHash.sha256(CanonicalCodec.geometry(g)));
    }

    @Test void towerOrdinalsCross2048Seam() {
        var recipe = PlanningFixtures.recipe(9282026); var scheduler = new SiteScheduler(recipe);
        var geometries = new ArrayList<SegmentGeometry>(); var prefixes = new ArrayList<PrefixSummary>();
        var prefix = PrefixSummaryIndex.origin();
        for (int i = 0; i < 4; i++) { var g = straight(recipe, i, 84, 84); geometries.add(g); prefixes.add(prefix); prefix = PrefixSummaryIndex.reduce(prefix, g); }
        var window = new GeometryWindow(geometries, prefixes);
        var sites = scheduler.schedule(window, 0, 8192L * 256);
        var towers = sites.stream().filter(s -> s.id().kind() == SiteKind.BEACON || s.id().kind() == SiteKind.ENEMY_TOWER).toList();
        assertEquals(27, towers.size());
        for (int i = 0; i < towers.size(); i++) {
            long n = i + 1; var site = towers.get(i);
            assertEquals(n * 300 * 256, site.globalSQ8());
            assertEquals(n % 2 == 0 ? SiteKind.BEACON : SiteKind.ENEMY_TOWER, site.id().kind());
            assertEquals(n % 2 == 0 ? n / 2 : n, site.id().ordinal());
        }
        assertEquals(towers.stream().filter(s -> s.globalSQ8() >= 2048L * 256 && s.globalSQ8() < 4096L * 256).map(SiteRecord::id).toList(),
                scheduler.schedule(window, 2048L * 256, 4096L * 256).stream()
                        .filter(s -> s.globalSQ8() >= 2048L * 256 && s.globalSQ8() < 4096L * 256)
                        .filter(s -> s.id().kind() == SiteKind.BEACON || s.id().kind() == SiteKind.ENEMY_TOWER).map(SiteRecord::id).toList());
        assertTrue(sites.stream().anyMatch(s -> s.id().kind() == SiteKind.FORT && s.id().ordinal() == 0));
        assertFalse(sites.stream().anyMatch(s -> s.id().kind() == SiteKind.GATE && s.id().ordinal() == 0));
    }

    @Test void gateAndFortGapsAreBounded() {
        var scheduler = new SiteScheduler(PlanningFixtures.recipe(1));
        long previousGate = 0, previousFort = 0;
        for (int i = 1; i <= 100000; i++) {
            long gate = scheduler.gatePositionQ8(i), fort = scheduler.fortPositionQ8(i);
            assertTrue(gate - previousGate >= 600 * 256 && gate - previousGate <= 1000 * 256);
            assertTrue(fort - previousFort >= 3000 * 256 && fort - previousFort <= 5000 * 256);
            previousGate = gate; previousFort = fort;
        }
    }

    @Test void platformAcrossSeamUsesOneHeight() {
        var recipe = PlanningFixtures.recipe(0);
        var planner = new HeightContractPlanner(recipe);
        var left = straight(recipe, 0, 84, 100); var right = straight(recipe, 1, 100, 84);
        var prefix = PrefixSummaryIndex.reduce(PrefixSummaryIndex.origin(), left);
        var site = new SiteRecord(new SiteId(recipe.wallId(), SiteKind.GATE, 3), 2048L * 256,
                new Frame(new Point2q(2048L * 256, 0), 1 << 30, 0, 84, false),
                new Bounds(2018, 52, -15, 2078, 88, 15), SiteState.PLANNED, Optional.empty(), Optional.empty());
        var sites = List.of(site);
        var a = solve(planner, left, PrefixSummaryIndex.origin(), sites, 64);
        var b = solve(planner, right, prefix, sites, 120);
        for (var station : a.stations()) if (station.globalSQ8() >= 2018L * 256) assertEquals(100, station.walkY());
        for (var station : b.stations()) if (station.globalSQ8() <= 2078L * 256) assertEquals(100, station.walkY());
        assertEquals(a.stations().getLast().walkY(), b.stations().getFirst().walkY());
        var scheduler = new SiteScheduler(recipe);
        assertEquals(scheduler.bindHeights(sites, a).getFirst().frame().walkY(),
                scheduler.bindHeights(sites, b).getFirst().frame().walkY());
    }

    @Test void infeasibleSlopeCreatesExplicitBreak() {
        var recipe = PlanningFixtures.recipe(0);
        var planner = new HeightContractPlanner(recipe);
        var g = straight(recipe, 0, 84, 3000);
        var table = solve(planner, g, PrefixSummaryIndex.origin(), List.of(), 64);
        assertEquals(84, table.stations().getFirst().walkY());
        assertEquals(3000, table.stations().getLast().walkY());
        assertEquals(1, table.breaks().size());
        assertEquals(BreakReason.SLOPE, table.breaks().getFirst().reason());
        assertEquals(BreakScope.PLANNED, table.breaks().getFirst().scope());
    }

    @Test void stationColumnsAreExplicitAndSlopeLimitsHold() {
        var recipe = PlanningFixtures.recipe(1);
        var planner = new HeightContractPlanner(recipe); var g = straight(recipe, 0, 84, 100);
        assertThrows(IllegalArgumentException.class, () -> planner.solve(g, PrefixSummaryIndex.origin(), List.of(), java.util.Map.of()));
        var table = solve(planner, g, PrefixSummaryIndex.origin(), List.of(), 90);
        for (int i = 1; i < table.stations().size(); i++) {
            var a = table.stations().get(i - 1); var b = table.stations().get(i);
            assertTrue(Math.abs(b.walkY() - a.walkY()) <= (b.globalSQ8() - a.globalSQ8()) * 3 / (4 * 256));
        }
        assertEquals(table.stations().getFirst().walkY(), planner.walkY(table, 0));
        assertEquals(table.stations().getLast().walkY(), planner.walkY(table, 2048L * 256));
        assertThrows(IllegalArgumentException.class, () -> planner.walkY(table, -1));
    }

    private static StationTable solve(HeightContractPlanner planner, SegmentGeometry g, PrefixSummary prefix,
            List<SiteRecord> sites, int height) {
        var columns = new HashMap<Point2q, ColumnSample>();
        for (var p : planner.samplePoints(g, prefix, sites)) columns.put(p, AnalyticTerrainOracle.dry(height));
        assertTrue(columns.size() <= 5998);
        return planner.solve(g, prefix, sites, columns);
    }
}
