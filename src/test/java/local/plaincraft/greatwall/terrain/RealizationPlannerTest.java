package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class RealizationPlannerTest {
    @Test void immutableInspectionHandoffRemainsChargedUntilItsOwnerCloses(){
        var base=input(false,0);
        var beyond=new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",100),base.plans(),base.tiles(),
                base.columns(),List.of(),Map.of(),0,H);
        try(var budget=new AllocationBudget(32L<<20)){
            var summary=CompiledAllocation.run(budget,()->planner().inspect(beyond),RealizationPlanner::inspectionBytes);
            assertEquals(RealizationPlanner.inspectionBytes(summary),budget.stats().used());
            assertTrue(budget.stats().used()>0,"an empty but published summary is not zero-owned after its callback returns");
            budget.close();assertEquals(0,budget.stats().used());
        }
    }
    @Test void elevenWideTowerKeepsTheWallColumnInsideItsTwelveWidePlanningGuard() {
        var base=input(false,0);var p=base.plans().plans().getFirst();long center=320*256L;
        var tower=new SiteRecord(new SiteId("fixture",SiteKind.ENEMY_TOWER,1),center,
                new Frame(new Point2q(center,0),1<<30,0,84,false),new Bounds(315,51,-5,326,92,6),
                SiteState.PLANNED,Optional.empty(),Optional.empty());
        var plans=new PlanWindow(List.of(new SegmentPlan(p.geometry(),p.prefix(),p.stations(),List.of(tower),p.hash())));
        var request=new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",4),plans,base.tiles(),base.columns(),List.of(),Map.of(),0,H);
        var d=new DecayField(0);var planner=new RealizationPlanner(new SurfaceProbe(),Map.of(
                SiteKind.WALL,new WallSection(d,PALETTE),SiteKind.END_CAP,new EndCap(d,PALETTE),
                SiteKind.ENEMY_TOWER,new EnemyTower(d,PALETTE)));
        var result=planner.resolve(request);
        var connector=new Point3i(314,83,0);
        assertTrue(result.components().stream().flatMap(c->c.voxels().stream())
                .anyMatch(v->v.pos().equals(connector)&&v.walkable()),"wall must meet the tower's actual x=315 entry");
        assertEquals(12,6+6,"independent witness: symmetric planning interval removes twelve columns");
        assertEquals(11,6-(-5),"frozen tower remains eleven columns; the planning guard is not its masonry");
        assertEquals(1,(320-5)-(320-6),"old subtraction leaves exactly one unsupported walkway column");
    }
    @Test void exhaustedBudgetRejectsInspectionBeforeAnyTerrainColumnIsRead() {
        var base=input(false,0);int[] reads={0};
        var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        base.columns().forEach((p,c)->columns.put(p,new SurfaceProbe.ColumnView(){
            public String state(int y){reads[0]++;return c.state(y);}
            public boolean solidSupport(int y){reads[0]++;return c.solidSupport(y);}
            public boolean protectedAt(int y){reads[0]++;return c.protectedAt(y);}
            public boolean water(int y){reads[0]++;return c.water(y);}
            public boolean airOrReplaceablePlant(int y){reads[0]++;return c.airOrReplaceablePlant(y);}
            public int highestNonPlantFeet(){reads[0]++;return c.highestNonPlantFeet();}
            public String hash(){reads[0]++;return c.hash();}
        }));
        var value=new RealizationPlanner.Input(base.key(),base.plans(),base.tiles(),columns,List.of(),Map.of(),0,H);
        try(var budget=new AllocationBudget(32L<<20)) {
            budget.consume(32L<<20);
            var failure=assertThrows(WallFailure.class,()->CompiledAllocation.run(budget,()->planner().inspect(value),ignored->0));
            assertEquals(FailureCode.CAPACITY,failure.code());
            assertEquals(0,reads[0],"reserve support/sort tables before inspecting any actual terrain");
            assertEquals(32L<<20,budget.stats().used(),"rejection cannot free the caller's reservation");
        }
    }

    @Test void inspectionScratchIsReleasedOnSuccessAndMissingColumnFailure() {
        var base=input(false,0);
        try(var budget=new AllocationBudget(32L<<20)) {
            var expected=planner().inspect(base);
            for(int n=0;n<3;n++) {
                assertEquals(expected,CompiledAllocation.run(budget,()->planner().inspect(base),ignored->0));
                assertEquals(0,budget.stats().used());
            }
            var columns=new HashMap<>(base.columns());columns.remove(new Point3i(320,0,0));
            var missing=new RealizationPlanner.Input(base.key(),base.plans(),base.tiles(),columns,List.of(),Map.of(),0,H);
            var failure=assertThrows(WallFailure.class,()->CompiledAllocation.run(budget,()->planner().inspect(missing),ignored->0));
            assertEquals(FailureCode.T0_MISSING,failure.code());assertEquals(0,budget.stats().used());
        }
    }
    private static final String H="a".repeat(64);
    private static final PaletteBinding PALETTE=(i,p,l,w,s,h)->new BlockSpec(switch(p){
        case FLOOR,SLAB->"minecraft:stone_brick_slab";case STAIR->"minecraft:stone_brick_stairs";
        case AIR->"minecraft:air";case WATER->"minecraft:water";default->"minecraft:stone_bricks";},Map.of());
    private static RealizationPlanner planner() {
        var d=new DecayField(0);
        return new RealizationPlanner(new SurfaceProbe(),Map.of(SiteKind.WALL,new WallSection(d,PALETTE),
                SiteKind.END_CAP,new EndCap(d,PALETTE),SiteKind.GATE,new Gate(d,PALETTE)));
    }
    private static RealizationPlanner terminalPlanner(){
        var recipe=new WorldRecipe(new UUID(0,1),"fixture",3,"minecraft:overworld",new Point3i(0,64,0),
                new Axis(new Point3i(0,64,0),1<<30,0),new DimensionBounds(0,128,29_999_000),
                new GenerationConfig(1,1,2048,4,150000,256,512,8,64L<<20,64L<<20,32L<<20,H),H,H,H,H);
        var d=new DecayField(3);
        return new RealizationPlanner(recipe,new SurfaceProbe(),Map.of(SiteKind.WALL,new WallSection(d,PALETTE),
                SiteKind.END_CAP,new EndCap(d,PALETTE),SiteKind.GATE,new Gate(d,PALETTE)));
    }
    private static RealizationPlanner.Input input(boolean reverse,long revision) {
        var stations=new ArrayList<Station>();for(int x=0;x<=1024;x+=4)stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var key=new SegmentKey(H,"minecraft:overworld",0);var start=new Boundary(0,new Point2q(0,0),84,H);
        var end=new Boundary(1,new Point2q(1024L*256,0),84,H);
        var g=new SegmentGeometry(key,List.of(start.point(),end.point()),List.of(0L,1024L*256),List.of(),Optional.empty(),start,end,0,0,H);
        var plan=new SegmentPlan(g,new PrefixSummary(0,0,Optional.empty(),H),new StationTable(stations,List.of(),H),List.of(),H);
        var tiles=Map.of(new TerrainTile.Key("minecraft:overworld",20,0,0,127),new TerrainTile(
                new TerrainTile.Key("minecraft:overworld",20,0,0,127),H,List.of("minecraft:air{}:fluid=minecraft:empty:be=false"),new int[32768]));
        var entries=new ArrayList<Point3i>();for(int z=-16;z<16;z++)for(int x=144;x<576;x++)entries.add(new Point3i(x,0,z));
        if(reverse)Collections.reverse(entries);
        var columns=new LinkedHashMap<Point3i,SurfaceProbe.ColumnView>();
        for(var p:entries)columns.put(p,new Flat());
        return new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",5),new PlanWindow(List.of(plan)),tiles,columns,List.of(),Map.of(),revision,H);
    }
    @Test void orderIndependentCellKeepsStationContractImmutable() {
        var a=input(false,0);var b=input(true,0);
        var first=planner().resolve(a);var second=planner().resolve(b);
        assertEquals(first,second);assertFalse(first.components().isEmpty());
        assertEquals(1,first.components().size());
        assertEquals(SiteKind.WALL,first.components().getFirst().id().kind());
        assertTrue(a.plans().plans().getFirst().stations().stations().stream().allMatch(s->s.walkY()==84));
    }
    @Test void frozenGateRevisionChangesCellHashWithoutChangingGeometry() {
        var a=planner().resolve(input(false,0));var b=planner().resolve(input(false,1));
        assertEquals(a.components(),b.components());assertNotEquals(a.hash(),b.hash());
    }
    @Test void capVotesCannotOverwriteAnAcceptedWholeGateFootprint() {
        var base=input(false,0);var p=base.plans().plans().getFirst();
        var gate=new SiteRecord(new SiteId("fixture",SiteKind.GATE,1),340*256L,
                new Frame(new Point2q(340*256L,0),1<<30,0,84,false),new Bounds(310,51,-15,370,88,15),
                SiteState.PLANNED,Optional.empty(),Optional.empty());
        var plans=new PlanWindow(List.of(new SegmentPlan(p.geometry(),p.prefix(),p.stations(),List.of(gate),p.hash())));
        var request=new RealizationPlanner.Input(base.key(),plans,base.tiles(),base.columns(),List.of(),Map.of(),0,H);
        var vote=terminalPlanner().probeCap(request,new GroundExitDecision.Edge(380*256L,false,new ArcInterval(200*256L,380*256L)));
        assertTrue(vote.cap().isPresent());
        assertTrue(vote.cap().orElseThrow().footprint().toQ8()<=310*256L,"whole gate wins before a cap can be certified");
    }
    @Test void partialTerminalCellKeepsOneNearSideCapAndOriginalReason() {
        for(var kind:TerminalKind.values()){
            var base=input(false,0);var old=base.plans().plans().getFirst();
            var stations=new ArrayList<>(old.stations().stations().stream().filter(s->s.globalSQ8()<350*256L).toList());
            stations.add(new Station(350*256L,new Point2q(350*256L,0),84,0));
            var geometry=old.geometry();
            var end=new Boundary(1,new Point2q(350*256L,0),84,H);
            var g=new SegmentGeometry(geometry.key(),List.of(geometry.start().point(),end.point()),List.of(0L,350*256L),List.of(),
                    Optional.of(new Terminal(0,350*256L,kind)),geometry.start(),end,0,0,H);
            var p=new SegmentPlan(g,old.prefix(),new StationTable(stations,List.of(),H),List.of(),H);
            var changed=new RealizationPlanner.Input(base.key(),new PlanWindow(List.of(p)),base.tiles(),base.columns(),
                    base.protectedBounds(),base.frozenGateY(),0,H);
            var resolved=terminalPlanner().resolve(changed);
            var prior=terminalPlanner().resolve(new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",4),changed.plans(),
                    changed.tiles(),changed.columns(),List.of(),Map.of(),0,H));
            var all=java.util.stream.Stream.concat(prior.components().stream(),resolved.components().stream()).toList();
            assertEquals(1,all.stream().filter(c->c.id().kind()==SiteKind.END_CAP).count(),resolved.breaks().toString());
            assertTrue(resolved.components().stream().flatMap(c->c.voxels().stream()).allMatch(v->v.pos().x()<350));
            assertTrue(resolved.breaks().stream().anyMatch(b->b.range().toQ8()==Long.MAX_VALUE
                    &&b.reason()==(kind==TerminalKind.WORLD_LIMIT?BreakReason.WORLD_LIMIT:BreakReason.RIVER_WIDE)));
            var mergedWithRuin=planner().resolve(changed);
            assertTrue(mergedWithRuin.breaks().stream().anyMatch(b->b.range().fromQ8()<350*256L&&b.range().toQ8()==Long.MAX_VALUE
                    &&b.reason()==(kind==TerminalKind.WORLD_LIMIT?BreakReason.WORLD_LIMIT:BreakReason.RIVER_WIDE)));
        }
    }
    private static final class Flat implements SurfaceProbe.ColumnView {
        public String state(int y){return y<64?"minecraft:stone{}:fluid=minecraft:empty:be=false":"minecraft:air{}:fluid=minecraft:empty:be=false";}
        public boolean solidSupport(int y){return y<64;}public boolean protectedAt(int y){return false;}
        public boolean water(int y){return false;}public boolean airOrReplaceablePlant(int y){return y>=64;}
        public int highestNonPlantFeet(){return 64;}public String hash(){return H;}
    }
}
