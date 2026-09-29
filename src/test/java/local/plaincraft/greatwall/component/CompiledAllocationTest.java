package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.diagnostics.*;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class CompiledAllocationTest {
    @Test void fortReleasesConsumedBuilderTablesBeforeMergingWithoutIncreasingBudget() {
        var input=TemplateFixtures.input(SiteKind.FORT,new Bounds(-24,-33,-24,24,8,24));
        var template=new FortPlaceholder(new DecayField(0),TemplateFixtures.PALETTE);
        var expected=template.compile(input);
        try(var budget=new AllocationBudget(32L<<20)) {
            var actual=CompiledAllocation.run(budget,()->CompiledAllocation.component(()->template.compile(input)),CompiledAllocation::bytes);
            assertEquals(expected,actual);assertFalse(actual.voxels().isEmpty());
            assertTrue(budget.stats().peak()<=32L<<20);
            assertEquals(CompiledAllocation.bytes(actual),budget.stats().used());
        }
    }
    @Test void insufficientCapacityRejectsBeforeAnyPaletteOrVoxelProduction() {
        var calls=new AtomicInteger();
        PaletteBinding palette=(i,p,l,w,s,h)->{calls.incrementAndGet();return TemplateFixtures.PALETTE.resolve(i,p,l,w,s,h);};
        var input=TemplateFixtures.input(SiteKind.ENEMY_TOWER,new Bounds(-5,-33,-5,6,8,6));
        try(var budget=new AllocationBudget(1024)) {
            var error=assertThrows(WallFailure.class,()->CompiledAllocation.run(budget,
                    ()->CompiledAllocation.component(()->new EnemyTower(new DecayField(0),palette).compile(input)),CompiledAllocation::bytes));
            assertEquals(FailureCode.CAPACITY,error.code());assertEquals(0,calls.get());
            assertEquals(0,budget.stats().used());
        }
    }
    @Test void sufficientAdmissionKeepsIdenticalGeometryAndReleasesTemporaryCapacity() {
        var input=TemplateFixtures.input(SiteKind.ENEMY_TOWER,new Bounds(-5,-33,-5,6,8,6));
        var template=new EnemyTower(new DecayField(0),TemplateFixtures.PALETTE);
        var expected=template.compile(input);
        try(var budget=new AllocationBudget(32L<<20)) {
            var actual=CompiledAllocation.run(budget,()->CompiledAllocation.component(()->template.compile(input)),CompiledAllocation::bytes);
            assertEquals(expected,actual);
            assertEquals(CompiledAllocation.bytes(actual),budget.stats().used());
            assertTrue(budget.stats().peak()>budget.stats().used());
            assertTrue(budget.stats().peak()<=32L<<20);
        }
        assertEquals(expected,template.compile(input),"thread-local allocation scope does not leak into the next call");
    }
}
