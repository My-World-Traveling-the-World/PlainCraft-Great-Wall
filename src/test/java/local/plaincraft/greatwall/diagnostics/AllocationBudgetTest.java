package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.model.WallFailure;
import org.junit.jupiter.api.Test;

class AllocationBudgetTest {
    @Test void refusesBeforeCallingProducerAndNeverChangesUsageOnRejection() {
        try(var budget=new AllocationBudget(1024)) {
            try(var pin=budget.reserve(1000)) {
                assertThrows(WallFailure.class,()->budget.reserve(25));
                assertEquals(1000,budget.stats().used());
                assertEquals(1000,budget.stats().peak());
            }
            assertEquals(0,budget.stats().used());
        }
    }
    @Test void ownerCloseRejectsLateProducersAndLatePinCloseIsIdempotent() {
        var budget=new AllocationBudget(1024);var pin=budget.reserve(1024);
        budget.close();pin.close();pin.close();
        assertEquals(0,budget.stats().used());
        assertThrows(WallFailure.class,()->budget.consume(1));
    }
}
