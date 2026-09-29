package local.plaincraft.greatwall.generation;

import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes.*;
/** Coordinate and before-state checks run before the first mutation, including replay. */
public final class OwnershipGuard {
    private OwnershipGuard() {}
    public static void check(int ownerX, int ownerZ, int x, int y, int z, int minY, int maxY,
                             boolean protectedBlock, String actual, String before, String after) {
        if (Math.floorDiv(x, 16) != ownerX || Math.floorDiv(z, 16) != ownerZ) {
            throw new IllegalStateException("CROSS_OWNER_WRITE");
        }
        if (y < minY || y > maxY || protectedBlock) throw new IllegalStateException("PROTECTED_WRITE");
        if (!actual.equals(before) && !actual.equals(after)) throw new IllegalStateException("OWNERSHIP_CONFLICT");
    }

    public record Context(DimensionBounds bounds,boolean matchingRevision,boolean initialBeforeFull,
            boolean ownershipMatches,boolean proofDurable) {
        public Context {Objects.requireNonNull(bounds);}
    }
    /** Conservative unprivileged check: marker-dependent replay/backfill requires the context overload. */
    public static WriteDecision decide(WriteMode mode,ChunkKey owner,Point3i pos,String current,
            boolean hasBlockEntity,BlockProof proof,boolean mutationTombstone) {
        Objects.requireNonNull(mode);Objects.requireNonNull(owner);Objects.requireNonNull(pos);
        Objects.requireNonNull(current);Objects.requireNonNull(proof);
        if(!pos.equals(proof.pos())||Math.floorDiv(pos.x(),16)!=owner.x()||Math.floorDiv(pos.z(),16)!=owner.z())
            return WriteDecision.CONFLICT;
        if(hasBlockEntity||proof.beforeHasBlockEntity()||mutationTombstone)return WriteDecision.PROTECTED;
        return mode==WriteMode.INITIAL&&current.equals(proof.beforeState())?WriteDecision.WRITE:WriteDecision.CONFLICT;
    }
    public static WriteDecision decide(WriteMode mode,ChunkKey owner,Point3i pos,String current,boolean hasBlockEntity,
            BlockProof proof,boolean mutationTombstone,Context context) {
        Objects.requireNonNull(context);
        if(pos.y()<context.bounds().minY()||pos.y()>=context.bounds().maxYExclusive()
                ||Math.abs((long)pos.x())>context.bounds().horizontalLimit()||Math.abs((long)pos.z())>context.bounds().horizontalLimit()
                ||!pos.equals(proof.pos())||Math.floorDiv(pos.x(),16)!=owner.x()||Math.floorDiv(pos.z(),16)!=owner.z()
                ||!context.proofDurable())return WriteDecision.CONFLICT;
        if(context.matchingRevision()&&current.equals(proof.afterState()))return WriteDecision.ALREADY_TARGET;
        if(hasBlockEntity||proof.beforeHasBlockEntity()||mutationTombstone)return WriteDecision.PROTECTED;
        if(!current.equals(proof.beforeState()))return WriteDecision.CONFLICT;
        if(mode==WriteMode.INITIAL&&!context.initialBeforeFull()||mode==WriteMode.GATE_BACKFILL&&!context.ownershipMatches())
            return WriteDecision.CONFLICT;
        return WriteDecision.WRITE;
    }
}
