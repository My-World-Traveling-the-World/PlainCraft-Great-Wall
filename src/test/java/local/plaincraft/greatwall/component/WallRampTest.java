package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.stream.Collectors;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.SupportGraph;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WallRampTest {
    @Test void fixedFourCandidateOrderUsesCompleteFootprintsAndNeverLoadedSide() {
        var full=TemplateFixtures.input(SiteKind.RAMP,new Bounds(-30,-33,-15,30,3,15));
        for(int first=0;first<4;first++) {
            int candidate=first;var map=new java.util.HashMap<>(full.supports());
            map.replaceAll((p,v)->{
                int slot=(p.x()>=0?2:0)+(p.z()<0?1:0);
                return slot<candidate?new ProbeResult(ProbeKind.NO_SUPPORT,0,java.util.OptionalInt.empty(),java.util.OptionalInt.empty(),64,TemplateFixtures.HASH):v;
            });
            var input=new ComponentInput(full.site(),full.stations(),map,full.decay(),full.family(),false,full.waters(),full.materials());
            var c=new Ramp(new DecayField(0),TemplateFixtures.PALETTE).compile(input);
            assertFalse(c.voxels().isEmpty(),"candidate="+first);
            var ground=c.requiredRoutes().getFirst().getFirst();
            assertEquals(first>=2,ground.x()>=0);assertEquals(first%2==1,ground.z()<0);
        }
    }
    @Test void outerRailsAreGroundedAndNeverReplaceTheThreeWideRuns() {
        var input=TemplateFixtures.input(SiteKind.RAMP,RAMP);
        var c=ramp.compileBetween(input,64,84);
        assertTrue(c.voxels().stream().anyMatch(v->v.part()==Part.PARAPET));
        for(int j=0;j<10;j++)for(int z=5;z<=7;z++) {
            var p=new Point3i(-26+j,64+j,z);
            assertTrue(c.voxels().stream().anyMatch(v->v.pos().equals(p)&&v.part()==Part.STAIR&&v.walkable()));
        }
        for(var rail:c.voxels().stream().filter(v->v.part()==Part.PARAPET).toList())
            assertTrue(c.voxels().stream().anyMatch(v->v.pos().equals(new Point3i(rail.pos().x(),rail.pos().y()-1,rail.pos().z()))
                    &&v.kind()==IntentKind.SOLID));
    }
    @ParameterizedTest @ValueSource(ints={0,4,5,12})
    void defaultRampGroundEntranceTracksActualSupportedGround(int drop) {
        var input=TemplateFixtures.input(SiteKind.RAMP,new Bounds(-30,-33,4,-6,3,15),0,0,0,PaletteFamily.STONE,DecayState.INTACT,drop);
        var ramp=new Ramp(new DecayField(0),TemplateFixtures.PALETTE);
        var c=ramp.compile(input);
        assertFalse(c.voxels().isEmpty());
        assertEquals(64-drop,c.requiredRoutes().getFirst().getFirst().y());
        if(drop>0){
            var nominal=ramp.compileBetween(input,64,84);
            assertEquals(64,nominal.requiredRoutes().getFirst().getFirst().y());
            assertTrue(nominal.requiredRoutes().getFirst().getFirst().y()-(64-drop)>1,
                    "counterexample: old W-20 default creates an unclimbable ground entrance");
        }
    }
    private final WallSection wall = new WallSection(new DecayField(0),TemplateFixtures.PALETTE);
    private final Ramp ramp = new Ramp(new DecayField(0),TemplateFixtures.PALETTE);
    private static final Bounds WALL = new Bounds(-32,-32,-5,32,3,5);
    private static final Bounds RAMP = new Bounds(-30,-33,4,-6,3,15);
    @Test void wallHas20By10TaperAndSixClearLane() {
        var c = wall.compile(TemplateFixtures.input(SiteKind.WALL,WALL));
        assertFalse(c.voxels().isEmpty());
        for (int y = 64; y < 76; y++) {
            final int yy = y;
            assertEquals(10,c.voxels().stream().filter(v -> v.pos().x() == 1 && v.pos().y() == yy && v.kind() == IntentKind.SOLID).count());
        }
        assertEquals(6,c.voxels().stream().filter(v -> v.pos().x()==1 && v.walkable()).count());
        assertEquals(8,c.voxels().stream().filter(v -> v.pos().x()==1 && v.pos().y()==80 && v.kind()==IntentKind.SOLID).count());
    }
    @Test void familiesHaveSameOccupancy() {
        Set<Point3i> stone = null;
        for (PaletteFamily family : PaletteFamily.values()) {
            var c = wall.compile(TemplateFixtures.input(SiteKind.WALL,WALL,0,0,0,family,DecayState.INTACT,0));
            var positions = c.voxels().stream().map(VoxelIntent::pos).collect(Collectors.toSet());
            if (stone == null) stone = positions; else assertEquals(stone,positions);
        }
    }
    @Test void rampHasGroundToWallRouteAndRejectsMissingSupport() {
        var input = TemplateFixtures.input(SiteKind.RAMP,RAMP);
        var c = ramp.compile(input);
        assertFalse(c.voxels().isEmpty());
        assertTrue(new SupportGraph().reachable(c,new Point3i(-28,64,6),new Point3i(-28,84,4)));
        assertTrue(ramp.compile(TemplateFixtures.input(SiteKind.RAMP,RAMP,0,0,0,PaletteFamily.STONE,DecayState.INTACT,13)).voxels().isEmpty());
    }
    @Test void rotatedWallRetainsContinuousSixWidePath() {
        for (int angle : new int[] {0,15,30,45,60,90}) {
            var input = TemplateFixtures.input(SiteKind.WALL,WALL,0,0,angle,PaletteFamily.STONE,DecayState.INTACT,0);
            assertFalse(wall.compile(input).voxels().isEmpty(),"angle="+angle);
        }
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,20,31,32})
    void rampHeightBoundary(int height) {
        var input = TemplateFixtures.input(SiteKind.RAMP,RAMP,0,0,0,PaletteFamily.STONE,DecayState.INTACT,Math.max(0,height-20));
        assertFalse(ramp.compileBetween(input,84-height,84).voxels().isEmpty(),"height="+height);
    }
    @Test void allThirtyTwoHeightsKeepTheirRequiredLandingsAndHeadroom() {
        for (int height = 1; height <= 32; height++) {
            var input = TemplateFixtures.input(SiteKind.RAMP,RAMP,0,0,0,PaletteFamily.STONE,DecayState.INTACT,Math.max(0,height-20));
            var c = ramp.compileBetween(input,84-height,84);
            assertFalse(c.voxels().isEmpty(),"height="+height);
            var occupied = c.voxels().stream().filter(v -> v.kind()==IntentKind.SOLID)
                    .collect(Collectors.toMap(VoxelIntent::pos,v -> v));
            for (var route : c.requiredRoutes()) for (var feet : route) {
                assertTrue(occupied.containsKey(new Point3i(feet.x(),feet.y()-1,feet.z())),"floor "+feet);
                assertFalse(occupied.containsKey(feet),"feet "+feet);
                assertFalse(occupied.containsKey(new Point3i(feet.x(),feet.y()+1,feet.z())),"head "+feet);
            }
            for (int z=5;z<8;z++) for (int x=-29;x<-26;x++)
                assertTrue(occupied.containsKey(new Point3i(x,83-height,z)),"complete lower landing H="+height);
            if (height<=3) for (int z=4;z<11;z++) for (int x=-26+height;x<-23+height;x++)
                assertTrue(occupied.containsKey(new Point3i(x,83,z)),"complete upper landing H="+height);
        }
    }
}
