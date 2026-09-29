package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.SupportGraph;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class GateCapTest {
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
