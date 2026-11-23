/* 文件职责：核对区块取证共享的边界、失败影响和真实起点几何下的工作量。 */
package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import local.plaincraft.greatwall.lifecycle.RootSearch;
import local.plaincraft.greatwall.lifecycle.SpawnDomain;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootStructureRequestsTest {
    @Test void sharedChunkDoesNotWaitForAnotherCandidatesSlowChunk() {
        var source=new Fake();
        var first=new ChunkPos(-3,-2);
        var shared=new ChunkPos(-2,-2);
        var last=new ChunkPos(-1,-2);
        var references=new RootStructureRequests<Integer>();
        var older=references.get(List.of(first,shared),source::request);
        var newer=references.get(List.of(shared,last),source::request);
        assertEquals(List.of(first,shared,last),source.calls,"同阶段的共享区块只租一次");
        source.pending.get(shared).complete(20);
        source.pending.get(last).complete(30);
        assertEquals(Map.of(shared,20,last,30),newer.resultNow(),"共享结果不能带入另一个候选独有的坐标");
        assertFalse(older.isDone());
        source.pending.get(first).completeExceptionally(new IllegalStateException("仅首个候选需要的区块坏了"));
        assertThrows(CompletionException.class,older::join);
        var starts=new Fake();
        var startView=new RootStructureRequests<Integer>().get(List.of(shared),starts::request);
        assertEquals(List.of(shared),starts.calls,"另一个区块状态仍需单独取证");
        assertFalse(startView.isDone());
    }

    @Test void innerRingReducesNativeRequestsAndOuterRingDoesNotAddAny() {
        // 和生产一样带真实出生区块授权，离线只替换发给区块加载器的端口。
        var domain=new SpawnDomain();
        domain.authorize(new ChunkKey("minecraft:overworld",-60,-65),11);
        var search=new RootSearch(new Point3i(-950,64,-1031),new DimensionBounds(-64,320,30_000_000),
                domain.snapshot(),(p,c)->true,true);
        int near=checkRing(search,0,true);
        int far=checkRing(search,RootSearch.RADIUS_COUNT-1,false);
        assertTrue(near>0,"真实近环候选要能合并原本重复的区块请求");
        assertEquals(0,far,"相互不重叠时不增加区块请求");
    }

    private static int checkRing(RootSearch search,int ring,boolean overlap) {
        var requests=new RootStructureRequests<Integer>();
        var all=new HashSet<ChunkPos>();
        var sent=new ArrayList<ChunkPos>();
        int visits=0;
        for(int angle=0;angle<8;angle++){
            var origin=search.ringPoint(ring,angle);
            var footprint=NativeRootStructureScreen.footprint(new Axis(origin,1<<30,0));
            var refs=NativeRootStructureScreen.referencePositions(java.util.Collections.nCopies(5,footprint));
            visits+=refs.size();
            all.addAll(refs);
            var result=requests.get(refs,missing->{
                sent.addAll(missing);
                var ready=new HashMap<ChunkPos,CompletableFuture<Integer>>();
                for(var key:missing)ready.put(key,CompletableFuture.completedFuture(key.x));
                return ready;
            }).join();
            assertEquals(new HashSet<>(refs),result.keySet());
        }
        assertEquals(all.size(),sent.size());
        System.out.printf("ROOT_STRUCTURE_SAMPLE ring=%d overlap=%s nativeRequests=%d->%d%n",ring,overlap,visits,sent.size());
        return visits-sent.size();
    }

    private static final class Fake {
        final Map<ChunkPos,CompletableFuture<Integer>> pending=new HashMap<>();
        final List<ChunkPos> calls=new ArrayList<>();

        Map<ChunkPos,CompletableFuture<Integer>> request(List<ChunkPos> missing){
            var result=new HashMap<ChunkPos,CompletableFuture<Integer>>();
            for(var key:missing){
                calls.add(key);
                var slot=new CompletableFuture<Integer>();
                pending.put(key,slot);
                result.put(key,slot);
            }
            return result;
        }
    }
}
