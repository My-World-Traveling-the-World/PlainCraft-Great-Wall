package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;

/** 每轮只检查少量候选，不为长队列排序全部元素；并列仍按原迭代顺序。 */
final class BackfillCandidateSelection {
    private record Ranked(ChunkKey key,double distance,int ordinal) {}
    private static final Comparator<Ranked> BEST=Comparator.comparingDouble(Ranked::distance)
            .thenComparingInt(Ranked::ordinal);

    static List<ChunkKey> select(Collection<ChunkKey> pending,int limit,boolean hasPlayers,double cx,double cz) {
        if(limit<=0)return List.of();
        if(!hasPlayers) {
            var result=new ArrayList<ChunkKey>(Math.min(limit,pending.size()));
            for(var key:pending) { if(result.size()==limit)break;result.add(key); }
            return result;
        }
        // 小队列沿用稳定排序，避免堆节点分配反而增加开销。
        if(pending.size()<=limit) {
            var result=new ArrayList<>(pending);
            result.sort(Comparator.comparingDouble(key->distance(key,cx,cz)));
            return result;
        }
        var heap=new PriorityQueue<Ranked>(limit,BEST.reversed());
        int ordinal=0;
        for(var key:pending) {
            double distance=distance(key,cx,cz);
            int order=ordinal++;
            var worst=heap.peek();
            if(heap.size()==limit && Double.compare(distance,worst.distance())>=0)continue;
            if(heap.size()==limit)heap.poll();
            heap.add(new Ranked(key,distance,order));
        }
        var sorted=new ArrayList<>(heap);sorted.sort(BEST);
        return sorted.stream().map(Ranked::key).toList();
    }

    private static double distance(ChunkKey key,double cx,double cz) {
        // 保留原玩家平均中心及int位移口径，等价优化不改变多人选块策略。
        double dx=(key.x()<<4)+8-cx,dz=(key.z()<<4)+8-cz;
        return dx*dx+dz*dz;
    }
}
