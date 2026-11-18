package local.plaincraft.greatwall.generation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.component.ImportedWallTemplate;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.gameplay.TemplateLoot;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallFailure;
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

    @Test void actualColoredBannerAndWallSignKeepNativeEntityProofs() {
        var template = Asset.CHENGHAI_TOWER.template();
        var id = new SiteId("fixture-wall", SiteKind.GATE, 0);
        var voxels = new java.util.ArrayList<VoxelIntent>();
        var entities = new java.util.ArrayList<BlockEntityIntent>();
        var before = new java.util.HashMap<Point3i, String>();
        // 取实物调色板和实体；两个不同注册名必须同时进入施工计划与区块写入证明。
        for (String type : List.of("minecraft:banner", "minecraft:sign")) {
            var cell = template.cells().stream().filter(c -> c.entity() != null && c.entity().type().equals(type)
                    && !template.state(c, Rotation.NONE).id().equals(type)).findFirst().orElseThrow();
            var pos = new Point3i(15 + voxels.size(), 70, 1);
            var state = template.state(cell, Rotation.CLOCKWISE_90);
            voxels.add(new VoxelIntent(pos, IntentKind.SOLID, Part.CORE, state, id, 700, true, false));
            entities.add(template.entityIntent(cell, pos, 42, id));
            before.put(pos, ChunkPlanCompiler.token(new BlockSpec("minecraft:air", Map.of())));
        }
        var compiler = new ChunkPlanCompiler();
        String h = "a".repeat(64);
        var bounds = new Bounds(15, 70, 1, 17, 71, 2);
        var raw = new ComponentVoxels(id, bounds, voxels, entities, List.of(), List.of(), h);
        var body = new ComponentVoxels(id, bounds, voxels, entities, List.of(), List.of(), CanonicalCodec.componentHash(raw));
        var cell = new RealizationCell(new CellKey(h, "minecraft:overworld", 0), List.of(), List.of(body), h, 0, h, h);
        for (int x = 0; x < 2; x++) {
            var plan = compiler.compile(new ChunkKey("minecraft:overworld", x, 0), h, List.of(cell), before);
            assertEquals(List.of(entities.get(x)), plan.blockEntities());
            assertEquals(1, plan.proofs().size());
            assertTrue(plan.proofs().getFirst().afterState().endsWith(":be=true"));
        }
        // 交换 NBT 类型仍能过哈希校验，必须由原版类型规则拒绝。
        var sign = entities.getLast();
        var illegal = List.of(new BlockEntityIntent(sign.pos(), "minecraft:barrel", id, sign.localId(), sign.fields()));
        var invalid = new ComponentVoxels(id, bounds, voxels, illegal, List.of(), List.of(), h);
        var signed = new ComponentVoxels(id, bounds, voxels, illegal, List.of(), List.of(), CanonicalCodec.componentHash(invalid));
        var bad = new RealizationCell(cell.key(), List.of(), List.of(signed), h, 0, h, h);
        assertEquals(FailureCode.OWNERSHIP_CONFLICT, assertThrows(WallFailure.class,
                () -> compiler.compile(new ChunkKey("minecraft:overworld", 1, 0), h, List.of(bad), before)).code());
    }
}
