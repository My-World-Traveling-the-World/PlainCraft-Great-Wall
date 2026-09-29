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
        return recipe(3);
    }
    private static WorldRecipe recipe(long seed){
        return new WorldRecipe(new UUID(0,1),"fixture",seed,"minecraft:overworld",new Point3i(0,64,0),
                new Axis(new Point3i(0,64,0),1<<30,0),new DimensionBounds(-64,320,29_999_000),
                new GenerationConfig(1,1,2048,4,150000,256,512,8,64L<<20,64L<<20,32L<<20),H,H,H,H);
    }
    private static PlanWindow plans(){
        return plans(128);
    }
    private static PlanWindow plans(int length){
        var stations=new ArrayList<Station>();for(int x=0;x<=length;x+=4)stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var start=new Boundary(0,new Point2q(0,0),84,H);var end=new Boundary(1,new Point2q(length*256L,0),84,H);
        var g=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),List.of(start.point(),end.point()),
                List.of(0L,length*256L),List.of(),Optional.of(new Terminal(0,length*256L,TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
        var fort=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),0,new Frame(new Point2q(0,0),1<<30,0,84,false),
                new Bounds(-24,49,-24,24,92,24),SiteState.PLANNED,Optional.empty(),Optional.empty());
        return new PlanWindow(List.of(new SegmentPlan(g,new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(fort),H)));
    }
    private static ProtoChunk chunk(GameTestHelper helper,ChunkPos pos){
        return chunk(helper,pos,false);
    }
    private static ProtoChunk chunk(GameTestHelper helper,ChunkPos pos,boolean ridge){
        var c=new ProtoChunk(pos,UpgradeData.EMPTY,helper.getLevel(),helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        c.setPersistedStatus(ChunkStatus.FEATURES);
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=61;y<64;y++)
            if(!ridge||pos.getMinBlockX()+x<128||pos.getMinBlockZ()+z>=-5&&pos.getMinBlockZ()+z<5)
                c.setBlockState(new BlockPos(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z),Blocks.STONE.defaultBlockState(),false);
        return c;
    }
    private static PaletteBinding palette(){return (i,p,l,w,s,h)->BlockCatalog.specification(switch(p){
        case FLOOR,SLAB->Blocks.STONE_BRICK_SLAB.defaultBlockState();case STAIR->Blocks.STONE_BRICK_STAIRS.defaultBlockState();
        case LAMP->Blocks.LANTERN.defaultBlockState();default->Blocks.STONE_BRICKS.defaultBlockState();});}
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
                SiteKind.END_CAP,new EndCap(decay,palette),SiteKind.FORT,new FortPlaceholder(decay,palette)));
        var pipeline=new ProductionWallPipeline(recipe,new ProductionWallPipeline.Ports(
                (k,m)->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans),()->{}),
                k->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans),()->{}),
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
                .thenCompose(v->pipeline.verifyLoaded(owner)).thenCompose(v->{
                    var marker=owner.getAttached(TerrainMarkers.PRODUCTION);
                    var tampered=new TerrainMarkers.Marker(marker.recipe(),marker.coverageHash(),marker.planHash(),marker.gateRevision()+1,
                            true,marker.walkwayFeet(),marker.gameplay(),marker.spawnSlots(),marker.realizedStates());
                    return snapshots.verifyLoaded(key,tampered,-64,320).handle((ignored,error)->{
                        if(error==null)throw new AssertionError("tampered marker revision accepted");
                        return (Void)null;
                    });
                }).orTimeout(90,TimeUnit.SECONDS).whenComplete((v,error)->helper.getLevel().getServer().execute(()->{
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
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void distantFailedCapCutsEarlyOwnerBeforeApplyAndColdReplayPreservesEdits(GameTestHelper helper){
        var io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"pgw-cross-cell-test");t.setDaemon(true);return t;});
        var chunks=new ConcurrentHashMap<ChunkPos,ChunkAccess>();var failures=new ConcurrentHashMap<String,Throwable>();
        var pipelines=new ConcurrentHashMap<String,ProductionWallPipeline>();var recipe=recipe(8);var plans=plans(480);
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var store=ChunkEvidenceStore.production(Path.of("build","s1b-game-fixtures",UUID.randomUUID().toString()));
        var snapshots=new TerrainSnapshotService(store,io,catalog);var decay=new DecayField(8);var palette=palette();
        var realization=new RealizationPlanner(recipe,new SurfaceProbe(),Map.of(SiteKind.WALL,new WallSection(decay,palette),
                SiteKind.END_CAP,new EndCap(decay,palette),SiteKind.FORT,new FortPlaceholder(decay,palette)));
        var ports=new ProductionWallPipeline.Ports(
                (k,m)->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans),()->{}),
                k->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans),()->{}),
                positions->{
                    new local.plaincraft.greatwall.diagnostics.StageTrace(UUID.randomUUID()).assertOutsideLock();
                    var ready=new HashMap<ChunkPos,ChunkAccess>();
                    for(var pos:positions)ready.put(pos,chunks.computeIfAbsent(pos,p->chunk(helper,p,true)));
                    return new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(Map.copyOf(ready)),()->{});
                },()->{},e->failures.put("failure",e),io);
        var pipeline=new ProductionWallPipeline(recipe,ports,snapshots,realization,new ChunkPlanCompiler(),new ChunkWallWriter());
        pipelines.put("initial",pipeline);var engine=new VanillaTerrainEngine(helper.getLevel());
        var earlyKey=new ChunkKey(recipe.dimension(),10,0);var lateKey=new ChunkKey(recipe.dimension(),29,0);
        var early=chunks.computeIfAbsent(new ChunkPos(10,0),p->chunk(helper,p,true));
        var late=chunks.computeIfAbsent(new ChunkPos(29,0),p->chunk(helper,p,true));
        var a=pipeline.beforeLight(earlyKey,early,engine);var b=pipeline.beforeLight(lateKey,late,engine);
        helper.assertTrue(early.getAttached(TerrainMarkers.PRODUCTION)==null&&late.getAttached(TerrainMarkers.PRODUCTION)==null,
                "no APPLY while remote ground-exit decision is unresolved");
        CompletableFuture.allOf(a,b).thenComposeAsync(v->snapshots.readPlan(earlyKey).thenCombine(snapshots.readPlan(lateKey),(p,q)->{
            if(!p.orElseThrow().walkwayFeet().isEmpty()||!q.orElseThrow().walkwayFeet().isEmpty())
                throw new AssertionError("early or terminal owner kept an ungrounded walkway");
            return (Void)null;
        }),io).thenComposeAsync(v->engine.exclusive(early.getPos(),()->{
            early.setBlockState(new BlockPos(162,83,0),Blocks.GOLD_BLOCK.defaultBlockState(),false);return (Void)null;
        }),io).thenComposeAsync(v->{
            pipeline.close();
            var coldPorts=new ProductionWallPipeline.Ports(ports.plans(),ports.arcPlans(),positions->{throw new AssertionError("replay recaptured FEATURES");},
                    ()->{},e->failures.put("cold",e),io);
            var cold=new ProductionWallPipeline(recipe,coldPorts,new TerrainSnapshotService(store,io,catalog),realization,new ChunkPlanCompiler(),new ChunkWallWriter());
            pipelines.put("cold",cold);
            return cold.beforeLight(lateKey,late,engine).thenComposeAsync(x->cold.beforeLight(earlyKey,early,engine),io);
        },io).orTimeout(150,TimeUnit.SECONDS).whenComplete((v,error)->helper.getLevel().getServer().execute(()->helper.runAfterDelay(1,()->{
            try {
                if(error!=null)helper.fail(error.toString());
                helper.assertTrue(failures.isEmpty(),"no swallowed decision failure");
                helper.assertTrue(pipeline.diagnostics().getOrDefault("PRODUCTION_APPLY_ENTER",0L)==2,"two owners applied only after certificates");
                helper.assertTrue(pipelines.get("cold").diagnostics().getOrDefault("PRODUCTION_APPLY_ENTER",0L)==0,"cold replay is read-only");
                helper.assertTrue(early.getBlockState(new BlockPos(162,83,0)).is(Blocks.GOLD_BLOCK),"player edit survives cold reverse replay");
                for(var p:pipelines.values())helper.assertTrue(p.diagnostics().get("leases")==0&&p.diagnostics().get("flights")==0,"no owner/FEATURES leases leak");
                helper.succeed();
            } finally {pipelines.values().forEach(ProductionWallPipeline::close);io.shutdown();}
        })));
    }
}
