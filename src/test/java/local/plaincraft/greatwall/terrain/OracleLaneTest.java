package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

class OracleLaneTest {
    private static final String HASH = PlanningFixtures.HASH;

    @Test void oncePerLaneAcrossJobsAndClosesOnlyAtDrain() throws Exception {
        var created = new AtomicInteger(); var closed = new AtomicInteger();
        var lane = lane(0, created, closed);
        try {
            lane.start().get(5, TimeUnit.SECONDS);
            var first = lane.submit(oracle -> ((OwnedOracle) oracle).identity()).get(5, TimeUnit.SECONDS);
            for (int job = 0; job < 4; job++) {
                assertEquals(first, lane.submit(oracle -> ((OwnedOracle) oracle).identity()).get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, created.get()); assertEquals(0, closed.get());
            assertEquals(0L, lane.diagnostics().getOrDefault("sentinel-WAIT_WHILE_LOCKED", 0L));
            assertEquals(0L, lane.diagnostics().getOrDefault("forbidden-init-thread", 0L));
        } finally { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
        assertEquals(1, closed.get());
        lane.close(); lane.drain().get(5, TimeUnit.SECONDS); assertEquals(1, closed.get());
    }

    @Test void sessionAndLaneIdentityNeverReuseAnotherOracle() throws Exception {
        var created = new AtomicInteger(); var closed = new AtomicInteger();
        UUID session = UUID.randomUUID();
        var a = new OracleLane(session, 0, HASH, () -> new OwnedOracle(created, closed));
        var b = new OracleLane(session, 1, HASH, () -> new OwnedOracle(created, closed));
        var c = new OracleLane(UUID.randomUUID(), 0, HASH, () -> new OwnedOracle(created, closed));
        try {
            var aa = a.submit(o -> ((OwnedOracle) o).identity()).get(5, TimeUnit.SECONDS);
            var bb = b.submit(o -> ((OwnedOracle) o).identity()).get(5, TimeUnit.SECONDS);
            var cc = c.submit(o -> ((OwnedOracle) o).identity()).get(5, TimeUnit.SECONDS);
            assertNotEquals(aa, bb); assertNotEquals(aa, cc); assertNotEquals(bb, cc);
            assertEquals(3, created.get());
        } finally {
            for (var lane : java.util.List.of(a, b, c)) { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
        }
        assertEquals(3, closed.get());
    }

    @Test void callerCancellationCannotCancelInitializationOrOtherJob() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var created = new AtomicInteger(); var closed = new AtomicInteger();
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            entered.countDown(); await(release); return new OwnedOracle(created, closed);
        });
        try {
            var view = lane.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            view.cancel(true);
            var cancelledJob = lane.submit(o -> ((OwnedOracle) o).identity()); cancelledJob.cancel(true);
            var retained = lane.submit(o -> ((OwnedOracle) o).identity());
            assertFalse(retained.isDone()); assertFalse(lane.drain().isDone());
            release.countDown();
            assertNotNull(retained.get(5, TimeUnit.SECONDS));
            assertEquals(1, created.get()); assertEquals(0, closed.get());
        } finally { release.countDown(); lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
        assertEquals(1, closed.get());
    }

    @Test void closeDuringUninterruptibleInitializationWaitsForActualProducer() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var created = new AtomicInteger(); var closed = new AtomicInteger();
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            entered.countDown(); await(release); return new OwnedOracle(created, closed);
        });
        try {
            var readiness = lane.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            lane.close();
            assertThrows(ExecutionException.class, () -> readiness.get(5, TimeUnit.SECONDS));
            assertFalse(lane.drain().isDone(), "completed public promises are not producer termination");
            assertEquals(0, closed.get());
            release.countDown(); lane.drain().get(5, TimeUnit.SECONDS);
            assertEquals(1, created.get()); assertEquals(1, closed.get());
            assertFalse(lane.diagnostics().containsKey("ready"), "closed session must not publish late readiness");
        } finally { release.countDown(); lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void initFailureIsVisibleNotRetriedAndDependenciesTerminate() throws Exception {
        var called = new AtomicInteger();
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            called.incrementAndGet(); throw new IllegalStateException("native failure counterexample");
        });
        try {
            assertThrows(ExecutionException.class, () -> lane.start().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> lane.submit(o -> 1).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> lane.start().get(5, TimeUnit.SECONDS));
            assertEquals(1, called.get());
        } finally { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void fingerprintMismatchClosesTheActualOracle() throws Exception {
        var created = new AtomicInteger(); var closed = new AtomicInteger();
        var lane = new OracleLane(UUID.randomUUID(), 0, "b".repeat(64), () -> new OwnedOracle(created, closed));
        try { assertThrows(ExecutionException.class, () -> lane.start().get(5, TimeUnit.SECONDS)); }
        finally { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
        assertEquals(1, created.get()); assertEquals(1, closed.get());
    }

    @Test void lockedRequestAndUnauthorizedThreadFailBeforeFactory() throws Exception {
        var created = new AtomicInteger(); var closed = new AtomicInteger();
        var lane = lane(0, created, closed);
        try {
            StageTrace.enterLockAction();
            try {
                assertThrows(IllegalStateException.class, lane::start);
                assertThrows(IllegalStateException.class, () -> lane.submit(o -> 1));
            } finally { StageTrace.leaveLockAction(); }
            assertEquals(0, created.get());
            assertThrows(IllegalStateException.class, OracleLane::assertNativeInitialization);
            assertEquals(2L, lane.diagnostics().get("sentinel-WAIT_WHILE_LOCKED"));
            lane.start().get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> lane.submit(o -> {
                OracleLane.assertNativeInitialization(); return 1;
            }).get(5, TimeUnit.SECONDS), "sampling action is not a second initialization grant");
        } finally { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void queueCapacityIsReservedBeforeSubmittingAndReleasedAfterDrain() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            entered.countDown(); await(release); return new OwnedOracle(new AtomicInteger(), new AtomicInteger());
        });
        try {
            lane.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) lane.submit(o -> 1);
            assertThrows(ExecutionException.class, () -> lane.submit(o -> 1).get(5, TimeUnit.SECONDS));
            assertEquals(64L, lane.diagnostics().get("queued"));
        } finally { lane.close(); release.countDown(); lane.drain().get(5, TimeUnit.SECONDS); }
        assertEquals(0L, lane.diagnostics().get("queued"));
    }

    @Test void canceledDrainViewCannotHideCloseFailure() throws Exception {
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> new OwnedOracle(new AtomicInteger(), new AtomicInteger()) {
            @Override public void close() { super.close(); throw new IllegalStateException("native close counterexample"); }
        });
        lane.start().get(5, TimeUnit.SECONDS);
        lane.drain().cancel(true);
        lane.close();
        assertThrows(ExecutionException.class, () -> lane.drain().get(5, TimeUnit.SECONDS));
        assertFalse(lane.diagnostics().containsKey("drained"));
    }

    @Test void readinessCallbackCannotBorrowTheInitializationGrant() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            entered.countDown(); await(release); return new OwnedOracle(new AtomicInteger(), new AtomicInteger());
        });
        try {
            var ready = lane.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var checked = ready.thenRun(() -> {
                assertTrue(Thread.currentThread().getName().startsWith("pgw-oracle-"),
                        "the counterexample must really execute inline on the completing lane");
                assertThrows(IllegalStateException.class, OracleLane::assertNativeInitialization,
                        "public ready callback is not part of the private constructor stage");
            });
            release.countDown();
            checked.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void initializationFailureCallbackCannotBorrowTheGrant() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            entered.countDown(); await(release); throw new IllegalStateException("constructor failure");
        });
        try {
            var ready = lane.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var checked = ready.handle((value, failure) -> {
                assertNotNull(failure);
                assertTrue(Thread.currentThread().getName().startsWith("pgw-oracle-"));
                assertThrows(IllegalStateException.class, OracleLane::assertNativeInitialization);
                return true;
            });
            release.countDown(); assertTrue(checked.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void eachLaneNativeConstructionPermitCanBeConsumedOnlyOnce() throws Exception {
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, () -> {
            OracleLane.claimNativeInitialization();
            assertThrows(IllegalStateException.class, OracleLane::claimNativeInitialization);
            return new OwnedOracle(new AtomicInteger(), new AtomicInteger());
        });
        try {
            lane.start().get(5, TimeUnit.SECONDS);
            assertEquals(1L, lane.diagnostics().get("native-constructions"));
            assertThrows(ExecutionException.class, () -> lane.submit(o -> {
                OracleLane.claimNativeInitialization(); return 1;
            }).get(5, TimeUnit.SECONDS));
        } finally { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void serverAndWorldgenThreadsCannotClaimNativeConstruction() throws Exception {
        for (String name : java.util.List.of("Server thread", "Worker-Main-worldgen", "c2me-worker")) {
            try (var worker = Executors.newSingleThreadExecutor(task -> new Thread(task, name))) {
                assertTrue(worker.submit(() -> {
                    assertThrows(IllegalStateException.class, OracleLane::claimNativeInitialization); return true;
                }).get(5, TimeUnit.SECONDS));
            }
        }
    }

    private static OracleLane lane(int index, AtomicInteger created, AtomicInteger closed) {
        return new OracleLane(UUID.randomUUID(), index, HASH, () -> new OwnedOracle(created, closed));
    }
    private static void await(CountDownLatch release) {
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture never released"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private static class OwnedOracle implements NaturalTerrainOracle {
        private final Thread owner = Thread.currentThread();
        private final UUID id = UUID.randomUUID();
        private final AtomicInteger closed;
        OwnedOracle(AtomicInteger created, AtomicInteger closed) {
            OracleLane.assertNativeInitialization(); this.closed = closed; created.incrementAndGet();
        }
        UUID identity() { assertSame(owner, Thread.currentThread()); return id; }
        @Override public ColumnCursor begin(int x, int z, SampleKind kind) { throw new UnsupportedOperationException(); }
        @Override public WorkState advance(ColumnCursor c, int limit) { throw new UnsupportedOperationException(); }
        @Override public ColumnSample finish(ColumnCursor c) { throw new UnsupportedOperationException(); }
        @Override public String fingerprint() { return HASH; }
        @Override public void close() { assertSame(owner, Thread.currentThread()); closed.incrementAndGet(); }
    }
}
