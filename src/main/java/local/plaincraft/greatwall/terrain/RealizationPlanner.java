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
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;

/** Whole-cell decisions use only immutable contracts/T0, never the owner currently asking for them. */
public final class RealizationPlanner {
    /** Ends whose ground exit could not be proven; the wall ends flush instead of with a cap. */
    private long capsDegraded;
    /** Cap pairs whose reserved spans overlapped, so neither was kept (see {@link #addCap}). */
    private long capsMutual;
    /** A cap already committed to a cell: the span it reserves plus the component that owns the voxels. */
    private record CapPlacement(long fromQ8,long toQ8,long edgeQ8,ComponentVoxels voxels){}
    public record Input(CellKey key,PlanWindow plans,Map<TerrainTile.Key,TerrainTile> tiles,
            Map<Point3i,SurfaceProbe.ColumnView> columns,List<Bounds> protectedBounds,Map<GateId,Integer> frozenGateY,
            long gateRevision,String protectionHash,Map<Long,Integer> foundationFloor,
            List<local.plaincraft.greatwall.planning.WallFoundationPlanner.ColumnFoundation> foundations) {
        /** Compatibility for analytic/tests without a foundation decision. */
        public Input(CellKey key,PlanWindow plans,Map<TerrainTile.Key,TerrainTile> tiles,
                Map<Point3i,SurfaceProbe.ColumnView> columns,List<Bounds> protectedBounds,Map<GateId,Integer> frozenGateY,
                long gateRevision,String protectionHash) {
            this(key,plans,tiles,columns,protectedBounds,frozenGateY,gateRevision,protectionHash,Map.of(),List.of());
        }
        public Input {
            Objects.requireNonNull(key);Objects.requireNonNull(plans);
            tiles=Map.copyOf(tiles);protectedBounds=List.copyOf(protectedBounds);frozenGateY=Map.copyOf(frozenGateY);
            // 只复用拥有独立存储的地基快照，其他调用者仍进行防御性复制。
            foundationFloor=FoundationFloorSnapshot.copyOf(foundationFloor);
            foundations=List.copyOf(foundations);
            // A read-only view of the column map, not a second copy. It holds one entry per sampled column
            // (about 27k on a real cell) and the provider already hands out an unmodifiable view, so
            // Map.copyOf here was a duplicate quadratic table build per owner - the same
            // ImmutableCollections$MapN cost FineHeightIndex.of documents at 53 s for 192k entries.
            columns=java.util.Collections.unmodifiableMap(columns);
            if(gateRevision<0)throw new IllegalArgumentException("negative revision");
            local.plaincraft.greatwall.model.WallTypes.requireHash(protectionHash);
        }
    }
    private final SurfaceProbe probe;
    private final Map<SiteKind,ComponentTemplate> templates;
    private final WorldRecipe recipe;
    private final local.plaincraft.greatwall.decay.BiomePaletteIndex biomeIndex;
    /** Memo for {@link #wallMaterials}: the ribbon is constant for a wall. */
    private volatile List<local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation> wallMaterialCache;

    /**
     * Set once the root fort has been reported as unrealizable.
     *
     * <p>Static because a planner is constructed per cell and the condition belongs to
     * the run, not to a cell; every planner in a session must observe the same answer.
     * Cleared at the start of each run so a fresh world is not marked by a previous one.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ROOT_DEGRADED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** True when the root fort was skipped this run; diagnostics read it. */
    public static boolean rootDegraded() { return ROOT_DEGRADED.get(); }

    /** Clears the degraded flag so a new run starts clean. */
    public static void resetRootDegraded() { ROOT_DEGRADED.set(false); }

    public RealizationPlanner(SurfaceProbe probe,Map<SiteKind,ComponentTemplate> templates){this(null,probe,templates);}
    public RealizationPlanner(WorldRecipe recipe,SurfaceProbe probe,Map<SiteKind,ComponentTemplate> templates){
        this(recipe,probe,null,templates);
    }
    public RealizationPlanner(WorldRecipe recipe,SurfaceProbe probe,
            local.plaincraft.greatwall.decay.BiomePaletteIndex biomeIndex,Map<SiteKind,ComponentTemplate> templates){
        this(recipe,probe,biomeIndex,templates,null);
    }
    private RealizationPlanner(WorldRecipe recipe,SurfaceProbe probe,
            local.plaincraft.greatwall.decay.BiomePaletteIndex biomeIndex,Map<SiteKind,ComponentTemplate> templates,
            RealizationWindow memos){
        this.recipe=recipe;this.probe=Objects.requireNonNull(probe);this.biomeIndex=biomeIndex;
        this.templates=Map.copyOf(templates);this.memos=memos;
    }
    /** 共享不可变配方/模板，窗口独占缓存以及实现期可变计数，不修改源实现器。 */
    public RealizationWindow openWindow(){return new RealizationWindow(this);}
    RealizationPlanner forkForWindow(RealizationWindow window){
        return new RealizationPlanner(recipe,probe,biomeIndex,templates,window);
    }

    private final RealizationWindow memos;

    /** 独占窗口负责所有缓存清理；独立实现器不保留派生缓存。 */
    public void clearCompiledBuildings(){if(memos!=null)memos.close();}
    public ImportedBuildingMemo.Stats buildingMemoStats(){
        return memos==null?new ImportedBuildingMemo.Stats(0,0,0,0,0):memos.buildingMemoStats();
    }
    public ImportedProjectionMemo.Stats projectionMemoStats(){
        return memos==null?new ImportedProjectionMemo.Stats(0,0,0,0):memos.projectionMemoStats();
    }
    public local.plaincraft.greatwall.diagnostics.CompileWindowMemory.Retained windowMemoStats(){
        return memos==null?new local.plaincraft.greatwall.diagnostics.CompileWindowMemory.Retained(0,0,0):memos.windowMemoStats();
    }
    public int templateVersion() { return recipe == null ? 1 : recipe.config().templateVersion(); }

    private List<Station> stationList(PlanWindow plans) {
        if (memos == null || plans.plans().size() != 1) return stations(plans);
        var plan = plans.plans().getFirst();
        return memos.stations(plan,()->stations(plans));
    }

    private List<SiteRecord> siteList(PlanWindow plans) {
        if (memos == null || plans.plans().size() != 1) return sites(plans);
        var plan = plans.plans().getFirst();
        return memos.sites(plan,()->sites(plans));
    }

    private List<WaterEvidence> waterList(PlanWindow plans) {
        if (memos == null || plans.plans().size() != 1) return waters(plans);
        var plan = plans.plans().getFirst();
        return memos.waters(plan,()->waters(plans));
    }

    /**
     * {@link WallSection#contract} memoised per plan instance and column.
     *
     * <p>The contract is a pure function of the station ribbon and the column's block coordinate, and the
     * compile resolves the same cell's columns once per owner - a cell has about ninety owners, so every
     * column paid the full ribbon walk about ninety times. That walk is the single largest item of a real
     * owner: ~67 us over the opening segment's ~2048 stations, ~23k columns per cell, so ~1.5 s per owner
     * of work that only varies with the column.
     */
    private WallSection.ColumnContract contractFor(Input input, List<Station> stations, int x, int z) {
        if (memos == null || input.plans().plans().size() != 1) return WallSection.contract(stations, x, z);
        var plan = input.plans().plans().getFirst();
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        return memos.contract(plan,key,()->WallSection.contract(stations,x,z));
    }

