package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.component.ImportedGateBody;
import local.plaincraft.greatwall.component.ImportedGateGeometry;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportedGatePlanTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void rotatedWholeGatePreservesLootAndBothPassagesAcrossOwners() {
        var rotation = Rotation.CLOCKWISE_90;
        var feet = new Point3i(-19, 81, 29);
        var body = ImportedGateBody.compile(new SiteId("fixture", SiteKind.GATE, 31), feet, rotation, 42);
        String hash = "a".repeat(64), dimension = "minecraft:overworld";
        var cell = new RealizationCell(new CellKey(hash, dimension, 1), List.of(), List.of(body), hash, 0, hash, hash);
        var before = new HashMap<Point3i, String>();
        for (var voxel : body.voxels()) before.put(voxel.pos(), "minecraft:air{}:fluid=minecraft:empty:be=false");
        var compiler = new ChunkPlanCompiler().window();
        var owners = Rasterizer.owners(dimension, body.bounds());
        var claimed = new HashMap<Point3i, IntentKind>();
        var walk = new HashSet<Point3i>();
        int containers = 0, entities = 0;
        for (var owner : owners) {
            var plan = compiler.compile(owner, hash, List.of(cell), before);
            for (var voxel : plan.voxels()) assertNull(claimed.putIfAbsent(voxel.pos(), voxel.kind()));
            walk.addAll(plan.walkwayFeet());
            for (var entity : plan.blockEntities()) {
                entities++;
                if (PlannedBlockEntityNbt.encode(entity).contains("LootTable")) containers++;
            }
        }
        // 取实物已知计数，可抓到整类装饰或箱桶随归属裁切消失。
        assertEquals(44, entities);
        assertEquals(18, containers);
        assertTrue(owners.size() > 10);
        assertEquals(1, compiler.resolutions());
        assertEquals(37521, claimed.values().stream().filter(v -> v == IntentKind.SOLID).count());
        assertTrue(walk.size() >= 113);
        assertTrue(walk.contains(new Point3i(-19, 81, -27)));
        assertTrue(walk.contains(new Point3i(-19, 81, 85)));
        var geometry = ImportedGateGeometry.gate();
        for (int z = 0; z < 32; z++) for (int y = 0; y < 5; y++) for (int x = 54; x < 59; x++) {
            assertEquals(IntentKind.OWNED_VOID, claimed.get(geometry.place(new Point3i(x, y, z), feet, rotation)));
        }
        // 包围盒角的原始空气应留给地形；门洞里的外部实心则拒绝整张区块计划。
        assertFalse(claimed.containsKey(geometry.place(new Point3i(0, 41, 0), feet, rotation)));
        var blocked = geometry.place(new Point3i(56, 0, 0), feet, rotation);
        var owner = new ChunkKey(dimension, blocked.x() >> 4, blocked.z() >> 4);
        before.put(blocked, "minecraft:stone{}:fluid=minecraft:empty:be=false");
        assertEquals(FailureCode.OWNERSHIP_CONFLICT, assertThrows(WallFailure.class,
                () -> compiler.compile(owner, hash, List.of(cell), Map.copyOf(before))).code());
    }
}
