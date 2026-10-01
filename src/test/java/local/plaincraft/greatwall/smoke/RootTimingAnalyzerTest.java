package local.plaincraft.greatwall.smoke;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import local.plaincraft.greatwall.lifecycle.RootSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RootTimingAnalyzerTest {
    @TempDir Path directory;
    @Test void overlappingNestedPausesAreCountedOnceAndClippedToBatch() {
        assertEquals(70, RootTimingAnalyzer.overlap(100, 200, List.of(
                new RootTimingAnalyzer.Span(180, 250), new RootTimingAnalyzer.Span(90, 150),
                new RootTimingAnalyzer.Span(110, 120), new RootTimingAnalyzer.Span(300, 400))));
    }
    @Test void absentOrMerelyTouchingPausesDoNotExplainSlowNativeCpu() {
        assertEquals(0, RootTimingAnalyzer.overlap(100, 200, List.of()));
        assertEquals(0, RootTimingAnalyzer.overlap(100, 200, List.of(new RootTimingAnalyzer.Span(200, 300))));
        assertThrows(IllegalArgumentException.class, () -> new RootTimingAnalyzer.Span(200, 100));
    }
    @Test void actualJfrClockAnchorJoinsExactBatchLedgerWithoutDroppingRows() throws Exception {
        try (var recording = new Recording()) {
            recording.enable(RootBatchLedger.AnchorEvent.class);
            recording.start();
            var ledger = new RootBatchLedger(directory, 1);
            long a = System.nanoTime(), b = System.nanoTime();
            ledger.append(a, b, 0, 0);
            ledger.persist(new RootSearch.Progress(3, 144, 8, 0, 5, 1, b - a, 0), null);
            recording.stop(); recording.dump(directory.resolve("root.jfr"));
        }
        RootTimingAnalyzer.main(new String[] {directory.toString()});
        String report = Files.readString(directory.resolve("root-timing-analysis.json"));
        assertTrue(report.contains("A_OBSERVED_NO_ROOT_OVERRUN"));
        assertTrue(report.contains("\"batches\": 1"));
        assertTrue(Files.size(directory.resolve("root-batches.csv.gz")) > 0);
    }
}
