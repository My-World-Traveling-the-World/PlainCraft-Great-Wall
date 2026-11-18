package local.plaincraft.greatwall.generation;

import java.util.*;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实编译产物驱动施工端口，夹具非空，失败不能反而报告建成。 */
class PublishedPlanPainterTest {
    private static final String H="a".repeat(64);
    private static final SiteId ID=new SiteId("wall",SiteKind.WALL,1);

    @Test void firstBuildOverridesUnrecordedBuildingAndResolvesPaletteOnce() {
        var plan=plan();var world=new Fake();
        world.blocks.put(new Point3i(0,1,0),"foreign:chest_with_payload");
        world.blocks.put(new Point3i(1,1,0),"minecraft:bedrock");
        var result=PublishedPlanPainter.paint(plan,world);
        assertTrue(result.complete());assertEquals(2,result.written());assertEquals(0,result.failed());
        assertEquals(1,world.resolves,"相同BlockSpec只解析一次");
        assertEquals(Set.of(new Point3i(0,1,0),new Point3i(1,1,0)),world.satisfied);
        assertEquals("minecraft:stone",world.blocks.get(new Point3i(0,1,0)));
        assertEquals("minecraft:stone",world.blocks.get(new Point3i(1,1,0)));
        assertEquals(List.of("write:0","write:1","finish:true"),world.events);
    }

    @Test void reopeningPreservesPlayerEditOnlyForCompletedIdenticalPlan() {
        assertTrue(PublishedPlanPainter.preserveCompleted(false,true,H,H));
        assertFalse(PublishedPlanPainter.preserveCompleted(false,false,H,H));
        assertFalse(PublishedPlanPainter.preserveCompleted(false,true,"b".repeat(64),H));
        assertFalse(PublishedPlanPainter.preserveCompleted(true,true,H,H));
        assertFalse(PublishedPlanPainter.preserveCompleted(false,false,null,H));
    }

    @Test void refusedWriteIsNotCountedOrSealedAndCanRetry() {
        var world=new Fake();world.rejectX=0;
        var first=PublishedPlanPainter.paint(plan(),world);
        assertFalse(first.complete());assertEquals(1,first.written());assertEquals(1,first.failed());
        assertEquals(Set.of(new Point3i(1,1,0)),world.satisfied);
        assertEquals(List.of(false),world.seals);assertEquals(1,world.failures.size());
        world.rejectX=-99;
        var retry=PublishedPlanPainter.paint(plan(),world);
        assertTrue(retry.complete());assertEquals(1,retry.written());
        assertEquals(List.of(false,true),world.seals);
        assertEquals(2,world.satisfied.size(),"重试中相同状态仍须完成实体阶段");
    }

    @Test void metadataFailureIsNotCompletionAndRetryHasNoDuplicateWrites() {
        var world=new Fake();world.metadataFailure=true;
        var failed=PublishedPlanPainter.paint(plan(),world);
        assertFalse(failed.complete());assertEquals(2,failed.written());assertEquals(1,failed.failed());
        assertEquals(1,world.failures.size());assertTrue(world.seals.isEmpty());
        world.metadataFailure=false;
        var retry=PublishedPlanPainter.paint(plan(),world);
        assertTrue(retry.complete());assertEquals(0,retry.written());assertEquals(List.of(true),world.seals);
        assertEquals(2,world.satisfied.size());
    }

    @Test void invalidHeightAndUnresolvableMaterialNeverSealAsComplete() {
        var world=new Fake();world.validHeight=false;
        var height=PublishedPlanPainter.paint(plan(),world);
        assertEquals(2,height.failed());assertEquals(0,height.written());assertFalse(height.complete());
        assertEquals(0,world.resolves);assertEquals(List.of(false),world.seals);
        world=new Fake();world.resolveFailure=true;
        var material=PublishedPlanPainter.paint(plan(),world);
        assertEquals(2,material.failed());assertFalse(material.complete());assertEquals(0,material.written());
    }

    @Test void keepAndOtherOwnerDoNotReadOrWrite() {
        var original=plan();
        var voxels=new ArrayList<>(original.voxels());
        voxels.add(voxel(2,IntentKind.KEEP));voxels.add(voxel(16,IntentKind.SOLID));
        // 仅追加入口应跳过的项，原始计划仍由真实编译器产生，空输入不能掩盖失败。
        var changed=new ChunkWritePlan(original.owner(),original.recipeHash(),original.inputHash(),voxels,
                original.proofs(),original.blockEntities(),original.postprocess(),original.fluidTicks(),
                original.walkwayFeet(),original.metadata(),original.hash());
        var world=new Fake();var result=PublishedPlanPainter.paint(changed,world);
        assertEquals(2,result.written());assertEquals(2,world.reads);assertTrue(result.complete());
    }

    private static VoxelIntent voxel(int x,IntentKind kind) {
        return new VoxelIntent(new Point3i(x,1,0),kind,Part.CORE,
                new BlockSpec("minecraft:stone",Map.of()),ID,400,true,true);
    }

    private static ChunkWritePlan plan() {
        var body=new ComponentVoxels(ID,new Bounds(0,0,0,2,3,1),
                List.of(voxel(0,IntentKind.SOLID),voxel(1,IntentKind.SOLID)),List.of(),List.of(),List.of(),H);
        var component=new ComponentVoxels(body.id(),body.bounds(),body.voxels(),body.blockEntities(),
                body.spawnSlots(),body.requiredRoutes(),CanonicalCodec.componentHash(body));
        var cell=new RealizationCell(new CellKey(H,"minecraft:overworld",0),List.of(),List.of(component),H,0,H,H);
        var result=new ChunkPlanCompiler().compile(new ChunkKey("minecraft:overworld",0,0),H,List.of(cell),
                Map.of(new Point3i(0,1,0),"minecraft:stone{}",new Point3i(1,1,0),"minecraft:air{}"));
        assertEquals(2,result.voxels().size());return result;
    }

    private static final class Fake implements PublishedPlanPainter.World<String> {
        final Map<Point3i,String> blocks=new HashMap<>();
        final List<String> events=new ArrayList<>(),failures=new ArrayList<>();
        final List<Boolean> seals=new ArrayList<>();
        Set<Point3i> satisfied=Set.of();int resolves,reads,rejectX=-99;
        boolean metadataFailure,resolveFailure,validHeight=true;
        public boolean validHeight(int y){return validHeight;}
        public String resolve(BlockSpec spec){resolves++;if(resolveFailure)throw new IllegalArgumentException("missing block");return spec.id();}
        public String read(Point3i p){reads++;return blocks.getOrDefault(p,"minecraft:air");}
        public boolean write(Point3i p,String state){if(p.x()==rejectX)return false;events.add("write:"+p.x());blocks.put(p,state);return true;}
        public void finish(Set<Point3i> touched,boolean complete){
            satisfied=touched;if(metadataFailure)throw new IllegalStateException("marker failure");
            events.add("finish:"+complete);seals.add(complete);
        }
        public void failure(Point3i p,RuntimeException failure){failures.add(failure.getMessage());}
    }
}
