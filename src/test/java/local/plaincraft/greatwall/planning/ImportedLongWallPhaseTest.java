package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** 直接验证正式栅格化的百格墙，不能只测相位公式或首四格。 */
class ImportedLongWallPhaseTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @EnumSource(Asset.class)
    void everyAssetAndRotationKeepsLongWallFloorsAndContinuousBattlements(Asset asset) {
        for (int rotation = 0; rotation < 4; rotation++) {
            var fixture = ImportedProductionPlanTest.fixture(asset, rotation);
            var site = fixture.site();
            var ports = ImportedSiteGeometry.layout(site).ports();
            for (int p = 0; p < ports.size(); p++) {
                var port = ports.get(p);
                boolean incoming = ports.size() == 2 && p == 0;
                long portS = ImportedSiteGeometry.portArc(site, p) / 256;
                long from = incoming ? portS - 128 : portS, to = incoming ? portS : portS + 128;
                var stations = new ArrayList<Station>();
                for (long s = from; s <= to; s++) {
                    long step = incoming ? portS - s : s - portS;
                    stations.add(new Station(s * 256, new Point2q(
                            port.feet().x() * 256L + 128 + step * 256 * port.outward().getStepX(),
                            port.feet().z() * 256L + 128 + step * 256 * port.outward().getStepZ()),
                            port.feet().y(), 0));
                }
                var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
                var flat = fixture.columns().values().iterator().next();
                for (var station : stations) {
                    int x = (int)Math.floorDiv(station.point().xQ8(), 256), z = (int)Math.floorDiv(station.point().zQ8(), 256);
                    for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++)
                        columns.put(new Point3i(x + dx, 0, z + dz), flat);
                }
                var blocks = new HashMap<Point3i, VoxelIntent>();
                for (long cell = Math.floorDiv(from, 64); cell <= Math.floorDiv(to - 1, 64); cell++) {
                    var id = new SiteId(site.id().wallId(), SiteKind.WALL, cell);
                    var wall = new SiteRecord(id, (cell * 64 + 32) * 256, site.frame(), site.bounds(),
                            SiteState.PLANNED, Optional.empty(), Optional.empty());
                    var input = new ComponentInput(wall, stations, Map.of(), new DecaySample(DecayState.INTACT, 0, 20),
                            PaletteFamily.STONE, false, List.of(), List.of(), new GroundEntranceContract.Evidence(
                                    GroundEntranceContract.Source.DURABLE_T0, fixture.recipe().bounds(), columns));
                    var interval = new ArcInterval(from * 256, to * 256);
                    var compiled = new ImportedRibbonSection(fixture.recipe().seed()).compileIntervals(input,
                            List.of(interval), List.of(), ignored -> new DecaySample(DecayState.INTACT, 0, 20),
                            List.of(), List.of(site));
                    assertFalse(compiled.voxels().isEmpty(), "每个参与的 cell 都必须真的有墙");
                    for (var voxel : compiled.voxels()) blocks.put(voxel.pos(), voxel);
                }
                int solid = 0, gaps = 0, lastGap = 0;
                for (int step = 1; step <= 100; step++) {
                    var floor = blocks.get(port.cell(step, 0, -1));
                    assertNotNull(floor, asset + "/" + rotation + "/" + p + " 马道 " + step);
                    assertTrue(floor.walkable(), "完整地板是节奏断言的前置条件");
                    var top = blocks.get(port.cell(step, 5, 2));
                    boolean merlon = top != null && top.kind() == IntentKind.SOLID;
                    if (step <= 48) assertEquals(step % 4 != 0, merlon, "48 格内不允许切换原点");
                    if (merlon) solid++;
                    else {
                        assertTrue(step - lastGap >= 3 && step - lastGap <= 6, "过渡只改一轮长度，不能连续缺");
                        lastGap = step;
                        gaps++;
                    }
                }
                assertTrue(solid >= 65 && gaps >= 20, "百格实际墙必须同时有垛和缺，空集合不能过关");
            }
        }
    }
}
