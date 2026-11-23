package local.plaincraft.greatwall.generation;

import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.lifecycle.RootStructureScreen;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** One bootstrap's asynchronous native structure proof; never borrows live RandomState or FEATURES. */
public final class NativeRootStructureScreen implements RootStructureScreen {
    /**
     * How many candidates the structure screen checks in one window. Each candidate leases up
     * to 81 chunks for STRUCTURE_REFERENCES and, when starts are found, up to 81 more for
     * STRUCTURE_STARTS, so an unbounded window would flood the chunk ticket system with all
     * 144 candidates at once. A window of 8 keeps the in-flight leases bounded while still
     * letting each window's chunk generation run in parallel.
     */
    private static final int STRUCTURE_BATCH = 8;
    private record Fact(String id,Bounds box) {}
    private static final class Window {
        // STARTS 不能冒充 REFERENCES；两个状态独立去重，全部投票结束才释放本批票据。
        final RootStructureRequests<ChunkAccess> references = new RootStructureRequests<>();
        final RootStructureRequests<ChunkAccess> starts = new RootStructureRequests<>();
        final List<StructureLease> leases = new ArrayList<>();
    }
    private final ServerLevel level;
    private final Map<Structure,String> ids;
    private final Executor nativeOwner;
    private final ConcurrentHashMap<UUID,StructureLease> leases=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Boolean> state=new ConcurrentHashMap<>();
    private final CompletableFuture<Void> drained=new CompletableFuture<>();
    private final ConcurrentHashMap<String,Integer> producers=new ConcurrentHashMap<>();

