package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import local.plaincraft.greatwall.decay.SupportGraph;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;

/** Internal composition only: all children must already belong to the same complete site. */
final class ComponentComposer {
    private ComponentComposer() {}
    static ComponentVoxels merge(ComponentInput input,List<ComponentVoxels> children) {
        if(children.isEmpty()) throw new IllegalArgumentException("empty composition");
        long entries=0;for(var child:children)entries=Math.addExact(entries,child.voxels().size());
        CompiledAllocation.charge(Math.multiplyExact(entries,128));
        Bounds b=children.getFirst().bounds();
        var voxels=new HashMap<Point3i,VoxelIntent>(); var entities=new ArrayList<BlockEntityIntent>();
        var slots=new ArrayList<SpawnSlot>(); var routes=new ArrayList<List<Point3i>>(); var roots=new HashSet<Point3i>();
        for(var c:children) {
            if(!c.id().equals(input.site().id())) throw new IllegalArgumentException("foreign child identity");
            b=new Bounds(Math.min(b.minX(),c.bounds().minX()),Math.min(b.minY(),c.bounds().minY()),Math.min(b.minZ(),c.bounds().minZ()),
                    Math.max(b.maxXExclusive(),c.bounds().maxXExclusive()),Math.max(b.maxYExclusive(),c.bounds().maxYExclusive()),
                    Math.max(b.maxZExclusive(),c.bounds().maxZExclusive()));
            if(c.voxels().isEmpty()) return ComponentAssembly.empty(input.site().id(),b);
            for(var v:c.voxels()) voxels.merge(v.pos(),v,(a,d)->{
                if(a.kind()==IntentKind.OWNED_VOID && a.required() || d.kind()==IntentKind.OWNED_VOID && d.required())
                    return a.kind()==IntentKind.OWNED_VOID && a.required()?a:d;
                if(a.walkable()!=d.walkable()) return a.walkable()?a:d;
                if(a.required()!=d.required()) return a.required()?a:d;
                if(a.priority()!=d.priority()) return a.priority()>d.priority()?a:d;
                return a.state().id().compareTo(d.state().id())<=0?a:d;
            });
            entities.addAll(c.blockEntities()); slots.addAll(c.spawnSlots()); routes.addAll(c.requiredRoutes());
        }
        for(var v:voxels.values()) {
            var support=input.supports().get(new Point3i(v.pos().x(),input.site().frame().walkY(),v.pos().z()));
            if(support!=null && v.kind()==IntentKind.SOLID && v.pos().y()<=support.groundTopY()) roots.add(v.pos());
        }
        var sorted=voxels.values().stream().sorted(java.util.Comparator.comparing(VoxelIntent::pos,ComponentAssembly.ORDER)).toList();
        var result=ComponentAssembly.state(ComponentAssembly.hashed(input.site().id(),b,sorted,entities,slots,routes),input.decay().state());
        return new SupportGraph().validate(result,roots);
    }

    static ComponentInput frame(ComponentInput input,Frame frame,long s) {
        var old=input.site();
        var site=new SiteRecord(old.id(),s,frame,old.bounds(),old.state(),old.absorbedBy(),old.reason());
        return new ComponentInput(site,input.stations(),input.supports(),input.decay(),input.family(),
                input.redSandstone(),input.waters(),input.materials());
    }
}
