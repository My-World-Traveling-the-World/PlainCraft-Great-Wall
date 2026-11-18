package local.plaincraft.greatwall.terrain;

import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import local.plaincraft.greatwall.component.GroundEntranceContract;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 不用时间阈值冒充守卫，验证真实坐标查询、独立所有权及跨cell零复制契约。 */
class FoundationFloorSnapshotTest {
    @Test void collisionAndNegativeCoordinatesMatchWithoutMutableAliases() {
        var source=new HashMap<Long,Integer>();
        for(int x=-64;x<64;x++)for(int z=-64;z<64;z++)source.put(key(x,z),x-z);
        var expected=new HashMap<>(source);
        var snapshot=FoundationFloorSnapshot.copyOf(Collections.unmodifiableMap(source));
        assertEquals(16384,snapshot.size());
        expected.forEach((k,v)->assertEquals(v,snapshot.get(k)));
        source.clear();
        assertEquals(expected,snapshot,"外部只读包装也可能有可变底表，必须复制");
        assertSame(snapshot,FoundationFloorSnapshot.copyOf(snapshot),"cell复用不能再次构建全表");
        assertThrows(UnsupportedOperationException.class,()->snapshot.put(1L,2));
        assertThrows(UnsupportedOperationException.class,()->snapshot.entrySet().iterator().next().setValue(2));
        assertThrows(UnsupportedOperationException.class,()->snapshot.entrySet().iterator().remove());
    }

    @Test void nullEntriesStillRejectRatherThanBecomingMissingTerrain() {
        var source=new HashMap<Long,Integer>();source.put(null,1);
        assertThrows(NullPointerException.class,()->FoundationFloorSnapshot.copyOf(source));
        source.clear();source.put(1L,null);
        assertThrows(NullPointerException.class,()->FoundationFloorSnapshot.copyOf(source));
        assertThrows(NullPointerException.class,()->FoundationFloorSnapshot.copyOf(null));
    }

    @Test void realizationInputKeepsTrustedSnapshotButCopiesExternalMap() {
        var external=new HashMap<Long,Integer>();external.put(key(-2,7),42);
        var first=input(external);
        assertInstanceOf(FoundationFloorSnapshot.class,first.foundationFloor(),"Input必须保留抗坐标聚集的表实现");
        external.put(key(-2,7),99);
        assertEquals(42,first.foundationFloor().get(key(-2,7)));
        var second=input(first.foundationFloor());
        assertSame(first.foundationFloor(),second.foundationFloor(),"真实Input不能改回Map.copyOf重建退化表");
        assertThrows(UnsupportedOperationException.class,()->second.foundationFloor().put(0L,1));
    }

    @Test void componentsReuseTheRealizationSnapshotWithoutAliasingExternalInput() {
        var key = key(-2, 7);
        var external = new HashMap<>(Map.of(key, 42));
        var shared = input(external).foundationFloor();
        var first = component(shared);
        var second = component(external);
        external.put(key, 91);
        // 同一次取证编译多个构件时，不应为每个构件重建整条走廊的坐标表。
        assertSame(shared, first.foundationFloor());
        assertEquals(42, second.foundationFloor().get(key));
        assertThrows(UnsupportedOperationException.class, () -> second.foundationFloor().put(key, 91));
    }

    private static ComponentInput component(Map<Long, Integer> floor) {
        var site = new SiteRecord(new SiteId("fixture", SiteKind.WALL, 0), 0,
                new Frame(new Point2q(0, 0), 1 << 30, 0, 84, false), new Bounds(0, 0, 0, 1, 1, 1),
                SiteState.PLANNED, Optional.empty(), Optional.empty());
        return new ComponentInput(site, List.of(), Map.of(), new DecaySample(DecayState.INTACT, 0, 20),
                PaletteFamily.STONE, false, List.of(), List.of(), GroundEntranceContract.Evidence.analytic(Map.of()),
                floor, List.of());
    }

    private static RealizationPlanner.Input input(java.util.Map<Long,Integer> floor) {
        String hash="a".repeat(64);
        return new RealizationPlanner.Input(
                new local.plaincraft.greatwall.model.WallTypes.CellKey(hash,"minecraft:overworld",0),
                new local.plaincraft.greatwall.model.WallTypes.PlanWindow(java.util.List.of()),
                java.util.Map.of(),java.util.Map.of(),java.util.List.of(),java.util.Map.of(),0,hash,
                floor,java.util.List.of());
    }

    private static long key(int x,int z) { return ((long)x<<32)^(z&0xffffffffL); }
}
