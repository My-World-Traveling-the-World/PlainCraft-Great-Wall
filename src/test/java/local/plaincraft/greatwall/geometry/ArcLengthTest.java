package local.plaincraft.greatwall.geometry;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ArcLengthTest {
    @Test void trueLengthAndBoundaryInterpolationAreExact() {
        var points = List.of(new Point2q(-768, -1024), new Point2q(0, 0), new Point2q(256, 0));
        var lengths = ArcLength.accumulate(points);
        assertEquals(List.of(0L, 1280L, 1536L), lengths);
        String hash = "a".repeat(64);
        var g = new SegmentGeometry(new SegmentKey(hash, "minecraft:overworld", 0), points, lengths, List.of(),
                Optional.empty(), new Boundary(0, points.getFirst(), 84, hash),
                new Boundary(1, points.getLast(), 84, hash), 0, 0, hash);
        assertEquals(points.getFirst(), ArcLength.pointAt(g, 0));
        assertEquals(new Point2q(-384, -512), ArcLength.pointAt(g, 640));
        assertEquals(points.get(1), ArcLength.pointAt(g, 1280));
        assertEquals(points.getLast(), ArcLength.pointAt(g, 1536));
        assertEquals(1, ArcLength.edgeAt(lengths, 1280));
        assertEquals(1, ArcLength.edgeAt(lengths, 1536));
        assertThrows(IllegalArgumentException.class, () -> ArcLength.edgeAt(lengths, -1));
        assertThrows(IllegalArgumentException.class, () -> ArcLength.edgeAt(lengths, 1537));
    }

    @Test void duplicatePointsCannotSilentlyChangeUIndices() {
        var p = new Point2q(1, 1);
        assertThrows(IllegalArgumentException.class, () -> ArcLength.accumulate(List.of(p, p)));
        assertThrows(UnsupportedOperationException.class, () -> ArcLength.accumulate(List.of(p, new Point2q(2, 1))).clear());
    }
}
