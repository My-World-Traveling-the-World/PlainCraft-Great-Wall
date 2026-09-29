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
                new BlockSpec(id,Map.of()),ID,400,true,kind==IntentKind.SOLID);
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
}
