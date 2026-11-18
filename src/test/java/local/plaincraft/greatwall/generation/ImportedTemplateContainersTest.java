package local.plaincraft.greatwall.generation;

import java.util.HashSet;
import local.plaincraft.greatwall.component.ImportedWallTemplate;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.gameplay.TemplateLoot;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportedTemplateContainersTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void allFiveRealTemplatesGiveEveryChestAndBarrelASeedWithoutCopyingSampleStock() {
        // 用实物数量防空转：漏掉整个模板或一类容器都不能只靠循环内部的断言放行。
        int[] solids = {37521, 19812, 9253, 9119, 117425};
        int[] entities = {44, 73, 23, 52, 439};
        int[] containers = {18, 21, 4, 25, 142};
        var allSeeds = new HashSet<Long>();
        for (Asset asset : Asset.values()) {
            var template = asset.template();
            assertSame(template, asset.template());
            assertEquals(solids[asset.ordinal()], template.cells().size());
            int entityCount = 0, containerCount = 0;
            var site = new SiteId("fixture-wall", SiteKind.GATE, asset.ordinal());
            for (var cell : template.cells()) {
                if (cell.entity() == null) continue;
                entityCount++;
                var intent = template.entityIntent(cell, cell.local(), 314159, site);
                var tag = PlannedBlockEntityNbt.encode(intent);
                if (cell.entity().type().equals("minecraft:barrel") || cell.entity().type().equals("minecraft:chest")) {
                    containerCount++;
                    assertEquals("{}", cell.entity().decoration());
                    assertTrue(TemplateLoot.isLootTable(tag.getString("LootTable")));
                    assertNotEquals(0L, tag.getLong("LootTableSeed"));
                    assertTrue(allSeeds.add(tag.getLong("LootTableSeed")));
                    assertFalse(tag.contains("Items"));
                }
            }
            assertEquals(entities[asset.ordinal()], entityCount);
            assertEquals(containers[asset.ordinal()], containerCount);
        }
        assertEquals(210, allSeeds.size());
    }

    @Test void cornerTurnRotatesStairStateAndBothWallEndsTogether() {
        var corner = Asset.CORNER_TOWER.template();
        var stair = corner.cells().stream().filter(cell -> corner.state(cell, Rotation.NONE).id().endsWith("_stairs"))
                .findFirst().orElseThrow();
        String facing = corner.state(stair, Rotation.NONE).properties().get("facing");
        var expected = net.minecraft.core.Direction.byName(facing).getClockWise().getName();
        assertEquals(expected, corner.state(stair, Rotation.CLOCKWISE_90).properties().get("facing"));
        // 原样北端指向东，东端指向南，避免用建筑正面代替墙端轴。
        assertEquals(new Point3i(1, 0, 0), ImportedWallTemplate.rotateOffset(new Point3i(0, 0, -1), Rotation.CLOCKWISE_90));
        assertEquals(new Point3i(0, 0, 1), ImportedWallTemplate.rotateOffset(new Point3i(1, 0, 0), Rotation.CLOCKWISE_90));
    }
}
