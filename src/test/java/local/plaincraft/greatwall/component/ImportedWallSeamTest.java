package local.plaincraft.greatwall.component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ImportedWallSeamTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest @EnumSource(Asset.class)
    void realBuildingsConnectToThePairedWallInEveryOrientation(Asset asset) {
        var expected = expectedPorts(asset);
        assertEquals(expected, ImportedWallPorts.of(asset));
        var site = new SiteId("fixture", switch (asset) {
            case GATE -> SiteKind.GATE;
            case ENEMY_TOWER -> SiteKind.ENEMY_TOWER;
            case BEACON -> SiteKind.BEACON;
            case CORNER_TOWER, CHENGHAI_TOWER -> SiteKind.FORT;
        }, 1);
        var local = asset == Asset.GATE ? new Point3i(56, 15, 19) : expected.getFirst().feet();
        // 跨过负坐标区块边界，能抓到旋转符号以及按世界坐标取相位的错误。
        var world = new Point3i(-16, 72, 31);
        for (var rotation : Rotation.values()) {
            var body = asset == Asset.GATE ? ImportedGateBody.compile(site, world, rotation, 1)
                    : ImportedTowerBody.compile(asset, site, local, world, rotation, 1, null, null);
            var placed = ImportedWallPorts.of(asset).stream().map(port -> port.place(local, world, rotation)).toList();
            var joined = ImportedWallSeam.attach(body, placed, 4);
            var layout = new ImportedWallLayout(asset, world, rotation);
            // 以旧编译器的实际产物验证预告范围；不可只对拍两次同一套包围盒计算。
            assertEquals(placed, layout.ports());
            assertEquals(body.bounds(), layout.boundsWithStraightWalls(0));
            assertEquals(joined.bounds(), layout.boundsWithStraightWalls(4));
            var blocks = new HashMap<Point3i, VoxelIntent>();
            for (var voxel : joined.voxels()) assertNull(blocks.put(voxel.pos(), voxel), "接缝不能覆盖建筑或改写同一格");
            // 每四列有 678 个素材方块和 72 个马道净空意图。
            assertEquals(body.voxels().size() + 750 * expected.size(), blocks.size());
            assertEquals(body.blockEntities(), joined.blockEntities());
            assertEquals(1 + expected.size(), joined.requiredRoutes().size());
            for (int i = 0; i < placed.size(); i++) {
                var port = placed.get(i);
                assertEquals(rotation.rotate(expected.get(i).outward()), port.outward());
                assertEquals(rotation.rotate(expected.get(i).merlonSide()), port.merlonSide());
                for (int step = 0; step <= 4; step++) {
                    var feet = port.cell(step, 0, 0);
                    assertTrue(WalkwayCollisionCheck.inspect(shape(blocks.get(feet)), shape(blocks.get(port.cell(step, 0, 1))),
                            shape(blocks.get(port.cell(step, 0, -1)))).valid(), "接口两边马道须齐平：" + feet);
                    for (int t : new int[] {-5, 5}) assertEquals("minecraft:quartz_block",
                            blocks.get(port.cell(step, t, -1)).state().id(), "石英腰线须连续");
                }
                for (int step = 1; step <= 3; step++) {
                    var stair = blocks.get(port.cell(step, 5, 2));
                    assertNotNull(stair, "第一列必须从垛起步");
                    assertEquals("minecraft:stone_brick_stairs", stair.state().id());
                    assertEquals(port.merlonSide().getOpposite().getName(), stair.state().properties().get("facing"));
                    assertNull(blocks.get(port.cell(step, -5, 2)), "内侧矮墙不可换成垛口");
                }
                assertNull(blocks.get(port.cell(4, 5, 2)), "第四列必须留垛口缺");
                assertEquals("bottom", blocks.get(port.cell(2, 5, 1)).state().properties().get("type"));
            }
            for (var v : body.voxels()) assertSame(v, blocks.get(v.pos()), "保留模板预留墙节和装饰");
            assertRoute(body.requiredRoutes().getFirst(), placed, blocks);
        }
    }

    private static void assertRoute(List<Point3i> route, List<ImportedWallPorts.Port> ports, Map<Point3i, VoxelIntent> blocks) {
        // 防止编译器只返回头尾两点，却把它当作楼内已经连通。
        assertEquals(ports.getFirst().feet(), route.getFirst());
        assertEquals(ports.getLast().feet(), route.getLast());
        for (int i = 0; i < route.size(); i++) {
            var p = route.get(i);
            assertTrue(WalkwayCollisionCheck.inspect(shape(blocks.get(p)), shape(blocks.get(new Point3i(p.x(), p.y()+1, p.z()))),
                    shape(blocks.get(new Point3i(p.x(), p.y()-1, p.z())))).valid());
            if (i > 0) {
                var q = route.get(i-1);
                assertEquals(1, Math.abs(p.x()-q.x()) + Math.abs(p.z()-q.z()));
                assertEquals(p.y(), q.y());
            }
        }
    }

    private static net.minecraft.world.phys.shapes.VoxelShape shape(VoxelIntent v) {
        return v == null ? Shapes.empty() : BlockCatalog.resolveState(v.state())
                .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static List<ImportedWallPorts.Port> expectedPorts(Asset asset) {
        // 按存档里实物端面列独立列出，不能用待测接口生成期望坐标。
        return switch (asset) {
            case GATE -> List.of(port(0, 19, Direction.WEST, Direction.SOUTH), port(112, 19, Direction.EAST, Direction.SOUTH));
            case ENEMY_TOWER -> List.of(port(0, 13, Direction.WEST, Direction.SOUTH), port(70, 13, Direction.EAST, Direction.SOUTH));
            case BEACON -> List.of(port(0, 5, Direction.WEST, Direction.SOUTH), port(50, 5, Direction.EAST, Direction.SOUTH));
            case CORNER_TOWER -> List.of(port(7, 0, Direction.NORTH, Direction.WEST), port(31, 22, Direction.EAST, Direction.SOUTH));
            case CHENGHAI_TOWER -> List.of(port(46, 0, Direction.NORTH, Direction.EAST));
        };
    }

    private static ImportedWallPorts.Port port(int x, int z, Direction out, Direction side) {
        return new ImportedWallPorts.Port(new Point3i(x, 15, z), out, side);
    }
}
