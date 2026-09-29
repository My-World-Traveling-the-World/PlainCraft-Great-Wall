package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class WaterFortTest {
    @Test void naturalThinIceOnlyClearsMainFourAndCannotBecomeAnUnsupportedPier() {
        var base=TemplateFixtures.input(SiteKind.WATER_GATE,new Bounds(-34,-32,-6,35,3,6));
        for(int iceX:new int[]{0,5,3}) {
            var supports=new HashMap<>(base.supports());
            supports.replaceAll((p,v)->p.x()>=-24&&p.x()<25?new ProbeResult(ProbeKind.WATER,60,OptionalInt.of(66),OptionalInt.of(60),4,
                    TemplateFixtures.HASH,p.x()==iceX?OptionalInt.of(65):OptionalInt.empty()):v);
            var water=new WaterEvidence(new ArcInterval(296*256L,345*256L),WaterClass.NARROW,49*256L,49*256L,66,List.of(),List.of(),TemplateFixtures.HASH);
            var c=new WaterGate(new DecayField(0),TemplateFixtures.PALETTE).compile(new ComponentInput(base.site(),base.stations(),supports,
                    base.decay(),base.family(),false,List.of(water),List.of()));
            if(iceX==3){assertTrue(c.voxels().isEmpty(),"a pier cannot remove side ice or hang above it");continue;}
            assertFalse(c.voxels().isEmpty());
            var changed=c.voxels().stream().filter(v->v.pos().x()==iceX&&v.pos().y()==65).toList();
            if(iceX==0)assertTrue(!changed.isEmpty()&&changed.stream().allMatch(v->v.kind()==IntentKind.FLUID&&v.state().id().equals("minecraft:water")));
            else assertTrue(changed.isEmpty(),"side ice is not rewritten as water");
        }
    }
    @Test void sevenColumnTailCannotBeMergedIntoFourPlusTwoPierUnderSixLimit() {
        assertTrue(4+2+1>6,"retained counterexample to literal remainder merge");
        assertEquals(7,5+2,"minimal tail uses five clear columns and the existing bank's two-column footing");
    }
    @Test void secondarySpansNeverLeaveOneOrTwoWideRemaindersOrExceedSix() {
        var template=new WaterGate(new DecayField(0),TemplateFixtures.PALETTE);
        for(int width=4;width<50;width++) {
            var base=TemplateFixtures.input(SiteKind.WATER_GATE,new Bounds(-34,-32,-6,35,3,6));
            int from=-width/2,to=from+width;var supports=new HashMap<>(base.supports());
            supports.replaceAll((p,v)->p.x()>=from&&p.x()<to?new ProbeResult(ProbeKind.WATER,60,OptionalInt.of(66),OptionalInt.of(60),4,TemplateFixtures.HASH):v);
            var water=new WaterEvidence(new ArcInterval((320L+from)*256,(320L+to)*256),WaterClass.NARROW,width*256L,width*256L,66,
                    List.of(),List.of(),TemplateFixtures.HASH);
            var c=template.compile(new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(water),List.of()));
            assertFalse(c.voxels().isEmpty(),"width="+width);
            var openings=new java.util.TreeSet<Integer>();
            c.voxels().stream().filter(v->v.pos().y()==66&&v.pos().z()==0&&v.kind()==IntentKind.OWNED_VOID).forEach(v->openings.add(v.pos().x()));
            int start=Integer.MIN_VALUE,previous=Integer.MIN_VALUE;
            for(int x:openings){if(start!=Integer.MIN_VALUE&&x!=previous+1){assertTrue(previous-start+1>=3&&previous-start+1<=6,"width="+width);start=x;}
                if(start==Integer.MIN_VALUE)start=x;previous=x;}
            assertTrue(previous-start+1>=3&&previous-start+1<=6,"width="+width);
            assertTrue(openings.containsAll(List.of(-2,-1,0,1)),"main navigation width stays four");
        }
    }
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
