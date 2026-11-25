package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.Bounds;

/** 把收口矩形按行合并，只为尚未取证的真实列建立任务，避免33个候选反复展开重叠区域。 */
final class SupplementalColumns {
    private record Span(int from,int to) {}
    private SupplementalColumns() {}

    static List<WallFinePlanner.Target> missing(List<Bounds> boxes,FineHeightIndex existing) {
        var rows=new TreeMap<Integer,List<Span>>();
        for(var box:boxes)for(int z=box.minZ();z<box.maxZExclusive();z++)
            rows.computeIfAbsent(z,ignored->new ArrayList<>()).add(new Span(box.minX(),box.maxXExclusive()));
        var result=new ArrayList<WallFinePlanner.Target>();
        rows.forEach((z,spans)->{
            spans.sort(Comparator.comparingInt(Span::from).thenComparingInt(Span::to));
            int from=spans.getFirst().from(),to=spans.getFirst().to();
            for(int i=1;i<spans.size();i++) {
                var next=spans.get(i);
                if(next.from()<=to)to=Math.max(to,next.to());
                else {append(result,existing,z,from,to);from=next.from();to=next.to();}
            }
            append(result,existing,z,from,to);
        });
        return List.copyOf(result);
    }

    private static void append(List<WallFinePlanner.Target> output,FineHeightIndex index,int z,int from,int to) {
        for(int x=from;x<to;x++)if(!index.contains(x,z))output.add(new WallFinePlanner.Target(x,z,0));
    }
}
