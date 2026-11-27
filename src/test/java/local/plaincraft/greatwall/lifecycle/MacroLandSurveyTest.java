package local.plaincraft.greatwall.lifecycle;

import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用近处相同、远处一半是海的地形，证明宏观选线没有退化为只看入口。 */
class MacroLandSurveyTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 320, 30_000_000);
    @Test void comparesBothHalvesUsingOriginalOceanBiomeEvenWhenCoarseDensityIsDry() {
        var survey = new MacroLandSurvey(new Point3i(0, 64, 0), BOUNDS, 8192);
        try (var oracle = new AnalyticTerrainOracle((x,z) -> new ColumnSample(64, OptionalInt.empty(),
                OptionalInt.empty(), Math.abs(x) > 1000 ? "minecraft:ocean" : "minecraft:plains",
                Quality.COARSE, false), true)) {
            while (!survey.done()) survey.advance(oracle);
            assertEquals(4096, survey.radius());
            // 圆域外的角格也被判定过，但不会调用地形探针。
            assertEquals(2209, survey.gridCells());
            assertEquals(survey.gridCells(), survey.visitedCells());
            assertEquals(1789, survey.sampleCount());
            assertEquals(survey.sampleCount(), oracle.begun());
            assertTrue(survey.score(axis(0, 1 << 30)) > survey.score(axis(1 << 30, 0)));
        }
    }
    @Test void unlimitedLooksForwardThroughTheWholeConfiguredWindow() {
        var window=RootAxisWindow.of(0,100000);
        // 海只位于 6 万到 9 万格；双向评分、只查一半以及以 5 万截断都会令东西平手。
        for (int side : new int[]{-1,1}) {
            var survey=new MacroLandSurvey(new Point3i(0,64,0),BOUNDS,window,MacroRiverWater.NONE,2500);
            try(var oracle=new AnalyticTerrainOracle((x,z)->new ColumnSample(64,OptionalInt.empty(),
                    OptionalInt.empty(),side*x>=60000 && side*x<=90000?"minecraft:ocean":"minecraft:plains",
                    Quality.COARSE,false),true)) {
                while(!survey.done()) survey.advance(oracle);
                assertTrue(oracle.begun()>100 && oracle.begun()<=2401);
                assertTrue(survey.score(axis(-side*(1<<30),0))>survey.score(axis(side*(1<<30),0)));
            }
        }
    }

    @Test void inlandWaterCountsForAxisSelectionWithoutTurningRiversIntoOceanBreaks() {
        // 河谷平原和以 ocean 为包名的普通山坡不是水体，避免把模组整类地形判成水。
        assertTrue(MacroLandSurvey.waterBiome("minecraft:frozen_river"));
        assertTrue(MacroLandSurvey.waterBiome("pack:glacial_lake"));
        assertFalse(MacroLandSurvey.waterBiome("ocean:foothills"));
        assertFalse(MacroLandSurvey.waterBiome("pack:riverside"));
        assertFalse(local.plaincraft.greatwall.planning.OceanRouteScan.isOcean("minecraft:frozen_river"));
    }
    @Test void maximumLengthKeepsSamplingBoundedAndUnknownWorldBorderIsPenalized() {
        var survey = new MacroLandSurvey(new Point3i(0, 64, 0), BOUNDS, 100000);
        try (var oracle = new AnalyticTerrainOracle((x,z) -> AnalyticTerrainOracle.dry(64), true)) {
            while (!survey.done()) survey.advance(oracle);
            assertEquals(50000, survey.radius());
            assertTrue(oracle.begun() <= 2401);
            assertTrue(oracle.begun() > 100);
            assertTrue(survey.score(axis(1 << 30, 0)) > survey.score(
                    new Axis(new Point3i(50000, 64, 0), 1 << 30, 0)));
        }
    }
    @Test void originalPlainsCanStillCarryPlannedRiversAndNoPlateIsRequestedOutsideTheBudget() {
        var queried = new java.util.HashSet<Long>();
        var source = new MacroRiverWater(2048, "水文版本一", (x,z) -> {
            assertTrue((long)x*x + (long)z*z <= 2048L*2048L);
            assertTrue(queried.add(((long)x << 32) ^ Integer.toUnsignedLong(z)), "每格最多一次模组水文查询");
            return x == 0 && Math.abs(z) > 256;
        });
        var survey = new MacroLandSurvey(new Point3i(0,64,0), BOUNDS, 8192, source);
        try (var oracle = new AnalyticTerrainOracle((x,z) -> AnalyticTerrainOracle.dry(65), true)) {
            while (!survey.done()) survey.advance(oracle);
            assertTrue(queried.size() > 100);
            assertTrue(queried.size() < survey.sampleCount(), "不得把整个选线半径交给河流建板");
            assertTrue(survey.score(axis(1 << 30,0)) > survey.score(axis(0,1 << 30)),
                    "同高原生平原中也要优先选不横穿模组水网的走向");
            assertNotEquals(source.rootCacheKey("轴"), new MacroRiverWater(1024, "水文版本一", (x,z) -> false).rootCacheKey("轴"));
            assertNotEquals(source.rootCacheKey("轴"), new MacroRiverWater(2048, "水文版本二", (x,z) -> false).rootCacheKey("轴"));
        }
    }

    @Test void riverSurveyFailureDoesNotBecomeDryLandOrAFatalServerError() {
        var problem = new MacroRiverWater.Unavailable("水文中断", new IllegalStateException("世界已停止"));
        var survey = new MacroLandSurvey(new Point3i(0,64,0), BOUNDS, 512,
                new MacroRiverWater(2048, "故障", (x,z) -> { throw problem; }));
        try (var oracle = new AnalyticTerrainOracle((x,z) -> AnalyticTerrainOracle.dry(70), true)) {
            assertSame(problem, assertThrows(MacroRiverWater.Unavailable.class, () -> {
                while (!survey.done()) survey.advance(oracle);
            }));
            assertTrue(ProductionSession.survivableReason(problem).contains("水文中断"));
            assertThrows(IllegalStateException.class, () -> survey.score(axis(1 << 30,0)));
        }
    }

    @Test void officialChannelAndLakeDetermineWaterButDryBanksDoNot() {
        var lakeCalls = new java.util.concurrent.atomic.AtomicInteger();
        var session = new dev.streamsreflowing.api.RiverPlanApi.Session() {
            public Object identity() { return this; }
            public dev.streamsreflowing.api.RiverPlanApi.Column column(int x, int z) {
                return new dev.streamsreflowing.api.RiverPlanApi.Column(x == -1, true, 72, 68, 0, 5);
            }
            public dev.streamsreflowing.api.RiverPlanApi.Lake lake(int x, int z) {
                lakeCalls.incrementAndGet();
                return new dev.streamsreflowing.api.RiverPlanApi.Lake(x == 1, 72, 69);
            }
            public java.util.List<dev.streamsreflowing.api.RiverPlanApi.Plate> platesNear(double x, double z, double radius) {
                throw new AssertionError("长城不能主动枚举板块");
            }
            public void prewarm(dev.streamsreflowing.api.RiverPlanApi.Plate plate) { fail("长城不能主动扩张河流预热"); }
            public dev.streamsreflowing.api.RiverPlanApi.Startup startup() { throw new AssertionError(); }
            public dev.streamsreflowing.api.RiverPlanApi.MacroWaterScope macroWaterScope() {
                return new dev.streamsreflowing.api.RiverPlanApi.MacroWaterScope(16, "湖川");
            }
        };
        var origin = new Point3i(0, 64, 0);
        var water = MacroRiverWater.Contract.from(session);
        assertTrue(water.wet(origin,-1,0));
        assertEquals(0, lakeCalls.get(), "河道已知就不补查湖");
        assertTrue(water.wet(origin,1,0)); assertFalse(water.wet(origin,0,0));
        assertFalse(water.wet(origin,1,17)); assertEquals(2, lakeCalls.get());
    }

    private static Axis axis(int x, int z) { return new Axis(new Point3i(0, 64, 0), x, z); }
}
