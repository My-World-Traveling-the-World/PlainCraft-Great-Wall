package local.plaincraft.greatwall.generation;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;

/** 固定队列快照的精确近邻原型；尚不接生产，队列变更时必须重建，不能把旧序号冒充实时迭代顺序。 */
final class BackfillCandidateIndex {
    record Query(List<ChunkKey> keys,int examined) {}
    private record Entry(ChunkKey key,int x,int z,int ordinal) {}
    private record Ranked(Entry entry,double distance) {}
    private record Node(int from,int to,int minX,int maxX,int minZ,int maxZ,Node left,Node right) {}
    private static final Comparator<Ranked> BEST=Comparator.comparingDouble(Ranked::distance)
            .thenComparingInt(r->r.entry().ordinal());
    private final List<ChunkKey> snapshot;
    private final Entry[] entries;
    private final Node root;

    BackfillCandidateIndex(Collection<ChunkKey> pending) {
        snapshot=List.copyOf(pending);
        entries=new Entry[snapshot.size()];
        for(int i=0;i<entries.length;i++) {
            var key=snapshot.get(i);
            // 与正式入口相同的int回绕口径；不能用long坐标替换后漏掉旧排序可见的点。
            entries[i]=new Entry(key,(key.x()<<4)+8,(key.z()<<4)+8,i);
        }
        root=build(0,entries.length);
    }

    Query select(int limit,boolean hasPlayers,double cx,double cz) {
        if(limit<=0||entries.length==0)return new Query(List.of(),0);
        if(!hasPlayers)return new Query(snapshot.subList(0,Math.min(limit,snapshot.size())),0);
        if(!Double.isFinite(cx)||!Double.isFinite(cz)) {
            // 非有限输入没有可靠几何下界，沿正式比较器完整求值，不能让剪枝改变NaN排序。
            return new Query(BackfillCandidateSelection.select(snapshot,limit,true,cx,cz),entries.length);
        }
        var search=new Search(Math.min(limit,entries.length),cx,cz);
        search.visit(root);
        var sorted=new ArrayList<>(search.heap);sorted.sort(BEST);
        return new Query(sorted.stream().map(r->r.entry().key()).toList(),search.examined);
    }

    private Node build(int from,int to) {
        if(from==to)return null;
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for(int i=from;i<to;i++) {
            var e=entries[i];
            minX=Math.min(minX,e.x());maxX=Math.max(maxX,e.x());
            minZ=Math.min(minZ,e.z());maxZ=Math.max(maxZ,e.z());
        }
        if(to-from<=32)return new Node(from,to,minX,maxX,minZ,maxZ,null,null);
        boolean splitX=(long)maxX-minX>=(long)maxZ-minZ;
        Arrays.sort(entries,from,to,Comparator.comparingInt((Entry e)->splitX?e.x():e.z())
                .thenComparingInt(Entry::ordinal));
        int mid=from+(to-from)/2;
        return new Node(from,to,minX,maxX,minZ,maxZ,build(from,mid),build(mid,to));
    }

    private final class Search {
        final int limit;
        final double cx,cz;
        final PriorityQueue<Ranked> heap;
        int examined;
        Search(int limit,double cx,double cz) {
            this.limit=limit;this.cx=cx;this.cz=cz;
            heap=new PriorityQueue<>(limit,BEST.reversed());
        }
        double lower(Node node) {
            double x=Math.max(node.minX(),Math.min(node.maxX(),cx));
            double z=Math.max(node.minZ(),Math.min(node.maxZ(),cz));
            double dx=x-cx,dz=z-cz;
            return dx*dx+dz*dz;
        }
        void visit(Node node) {
            // 等距边界不能剪掉，另一子树可能有更早的原始序号。
            if(heap.size()==limit&&Double.compare(lower(node),heap.peek().distance())>0)return;
            if(node.left()==null) {
                for(int i=node.from();i<node.to();i++) {
                    var e=entries[i];double dx=e.x()-cx,dz=e.z()-cz;
                    double distance=dx*dx+dz*dz;examined++;
                    if(heap.size()==limit) {
                        int comparison=Double.compare(distance,heap.peek().distance());
                        if(comparison>0||(comparison==0&&e.ordinal()>=heap.peek().entry().ordinal()))continue;
                    }
                    if(heap.size()==limit)heap.poll();
                    heap.add(new Ranked(e,distance));
                }
            } else if(Double.compare(lower(node.left()),lower(node.right()))<=0) {
                visit(node.left());visit(node.right());
            } else {
                visit(node.right());visit(node.left());
            }
        }
    }
}
