package local.plaincraft.greatwall.component;

import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportedGateGeometryTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void actualGateConnectsBothWallEndsAndKeepsGroundPassageSeparate() {
        var gate = ImportedGateGeometry.gate();
        var route = gate.walkRoute();
        // 东西端相隔 112 格；至少 113 站才能连通，空路和只返回两端都不准通过。
        assertTrue(route.size() >= 113);
        assertEquals(new Point3i(0, 15, 19), route.getFirst());
        assertEquals(new Point3i(112, 15, 19), route.getLast());
        for (int i = 1; i < route.size(); i++) {
            var p = route.get(i - 1);
            var q = route.get(i);
            assertEquals(p.y(), q.y());
            assertEquals(1, Math.abs(p.x() - q.x()) + Math.abs(p.z() - q.z()));
        }
        assertEquals(new Bounds(54, 0, 0, 59, 5, 32), gate.roadVoid());
    }

    @Test void quarterTurnTranslatesBothSeamsAndRoadAtTheRightHeights() {
        var gate = ImportedGateGeometry.gate();
        var worldFeet = new Point3i(-19, 81, 29);
        var route = gate.walkRoute();
        // 使用负坐标及非原点高度，让轴次序、符号和脚底偏移的错误都能显现。
        assertEquals(new Point3i(-19, 81, -27), gate.place(route.getFirst(), worldFeet, Rotation.CLOCKWISE_90));
        assertEquals(new Point3i(-19, 81, 85), gate.place(route.getLast(), worldFeet, Rotation.CLOCKWISE_90));
        assertEquals(new Point3i(0, 66, 29), gate.place(new Point3i(56, 0, 0), worldFeet, Rotation.CLOCKWISE_90));
    }
}
