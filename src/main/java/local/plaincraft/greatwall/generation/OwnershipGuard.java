package local.plaincraft.greatwall.generation;

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
}
