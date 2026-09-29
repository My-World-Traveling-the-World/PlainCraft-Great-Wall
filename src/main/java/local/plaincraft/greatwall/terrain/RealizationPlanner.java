package local.plaincraft.greatwall.terrain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Whole-cell decisions use only immutable contracts/T0, never the owner currently asking for them. */
public final class RealizationPlanner {
    public record Input(CellKey key,PlanWindow plans,Map<TerrainTile.Key,TerrainTile> tiles,
            Map<Point3i,SurfaceProbe.ColumnView> columns,List<Bounds> protectedBounds,Map<GateId,Integer> frozenGateY,
            long gateRevision,String protectionHash) {
        public Input {
            Objects.requireNonNull(key);Objects.requireNonNull(plans);
            tiles=Map.copyOf(tiles);columns=Map.copyOf(columns);protectedBounds=List.copyOf(protectedBounds);frozenGateY=Map.copyOf(frozenGateY);
            if(gateRevision<0)throw new IllegalArgumentException("negative revision");
            local.plaincraft.greatwall.model.WallTypes.requireHash(protectionHash);
        }
    }
    private final SurfaceProbe probe;
    private final Map<SiteKind,ComponentTemplate> templates;
    private final WorldRecipe recipe;
    public RealizationPlanner(SurfaceProbe probe,Map<SiteKind,ComponentTemplate> templates){this(null,probe,templates);}
    public RealizationPlanner(WorldRecipe recipe,SurfaceProbe probe,Map<SiteKind,ComponentTemplate> templates){
        this.recipe=recipe;this.probe=Objects.requireNonNull(probe);this.templates=Map.copyOf(templates);
    }
    public RealizationCell resolve(Input input) {
        if(input.key().arcCell()<0)throw new IllegalArgumentException("negative arc cell");
        if(recipe!=null&&(!recipe.recipeHash().equals(input.key().recipeHash())||!recipe.dimension().equals(input.key().dimension())))
            throw new WallFailure(FailureCode.RECIPE_MISMATCH,"foreign realization request");
        var stations=stations(input.plans());var accepted=new ArrayList<ComponentVoxels>();var breaks=new ArrayList<BreakInterval>();
        long from=Math.multiplyExact(input.key().arcCell(),64L*256),to=Math.addExact(from,64L*256);
        String t0=hash(input.tiles().values().stream().map(t->t.key().identity()+":"+t.hash()).sorted().toList().toString());
        if(stations.size()<2||from>=stations.getLast().globalSQ8())return result(input,breaks,accepted,t0);
        if(from<stations.getFirst().globalSQ8())
            throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN,"cell needs complete station ribbon");
        to=Math.min(to,stations.getLast().globalSQ8());
        var bounds=recipe!=null?recipe.bounds():new DimensionBounds(
                input.tiles().keySet().stream().mapToInt(TerrainTile.Key::minY).min().orElseThrow(),
                input.tiles().keySet().stream().mapToInt(k->k.maxY()+1).max().orElseThrow(),29_999_000);
        String wall=recipe!=null?recipe.wallId():input.plans().plans().stream().flatMap(p->p.sites().stream()).findFirst()
                .map(s->s.id().wallId()).orElse(input.key().recipeHash());
        var field=new DecayField(recipe==null?0:recipe.seed());
        var sites=sites(input.plans());var maintained=sites.stream().filter(s->s.state()!=SiteState.SUPPRESSED&&s.state()!=SiteState.ABSORBED)
                .map(SiteRecord::globalSQ8).toList();
        var waters=waters(input.plans());long terminal=Long.MAX_VALUE;BreakReason terminalReason=BreakReason.RIVER_WIDE;
        for(var p:input.plans().plans()){
            breaks.addAll(p.stations().breaks());
            if(p.geometry().terminal().isPresent()) {
                var end=p.geometry().terminal().get();
                long s=p.prefix().lengthBeforeQ8()+end.localSQ8();
                if(s<terminal){terminal=s;terminalReason=end.kind()==TerminalKind.WORLD_LIMIT?BreakReason.WORLD_LIMIT:BreakReason.RIVER_WIDE;}
            }
        }
        if(terminal!=Long.MAX_VALUE)breaks.add(new BreakInterval(new ArcInterval(terminal,Long.MAX_VALUE),terminalReason,BreakScope.PLANNED));
        var supported=new HashMap<Point3i,ProbeResult>();
        var columnEntries=input.columns().entrySet().stream().sorted(Map.Entry.<Point3i,SurfaceProbe.ColumnView>comparingByKey(
                Comparator.comparingInt(Point3i::z).thenComparingInt(Point3i::x).thenComparingInt(Point3i::y))).toList();
        for(var e:columnEntries) {
            if(e.getKey().y()!=0)throw new IllegalArgumentException("column keys use y=0, independent of contract W");
            var contract=WallSection.contract(stations,e.getKey().x(),e.getKey().z());
            if(contract.globalSQ8()<Math.max(0,from-160L*256)||contract.globalSQ8()>=to+160L*256)continue;
            var value=probe.probe(protectedView(e.getValue(),e.getKey(),input.protectedBounds()),contract.walkY(),bounds);
            supported.put(new Point3i(e.getKey().x(),contract.walkY(),e.getKey().z()),value);
            if(contract.lateralCell()>=-5&&contract.lateralCell()<5&&value.kind()!=ProbeKind.SUPPORTED&&value.kind()!=ProbeKind.WATER){
                long bin=Math.floorDiv(contract.globalSQ8(),256)*256;
                breaks.add(new BreakInterval(new ArcInterval(Math.max(0,bin-8L*256),bin+9L*256),reason(value.kind()),BreakScope.REALIZED));
            }
        }
        var occupied=new ArrayList<ArcInterval>();
        for(var site:sites) {
            if(site.globalSQ8()<Math.max(0,from-64L*256)||site.globalSQ8()>=to+64L*256||site.state()==SiteState.SUPPRESSED||site.state()==SiteState.ABSORBED)continue;
            long extent=switch(site.id().kind()){case FORT->24L*256;case GATE->30L*256;case ENEMY_TOWER->6L*256;case BEACON->5L*256;default->0;};
            if(extent==0)continue;
            if(site.globalSQ8()>=terminal)continue;
            // Hydrology owns crossings. Site ordinals remain unchanged even when their body is suppressed.
            long siteFrom=Math.max(0,site.globalSQ8()-extent),siteTo=site.globalSQ8()+extent;
            if(waters.stream().anyMatch(w->w.classification()!=WaterClass.DRY&&w.crossing().fromQ8()-8L*256<siteTo
                    &&w.crossing().toQ8()+8L*256>siteFrom))continue;
            occupied.add(new ArcInterval(Math.max(0,site.globalSQ8()-extent),site.globalSQ8()+extent));
            // All cells can vote on a site's full footprint; only its center cell publishes it.
            var candidate=componentInput(input,site,stations,field.sample(site.globalSQ8(),PaletteFamily.STONE,maintained),List.of(),bounds,supported);
            ComponentVoxels component=compile(site.id().kind(),candidate,input);
            if(component==null||component.voxels().isEmpty()){
                if(site.id().kind()==SiteKind.FORT&&site.id().ordinal()==0)throw new WallFailure(FailureCode.ROOT_NOT_FOUND,"fort0 realization failed");
                breaks.add(new BreakInterval(new ArcInterval(Math.max(0,site.globalSQ8()-extent-8L*256),site.globalSQ8()+extent+8L*256),BreakReason.CONFLICT,BreakScope.REALIZED));
            }else if(site.globalSQ8()>=from&&site.globalSQ8()<to&&site.globalSQ8()<terminal)accepted.add(component);
        }
        for(var water:waters) {
            if(water.classification()!=WaterClass.NARROW)continue;
            long center=(water.crossing().fromQ8()+water.crossing().toQ8())/2;
            if(center<Math.max(0,from-128L*256)||center>=to+128L*256)continue;
            occupied.add(new ArcInterval(Math.max(0,water.crossing().fromQ8()-8L*256),water.crossing().toQ8()+8L*256));
            var site=site(wall,SiteKind.WATER_GATE,water.crossing().fromQ8(),center,stations);
            var candidate=componentInput(input,site,stations,field.sample(center,PaletteFamily.STONE,maintained),List.of(water),bounds,supported);
            var component=compile(SiteKind.WATER_GATE,candidate,input);
            if(component==null||component.voxels().isEmpty())breaks.add(new BreakInterval(water.crossing(),BreakReason.NO_SUPPORT,BreakScope.REALIZED));
            else if(center>=from&&center<to&&center<terminal)accepted.add(component);
        }
        var merged=merge(breaks);var excluded=new ArrayList<>(occupied);merged.forEach(b->excluded.add(b.range()));
        var walkBreaks=new ArrayList<>(merged);
        long decayFrom=Math.max(stations.getFirst().globalSQ8(),Math.max(0,from-160L*256));
        long decayTo=Math.min(stations.getLast().globalSQ8(),Math.min(terminal,to+160L*256));
        for(long s=Math.floorDiv(decayFrom,256)*256;s<decayTo;s+=256){
            var d=field.sample(s,PaletteFamily.STONE,maintained);
            if(d.state()==DecayState.RUIN||d.state()==DecayState.BURIED)
                walkBreaks.add(new BreakInterval(new ArcInterval(s,Math.min(s+256,decayTo)),BreakReason.BURIED,BreakScope.REALIZED));
        }
        var walkMerged=merge(walkBreaks);
        var capExclusions=new ArrayList<ArcInterval>();
        for(var gap:walkMerged){
            addCap(input,wall,gap.range().fromQ8(),false,from,to,terminal,stations,field,maintained,bounds,supported,accepted,capExclusions);
            if(gap.range().toQ8()!=Long.MAX_VALUE&&gap.range().toQ8()<terminal&&gap.range().toQ8()<decayTo)
                addCap(input,wall,gap.range().toQ8(),true,from,to,terminal,stations,field,maintained,bounds,supported,accepted,capExclusions);
        }
        excluded.addAll(capExclusions);
        var buildable=subtract(new ArcInterval(from,to),excluded);
        if(!buildable.isEmpty()){
            var site=site(wall,SiteKind.WALL,input.key().arcCell(),from+32L*256,stations);
            var candidate=new ComponentInput(site,stations,supported,field.sample(site.globalSQ8(),PaletteFamily.STONE,maintained),
                    PaletteFamily.STONE,false,List.of(),List.of());
            var template=templates.get(SiteKind.WALL);
            if(!(template instanceof WallSection section))throw new IllegalArgumentException("WALL template must support build intervals");
            var walkExcluded=new ArrayList<>(excluded);walkMerged.forEach(b->walkExcluded.add(b.range()));
            var component=section.compileIntervals(candidate,buildable,subtract(new ArcInterval(from,to),walkExcluded),
                    s->field.sample(s,PaletteFamily.STONE,maintained));
            if(!component.voxels().isEmpty())accepted.add(component);
        }
        return result(input,walkMerged,accepted,t0);
    }
    private void addCap(Input input,String wall,long s,boolean reverse,long from,long to,long terminal,List<Station> stations,
            DecayField field,List<Long> maintained,DimensionBounds bounds,Map<Point3i,ProbeResult> known,List<ComponentVoxels> components,
            List<ArcInterval> exclusions){
        if(s<=0||s<Math.max(0,from-160L*256)||s>to+160L*256||s>stations.getLast().globalSQ8())return;
        var site=site(wall,SiteKind.END_CAP,Math.addExact(Math.multiplyExact(s,2),reverse?1:0),s,stations);
        if(reverse){var f=site.frame();site=new SiteRecord(site.id(),s,new Frame(f.center(),-f.txQ30(),-f.tzQ30(),f.walkY(),!f.mirror()),
                site.bounds(),site.state(),site.absorbedBy(),site.reason());}
        if(!(templates.get(SiteKind.END_CAP) instanceof EndCap template))throw new IllegalArgumentException("missing EndCap");
        for(var attempt:EndCap.candidates(site,stations)){
            var candidate=componentInput(input,attempt,stations,new DecaySample(DecayState.INTACT,0,20),List.of(),bounds,known);
            ComponentVoxels cap;
            try{cap=template.compileAt(candidate);}
            catch(WallFailure failure){if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT)throw failure;continue;}
            if(cap.voxels().isEmpty())continue;
            long actual=attempt.globalSQ8();
            exclusions.add(reverse?new ArcInterval(s,actual+30L*256):new ArcInterval(Math.max(0,actual-30L*256),s));
            if(s>=from&&(s<to||s==to&&s==stations.getLast().globalSQ8()))components.add(cap);
            return;
        }
        throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"no proven ground exit for break at "+s);
    }
    private ComponentVoxels compile(SiteKind kind,ComponentInput candidate,Input input) {
        var template=templates.get(kind);if(template==null)throw new IllegalArgumentException("missing template "+kind);
        try{
            if(template instanceof Gate gate){
                Integer ground=input.frozenGateY().get(new GateId(candidate.site().id().wallId(),"planned",candidate.site().id().ordinal()));
                return ground==null?gate.compile(candidate):gate.compileAtGround(candidate,ground);
            }
            return template.compile(candidate);
        }catch(WallFailure failure){if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT)throw failure;return null;}
    }
    private ComponentInput componentInput(Input input,SiteRecord site,List<Station> stations,DecaySample decay,List<WaterEvidence> waters,
            DimensionBounds bounds,Map<Point3i,ProbeResult> known) {
        var supports=new HashMap<Point3i,ProbeResult>(known);
        var provisional=new ComponentInput(site,stations,supports,decay,PaletteFamily.STONE,false,waters,List.of());
        var template=templates.get(site.id().kind());if(template==null)throw new IllegalArgumentException("missing template "+site.id().kind());
        Bounds world=Rasterizer.worldBounds(site.frame(),template.localBounds(provisional));
        for(var e:input.columns().entrySet()) {
            int x=e.getKey().x(),z=e.getKey().z();
            if(x<world.minX()||x>=world.maxXExclusive()||z<world.minZ()||z>=world.maxZExclusive())continue;
            var key=new Point3i(x,site.frame().walkY(),z);
            supports.computeIfAbsent(key,k->probe.probe(protectedView(e.getValue(),e.getKey(),input.protectedBounds()),k.y(),bounds));
        }
        return new ComponentInput(site,stations,supports,decay,PaletteFamily.STONE,false,waters,List.of());
    }
    private static SiteRecord site(String wall,SiteKind kind,long ordinal,long s,List<Station> stations) {
        int i=1;while(i<stations.size()-1&&stations.get(i).globalSQ8()<s)i++;
        var a=stations.get(i-1);var b=stations.get(i);long length=b.globalSQ8()-a.globalSQ8(),offset=s-a.globalSQ8();
        long dx=b.point().xQ8()-a.point().xQ8(),dz=b.point().zQ8()-a.point().zQ8();
        var point=new Point2q(a.point().xQ8()+FixedMath.mulDiv(dx,offset,length),a.point().zQ8()+FixedMath.mulDiv(dz,offset,length));
        int w=a.walkY()+(int)FixedMath.roundDiv((long)(b.walkY()-a.walkY())*offset,length);
        double norm=StrictMath.hypot(dx,dz);
        var frame=new Frame(point,(int)StrictMath.round(dx*(1L<<30)/norm),(int)StrictMath.round(dz*(1L<<30)/norm),w,false);
        int x=FixedMath.floorBlock(point.xQ8()),z=FixedMath.floorBlock(point.zQ8());
        return new SiteRecord(new SiteId(wall,kind,ordinal),s,frame,new Bounds(x-33,w-35,z-33,x+34,w+8,z+34),SiteState.PLANNED,Optional.empty(),Optional.empty());
    }
    private static List<Station> stations(PlanWindow plans){
        var values=new TreeMap<Long,Station>();for(var p:plans.plans())for(var s:p.stations().stations()){
            var old=values.putIfAbsent(s.globalSQ8(),s);
            if(old!=null&&(old.walkY()!=s.walkY()||!old.point().equals(s.point())))throw new IllegalArgumentException("inconsistent seam");
        }return List.copyOf(values.values());
    }
    private static List<SiteRecord> sites(PlanWindow plans){
        var values=new TreeMap<SiteId,SiteRecord>(Comparator.comparing(SiteId::wallId).thenComparing(s->s.kind().name()).thenComparingLong(SiteId::ordinal));
        for(var p:plans.plans())for(var s:p.sites()){var old=values.putIfAbsent(s.id(),s);if(old!=null&&!old.equals(s))throw new IllegalArgumentException("inconsistent site");}
        return List.copyOf(values.values());
    }
    private static List<WaterEvidence> waters(PlanWindow plans){
        var values=new TreeMap<String,WaterEvidence>();
        for(var p:plans.plans())for(var w:p.geometry().water()){
            long start=p.prefix().lengthBeforeQ8()+w.crossing().fromQ8(),end=p.prefix().lengthBeforeQ8()+w.crossing().toQ8();
            if(start<0)throw new WallFailure(FailureCode.ROOT_NOT_FOUND,"river before root has no certified bank");
            var range=new ArcInterval(start,end);
            String key=range+":"+w.waterTopY()+":"+w.banks();
            var value=new WaterEvidence(range,w.classification(),w.channelWidthQ8(),w.crossingSpanQ8(),w.waterTopY(),w.banks(),w.piers(),w.inputHash());
            var old=values.putIfAbsent(key,value);
            if(old!=null&&(old.channelWidthQ8()!=value.channelWidthQ8()||old.classification()!=value.classification()))
                throw new WallFailure(FailureCode.HYDROLOGY_UNRESOLVED,"seam water evidence differs");
        }return List.copyOf(values.values());
    }
    private static List<BreakInterval> merge(List<BreakInterval> values){
        var sorted=values.stream().sorted(Comparator.comparingLong((BreakInterval b)->b.range().fromQ8()).thenComparing(b->b.reason().name())).toList();
        var result=new ArrayList<BreakInterval>();
        for(var b:sorted){if(result.isEmpty()||result.getLast().range().toQ8()<b.range().fromQ8())result.add(b);
            else{var old=result.removeLast();boolean terminal=b.range().toQ8()==Long.MAX_VALUE;
                result.add(new BreakInterval(new ArcInterval(old.range().fromQ8(),Math.max(old.range().toQ8(),b.range().toQ8())),
                        terminal?b.reason():old.reason(),terminal?b.scope():old.scope()));}}
        return List.copyOf(result);
    }
    private static List<ArcInterval> subtract(ArcInterval span,List<ArcInterval> gaps){
        var result=new ArrayList<ArcInterval>();long cursor=span.fromQ8();
        for(var gap:gaps.stream().sorted(Comparator.comparingLong(ArcInterval::fromQ8)).toList()){
            if(gap.toQ8()<=cursor||gap.fromQ8()>=span.toQ8())continue;
            if(gap.fromQ8()>cursor)result.add(new ArcInterval(cursor,Math.min(gap.fromQ8(),span.toQ8())));
            cursor=Math.max(cursor,gap.toQ8());if(cursor>=span.toQ8())break;
        }
        if(cursor<span.toQ8())result.add(new ArcInterval(cursor,span.toQ8()));return List.copyOf(result);
    }
    private static SurfaceProbe.ColumnView protectedView(SurfaceProbe.ColumnView original,Point3i pos,List<Bounds> boxes){
        return new SurfaceProbe.ColumnView(){
            public String state(int y){return original.state(y);}public boolean solidSupport(int y){return original.solidSupport(y);}
            public boolean water(int y){return original.water(y);}public boolean airOrReplaceablePlant(int y){return original.airOrReplaceablePlant(y);}
            public int highestNonPlantFeet(){return original.highestNonPlantFeet();}public String hash(){return original.hash();}
            public boolean protectedAt(int y){return original.protectedAt(y)||boxes.stream().anyMatch(b->pos.x()>=b.minX()&&pos.x()<b.maxXExclusive()
                    &&pos.z()>=b.minZ()&&pos.z()<b.maxZExclusive()&&y>=b.minY()&&y<b.maxYExclusive());}
        };
    }
    private static BreakReason reason(ProbeKind kind){return switch(kind){case PROTECTED->BreakReason.STRUCTURE_CONFLICT;case UPPER_CENSORED->BreakReason.BURIED;default->BreakReason.NO_SUPPORT;};}
    private static RealizationCell result(Input input,List<BreakInterval> breaks,List<ComponentVoxels> components,String t0){
        var sorted=components.stream().sorted(Comparator.comparing((ComponentVoxels c)->c.id().kind().name()).thenComparingLong(c->c.id().ordinal())).toList();
        String hash=hash(input.key()+"\n"+t0+"\n"+input.gateRevision()+"\n"+input.protectionHash()+"\n"+breaks+"\n"+sorted.stream().map(ComponentVoxels::hash).toList());
        return new RealizationCell(input.key(),breaks,sorted,t0,input.gateRevision(),input.protectionHash(),hash);
    }
    private static String hash(String value){return DeterministicHash.sha256(value.getBytes(StandardCharsets.UTF_8));}
}
