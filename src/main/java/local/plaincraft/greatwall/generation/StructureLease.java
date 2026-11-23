package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    // 保留每个区块独立的结果，重叠候选无需等待本租约里与它无关的坐标。
    private final Map<ChunkPos,CompletableFuture<ChunkAccess>> chunks;
    private final CompletableFuture<Void> drained=new CompletableFuture<>();

    public StructureLease(ServerLevel level,List<ChunkPos> positions,ChunkStatus status) {
        if(status!=ChunkStatus.STRUCTURE_STARTS&&status!=ChunkStatus.STRUCTURE_REFERENCES)
            throw new IllegalArgumentException("root lease cannot request terrain/features/light");
        if(positions.isEmpty()||positions.size()>81)throw new IllegalArgumentException("finite structure lease required");
        this.level=level; this.positions=List.copyOf(positions); this.status=status;
        var outputs = new HashMap<ChunkPos,CompletableFuture<ChunkAccess>>();
        for (var pos : this.positions) outputs.put(pos,new CompletableFuture<>());
        chunks=Map.copyOf(outputs);
        new StageTrace(UUID.randomUUID()).assertOutsideLock();
        executor().execute(()->{
            if(state.containsKey("closed")){cleanup();return;}
            var inFlight=new ArrayList<CompletableFuture<?>>(this.positions.size());
            try {
                var access=(TerrainChunkAccess)level.getChunkSource();
                // 部分加票后异常也要回收；部分请求已发出时仍等真正的生产者退出。
                state.put("installed",true);
                for(var p:this.positions)access.greatwall$distanceManager().addTicket(TYPE,p,ChunkLevel.byStatus(status),id);
                for(var p:this.positions){
                    var target=chunks.get(p);
                    inFlight.add(access.greatwall$request(p.x,p.z,status,true).whenComplete((result,error)->{
                        if(error!=null)target.completeExceptionally(error);
                        else {
                            var chunk=result.orElse(null);
                            if(chunk==null)target.completeExceptionally(new IllegalStateException("长城起点结构区块不可用: "+p));
                            else target.complete(chunk);
                        }
                    }));
                }
            }catch(Throwable error){
                state.put("closed",true);
                failUnresolved(error);
            }
            CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
                state.put("producer-done",true);
                if(state.containsKey("closed"))executor().execute(this::cleanup);
            });
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
    Map<ChunkPos,CompletableFuture<ChunkAccess>> chunks(){return chunks;}

    private void failUnresolved(Throwable error) {
        chunks.values().forEach(result->result.completeExceptionally(error));
    }
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
        failUnresolved(new IllegalStateException("长城起点结构租约已关闭"));
        executor().execute(()->{if(state.containsKey("producer-done")||!state.containsKey("installed"))cleanup();});
    }
}
