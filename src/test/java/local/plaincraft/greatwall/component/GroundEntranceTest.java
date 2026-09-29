package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class GroundEntranceTest {
    @Test void nominalGroundFormulaIsNotAnExitWhenFoundationDropIsNonzero() {
        for(int drop:new int[]{4,5,12}) {
            int walkY=84,naturalGround=walkY-20-drop,frozenEntrance=walkY-20;
            assertTrue(frozenEntrance-naturalGround>1,"the former entrance requires a jump higher than one block");
        }
        assertEquals(-33,-20-12-1,"lowest legal landing needs one supporting floor layer below -32");
    }
    @Test void enemyTowerHasARealGroundExitForEverySupportedFoundationDrop() {
        var template=new EnemyTower(new DecayField(0),TemplateFixtures.PALETTE);
        for(int drop=0;drop<=12;drop++) {
            var input=TemplateFixtures.input(SiteKind.ENEMY_TOWER,new Bounds(-5,-33,-5,6,8,6),0,0,0,
                    PaletteFamily.STONE,DecayState.INTACT,drop);
            assertGroundExit(template.compile(input),64-drop,"tower drop="+drop);
        }
    }
    @Test void fortCourtyardAndBothInternalRampsMeetNaturalGround() {
        var template=new FortPlaceholder(new DecayField(0),TemplateFixtures.PALETTE);
        for(int drop=0;drop<=12;drop++) {
            var input=TemplateFixtures.input(SiteKind.FORT,new Bounds(-24,-33,-24,24,8,24),0,0,0,
                    PaletteFamily.STONE,DecayState.INTACT,drop);
            var c=template.compile(input);assertGroundExit(c,64-drop,"fort drop="+drop);
            for(var route:c.requiredRoutes())assertEquals(64-drop,route.getFirst().y());
        }
    }
    private static void assertGroundExit(ComponentVoxels c,int ground,String label) {
        assertFalse(c.voxels().isEmpty(),label);
        var exits=c.requiredRoutes().stream().flatMap(List::stream).filter(p->p.y()==ground).toList();
        assertFalse(exits.isEmpty(),label+" has no route at natural ground");
        var solids=new HashSet<Point3i>();
        c.voxels().stream().filter(v->v.kind()==IntentKind.SOLID).forEach(v->solids.add(v.pos()));
        var footprint=new HashSet<Point3i>();
        c.voxels().forEach(v->footprint.add(new Point3i(v.pos().x(),0,v.pos().z())));
        assertTrue(exits.stream().anyMatch(p->List.of(new Point3i(p.x()-1,p.y(),p.z()),new Point3i(p.x()+1,p.y(),p.z()),
                new Point3i(p.x(),p.y(),p.z()-1),new Point3i(p.x(),p.y(),p.z()+1)).stream().anyMatch(q->
                        !footprint.contains(new Point3i(q.x(),0,q.z()))
                                &&!solids.contains(q))),label+" must reach the exterior, not just a basement floor");
        var graph=new SupportGraph();
        var wall=c.requiredRoutes().stream().flatMap(List::stream).filter(p->p.y()==84).findFirst().orElseThrow();
        for(var exit:exits)assertTrue(graph.reachable(c,wall,exit),label+" ground must connect to wall walk");
    }
}
