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
            assertTrue(survey.sampleCount() > 100);
            assertTrue(survey.sampleCount() <= 2401);
            assertEquals(survey.sampleCount(), oracle.begun());
            assertTrue(survey.score(axis(0, 1 << 30)) > survey.score(axis(1 << 30, 0)));
        }
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
    private static Axis axis(int x, int z) { return new Axis(new Point3i(0, 64, 0), x, z); }
}
