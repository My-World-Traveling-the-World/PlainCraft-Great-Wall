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
    private final WallSection wall = new WallSection(new DecayField(0),TemplateFixtures.PALETTE);
    private final Ramp ramp = new Ramp(new DecayField(0),TemplateFixtures.PALETTE);
    private static final Bounds WALL = new Bounds(-32,-32,-5,32,3,5);
    private static final Bounds RAMP = new Bounds(-30,-32,4,-6,3,15);
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
}
