package local.plaincraft.greatwall.generation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.mixin.ChunkMapAccess;
import local.plaincraft.greatwall.mixin.TerrainChunkAccess;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Pre-recipe native structures only. Closing a view cannot claim an entered producer has drained. */
public final class StructureLease implements AutoCloseable {
    private static final TicketType<String> TYPE=TicketType.create("pgw_root_structures",Comparator.<String>naturalOrder());
    private final String id=UUID.randomUUID().toString();
    private final ServerLevel level;
    private final List<ChunkPos> positions;
    private final ChunkStatus status;
    private final ConcurrentHashMap<String,Boolean> state=new ConcurrentHashMap<>();
    private final CompletableFuture<Map<ChunkPos,ChunkAccess>> ready=new CompletableFuture<>();
    private final CompletableFuture<Void> drained=new CompletableFuture<>();

    public StructureLease(ServerLevel level,List<ChunkPos> positions,ChunkStatus status) {
        if(status!=ChunkStatus.STRUCTURE_STARTS&&status!=ChunkStatus.STRUCTURE_REFERENCES)
            throw new IllegalArgumentException("root lease cannot request terrain/features/light");
        if(positions.isEmpty()||positions.size()>81)throw new IllegalArgumentException("finite structure lease required");
        this.level=level; this.positions=positions.stream().distinct().toList(); this.status=status;
        new StageTrace(UUID.randomUUID()).assertOutsideLock();
        executor().execute(()->{
            if(state.containsKey("closed")){ready.completeExceptionally(new IllegalStateException("root lease closed"));cleanup();return;}
            try {
                var access=(TerrainChunkAccess)level.getChunkSource();
                for(var p:this.positions)access.greatwall$distanceManager().addTicket(TYPE,p,ChunkLevel.byStatus(status),id);
                state.put("installed",true);
                var futures=this.positions.stream().map(p->access.greatwall$request(p.x,p.z,status,true)
                        .thenApply(result->Map.entry(p,result.orElseThrow(()->new IllegalStateException("root structures unavailable: "+p)))))
                        .toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
                    state.put("producer-done",true);
                    if(error!=null)ready.completeExceptionally(error);
                    else if(state.containsKey("closed"))ready.completeExceptionally(new IllegalStateException("root lease closed"));
                    else {
                        var map=new java.util.HashMap<ChunkPos,ChunkAccess>();
                        futures.forEach(f->{var entry=f.resultNow();map.put(entry.getKey(),entry.getValue());});
                        ready.complete(Map.copyOf(map));
                    }
                    if(state.containsKey("closed"))executor().execute(this::cleanup);
                });
            }catch(Throwable error){
                // A throw mid-addTicket (the loop above can add some positions before one
                // fails) must not leak those tickets. cleanup() only removes tickets when the
                // "installed" marker is present, and that marker is set AFTER the whole loop,
                // so on this path it is missing and cleanup() would skip the removal. Mark the
                // lease as installed here so cleanup() removes every position; removeTicket is
                // a no-op for a position whose ticket was never added.
                state.put("installed",true);
                state.put("producer-done",true);ready.completeExceptionally(error);state.put("closed",true);cleanup();
            }
        });
    }
    private java.util.concurrent.Executor executor() {
        // The global server queue, not the world-local chunk executor. The road side waits in
        // managedBlock, which drains the global queue and - crucially - raises the in-managed-
        // block depth so pollTask skips shouldRun(); a task posted to the world-local chunk
        // queue is never seen by managedBlock, so the wall would deadlock waiting on its own
        // chunk request. The global queue is also the thread-safe place for addTicket/request.
        return level.getServer();
    }
    public CompletableFuture<Map<ChunkPos,ChunkAccess>> ready(){return ready.copy();}
    public CompletableFuture<Void> drained(){return drained.copy();}
    private void cleanup() {
        if(state.putIfAbsent("cleaned",true)!=null)return;
        if(state.remove("installed")!=null){
            var access=(TerrainChunkAccess)level.getChunkSource();
            for(var p:positions)access.greatwall$distanceManager().removeTicket(TYPE,p,ChunkLevel.byStatus(status),id);
        }
        drained.complete(null);
    }
    @Override public void close() {
        if(state.putIfAbsent("closed",true)!=null)return;
        ready.completeExceptionally(new IllegalStateException("root lease closed"));
        executor().execute(()->{if(state.containsKey("producer-done")||!state.containsKey("installed"))cleanup();});
    }
}
