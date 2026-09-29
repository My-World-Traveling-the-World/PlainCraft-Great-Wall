package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.SupportGraph;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class GateCapTest {
    @Test void nonWalkableCapStatesRetainRuinsWithoutReadingANonexistentRoute() {
        for(var state:java.util.List.of(DecayState.RUIN,DecayState.BURIED)) {
            var input=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15),
                    0,0,0,PaletteFamily.STONE,state,0);
            var cap=assertDoesNotThrow(()->new EndCap(new DecayField(0),TemplateFixtures.PALETTE).compile(input));
            assertFalse(cap.voxels().isEmpty(),"visible rooted ruins must not be discarded");
            assertTrue(cap.requiredRoutes().isEmpty());
            assertTrue(cap.voxels().stream().noneMatch(VoxelIntent::walkable));
            assertTrue(cap.blockEntities().isEmpty());
            // This is the old accessor, retained as a concrete counterexample to the old assumption.
            assertThrows(java.util.NoSuchElementException.class,()->cap.requiredRoutes().getFirst());
        }
    }
    @Test void filledFoundationsDoNotPretendTheirTopIsNaturalGround() {
        for(int drop:new int[]{4,5,12}){
            var input=TemplateFixtures.input(SiteKind.GATE,new Bounds(-30,-33,-15,30,4,15),0,0,0,PaletteFamily.STONE,DecayState.INTACT,drop);
            var c=new Gate(new DecayField(0),TemplateFixtures.PALETTE).compile(input);
            assertFalse(c.voxels().isEmpty());
            assertTrue(new SupportGraph().reachable(c,new Point3i(0,64-drop,-5),new Point3i(0,64-drop,4)));
            var capInput=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-5,1,3,15),0,0,0,PaletteFamily.STONE,DecayState.INTACT,drop);
            var cap=new EndCap(new DecayField(0),TemplateFixtures.PALETTE).compile(capInput);
            assertFalse(cap.voxels().isEmpty());
            assertTrue(new SupportGraph().reachable(cap,new Point3i(-9,84,0),new Point3i(-28,64-drop,6)));
        }
    }
    @Test void capRetreatFollowsBendAndHeightInsteadOfOriginalTangent() {
        var input=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-5,1,3,15));
        var stations=java.util.List.of(new Station(0,new Point2q(0,0),80,0),
                new Station(32*256,new Point2q(32*256,0),84,0),
                new Station(64*256,new Point2q(32*256,32*256),88,0));
        var site=new SiteRecord(input.site().id(),48*256,new Frame(new Point2q(32*256,16*256),0,1<<30,86,false),
                input.site().bounds(),SiteState.PLANNED,java.util.Optional.empty(),java.util.Optional.empty());
        var candidates=EndCap.candidates(site,stations);
        var at24=candidates.get(6);
        assertEquals(new Point2q(24*256,0),at24.frame().center());
        assertEquals(83,at24.frame().walkY());
        assertEquals(1<<30,at24.frame().txQ30());
        // Original translation incorrectly places this retry at (32,-8), still at W=86.
        assertNotEquals(local.plaincraft.greatwall.geometry.Projection.local(site.frame(),-24*256,0),at24.frame().center());
        var reverse=new SiteRecord(site.id(),site.globalSQ8(),new Frame(site.frame().center(),0,-(1<<30),86,true),
                site.bounds(),site.state(),site.absorbedBy(),site.reason());
        var downstream=EndCap.candidates(reverse,stations).get(1);
        assertEquals(52*256,downstream.globalSQ8());
        assertEquals(new Point2q(32*256,20*256),downstream.frame().center());
        assertEquals(86,downstream.frame().walkY()); // Frozen roundDiv resolves exact halves downward.
        assertEquals(87,EndCap.candidates(reverse,stations).get(2).frame().walkY());
    }
    @Test void rampsNeverBlockGateApproach() {
        var input=TemplateFixtures.input(SiteKind.GATE,new Bounds(-30,-33,-15,30,4,15));
        var gate=new Gate(new DecayField(0),TemplateFixtures.PALETTE);
        var c=gate.compile(input); assertFalse(c.voxels().isEmpty());
        assertTrue(new SupportGraph().reachable(c,new Point3i(0,64,-5),new Point3i(0,64,4)));
        assertTrue(new SupportGraph().reachable(c,new Point3i(-28,64,6),new Point3i(-28,84,0)));
        assertTrue(new SupportGraph().reachable(c,new Point3i(-28,64,-7),new Point3i(-28,84,0)));
        assertEquals(c,gate.compileAtGround(input,64));
    }
    @Test void capHasGroundEgressAndDoesNotKeepAnIsolatedTip() {
        var input=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-5,1,3,15));
        var c=new EndCap(new DecayField(0),TemplateFixtures.PALETTE).compile(input);
        assertFalse(c.voxels().isEmpty());
        assertTrue(new SupportGraph().reachable(c,new Point3i(-9,84,0),new Point3i(-28,64,6)));
        assertTrue(c.voxels().stream().filter(VoxelIntent::walkable).allMatch(v->v.pos().x()< -8));
    }
}
