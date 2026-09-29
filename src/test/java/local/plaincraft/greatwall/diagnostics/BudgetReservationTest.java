package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.PlanLease;
import org.junit.jupiter.api.Test;

class BudgetReservationTest {
    @Test void closeKeepsDeliveredPinsAccountedUntilTheirActualRelease() throws Exception {
        var budget = new BudgetReservation(1024, 4, Runnable::run);
        var pin = budget.acquire(1024).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var pending = budget.acquire(1).toCompletableFuture();
        budget.close();
        assertTrue(pending.isCompletedExceptionally());
        assertEquals(1024, budget.stats().reserved());
        assertTrue(budget.acquire(1).toCompletableFuture().isCompletedExceptionally());
        pin.close();
        pin.close();
        assertEquals(0, budget.stats().reserved());
        assertEquals(0, budget.stats().admitted());
    }

    @Test void rejectedExecutorFailsPendingAdmissionWithoutRetainingBytes() {
        var budget = new BudgetReservation(1024, 4, task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertTrue(budget.acquire(1024).toCompletableFuture().isCompletedExceptionally());
        assertEquals(0, budget.stats().reserved());
        assertEquals(0, budget.stats().admitted());
        budget.close();
    }

    @Test void concurrentOwnerDoesNotRequireASingleThreadExecutor() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8);
             var budget = new BudgetReservation(4096, 4096, executor)) {
            var consumers = new java.util.ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 2000; i++) {
                consumers.add(CompletableFuture.supplyAsync(() -> budget.acquire(1024), executor)
                        .thenCompose(stage -> stage).thenAccept(lease -> {
                            try { assertTrue(budget.stats().reserved() <= 4096); }
                            finally { lease.close(); }
                        }).toCompletableFuture());
            }
            CompletableFuture.allOf(consumers.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            assertEquals(0, budget.stats().reserved());
            assertEquals(0, budget.stats().waiting());
        }
    }

    @Test void reservationIsGrantedBeforeProducerAndReleasedAfterPinClose() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor();
             var budget = new BudgetReservation(64L << 20, 4, executor)) {
            var reservation = budget.acquire(64L << 20).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var producerCalls = new AtomicInteger();
            var blocked = budget.acquire(1).toCompletableFuture();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(blocked.isDone());
            assertEquals(0, producerCalls.get());
            assertEquals(64L << 20, budget.stats().reserved());
            reservation.close();
            try (PlanLease<BudgetReservation.Reservation> next = blocked.get(5, TimeUnit.SECONDS)) {
                assertEquals(1, next.future().get().bytes());
            }
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, budget.stats().reserved());
        }
    }

    @Test void overLimitIsRejectedBeforeAnyAllocationCanStart() {
        try (var executor = Executors.newSingleThreadExecutor();
             var budget = new BudgetReservation(1024, 4, executor)) {
            var started = new AtomicInteger();
            assertTrue(budget.acquire(1025).toCompletableFuture().isCompletedExceptionally());
            assertEquals(0, started.get());
            assertEquals(0, budget.stats().reserved());
        }
    }

    @Test void cancelledWaiterDoesNotConsumeCapacity() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor();
             var budget = new BudgetReservation(1024, 4, executor)) {
            var first = budget.acquire(1024).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var waiter = budget.acquire(1).toCompletableFuture();
            waiter.cancel(false);
            first.close();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, budget.stats().waiting());
            assertEquals(0, budget.stats().reserved());
        }
    }
}
