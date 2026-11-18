package local.plaincraft.greatwall.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.VoxelIntent;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ImportedShowcaseTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest @EnumSource(Asset.class)
    void eachExhibitWritesItsRealBuildingAndAttachedWalls(Asset asset) {
        var feet = new Point3i(-400, -45, asset.ordinal() * 160);
        var plot = ImportedShowcase.plot(asset, 0, 0);
        var plan = ImportedShowcase.compile(asset, feet, 42);
        var writes = new HashMap<Point3i, VoxelIntent>();
        for (int cursor = 0; cursor < plan.voxels().size();) {
            int next = ImportedShowcase.writeSlice(plan, cursor, v -> assertNull(writes.put(v.pos(), v)));
            assertTrue(next > cursor && next - cursor <= 4000, "一批必须前进且保留每 tick 上限");
            cursor = next;
        }
        // 用导出清单实数作独立判据；墙为每四列 678 个实物格，净空不计落块。
        int building = switch (asset) {
            case GATE -> 37521;
            case ENEMY_TOWER -> 19812;
            case BEACON -> 9253;
            case CORNER_TOWER -> 9119;
            case CHENGHAI_TOWER -> 117425;
        };
        assertEquals(building + 678 * 6 * (asset == Asset.CHENGHAI_TOWER ? 1 : 2), writes.size());
        assertTrue(writes.keySet().stream().allMatch(p -> p.x() >= plot.minX() && p.x() <= plot.maxX()
                && p.z() >= plot.minZ() && p.z() <= plot.maxZ()));
        // 门洞贯通，放置器不能把声明的净空写成实体或填入地基。
        if (asset == Asset.GATE) for (int z = -19; z <= 12; z++) {
            assertFalse(writes.containsKey(new Point3i(-400, -60, z)));
        }
    }

    @Test void oldWorkshopWithoutAuthorizationIsUntouched(@TempDir Path root) throws Exception {
        assertTrue(ImportedShowcaseReceipt.open(root, false, 42, 10, 20).isEmpty());
        assertFalse(Files.exists(root.resolve("greatwall")));
    }

    @Test void interruptedWorkshopKeepsOriginalLocationAndOnlySkipsSavedPieces(@TempDir Path root) throws Exception {
        var first = ImportedShowcaseReceipt.open(root, true, -42, 10, 20).orElseThrow();
        assertEquals(0, first.completed());
        first.save(root, 0b00101);
        var reopened = ImportedShowcaseReceipt.open(root, false, -42, 90, 100).orElseThrow();
        assertEquals(new ImportedShowcaseReceipt(-42, 10, 20, 0b00101), reopened);
        assertTrue(reopened.contains(0));
        assertTrue(reopened.contains(2));
        assertFalse(reopened.contains(1));
        assertThrows(java.io.IOException.class, () -> ImportedShowcaseReceipt.open(root, false, 42, 10, 20));
    }
}
