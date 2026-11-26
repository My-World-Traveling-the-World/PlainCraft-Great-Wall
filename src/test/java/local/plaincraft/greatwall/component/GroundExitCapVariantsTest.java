package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class GroundExitCapVariantsTest {
    /** 拒绝水中出口时不分配主体；有效陆地正对照证明palette计数接在真实编译链上。 */
    @Test void waterExitRejectsBeforeBodyAllocationAndPaletteWorkInEveryState() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        PaletteBinding palette=(i,p,l,w,s,h)->{calls.incrementAndGet();return TemplateFixtures.PALETTE.resolve(i,p,l,w,s,h);};
        var template=new EndCap(new DecayField(0),palette);
        var land=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15));
        assertFalse(template.compileAt(land).voxels().isEmpty());assertTrue(calls.get()>0);
        for(var state:DecayState.values()) {
            var base=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15),0,0,0,PaletteFamily.STONE,state,0);
            var supports=new HashMap<>(base.supports());
            supports.replaceAll((p,v)->new ProbeResult(ProbeKind.WATER,64,OptionalInt.of(65),OptionalInt.empty(),0,TemplateFixtures.HASH));
            var water=new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(),List.of());
            calls.set(0);
            try(var budget=new local.plaincraft.greatwall.diagnostics.AllocationBudget(256*1024)) {
                var result=local.plaincraft.greatwall.diagnostics.CompiledAllocation.run(budget,
                        ()->local.plaincraft.greatwall.diagnostics.CompiledAllocation.component(()->template.compileAt(water)),
                        local.plaincraft.greatwall.diagnostics.CompiledAllocation::bytes);
                assertTrue(result.voxels().isEmpty());assertEquals(0,calls.get(),state.toString());
                assertTrue(budget.stats().peak()<256*1024);
            }
        }
    }

    /** 在移动入口检查之前冻结四个朝向的完整产物hash，不能用新实现自我回放作等价证据。 */
    @Test void validOrientationGeometryMatchesFrozenImplementation() {
        var input=TemplateFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15));
        var hashes=new ArrayList<String>();
        new EndCap(new DecayField(0),TemplateFixtures.PALETTE).compileAt(input,c->{hashes.add(c.hash().toString());return false;});
        assertEquals(4,hashes.size());
        assertEquals(List.of("c14be916cd72fafb78f59fa1dfdbe5262999e20025f870ee3c8e852a6e911e7e","abcac8c9509e4e5df47964db58733d7eea6a75f4de77c83dd89a957ab15e83a3","6de6dd3b1ecbd034896fe34bacf29db16b5b6e4ebcbe93082391efa040fab2b8","35535eb3d2e31e16f2d7ef58871874d448fafa4d3d562d5a1daf13313cb9fb43"),hashes);
    }

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
