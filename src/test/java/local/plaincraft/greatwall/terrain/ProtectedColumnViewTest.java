package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ProtectedColumnViewTest {
    private static final SurfaceProbe.ColumnView SOURCE = new SurfaceProbe.ColumnView() {
        public String state(int y) { return y < 64 ? "minecraft:stone" : "minecraft:air"; }
        public boolean solidSupport(int y) { return y < 64; }
        public boolean water(int y) { return false; }
        public boolean airOrReplaceablePlant(int y) { return y >= 64; }
        public int highestNonPlantFeet() { return 64; }
        public String hash() { return "a".repeat(64); }
        public boolean protectedAt(int y) { return y == 7; }
        public boolean naturalThinIce(int y) { return y == 63; }
    };

    @Test void prefilteredColumnsMatchTheIndependentOldScanIncludingBoundariesAndDuplicates() {
        var boxes = new ArrayList<Bounds>();
        var random = new Random(1729);
        for (int i = 0; i < 150; i++) {
            int x = random.nextInt(64) - 32, z = random.nextInt(64) - 32, y = random.nextInt(96) - 32;
            boxes.add(new Bounds(x, y, z, x + 8, y + 16, z + 8));
        }
        boxes.addAll(List.copyOf(boxes));
        for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) {
            var pos = new Point3i(x, 0, z);
            var actual = ProtectedColumnView.wrap(SOURCE, pos, boxes);
            for (int y = -33; y < 96; y++) {
                boolean expected = SOURCE.protectedAt(y);
                for (var box : boxes) expected |= x >= box.minX() && x < box.maxXExclusive()
                        && z >= box.minZ() && z < box.maxZExclusive() && y >= box.minY() && y < box.maxYExclusive();
                assertEquals(expected, actual.protectedAt(y));
                assertEquals(SOURCE.naturalThinIce(y) && !expected, actual.naturalThinIce(y));
            }
            assertEquals(SOURCE.hash(), actual.hash());
            assertEquals(SOURCE.state(64), actual.state(64));
            assertEquals(SOURCE.highestNonPlantFeet(), actual.highestNonPlantFeet());
        }
    }

    @Test void noIntersectingStructureStillKeepsOriginalProtectionAndThinIcePrecedence() {
        var boxes = List.of(new Bounds(0, 0, 0, 16, 80, 16));
        var outside = ProtectedColumnView.wrap(SOURCE, new Point3i(16, 0, 16), boxes);
        assertEquals(SOURCE.protectedAt(7), outside.protectedAt(7));
        assertEquals(SOURCE.naturalThinIce(63), outside.naturalThinIce(63));
        var protectedColumn = ProtectedColumnView.wrap(SOURCE, new Point3i(15, 0, 15), boxes);
        assertTrue(protectedColumn.protectedAt(0));
        assertTrue(protectedColumn.protectedAt(79));
        assertFalse(protectedColumn.protectedAt(80));
    }

    @Test void invocationIndexPreservesOldProtectionAcrossNegativeChunksAndUnknownColumns() {
        var boxes=List.of(new Bounds(-20,-10,-20,-2,80,-2),new Bounds(-20,-10,-20,-2,80,-2),
                new Bounds(16,20,16,32,60,32));
        var columns=List.of(new Point3i(-17,0,-17),new Point3i(-1,0,-1),new Point3i(16,0,16));
        ProtectedColumnView.indexed(boxes,columns,()->{
            for(int x=-33;x<=33;x++)for(int z=-33;z<=33;z++) {
                var view=ProtectedColumnView.wrap(SOURCE,new Point3i(x,0,z),boxes);
                for(int y=-11;y<=81;y++) {
                    boolean expected=SOURCE.protectedAt(y);
                    for(var b:boxes)expected|=x>=b.minX()&&x<b.maxXExclusive()
                            &&z>=b.minZ()&&z<b.maxZExclusive()&&y>=b.minY()&&y<b.maxYExclusive();
                    assertEquals(expected,view.protectedAt(y));
                }
            }
            return null;
        });
    }

    @Test void indexScopeReleasesScratchAndRestoresThePreviousInvocationOnFailure() {
        var boxes=List.of(new Bounds(0,0,0,16,80,16));
        var columns=List.of(new Point3i(0,0,0));
        var budget=new local.plaincraft.greatwall.diagnostics.AllocationBudget(1L<<20);
        local.plaincraft.greatwall.diagnostics.CompiledAllocation.run(budget,()->{
            ProtectedColumnView.indexed(boxes,columns,()->{
                assertThrows(IllegalStateException.class,()->ProtectedColumnView.indexed(List.of(),
                        columns,()->{throw new IllegalStateException("retained index failure");}));
                assertTrue(ProtectedColumnView.wrap(SOURCE,columns.getFirst(),boxes).protectedAt(64));
                return null;
            });
            assertEquals(0,budget.stats().used());
            return null;
        },ignored->0);
        assertEquals(0,budget.stats().used());
        budget.close();
    }

    @Test void insufficientBucketBudgetFailsBeforeColumnsAndDoesNotMaskCapacity() {
        var boxes=List.of(new Bounds(0,0,0,16,80,16));
        var budget=new local.plaincraft.greatwall.diagnostics.AllocationBudget(900);
        local.plaincraft.greatwall.diagnostics.CompiledAllocation.run(budget,()->{
            var error=assertThrows(local.plaincraft.greatwall.model.WallFailure.class,
                    ()->ProtectedColumnView.indexed(boxes,List.of(new Point3i(0,0,0)),
                            ()->{throw new AssertionError("under-budget index must not enter the action");}));
            assertEquals(FailureCode.CAPACITY,error.code());
            assertEquals(0,budget.stats().used());
            return null;
        },ignored->0);
        budget.close();
    }

    @Test void originalDuplicatedBoxesAreTraversedOnceNotAgainForEveryColumn() {
        int[] reads={0};
        var backing=new ArrayList<Bounds>();
        for(int i=0;i<1000;i++)backing.add(new Bounds(0,0,0,32,80,32));
        List<Bounds> counted=new AbstractList<>() {
            public Bounds get(int index){reads[0]++;return backing.get(index);}
            public int size(){return backing.size();}
        };
        var columns=new ArrayList<Point3i>();
        for(int x=0;x<32;x++)for(int z=0;z<32;z++)columns.add(new Point3i(x,0,z));
        ProtectedColumnView.indexed(counted,columns,()->{
            int afterIndex=reads[0];
            for(var pos:columns) {
                var view=ProtectedColumnView.wrap(SOURCE,pos,counted);
                assertTrue(view.protectedAt(64));
            }
            assertEquals(afterIndex,reads[0],"column queries never rescan the original duplicated protection list");
            return null;
        });
        assertTrue(reads[0]>=backing.size(),"the original boxes were actually visited when the index was built");
    }
}
