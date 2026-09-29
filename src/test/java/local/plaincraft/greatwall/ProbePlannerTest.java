package local.plaincraft.greatwall;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.lifecycle.BootstrapState;
import local.plaincraft.greatwall.planning.ProbePlanner;
import org.junit.jupiter.api.Test;

final class ProbePlannerTest {
    private static final ProbePlanner.Key KEY = new ProbePlanner.Key("minecraft:overworld", 0);

    @Test void hundredWaitersComputeOnceAndCancelIndependently() throws Exception {
        try (var planner = new ProbePlanner(42, Duration.ofMillis(500), ProbePlanner.Outcome.SUCCESS);
             var requesters = Executors.newFixedThreadPool(16)) {
            var submissions = new ArrayList<CompletableFuture<CompletableFuture<ProbePlanner.Plan>>>();
            for (int i = 0; i < 100; i++) {
                submissions.add(CompletableFuture.supplyAsync(() -> planner.require(KEY), requesters));
            }
            var requests = new ArrayList<CompletableFuture<ProbePlanner.Plan>>();
            for (var submission : submissions) requests.add(submission.get(2, TimeUnit.SECONDS));
            requests.getFirst().cancel(false);
            String hash = requests.get(1).get(3, TimeUnit.SECONDS).hash();
            for (var request : requests.subList(1, requests.size())) assertEquals(hash, request.get(3, TimeUnit.SECONDS).hash());
            assertTrue(requests.getFirst().isCancelled());
            assertEquals(1L, planner.stats().get("computations"));
        }
    }

    @Test void startupPumpDoesNotNeedNormalTicks() throws Exception {
        for (int delay : new int[]{1, 100, 2000}) {
            try (var planner = new ProbePlanner(42, Duration.ofMillis(delay), ProbePlanner.Outcome.SUCCESS)) {
                assertNotNull(planner.require(KEY).get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapState.BOOTSTRAP, planner.state());
                assertTrue(planner.stats().get("startupPumpSlices") > 0);
            }
        }
    }

    @Test void closingThirtySecondPlanTerminatesEveryFuture() {
        var planner = new ProbePlanner(42, Duration.ofSeconds(30), ProbePlanner.Outcome.SUCCESS);
        var leases = new ArrayList<CompletableFuture<ProbePlanner.Plan>>();
        for (int i = 0; i < 100; i++) leases.add(planner.require(KEY));
        planner.close();
        leases.forEach(lease -> assertTrue(lease.isCompletedExceptionally()));
        assertEquals(0, planner.inFlightCount());
        assertEquals(BootstrapState.CLOSED, planner.state());
        assertTrue(planner.require(KEY).isCompletedExceptionally());
    }

    @Test void failureAndCancellationDoNotPublishEmptySuccess() throws Exception {
        for (var outcome : new ProbePlanner.Outcome[]{ProbePlanner.Outcome.FAIL, ProbePlanner.Outcome.CANCEL}) {
            try (var planner = new ProbePlanner(42, Duration.ofMillis(1), outcome)) {
                assertThrows(Exception.class, () -> planner.require(KEY).get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test void previousSessionCannotPublishIntoNewSession() throws Exception {
        var old = new ProbePlanner(1, Duration.ofSeconds(30), ProbePlanner.Outcome.SUCCESS);
        var oldLease = old.require(KEY);
        old.close();
        try (var current = new ProbePlanner(2, Duration.ofMillis(1), ProbePlanner.Outcome.SUCCESS)) {
            assertNotEquals(old.session(), current.session());
            assertNotNull(current.require(KEY).get(2, TimeUnit.SECONDS));
            assertTrue(oldLease.isCompletedExceptionally());
        }
    }

    @Test void diagnosticSentinelRejectsAnActualLockAction() {
        try (var planner = new ProbePlanner(42, Duration.ZERO, ProbePlanner.Outcome.SUCCESS)) {
            StageTrace.enterLockAction();
            try { assertThrows(IllegalStateException.class, () -> planner.require(KEY)); }
            finally { StageTrace.leaveLockAction(); }
            assertEquals(1L, planner.trace().counts().get("WAIT_WHILE_LOCKED"));
        }
    }

    @Test void orderDelayAndSessionNeverEnterTheDiagnosticPlanHash() throws Exception {
        var keys = List.of(KEY, new ProbePlanner.Key("minecraft:overworld", -1),
                new ProbePlanner.Key("minecraft:overworld", Long.MAX_VALUE),
                new ProbePlanner.Key("minecraft:the_nether", 0));
        Map<ProbePlanner.Key, String> hashes = new HashMap<>();
        try (var first = new ProbePlanner(42, Duration.ZERO, ProbePlanner.Outcome.SUCCESS)) {
            for (var key : keys) hashes.put(key, first.require(key).get(3, TimeUnit.SECONDS).hash());
        }
        try (var reverse = new ProbePlanner(42, Duration.ofMillis(3), ProbePlanner.Outcome.SUCCESS)) {
            for (var key : keys.reversed()) {
                assertEquals(hashes.get(key), reverse.require(key).get(3, TimeUnit.SECONDS).hash());
            }
        }
        assertEquals(keys.size(), hashes.values().stream().distinct().count());
        try (var otherSeed = new ProbePlanner(43, Duration.ZERO, ProbePlanner.Outcome.SUCCESS)) {
            assertNotEquals(hashes.get(KEY), otherSeed.require(KEY).get(3, TimeUnit.SECONDS).hash());
        }
    }
}
