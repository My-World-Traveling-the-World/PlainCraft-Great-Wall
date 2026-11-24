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
import local.plaincraft.greatwall.component.S2TemplateSet;
import local.plaincraft.greatwall.component.ExactFootprintTemplate;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.EvidenceCodec;
import local.plaincraft.greatwall.planning.ImportedSamplingFootprint;

/** A canonical per-owner envelope includes every potential local template/retreat, not just accepted cells. */
public final class CoveragePlanner {
    private final java.util.Map<SiteKind,ComponentTemplate> templates;
    private final int version;
    private final boolean exact;
    private static final java.util.Comparator<ChunkKey> OWNER_ORDER = java.util.Comparator.comparing(ChunkKey::dimension)
            .thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z);
    public CoveragePlanner() { this(1); }
    public CoveragePlanner(int version) { this(version, false); }

    public CoveragePlanner(int version, boolean exact) {
        this.version=version;
        this.exact=exact;
        templates=imported()?S2TemplateSet.create(0,(i,p,l,w,s,h)->S1TemplateSet.material(i.family(),i.redSandstone(),p), version)
                :S1TemplateSet.create(0);
    }
    /** 新配方的跨段半单元必须都保留，不能沿用旧版择一策略。 */
    public boolean imported() { return WallRecipeRules.usesAuthoredTemplates(version); }
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
                min=Math.min(min,Math.min(site.frame().walkY()-35,b.minY()));
                max=Math.max(max,Math.max(site.frame().walkY()+8,b.maxYExclusive()));
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
        // Nothing exists past the terminal, so nothing there needs an owner. The plans already stop
        // their stations and sites at that arc (RealizationPlanner breaks on geometry.terminal()), but
        // this enumeration is driven by the projection rather than by the plans, so it kept asking for
        // cells across the whole route: a measured session compiled 559 owners for a wall whose
        // terminal sat 1750 blocks into a 8192 block route. One cell of margin keeps the end cap covered.
        long terminalLimit = Long.MAX_VALUE;
        for (var limited : plans.plans()) {
            var terminal = limited.geometry().terminal();
            if (terminal.isEmpty()) continue;
            long global = limited.prefix().lengthBeforeQ8() + terminal.get().localSQ8() + 64L * 256;
            terminalLimit = Math.min(terminalLimit, global);
        }
        if (projection.globalSQ8() > terminalLimit) return List.of();
        if (max > terminalLimit) max = terminalLimit;
        String recipe=plans.plans().getFirst().geometry().key().recipeHash();
        var result=new ArrayList<CellKey>();
        var ownerLookup = prepareOwners(plans);
        for(long cell=Math.floorDiv(min,64L*256);cell<=Math.floorDiv(max,64L*256);cell++) {
            var candidate=new CellKey(recipe,chunk.dimension(),cell);
            if(ownerLookup.forCell(candidate).contains(chunk)) result.add(candidate);
        }
        return List.copyOf(result);
    }
    public List<ChunkKey> realizationOwners(CellKey cell,PlanWindow plans) {
        return prepareOwners(plans).forCell(cell);
    }

    /** 同一个冻结窗口的多次 owner 查询共用站点顺序和模板足迹，仍逐单元判预算。 */
    public OwnerLookup prepareOwners(PlanWindow plans) {
        // 单段站表已校验弧长严格递增；只有多段才需要合并并检查接缝。
        var orderedStations = plans.plans().size() == 1
                ? plans.plans().getFirst().stations().stations() : stations(plans);
        var footprints = new ArrayList<SiteFootprint>();
        if (!exact) for(var plan:plans.plans())for(var site:plan.sites()) {
            var template=templates.get(site.id().kind());
            if(template==null)throw new WallFailure(FailureCode.INVALID_RESOURCE,"unknown capture footprint: "+site.id());
            var provisional=new ComponentInput(site,List.of(),java.util.Map.of(),new DecaySample(DecayState.INTACT,0,20),
                    PaletteFamily.STONE,false,List.of(),List.of());
            var b=template instanceof ExactFootprintTemplate exact?exact.worldBounds(provisional)
                    :Rasterizer.worldBounds(site.frame(),template.localBounds(provisional));
            footprints.add(new SiteFootprint(site.globalSQ8(), footprintReach(site,b), b));
        }
        var attachments = exact ? plans.plans().stream()
                .flatMap(plan -> ImportedSamplingFootprint.attachments(plan).stream()).toList()
                : List.<ImportedSamplingFootprint.Attachment>of();
        return new OwnerLookup(orderedStations, List.copyOf(footprints), attachments);
    }

    private record SiteFootprint(long arc, long reach, Bounds bounds) { }

    public static final class OwnerLookup {
        private final List<Station> stations;
        private final List<SiteFootprint> footprints;

        private final List<ImportedSamplingFootprint.Attachment> attachments;

        private OwnerLookup(List<Station> stations, List<SiteFootprint> footprints,
                List<ImportedSamplingFootprint.Attachment> attachments) {
            this.attachments = attachments;
            this.stations = stations;
            this.footprints = footprints;
        }

        public List<ChunkKey> forCell(CellKey cell) {
            long from=Math.max(0,cell.arcCell()*64L*256-192L*256),to=(cell.arcCell()+1)*64L*256+192L*256;
            var owners=new TreeSet<ChunkKey>(OWNER_ORDER);
            // 从第一个达到下界的站开始，端点仍是闭区间，防止接缝恰好在边界时漏取证。
            int lo = 0, hi = stations.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (stations.get(mid).globalSQ8() < from) lo = mid + 1; else hi = mid;
            }
            for (int i = lo; i < stations.size(); i++) {
                var s = stations.get(i);
                if (s.globalSQ8() > to) break;
                int x=FixedMath.floorBlock(s.point().xQ8()),z=FixedMath.floorBlock(s.point().zQ8());
                for(int cx=Math.floorDiv(x-16,16);cx<=Math.floorDiv(x+16,16);cx++)
                    for(int cz=Math.floorDiv(z-16,16);cz<=Math.floorDiv(z+16,16);cz++)
                        owners.add(new ChunkKey(cell.dimension(),cx,cz));
            }
            // 模板仍按原次序增加 owner：前面站点和模板占了多少额度，决定后面足迹能否加入。
            for (var footprint : footprints) {
                long arc = footprint.arc(), reach = footprint.reach();
                if(arc+reach<from-64L*256||arc-reach>to+64L*256)continue;
                var b = footprint.bounds();
                if(owners.size()+footprintOwnerCount(b)>256 && (arc<from || arc>to))continue;
                addOwners(owners,cell.dimension(),b);
            }
            // 收口按断点选候选，落点和读列可退到断点较远的区块。
            for (var a : attachments) if (a.arc() >= from && a.arc() <= to) addOwners(owners, cell.dimension(), a.bounds());
            if(owners.size()>256)throw new WallFailure(FailureCode.CAPACITY,"realization footprint exceeds 256 capture owners");
            return List.copyOf(owners);
        }
    }
    private static void addOwners(TreeSet<ChunkKey> owners,String dimension,Bounds b){
        for(int cx=Math.floorDiv(b.minX()-1,16);cx<=Math.floorDiv(b.maxXExclusive(),16);cx++)
            for(int cz=Math.floorDiv(b.minZ()-1,16);cz<=Math.floorDiv(b.maxZExclusive(),16);cz++)
                owners.add(new ChunkKey(dimension,cx,cz));
    }

    /** 整个矩形格数等于原独立足迹集合大小；不能用与已选 owner 的差集大小替代。 */
    private static long footprintOwnerCount(Bounds b){
        long columns = Math.floorDiv(b.maxXExclusive(),16) - (long)Math.floorDiv(b.minX()-1,16) + 1;
        long rows = Math.floorDiv(b.maxZExclusive(),16) - (long)Math.floorDiv(b.minZ()-1,16) + 1;
        return Math.max(0,columns) * Math.max(0,rows);
    }

    /**
     * {@return how far, in arc space, this footprint can reach from its site centre}
     *
     * <p>The footprint is the template's axis-aligned world envelope, so the largest distance from the
     * centre to any of its corners bounds the arc distance to any voxel it can write. Deliberately
     * conservative in the admitting direction: it can admit a site whose voxels never enter this cell,
     * never the other way around.
     */
    private static long footprintReach(SiteRecord site,Bounds b){
        long cx=FixedMath.floorBlock(site.frame().center().xQ8()),cz=FixedMath.floorBlock(site.frame().center().zQ8());
        long dx=Math.max(Math.abs(b.minX()-cx),Math.abs((long)b.maxXExclusive()-cx));
        long dz=Math.max(Math.abs(b.minZ()-cz),Math.abs((long)b.maxZExclusive()-cz));
        return Math.max(dx,dz)*256L;
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
