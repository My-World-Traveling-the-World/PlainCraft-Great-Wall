import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Cached-JVM diagnostic only. Does not stand in for the locked Gradle test task. */
public final class RunWalkwayCollisionTests {
    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(
                        "local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheckTest")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(output);
        summary.printFailuresTo(output);
        output.println("CACHED_JVM_DIAGNOSTIC_ONLY_NOT_STRICT_GRADLE_ACCEPTANCE");
        if (summary.getTestsFoundCount() != 9 || summary.getTestsSucceededCount() != 9
                || summary.getTestsSkippedCount() != 0 || summary.getTotalFailureCount() != 0) {
            throw new IllegalStateException("incomplete/failed diagnostic JUnit run");
        }
    }
}
