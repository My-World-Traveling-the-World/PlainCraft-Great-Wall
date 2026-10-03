package local.plaincraft.greatwall.compat.road;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.*;

/** Builds only immutable geometric obstacles; a journal ACK is not a physical hole proof. */
final class WallSnapshotBuilder {
    private record Source(String revision, List<Strip> strips, List<Passage> gates,
            Map<Long,String> gateByArcBucket, List<PlanLease<PlanWindow>> leases)
            implements WallObstacleProvider.WallSnapshotSource {
        Source {
            strips=List.copyOf(strips);gates=List.copyOf(gates);
            gateByArcBucket=Map.copyOf(gateByArcBucket);leases=List.copyOf(leases);
        }
    }
    private WallSnapshotBuilder() {}

    static WallObstacleProvider.WallSnapshotSource build(String recipe, String wallId, Request request,
            List<PlanWindow> windows, Map<GateId,RoadTypes.GateRecord> records,
            List<PlanLease<PlanWindow>> leases) {
        var plans=new TreeMap<Long,SegmentPlan>();
        for(var window:windows)for(var plan:window.plans()) {
            if(!recipe.equals(plan.geometry().key().recipeHash())
                    ||!request.dimension().equals(plan.geometry().key().dimension()))
                throw new IllegalArgumentException("wall snapshot contains a foreign recipe or dimension");
            var prior=plans.putIfAbsent(plan.geometry().key().index(),plan);
            if(prior!=null&&!prior.equals(plan))throw new IllegalStateException("inconsistent pinned wall windows");
        }
        var strips=new ArrayList<Strip>();
        for(var plan:plans.values()) {
            if(plan.prefix().terminal().filter(t->t.segment()<plan.geometry().key().index()).isPresent())continue;
            var ribbon=plan.stations().stations();
            if(ribbon.size()<2)continue;
            long end=ribbon.getLast().globalSQ8();
            if(plan.geometry().terminal().isPresent())
                end=Math.min(end,Math.addExact(plan.prefix().lengthBeforeQ8(),plan.geometry().terminal().orElseThrow().localSQ8()));
            for(long from=ribbon.getFirst().globalSQ8();from<end;) {
                long to=Math.min(end,Math.addExact(from,WallObstacleProvider.MAX_STRIP_ARC_Q8));
                var line=new ArrayList<Pos>();line.add(at(ribbon,from));
                for(var station:ribbon)if(station.globalSQ8()>from&&station.globalSQ8()<to)line.add(pos(station));
                line.add(at(ribbon,to));
                var strip=new Strip(recipe+"/"+plan.geometry().key().index()+"/"+from,line,from,to,5);
                if(WallObstacleGeometry.intersects(strip,request.bounds()))strips.add(strip);
                from=to;
            }
        }
        var gates=new ArrayList<Passage>();
        var arc=new TreeMap<Long,String>();
        var sorted=records.values().stream().sorted(Comparator.comparing((RoadTypes.GateRecord r)->r.contract().id().wallId())
                .thenComparing(r->r.contract().id().namespace()).thenComparingLong(r->r.contract().id().ordinal())).toList();
        for(var record:sorted) {
            if(!wallId.equals(record.contract().id().wallId()))
                throw new IllegalArgumentException("wall snapshot contains foreign gate history");
            if(record.state()!=RoadTypes.GateState.FROZEN&&record.state()!=RoadTypes.GateState.PENDING_CHUNKS
                    &&record.state()!=RoadTypes.GateState.APPLIED)continue;
            var gate=record.contract();var frame=gate.frame();
            if(gate.affected().stream().anyMatch(owner->!request.dimension().equals(owner.dimension())))
                throw new IllegalArgumentException("frozen gate contains a foreign dimension");
            int x=FixedMath.floorBlock(frame.center().xQ8()),z=FixedMath.floorBlock(frame.center().zQ8());
            if(x+32<request.bounds().minX()||x-32>=request.bounds().maxXExclusive()
                    ||z+32<request.bounds().minZ()||z-32>=request.bounds().maxZExclusive())continue;
            String id=RoadAdapterState.encode(gate.id());
            // Physical readiness will be supplied only after loaded targets are independently revalidated.
            gates.add(new Passage(id,new Pos(x,gate.groundY(),z),frame.txQ30(),frame.tzQ30(),
                    gate.apertureWidth(),gate.apertureHeight(),10,record.revision(),false));
            arc.put(Math.floorDiv(gate.globalSQ8(),128L*256),id);
        }
        String identity=recipe+"\n"+wallId+"\n"+request+"\n"+plans.values().stream().map(SegmentPlan::hash).toList()
                +"\n"+sorted+"\n"+gates;
        return new Source(DeterministicHash.sha256(identity.getBytes(StandardCharsets.UTF_8)),strips,gates,arc,leases);
    }

    private static Pos pos(Station station) {
        return new Pos(FixedMath.floorBlock(station.point().xQ8()),station.walkY(),FixedMath.floorBlock(station.point().zQ8()));
    }
    private static Pos at(List<Station> ribbon,long s) {
        if(s==ribbon.getFirst().globalSQ8())return pos(ribbon.getFirst());
        for(int i=1;i<ribbon.size();i++) {
            var a=ribbon.get(i-1);var b=ribbon.get(i);
            if(s<a.globalSQ8()||s>b.globalSQ8())continue;
            long offset=s-a.globalSQ8(),length=b.globalSQ8()-a.globalSQ8();
            long x=a.point().xQ8()+FixedMath.mulDiv(b.point().xQ8()-a.point().xQ8(),offset,length);
            long z=a.point().zQ8()+FixedMath.mulDiv(b.point().zQ8()-a.point().zQ8(),offset,length);
            int y=a.walkY()+Math.toIntExact(FixedMath.roundDiv((long)(b.walkY()-a.walkY())*offset,length));
            return new Pos(FixedMath.floorBlock(x),y,FixedMath.floorBlock(z));
        }
        throw new IllegalArgumentException("strip endpoint outside pinned station ribbon");
    }
}
