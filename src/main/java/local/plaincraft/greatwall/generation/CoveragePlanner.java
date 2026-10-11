package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.component.ComponentTemplate;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.EvidenceCodec;

/** A canonical per-owner envelope includes every potential local template/retreat, not just accepted cells. */
public final class CoveragePlanner {
    private final java.util.Map<SiteKind,ComponentTemplate> templates=S1TemplateSet.create(0);
    public CoveragePlanner() {}
    public Coverage forChunk(ChunkKey chunk,PlanWindow plans,DimensionBounds bounds) {
        var stations=stations(plans);
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
        long x0=(long)chunk.x()*16,z0=(long)chunk.z()*16;
        for(var s:stations) {
            long x=Math.floorDiv(s.point().xQ8(),256),z=Math.floorDiv(s.point().zQ8(),256);
            if(x>=x0-196&&x<x0+16+196&&z>=z0-196&&z<z0+16+196) {
                min=Math.min(min,s.walkY()-35);max=Math.max(max,s.walkY()+8);
            }
        }
        for(var plan:plans.plans())for(var site:plan.sites()) {
            var b=site.bounds();
            if(b.minX()<x0+16+128&&b.maxXExclusive()>x0-128&&b.minZ()<z0+16+128&&b.maxZExclusive()>z0-128) {
                min=Math.min(min,site.frame().walkY()-35);max=Math.max(max,site.frame().walkY()+8);
            }
        }
        // An owner chunk can be admitted by realizationOwners because it falls inside a site's real
        // Rasterizer.worldBounds (the template footprint), which can be wider than the fixed 196/128
        // windows used above. That chunk then has no station or site.bounds() hit here, but it still
        // must be captured for its cell. Falling back to the whole dimension Y range is the safe
        // conservative coverage - it captures more pages, never fewer - rather than failing the
        // session on a radius-assumption mismatch.
        if(min==Integer.MAX_VALUE) {
            min=bounds.minY();max=bounds.maxYExclusive();
        } else {
            min=Math.max(min,bounds.minY());max=Math.min(max,bounds.maxYExclusive());
        }
        if(min>=max)throw new WallFailure(FailureCode.OUT_OF_BOUNDS,"empty vertical coverage");
        var bases=new ArrayList<Integer>();
        for(int base=Math.floorDiv(min,128)*128;base<max;base=Math.addExact(base,128))bases.add(base);
        if(bases.size()>64)throw new WallFailure(FailureCode.CAPACITY,"coverage exceeds 64 pages");
        var coverage=new Coverage(chunk,bases,new Bounds(Math.toIntExact(x0),min,Math.toIntExact(z0),
                Math.toIntExact(x0+16),max,Math.toIntExact(z0+16)),"0".repeat(64));
        return new Coverage(chunk,bases,coverage.readBounds(),DeterministicHash.sha256(EvidenceCodec.coverage(coverage)));
    }
    public List<CellKey> cellsForChunk(ChunkKey chunk,PlanWindow plans) {
        if(plans.plans().isEmpty())return List.of();
        var geometries=plans.plans().stream().map(SegmentPlan::geometry).toList();
        var prefixes=plans.plans().stream().map(SegmentPlan::prefix).toList();
        // A chunk that is not within the wall's projection window is a normal, expected case when
        // the player (or a bulk pre-generator like Chunky) walks far from the wall: that chunk has
        // no wall to build. Projection.nearest throws OUT_OF_BOUNDS for it, which must be treated
        // as "no cells here", never as a session failure - otherwise a single far-away chunk fails
        // the whole production pipeline and stops the server.
        final ProjectionResult projection;
        try {
            projection = Projection.nearest(chunk.x()*16+8,chunk.z()*16+8,new GeometryWindow(geometries,prefixes));
        } catch (WallFailure outOfWindow) {
            if (outOfWindow.code() == FailureCode.OUT_OF_BOUNDS) return List.of();
            throw outOfWindow;
        }
        if(Math.abs(projection.lateralQ8())>196L*256)return List.of();
        long min=Math.max(0,projection.globalSQ8()-192L*256),max=projection.globalSQ8()+192L*256;
        String recipe=plans.plans().getFirst().geometry().key().recipeHash();
        var result=new ArrayList<CellKey>();
        for(long cell=Math.floorDiv(min,64L*256);cell<=Math.floorDiv(max,64L*256);cell++) {
            var candidate=new CellKey(recipe,chunk.dimension(),cell);
            if(realizationOwners(candidate,plans).contains(chunk)) result.add(candidate);
        }
        return List.copyOf(result);
    }
    public List<ChunkKey> realizationOwners(CellKey cell,PlanWindow plans) {
        long from=Math.max(0,cell.arcCell()*64L*256-192L*256),to=(cell.arcCell()+1)*64L*256+192L*256;
        var owners=new TreeSet<ChunkKey>(java.util.Comparator.comparing(ChunkKey::dimension).thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
        for(var s:stations(plans))if(s.globalSQ8()>=from&&s.globalSQ8()<=to) {
            int x=FixedMath.floorBlock(s.point().xQ8()),z=FixedMath.floorBlock(s.point().zQ8());
            for(int cx=Math.floorDiv(x-16,16);cx<=Math.floorDiv(x+16,16);cx++)
                for(int cz=Math.floorDiv(z-16,16);cz<=Math.floorDiv(z+16,16);cz++)
                    owners.add(new ChunkKey(cell.dimension(),cx,cz));
        }
        for(var plan:plans.plans())for(var site:plan.sites())if(site.globalSQ8()>=from&&site.globalSQ8()<=to) {
            var template=templates.get(site.id().kind());
            if(template==null)throw new WallFailure(FailureCode.INVALID_RESOURCE,"unknown capture footprint: "+site.id());
            var provisional=new ComponentInput(site,List.of(),java.util.Map.of(),new DecaySample(DecayState.INTACT,0,20),
                    PaletteFamily.STONE,false,List.of(),List.of());
            var b=Rasterizer.worldBounds(site.frame(),template.localBounds(provisional));
            addOwners(owners,cell.dimension(),b);
        }
        if(owners.size()>256)throw new WallFailure(FailureCode.CAPACITY,"realization footprint exceeds 256 capture owners");
        return List.copyOf(owners);
    }
    private static void addOwners(TreeSet<ChunkKey> owners,String dimension,Bounds b){
        for(int cx=Math.floorDiv(b.minX()-1,16);cx<=Math.floorDiv(b.maxXExclusive(),16);cx++)
            for(int cz=Math.floorDiv(b.minZ()-1,16);cz<=Math.floorDiv(b.maxZExclusive(),16);cz++)
                owners.add(new ChunkKey(dimension,cx,cz));
    }
    private static List<Station> stations(PlanWindow plans) {
        var result=new TreeMap<Long,Station>();
        for(var plan:plans.plans())for(var station:plan.stations().stations()) {
            var previous=result.putIfAbsent(station.globalSQ8(),station);
            if(previous!=null&&(previous.walkY()!=station.walkY()||!previous.point().equals(station.point())))
                throw new WallFailure(FailureCode.RECIPE_MISMATCH,"inconsistent seam station");
        }
        return List.copyOf(result.values());
    }
}
