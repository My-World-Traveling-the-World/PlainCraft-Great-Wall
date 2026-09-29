package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class GroundExitCapVariantsTest {
    @Test void rejectedFirstOrientationDoesNotSkipTheRemainingCandidatesAtThisRetreat() {
        var input=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15));
        var template=new EndCap(new DecayField(0),TemplateFixtures.PALETTE);
        var first=template.compileAt(input);
        assertFalse(first.voxels().isEmpty());
        for(int acceptedIndex=0;acceptedIndex<4;acceptedIndex++) {
            int target=acceptedIndex;
            var examined=new ArrayList<ComponentVoxels>();
            var accepted=template.compileAt(input,c->{examined.add(c);return examined.size()==target+1;});
            assertEquals(target+1,examined.size());assertFalse(accepted.voxels().isEmpty());
            if(target>0)assertNotEquals(first.hash(),accepted.hash(),"the former first-only result cannot satisfy this certificate");
            var replay=template.compileAt(input,c->c.hash().equals(accepted.hash()));
            assertEquals(accepted,replay,"cold replay must select the certified orientation");
        }
        var rejected=new ArrayList<ComponentVoxels>();
        assertTrue(template.compileAt(input,c->{rejected.add(c);return false;}).voxels().isEmpty());
        assertEquals(4,rejected.size(),"exhaust all four orientations before retreating");
    }
    @Test void allFourCapExitsConnectToTheSameWallWithoutChangingRetreatSchedule() {
        var base=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15));
        for(int first=0;first<4;first++) {
            int target=first;var supports=new HashMap<>(base.supports());
            supports.replaceAll((p,v)->{
                int slot=(p.x()>=0?2:0)+(p.z()<0?1:0);
                boolean entrance=(p.x()>=-29&&p.x()<-26||p.x()>=26&&p.x()<29)&&(p.z()==6||p.z()==-7);
                return entrance&&slot<target?new ProbeResult(ProbeKind.NO_SUPPORT,0,OptionalInt.empty(),OptionalInt.empty(),64,TemplateFixtures.HASH):v;
            });
            var input=new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(),List.of());
            var c=new EndCap(new DecayField(0),TemplateFixtures.PALETTE).compileAt(input);
            assertFalse(c.voxels().isEmpty(),"candidate="+first);
            var ground=c.requiredRoutes().getLast().getFirst();
            assertEquals(first>=2,ground.x()>=0);assertEquals(first%2==1,ground.z()<0);
            assertTrue(new SupportGraph().reachable(c,c.requiredRoutes().getFirst().getFirst(),ground),"cap and ramp must share one route");
            assertTrue(c.voxels().stream().allMatch(v->v.pos().x()>=-30&&v.pos().x()<30&&v.pos().z()>=-15&&v.pos().z()<15));
        }
    }
}
