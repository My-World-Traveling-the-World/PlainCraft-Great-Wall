package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/** A flat, ten-wide ridge after a valid fort. Both ramp sides are deeper than the frozen support window. */
class GroundExitFallbackTest {
    private static final String H="a".repeat(64);
    private static final PaletteBinding PALETTE=(i,p,l,w,s,h)->new BlockSpec(switch(p){
        case FLOOR,SLAB->"minecraft:stone_brick_slab";case STAIR->"minecraft:stone_brick_stairs";
        case AIR->"minecraft:air";case LAMP->"minecraft:lantern";default->"minecraft:stone_bricks";},Map.of());
    private record Fixture(RealizationPlanner planner,RealizationPlanner.Input early,RealizationPlanner.Input terminal,
            ComponentVoxels root) {}
    private static Fixture fixture(){
        var recipe=new WorldRecipe(new UUID(0,8),"fixture",8,"minecraft:overworld",new Point3i(0,64,0),
                new Axis(new Point3i(0,64,0),1<<30,0),new DimensionBounds(0,128,29_999_000),
                new GenerationConfig(1,1,2048,4,150000,256,512,8,64L<<20,64L<<20,32L<<20,H),H,H,H,H);
        var stations=new ArrayList<Station>();
        for(int x=0;x<=480;x+=4)stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var start=new Boundary(0,new Point2q(0,0),84,H);var end=new Boundary(1,new Point2q(480*256L,0),84,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,recipe.dimension(),0),List.of(start.point(),end.point()),List.of(0L,480*256L),
                List.of(),Optional.of(new Terminal(0,480*256L,TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
        var fort=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),0,new Frame(new Point2q(0,0),1<<30,0,84,false),
                new Bounds(-24,49,-24,24,92,24),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var plans=new PlanWindow(List.of(new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(fort),H)));
        var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        var tiles=new HashMap<TerrainTile.Key,TerrainTile>();
        for(int cx=-3;cx<=40;cx++)for(int cz=-2;cz<=1;cz++){
            int[] data=new int[32768];
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                int wx=cx*16+x,wz=cz*16+z;
                var column=new RidgeColumn(wx<128||wz>=-5&&wz<5?64:0);
                columns.put(new Point3i(wx,0,wz),column);
                for(int y=0;y<128;y++)data[(y*16+z)*16+x]=y<column.ground?1:0;
            }
            var key=new TerrainTile.Key(recipe.dimension(),cx,cz,0,127);
            tiles.put(key,new TerrainTile(key,H,List.of("minecraft:air{}:fluid=minecraft:empty:be=false","minecraft:stone{}:fluid=minecraft:empty:be=false"),data));
        }
        var d=new DecayField(8);var probe=new SurfaceProbe();
        var template=new FortPlaceholder(d,PALETTE);
        var supports=new HashMap<Point3i,ProbeResult>();
        for(int z=-26;z<=25;z++)for(int x=-26;x<=25;x++)
            supports.put(new Point3i(x,84,z),probe.probe(columns.get(new Point3i(x,0,z)),84,recipe.bounds()));
        var root=template.compile(new ComponentInput(fort,stations,supports,new DecaySample(DecayState.INTACT,0,20),
                PaletteFamily.STONE,false,List.of(),List.of()));
        var planner=new RealizationPlanner(recipe,probe,Map.of(SiteKind.WALL,new WallSection(d,PALETTE),
                SiteKind.END_CAP,new EndCap(d,PALETTE),SiteKind.FORT,template));
        var early=new RealizationPlanner.Input(new CellKey(H,recipe.dimension(),2),plans,tiles,columns,List.of(),Map.of(),0,H);
        var terminal=new RealizationPlanner.Input(new CellKey(H,recipe.dimension(),7),plans,tiles,columns,List.of(),Map.of(),0,H);
        return new Fixture(planner,early,terminal,root);
    }
    @Test void finiteRetreatCanFailAfterAnEarlierValidGroundExit(){
        var f=fixture();
        assertFalse(f.root().voxels().isEmpty(),"the earlier fort is physically buildable");
        var probe=new SurfaceProbe();var bounds=new DimensionBounds(0,128,29_999_000);
        for(int retreat=0;retreat<=128;retreat+=4)for(int side:new int[]{-1,1})for(int direction:new int[]{-1,1}){
            int x=480-retreat+direction*28,z=side*6;
            assertEquals(ProbeKind.NO_SUPPORT,probe.probe(f.terminal().columns().get(new Point3i(x,0,z)),84,bounds).kind());
        }
        var early=f.planner().resolve(f.early());
        assertTrue(early.components().stream().flatMap(c->c.voxels().stream()).anyMatch(VoxelIntent::walkable),
                "an upstream independent cell already publishes a walkway before the distant failure");
        var failure=assertThrows(WallFailure.class,()->f.planner().resolve(f.terminal()));
        assertEquals(FailureCode.OWNERSHIP_CONFLICT,failure.code());
        assertTrue(failure.getMessage().contains("no proven ground exit"));
    }
    @Test void exhaustedCapSearchMustFallBackToTheEarlierGroundExitWithoutFatalGeneration() throws Exception {
        var f=fixture();
        try(var worker=Executors.newSingleThreadExecutor();var resolver=resolver(f,worker)) {
        assertFalse(f.planner().inspect(at(f,0)).exits().isEmpty(),()->"root must expose a ground exit: "+
                f.planner().resolve(at(f,0)).components().stream().map(c->c.id()+" "+c.requiredRoutes()+" "+
                        c.voxels().stream().filter(VoxelIntent::walkable).collect(java.util.stream.Collectors.groupingBy(v->v.pos().y(),java.util.stream.Collectors.counting()))).toList());
        var decisions=assertDoesNotThrow(()->resolver.require(f.terminal().key()).get(60,TimeUnit.SECONDS),
                "S1B requires asynchronously proving the earlier exit before any cell may APPLY");
        var resolved=assertDoesNotThrow(()->f.planner().resolve(f.terminal(),decisions),
                "S1B contract requires fallback to the previous proven ground exit, not a fatal error on an ordinary supported ridge");
        assertTrue(resolved.breaks().stream().anyMatch(b->b.range().fromQ8()<=24*256L&&b.range().toQ8()==Long.MAX_VALUE),
                "fallback must withdraw the continuous walking interval before owner APPLY, not invent an unsupported ramp");
        var earlyDecisions=resolver.require(f.early().key()).get(60,TimeUnit.SECONDS);
        assertEquals(decisions,earlyDecisions,"late and early consumers wait for the same final interval");
        var early=f.planner().resolve(f.early(),earlyDecisions);
        assertTrue(early.components().stream().flatMap(c->c.voxels().stream()).noneMatch(VoxelIntent::walkable));
        var root=f.planner().resolve(at(f,0),resolver.require(at(f,0).key()).get(60,TimeUnit.SECONDS));
        assertTrue(root.components().stream().anyMatch(c->c.id().equals(f.root().id())));
        }
    }
    private static RealizationPlanner.Input at(Fixture f,long cell) {
        var i=f.early();return new RealizationPlanner.Input(new CellKey(i.key().recipeHash(),i.key().dimension(),cell),
                i.plans(),i.tiles(),i.columns(),i.protectedBounds(),i.frozenGateY(),i.gateRevision(),i.protectionHash());
    }
    private static GroundExitResolver resolver(Fixture f,Executor worker) {
        return new GroundExitResolver(new GroundExitResolver.Source(){
            public CompletableFuture<GroundExitDecision.Slice> inspect(CellKey key){
                return CompletableFuture.supplyAsync(()->f.planner().inspect(at(f,key.arcCell())),worker);
            }
            public CompletableFuture<GroundExitDecision.CapVote> cap(GroundExitDecision.Key key,GroundExitDecision.Edge edge){
                return CompletableFuture.supplyAsync(()->f.planner().probeCap(at(f,Math.floorDiv(edge.sQ8(),64L*256)),edge),worker);
            }
            public CompletableFuture<Void> persist(GroundExitDecision decision){return CompletableFuture.completedFuture(null);}
        },worker,1<<20);
    }
    @Test void missingFootprintColumnDropsTheComponentAndPublishesTheGap() {
        var f=fixture();var i=at(f,0);var columns=new HashMap<>(i.columns());columns.remove(new Point3i(-20,0,-20));
        var incomplete=new RealizationPlanner.Input(i.key(),i.plans(),i.tiles(),columns,i.protectedBounds(),i.frozenGateY(),i.gateRevision(),i.protectionHash());
        // A cache hole is not physical infeasibility: the footprint screen refuses to place the
        // component and the refusal is published as a realized break. It must never be read as
        // "this component cannot prove a ground exit" and quietly replaced by an earlier exit —
        // the gap has to be visible in the plan.
        var slice=assertDoesNotThrow(()->f.planner().inspect(incomplete));
        assertTrue(slice.breaks().stream().anyMatch(b->b.scope()==BreakScope.REALIZED),
                "the dropped component must be reported as a realized break, not swallowed");
        assertTrue(slice.exits().stream().noneMatch(e->e.site().equals(f.root().id())),
                "a component that was not placed cannot prove a ground exit either");
    }
    private static final class RidgeColumn implements SurfaceProbe.ColumnView {
        private final int ground;
        RidgeColumn(int ground){this.ground=ground;}
        public String state(int y){return y<ground?"minecraft:stone{}:fluid=minecraft:empty:be=false":"minecraft:air{}:fluid=minecraft:empty:be=false";}
        public boolean solidSupport(int y){return y<ground;}
        public boolean protectedAt(int y){return false;}
        public boolean water(int y){return false;}
        public boolean airOrReplaceablePlant(int y){return y>=ground;}
        public int highestNonPlantFeet(){return ground;}
        public String hash(){return local.plaincraft.greatwall.geometry.DeterministicHash.sha256(("ridge-ground:"+ground).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