    public NativeRootStructureScreen(ServerLevel level) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("root structure registry freezes on server owner");
        this.level=level;
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var frozen=new HashMap<Structure,String>();
        registry.forEach(s->frozen.put(s,registry.getKey(s).toString()));
        ids=Map.copyOf(frozen);
        // The global server queue: the road side drains it in managedBlock (which also makes
        // pollTask skip shouldRun()), so posting the candidate vote chain here keeps it alive
        // during startup. See StructureLease for the full reasoning.
        nativeOwner=level.getServer();
    }
    public static Bounds footprint(Axis axis) {
        // Planning may rotate the first wall tangent away from the projection axis.
        // 24*sqrt(2)<34; two extra columns cover raster and entrance guards at every heading.
        int x=axis.origin().x(),z=axis.origin().z(),w=Math.addExact(axis.origin().y(),20);
        return new Bounds(Math.subtractExact(x,36),w-35,Math.subtractExact(z,36),
                Math.addExact(x,37),w+8,Math.addExact(z,37));
    }
    private static boolean intersects(Bounds a,Bounds b) {
        return a.minX()<b.maxXExclusive()&&a.maxXExclusive()>b.minX()
                &&a.minZ()<b.maxZExclusive()&&a.maxZExclusive()>b.minZ()
                &&a.minY()<b.maxYExclusive()&&a.maxYExclusive()>b.minY();
    }
    @Override public CompletableFuture<List<Vote>> inspect(List<Candidate> candidates) {
        var frozen=List.copyOf(candidates);
        if(frozen.size()>local.plaincraft.greatwall.lifecycle.RootSearch.RADIUS_COUNT*36)
            throw new WallFailure(FailureCode.CAPACITY,"root candidate set exceeds frozen ring");
        active();producers.merge("count",1,Integer::sum);
        // Batch, don't fully chain. The old thenComposeAsync chain started candidate N+1 only
        // after candidate N's chunk lease had fully loaded AND voted, so chunk generation was
        // serialised one candidate at a time. Firing a bounded window of candidates up front
        // lets their chunk requests hit the queue together and load in parallel, while the
        // window keeps the in-flight lease count (each up to 81 chunks) bounded instead of
        // materialising every ring candidate's lease at once.
        var votes=new ArrayList<Vote>(frozen.size());
        var completed=new java.util.concurrent.atomic.AtomicInteger();
        CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
        for(int from=0;from<frozen.size();from+=STRUCTURE_BATCH){
            int to=Math.min(frozen.size(),from+STRUCTURE_BATCH);
            final int start=from;
            chain=chain.thenComposeAsync(ignored->{
                var window=new Window();
                var pending=new ArrayList<CompletableFuture<Vote>>(to-start);
                try {
                    for(int i=start;i<to;i++)pending.add(check(frozen.get(i),window));
                } catch(RuntimeException | Error failure) {
                    window.leases.forEach(this::release);
                    throw failure;
                }
                return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .thenAcceptAsync(done->{
                            for(var waiting:pending){
                                votes.add(waiting.join());
                                local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStructureProgress(
                                        completed.incrementAndGet(), frozen.size());
                            }
                        },nativeOwner)
                        .whenCompleteAsync((done,failure)->window.leases.forEach(this::release),nativeOwner);
            },nativeOwner);
        }
        CompletableFuture<List<Vote>> result=chain.thenApply(ignored->List.copyOf(votes));
        result.whenComplete((v,error)->{producers.compute("count",(k,n)->n-1);completeDrain();});
        return result.copy();
    }
    private CompletableFuture<Vote> check(Candidate candidate,Window window) {
        active();
        var footprints=candidate.axes().stream().map(NativeRootStructureScreen::footprint).toList();
        var positions=referencePositions(footprints);
        return window.references.get(positions,missing->request(window,missing,ChunkStatus.STRUCTURE_REFERENCES))
                .thenComposeAsync(chunks->{
                    active();
                    var starts=new TreeSet<>(CHUNK_ORDER);
                    for(var chunk:chunks.values())for(var refs:chunk.getAllReferences().values())
                        for(long ref:refs)if(!chunks.containsKey(new ChunkPos(ref)))starts.add(new ChunkPos(ref));
                    if(chunks.size()+starts.size()>81)
                        throw new WallFailure(FailureCode.CAPACITY,"root reference/starts union exceeds 81");
                    if(starts.isEmpty()) return CompletableFuture.completedFuture(vote(candidate,footprints,chunks,chunks));
                    return window.starts.get(List.copyOf(starts),missing->request(window,missing,ChunkStatus.STRUCTURE_STARTS))
                            .thenApplyAsync(extra->{
                                active();
                                var combined=new HashMap<>(chunks);
                                combined.putAll(extra);
                                return vote(candidate,footprints,chunks,combined);
                            },nativeOwner);
                },nativeOwner);
    }

    private static final Comparator<ChunkPos> CHUNK_ORDER =
            Comparator.comparingInt((ChunkPos p)->p.x).thenComparingInt(p->p.z);

    static List<ChunkPos> referencePositions(List<Bounds> footprints) {
        var positions=new TreeSet<>(CHUNK_ORDER);
        for(int i=0;i<footprints.size();i++){
            var b=footprints.get(i);
            // 同原点不同朝向的五个包围盒相同，只枚举一次区块。
            if(i>0&&b.equals(footprints.getFirst()))continue;
            for(int x=Math.floorDiv(b.minX()-1,16);x<=Math.floorDiv(b.maxXExclusive(),16);x++)
                for(int z=Math.floorDiv(b.minZ()-1,16);z<=Math.floorDiv(b.maxZExclusive(),16);z++)
                    positions.add(new ChunkPos(x,z));
        }
        if(positions.size()>81)throw new WallFailure(FailureCode.CAPACITY,"root reference footprint exceeds finite lease");
        return List.copyOf(positions);
    }

    private Map<ChunkPos,CompletableFuture<ChunkAccess>> request(Window window,List<ChunkPos> missing,ChunkStatus status) {
        var lease=track(new StructureLease(level,missing,status));
        window.leases.add(lease);
        return lease.chunks();
    }

    private Vote vote(Candidate candidate,List<Bounds> footprints,Map<ChunkPos,ChunkAccess> referenceChunks,
            Map<ChunkPos,ChunkAccess> prepared) {
        active();
        var facts=new TreeMap<String,Fact>();var missing=new TreeSet<String>();
        // Added start chunks are read only as named targets, never recursively as new reference owners.
        for(var chunk:referenceChunks.values()) {
            chunk.getAllStarts().forEach((structure,start)->addFact(structure,start,facts));
            chunk.getAllReferences().forEach((structure,refs)->{
                String id=id(structure);
                for(long ref:refs){
                    var source=prepared.get(new ChunkPos(ref));
                    var start=source==null?null:source.getStartForStructure(structure);
                    if(start==null||!start.isValid())missing.add(id+"@"+new ChunkPos(ref));
                    else addFact(structure,start,facts);
                }
            });
        }
        var allowed=new ArrayList<Integer>();var reasons=new ArrayList<String>();
        var firstFootprint=footprints.getFirst();
        String firstReason=reason(candidate,firstFootprint,facts,missing);
        for(int index=0;index<5;index++){
            var footprint=footprints.get(index);
            // 正式候选五个方向同原点，结构判定的探测盒相同；只有不同盒才重扫证据。
            String reason=firstFootprint.equals(footprint) ? firstReason : reason(candidate,footprint,facts,missing);
            if(reason.equals("CLEAR"))allowed.add(index);
            reasons.add(reason);
        }
        // One line per candidate, not one per axis. All five axes measure the same footprint - the axis
        // only chooses which one the wall is built along - so five identical lines per candidate said
        // nothing and buried the rest of the startup. The status is collapsed when it is the same on every
        // axis (the normal case) and spelled out per axis when it is not.
        String uniform = reasons.stream().allMatch(reasons.get(0)::equals) ? reasons.get(0) : reasons.toString();
        GreatWallMod.LOGGER.info("[GreatWall:root-structure] origin={} radius={} angle={} status={} axes={}/5 footprint={}",
                candidate.origin(), candidate.radiusIndex(), candidate.angleIndex(),
                uniform, allowed.size(), footprints.get(0));
        return new Vote(candidate.radiusIndex(),candidate.angleIndex(),allowed,reasons);
    }
    private static String reason(Candidate candidate,Bounds footprint,Map<String,Fact> facts,Set<String> missing) {
        if(!missing.isEmpty())return "UNRESOLVED_REFERENCE:"+missing;
        var surface=new Bounds(footprint.minX(),candidate.origin().y()-1,footprint.minZ(),
                footprint.maxXExclusive(),candidate.origin().y()+1,footprint.maxZExclusive());
        var hits=facts.values().stream().filter(f->intersects(surface,f.box())).map(f->f.id()+":"+f.box()).toList();
        return hits.isEmpty() ? "CLEAR" : "STRUCTURE_CONFLICT:"+hits;
    }

    private String id(Structure structure) {
        var id=ids.get(structure);
        if(id==null)throw new WallFailure(FailureCode.INVALID_RESOURCE,"root encountered an unregistered structure identity");
        return id;
    }
    private void addFact(Structure structure,StructureStart start,Map<String,Fact> facts) {
        if(start==null||!start.isValid())return;
        var nativeBox=start.getBoundingBox();
        var box=new Bounds(nativeBox.minX(),nativeBox.minY(),nativeBox.minZ(),nativeBox.maxX()+1,nativeBox.maxY()+1,nativeBox.maxZ()+1);
        String id=id(structure);facts.put(id+":"+start.getChunkPos()+":"+box,new Fact(id,box));
    }
    private StructureLease track(StructureLease lease) {leases.put(UUID.randomUUID(),lease);return lease;}
    private void release(StructureLease lease) {
        lease.close();
        lease.drained().whenComplete((v,e)->{leases.entrySet().removeIf(entry->entry.getValue()==lease);completeDrain();});
    }
    private void active(){if(state.containsKey("closed"))throw new WallFailure(FailureCode.CLOSED,"root structure screen closed");}
    private void completeDrain(){if(state.containsKey("closed")&&producers.getOrDefault("count",0)==0&&leases.isEmpty())drained.complete(null);}
    @Override public CompletableFuture<Void> drained(){return drained.copy();}
    @Override public void close(){
        if(state.putIfAbsent("closed",true)!=null)return;
        leases.values().forEach(this::release);completeDrain();
    }
}
