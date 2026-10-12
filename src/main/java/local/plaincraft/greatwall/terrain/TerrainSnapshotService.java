package local.plaincraft.greatwall.terrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.generation.ProtectionSnapshot;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.EvidenceCodec;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** FEATURES-halo barrier with durable, immutable per-owner snapshots. Never samples an applied wall. */
public final class TerrainSnapshotService implements AutoCloseable {
    public record CapturedTerrain(Map<TerrainTile.Key,TerrainTile> tiles,Map<ChunkKey,CaptureMetadata> metadata) {
        public CapturedTerrain {tiles=Map.copyOf(tiles);metadata=Map.copyOf(metadata);}
    }
    private record Saved(Map<TerrainTile.Key,TerrainTile> tiles,Optional<CaptureMetadata> metadata) {}
    private record Hints(Map<TerrainTile.Key,Long> pages,OptionalLong metadata) {}
    private record Flight(CompletableFuture<CapturedTerrain> result,AllocationBudget allocations,String recipe) {}
    private final ConcurrentHashMap<Coverage,Flight> flights=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkKey,Coverage> claims=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Boolean> state=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> admission=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> allocationCounts=new ConcurrentHashMap<>();
    private final ChunkEvidenceStore store;
    private final Executor io;
    private final BlockCatalog catalog;
    /**
     * Planning-resolved structure footprints (from the corridor scan), injected once the scan
     * settles. Empty until then, so a capture that runs before the scan simply sees no predicted
     * structures — matching the pre-refactor behaviour rather than failing. Volatile so the
     * generation path observes the post-scan value without synchronizing with the scan thread.
     */
    private volatile List<Bounds> predictedStructures;
    public TerrainSnapshotService(ChunkEvidenceStore store,Executor io) {this(store,io,new BlockCatalog(RegistryAccess.EMPTY));}
    public TerrainSnapshotService(ChunkEvidenceStore store,Executor io,BlockCatalog catalog) {
        this(store,io,catalog,List.of());
    }
    public TerrainSnapshotService(ChunkEvidenceStore store,Executor io,BlockCatalog catalog,List<Bounds> predictedStructures) {
        this.store=Objects.requireNonNull(store);this.io=Objects.requireNonNull(io);this.catalog=Objects.requireNonNull(catalog);
        this.predictedStructures=List.copyOf(predictedStructures);
    }

