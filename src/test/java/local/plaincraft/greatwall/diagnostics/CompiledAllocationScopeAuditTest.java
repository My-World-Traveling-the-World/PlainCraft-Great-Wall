package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import org.junit.jupiter.api.Test;

/** Scope restoration and transfer tests, independent of Minecraft and object-size estimates. */
class CompiledAllocationScopeAuditTest {
    @Test void explicitReturnedEvidenceKeepsItsPrechargedArenaUntilConsumerRelease() {
        try(var budget=new AllocationBudget(64)) {
            var result=CompiledAllocation.retainAll(budget,()->{
                CompiledAllocation.charge(32);
                return new byte[8];
            });
            assertEquals(8,result.length);
            assertEquals(32,budget.stats().used());
            assertThrows(WallFailure.class,()->CompiledAllocation.run(budget,()->{
                CompiledAllocation.charge(33);return 0L;
            },Long::longValue));
            assertEquals(32,budget.stats().used());
            budget.release(32);
            assertEquals(0,budget.stats().used());
        }
    }
    @Test void successfulChildTransfersOnlyRetainedBytesToItsParent() {
        try (var budget = new AllocationBudget(64)) {
            long value = CompiledAllocation.run(budget, () -> {
                CompiledAllocation.charge(5);
                long child = CompiledAllocation.run(budget, () -> {
                    CompiledAllocation.charge(20);
                    return 7L;
                }, Long::longValue);
                assertEquals(12, budget.stats().used());
                CompiledAllocation.discard(child);
                assertEquals(5, budget.stats().used());
                return 3L;
            }, Long::longValue);
            assertEquals(3, value);
            assertEquals(3, budget.stats().used());
            assertEquals(25, budget.stats().peak());
            budget.release(value);
            assertEquals(0, budget.stats().used());
        }
    }

    @Test void childCapacityFailureRestoresParentAndReleasesOnlyChildCharges() {
        try (var budget = new AllocationBudget(32)) {
            CompiledAllocation.run(budget, () -> {
                CompiledAllocation.charge(8);
                var failure = assertThrows(WallFailure.class, () -> CompiledAllocation.run(budget, () -> {
                    CompiledAllocation.charge(16);
                    CompiledAllocation.charge(9);
                    return 16L;
                }, Long::longValue));
                assertEquals(FailureCode.CAPACITY, failure.code());
                assertEquals(8, budget.stats().used());
                CompiledAllocation.charge(4);
                CompiledAllocation.discard(4);
                return 0L;
            }, Long::longValue);
            assertEquals(0, budget.stats().used());
            assertEquals(24, budget.stats().peak());
        }
    }

    @Test void failedRetentionEstimatorRestoresThreadLocalScope() {
        try (var budget = new AllocationBudget(32)) {
            var original = new IllegalStateException("retention estimator failed");
            assertSame(original, assertThrows(IllegalStateException.class, () -> CompiledAllocation.run(budget, () -> {
                CompiledAllocation.charge(12);
                return 1;
            }, ignored -> { throw original; })));
            assertEquals(0, budget.stats().used());
            assertDoesNotThrow(() -> CompiledAllocation.charge(Long.MAX_VALUE),
                    "a failed invocation must not leave an active production frame on the calling thread");
            CompiledAllocation.run(budget, () -> {
                CompiledAllocation.charge(32);
                return 0L;
            }, Long::longValue);
            assertEquals(0, budget.stats().used());
        }
    }

    @Test void retainedBytesCannotExceedPrechargedBytesEvenAfterNestedSuccess() {
        try (var budget = new AllocationBudget(32)) {
            assertThrows(IllegalStateException.class, () -> CompiledAllocation.run(budget, () -> {
                CompiledAllocation.run(budget, () -> {
                    CompiledAllocation.charge(12);
                    return 6L;
                }, Long::longValue);
                return 7L;
            }, Long::longValue));
            assertEquals(0, budget.stats().used());
        }
    }

    @Test void foreignNestedBudgetIsRejectedBeforeItsProducerAndParentSurvives() {
        try (var parent = new AllocationBudget(32); var foreign = new AllocationBudget(32)) {
            var calls = new AtomicInteger();
            CompiledAllocation.run(parent, () -> {
                CompiledAllocation.charge(8);
                assertThrows(IllegalStateException.class, () -> CompiledAllocation.run(foreign, () -> {
                    calls.incrementAndGet();
                    return 0L;
                }, Long::longValue));
                CompiledAllocation.discard(8);
                return 0L;
            }, Long::longValue);
            assertEquals(0, calls.get());
            assertEquals(0, parent.stats().used());
            assertEquals(0, foreign.stats().used());
        }
    }
}
