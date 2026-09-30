package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ProtectionSnapshotTest {
    @Test void immutableHalfOpenProtectionPreservesTallCoordinates() {
        var boxes=new ArrayList<>(List.of(new Bounds(-2,5998,-3,3,6005,2)));
        var entities=new HashSet<>(Set.of(new Point3i(9,6000,-5)));
        var immutable=new HashSet<>(Set.of(new Point3i(8,6001,-6)));
        var snapshot=ProtectionSnapshot.freeze(boxes,entities,immutable);
        boxes.clear();entities.clear();immutable.clear();
        assertTrue(snapshot.protectedAt(new Point3i(-2,5998,-3)));
        assertTrue(snapshot.protectedAt(new Point3i(2,6004,1)));
        assertFalse(snapshot.protectedAt(new Point3i(3,6004,1)));
        assertFalse(snapshot.protectedAt(new Point3i(2,6005,1)));
        assertTrue(snapshot.protectedAt(new Point3i(9,6000,-5)));
        assertTrue(snapshot.protectedAt(new Point3i(8,6001,-6)));
        assertThrows(UnsupportedOperationException.class,()->snapshot.structures().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.blockEntities().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.immutableBlocks().clear());
    }

    @Test void orderingAndDuplicateBoxesDoNotChangeHashButTamperingFails() {
        var a=new Bounds(-16,0,0,0,80,16);var b=new Bounds(0,0,0,16,80,16);
        var entities=Set.of(new Point3i(1,74,2),new Point3i(-1,6000,-2));
        var first=ProtectionSnapshot.freeze(List.of(b,a,a),entities,Set.of());
        var second=ProtectionSnapshot.freeze(List.of(a,b),entities,Set.of());
        assertEquals(first,second);
        assertThrows(WallFailure.class,()->new ProtectionSnapshot(first.structures(),Set.of(),Set.of(),first.hash()));
    }
}
