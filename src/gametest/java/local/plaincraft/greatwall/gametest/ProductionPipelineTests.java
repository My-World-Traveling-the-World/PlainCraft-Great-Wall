package local.plaincraft.greatwall.gametest;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.terrain.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ProductionPipelineTests implements FabricGameTest {
    private static final String H="a".repeat(64);
    private static WorldRecipe recipe(){
        return new WorldRecipe(new UUID(0,1),"fixture",3,"minecraft:overworld",new Point3i(0,64,0),
                new Axis(new Point3i(0,64,0),1<<30,0),new DimensionBounds(-64,320,29_999_000),
                new GenerationConfig(1,1,2048,4,150000,256,512,8,64L<<20,64L<<20,32L<<20),H,H,H,H);
    }
    private static PlanWindow plans(){
        var stations=new ArrayList<Station>();for(int x=0;x<=128;x+=4)stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var start=new Boundary(0,new Point2q(0,0),84,H);var end=new Boundary(1,new Point2q(128*256,0),84,H);
        var g=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),List.of(start.point(),end.point()),
                List.of(0L,128*256L),List.of(),Optional.of(new Terminal(0,128*256,TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
        return new PlanWindow(List.of(new SegmentPlan(g,new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(),H)));
    }
    private static ProtoChunk chunk(GameTestHelper helper,ChunkPos pos){
        var c=new ProtoChunk(pos,UpgradeData.EMPTY,helper.getLevel(),helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        c.setPersistedStatus(ChunkStatus.FEATURES);
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=61;y<64;y++)
            c.setBlockState(new BlockPos(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z),Blocks.STONE.defaultBlockState(),false);
        return c;
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void prepareCaptureCompileDurableApplyAndReadOnlyReplay(GameTestHelper helper){
        var io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"pgw-pipeline-test");t.setDaemon(true);return t;});
        var chunks=new ConcurrentHashMap<ChunkPos,ChunkAccess>();
        var failures=new ConcurrentHashMap<String,Throwable>();
        var recipe=recipe();var plans=plans();var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var store=ChunkEvidenceStore.production(Path.of("build","s1b-game-fixtures",UUID.randomUUID().toString()));
        var snapshots=new TerrainSnapshotService(store,io,catalog);
        PaletteBinding palette=(i,p,l,w,s,h)->BlockCatalog.specification(switch(p){
            case FLOOR,SLAB->Blocks.STONE_BRICK_SLAB.defaultBlockState();case STAIR->Blocks.STONE_BRICK_STAIRS.defaultBlockState();
            case LAMP->Blocks.LANTERN.defaultBlockState();default->Blocks.STONE_BRICKS.defaultBlockState();});
        var decay=new DecayField(3);
        var realization=new RealizationPlanner(recipe,new SurfaceProbe(),Map.of(SiteKind.WALL,new WallSection(decay,palette),
                SiteKind.END_CAP,new EndCap(decay,palette)));
        var pipeline=new ProductionWallPipeline(recipe,new ProductionWallPipeline.Ports(
                (k,m)->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans),()->{}),
                positions->{
                    var ready=new HashMap<ChunkPos,ChunkAccess>();
                    for(var pos:positions)ready.put(pos,chunks.computeIfAbsent(pos,p->chunk(helper,p)));
                    return new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(Map.copyOf(ready)),()->{});
                },()->{},e->failures.put("failure",e),io),snapshots,realization,new ChunkPlanCompiler(),new ChunkWallWriter());
        var key=new ChunkKey(recipe.dimension(),3,0);var pos=new ChunkPos(3,0);
        var owner=chunks.computeIfAbsent(pos,p->chunk(helper,p));var engine=new VanillaTerrainEngine(helper.getLevel());
        pipeline.beforeFeatures(key).thenCompose(v->{
            if(owner.getAttached(TerrainMarkers.PRODUCTION)!=null)throw new AssertionError("PREPARE wrote owner");
            return pipeline.beforeLight(key,owner,engine).thenRun(()->new local.plaincraft.greatwall.diagnostics.StageTrace(UUID.randomUUID()).assertOutsideLock());
        }).thenCompose(v->engine.exclusive(pos,()->{
            if(owner.getAttached(TerrainMarkers.PRODUCTION)==null)throw new AssertionError("no production marker");
            owner.setBlockState(new BlockPos(50,83,0),Blocks.GOLD_BLOCK.defaultBlockState(),false);return (Void)null;
        })).thenComposeAsync(v->pipeline.beforeLight(key,owner,engine),io)
                .thenCompose(v->pipeline.verifyLoaded(owner)).orTimeout(90,TimeUnit.SECONDS).whenComplete((v,error)->helper.getLevel().getServer().execute(()->{
            helper.runAfterDelay(1,()->{
                try{
                    if(error!=null)helper.fail(error.toString());
                    helper.assertTrue(failures.isEmpty(),"no silently swallowed pipeline failure");
                    var d=pipeline.diagnostics();
                    helper.assertTrue(d.getOrDefault("PRODUCTION_APPLY_ENTER",0L)==1,"apply once after proof persistence");
                    helper.assertTrue(d.getOrDefault("PRODUCTION_PROOF_DURABLE",0L)==1,"owner proof is durable before apply");
                    helper.assertTrue(d.get("leases")==0&&d.get("flights")==0,"all pins released");
                    helper.assertTrue(owner.getBlockState(new BlockPos(50,83,0)).is(Blocks.GOLD_BLOCK),"player change survives replay");
                    helper.succeed();
                }finally{pipeline.close();io.shutdown();}
            });
        }));
    }
}
