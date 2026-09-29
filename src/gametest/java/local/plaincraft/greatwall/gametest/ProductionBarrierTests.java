package local.plaincraft.greatwall.gametest;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.generation.VanillaTerrainEngine;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.EvidenceCodec;
import local.plaincraft.greatwall.terrain.*;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;

public final class ProductionBarrierTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=300,batch="pgw-tall-columns")
    public void sixtyFourPageColumnsPreserveCanonicalHashesAndHighCoordinates(GameTestHelper helper) {
        var key=new ChunkKey("minecraft:overworld",-1,0);var bases=new ArrayList<Integer>();
        var tiles=new HashMap<TerrainTile.Key,TerrainTile>();
        for(int page=0;page<64;page++) {
            int base=page*128;bases.add(base);
            var tileKey=new TerrainTile.Key(key.dimension(),-1,0,base,base+127);
            tiles.put(tileKey,new TerrainTile(tileKey,H,List.of("minecraft:stone{}:fluid=minecraft:empty:be=false"),new int[tileKey.volume()]));
        }
        var raw=new Coverage(key,bases,new Bounds(-16,0,0,0,8192,16),H);
        var coverage=new Coverage(key,bases,raw.readBounds(),DeterministicHash.sha256(EvidenceCodec.coverage(raw)));
        var columns=new ArrayList<ColumnEvidence>();
        for(int z=0;z<16;z++)for(int x=-16;x<0;x++)columns.add(new ColumnEvidence(x,z,6000,false));
        var metadata=EvidenceCodec.hashed(new CaptureMetadata(coverage,columns,List.of(),Set.of(),Set.of(),H));
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var shared=SnapshotColumnView.columns(metadata,tiles,catalog);
        helper.assertTrue(shared.size()==256,"all owner columns share the tall page index");
        for(var p:shared.keySet()) {
            var legacy=new SnapshotColumnView(p.x(),p.z(),metadata,tiles,catalog);
            helper.assertTrue(shared.get(p).hash().equals(legacy.hash()),"page-index sharing does not change canonical column identity");
            helper.assertTrue(shared.get(p).state(6000).equals(legacy.state(6000))
                    &&shared.get(p).state(8191).equals(legacy.state(8191)),"three-int high coordinates select the correct T0 page");
        }
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=300)
    public void insufficientT0BudgetRejectsBeforeDataAllocation(GameTestHelper helper) {
        var f=fixture(helper);var budget=new AllocationBudget((24L<<20)+1024);
        f.service().capture(f.coverage(),f.chunks(),f.engine(),H,budget).whenComplete((value,error)->
                helper.getLevel().getServer().execute(()->helper.runAfterDelay(1,()->{
                    try {
                        helper.assertTrue(error!=null&&error.toString().contains("before producer"),"capacity rejected during forecast");
                        helper.assertTrue(f.service().dataProductions()==0,"no disk payload or T0 state array was allocated");
                        helper.assertTrue(budget.stats().used()==0,"scratch reservation released after denial");
                        helper.assertTrue(f.chunks().get(f.key()).getAttached(TerrainMarkers.PRODUCTION)==null,"no APPLY marker on capacity failure");
                        helper.succeed();
                    } finally {budget.close();f.close();}
                })));
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=300)
    public void rejectedIoDoesNotLeakSnapshotAdmission(GameTestHelper helper) {
        var f=fixture(helper);
        var service=new TerrainSnapshotService(ChunkEvidenceStore.production(Path.of("build","s1b-game-fixtures",UUID.randomUUID().toString())),
                r->{throw new RejectedExecutionException("fixture rejection");},f.service().catalog());
        try{
            for(int n=0;n<300;n++){
                var future=service.capture(f.coverage(),f.chunks(),f.engine(),H,f.allocations());
                helper.assertTrue(future.isCompletedExceptionally(),"executor rejection terminates every future");
                helper.assertTrue(service.diagnostics().values().stream().allMatch(v->v==0),"no rejected capture flight/claim/admission leak");
                helper.assertTrue(f.allocations().stats().used()==0,"rejected IO returns every allocation permit");
            }
            helper.succeed();
        }finally{service.close();f.close();}
    }
    private static final String H="a".repeat(64);
    private record Fixture(ChunkKey key,Coverage coverage,Map<ChunkKey,ChunkAccess> chunks,ExecutorService io,
            TerrainSnapshotService service,VanillaTerrainEngine engine,AllocationBudget allocations) implements AutoCloseable {
        @Override public void close(){service.close();allocations.close();io.shutdown();}
    }
    private static Fixture fixture(GameTestHelper h) {
        var key=new ChunkKey("minecraft:overworld",0,0);
        var raw=new Coverage(key,List.of(0,128),new Bounds(0,93,0,16,136,16),H);
        var coverage=new Coverage(key,raw.tileBases(),raw.readBounds(),DeterministicHash.sha256(EvidenceCodec.coverage(raw)));
        var chunks=new HashMap<ChunkKey,ChunkAccess>();
        int radius=ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            var chunk=new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,h.getLevel(),
                    h.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
            chunk.setPersistedStatus(ChunkStatus.FEATURES);
            for(int zz=0;zz<16;zz++)for(int xx=0;xx<16;xx++)for(int y=105;y<108;y++)
                chunk.setBlockState(new BlockPos(x*16+xx,y,z*16+zz),Blocks.STONE.defaultBlockState(),false);
            chunks.put(new ChunkKey(key.dimension(),x,z),chunk);
        }
        var io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"pgw-test-evidence");t.setDaemon(true);return t;});
        var store=ChunkEvidenceStore.production(Path.of("build","s1b-game-fixtures",UUID.randomUUID().toString()));
        return new Fixture(key,coverage,Map.copyOf(chunks),io,
                new TerrainSnapshotService(store,io,new BlockCatalog(h.getLevel().registryAccess())),new VanillaTerrainEngine(h.getLevel()),new AllocationBudget(64L<<20));
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=300)
    public void multiPageT0CapturesOnce(GameTestHelper helper) {
        var f=fixture(helper);var requests=new ArrayList<CompletableFuture<TerrainSnapshotService.CapturedTerrain>>();
        for(int i=0;i<20;i++)requests.add(f.service().capture(f.coverage(),f.chunks(),f.engine(),H,f.allocations()));
        try(var foreign=new AllocationBudget(64L<<20)) {
            helper.assertTrue(f.service().capture(f.coverage(),f.chunks(),f.engine(),H,foreign).isCompletedExceptionally(),
                    "another arena must not borrow a snapshot whose original pin may close first");
            helper.assertTrue(foreign.stats().used()==0,"foreign arena did not start a producer");
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->
                helper.getLevel().getServer().execute(()->helper.runAfterDelay(1,()->{
                    try{
                        if(error!=null)helper.fail(error.toString());
                        var first=requests.getFirst().resultNow();
                        helper.assertTrue(first.tiles().size()==2,"all pages captured");
                        helper.assertTrue(requests.stream().allMatch(p->p.resultNow().tiles().equals(first.tiles())),"single-flight callers share same immutable pages");
                        var col=new SnapshotColumnView(0,0,first.metadata().get(f.key()),first.tiles(),f.service().catalog());
                        helper.assertTrue(col.highestNonPlantFeet()==108,"summary retains actual FEATURES height");
                        helper.assertTrue(new local.plaincraft.greatwall.generation.SurfaceProbe().probe(col,128,new DimensionBounds(-64,320,29_999_000)).kind()==ProbeKind.SUPPORTED,
                                "captured native column satisfies three-layer contract");
                        helper.succeed();
                    }finally{f.close();}
                })));
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=300)
    public void lateCellReadsOriginalT0(GameTestHelper helper) {
        var f=fixture(helper);
        f.service().capture(f.coverage(),f.chunks(),f.engine(),H,f.allocations()).thenCompose(first->
                f.engine().exclusive(new ChunkPos(0,0),()->{
                    var owner=f.chunks().get(f.key());
                    owner.setBlockState(new BlockPos(0,107,0),Blocks.GOLD_BLOCK.defaultBlockState(),false);
                    owner.setAttached(TerrainMarkers.PRODUCTION,new TerrainMarkers.Marker(H,f.coverage().hash(),H,0,true,List.of(),List.of(),List.of(),Map.of()));
                    return first;
                }).thenCompose(saved->f.service().capture(f.coverage(),f.chunks(),f.engine(),H,f.allocations()).thenApply(second->
                        Map.entry(saved,second)))).whenComplete((pair,error)->helper.getLevel().getServer().execute(()->helper.runAfterDelay(1,()->{
                    try{
                        if(error!=null)helper.fail(error.toString());
                        helper.assertTrue(pair.getKey().tiles().keySet().equals(pair.getValue().tiles().keySet()),"same durable page set");
                        for(var entry:pair.getKey().tiles().entrySet()){
                            var restored=pair.getValue().tiles().get(entry.getKey());var original=entry.getValue();
                            helper.assertTrue(original.hash().equals(restored.hash())&&original.palette().equals(restored.palette())
                                    &&Arrays.equals(original.copyStates(),restored.copyStates()),"late cell reuses byte-identical original pages");
                        }
                        var tile=pair.getValue().tiles().values().stream().filter(t->t.key().minY()==0).findFirst().orElseThrow();
                        helper.assertTrue(tile.state(0,107,0).startsWith("minecraft:stone{"),"T0 must not contain applied wall");
                        helper.assertTrue(f.chunks().get(f.key()).getBlockState(new BlockPos(0,107,0)).is(Blocks.GOLD_BLOCK),"capture must not alter current blocks");
                        helper.succeed();
                    }finally{f.close();}
                })));
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=300)
    public void missingProofFailsClosed(GameTestHelper helper) {
        var f=fixture(helper);
        var proofBudget=new AllocationBudget(32L<<20);
        f.service().capture(f.coverage(),f.chunks(),f.engine(),H,f.allocations()).thenCompose(ignored->
                f.service().verifyLoaded(f.key(),new TerrainMarkers.Marker(H,f.coverage().hash(),H,0,true,List.of(),List.of(),List.of(),Map.of()),-64,320,f.allocations(),proofBudget))
                .whenComplete((ignored,error)->helper.getLevel().getServer().execute(()->helper.runAfterDelay(1,()->{
                    try{
                        helper.assertTrue(error!=null&&error.toString().contains("proof missing"),"missing authoritative proof is an error, never an empty replacement");
                        helper.succeed();
                    }finally{proofBudget.close();f.close();}
                })));
    }
}
