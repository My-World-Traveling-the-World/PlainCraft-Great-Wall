package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class OwnershipGuardTest {
    private final ChunkKey owner=new ChunkKey("minecraft:overworld",-1,0);
    private final Point3i pos=new Point3i(-1,6000,0);
    private final BlockProof proof=new BlockProof(pos,"before","after",false,new SiteId("wall",SiteKind.WALL,1));
    private OwnershipGuard.Context context(boolean marker,boolean initial,boolean ownership,boolean durable) {
        return new OwnershipGuard.Context(new DimensionBounds(-64,8192,29_999_000),marker,initial,ownership,durable);
    }
    @Test void markerAndOwnershipCannotBeInferredFromStoneEquality() {
        assertEquals(WriteDecision.CONFLICT,OwnershipGuard.decide(WriteMode.INITIAL,owner,pos,"after",false,proof,false));
        assertEquals(WriteDecision.ALREADY_TARGET,OwnershipGuard.decide(WriteMode.INITIAL,owner,pos,"after",false,proof,false,context(true,false,false,true)));
        assertEquals(WriteDecision.CONFLICT,OwnershipGuard.decide(WriteMode.GATE_BACKFILL,owner,pos,"before",false,proof,false,context(false,false,false,true)));
        assertEquals(WriteDecision.WRITE,OwnershipGuard.decide(WriteMode.GATE_BACKFILL,owner,pos,"before",false,proof,false,context(false,false,true,true)));
    }
    @Test void playerMutationsFullChunksAndUndurableProofAreRejected() {
        assertEquals(WriteDecision.PROTECTED,OwnershipGuard.decide(WriteMode.INITIAL,owner,pos,"before",false,proof,true,context(false,true,false,true)));
        assertEquals(WriteDecision.CONFLICT,OwnershipGuard.decide(WriteMode.INITIAL,owner,pos,"before",false,proof,false,context(false,false,false,true)));
        assertEquals(WriteDecision.CONFLICT,OwnershipGuard.decide(WriteMode.INITIAL,owner,pos,"before",false,proof,false,context(false,true,false,false)));
        assertEquals(WriteDecision.WRITE,OwnershipGuard.decide(WriteMode.INITIAL,owner,pos,"before",false,proof,false,context(false,true,false,true)));
    }
}
