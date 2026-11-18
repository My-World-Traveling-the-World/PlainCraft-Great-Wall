package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ChunkPlanCompilerTest {
    private static final String H="a".repeat(64),AIR="minecraft:air{}:fluid=minecraft:empty:be=false";
    private static final SiteId ID=new SiteId("wall",SiteKind.WALL,1);
    private static ComponentVoxels component(List<VoxelIntent> voxels) {
        var c=new ComponentVoxels(ID,new Bounds(-1,0,0,17,4,1),voxels,List.of(),List.of(),List.of(),H);
        return new ComponentVoxels(c.id(),c.bounds(),c.voxels(),c.blockEntities(),c.spawnSlots(),c.requiredRoutes(),
                DeterministicHash.sha256(CanonicalCodec.component(c)));
    }
    private static RealizationCell cell(ComponentVoxels c,long rev) {
        return new RealizationCell(new CellKey(H,"minecraft:overworld",0),List.of(),List.of(c),H,rev,H,H);
    }
    private static VoxelIntent voxel(int x,IntentKind kind,String id) {
        return new VoxelIntent(new Point3i(x,1,0),kind,kind==IntentKind.OWNED_VOID?Part.AIR:Part.CORE,
                new local.plaincraft.greatwall.model.WallTypes.BlockSpec(id,Map.of()),ID,400,true,kind==IntentKind.SOLID);
    }
    @Test void clipsAfterWholeComponentAndRoundTripsMetadata() throws Exception {
        var c=component(List.of(voxel(-1,IntentKind.SOLID,"minecraft:stone"),voxel(0,IntentKind.SOLID,"minecraft:stone"),
                voxel(16,IntentKind.SOLID,"minecraft:stone")));
        var compiler=new ChunkPlanCompiler();
        var plan=compiler.compile(new ChunkKey("minecraft:overworld",0,0),H,List.of(cell(c,3)),Map.of(new Point3i(0,1,0),AIR));
        assertEquals(1,plan.voxels().size());assertEquals(3,plan.metadata().gateRevision());
        assertEquals(plan,CanonicalCodec.readWritePlan(CanonicalCodec.writePlan(plan)));
        assertEquals(1,compiler.compile(new ChunkKey("minecraft:overworld",-1,0),H,List.of(cell(c,3)),
                Map.of(new Point3i(-1,1,0),AIR)).voxels().size());
    }
    @Test void gateRevisionAndCoverageChangeCompileKey() {
        var c=component(List.of(voxel(0,IntentKind.SOLID,"minecraft:stone")));
        var compiler=new ChunkPlanCompiler();var key=new ChunkKey("minecraft:overworld",0,0);var before=Map.of(new Point3i(0,1,0),AIR);
        var a=compiler.compile(key,H,List.of(cell(c,0)),before);
        assertNotEquals(a.hash(),compiler.compile(key,H,List.of(cell(c,1)),before).hash());
        assertNotEquals(a.hash(),compiler.bindCoverage(a,"b".repeat(64)).hash());
    }
    @Test void negativeSpaceNeverCarvesForeignVoxelsAndMissingEvidenceFails() {
        var c=component(List.of(voxel(0,IntentKind.OWNED_VOID,"minecraft:air")));var compiler=new ChunkPlanCompiler();
        var key=new ChunkKey("minecraft:overworld",0,0);
        assertThrows(WallFailure.class,()->compiler.compile(key,H,List.of(cell(c,0)),Map.of(new Point3i(0,1,0),
                "minecraft:stone{}:fluid=minecraft:empty:be=false")));
        assertThrows(WallFailure.class,()->compiler.compile(key,H,List.of(cell(c,0)),Map.of()));
    }
    @Test void componentOrderAndDuplicatesDoNotChangePlan() {
        var c=component(List.of(voxel(0,IntentKind.SOLID,"minecraft:stone")));var compiler=new ChunkPlanCompiler();
        var key=new ChunkKey("minecraft:overworld",0,0);var before=Map.of(new Point3i(0,1,0),AIR);
        assertEquals(compiler.compile(key,H,List.of(cell(c,0)),before),compiler.compile(key,H,List.of(cell(c,0),cell(c,0)),before));
    }

    @Test void startupOwnersShareResolutionButKeepSeamAndIndependentProofs() {
        var fence = new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:oak_fence", Map.of());
        var southStair = new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:stone_stairs",
                Map.of("facing", "east", "half", "bottom"));
        var northStair = new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:stone_stairs",
                Map.of("facing", "north", "half", "bottom"));
        var c = component(List.of(
                new VoxelIntent(new Point3i(15, 1, 0), IntentKind.SOLID, Part.FENCE, fence, ID, 400, true, false),
                new VoxelIntent(new Point3i(16, 1, 0), IntentKind.SOLID, Part.FENCE, fence, ID, 400, true, false),
                new VoxelIntent(new Point3i(15, 1, 1), IntentKind.SOLID, Part.CORE, southStair, ID, 400, true, false),
                new VoxelIntent(new Point3i(16, 1, 1), IntentKind.SOLID, Part.CORE, northStair, ID, 400, true, false)));
        var cells = List.of(cell(c, 7));
        var compiler = new ChunkPlanCompiler();
        var window = compiler.window();
        for (int x = 0; x < 2; x++) {
            var owner = new ChunkKey("minecraft:overworld", x, 0);
            var before = Map.of(new Point3i(15 + x, 1, 0), AIR, new Point3i(15 + x, 1, 1), AIR);
            // 缺施工前状态只应拒绝当前 owner，不能污染相邻区块共享的解析结果。
            assertEquals(FailureCode.T0_MISSING,
                    assertThrows(WallFailure.class, () -> window.compile(owner, H, cells, Map.of())).code());
            var actual = window.compile(owner, H, cells, before);
            assertEquals(2, actual.voxels().size());
            assertEquals("true", actual.voxels().getFirst().state().properties().get(x == 0 ? "east" : "west"));
            if (x == 0) assertEquals("outer_left", actual.voxels().getLast().state().properties().get("shape"));
            assertArrayEquals(CanonicalCodec.writePlan(compiler.compile(owner, H, cells, before)),
                    CanonicalCodec.writePlan(actual));
        }
        assertEquals(1, window.resolutions());
    }

    @Test void forgedComponentHashCannotBorrowValidResolution() {
        var good = component(List.of(voxel(0, IntentKind.SOLID, "minecraft:stone")));
        var forged = new ComponentVoxels(good.id(), good.bounds(),
                List.of(voxel(0, IntentKind.SOLID, "minecraft:dirt")), List.of(), List.of(), List.of(), good.hash());
        var key = new ChunkKey("minecraft:overworld", 0, 0);
        var before = Map.of(new Point3i(0, 1, 0), AIR);
        var window = new ChunkPlanCompiler().window();
        assertEquals(1, window.compile(key, H, List.of(cell(good, 0)), before).voxels().size());
        // 故意让单元哈希和构件宣称哈希一致，防止夹具只测到了键不同。
        assertEquals(cell(good, 0).hash(), cell(forged, 0).hash());
        var failure = assertThrows(WallFailure.class, () -> window.compile(key, H, List.of(cell(forged, 0)), before));
        assertEquals("component hash mismatch", failure.getMessage());
    }
}
