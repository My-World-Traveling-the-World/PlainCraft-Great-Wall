package local.plaincraft.greatwall.generation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.diagnostics.BudgetReservation;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.*;

/** Production strategy. Async admission happens outside native locks; no future is synchronously waited. */
public final class ProductionWallPipeline implements AutoCloseable {
    public record Ports(BiFunction<ChunkKey,Integer,PlanLease<PlanWindow>> plans,
            Function<CellKey,PlanLease<PlanWindow>> arcPlans,
            Function<List<ChunkPos>,PlanLease<Map<ChunkPos,ChunkAccess>>> features,
            Runnable assertActive,Consumer<Throwable> fail,Executor worker) {
        public Ports {Objects.requireNonNull(plans);Objects.requireNonNull(arcPlans);Objects.requireNonNull(features);Objects.requireNonNull(assertActive);
            Objects.requireNonNull(fail);Objects.requireNonNull(worker);}
    }
    private record Collected(List<RealizationCell> cells,Map<Point3i,String> before,Coverage own,long bytes,List<GroundExitDecision> exits) {
        Collected {cells=List.copyOf(cells);before=Map.copyOf(before);exits=List.copyOf(exits);}
    }
    private record CellTerrain(RealizationPlanner.Input input,Map<ChunkKey,CaptureMetadata> metadata,
            PlanLease<BudgetReservation.Reservation> reservation,AllocationBudget allocations) {
        CellTerrain {metadata=Map.copyOf(metadata);}
    }
    private static final class CellCaptureAccumulator {
        private final Map<TerrainTile.Key,TerrainTile> tiles=new HashMap<>();
        private final Map<ChunkKey,CaptureMetadata> metadata=new HashMap<>();
        private void add(TerrainSnapshotService.CapturedTerrain captured) {
            tiles.putAll(captured.tiles());metadata.putAll(captured.metadata());
        }
        private TerrainSnapshotService.CapturedTerrain freeze() {
            return new TerrainSnapshotService.CapturedTerrain(tiles,metadata);
        }
    }
    private final WorldRecipe recipe;
    private final Ports ports;
    private final TerrainSnapshotService snapshots;
    private final RealizationPlanner realization;
    private final ChunkPlanCompiler compiler;
    private final ChunkWallWriter writer;
    private final CoveragePlanner coverage=new CoveragePlanner();
    private final StageTrace trace=new StageTrace(UUID.randomUUID());
    private final ConcurrentHashMap<ChunkKey,CompletableFuture<Void>> flights=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID,AutoCloseable> leases=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID,PlanLease<?>> allocationPins=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<Void>> queue=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Boolean> state=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> admitted=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,GroundExitResolver> exitResolvers=new ConcurrentHashMap<>();
    private final BudgetReservation terrainReservations;
    private final BudgetReservation compiledReservations;
    private final ConcurrentHashMap<String,AllocationBudget> compilationAllocations=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> allocationPeaks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> costs=new ConcurrentHashMap<>();
    public ProductionWallPipeline(WorldRecipe recipe,Ports ports,TerrainSnapshotService snapshots,
            RealizationPlanner realization,ChunkPlanCompiler compiler,ChunkWallWriter writer) {
        this.recipe=Objects.requireNonNull(recipe);this.ports=Objects.requireNonNull(ports);this.snapshots=Objects.requireNonNull(snapshots);
        this.realization=Objects.requireNonNull(realization);this.compiler=Objects.requireNonNull(compiler);this.writer=Objects.requireNonNull(writer);
        this.terrainReservations=new BudgetReservation(recipe.config().terrainBytes(),4,ports.worker());
        this.compiledReservations=new BudgetReservation(recipe.config().compiledBytes(),4,ports.worker());
        queue.put("tail",CompletableFuture.completedFuture(null));
    }
    public CompletableFuture<Void> beforeFeatures(ChunkKey key) {
        try{
            active();trace.assertOutsideLock();
            var lease=track(ports.plans().apply(key,32));
            return lease.future().thenAccept(plans->{active();event("PRODUCTION_PLAN_READY",key,"prepare","none");})
                    .whenComplete((v,e)->release(lease));
        }catch(Throwable error){return failed(error);}
    }
    public CompletableFuture<Void> beforeLight(ChunkKey key,ChunkAccess owner,TerrainEngine engine) {
        try{
            active();trace.assertOutsideLock();
            if(!key.dimension().equals(recipe.dimension()))return CompletableFuture.completedFuture(null);
            if(key.x()!=owner.getPos().x||key.z()!=owner.getPos().z)throw failure(FailureCode.OWNERSHIP_CONFLICT,"owner mismatch");
            var inFlight=flights.get(key);if(inFlight!=null)return inFlight.copy();
            admitted.compute("count",(k,n)->{
                int old=n==null?0:n;
                if(old>=256)throw failure(FailureCode.CAPACITY,"production owner waiter limit");
                return old+1;
            });
            var promise=new CompletableFuture<Void>();var existing=flights.putIfAbsent(key,promise);
            if(existing!=null){admitted.compute("count",(k,n)->n-1);return existing.copy();}
            promise.whenComplete((v,e)->{flights.remove(key,promise);admitted.compute("count",(k,n)->n-1);});
            var done=new CompletableFuture<Void>();var prior=queue.put("tail",done);
            try{
            long requestedAt=System.nanoTime();
            prior.handle((v,e)->null).thenComposeAsync(v->{
                long startedAt=System.nanoTime();
                cost("queue",startedAt-requestedAt);
                return runOwner(key,owner,engine).whenComplete((value,error)->cost(error==null?"owner":"failed-owner",System.nanoTime()-startedAt));
            },ports.worker())
                    .thenApplyAsync(v->{active();return v;},ports.worker()).whenComplete((v,error)->{
                try{
                    if(error==null){active();promise.complete(null);}
                    else{promise.completeExceptionally(error);if(!state.containsKey("closed"))ports.fail().accept(error);}
                }catch(Throwable stopped){promise.completeExceptionally(stopped);}
                finally{done.complete(null);}
            });
            }catch(Throwable failure){promise.completeExceptionally(failure);done.complete(null);if(!state.containsKey("closed"))ports.fail().accept(failure);}
            return promise.copy();
        }catch(Throwable error){return failed(error);}
    }
    private CompletableFuture<Void> runOwner(ChunkKey key,ChunkAccess owner,TerrainEngine engine) {
        active();
        // Replays validate durable evidence and the marker; player edits are not recaptured as T0.
        return engine.exclusive(owner.getPos(),()->{active();return owner.getAttached(TerrainMarkers.PRODUCTION);})
                .thenComposeAsync(marker->{
                    if(marker!=null&&marker.complete())
                        return verifyLoaded(key,marker,owner.getMinBuildHeight(),owner.getMaxBuildHeight());
                    var lease=track(ports.plans().apply(key,512));
                    return lease.future().thenComposeAsync(plans->{
                        active();var cells=coverage.cellsForChunk(key,plans);
                        if(cells.isEmpty())return CompletableFuture.completedFuture(null);
                        if(owner.getPersistedStatus().isOrAfter(ChunkStatus.FULL))
                            throw failure(FailureCode.EXISTING_WORLD,"FULL wall owner lacks completed marker");
                        return compiledReservations.acquire(recipe.config().compiledBytes()).thenCompose(compiledLease -> {
                            var allocations=new AllocationBudget(recipe.config().compiledBytes());
                            try {
                                trackPin(compiledLease);
                                if(compilationAllocations.putIfAbsent("owner",allocations)!=null)throw new IllegalStateException("overlapping owner compilation");
                                return CompletableFuture.completedFuture((Void)null)
                                        .thenComposeAsync(v -> compileAndApply(key,owner,engine,plans,cells),ports.worker())
                                        .handle((v,error)->error)
                                        .thenCompose(error->closeExitResolvers().handle((ignored,drainError)->{
                                            allocationPeaks.merge("compiledAllocationPeak",allocations.stats().peak(),Math::max);
                                            compilationAllocations.remove("owner",allocations);allocations.close();releasePin(compiledLease);
                                            if(error!=null)throw new CompletionException(error);
                                            if(drainError!=null)throw new CompletionException(drainError);
                                            return (Void)null;
                                        }));
                            } catch (Throwable error) {
                                compilationAllocations.remove("owner",allocations);allocations.close();
                                releasePin(compiledLease);
                                return CompletableFuture.<Void>failedFuture(error);
                            }
                        }).toCompletableFuture();
                    },ports.worker()).whenComplete((v,e)->release(lease));
                },ports.worker());
    }
    private CompletableFuture<Void> compileAndApply(ChunkKey key, ChunkAccess owner, TerrainEngine engine,
            PlanWindow plans, List<CellKey> cells) {
        CompletableFuture<Collected> chain=CompletableFuture.completedFuture(new Collected(List.of(),Map.of(),null,0,List.of()));
        for(var cell:cells)chain=chain.thenComposeAsync(prior->resolveCell(cell,plans,key,engine)
                .thenApply(next->combine(prior,next)),ports.worker());
        return chain.thenComposeAsync(collected->{
            active();
            if(collected.own()==null) {
                System.err.println("S1C_OWNER_COVERAGE_MISSING owner="+key+" requestedCells="+cells);
                throw failure(FailureCode.T0_MISSING,"owner not in realization evidence");
            }
            var plan=CompiledAllocation.run(compilationBudget(),()->compiler.bindCoverage(
                    compiler.compile(key,recipe.recipeHash(),collected.cells(),collected.before()),collected.own().hash()),
                    value->4096L+512L*value.voxels().size());
            event("PRODUCTION_COMPILED",key,"compile","none");
            return snapshots.persistPlan(plan,collected.exits(),compilationBudget()).thenCompose(v->{
                active();event("PRODUCTION_PROOF_DURABLE",key,"persist","none");
                return engine.exclusive(owner.getPos(),()->{
                    active();event("PRODUCTION_APPLY_ENTER",key,"apply",engine.name());
                    long startedAt=System.nanoTime();
                    var report=CompiledAllocation.run(compilationBudget(),()->writer.apply(owner,plan,WriteMode.INITIAL),value->0);
                    cost("apply",System.nanoTime()-startedAt);
                    costs.merge("written-voxels",(long)report.written(),Long::sum);
                    if(!report.complete())throw failure(FailureCode.OWNERSHIP_CONFLICT,"preflight conflicts: "+report.conflicts());
                    event("PRODUCTION_APPLY_EXIT",key,"apply",engine.name());return (Void)null;
                });
            });
        },ports.worker());
    }
    private CompletableFuture<Collected> resolveCell(CellKey cell,PlanWindow initial,ChunkKey owner,TerrainEngine engine) {
        return exitResolver(engine).require(cell)
            .exceptionally(failure -> {
                // A cell with no T0-accepted ground exit left of it (cell 0 has none) is a terrain
                // unsuitability, not a session failure: the wall's anchor fort (FORT0) is allowed to
                // degrade and the rest of the wall keeps generating. RealizationPlanner already
                // records FORT0_DEGRADED for an empty/no-exit result, so surface the failure as an
                // empty decision list instead of throwing and stopping the server.
                if (failure instanceof WallFailure wall && wall.code() == FailureCode.ROOT_NOT_FOUND) {
                    GreatWallMod.LOGGER.warn("[GreatWall:S1C] FORT0_DEGRADED reason=no-ground-exit cell={} owner={}; "
                            + "the wall continues without its root fort", cell, owner);
                    return List.<GroundExitDecision>of();
                }
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new java.util.concurrent.CompletionException(failure);
            })
            .thenComposeAsync(decisions->{
            active();event("PRODUCTION_EXIT_READY",owner,"certified","none");
            return useCell(cell,engine,data->{
                active();var input=data.input();
                var result=CompiledAllocation.run(compilationBudget(),()->realization.resolve(input,decisions),ProductionWallPipeline::componentBytes);
                var before=new HashMap<Point3i,String>();
                for(var component:result.components())for(var v:component.voxels())if(Math.floorDiv(v.pos().x(),16)==owner.x()
                        &&Math.floorDiv(v.pos().z(),16)==owner.z()){
                    var column=input.columns().get(new Point3i(v.pos().x(),0,v.pos().z()));
                    if(column==null)throw failure(FailureCode.T0_MISSING,"write outside captured columns");
                    if(!before.containsKey(v.pos()))compilationBudget().consume(128);
                    before.put(v.pos(),column.state(v.pos().y()));
                }
                var own=data.metadata().get(owner);
                if(own==null) {
                    var projection=local.plaincraft.greatwall.geometry.Projection.nearest(
                            owner.x()*16+8,owner.z()*16+8,new GeometryWindow(
                                    initial.plans().stream().map(SegmentPlan::geometry).toList(),
                                    initial.plans().stream().map(SegmentPlan::prefix).toList()));
                    System.err.println("S1C_OWNER_CELL_DIAGNOSTIC owner="+owner+" cell="+cell
                            +" lateralQ8="+projection.lateralQ8()+" globalSQ8="+projection.globalSQ8()
                            +" capturedOwners="+data.metadata().keySet().stream()
                                    .sorted(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z)).toList());
                }
                return new Collected(List.of(result),before,own==null?null:own.coverage(),componentBytes(result)+128L*before.size(),decisions);
            });
        },ports.worker());
    }
    private GroundExitResolver exitResolver(TerrainEngine engine) {
        return exitResolvers.computeIfAbsent(engine.name(),name->new GroundExitResolver(new GroundExitResolver.Source(){
            public CompletableFuture<GroundExitDecision.Slice> inspect(CellKey cell) {
                trace.assertOutsideLock();return useCell(cell,engine,
                        data->CompiledAllocation.run(compilationBudget(),()->realization.inspect(data.input()),RealizationPlanner::inspectionBytes));
            }
            public CompletableFuture<GroundExitDecision.CapVote> cap(GroundExitDecision.Key key,GroundExitDecision.Edge edge) {
                trace.assertOutsideLock();var cell=new CellKey(key.recipeHash(),key.dimension(),Math.floorDiv(edge.sQ8(),64L*256));
                return useCell(cell,engine,
                        data->CompiledAllocation.run(compilationBudget(),()->realization.probeCap(data.input(),edge),RealizationPlanner::capVoteBytes));
            }
            public CompletableFuture<Void> persist(GroundExitDecision decision) {
                trace.assertOutsideLock();return snapshots.persistExitDecision(decision,compilationBudget());
            }
        },ports.worker(),Math.min(8L<<20,recipe.config().compiledBytes()/4),this::compilationBudget));
    }
    private <T> CompletableFuture<T> useCell(CellKey cell,TerrainEngine engine,Function<CellTerrain,T> action) {
        return loadCell(cell,engine).thenCompose(data->{
            try {
                return CompletableFuture.supplyAsync(()->{active();return action.apply(data);},ports.worker())
                        .whenComplete((value,error)->releaseTerrain(data));
            } catch(Throwable rejected) {
                releaseTerrain(data);
                return CompletableFuture.failedFuture(rejected);
            }
        });
    }
    private CompletableFuture<CellTerrain> loadCell(CellKey cell,TerrainEngine engine) {
        active();
        trace.assertOutsideLock();
        return terrainReservations.acquire(recipe.config().terrainBytes()).thenCompose(reservation->{
          PlanLease<PlanWindow> lease;
          var pin=reservation;
          var allocations=new AllocationBudget(recipe.config().terrainBytes());
          try {trackPin(pin);lease=track(ports.arcPlans().apply(cell));}
          catch(Throwable error){allocations.close();releasePin(pin);return CompletableFuture.<CellTerrain>failedFuture(error);}
          return lease.future().thenComposeAsync(plans->{
            active();var owners=coverage.realizationOwners(cell,plans);
            // Serial future completion publishes each merge before the next owner; existing forecast pins cover the maps.
            var accumulated=new CellCaptureAccumulator();
            CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
            for(var member:owners)chain=chain.thenComposeAsync(ignored->capture(member,engine,allocations)
                    .thenAccept(accumulated::add),ports.worker());
            return chain.thenApplyAsync(ignored->{
                active();var captured=accumulated.freeze();
                var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();var protection=new ArrayList<Bounds>();
                for(var metadata:captured.metadata().values()){
                    protection.addAll(metadata.protectedStructures());
                    columns.putAll(SnapshotColumnView.columns(metadata,captured.tiles(),snapshots.catalog()));
                }
                String protectionHash=DeterministicHash.sha256(captured.metadata().values().stream()
                        .map(CaptureMetadata::hash).sorted().toList().toString().getBytes(StandardCharsets.UTF_8));
                return new CellTerrain(new RealizationPlanner.Input(cell,plans,captured.tiles(),columns,protection,Map.of(),0,protectionHash),captured.metadata(),pin,allocations);
            },ports.worker());
          },ports.worker()).whenComplete((v,e)->release(lease))
                  .whenComplete((v,e)->{if(e!=null){allocations.close();releasePin(pin);}});
        }).toCompletableFuture();
    }
    private CompletableFuture<TerrainSnapshotService.CapturedTerrain> capture(ChunkKey key,TerrainEngine engine,AllocationBudget allocations) {
        active();var plan=track(ports.plans().apply(key,256));
        return plan.future().thenComposeAsync(window->{
            active();Coverage c=coverage.forChunk(key,window,recipe.bounds());
            int radius=ProtectionSnapshot.leaseRadiusChunks();
            var positions=new ArrayList<ChunkPos>();
            for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)positions.add(new ChunkPos(key.x()+x,key.z()+z));
            var features=track(ports.features().apply(List.copyOf(positions)));
            event("PRODUCTION_PREPARE_FEATURES",key,"features","none");
            return features.future().thenComposeAsync(chunks->{
                active();var map=new HashMap<ChunkKey,ChunkAccess>();
                chunks.forEach((p,ch)->map.put(new ChunkKey(key.dimension(),p.x,p.z),ch));
                // The S1 contract requires every cross-chunk structure reference to be
                // resolved from lease content rather than a synchronous getChunk. A
                // reference points at the chunk where a structure STARTS, which can sit
                // further out than the FEATURES write radius (measured on seed 17: +4 and
                // -2 chunks while the radius is 1), and an unresolved reference makes
                // ProtectionSnapshot protect the whole read window - which blocked the
                // fort0 entrance on 188/188 columns with zero terrain faults. Prepare
                // exactly the referenced chunks instead of widening the blanket halo.
                return withReferenceChunks(key,map).thenComposeAsync(prepared->
                        snapshots.capture(c,prepared,engine,recipe.recipeHash(),allocations),ports.worker());
            },ports.worker()).whenComplete((v,e)->release(features));
        },ports.worker()).whenComplete((v,e)->release(plan));
    }

    /**
     * Adds the chunks named by the owner's structure references to an already
     * prepared FEATURES map. Only the chunks actually referenced are requested,
     * and the total stays inside the frozen 81-chunk lease bound.
     */
    private CompletableFuture<Map<ChunkKey,ChunkAccess>> withReferenceChunks(ChunkKey key,
            Map<ChunkKey,ChunkAccess> halo) {
        ChunkAccess owner=halo.get(key);
        if(owner==null)return CompletableFuture.completedFuture(Map.copyOf(halo));
        var extra=new ArrayList<ChunkPos>();
        for(var entry:owner.getAllReferences().entrySet())for(long reference:entry.getValue()) {
            var pos=new ChunkPos(reference);
            var candidate=new ChunkKey(key.dimension(),pos.x,pos.z);
            if(halo.containsKey(candidate))continue;
            if(extra.stream().anyMatch(p->p.x==pos.x&&p.z==pos.z))continue;
            extra.add(pos);
        }
        if(extra.isEmpty())return CompletableFuture.completedFuture(Map.copyOf(halo));
        if(Math.addExact(halo.size(),extra.size())>81)throw failure(FailureCode.CAPACITY,
                "structure-reference lease exceeds the frozen 81-chunk bound: halo="+halo.size()
                        +" references="+extra.size());
        var lease=track(ports.features().apply(List.copyOf(extra)));
        event("PRODUCTION_PREPARE_REFERENCES",key,"references",Integer.toString(extra.size()));
        return lease.future().thenApplyAsync(chunks->{
            active();var merged=new HashMap<>(halo);
            chunks.forEach((p,ch)->merged.put(new ChunkKey(key.dimension(),p.x,p.z),ch));
            return Map.copyOf(merged);
        },ports.worker()).whenComplete((v,e)->release(lease));
    }
    public CompletableFuture<Void> verifyLoaded(ChunkAccess owner) {
        var key=new ChunkKey(recipe.dimension(),owner.getPos().x,owner.getPos().z);
        var marker=owner.getAttached(TerrainMarkers.PRODUCTION);
        if(marker!=null)return verifyLoaded(key,marker,owner.getMinBuildHeight(),owner.getMaxBuildHeight());
        try{
            active();var lease=track(ports.plans().apply(key,512));
            return lease.future().thenAccept(plans->{
                active();
                if(!coverage.cellsForChunk(key,plans).isEmpty())
                    throw failure(FailureCode.EXISTING_WORLD,"FULL wall owner without production marker; no old-world backfill");
            }).whenComplete((v,e)->release(lease));
        }catch(Throwable error){return failed(error);}
    }
    private CompletableFuture<Void> verifyLoaded(ChunkKey key,TerrainMarkers.Marker marker,int minY,int maxY) {
        active();
        if(!marker.recipe().equals(recipe.recipeHash()))return failed(failure(FailureCode.RECIPE_MISMATCH,"foreign FULL marker"));
        return withBudget(compiledReservations,recipe.config().compiledBytes(),compiled->
                withBudget(terrainReservations,recipe.config().terrainBytes(),terrain->
                        snapshots.verifyLoaded(key,marker,minY,maxY,terrain,compiled)))
                .thenRun(()->{active();event("PRODUCTION_REPLAY_VERIFIED",key,"load-read-only","none");});
    }
    private <T> CompletableFuture<T> withBudget(BudgetReservation reservations,long bytes,
            Function<AllocationBudget,CompletableFuture<T>> operation) {
        return reservations.acquire(bytes).thenCompose(pin->{
            var budget=new AllocationBudget(bytes);
            try {
                trackPin(pin);
                return operation.apply(budget).whenComplete((value,error)->{budget.close();releasePin(pin);});
            } catch(Throwable failure) {
                budget.close();releasePin(pin);return CompletableFuture.<T>failedFuture(failure);
            }
        }).toCompletableFuture();
    }
    private Collected combine(Collected a,Collected b) {
        long bytes=Math.addExact(a.bytes(),b.bytes());
        var cells=new ArrayList<>(a.cells());cells.addAll(b.cells());
        var before=new HashMap<>(a.before());b.before().forEach((p,value)->{
            var old=before.putIfAbsent(p,value);if(old!=null&&!old.equals(value))throw failure(FailureCode.T0_CORRUPT,"two before-states");
        });
        if(a.own()!=null&&b.own()!=null&&!a.own().equals(b.own()))throw failure(FailureCode.RECIPE_MISMATCH,"owner coverage changed between cells");
        var exits=new TreeMap<String,GroundExitDecision>();
        for(var d:a.exits())exits.put(d.hash(),d);for(var d:b.exits())exits.put(d.hash(),d);
        return new Collected(cells,before,a.own()==null?b.own():a.own(),bytes,List.copyOf(exits.values()));
    }
    private ChunkKey cellCenter(CellKey cell,PlanWindow window) {
        long s=cell.arcCell()*64L*256+32L*256;
        var stations=window.plans().stream().flatMap(p->p.stations().stations().stream()).sorted(Comparator.comparingLong(Station::globalSQ8)).toList();
        if(stations.isEmpty())throw failure(FailureCode.MISSING_FEATURES_PLAN,"empty cell contracts");
        var point=stations.getLast().point();
        for(int i=1;i<stations.size();i++){
            var a=stations.get(i-1);var b=stations.get(i);
            if(s<a.globalSQ8()||s>b.globalSQ8()||b.globalSQ8()==a.globalSQ8())continue;
            long length=b.globalSQ8()-a.globalSQ8(),offset=s-a.globalSQ8();
            point=new Point2q(a.point().xQ8()+FixedMath.mulDiv(b.point().xQ8()-a.point().xQ8(),offset,length),
                    a.point().zQ8()+FixedMath.mulDiv(b.point().zQ8()-a.point().zQ8(),offset,length));break;
        }
        return new ChunkKey(cell.dimension(),Math.floorDiv(FixedMath.floorBlock(point.xQ8()),16),Math.floorDiv(FixedMath.floorBlock(point.zQ8()),16));
    }
    private void releaseTerrain(CellTerrain data){allocationPeaks.merge("terrainAllocationPeak",data.allocations().stats().peak(),Math::max);
        data.allocations().close();releasePin(data.reservation());}
    private CompletableFuture<Void> closeExitResolvers(){
        // Certificates remain durable; memory-only summaries cannot outlive their owning arena.
        var pending=new ArrayList<CompletableFuture<Void>>();
        exitResolvers.values().forEach(resolver->{resolver.close();pending.add(resolver.drained());});exitResolvers.clear();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }
    private AllocationBudget compilationBudget(){
        active();var value=compilationAllocations.get("owner");
        if(value==null)throw new IllegalStateException("producer has no prior compiled reservation");return value;
    }
    private static long componentBytes(RealizationCell cell){
        return cell.components().stream().mapToLong(c->512L+c.voxels().size()*256L+c.blockEntities().size()*256L).sum();
    }
    private <T> PlanLease<T> track(PlanLease<T> lease){
        leases.put(lease.id(),lease);
        try{active();return lease;}catch(Throwable error){release(lease);throw error;}
    }
    private void release(PlanLease<?> lease){leases.remove(lease.id());lease.close();}
    private <T> PlanLease<T> trackPin(PlanLease<T> lease){
        allocationPins.put(lease.id(),lease);
        try{active();return lease;}catch(Throwable error){releasePin(lease);throw error;}
    }
    private void releasePin(PlanLease<?> lease){allocationPins.remove(lease.id());lease.close();}
    private void active(){if(state.containsKey("closed"))throw failure(FailureCode.CLOSED,"production pipeline closed");ports.assertActive().run();}
    private <T> CompletableFuture<T> failed(Throwable error){if(!state.containsKey("closed"))ports.fail().accept(error);return CompletableFuture.failedFuture(error);}
    private void event(String e,ChunkKey key,String stage,String lock){trace.record(e,key.dimension(),key.x(),key.z(),stage,lock);}
    private void cost(String name,long nanos) {
        costs.merge("cost-"+name+"-count",1L,Long::sum);
        costs.merge("cost-"+name+"-total-nanos",nanos,Long::sum);
        costs.merge("cost-"+name+"-max-nanos",nanos,Math::max);
    }
    public Map<String,Long> diagnostics(){
        var result=new HashMap<>(trace.counts());result.put("leases",(long)leases.size()+allocationPins.size());result.put("flights",(long)flights.size());
        result.put("terrainReserved",terrainReservations.stats().reserved());
        result.put("compiledReserved",compiledReservations.stats().reserved());
        result.putAll(allocationPeaks);
        result.putAll(costs);
        result.put("admitted",(long)admitted.getOrDefault("count",0));return Map.copyOf(result);
    }
    @Override public void close(){
        if(state.putIfAbsent("closed",true)!=null)return;
        flights.values().forEach(f->f.completeExceptionally(failure(FailureCode.CLOSED,"production pipeline closed")));flights.clear();
        leases.values().forEach(l->{try{l.close();}catch(Exception ignored){}});leases.clear();
        // Do not complete internal dependency futures early: they may still own entered captures.
        queue.get("tail").whenComplete((value,error)->{
            closeExitResolvers();
        });
        terrainReservations.close();compiledReservations.close();
        // Reservation owners release only after their entered producer/consumer chains quiesce.
        // Closing admissions cannot make still-live T0/compiled objects unaccounted.
        snapshots.close();
    }
    private static WallFailure failure(FailureCode code,String message){return new WallFailure(code,message);}
}
