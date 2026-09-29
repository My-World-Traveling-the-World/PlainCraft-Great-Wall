package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class WaterFortTest {
    @Test void width49Crosses50And51AreRejectedWithoutWaterRise() {
        var template=new WaterGate(new DecayField(0),TemplateFixtures.PALETTE);
        for(int width:new int[]{49,50,51}) {
            var initial=TemplateFixtures.input(SiteKind.WATER_GATE,new Bounds(-34,-32,-6,35,3,6));
            var supports=new HashMap<>(initial.supports());
            supports.replaceAll((p,v)->p.x()>=-24 && p.x()<25?new ProbeResult(ProbeKind.WATER,60,OptionalInt.of(66),
                    OptionalInt.of(60),4,TemplateFixtures.HASH):v);
            var water=new WaterEvidence(new ArcInterval(296L*256,345L*256),width<50?WaterClass.NARROW:WaterClass.WIDE,
                    width*256L,49L*256,66,List.of(new Point3i(-25,64,0),new Point3i(25,64,0)),
                    List.of(new Point3i(4,60,0)),TemplateFixtures.HASH);
            var input=new ComponentInput(initial.site(),initial.stations(),supports,initial.decay(),initial.family(),false,List.of(water),List.of());
            var c=template.compile(input);
            if(width>=50) {assertTrue(c.voxels().isEmpty());continue;}
            assertFalse(c.voxels().isEmpty());
            assertTrue(c.voxels().stream().filter(v->v.part()==Part.WATER).allMatch(v->v.pos().y()>=60 && v.pos().y()<66
                    && "0".equals(v.state().properties().get("level"))));
            for(int x=-2;x<2;x++) for(int y=66;y<69;y++) for(int z=-5;z<5;z++) {
                var p=new Point3i(x,y,z);
                assertTrue(c.voxels().stream().anyMatch(v->v.pos().equals(p)&&v.kind()==IntentKind.OWNED_VOID));
            }
        }
    }
    @Test void fortStays48AndHasNoGameplayEntities() {
        var template=new FortPlaceholder(new DecayField(0),TemplateFixtures.PALETTE);
        var c=template.compile(TemplateFixtures.input(SiteKind.FORT,new Bounds(-24,-32,-24,24,8,24)));
        assertFalse(c.voxels().isEmpty());
        assertEquals(48,c.voxels().stream().map(v->v.pos().x()).distinct().count());
        assertEquals(48,c.voxels().stream().map(v->v.pos().z()).distinct().count());
        assertTrue(c.blockEntities().isEmpty());assertTrue(c.spawnSlots().isEmpty());
    }
}
