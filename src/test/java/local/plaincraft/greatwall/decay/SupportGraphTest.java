package local.plaincraft.greatwall.decay;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class SupportGraphTest {
    private static final SiteId ID = new SiteId("test", SiteKind.ENEMY_TOWER, 1);
    private static VoxelIntent stone(int x, int y, int z, boolean required) {
        return new VoxelIntent(new Point3i(x,y,z), IntentKind.SOLID, Part.CORE,
                new BlockSpec("minecraft:stone", Map.of()), ID, 600, required, false);
    }
    private static ComponentVoxels component(List<VoxelIntent> v, List<List<Point3i>> routes) {
        return new ComponentVoxels(ID, new Bounds(-4,-4,-4,8,10,8), v, List.of(), List.of(), routes, "0".repeat(64));
    }

    @Test void removesFloatingIslandsButRejectsRequiredIsland() {
        var graph = new SupportGraph();
        var c = component(List.of(stone(0,0,0,true), stone(0,1,0,true), stone(5,5,5,false)), List.of());
        var roots = Set.of(new Point3i(0,0,0));
        assertEquals(2, graph.validate(c, roots).voxels().size());
        assertThrows(WallFailure.class, () -> graph.validate(component(List.of(stone(5,5,5,true)), List.of()), roots));
    }

    @Test void routesNeedSupportTwoBlockHeadroomAndOneBlockRise() {
        var graph = new SupportGraph();
        var c = component(List.of(stone(0,0,0,true), stone(1,0,0,true), stone(1,1,0,true)), List.of());
        assertTrue(graph.reachable(c, new Point3i(0,1,0), new Point3i(1,2,0)));
        assertFalse(graph.reachable(c, new Point3i(0,1,0), new Point3i(4,2,0)));
        var blocked = component(List.of(stone(0,0,0,true), stone(1,0,0,true), stone(1,2,0,true)), List.of());
        assertFalse(graph.reachable(blocked, new Point3i(0,1,0), new Point3i(1,1,0)));
    }

    @Test void gravityCannotBeHeldFromSideAndDuplicateVoxelsFailClosed() {
        var sand = new VoxelIntent(new Point3i(1,1,0), IntentKind.SOLID, Part.SKIN,
                new BlockSpec("minecraft:sand", Map.of()), ID, 600, false, false);
        var graph = new SupportGraph();
        assertEquals(2, graph.validate(component(List.of(stone(0,0,0,true), stone(0,1,0,true), sand), List.of()),
                Set.of(new Point3i(0,0,0))).voxels().size());
        assertThrows(WallFailure.class, () -> graph.validate(component(List.of(stone(0,0,0,true),stone(0,0,0,true)), List.of()), Set.of()));
    }
}
