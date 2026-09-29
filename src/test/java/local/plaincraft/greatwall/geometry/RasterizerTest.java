package local.plaincraft.greatwall.geometry;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class RasterizerTest {
    @Test void inverseUsesCentersAndMirrorsExactlyOnce() {
        var frame = new Frame(new Point2q(0, 0), 1 << 30, 0, 6000, false);
        assertEquals(new Point3i(-1, -20, -5), Rasterizer.localCell(frame, new Point3i(-1, 5980, -5)));
        var mirrored = new Frame(frame.center(), frame.txQ30(), 0, 6000, true);
        assertEquals(new Point3i(-1, -20, 4), Rasterizer.localCell(mirrored, new Point3i(-1, 5980, -5)));
        var rotated = new Frame(new Point2q(0, 0), 0, 1 << 30, 6000, false);
        assertEquals(new Point3i(2, 0, -2), Rasterizer.localCell(rotated, new Point3i(1, 6000, 2)));
    }

    @Test void transformedBoundsContainAllInverseSelectedCells() {
        var local = new Bounds(-5, -32, -5, 6, 7, 6);
        for (int angle : new int[] {0, 15, 30, 45, 60, 90}) for (boolean mirror : new boolean[] {false, true}) {
            double radians = angle * StrictMath.PI / 180;
            var frame = new Frame(new Point2q(-1_000_000L * 256 + 37, 1_000_000L * 256 + 19),
                    (int) StrictMath.round(StrictMath.cos(radians) * (1L << 30)),
                    (int) StrictMath.round(StrictMath.sin(radians) * (1L << 30)), 6000, mirror);
            Bounds b = Rasterizer.worldBounds(frame, local);
            assertEquals(5968, b.minY()); assertEquals(6007, b.maxYExclusive());
            int count = 0;
            for (int z = b.minZ() - 2; z < b.maxZExclusive() + 2; z++)
                for (int x = b.minX() - 2; x < b.maxXExclusive() + 2; x++) {
                    var p = Rasterizer.localCell(frame, new Point3i(x, 6000, z));
                    if (p.x() >= -5 && p.x() < 6 && p.z() >= -5 && p.z() < 6) {
                        count++; assertTrue(x >= b.minX() && x < b.maxXExclusive() && z >= b.minZ() && z < b.maxZExclusive());
                    }
                }
            assertTrue(count >= 115 && count <= 128);
        }
    }

    @Test void supercoverIncludesCornerSidesAndIsReversible() {
        var a = new Point2q(128, 128); var b = new Point2q(640, 640);
        var cells = Rasterizer.supercover(a, b, 6000);
        assertEquals(7, cells.size());
        assertTrue(cells.containsAll(List.of(new Point3i(1, 6000, 0), new Point3i(0, 6000, 1))));
        assertEquals(cells, Rasterizer.supercover(b, a, 6000));
        assertEquals(Rasterizer.supercover(new Point2q(-512, -256), new Point2q(512, -256), 0),
                Rasterizer.supercover(new Point2q(512, -256), new Point2q(-512, -256), 0));
    }

    @Test void boundaryLineCoversBothSidesAndTraversalIsBounded() {
        var result = Rasterizer.supercover(new Point2q(0, 128), new Point2q(0, 640), 0);
        assertEquals(6, result.size());
        assertThrows(IllegalArgumentException.class,
                () -> Rasterizer.supercover(new Point2q(0, 0), new Point2q(1_000_000L * 256, 0), 0));
    }

    @Test void ownersUseFloorDivisionAndExclusiveMaximum() {
        assertEquals(List.of(new ChunkKey("minecraft:overworld", -1, -1)),
                Rasterizer.owners("minecraft:overworld", new Bounds(-16, 0, -16, 0, 1, 0)));
        assertEquals(4, Rasterizer.owners("minecraft:overworld", new Bounds(-1, 0, -1, 1, 1, 1)).size());
        assertThrows(IllegalArgumentException.class,
                () -> Rasterizer.owners("minecraft:overworld", new Bounds(0, 0, 0, 257 * 16, 1, 16)));
    }
}
