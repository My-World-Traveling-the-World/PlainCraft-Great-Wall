package local.plaincraft.greatwall.smoke;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import local.plaincraft.greatwall.lifecycle.RootSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RootBatchLedgerTest {
    @TempDir Path directory;

    @Test void everyBatchRetainsItsExactPrimitiveTimesAndDoesNotOverwrite() throws Exception {
        var ledger = new RootBatchLedger(directory, 2);
        ledger.append(100, 200, 75, 0); ledger.append(300, 50_000_300L, 1_000_000, 1);
        ledger.persist(new RootSearch.Progress(3, 144, 8, 0, 5, 28935, 50_000_000, 1), null);
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(directory.resolve("root-batches.bin.gz"))))) {
            assertEquals(RootBatchLedger.MAGIC, in.readInt()); assertEquals(1, in.readInt()); assertEquals(2, in.readLong());
            for (long expected : new long[] {100, 200, 75, 0, 300, 50_000_300L, 1_000_000, 1}) assertEquals(expected, in.readLong());
            assertEquals(-1, in.read());
        }
        assertThrows(IllegalStateException.class, () -> ledger.persist(null, null));
    }
    @Test void exhaustionAndInvalidTimesAreVisibleRatherThanDroppingBatches() {
        var ledger = new RootBatchLedger(directory, 1);
        assertThrows(IllegalArgumentException.class, () -> ledger.append(200, 100, 0, 0));
        ledger.append(100, 200, 20, 0);
        assertThrows(IllegalStateException.class, () -> ledger.append(300, 400, 20, 0));
    }
}
