package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class WallDecayTest {
    @Test void oneCellRendersLowRuinsWithoutKeepingTheirSkyWalkway() {
        var input=TemplateFixtures.input(SiteKind.WALL,new Bounds(-32,-32,-5,32,3,5));
        long center=input.site().globalSQ8();
        var span=new ArcInterval(center-32*256,center+32*256);
        var samples=(java.util.function.LongFunction<DecaySample>)s->s<center
                ?new DecaySample(DecayState.INTACT,0,20):new DecaySample(DecayState.RUIN,42000,4);
        var c=new WallSection(new DecayField(0),TemplateFixtures.PALETTE).compileIntervals(input,List.of(span),
                List.of(new ArcInterval(span.fromQ8(),center)),samples);
        assertFalse(c.voxels().isEmpty());
        assertTrue(c.voxels().stream().anyMatch(v->v.pos().x()>0&&v.kind()==IntentKind.SOLID));
        assertTrue(c.voxels().stream().filter(v->v.pos().x()>=0&&v.kind()==IntentKind.SOLID).allMatch(v->v.pos().y()<68));
        assertTrue(c.voxels().stream().filter(VoxelIntent::walkable).allMatch(v->v.pos().x()<0));
        assertFalse(c.requiredRoutes().isEmpty());
    }
    @Test void finalPartialCellDoesNotDemandNonexistentFutureStations() {
        var input=TemplateFixtures.input(SiteKind.WALL,new Bounds(-32,-32,-5,32,3,5));
        long end=input.site().globalSQ8()+12*256;
        var stations=List.of(input.stations().getFirst(),new Station(end,new Point2q(12*256,0),84,0));
        var partial=new ComponentInput(input.site(),stations,input.supports(),input.decay(),input.family(),false,List.of(),List.of());
        var c=new WallSection(new DecayField(0),TemplateFixtures.PALETTE).compileIntervals(partial,
                List.of(new ArcInterval(input.site().globalSQ8()-32*256,end)));
        assertFalse(c.voxels().isEmpty());
        assertTrue(c.voxels().stream().allMatch(v->v.pos().x()<12));
    }
}
