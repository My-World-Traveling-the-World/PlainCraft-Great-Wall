package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Collectors;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class TemplateTest {
    private final DecayField decay=new DecayField(0);
    @Test void towerIs11Not12AndAllLevelsReachGround() {
        var template=new EnemyTower(decay,TemplateFixtures.PALETTE);
        var input=TemplateFixtures.input(SiteKind.ENEMY_TOWER,new Bounds(-5,-32,-5,6,8,6));
        var c=template.compile(input);
        assertFalse(c.voxels().isEmpty());
        assertEquals(11,c.voxels().stream().map(v->v.pos().x()).distinct().count());
        assertEquals(11,c.voxels().stream().map(v->v.pos().z()).distinct().count());
        assertEquals(3,c.spawnSlots().size());
        assertEquals(1,c.blockEntities().size());
    }
    @Test void beaconHasFourUnlitFiresOneIdentity() {
        var template=new BeaconTower(decay,TemplateFixtures.PALETTE);
        var c=template.compile(TemplateFixtures.input(SiteKind.BEACON,new Bounds(-5,-32,-5,5,7,5)));
        assertFalse(c.voxels().isEmpty());
        var fires=c.voxels().stream().filter(v->v.part()==Part.CAMPFIRE).toList();
        assertEquals(4,fires.size()); assertEquals(4,c.blockEntities().size());
        assertTrue(fires.stream().allMatch(v->"false".equals(v.state().properties().get("lit"))
                && "true".equals(v.state().properties().get("signal_fire"))));
        assertEquals(1,c.blockEntities().stream().map(e->e.fields().get("beaconOrdinal")).distinct().count());
        assertEquals(java.util.Set.of(0,1,2,3),c.blockEntities().stream().map(BlockEntityIntent::localId).collect(Collectors.toSet()));
    }
    @Test void ruinAndBuriedHaveNoFunctionalFireOrSpawn() {
        for(DecayState state:new DecayState[]{DecayState.RUIN,DecayState.BURIED}) {
            var input=TemplateFixtures.input(SiteKind.BEACON,new Bounds(-5,-32,-5,5,7,5),0,0,0,PaletteFamily.STONE,state,0);
            var c=new BeaconTower(decay,TemplateFixtures.PALETTE).compile(input);
            assertFalse(c.voxels().isEmpty());
            assertTrue(c.blockEntities().isEmpty()); assertTrue(c.spawnSlots().isEmpty());
            assertTrue(c.voxels().stream().noneMatch(v->v.part()==Part.CAMPFIRE));
            assertEquals(state,c.realizedState());
        }
    }
    @Test void allHeadingsKeepFourFireIdentitiesAndTowerRoutes() {
        for(int angle:new int[]{0,15,30,45,60,90}) {
            var beacon=new BeaconTower(decay,TemplateFixtures.PALETTE).compile(TemplateFixtures.input(
                    SiteKind.BEACON,new Bounds(-5,-32,-5,5,7,5),0,0,angle,PaletteFamily.STONE,DecayState.INTACT,0));
            assertFalse(beacon.voxels().isEmpty(),"beacon angle="+angle);
            assertEquals(4,beacon.voxels().stream().filter(v->v.part()==Part.CAMPFIRE).count(),"fire angle="+angle);
            assertEquals(4,beacon.blockEntities().size(),"identity angle="+angle);
            var tower=new EnemyTower(decay,TemplateFixtures.PALETTE).compile(TemplateFixtures.input(
                    SiteKind.ENEMY_TOWER,new Bounds(-5,-32,-5,6,8,6),0,0,angle,PaletteFamily.STONE,DecayState.INTACT,0));
            assertFalse(tower.voxels().isEmpty(),"tower angle="+angle);
        }
    }
}
