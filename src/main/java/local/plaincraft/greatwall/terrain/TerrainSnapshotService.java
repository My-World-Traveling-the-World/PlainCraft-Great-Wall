package local.plaincraft.greatwall.terrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.EvidenceCodec;
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
    private final ConcurrentHashMap<Coverage,CompletableFuture<CapturedTerrain>> flights=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkKey,Coverage> claims=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Boolean> state=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> admission=new ConcurrentHashMap<>();
    private final ChunkEvidenceStore store;
    private final Executor io;
    private final BlockCatalog catalog;
    public TerrainSnapshotService(ChunkEvidenceStore store,Executor io) {this(store,io,new BlockCatalog(RegistryAccess.EMPTY));}
    public TerrainSnapshotService(ChunkEvidenceStore store,Executor io,BlockCatalog catalog) {
        this.store=Objects.requireNonNull(store);this.io=Objects.requireNonNull(io);this.catalog=Objects.requireNonNull(catalog);
    }
    public BlockCatalog catalog(){return catalog;}
    public CompletableFuture<CapturedTerrain> capture(Coverage coverage,Map<ChunkKey,ChunkAccess> featuresChunks,
            TerrainEngine engine,String recipeHash) {
        CompletableFuture<CapturedTerrain> owned=null;boolean reserved=false;
        try {
            active();
            if(!coverage.hash().equals(DeterministicHash.sha256(EvidenceCodec.coverage(coverage))))throw failure(FailureCode.RECIPE_MISMATCH,"coverage hash mismatch");
            ChunkAccess owner=featuresChunks.get(coverage.chunk());
            if(owner==null)throw failure(FailureCode.T0_MISSING,"owner not prepared");
            int radius=ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
            if(radius<0||radius>4)throw failure(FailureCode.UNSUPPORTED_ADAPTER,"unsupported FEATURES halo");
            for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
                var key=new ChunkKey(coverage.chunk().dimension(),coverage.chunk().x()+x,coverage.chunk().z()+z);
                var chunk=featuresChunks.get(key);
                if(chunk==null||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)||chunk.getPos().x!=key.x()||chunk.getPos().z!=key.z())
                    throw failure(FailureCode.MISSING_FEATURES_PLAN,"FEATURES halo not ready: "+key);
            }
            var existing=flights.get(coverage);if(existing!=null)return existing.copy();
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
            existing=flights.putIfAbsent(coverage,promise);
            if(existing!=null){releaseAdmission();reserved=false;return existing.copy();}
            owned=promise;
            promise.whenComplete((v,e)->{
                flights.remove(coverage,promise);claims.remove(coverage.chunk(),coverage);releaseAdmission();
            });
            reserved=false;
            active();
            io(()->readSaved(coverage,keys,recipeHash)).thenComposeAsync(saved->
                    engine.exclusive(owner.getPos(),()->{
                        active();var marker=owner.getAttached(TerrainMarkers.PRODUCTION);
                        if(marker!=null&&(saved.metadata().isEmpty()||saved.tiles().size()!=keys.size()))
                            throw failure(FailureCode.T0_MISSING,"applied owner has missing T0 evidence");
                        if(marker!=null&&(!marker.recipe().equals(recipeHash)||!marker.coverageHash().equals(coverage.hash())))
                            throw failure(FailureCode.RECIPE_MISMATCH,"owner marker/coverage mismatch");
                        if(saved.metadata().isEmpty()&&owner.getPersistedStatus().isOrAfter(ChunkStatus.FULL))
                            throw failure(FailureCode.EXISTING_WORLD,"cannot first-capture a FULL chunk");
                        return saved;
                    }),io).thenComposeAsync(saved->io(()->{store.persistCoverageIntent(coverage);return saved;}),io).thenComposeAsync(saved->{
                        if(saved.metadata().isPresent())return CompletableFuture.completedFuture(
                                new CapturedTerrain(saved.tiles(),Map.of(coverage.chunk(),saved.metadata().get())));
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
                                active();var entities=new HashSet<Point3i>();var structures=new ArrayList<Bounds>();
                                for(var p:owner.getBlockEntitiesPos())entities.add(new Point3i(p.getX(),p.getY(),p.getZ()));
                                for(var start:owner.getAllStarts().values())if(start.isValid()){
                                    var b=start.getBoundingBox();structures.add(new Bounds(b.minX(),b.minY(),b.minZ(),b.maxX()+1,b.maxY()+1,b.maxZ()+1));
                                }
                                if(owner.hasAnyStructureReferences())structures.add(coverage.readBounds());
                                return new CaptureMetadata(coverage,summary,structures,entities,Set.of(),"0".repeat(64));
                            }),io).thenComposeAsync(base->io(()->{
                                var immutable=new HashSet<Point3i>();
                                for(var tile:captured.values())for(int y=tile.key().minY();y<=tile.key().maxY();y++)
                                    for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                                        if(catalog.flags(tile.state(x,y,z)).protectedBlock())immutable.add(new Point3i(tile.key().chunkX()*16+x,y,tile.key().chunkZ()*16+z));
                                var metadata=EvidenceCodec.hashed(new CaptureMetadata(base.coverage(),base.columns(),
                                        base.protectedStructures(),base.blockEntities(),immutable,"0".repeat(64)));
                                for(var page:captured.values())store.persistBeforeApply(page);
                                store.persistCoverage(metadata);
                                return new CapturedTerrain(captured,Map.of(coverage.chunk(),metadata));
                            }),io);
                        },io);
                    },io).whenComplete((value,error)->{
                        if(error==null&&!state.containsKey("closed"))promise.complete(value);
                        else promise.completeExceptionally(error!=null?error:failure(FailureCode.CLOSED,"snapshot service closed"));
                    });
            return promise.copy();
        }catch(Throwable failure){
            if(owned!=null)owned.completeExceptionally(failure);
            else if(reserved)releaseAdmission();
            return CompletableFuture.failedFuture(failure);
        }
    }
    private void releaseAdmission(){admission.compute("count",(k,n)->{if(n==null||n<=0)throw new IllegalStateException("snapshot admission underflow");return n-1;});}
    public Map<String,Integer> diagnostics(){return Map.of("flights",flights.size(),"claims",claims.size(),"admitted",admission.getOrDefault("count",0));}
    public CompletableFuture<Void> persistPlan(ChunkWritePlan plan){return io(()->{store.persistPlan(plan);return null;});}
    public CompletableFuture<Optional<ChunkWritePlan>> readPlan(ChunkKey owner){return io(()->store.readPlan(owner));}
    public CompletableFuture<Void> verifyLoaded(ChunkKey owner,TerrainMarkers.Marker marker,int minY,int maxY) {
        return io(()->{
            var coverage=store.readCoverage(owner).orElseThrow(()->failure(FailureCode.T0_MISSING,"FULL owner coverage missing"));
            var proof=store.readPlan(owner).orElseThrow(()->failure(FailureCode.T0_MISSING,"FULL owner proof missing"));
            if(!marker.complete()||!marker.recipe().equals(proof.recipeHash())||!marker.planHash().equals(proof.hash())
                    ||!marker.coverageHash().equals(coverage.coverage().hash())||!marker.coverageHash().equals(proof.metadata().coverageHash())
                    ||marker.gateRevision()!=proof.metadata().gateRevision()||!marker.walkwayFeet().equals(proof.walkwayFeet())
                    ||!marker.gameplay().equals(proof.blockEntities())||!marker.spawnSlots().equals(proof.metadata().spawnSlots())
                    ||!marker.realizedStates().equals(proof.metadata().realizedStates()))
                throw failure(FailureCode.T0_CORRUPT,"FULL marker mismatch");
            for(var key:keys(coverage.coverage(),minY,maxY))
                if(store.read(key,marker.recipe()).isEmpty())throw failure(FailureCode.T0_MISSING,"FULL owner page missing");
            return null;
        });
    }
    private Saved readSaved(Coverage coverage,List<TerrainTile.Key> keys,String recipe)throws IOException {
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
        flights.values().forEach(f->f.completeExceptionally(failure(FailureCode.CLOSED,"snapshot service closed")));
        flights.clear();claims.clear();
    }
    private static WallFailure failure(FailureCode code,String message){return new WallFailure(code,message);}
    @FunctionalInterface private interface IoAction<T>{T run()throws IOException;}
}