    /** Publishes the planning-resolved structure footprints once the corridor scan settles. */
    public void setPredictedStructures(List<Bounds> structures) {
        this.predictedStructures = List.copyOf(Objects.requireNonNull(structures, "structures"));
    }
    public BlockCatalog catalog(){return catalog;}
    public long dataProductions(){return allocationCounts.getOrDefault("data",0L);}
    public CompletableFuture<CapturedTerrain> capture(Coverage coverage,Map<ChunkKey,ChunkAccess> featuresChunks,
            TerrainEngine engine,String recipeHash,AllocationBudget allocations) {
        Flight owned=null;boolean reserved=false;
        var scratch=new ConcurrentHashMap<String,AllocationBudget.Pin>();
        var retained=new ConcurrentHashMap<String,AllocationBudget.Pin>();
        var scratchBudget=new AllocationBudget(24L<<20);
        try {
            active();
            if(allocations.stats().closed())throw failure(FailureCode.CLOSED,"capture allocation owner closed");
            if(featuresChunks.size()>81)throw failure(FailureCode.CAPACITY,"capture prepared map exceeds finite FEATURES lease");
            if(!coverage.hash().equals(DeterministicHash.sha256(EvidenceCodec.coverage(coverage))))throw failure(FailureCode.RECIPE_MISMATCH,"coverage hash mismatch");
            ChunkAccess owner=featuresChunks.get(coverage.chunk());
            if(owner==null)throw failure(FailureCode.T0_MISSING,"owner not prepared");
            // Owner-only capture: only the chunk being written must be FEATURES-ready. The historical
            // 9x9 halo check is gone along with the cross-chunk reference walk, so a capture can never
            // wait on a neighbour's generation.
            if(!owner.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)
                    ||owner.getPos().x!=coverage.chunk().x()||owner.getPos().z!=coverage.chunk().z())
                throw failure(FailureCode.MISSING_FEATURES_PLAN,"owner not FEATURES-ready: "+coverage.chunk());
            var existing=flights.get(coverage);if(existing!=null)return captureView(existing,allocations,recipeHash);
            var keys=keys(coverage,owner.getMinBuildHeight(),owner.getMaxBuildHeight());
            admission.compute("count",(k,n)->{
                int old=n==null?0:n;
                if(old>=256)throw failure(FailureCode.CAPACITY,"snapshot flight capacity");
                return old+1;
            });
            reserved=true;
            Coverage claim=claims.putIfAbsent(coverage.chunk(),coverage);
            if(claim!=null&&!claim.equals(coverage))throw failure(FailureCode.RECIPE_MISMATCH,"same owner requested two capture windows");
            var promise=new CompletableFuture<CapturedTerrain>();
            var flight=new Flight(promise,allocations,recipeHash);
            existing=flights.putIfAbsent(coverage,flight);
            if(existing!=null){releaseAdmission();reserved=false;return captureView(existing,allocations,recipeHash);}
            owned=flight;
            reserved=false;
            var scratchPin=allocations.reserve(24L<<20);scratch.put("working",scratchPin);
            var prepared=new HashMap<ChunkPos,ChunkAccess>();
            featuresChunks.forEach((key,value)->{
                if(!key.dimension().equals(coverage.chunk().dimension())||value.getPos().x!=key.x()||value.getPos().z!=key.z())
                    throw failure(FailureCode.RECIPE_MISMATCH,"prepared structure owner mismatch");
                prepared.put(new ChunkPos(key.x(),key.z()),value);
            });
            var frozenPrepared=Map.copyOf(prepared);
            active();
            io(()->allocationHints(coverage,keys)).thenComposeAsync(hints->engine.exclusive(owner.getPos(),()->{
                active();return forecast(owner,keys,hints,frozenPrepared);
            }),io).thenComposeAsync(bytes->{
                active();var pin=allocations.reserve(bytes);retained.put("capture",pin);
                return io(()->{
                    // This owner has no newly captured payload yet. Cold decoding borrows both
                    // existing parent pins, then hands retained data back to the forecast pin.
                    // Later capture/persistence still uses only the fixed 24 MiB scratch arena.
                    try(var coldBudget=new AllocationBudget(Math.addExact(bytes,24L<<20))) {
                        return accounted(coldBudget,()->readSaved(coverage,keys,recipeHash),false);
                    }
                });
            },io).thenComposeAsync(saved->
                    engine.exclusive(owner.getPos(),()->{
                        active();var marker=owner.getAttached(TerrainMarkers.PRODUCTION);
                        if(marker!=null&&(saved.metadata().isEmpty()||saved.tiles().size()!=keys.size()))
                            throw failure(FailureCode.T0_MISSING,"applied owner has missing T0 evidence");
                        if(marker!=null&&(!marker.recipe().equals(recipeHash)||!marker.coverageHash().equals(coverage.hash())))
                            throw failure(FailureCode.RECIPE_MISMATCH,"owner marker/coverage mismatch");
                        if(saved.metadata().isEmpty()&&owner.getPersistedStatus().isOrAfter(ChunkStatus.FULL))
                            throw failure(FailureCode.EXISTING_WORLD,"cannot first-capture a FULL chunk");
                        return saved;
                    }),io).thenComposeAsync(saved->io(()->accounted(scratchBudget,()->{
                        store.persistCoverageIntent(coverage);return saved;
                    },false)),io).thenComposeAsync(saved->{
                        if(saved.metadata().isPresent()) {
                            traceProtection("DURABLE_READ",saved.metadata().get());
                            return CompletableFuture.completedFuture(
                                new CapturedTerrain(saved.tiles(),Map.of(coverage.chunk(),saved.metadata().get())));
                        }
                        CompletableFuture<Map<TerrainTile.Key,TerrainTile>> pages=CompletableFuture.completedFuture(saved.tiles());
                        for(var key:keys)if(!saved.tiles().containsKey(key)) {
                            pages=pages.thenComposeAsync(prior->engine.exclusive(owner.getPos(),()->{
                                active();var copy=new HashMap<>(prior);copy.put(key,capturePage(owner,key,recipeHash));return Map.copyOf(copy);
                            }),io);
                        }
                        return pages.thenComposeAsync(captured->{
                            CompletableFuture<List<ColumnEvidence>> columns=CompletableFuture.completedFuture(List.of());
                            for(int offset=0;offset<256;offset+=32) {
                                final int start=offset;
                                columns=columns.thenComposeAsync(prior->engine.exclusive(owner.getPos(),()->{
                                    active();var copy=new ArrayList<>(prior);copy.addAll(summarize(owner,start,start+32));return List.copyOf(copy);
                                }),io);
                            }
                            return columns.thenComposeAsync(summary->engine.exclusive(owner.getPos(),()->{
                                active();var entities=new HashSet<Point3i>();
                                for(var p:owner.getBlockEntitiesPos())entities.add(new Point3i(p.getX(),p.getY(),p.getZ()));
                                var structures=ProtectionSnapshot.structureBounds(owner,predictedStructures,frozenPrepared,coverage.readBounds());
                                return new CaptureMetadata(coverage,summary,structures,entities,Set.of(),"0".repeat(64));
                            }),io).thenComposeAsync(base->io(()->accounted(scratchBudget,()->{
                                var immutable=new HashSet<Point3i>();
                                for(var tile:captured.values())for(int y=tile.key().minY();y<=tile.key().maxY();y++)
                                    for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                                        if(catalog.flags(tile.state(x,y,z)).protectedBlock())immutable.add(new Point3i(tile.key().chunkX()*16+x,y,tile.key().chunkZ()*16+z));
                                var metadata=EvidenceCodec.hashed(new CaptureMetadata(base.coverage(),base.columns(),
                                        base.protectedStructures(),base.blockEntities(),immutable,"0".repeat(64)));
                                for(var page:captured.values())store.persistBeforeApply(page);
                                store.persistCoverage(metadata);
                                traceProtection("NEW_CAPTURE",metadata);
                                return new CapturedTerrain(captured,Map.of(coverage.chunk(),metadata));
                            },false)),io);
                        },io);
                    },io).whenComplete((value,error)->{
                        scratchBudget.close();finishCapture(coverage,flight,scratch,retained,value,error);
                    });
            return promise.copy();
        }catch(Throwable failure){
            scratchBudget.close();
            if(owned!=null)finishCapture(coverage,owned,scratch,retained,null,failure);
            else if(reserved)releaseAdmission();
            return CompletableFuture.failedFuture(failure);
        }
    }
    private static void traceProtection(String source,CaptureMetadata metadata) {
        if(Boolean.getBoolean("greatwall.s1c.protectionTrace"))
            GreatWallMod.LOGGER.info("[GreatWall:protection-source] metadataSource={} owner={} hash={} read={} boxes={}",
                    source,metadata.coverage().chunk(),metadata.hash(),metadata.coverage().readBounds(),metadata.protectedStructures());
    }
    private void finishCapture(Coverage coverage,Flight flight,Map<String,AllocationBudget.Pin> scratch,
            Map<String,AllocationBudget.Pin> retained,CapturedTerrain value,Throwable error) {
        // Reentrant consumers may start another capture immediately on publication. Clean scratch first.
        Throwable outcome=error!=null?error:state.containsKey("closed")?failure(FailureCode.CLOSED,"snapshot service closed"):null;
        scratch.values().forEach(AllocationBudget.Pin::close);
        if(outcome!=null)retained.values().forEach(AllocationBudget.Pin::close);
        if(flights.remove(coverage,flight)) {
            claims.remove(coverage.chunk(),coverage);releaseAdmission();
        }
        if(outcome==null)flight.result().complete(value);else flight.result().completeExceptionally(outcome);
    }
    private static CompletableFuture<CapturedTerrain> captureView(Flight flight,AllocationBudget allocations,String recipe) {
        if(!flight.recipe().equals(recipe))return CompletableFuture.failedFuture(
                failure(FailureCode.RECIPE_MISMATCH,"capture belongs to another world recipe"));
        if(flight.allocations()!=allocations)return CompletableFuture.failedFuture(
                failure(FailureCode.CAPACITY,"capture belongs to another pinned working set; retry after its capture completes"));
        return flight.result().copy();
    }
    private void releaseAdmission(){admission.compute("count",(k,n)->{if(n==null||n<=0)throw new IllegalStateException("snapshot admission underflow");return n-1;});}
    public Map<String,Integer> diagnostics(){return Map.of("flights",flights.size(),"claims",claims.size(),"admitted",admission.getOrDefault("count",0));}
    public CompletableFuture<Void> persistPlan(ChunkWritePlan plan,AllocationBudget allocations){
        return io(()->accounted(allocations,()->{store.persistPlan(plan);return null;},false));
    }
    public CompletableFuture<Void> persistExitDecision(GroundExitDecision decision,AllocationBudget allocations){
        return io(()->accounted(allocations,()->{store.persistExitDecision(decision);return null;},false));
    }
    public CompletableFuture<Void> persistPlan(ChunkWritePlan plan,List<GroundExitDecision> decisions,AllocationBudget allocations) {
        var frozen=List.copyOf(decisions);
        return io(()->accounted(allocations,()->{store.persistExitProof(plan,frozen);store.persistPlan(plan);return null;},false));
    }
    public CompletableFuture<Void> verifyExitProof(ChunkKey owner,AllocationBudget allocations) {
        return io(()->accounted(allocations,()->{
            store.verifyExitProof(store.readPlan(owner).orElseThrow(()->failure(FailureCode.T0_MISSING,"exit owner proof missing")));return null;
        },false));
    }
    /** The decoded result remains charged until the caller releases its explicitly supplied arena. */
    public CompletableFuture<Optional<ChunkWritePlan>> readPlan(ChunkKey owner,AllocationBudget allocations){
        return io(()->accounted(allocations,()->store.readPlan(owner),true));
    }
    public CompletableFuture<Void> verifyLoaded(ChunkKey owner,TerrainMarkers.Marker marker,int minY,int maxY,
            AllocationBudget terrain,AllocationBudget compiled) {
        return io(()->{
          accounted(compiled,()->{
            var proof=store.readPlan(owner).orElseThrow(()->failure(FailureCode.T0_MISSING,"FULL owner proof missing"));
            if(!marker.complete()||!marker.recipe().equals(proof.recipeHash())||!marker.planHash().equals(proof.hash())
                    ||!marker.coverageHash().equals(proof.metadata().coverageHash())
                    ||marker.gateRevision()!=proof.metadata().gateRevision()||!marker.walkwayFeet().equals(proof.walkwayFeet())
                    ||!marker.gameplay().equals(proof.blockEntities())||!marker.spawnSlots().equals(proof.metadata().spawnSlots())
                    ||!marker.realizedStates().equals(proof.metadata().realizedStates()))
                throw failure(FailureCode.T0_CORRUPT,"FULL marker mismatch");
            store.verifyExitProof(proof);
            return null;
          },false);
          return accounted(terrain,()->{
            var coverage=store.readCoverage(owner).orElseThrow(()->failure(FailureCode.T0_MISSING,"FULL owner coverage missing"));
            if(!marker.coverageHash().equals(coverage.coverage().hash()))throw failure(FailureCode.T0_CORRUPT,"FULL coverage mismatch");
            for(var key:keys(coverage.coverage(),minY,maxY))
                accounted(terrain,()->{
                    if(store.read(key,marker.recipe()).isEmpty())throw failure(FailureCode.T0_MISSING,"FULL owner page missing");
                    return null;
                },false);
            return null;
          },false);
        });
    }
    private static <T> T accounted(AllocationBudget budget,IoAction<T> action,boolean retain) {
        java.util.function.Supplier<T> producer=()->{
            try{return action.run();}catch(IOException failure){throw new CompletionException(failure);}
        };
        return retain?CompiledAllocation.retainAll(budget,producer):CompiledAllocation.run(budget,producer,value->0);
    }
    private Saved readSaved(Coverage coverage,List<TerrainTile.Key> keys,String recipe)throws IOException {
        allocationCounts.merge("data",1L,Long::sum);
        var intent=store.readCoverageIntent(coverage.chunk());
        if(intent.isPresent()&&!intent.get().equals(coverage))throw failure(FailureCode.RECIPE_MISMATCH,"capture intent changed");
        var metadata=store.readCoverage(coverage.chunk());
        if(metadata.isPresent()&&!metadata.get().coverage().equals(coverage))throw failure(FailureCode.RECIPE_MISMATCH,"coverage changed");
        var tiles=new HashMap<TerrainTile.Key,TerrainTile>();
        for(var key:keys)store.read(key,recipe).ifPresent(tile->tiles.put(key,tile));
        if(intent.isEmpty()&&(!tiles.isEmpty()||metadata.isPresent()))throw failure(FailureCode.T0_CORRUPT,"evidence without original capture intent");
        if(metadata.isPresent()&&tiles.size()!=keys.size())throw failure(FailureCode.T0_MISSING,"completed capture lost a page");
        return new Saved(Map.copyOf(tiles),metadata);
    }
    private Hints allocationHints(Coverage coverage,List<TerrainTile.Key> keys)throws IOException {
        var pages=new HashMap<TerrainTile.Key,Long>();
        for(var key:keys){var hint=store.tileAllocationHint(key);if(hint.isPresent())pages.put(key,hint.getAsLong());}
        return new Hints(Map.copyOf(pages),store.metadataAllocationHint(coverage.chunk()));
    }
    /** Counting pass borrows native states; it does not allocate T0 arrays or decoded palettes. */
    private long forecast(ChunkAccess owner,List<TerrainTile.Key> keys,Hints hints,Map<ChunkPos,ChunkAccess> prepared) {
        // Includes the later 256 column views, their hashes and all copied index maps.
        long bytes=256L*512+16384;
        boolean summarizeProtection=hints.metadata().isEmpty();
        if(owner.getAttached(TerrainMarkers.PRODUCTION)!=null&&(summarizeProtection||hints.pages().size()!=keys.size()))
            throw failure(FailureCode.T0_MISSING,"applied owner lacks original allocation evidence");
        long protectedCount=0;
        var entities=owner.getBlockEntitiesPos();var pos=new BlockPos.MutableBlockPos();
        for(var key:keys) {
            var saved=hints.pages().get(key);
            if(saved!=null){bytes=Math.addExact(bytes,saved);if(!summarizeProtection)continue;}
            var plain=new HashMap<net.minecraft.world.level.block.state.BlockState,Boolean>();
            var blockEntities=new HashMap<net.minecraft.world.level.block.state.BlockState,Boolean>();
            long paletteBytes=0;
            for(int y=key.minY();y<=key.maxY();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                pos.set(key.chunkX()*16+x,y,key.chunkZ()*16+z);
                var block=owner.getBlockState(pos);boolean entity=entities.contains(pos);
                var states=entity?blockEntities:plain;
                Boolean protect=states.get(block);
                if(protect==null) {
                    String token=BlockCatalog.token(block,entity);
                    paletteBytes=Math.addExact(paletteBytes,128L+4L*token.length());
                    protect=catalog.flags(token).protectedBlock();states.put(block,protect);
                }
                if(summarizeProtection&&protect)protectedCount++;
            }
            if(saved==null)bytes=Math.addExact(bytes,1024L+4L*key.volume()+paletteBytes);
        }
        if(hints.metadata().isPresent())bytes=Math.addExact(bytes,hints.metadata().getAsLong());
        else bytes=Math.addExact(bytes,16384L+128L*(protectedCount+entities.size()
                +ProtectionSnapshot.structureBoxUpperBound(owner,predictedStructures.size(),prepared)+1));
        return bytes;
    }
    private TerrainTile capturePage(ChunkAccess owner,TerrainTile.Key key,String recipe) {
        var palette=new ArrayList<String>();var ids=new HashMap<String,Integer>();var tokens=new HashMap<net.minecraft.world.level.block.state.BlockState,String>();
        int[] states=new int[key.volume()];int cursor=0;var entities=owner.getBlockEntitiesPos();var pos=new BlockPos.MutableBlockPos();
        for(int y=key.minY();y<=key.maxY();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            pos.set(key.chunkX()*16+x,y,key.chunkZ()*16+z);
            var state=owner.getBlockState(pos);
            String token=entities.contains(pos)?BlockCatalog.token(state,true):tokens.computeIfAbsent(state,s->BlockCatalog.token(s,false));
            Integer id=ids.get(token);if(id==null){id=palette.size();palette.add(token);ids.put(token,id);}states[cursor++]=id;
        }
        return new TerrainTile(key,recipe,palette,states);
    }
    private List<ColumnEvidence> summarize(ChunkAccess owner,int from,int to) {
        var result=new ArrayList<ColumnEvidence>();var pos=new BlockPos.MutableBlockPos();
        for(int n=from;n<to;n++){
            int x=owner.getPos().getMinBlockX()+n%16,z=owner.getPos().getMinBlockZ()+n/16;
            int y=Math.min(owner.getMaxBuildHeight()-1,owner.getHeight(Heightmap.Types.WORLD_SURFACE_WG,n%16,n/16));
            int scanned=0;
            for(;y>=owner.getMinBuildHeight()&&scanned<256;y--,scanned++) {
                pos.set(x,y,z);var flags=catalog.flags(BlockCatalog.token(owner.getBlockState(pos),false));
                if(!flags.replaceablePlant())break;
            }
            result.add(new ColumnEvidence(x,z,y+1,scanned==256&&y>=owner.getMinBuildHeight()));
        }
        return List.copyOf(result);
    }
    private static List<TerrainTile.Key> keys(Coverage c,int minY,int maxY) {
        var keys=new ArrayList<TerrainTile.Key>();
        for(int base:c.tileBases()){
            int lo=Math.max(base,minY),hi=Math.min(Math.addExact(base,127),maxY-1);
            if(lo>hi)throw failure(FailureCode.OUT_OF_BOUNDS,"page outside dimension");
            keys.add(new TerrainTile.Key(c.chunk().dimension(),c.chunk().x(),c.chunk().z(),lo,hi));
        }return List.copyOf(keys);
    }
    private <T> CompletableFuture<T> io(IoAction<T> action) {
        try{return CompletableFuture.supplyAsync(()->{active();try{return action.run();}catch(IOException e){throw new CompletionException(e);}},io);}
        catch(RejectedExecutionException e){return CompletableFuture.failedFuture(e);}
    }
    private void active(){if(state.containsKey("closed"))throw failure(FailureCode.CLOSED,"snapshot service closed");}
    @Override public void close(){
        if(state.putIfAbsent("closed",true)!=null)return;
        // In-flight engine/IO actions still own pages. Their terminal continuations release pins,
        // then fail the capture; callers must not free the enclosing arena before that point.
    }
    private static WallFailure failure(FailureCode code,String message){return new WallFailure(code,message);}
    @FunctionalInterface private interface IoAction<T>{T run()throws IOException;}
}
