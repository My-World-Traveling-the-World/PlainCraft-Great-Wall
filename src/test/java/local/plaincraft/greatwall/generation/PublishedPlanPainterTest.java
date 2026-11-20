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

    @Test void slicedReplayKeepsEventsPaletteAndFinalPayloadExactly() {
        var expected=new Fake();
        var frozenResult=FrozenPublishedPlanPainter.paint(plan(),expected);
        var expectedResult=new PublishedPlanPainter.Result(frozenResult.complete(),frozenResult.written(),frozenResult.failed());
        var actual=new Fake();var cursor=new PublishedPlanCursor<>(plan(),actual);
        assertThrows(IllegalStateException.class,cursor::result);
        assertFalse(cursor.advance(1,()->false));
        assertEquals(List.of("write:0"),actual.events);
        assertTrue(actual.seals.isEmpty(),"半块不得写实体/完成标记");
        assertEquals(1,cursor.visited());
        assertTrue(cursor.advance(1,()->false));
        assertEquals(expectedResult,cursor.result());
        assertEquals(expected.events,actual.events);
        assertEquals(expected.blocks,actual.blocks);
        assertEquals(expected.satisfied,actual.satisfied);
        assertEquals(1,actual.resolves,"暂停不能清掉材料缓存");
        assertTrue(cursor.advance(1,()->false));
        assertEquals(expected.events,actual.events,"再次推进不得重复结案");
    }

    @Test void yieldBeforeFirstVoxelDoesNoWorldWorkAndCancelNeverSeals() {
        var world=new Fake();var cursor=new PublishedPlanCursor<>(plan(),world);
        assertFalse(cursor.advance(8,()->true));
        assertEquals(0,cursor.visited());assertEquals(0,world.reads);assertTrue(world.events.isEmpty());
        assertFalse(cursor.advance(1,()->false));
        cursor.cancel();cursor.cancel();
        assertEquals(PublishedPlanCursor.State.CANCELLED,cursor.state());
        assertEquals(List.of("write:0"),world.events);
        assertThrows(IllegalStateException.class,()->cursor.advance(1,()->false));
        assertThrows(IllegalStateException.class,cursor::result);
    }

    @Test void failuresRemainAcrossSlicesAndStillFinishOnlyOnce() {
        var world=new Fake();world.rejectX=0;
        var cursor=new PublishedPlanCursor<>(plan(),world);
        assertFalse(cursor.advance(1,()->false));
        assertTrue(world.seals.isEmpty());assertEquals(1,world.failures.size());
        assertTrue(cursor.advance(1,()->false));
        assertEquals(new PublishedPlanPainter.Result(false,1,1),cursor.result());
        assertEquals(List.of(false),world.seals);
    }

    @Test void skipVoxelsConsumeBudgetWithoutReadingWorld() {
        var original=plan();var voxels=new ArrayList<VoxelIntent>();
        for(int i=0;i<300;i++)voxels.add(voxel(16,IntentKind.KEEP));
        voxels.addAll(original.voxels());
        var changed=new ChunkWritePlan(original.owner(),original.recipeHash(),original.inputHash(),voxels,
                original.proofs(),original.blockEntities(),original.postprocess(),original.fluidTicks(),
                original.walkwayFeet(),original.metadata(),original.hash());
        var world=new Fake();var cursor=new PublishedPlanCursor<>(changed,world);
        assertThrows(IllegalArgumentException.class,()->cursor.advance(0,()->false));
        assertFalse(cursor.advance(64,()->false));
        assertEquals(64,cursor.visited());assertEquals(0,world.reads);assertTrue(world.events.isEmpty());
        assertEquals(new PublishedPlanPainter.Result(true,2,0),cursor.runAll());
        assertEquals(List.of("write:0","write:1","finish:true"),world.events);
    }

    @Test void cursorRejectsForeignThreadAndEscapingFailureCannotReplay() throws Exception {
        var world=new Fake();var cursor=new PublishedPlanCursor<>(plan(),world);
        var rejected=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread=new Thread(()->{try{cursor.advance(1,()->false);}catch(Throwable t){rejected.set(t);}});
        thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,rejected.get());
        assertEquals(0,cursor.visited());assertEquals(PublishedPlanCursor.State.NEW,cursor.state());
        assertThrows(IllegalStateException.class,()->cursor.advance(1,()->{throw new IllegalStateException("预算源故障");}));
        assertEquals(PublishedPlanCursor.State.FAILED,cursor.state());
        assertThrows(IllegalStateException.class,cursor::runAll);
        assertTrue(world.events.isEmpty());
    }

    @Test void slicedFaultCombinationsMatchIndependentFrozenEntry() {
        var plan=plan();int compared=0;
        for(int mask=0;mask<32;mask++)for(int budget:new int[]{1,2,64}) {
            var old=new Fake();var actual=new Fake();
            for(var world:List.of(old,actual)) {
                world.rejectX=(mask&1)==0?-99:0;
                world.validHeight=(mask&2)==0;
                world.resolveFailure=(mask&4)!=0;
                world.metadataFailure=(mask&8)!=0;
                if((mask&16)!=0)world.blocks.put(new Point3i(0,1,0),"minecraft:stone");
            }
            var expected=FrozenPublishedPlanPainter.paint(plan,old);
            var cursor=new PublishedPlanCursor<>(plan,actual);
            int advances=0;
            while(!cursor.advance(budget,()->false))assertTrue(++advances<3,"两体素计划不能无限暂停");
            assertEquals(new PublishedPlanPainter.Result(expected.complete(),expected.written(),expected.failed()),cursor.result());
            assertEquals(old.events,actual.events);assertEquals(old.blocks,actual.blocks);
            assertEquals(old.satisfied,actual.satisfied);assertEquals(old.failures,actual.failures);
            assertEquals(old.resolves,actual.resolves);assertEquals(old.reads,actual.reads);
            compared++;
        }
        assertEquals(96,compared,"所有故障组合都必须真正执行，不能空集合空转");
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

    private static final class Fake implements PublishedPlanPainter.World<String>,FrozenPublishedPlanPainter.World<String> {
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
        // 两份独立世界端口都带默认回调；显式合并仅供冻结对拍，不介入生产路径。
        public void written(Point3i p,String state,int count){}
    }
}