    /**
     * {@link #evidenceHash} memoised per plan instance and cell.
     *
     * <p>The digest is taken over the cell key, the plan hashes, the tiles, every sampled column with its
     * digest, the protection hash and the gate revision - every one of which the compiler builds
     * identically for all owners of a cell. Recomputing it per owner meant sorting and hashing the whole
     * column list (about 27k entries, each contributing a 64-character digest) once per owner instead of
     * once per cell: the difference measured in gigabytes of transient strings for one window.
     */
    private String evidenceHashFor(Input input) {        if (memos == null || input.plans().plans().size() != 1) return evidenceHash(input);
        var plan = input.plans().plans().getFirst();
        return memos.evidence(plan,input.key(),()->evidenceHash(input));
    }
    public RealizationCell resolve(Input input) {
        return resolve(input,null,null);
    }
    /** Production callers must obtain these certificates through the lock-external resolver first. */
    public RealizationCell resolve(Input input,List<GroundExitDecision> decisions) {
        Objects.requireNonNull(decisions);
        return resolve(input,List.copyOf(decisions),null);
    }
    public GroundExitDecision.Slice inspect(Input input) {
        GroundExitDecision.Slice[] result={null};
        resolve(input,null,s->result[0]=s);
        return Objects.requireNonNull(result[0],"missing exit slice");
    }
    public static long inspectionBytes(GroundExitDecision.Slice value){
        return inspectionBytes(value.cell(),value.exits(),value.breaks());
    }
    private static long inspectionBytes(CellKey cell,List<GroundExitDecision.Exit> exits,List<BreakInterval> breaks){
        long bytes=2048L+1024L*exits.size()+256L*(breaks.size()+1L)+16L*cell.dimension().length();
        for(var exit:exits)bytes=Math.addExact(bytes,16L*exit.site().wallId().length());
        return bytes;
    }
    public static long capVoteBytes(GroundExitDecision.CapVote value){
        return 2048L+value.cap().map(c->16L*c.site().wallId().length()).orElse(0L);
    }
    private static GroundExitDecision.Slice inspection(CellKey cell,List<GroundExitDecision.Exit> exits,
            List<BreakInterval> breaks,Optional<BreakInterval> terminal,String t0){
        CompiledAllocation.charge(inspectionBytes(cell,exits,breaks));
        return new GroundExitDecision.Slice(cell,exits,breaks,terminal,t0);
    }
    private static GroundExitDecision.CapVote capVote(Optional<GroundExitDecision.Cap> cap,String t0){
        CompiledAllocation.charge(2048L+cap.map(c->16L*c.site().wallId().length()).orElse(0L));
        return new GroundExitDecision.CapVote(cap,t0);
    }
    private RealizationCell resolve(Input input,List<GroundExitDecision> decisions,
            java.util.function.Consumer<GroundExitDecision.Slice> inspection) {
        long temporary=workingBytes(input);
        CompiledAllocation.charge(temporary);
        try { return ProtectedColumnView.indexed(input.protectedBounds(),input.columns().keySet(),
                ()->resolveAccounted(input,decisions,inspection)); }
        finally { CompiledAllocation.discard(temporary); }
    }
    private RealizationCell resolveAccounted(Input input,List<GroundExitDecision> decisions,
            java.util.function.Consumer<GroundExitDecision.Slice> inspection) {
        if(input.key().arcCell()<0)throw new IllegalArgumentException("negative arc cell");
        if(recipe!=null&&(!recipe.recipeHash().equals(input.key().recipeHash())||!recipe.dimension().equals(input.key().dimension())))
            throw new WallFailure(FailureCode.RECIPE_MISMATCH,"foreign realization request");
        var stations=stationList(input.plans());var accepted=new ArrayList<ComponentVoxels>();var breaks=new ArrayList<BreakInterval>();
        long from=Math.multiplyExact(input.key().arcCell(),64L*256),to=Math.addExact(from,64L*256);
        String t0=hash(input.tiles().values().stream().map(t->t.key().identity()+":"+t.hash()).sorted().toList().toString());
        if(stations.size()<2||from>=stations.getLast().globalSQ8()) {
            if(inspection!=null)inspection.accept(inspection(input.key(),List.of(),List.of(),terminal(input.plans()),evidenceHashFor(input)));
            return result(input,breaks,accepted,t0);
        }
        // A cell before the wall's first station is the mirror image of the "past the last station"
        // case above: it has no wall to build, so it degrades to an empty cell instead of failing.
        // Treating one side as empty and the other as a fatal MISSING_FEATURES_PLAN was asymmetric
        // and stopped the server on an otherwise normal geometry boundary.
        if(from<stations.getFirst().globalSQ8()) {
            if(ImportedSiteGeometry.enabled(recipe)&&to>stations.getFirst().globalSQ8())from=stations.getFirst().globalSQ8();
            else {
                if(inspection!=null)inspection.accept(inspection(input.key(),List.of(),List.of(),terminal(input.plans()),evidenceHashFor(input)));
                return result(input,breaks,accepted,t0);
            }
        }
        to=Math.min(to,stations.getLast().globalSQ8());
        if(inspection!=null||decisions!=null)requireRibbonColumns(input,stations,from,to);
        var bounds=recipe!=null?recipe.bounds():new DimensionBounds(
                input.tiles().keySet().stream().mapToInt(TerrainTile.Key::minY).min().orElseThrow(),
                input.tiles().keySet().stream().mapToInt(k->k.maxY()+1).max().orElseThrow(),29_999_000);
        String wall=recipe!=null?recipe.wallId():input.plans().plans().stream().flatMap(p->p.sites().stream()).findFirst()
                .map(s->s.id().wallId()).orElse(input.key().recipeHash());
        var field=new DecayField(recipe==null?0:recipe.seed(),templateVersion());
        var sites=siteList(input.plans());var maintained=new ArrayList<Long>();var exits=new ArrayList<GroundExitDecision.Exit>();
        var waters=waterList(input.plans());long terminal=Long.MAX_VALUE;BreakReason terminalReason=BreakReason.RIVER_WIDE;
        for(var p:input.plans().plans()){
            breaks.addAll(p.stations().breaks());
            if(p.geometry().terminal().isPresent()) {
                var end=p.geometry().terminal().get();
                long s=p.prefix().lengthBeforeQ8()+end.localSQ8();
                if(s<terminal){terminal=s;terminalReason=end.kind()==TerminalKind.WORLD_LIMIT?BreakReason.WORLD_LIMIT:BreakReason.RIVER_WIDE;}
            }
        }
        if(terminal!=Long.MAX_VALUE)breaks.add(new BreakInterval(new ArcInterval(terminal,Long.MAX_VALUE),terminalReason,BreakScope.PLANNED));
        if(!field.weathers()) {
            // 宽水只收口这一段；两岸陆地都留给原施工链，不得沿段号把对岸整段作废。
            for(var w:waters) {
                if(w.classification()==WaterClass.WIDE)
                    breaks.add(new BreakInterval(w.crossing(),BreakReason.RIVER_WIDE,BreakScope.PLANNED));
                else if(w.classification()==WaterClass.SUPPORT_GAP)
                    breaks.add(new BreakInterval(w.crossing(),BreakReason.NO_SUPPORT,BreakScope.PLANNED));
            }
        }
        var supported=new HashMap<Point3i,ProbeResult>();
        var importedProjection=ImportedSiteGeometry.enabled(recipe)
                ?memos==null?new ImportedRibbonProjection(stations,sites):memos.projections.get(input.plans(),stations,sites):null;
        var importedBoxes=ImportedSiteGeometry.enabled(recipe)?sites.stream().filter(s->s.state()==SiteState.PLANNED)
                .map(ImportedSiteGeometry::bounds).toList():List.<Bounds>of();
        var columnEntries=input.columns().entrySet().stream().sorted(Map.Entry.<Point3i,SurfaceProbe.ColumnView>comparingByKey(
                Comparator.comparingInt(Point3i::z).thenComparingInt(Point3i::x).thenComparingInt(Point3i::y))).toList();
        for(var e:columnEntries) {
            if(e.getKey().y()!=0)throw new IllegalArgumentException("column keys use y=0, independent of contract W");
            int x=e.getKey().x(),z=e.getKey().z();
            // 大楼背后低地不属于接引墙，不可夹到端面并制造一段假的无支撑缺口。
            if(importedProjection!=null&&importedBoxes.stream().anyMatch(b->x>=b.minX()&&x<b.maxXExclusive()&&z>=b.minZ()&&z<b.maxZExclusive()))continue;
            var contract=importedProjection==null?contractFor(input,stations,x,z):importedProjection.contract(x,z);
            if(contract==null||importedProjection!=null&&(contract.globalSQ8()<stations.getFirst().globalSQ8()
                    ||contract.globalSQ8()>=stations.getLast().globalSQ8()))continue;
            if(contract.globalSQ8()<Math.max(0,from-160L*256)||contract.globalSQ8()>=to+160L*256)continue;
            var value=ProtectedColumnView.use(e.getValue(),e.getKey(),input.protectedBounds(),
                    column->probe.probe(column,contract.walkY(),bounds));
            supported.put(new Point3i(e.getKey().x(),contract.walkY(),e.getKey().z()),value);
            // 配套墙逐列从真实地表填起，不能再用旧 20 格底座的 12 格挖深阈值裁断高平台。
            if(contract.lateralCell()>=-5&&contract.lateralCell()<5&&value.kind()!=ProbeKind.SUPPORTED&&value.kind()!=ProbeKind.WATER
                    &&(importedProjection==null||value.kind()!=ProbeKind.NO_SUPPORT)){
                long bin=Math.floorDiv(contract.globalSQ8(),256)*256;
                breaks.add(new BreakInterval(new ArcInterval(Math.max(0,bin-8L*256),bin+9L*256),reason(value.kind()),BreakScope.REALIZED));
            }
        }
        var occupied=new ArrayList<ArcInterval>();
        var gateWalkIntervals=new ArrayList<ArcInterval>();
        var gateEndPlanes=new ArrayList<Frame>();
        String evidence=null;
        for(var site:sites) {
            if(site.globalSQ8()<Math.max(0,from-64L*256)||site.globalSQ8()>=to+64L*256||site.state()==SiteState.SUPPRESSED||site.state()==SiteState.ABSORBED)continue;
            long extent=siteExtent(site);
            if(extent==0&&!ImportedSiteGeometry.enabled(recipe))continue;
            if(site.globalSQ8()>=terminal)continue;
            // Hydrology owns crossings. Site ordinals remain unchanged even when their body is suppressed.
            long siteFrom=Math.max(0,site.globalSQ8()-extent),siteTo=site.globalSQ8()+Math.max(256,extent);
            if(waters.stream().anyMatch(w->w.classification()!=WaterClass.DRY&&w.crossing().fromQ8()-8L*256<siteTo
                    &&w.crossing().toQ8()+8L*256>siteFrom))continue;
            // An 11-column tower occupies [-5,6), not the symmetric 12-column planning guard.
            // Preserve that guard for hydrology/exits, but do not remove the connecting wall column.
            long replacementFrom=!ImportedSiteGeometry.enabled(recipe)&&site.id().kind()==SiteKind.ENEMY_TOWER
                    ?site.globalSQ8()-5L*256:site.globalSQ8()-extent;
            var replacement=new ArcInterval(Math.max(0,replacementFrom),
                    site.globalSQ8()+extent+(ImportedSiteGeometry.enabled(recipe)?256:0));
            // All cells can vote on a site's full footprint; only its center cell publishes it.
            ComponentVoxels component=compile(site.id().kind(),()->componentInput(input,site,stations,
                    field.sample(site.globalSQ8(),PaletteFamily.STONE,List.of(site.globalSQ8())),List.of(),bounds,supported),input);
            if(component==null||component.voxels().isEmpty()){
                occupied.add(replacement);
                // fort0 is the wall's anchor, so its failure used to end the session. That
                // made a single unsuitable entrance - terrain that passes the footprint and
                // axis screens but cannot carry a level ground road - cost the entire run,
                // including the ~22 minutes of prewarm that preceded it, and the same cost
                // again on every retry.
                //
                // The wall does not depend on fort0 existing: every other component is
                // placed from the axis and its own station, and fort0 is one site among
                // many. Recording it as a realized break leaves the rest of the wall intact
                // and reports the gap, which is strictly more useful than no wall at all.
                if(site.id().kind()==SiteKind.FORT&&site.id().ordinal()==0){
                    breaks.add(new BreakInterval(new ArcInterval(Math.max(0,site.globalSQ8()-extent-8L*256),
                            site.globalSQ8()+extent+8L*256),BreakReason.CONFLICT,BreakScope.REALIZED));
                    ROOT_DEGRADED.set(true);                    local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                            "[GreatWall:S1C] FORT0_DEGRADED site={} s={} reason=realization-failed; "
                                    + "the wall continues without its root fort",site.id(),site.globalSQ8());
                } else {
                    breaks.add(new BreakInterval(new ArcInterval(Math.max(0,site.globalSQ8()-extent-8L*256),site.globalSQ8()+extent+8L*256),BreakReason.CONFLICT,BreakScope.REALIZED));
                }
            }else {
                if(site.id().kind()==SiteKind.GATE&&!ImportedSiteGeometry.enabled(recipe)) {
                    gateEndPlanes.add(site.frame());gateWalkIntervals.add(replacement);
                }else occupied.add(replacement);
                maintained.add(site.globalSQ8());
                if(site.globalSQ8()>=from&&site.globalSQ8()<to&&site.globalSQ8()<terminal) {
                    accepted.add(component);
                    if(naturalExit(input,site,component)) {
                        if(evidence==null)evidence=evidenceHashFor(input);
                        exits.add(new GroundExitDecision.Exit(site.id(),site.globalSQ8(),
                                new ArcInterval(siteFrom,siteTo),component.hash(),evidence));
                    }
                }
            }
        }
        for(var water:waters) {
            if(water.classification()!=WaterClass.NARROW)continue;
            long center=(water.crossing().fromQ8()+water.crossing().toQ8())/2;
            if(ImportedSiteGeometry.enabled(recipe)&&center>=terminal)continue;
            if(!field.weathers()&&water.crossing().toQ8()+8L*256>terminal) {
                breaks.add(new BreakInterval(water.crossing(),BreakReason.RIVER_WIDE,BreakScope.PLANNED));
                continue;
            }
            if(center<Math.max(0,from-128L*256)||center>=to+128L*256)continue;
            occupied.add(new ArcInterval(Math.max(0,water.crossing().fromQ8()-8L*256),water.crossing().toQ8()+8L*256));
            var site=site(wall,SiteKind.WATER_GATE,water.crossing().fromQ8(),center,stations);
            var component=compile(SiteKind.WATER_GATE,()->componentInput(input,site,stations,
                    field.sample(center,PaletteFamily.STONE,maintained),List.of(water),bounds,supported),input);
            if(component==null||component.voxels().isEmpty())breaks.add(new BreakInterval(water.crossing(),BreakReason.NO_SUPPORT,BreakScope.REALIZED));
            else if(center>=from&&center<to&&center<terminal)accepted.add(component);
        }
        var merged=merge(breaks);var excluded=new ArrayList<>(occupied);merged.forEach(b->excluded.add(b.range()));
        var walkBreaks=new ArrayList<>(merged);
        long decayFrom=Math.max(stations.getFirst().globalSQ8(),Math.max(0,from-160L*256));
        long decayTo=Math.min(stations.getLast().globalSQ8(),Math.min(terminal,to+160L*256));
        // 从源头跳过废墟断点，通行证明与实际落下的马道必须一致。
        if(field.weathers())for(long s=Math.floorDiv(decayFrom,256)*256;s<decayTo;s+=256){
            var d=field.sample(s,PaletteFamily.STONE,maintained);
            boolean seam=nearImportedSeam(s,sites);
            if(!seam&&(d.state()==DecayState.RUIN||d.state()==DecayState.BURIED))
                walkBreaks.add(new BreakInterval(new ArcInterval(s,Math.min(s+256,decayTo)),BreakReason.BURIED,BreakScope.REALIZED));
        }
        var walkMerged=merge(walkBreaks);
        if(inspection!=null) {
            var core=new ArrayList<BreakInterval>();
            for(var gap:walkMerged) {
                long a=Math.max(from,gap.range().fromQ8()),b=Math.min(to,gap.range().toQ8());
                if(a<b)core.add(new BreakInterval(new ArcInterval(a,b),gap.reason(),gap.scope()));
            }
            inspection.accept(inspection(input.key(),exits,core,terminal(input.plans()),evidence==null?evidenceHashFor(input):evidence));
            return result(input,walkMerged,accepted,t0);
        }
        var capExclusions=new ArrayList<ArcInterval>();
        // Caps committed so far, so the next one can tell whether it overlaps one already placed.
        var capPlacements=new ArrayList<CapPlacement>();
        if(decisions==null)for(var gap:walkMerged){
            addCap(input,wall,gap.range().fromQ8(),false,from,to,terminal,stations,field,maintained,bounds,supported,accepted,capExclusions,capPlacements);
            if(gap.range().toQ8()!=Long.MAX_VALUE&&gap.range().toQ8()<terminal&&gap.range().toQ8()<decayTo)
                addCap(input,wall,gap.range().toQ8(),true,from,to,terminal,stations,field,maintained,bounds,supported,accepted,capExclusions,capPlacements);
        }
        else {
            validateDecisions(input,decisions,from,to);
            var certifiedBreaks=new ArrayList<BreakInterval>();
            for(var decision:decisions) {
                certifiedBreaks.addAll(decision.breaks());capExclusions.addAll(decision.exclusions());
                for(var cap:decision.caps()) {
                    long s=cap.edge().sQ8();
                    if(s<from||s>=to&&!(s==to&&s==stations.getLast().globalSQ8()))continue;
                    var original=capSite(wall,s,cap.edge().reverse(),stations);
                    var attempt=EndCap.candidates(original,stations).stream().filter(a->a.globalSQ8()==cap.actualSQ8()).findFirst()
                            .orElseThrow(()->new WallFailure(FailureCode.T0_CORRUPT,"certified cap left station ribbon"));
                    var compiled=CompiledAllocation.component(()->((EndCap)templates.get(SiteKind.END_CAP)).compileAt(componentInput(input,attempt,stations,
                            new DecaySample(DecayState.INTACT,0,20),List.of(),bounds,supported),c->c.hash().equals(cap.componentHash())));
                    if(!compiled.id().equals(cap.site())||!compiled.hash().equals(cap.componentHash()))
                        throw new WallFailure(FailureCode.T0_CORRUPT,"certified cap changed before APPLY");
                    accepted.add(compiled);
                }
            }
            // The final cut is shared, including cells hundreds of blocks before the failing endpoint.
            accepted.removeIf(c->{
                if(c.id().kind()==SiteKind.END_CAP)return false;
                long center=c.id().kind()==SiteKind.WATER_GATE?waters.stream().filter(w->w.crossing().fromQ8()==c.id().ordinal())
                        .mapToLong(w->w.crossing().fromQ8()+(w.crossing().toQ8()-w.crossing().fromQ8())/2).findFirst().orElseThrow():
                        sites.stream().filter(s->s.id().equals(c.id())).mapToLong(SiteRecord::globalSQ8).findFirst().orElseThrow();
                return capExclusions.stream().anyMatch(r->center>=r.fromQ8()&&center<r.toQ8());
            });
            walkMerged=merge(certifiedBreaks);
        }
        excluded.addAll(capExclusions);
        var buildable=subtract(new ArcInterval(from,to),excluded);
        if(!buildable.isEmpty()){
            // 段缝可以穿过 64 格单元，两份半墙用不同身份提交，不能互相去重或报哈希冲突。
            long segment=input.plans().plans().getFirst().geometry().key().index();
            if(ImportedSiteGeometry.enabled(recipe)&&(segment<0||segment>Integer.MAX_VALUE||input.key().arcCell()>0xffffffffL))
                throw new WallFailure(FailureCode.OUT_OF_BOUNDS,"实物墙构件身份超出范围");
            long ordinal=ImportedSiteGeometry.enabled(recipe)?(segment<<32)|input.key().arcCell():input.key().arcCell();
            var site=site(wall,SiteKind.WALL,ordinal,input.key().arcCell()*64L*256+32L*256,stations);
            // The wall body is the biggest component of every owner, and it was handed an EMPTY material
            // ribbon: PaletteResolver refuses that, so every voxel of every wall section failed. The
            // error surfaced as "material ribbon lacks the eight-block halo" before the check became an
            // emptiness test, which is why 2176 of 2188 owners died and the wall published nothing - the
            // halo was never the problem, the empty list was.
            var candidate=new ComponentInput(site,stations,supported,field.sample(site.globalSQ8(),PaletteFamily.STONE,maintained),
                    PaletteFamily.STONE,false,List.of(),wallMaterials(input,stations),
                    ImportedSiteGeometry.enabled(recipe)?new GroundEntranceContract.Evidence(
                            GroundEntranceContract.Source.DURABLE_T0,bounds,input.columns())
                            :GroundEntranceContract.Evidence.analytic(supported),
                    input.foundationFloor(),input.foundations());
            var template=templates.get(SiteKind.WALL);
            if(!(template instanceof IntervalWallTemplate section))throw new IllegalArgumentException("WALL template must support build intervals");
            var walkExcluded=new ArrayList<>(excluded);walkMerged.forEach(b->walkExcluded.add(b.range()));
            walkExcluded.addAll(gateWalkIntervals);
            ComponentVoxels component;
            try {
                component=section instanceof ImportedRibbonSection imported
                        ? imported.compileIntervals(candidate,buildable,subtract(new ArcInterval(from,to),walkExcluded),
                                s->field.sample(s,PaletteFamily.STONE,maintained),gateEndPlanes,sites,importedProjection)
                        : section.compileIntervals(candidate,buildable,subtract(new ArcInterval(from,to),walkExcluded),
                                s->field.sample(s,PaletteFamily.STONE,maintained),gateEndPlanes,sites);
                if(ImportedSiteGeometry.enabled(recipe))component=ImportedConstructionGuard.validate(component,input,bounds);
            } catch (IllegalArgumentException outsideCell) {
                // The template validates its own inputs (WallSection throws "build interval outside cell",
                // "walkable interval outside cell", "height contract ..." as plain IllegalArgumentExceptions)
                // and this call sits outside the WallFailure handling compile() uses, so such an interval used
                // to escape as an anonymous owner failure that dropped EVERY component of the owner. Turn it
                // into the same named break the WallFailure path records, so the failure keeps its reason and
                // costs only this one component.
                local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                        "[GreatWall:corridor] WALL_SECTION_INTERVAL_REJECTED arc=[{},{}] reason={}",
                        from, to, outsideCell.getMessage());
                breaks.add(new BreakInterval(new ArcInterval(from,to),BreakReason.STRUCTURE_CONFLICT,BreakScope.REALIZED));
                component=null;
            }
            if(component!=null&&!component.voxels().isEmpty())accepted.add(component);
        }
        var resolved=result(input,walkMerged,accepted,t0);
        if(decisions==null)return resolved;
        return new RealizationCell(resolved.key(),resolved.breaks(),resolved.components(),resolved.t0Hash(),resolved.gateRevision(),
                resolved.protectionHash(),hash(resolved.hash()+"\n"+decisions.stream().map(GroundExitDecision::hash).sorted().toList()));
    }
    public GroundExitDecision.CapVote probeCap(Input input,GroundExitDecision.Edge edge) {
        long temporary=workingBytes(input);
        CompiledAllocation.charge(temporary);
        try { return ProtectedColumnView.indexed(input.protectedBounds(),input.columns().keySet(),
                ()->probeCapAccounted(input,edge)); }
        finally { CompiledAllocation.discard(temporary); }
    }
    private GroundExitDecision.CapVote probeCapAccounted(Input input,GroundExitDecision.Edge edge) {
        var ribbon=stationList(input.plans());
        String wall=recipe==null?siteList(input.plans()).getFirst().id().wallId():recipe.wallId();
        var limits=recipe==null?new DimensionBounds(input.tiles().keySet().stream().mapToInt(TerrainTile.Key::minY).min().orElseThrow(),
                input.tiles().keySet().stream().mapToInt(k->k.maxY()+1).max().orElseThrow(),29_999_000):recipe.bounds();
        var original=capSite(wall,edge.sQ8(),edge.reverse(),ribbon);
        var occupied=capObstacles(input,ribbon,limits,edge.sQ8());
        for(var attempt:EndCap.candidates(original,ribbon)) {
            long a=edge.reverse()?attempt.globalSQ8():attempt.globalSQ8()-30L*256;
            long b=edge.reverse()?attempt.globalSQ8()+30L*256:attempt.globalSQ8();
            if(a<edge.allowed().fromQ8()||b>edge.allowed().toQ8())continue;
            try {
                var c=CompiledAllocation.component(()->((EndCap)templates.get(SiteKind.END_CAP)).compileAt(componentInput(input,attempt,ribbon,
                        new DecaySample(DecayState.INTACT,0,20),List.of(),limits,Map.of()),candidate->{
                            var footprint=capFootprint(candidate,ribbon);
                            return footprint.fromQ8()>=edge.allowed().fromQ8()&&footprint.toQ8()<=edge.allowed().toQ8()
                                    &&occupied.stream().noneMatch(r->r.fromQ8()<footprint.toQ8()&&r.toQ8()>footprint.fromQ8());
                        }));
                if(!c.voxels().isEmpty()) {
                    return capVote(Optional.of(new GroundExitDecision.Cap(c.id(),edge,
                            attempt.globalSQ8(),c.hash(),capFootprint(c,ribbon))),evidenceHashFor(input));
                }
            }catch(WallFailure e){if(e.code()!=FailureCode.OWNERSHIP_CONFLICT)throw e;}
        }
        return capVote(Optional.empty(),evidenceHashFor(input));
    }
    private static ArcInterval capFootprint(ComponentVoxels component,List<Station> ribbon) {
        long min=Long.MAX_VALUE,max=Long.MIN_VALUE;
        for(var voxel:component.voxels()) {
            long s=WallSection.contract(ribbon,voxel.pos().x(),voxel.pos().z()).globalSQ8();
            min=Math.min(min,Math.floorDiv(s,256)*256);max=Math.max(max,(Math.floorDiv(s,256)+1)*256);
        }
        return new ArcInterval(min,max);
    }
    private List<ArcInterval> capObstacles(Input input,List<Station> ribbon,DimensionBounds limits,long edge) {
        var result=new ArrayList<ArcInterval>();var field=new DecayField(recipe==null?0:recipe.seed(),templateVersion());
        var water=waterList(input.plans());
        for(var w:water)if(w.classification()!=WaterClass.DRY)result.add(new ArcInterval(Math.max(0,w.crossing().fromQ8()-8*256L),w.crossing().toQ8()+8*256L));
        for(var s:siteList(input.plans())) {
            if(Math.abs(s.globalSQ8()-edge)>192*256L||s.state()==SiteState.SUPPRESSED||s.state()==SiteState.ABSORBED)continue;
            // 实物接口和接引段共同拥有通路；断头坡道不能退让到楼旁再把这段墙裁掉。
            if(ImportedSiteGeometry.enabled(recipe)) {
                long reach=ImportedSiteGeometry.reach(s.id())*256L;
                result.add(new ArcInterval(Math.max(0,s.globalSQ8()-reach),s.globalSQ8()+reach+256));
                continue;
            }
            long extent=siteExtent(s);
            if(extent==0)continue;
            var range=new ArcInterval(Math.max(0,s.globalSQ8()-extent),s.globalSQ8()+extent);
            if(result.stream().anyMatch(r->r.fromQ8()<range.toQ8()&&r.toQ8()>range.fromQ8()))continue;
            var c=compile(s.id().kind(),()->componentInput(input,s,ribbon,
                    field.sample(s.globalSQ8(),PaletteFamily.STONE,List.of(s.globalSQ8())),List.of(),limits,Map.of()),input);
            if(c!=null&&!c.voxels().isEmpty())result.add(range);
        }
        return List.copyOf(result);
    }
    private static SiteRecord capSite(String wall,long s,boolean reverse,List<Station> ribbon) {
        var value=site(wall,SiteKind.END_CAP,Math.addExact(Math.multiplyExact(s,2),reverse?1:0),s,ribbon);
        if(!reverse)return value;
        var f=value.frame();return new SiteRecord(value.id(),s,new Frame(f.center(),-f.txQ30(),-f.tzQ30(),f.walkY(),!f.mirror()),
                value.bounds(),value.state(),value.absorbedBy(),value.reason());
    }
    private static Optional<BreakInterval> terminal(PlanWindow plans) {
        return plans.plans().stream().filter(p->p.geometry().terminal().isPresent()).map(p->{
            var t=p.geometry().terminal().orElseThrow();return new BreakInterval(new ArcInterval(p.prefix().lengthBeforeQ8()+t.localSQ8(),Long.MAX_VALUE),
                    t.kind()==TerminalKind.WORLD_LIMIT?BreakReason.WORLD_LIMIT:BreakReason.RIVER_WIDE,BreakScope.PLANNED);
        }).min(Comparator.comparingLong(b->b.range().fromQ8()));
    }
    private static String evidenceHash(Input input) {
        long temporary=Math.addExact(4096,Math.addExact(Math.multiplyExact(768L,input.columns().size()),
                Math.multiplyExact(512L,input.tiles().size())));
        CompiledAllocation.charge(temporary);
        try { return evidenceHashAccounted(input); }
        finally { CompiledAllocation.discard(temporary); }
    }
    private static String evidenceHashAccounted(Input input) {
        var columns=input.columns().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Point3i::x).thenComparingInt(Point3i::z)))
                .map(e->e.getKey()+":"+e.getValue().hash()).toList();
        return hash(input.key()+"\n"+input.plans().plans().stream().map(SegmentPlan::hash).sorted().toList()+"\n"
                +input.tiles().values().stream().map(t->t.key().identity()+":"+t.hash()).sorted().toList()+"\n"+columns+"\n"
                +input.protectionHash()+"\n"+input.gateRevision()+"\n"+input.frozenGateY().entrySet().stream().map(Object::toString).sorted().toList());
    }
    private static void validateDecisions(Input input,List<GroundExitDecision> decisions,long from,long to) {
        long cursor=from;
        for(var d:decisions.stream().sorted(Comparator.comparingLong(d->d.key().range().fromQ8())).toList()) {
            if(!d.key().recipeHash().equals(input.key().recipeHash())||!d.key().dimension().equals(input.key().dimension())
                    ||!d.hash().equals(local.plaincraft.greatwall.persistence.GroundExitCodec.hashed(d).hash()))
                throw new WallFailure(FailureCode.RECIPE_MISMATCH,"foreign exit decision");
            if(d.key().range().fromQ8()>cursor)throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN,"gap in exit decisions");
            cursor=Math.max(cursor,d.key().range().toQ8());
        }
        if(cursor<to)throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN,"uncertified cell cannot APPLY");
    }
    private static boolean naturalExit(Input input,SiteRecord site,ComponentVoxels component) {
        if(component.requiredRoutes().isEmpty())return false;
        var solids=new HashSet<Point3i>();
        for(var v:component.voxels()) {
            if(v.kind()==IntentKind.SOLID)solids.add(v.pos());
        }
        var feet=new HashSet<Point3i>();
        for(var v:component.voxels())if(v.walkable()) {
            var p=new Point3i(v.pos().x(),v.pos().y()+1,v.pos().z());
            if(!solids.contains(p)&&!solids.contains(new Point3i(p.x(),p.y()+1,p.z())))feet.add(p);
        }
        var queue=new ArrayDeque<Point3i>();var seen=new HashSet<Point3i>();
        for(var p:feet)if(p.y()==site.frame().walkY()){queue.add(p);seen.add(p);}
        var box=component.bounds();
        while(!queue.isEmpty()) {
            var p=queue.removeFirst();
            for(var dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int dy=-1;dy<=1;dy++) {
                var n=new Point3i(p.x()+dir[0],p.y()+dy,p.z()+dir[1]);
                if(feet.contains(n)){if(seen.add(n))queue.addLast(n);continue;}
                var column=input.columns().get(new Point3i(n.x(),0,n.z()));
                if(column==null)throw new WallFailure(FailureCode.T0_MISSING,"ground-exit apron not captured: site="
                        +site.id()+" from="+p+" neighbor="+n+" componentBounds="+box);
                boolean natural=ProtectedColumnView.use(column,n,input.protectedBounds(),view->
                        n.y()<site.frame().walkY()&&view.highestNonPlantFeet()==n.y()&&view.solidSupport(n.y()-1)
                        &&!solids.contains(n)&&!solids.contains(new Point3i(n.x(),n.y()+1,n.z()))
                        &&view.airOrReplaceablePlant(n.y())&&view.airOrReplaceablePlant(n.y()+1)
                        &&!view.protectedAt(n.y())&&!view.protectedAt(n.y()-1));
                if(natural) {
                    if(n.x()<box.minX()||n.x()>=box.maxXExclusive()||n.z()<box.minZ()||n.z()>=box.maxZExclusive())return true;
                    if(seen.add(n))queue.addLast(n);
                }
            }
        }
        return false;
    }
    private void addCap(Input input,String wall,long s,boolean reverse,long from,long to,long terminal,List<Station> stations,
            DecayField field,List<Long> maintained,DimensionBounds bounds,Map<Point3i,ProbeResult> known,List<ComponentVoxels> components,
            List<ArcInterval> exclusions,List<CapPlacement> caps){
        if(s<=0||s<Math.max(0,from-160L*256)||s>to+160L*256||s>stations.getLast().globalSQ8())return;
        var site=site(wall,SiteKind.END_CAP,Math.addExact(Math.multiplyExact(s,2),reverse?1:0),s,stations);
        if(reverse){var f=site.frame();site=new SiteRecord(site.id(),s,new Frame(f.center(),-f.txQ30(),-f.tzQ30(),f.walkY(),!f.mirror()),
                site.bounds(),site.state(),site.absorbedBy(),site.reason());}
        if(!(templates.get(SiteKind.END_CAP) instanceof EndCap template))throw new IllegalArgumentException("missing EndCap");
        var candidates=EndCap.candidates(site,stations);
        // A candidate that cannot compile is silently skipped, so the DEGRADED line below could not say
        // whether the thirty-three attempts threw or compiled to nothing. Those are different faults: a
        // throw means the fine sample never covered the footprint (T0_MISSING) or the ownership graph
        // refused it, while empty voxels mean the template itself resolved nothing. One line each, on the
        // first occurrence only - 132 attempts per cap would otherwise bury the log.
        int capFailed=0,capEmpty=0;
        FailureCode capFirstFailure=null;
        for(var attempt:candidates){
            if(ImportedSiteGeometry.enabled(recipe)) {
                long a=reverse?s:Math.max(0,attempt.globalSQ8()-30L*256);
                long b=reverse?attempt.globalSQ8()+30L*256:s;
                boolean conflicts=siteList(input.plans()).stream().filter(v->v.state()==SiteState.PLANNED).anyMatch(v->{
                    long reach=ImportedSiteGeometry.reach(v.id())*256L;
                    return a<v.globalSQ8()+reach+256&&b>Math.max(0,v.globalSQ8()-reach);
                });
                if(conflicts)continue;
            }
            ComponentVoxels cap;
            try{cap=CompiledAllocation.component(()->template.compileAt(componentInput(input,attempt,stations,
                    new DecaySample(DecayState.INTACT,0,20),List.of(),bounds,known)));}
            catch(WallFailure failure){
                // Retry the next (more inward) candidate instead of failing the corridor: see compile().
                if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT&&failure.code()!=FailureCode.T0_MISSING)throw failure;
                if(capFailed++==0){
                    capFirstFailure=failure.code();
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                            "[GreatWall:diag] CAP_CANDIDATE_FAILED s={} code={} detail={}",
                            s,failure.code(),failure.getMessage());
                }
                continue;
            }
            if(cap.voxels().isEmpty()){
                if(capEmpty++==0)local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                        "[GreatWall:diag] CAP_EMPTY s={} attemptGlobalSQ8={} walkY={}",
                        s,attempt.globalSQ8(),attempt.frame().walkY());
                continue;
            }
            long actual=attempt.globalSQ8();
            var span=reverse?new ArcInterval(s,actual+30L*256):new ArcInterval(Math.max(0,actual-30L*256),s);
            // A cap reserves +/-30 blocks of its own edge, so a pair closer together than one cap is long
            // interpenetrates: the measured run had two caps 26 blocks apart whose exclusions unioned into a
            // 34-block hole, and a whole cap's worth of corridor disappeared with it. That is not a boundary
            // between neighbouring components. GroundExitResolver already answers the certified equivalent
            // with "keepFrom > keepTo -> both caps void", so mirror it here: drop both and leave the wall
            // continuous, rather than keeping one cap facing a gap it cannot close.
            CapPlacement clash=null;
            for(var earlier:caps){
                if(earlier.toQ8()>span.fromQ8()&&span.toQ8()>earlier.fromQ8()){clash=earlier;break;}
            }
            if(clash!=null){
                caps.remove(clash);
                components.remove(clash.voxels());
                exclusions.remove(new ArcInterval(clash.fromQ8(),clash.toQ8()));
                capsMutual++;
                if(capsMutual<=8){
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                            "[GreatWall:S1C] WALL_END_CAP_MUTUAL dropped both at={} and={} total={}",
                            s,clash.edgeQ8(),capsMutual);
                }
                return;
            }
            exclusions.add(span);
            caps.add(new CapPlacement(span.fromQ8(),span.toQ8(),actual,cap));
            if(s>=from&&(s<to||s==to&&s==stations.getLast().globalSQ8()))components.add(cap);
            return;
        }
        // Degrade instead of failing the owner. 560 of 2185 owners died here in the measured
        // session, and each one cost its whole window: the wall had missing chunks where it should
        // merely have ended square. A cap is decoration on a flush end, so its absence costs a
        // square end, not a hole.
        capsDegraded++;
        if(capsDegraded<=8||capsDegraded%512==0){
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:S1C] WALL_END_CAP_DEGRADED break={} candidates={} columns={} degraded={}",
                    s,candidates.size(),input.columns().size(),capsDegraded);
        }
    }
    private ComponentVoxels compile(SiteKind kind,java.util.function.Supplier<ComponentInput> preparation,Input input) {
        var template=templates.get(kind);if(template==null)throw new IllegalArgumentException("missing template "+kind);
        try{
            return CompiledAllocation.component(()->{
            var candidate=preparation.get();
            if(template instanceof Gate gate){
                Integer ground=input.frozenGateY().get(new GateId(candidate.site().id().wallId(),"planned",candidate.site().id().ordinal()));
                return ground==null?gate.compile(candidate):gate.compileAtGround(candidate,ground);
            }
            if(template instanceof ImportedBuildingTemplate) {
                java.util.function.Supplier<ComponentVoxels> production = () ->
                        ImportedConstructionGuard.validate(template.compile(candidate),input,recipe.bounds());
                return memos == null ? production.get() : memos.buildings.get(candidate,input,production);
            }
            // 水门继续走自身认证，不能拿实物楼体缓存或禁挖规则替代。
            return template.compile(candidate);
            });
        }catch(WallFailure failure){
            // A component that cannot be placed is one broken link, not a reason to abandon the whole
            // corridor: the caller records the break and carries on (FORT0 logs FORT0_DEGRADED and the
            // wall keeps its remaining segments). T0_MISSING belongs here too — it says this component's
            // axis aligned world bounds reach terrain the fine sample never captured, which is exactly as
            // site-local as an ownership conflict. It only happens for a corridor planned with a narrower
            // band than this build uses (a saved plan) or a footprint wider than WallFinePlanner.LATERAL.
            if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT&&failure.code()!=FailureCode.T0_MISSING)throw failure;
            if(failure.code()==FailureCode.T0_MISSING||ImportedSiteGeometry.enabled(recipe)){
                // Dropping a component is the safe cushion, but it is not allowed to be quiet: a hole in
                // the fine sample means someone is planning with a band narrower than this build widens it
                // to, and the gap in the wall is the only other symptom.
                local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                        "[GreatWall:S1C] COMPONENT_SKIPPED kind={} reason={}; the wall leaves a gap here",
                        kind, failure.getMessage());
            }
            return null;
        }
    }
    /**
     * One line per component footprint saying how much of it the fine sample actually covered.
     *
     * <p>{@code componentInput} throws on the first footprint column that is absent, and that message
     * names a single point - which cannot distinguish "the placement reaches outside the sampled band"
     * from "the sparse plan skipped this column" from "the column was probed and then dropped". This
     * counts the gaps and their bounding box before the walk begins, and changes nothing else. Silent
     * when the footprint is fully covered, so an ordinary run gains no noise.
     */
    private static void logFootprintCoverage(SiteRecord site,Bounds world,Map<Point3i,SurfaceProbe.ColumnView> columns) {
        try {
            int width=world.maxXExclusive()-world.minX(),depth=world.maxZExclusive()-world.minZ();
            int footprint=width*depth,missing=0;
            int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
            for(int z=world.minZ();z<world.maxZExclusive();z++)for(int x=world.minX();x<world.maxXExclusive();x++) {
                if(columns.get(new Point3i(x,0,z))!=null)continue;
                missing++;
                if(x<minX)minX=x; if(x>maxX)maxX=x;
                if(z<minZ)minZ=z; if(z>maxZ)maxZ=z;
            }
            if(missing==0)return;
            // How far sideways this footprint reaches, measured in the wall's own frame. The fine sample
            // hands out the wide band (+/-52) only near the planned gate and fort stations and the narrow
            // one (+/-16) everywhere else, so a component whose lateral reach exceeds the band it happened
            // to be sampled under loses the columns it needs - which is precisely what T0_MISSING means
            // here. Printing the reach beside both band widths turns that from a guess into a number.
            long nx=-(long)site.frame().tzQ30(),nz=(long)site.frame().txQ30();
            long cx8=site.frame().center().xQ8(),cz8=site.frame().center().zQ8();
            int lateralNeed=0;
            for(int[] corner:new int[][]{{world.minX(),world.minZ()},{world.minX(),world.maxZExclusive()-1},
                    {world.maxXExclusive()-1,world.minZ()},{world.maxXExclusive()-1,world.maxZExclusive()-1}}) {
                long dx8=((long)corner[0]<<8)-cx8,dz8=((long)corner[1]<<8)-cz8;
                long lateralQ8=Math.abs((dx8*nx+dz8*nz)>>30);
                lateralNeed=Math.max(lateralNeed,(int)(lateralQ8>>8));
            }
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:diag] COMPONENT_FOOTPRINT site={} kind={} ordinal={} footprint={}x{}={} sampled={} missing={} missingBox=[{},{}]..[{},{}] "
                            + "lateralNeed={} narrowBand={} wideBand={} exceedsNarrow={}",
                    site.id().wallId(),site.id().kind(),site.id().ordinal(),width,depth,footprint,
                    footprint-missing,missing,minX,minZ,maxX,maxZ,
                    lateralNeed,local.plaincraft.greatwall.planning.WallFinePlanner.BASE_LATERAL,
                    local.plaincraft.greatwall.planning.WallFinePlanner.LATERAL,
                    lateralNeed>local.plaincraft.greatwall.planning.WallFinePlanner.BASE_LATERAL);
        } catch(RuntimeException logOnly) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn("[GreatWall:diag] COMPONENT_FOOTPRINT could not be computed",logOnly);
        }
    }

    private ComponentInput componentInput(Input input,SiteRecord site,List<Station> stations,DecaySample decay,List<WaterEvidence> waters,
            DimensionBounds bounds,Map<Point3i,ProbeResult> known) {
        var materials=materials(input,stations);
        var provisional=new ComponentInput(site,stations,Map.of(),decay,PaletteFamily.STONE,false,waters,materials);
        var template=templates.get(site.id().kind());if(template==null)throw new IllegalArgumentException("missing template "+site.id().kind());
        Bounds world=template instanceof ExactFootprintTemplate exact?exact.worldBounds(provisional)
                :Rasterizer.worldBounds(site.frame(),template.localBounds(provisional));
        long columns=Math.multiplyExact((long)world.maxXExclusive()-world.minX(),(long)world.maxZExclusive()-world.minZ());
        CompiledAllocation.charge(Math.addExact(1024,Math.multiplyExact(columns,256)));
        // Templates only borrow their full authorized footprint, never the unrelated cell support table.
        var supports=new HashMap<Point3i,ProbeResult>();
        // Count the footprint's missing columns before walking it. The failure below names a single point,
        // and a component that straddles the sparse fine-sample plan's unprobed wings is missing dozens of
        // them - one point says nothing about which. This line says how big the footprint is, how much of
        // it the sample covered, and where the uncovered part lies, which is what decides whether the
        // sample is too sparse or the placement is out of band. It is a diagnostic only: the walk below
        // and its throw are unchanged.
        logFootprintCoverage(site, world, input.columns());
        for(int z=world.minZ();z<world.maxZExclusive();z++)for(int x=world.minX();x<world.maxXExclusive();x++) {
            var pos=new Point3i(x,0,z);var column=input.columns().get(pos);
            if(column==null)throw new WallFailure(FailureCode.T0_MISSING,"complete component T0 column missing: "+pos);
            var key=new Point3i(x,site.frame().walkY(),z);
            var existing=known.get(key);
            supports.put(key,existing!=null?existing:ProtectedColumnView.use(column,pos,input.protectedBounds(),
                    view->probe.probe(view,key.y(),bounds)));
        }
        if(Boolean.getBoolean("greatwall.s1c.protectionTrace")&&site.id().kind()==SiteKind.FORT&&site.id().ordinal()==0) {
            var counts=new EnumMap<ProbeKind,Integer>(ProbeKind.class);
            supports.values().forEach(v->counts.merge(v.kind(),1,Integer::sum));
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:protection-source] fort0 cell={} bounds={} supportKinds={} intersectingBoxes={}",
                    input.key(),world,counts,input.protectedBounds().stream().filter(b->
                            b.minX()<world.maxXExclusive()&&b.maxXExclusive()>world.minX()
                            &&b.minZ()<world.maxZExclusive()&&b.maxZExclusive()>world.minZ()
                            &&b.minY()<world.maxYExclusive()&&b.maxYExclusive()>world.minY()).distinct().toList());
        }
        var evidence=new GroundEntranceContract.Evidence(
                recipe==null?GroundEntranceContract.Source.ANALYTIC_FIXTURE:GroundEntranceContract.Source.DURABLE_T0,
                bounds,input.columns());
        var candidate=new ComponentInput(site,stations,supports,decay,PaletteFamily.STONE,false,waters,materials,evidence,
                input.foundationFloor(),input.foundations());
        if(Boolean.getBoolean("greatwall.s1c.protectionTrace")&&site.id().kind()==SiteKind.FORT&&site.id().ordinal()==0){
            var certificate=GroundEntranceContract.inspect(site.frame(),GroundEntranceContract.Corridor.fort(),supports,evidence);
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:entrance] fort0 frame={} source={} certificate={}",site.frame(),evidence.source(),certificate);
        }
        return candidate;
    }
    private long siteExtent(SiteRecord site) {
        if(ImportedSiteGeometry.enabled(recipe))return ImportedSiteGeometry.extent(site.id())*256L;
        return switch(site.id().kind()){case FORT->24L*256;case GATE->30L*256;case ENEMY_TOWER->6L*256;case BEACON->5L*256;default->0;};
    }
    private boolean nearImportedSeam(long s,List<SiteRecord> sites) {
        return ImportedSiteGeometry.enabled(recipe)&&sites.stream().anyMatch(site->site.state()==SiteState.PLANNED
                // 接引区不能被旧断头坡道反向占走，衰败从回到原路线之后再恢复。
                && Math.abs(site.globalSQ8()-s)<=ImportedSiteGeometry.reach(site.id())*256L);
    }
    /**
     * The material ribbon for a component: one material for the whole wall.
     *
     * <p>The wall used to take its material from the biome around every voxel, blended over an
     * eight-block window. That is not what a wall looks like - a real one is the same stone for
     * hundreds of blocks, and in the west the same rammed earth - and it cost a blend per emitted
     * voxel plus a ribbon whose edges no window could ever satisfy (a measured session rejected 2176
     * of 2188 components with "material ribbon lacks the eight-block halo").
     *
     * <p>So the material is decided once, from the wall's own first station, and held constant. The
     * first station is the wall's start, so every window of a segment derives the same material and
     * the seams cannot disagree.
     */
    /** The wall's material, resolved once: every component of the wall shares it. */
    private List<local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation> wallMaterials(
            Input input, List<Station> stations) {
        var cached = wallMaterialCache;
        if (cached == null) {
            cached = materials(input, stations);
            wallMaterialCache = cached;
        }
        return cached;
    }

    private List<local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation> materials(Input input, List<Station> stations) {
        if (biomeIndex == null) return List.of();
        var keys = new java.util.HashMap<Long, String>();
        for (var plan : input.plans().plans()) keys.putAll(plan.stations().biomeKeys());
        var ribbon = biomeIndex.ribbonByKey(stations, keys);
        if (ribbon.isEmpty()) return ribbon;
        var anchor = ribbon.getFirst();
        return List.of(
                new local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation(0, anchor.biomeKey(), anchor.variant()),
                new local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation(Long.MAX_VALUE / 8,
                        anchor.biomeKey(), anchor.variant()));
    }
    private static long workingBytes(Input input) {
        long bytes=Math.addExact(8192,Math.multiplyExact(256L,input.columns().size()));
        bytes=Math.addExact(bytes,Math.multiplyExact(512L,input.tiles().size()));
        for(var plan:input.plans().plans()) {
            bytes=Math.addExact(bytes,Math.multiplyExact(256L,plan.stations().stations().size()));
            bytes=Math.addExact(bytes,Math.multiplyExact(512L,plan.sites().size()+plan.geometry().water().size()+plan.stations().breaks().size()));
        }
        return bytes;
    }
    private static void requireRibbonColumns(Input input,List<Station> ribbon,long from,long to) {
        for(long s=from;s<to;s+=256) {
            var center=site("probe",SiteKind.WALL,0,s,ribbon).frame().center();
            int x=FixedMath.floorBlock(center.xQ8()),z=FixedMath.floorBlock(center.zQ8());
            for(int dz=-7;dz<=7;dz++)for(int dx=-7;dx<=7;dx++)
                if(!input.columns().containsKey(new Point3i(x+dx,0,z+dz)))
                    throw new WallFailure(FailureCode.T0_MISSING,"wall core T0 column missing");
        }
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
            // A river crossing whose global arc coordinate falls before the wall's start is not part of
            // this wall; drop it. It used to throw ROOT_NOT_FOUND, which is wrong twice over: it is a
            // normal boundary case (not a missing ground exit), and reusing that code made resolveCell
            // misclassify it as FORT0_DEGRADED.
            if(start<0)continue;
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
    private static BreakReason reason(ProbeKind kind){return switch(kind){case PROTECTED->BreakReason.STRUCTURE_CONFLICT;case UPPER_CENSORED->BreakReason.BURIED;default->BreakReason.NO_SUPPORT;};}
    private static RealizationCell result(Input input,List<BreakInterval> breaks,List<ComponentVoxels> components,String t0){
        var sorted=components.stream().sorted(Comparator.comparing((ComponentVoxels c)->c.id().kind().name()).thenComparingLong(c->c.id().ordinal())).toList();
        String hash=hash(input.key()+"\n"+t0+"\n"+input.gateRevision()+"\n"+input.protectionHash()+"\n"+breaks+"\n"+sorted.stream().map(ComponentVoxels::hash).toList());
        return new RealizationCell(input.key(),breaks,sorted,t0,input.gateRevision(),input.protectionHash(),hash);
    }
    private static String hash(String value){return DeterministicHash.sha256(value.getBytes(StandardCharsets.UTF_8));}
}
