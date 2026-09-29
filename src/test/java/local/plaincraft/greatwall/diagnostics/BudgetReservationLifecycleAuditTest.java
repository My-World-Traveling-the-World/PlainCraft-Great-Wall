package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** Deterministic lifecycle boundaries; these are accounting tests, not heap measurements. */
class BudgetReservationLifecycleAuditTest {
    @Test void cancellingBeforeOwnerDrainNeverCreatesAHeldPin() {
        var owner = new ManualOwner();
        try (var budget = new BudgetReservation(8, 4, owner)) {
            var cancelled = budget.acquire(8).toCompletableFuture();
            assertEquals(1, budget.stats().waiting());
            assertTrue(cancelled.cancel(false));
            owner.drain();
            assertEquals(0, budget.stats().admitted());
            assertEquals(0, budget.stats().reserved());
            var replacement = budget.acquire(8).toCompletableFuture();
            owner.drain();
            replacement.resultNow().close();
            owner.drain();
            assertEquals(0, budget.stats().reserved());
        }
    }

    @Test void cancellingFifoHeadAdmitsSmallerSuccessorWithoutReleasingExistingConsumer() {
        var owner = new ManualOwner();
        try (var budget = new BudgetReservation(8, 4, owner)) {
            var first = budget.acquire(6).toCompletableFuture();
            owner.drain();
            var head = budget.acquire(4).toCompletableFuture();
            var tail = budget.acquire(2).toCompletableFuture();
            owner.drain();
            assertFalse(head.isDone());
            assertFalse(tail.isDone(), "FIFO must not bypass the larger head");
            head.cancel(false);
            owner.drain();
            assertTrue(tail.isDone());
            assertEquals(8, budget.stats().reserved());
            first.resultNow().close();
            tail.resultNow().close();
            owner.drain();
            assertEquals(0, budget.stats().admitted());
        }
    }

    @Test void executorRejectionFailsWaitersButCannotUnpinDeliveredData() {
        var owner = new ManualOwner();
        var budget = new BudgetReservation(8, 4, owner);
        var delivered = budget.acquire(8).toCompletableFuture();
        owner.drain();
        try {
            owner.reject = true;
            var rejected = budget.acquire(1).toCompletableFuture();
            assertTrue(rejected.isCompletedExceptionally());
            assertTrue(budget.stats().closed());
            assertEquals(0, budget.stats().waiting());
            assertEquals(8, budget.stats().reserved(), "delivered payload still has a consumer");
        } finally {
            delivered.resultNow().close();
            budget.close();
        }
        assertEquals(0, budget.stats().reserved());
        assertEquals(0, budget.stats().admitted());
    }

    @Test void closeBeforeDrainFailsEveryWaiterAndQueuedDrainCannotResurrectThem() {
        var owner = new ManualOwner();
        var budget = new BudgetReservation(8, 4, owner);
        var first = budget.acquire(4).toCompletableFuture();
        var second = budget.acquire(4).toCompletableFuture();
        budget.close();
        owner.drain();
        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
        assertEquals(0, budget.stats().reserved());
        assertEquals(0, budget.stats().admitted());
    }

    @Test void reentrantCloseOnFirstGrantCannotLeakOtherAlreadyAdmittedJobs() {
        var owner = new ManualOwner();
        var budget = new BudgetReservation(8, 4, owner);
        var first = budget.acquire(4).toCompletableFuture();
        var second = budget.acquire(4).toCompletableFuture();
        first.thenAccept(lease -> {
            budget.close();
            lease.close();
        });
        owner.drain();
        assertTrue(first.isDone());
        assertTrue(second.isCompletedExceptionally());
        assertEquals(0, budget.stats().reserved());
        assertEquals(0, budget.stats().admitted());
    }

    @Test void failingConsumerOwnsItsPinUntilFinallyClosesIt() {
        var owner = new ManualOwner();
        try (var budget = new BudgetReservation(8, 4, owner)) {
            var stage = budget.acquire(8).thenAccept(lease -> {
                try {
                    assertEquals(8, budget.stats().reserved());
                    throw new IllegalStateException("consumer failure");
                } finally {
                    lease.close();
                }
            }).toCompletableFuture();
            owner.drain();
            assertTrue(stage.isCompletedExceptionally());
            assertEquals(0, budget.stats().reserved());
        }
    }

    private static final class ManualOwner implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean reject;

        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("audit owner stopped");
            tasks.addLast(task);
        }

        void drain() {
            int steps = 0;
            while (!tasks.isEmpty()) {
                assertTrue(++steps < 1000, "owner did not quiesce");
                tasks.removeFirst().run();
            }
        }
    }
}
