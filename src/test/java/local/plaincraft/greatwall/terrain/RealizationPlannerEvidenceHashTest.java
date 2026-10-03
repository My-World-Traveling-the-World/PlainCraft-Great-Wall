package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class RealizationPlannerEvidenceHashTest {
    private static final String H="a".repeat(64),B="b".repeat(64),C="c".repeat(64);
    private static final String ORIGINAL_EVIDENCE_SIGNATURE="15c74f9cf28d029557be9db0bfadf90b188fb025ca5beacfbbbb2696ce1f3b78";
    private static final Point3i OUTSIDE=new Point3i(900,0,24);
    private static final TerrainTile.Key OUTSIDE_TILE=new TerrainTile.Key("minecraft:overworld",56,1,0,127);
    private static final GateId UNUSED_GATE=new GateId("fixture","planned",999);
    private static final PaletteBinding PALETTE=(i,p,l,w,s,h)->new BlockSpec(switch(p){
        case FLOOR,SLAB->"minecraft:stone_brick_slab";case STAIR->"minecraft:stone_brick_stairs";
        case AIR->"minecraft:air";case WATER->"minecraft:water";default->"minecraft:stone_bricks";},Map.of());

    @Test void naturalExitsAndFinalSliceReduceFullEvidenceExactlyOnce() {
        var outside=new HashOnly(H);var input=input(false,outside);
        assertOutsideProbeWindow(input);
        var slice=planner().inspect(input);
        assertEquals(2,slice.exits().size(),"two real EnemyTower ground exits must exercise reuse");
        assertExitHashes(slice);
        System.out.println("inspect: naturalExits="+slice.exits().size()+", outsideColumnHashReads="+outside.hashReads);
        assertEquals(1,outside.hashReads,"outside-S column is hashed only by the full evidence reduction");
    }

    @Test void naturalExitsWithoutInspectionReduceFullEvidenceExactlyOnce() {
        var outside=new HashOnly(H);var input=input(false,outside);
        assertOutsideProbeWindow(input);
        var cell=planner().resolve(input);
        assertEquals(2,cell.components().stream().filter(c->c.id().kind()==SiteKind.ENEMY_TOWER).count());
        System.out.println("resolve: outsideColumnHashReads="+outside.hashReads);
        assertEquals(1,outside.hashReads,"later natural exits reuse the first evidence hash without a Slice");
    }

    @Test void fullEvidenceRemainsSensitiveToEveryOriginalInputField() {
        var input=input(false,new HashOnly(H));var expected=planner().inspect(input);
        var columns=new LinkedHashMap<>(input.columns());columns.put(OUTSIDE,new HashOnly(B));
        var plans=new ArrayList<>(input.plans().plans());var remote=plans.getLast();
        assertTrue(remote.stations().stations().getFirst().globalSQ8()>=384*256L);
        plans.set(plans.size()-1,new SegmentPlan(remote.geometry(),remote.prefix(),remote.stations(),remote.sites(),C));
        var tiles=new LinkedHashMap<>(input.tiles());tiles.put(OUTSIDE_TILE,tile(OUTSIDE_TILE,"minecraft:stone"));
        var frozen=new LinkedHashMap<>(input.frozenGateY());frozen.put(UNUSED_GATE,65);
        var changes=new LinkedHashMap<String,RealizationPlanner.Input>();
        changes.put("outside-column hash",copy(input,input.key(),input.plans(),input.tiles(),columns,input.frozenGateY(),0,H));
        changes.put("outside-window plan hash",copy(input,input.key(),new PlanWindow(plans),input.tiles(),input.columns(),input.frozenGateY(),0,H));
        changes.put("outside-window tile",copy(input,input.key(),input.plans(),tiles,input.columns(),input.frozenGateY(),0,H));
        changes.put("protection hash",copy(input,input.key(),input.plans(),input.tiles(),input.columns(),input.frozenGateY(),0,B));
        changes.put("gate revision",copy(input,input.key(),input.plans(),input.tiles(),input.columns(),input.frozenGateY(),1,H));
        changes.put("unrelated frozen gate Y",copy(input,input.key(),input.plans(),input.tiles(),input.columns(),frozen,0,H));
        changes.put("cell",copy(input,new CellKey(H,"minecraft:overworld",4),input.plans(),input.tiles(),input.columns(),input.frozenGateY(),0,H));
        assertEquals(originalEvidenceSignature(input),expected.t0Hash());
        for(var change:changes.entrySet()) {
            var slice=planner().inspect(change.getValue());
            assertNotEquals(expected.t0Hash(),slice.t0Hash(),change.getKey());
            assertEquals(originalEvidenceSignature(change.getValue()),slice.t0Hash(),change.getKey());
            assertExitHashes(slice);
        }
    }

    @Test void insertionOrderAndOriginalUtf8SignatureRemainUnchanged() {
        var forward=input(false,new HashOnly(H));var reverse=input(true,new HashOnly(H));
        var first=planner().inspect(forward);var second=planner().inspect(reverse);
        assertEquals(2,first.exits().size());
        assertEquals(first,second);
        assertEquals(ORIGINAL_EVIDENCE_SIGNATURE,first.t0Hash());
        assertEquals(originalEvidenceSignature(forward),first.t0Hash());
        assertEquals(originalEvidenceSignature(reverse),second.t0Hash());
        assertExitHashes(first);
        System.out.println("originalEvidenceSignature="+first.t0Hash());
    }

    @Test void noNaturalExitHashesOnlyWhenPublishingInspection() {
        var outside=new HashOnly(H);var input=withoutSites(input(false,outside));
        planner().resolve(input);
        assertEquals(0,outside.hashReads,"resolve without natural exits must not eagerly hash evidence");
        var slice=planner().inspect(input);
        assertTrue(slice.exits().isEmpty());
        assertEquals(1,outside.hashReads,"the final Slice still needs the full evidence hash");
    }

    @Test void earlyStationBranchesKeepTheirOriginalHashBehavior() {
        for(boolean shortRibbon:List.of(false,true)) {
            var outside=new HashOnly(H);var base=input(false,outside);
            var plans=base.plans();
            if(shortRibbon) {
                var p=plans.plans().getFirst();
                plans=new PlanWindow(List.of(new SegmentPlan(p.geometry(),p.prefix(),
                        new StationTable(List.of(p.stations().stations().getFirst()),List.of(),H),p.sites(),p.hash())));
            }
            var key=shortRibbon?base.key():new CellKey(H,"minecraft:overworld",100);
            var early=copy(base,key,plans,base.tiles(),base.columns(),base.frozenGateY(),0,H);
            planner().resolve(early);
            assertEquals(0,outside.hashReads);
            var slice=planner().inspect(early);
            assertTrue(slice.exits().isEmpty());
            assertEquals(1,outside.hashReads);
            assertEquals(originalEvidenceSignature(early),slice.t0Hash());
        }
    }

    private static void assertOutsideProbeWindow(RealizationPlanner.Input input) {
        var ribbon=input.plans().plans().stream().flatMap(p->p.stations().stations().stream())
                .distinct().sorted(Comparator.comparingLong(Station::globalSQ8)).toList();
        long s=WallSection.contract(ribbon,OUTSIDE.x(),OUTSIDE.z()).globalSQ8();
        long from=input.key().arcCell()*64*256L,to=from+64*256L;
        assertTrue(s<Math.max(0,from-160*256L)||s>=to+160*256L);
    }

    private static void assertExitHashes(GroundExitDecision.Slice slice) {
        for(var exit:slice.exits())assertEquals(slice.t0Hash(),exit.t0Hash());
    }

    // Frozen pre-optimization signature: every input, original sort keys, text encoding and separators.
    private static String originalEvidenceSignature(RealizationPlanner.Input input) {
        var columns=input.columns().entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(Point3i::x).thenComparingInt(Point3i::z)))
                .map(e->e.getKey()+":"+e.getValue().hash()).toList();
        String value=input.key()+"\n"+input.plans().plans().stream().map(SegmentPlan::hash).sorted().toList()+"\n"
                +input.tiles().values().stream().map(t->t.key().identity()+":"+t.hash()).sorted().toList()+"\n"+columns+"\n"
                +input.protectionHash()+"\n"+input.gateRevision()+"\n"
                +input.frozenGateY().entrySet().stream().map(Object::toString).sorted().toList();
        return DeterministicHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static RealizationPlanner planner() {
        var d=new DecayField(0);
        return new RealizationPlanner(new SurfaceProbe(),Map.of(SiteKind.WALL,new WallSection(d,PALETTE),
                SiteKind.END_CAP,new EndCap(d,PALETTE),SiteKind.ENEMY_TOWER,new EnemyTower(d,PALETTE)));
    }

    private static RealizationPlanner.Input withoutSites(RealizationPlanner.Input input) {
        var plans=input.plans().plans().stream().map(p->new SegmentPlan(p.geometry(),p.prefix(),p.stations(),List.of(),p.hash())).toList();
        return copy(input,input.key(),new PlanWindow(plans),input.tiles(),input.columns(),input.frozenGateY(),0,H);
    }

    private static RealizationPlanner.Input copy(RealizationPlanner.Input input,CellKey key,PlanWindow plans,
            Map<TerrainTile.Key,TerrainTile> tiles,Map<Point3i,SurfaceProbe.ColumnView> columns,
            Map<GateId,Integer> frozen,long revision,String protection) {
        return new RealizationPlanner.Input(key,plans,tiles,columns,input.protectedBounds(),frozen,revision,protection);
    }

    private static RealizationPlanner.Input input(boolean reverse,HashOnly outside) {
        var towers=new ArrayList<SiteRecord>();
        for(int x:new int[]{336,368})towers.add(new SiteRecord(new SiteId("fixture",SiteKind.ENEMY_TOWER,x),x*256L,
                new Frame(new Point2q(x*256L,0),1<<30,0,84,false),new Bounds(x-5,51,-5,x+6,92,6),
                SiteState.PLANNED,Optional.empty(),Optional.empty()));
        if(reverse)Collections.reverse(towers);
        var plans=new ArrayList<SegmentPlan>(List.of(plan(0,towers,H),plan(1,List.of(),B)));
        var positions=new ArrayList<Point3i>();
        for(int z=-16;z<16;z++)for(int x=144;x<576;x++)positions.add(new Point3i(x,0,z));
        positions.add(OUTSIDE);
        var tileKeys=new ArrayList<>(List.of(new TerrainTile.Key("minecraft:overworld",20,0,0,127),OUTSIDE_TILE));
        var gateIds=new ArrayList<>(List.of(new GateId("fixture","planned",998),UNUSED_GATE));
        if(reverse) {
            Collections.reverse(plans);Collections.reverse(positions);Collections.reverse(tileKeys);Collections.reverse(gateIds);
        }
        var columns=new LinkedHashMap<Point3i,SurfaceProbe.ColumnView>();
        for(var pos:positions)columns.put(pos,pos.equals(OUTSIDE)?outside:new Flat());
        var tiles=new LinkedHashMap<TerrainTile.Key,TerrainTile>();
        for(var key:tileKeys)tiles.put(key,tile(key,"minecraft:air"));
        var frozen=new LinkedHashMap<GateId,Integer>();
        for(var id:gateIds)frozen.put(id,id.equals(UNUSED_GATE)?64:63);
        return new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",5),new PlanWindow(plans),tiles,columns,
                List.of(new Bounds(900,0,24,901,1,25)),frozen,0,H);
    }

    private static SegmentPlan plan(int index,List<SiteRecord> sites,String signature) {
        long from=index*1024*256L,to=from+1024*256L;
        var start=new Boundary(index,new Point2q(from,0),84,H);var end=new Boundary(index+1,new Point2q(to,0),84,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",index),List.of(start.point(),end.point()),
                List.of(0L,1024*256L),List.of(),Optional.empty(),start,end,0,0,H);
        var stations=new ArrayList<Station>();
        for(long s=from;s<=to;s+=4*256L)stations.add(new Station(s,new Point2q(s,0),84,0));
        return new SegmentPlan(geometry,new PrefixSummary(index,from,Optional.empty(),H),
                new StationTable(stations,List.of(),H),sites,signature);
    }

    private static TerrainTile tile(TerrainTile.Key key,String state) {
        return new TerrainTile(key,H,List.of(state+"{}:fluid=minecraft:empty:be=false"),new int[key.volume()]);
    }

    private static class Flat implements SurfaceProbe.ColumnView {
        public String state(int y){return y<64?"minecraft:stone{}:fluid=minecraft:empty:be=false":"minecraft:air{}:fluid=minecraft:empty:be=false";}
        public boolean solidSupport(int y){return y<64;}public boolean protectedAt(int y){return false;}
        public boolean water(int y){return false;}public boolean airOrReplaceablePlant(int y){return y>=64;}
        public int highestNonPlantFeet(){return 64;}public String hash(){return H;}
    }

    private static final class HashOnly implements SurfaceProbe.ColumnView {
        private final String signature;
        int hashReads;
        HashOnly(String signature){this.signature=signature;}
        public String state(int y){throw unexpectedRead();}
        public boolean solidSupport(int y){throw unexpectedRead();}
        public boolean protectedAt(int y){throw unexpectedRead();}
        public boolean water(int y){throw unexpectedRead();}
        public boolean airOrReplaceablePlant(int y){throw unexpectedRead();}
        public boolean naturalThinIce(int y){throw unexpectedRead();}
        public int highestNonPlantFeet(){throw unexpectedRead();}
        public String hash(){hashReads++;return signature;}
        private AssertionError unexpectedRead(){return new AssertionError("outside-S column was terrain-probed");}
    }
}
