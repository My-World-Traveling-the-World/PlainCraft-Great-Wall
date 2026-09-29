package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BoundedByteArrayOutputStreamTest {
    @Test void capacityNeverRoundsPastNonPowerOfTwoLimit() {
        var out = new BoundedByteArrayOutputStream(10001);
        out.write(new byte[8192], 0, 8192);
        out.write(7);
        assertEquals(10001, out.capacity());
        out.writeBytes(new byte[1808]);
        assertEquals(10001, out.size());
        assertThrows(IllegalArgumentException.class, () -> out.write(9));
        assertEquals(10001, out.capacity());
        assertEquals(10001, out.size());
    }

    @Test void bulkOverflowFailsBeforeGrowthAndPreservesPreviousContents() {
        var out = new BoundedByteArrayOutputStream(9000);
        out.write(3);
        assertThrows(IllegalArgumentException.class, () -> out.writeBytes(new byte[9000]));
        assertEquals(8192, out.capacity());
        assertArrayEquals(new byte[]{3}, out.toByteArray());
        assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[1], 0, 2));
    }
}
