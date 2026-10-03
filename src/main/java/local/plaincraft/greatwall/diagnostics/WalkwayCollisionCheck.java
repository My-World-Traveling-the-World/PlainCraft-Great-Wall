package local.plaincraft.greatwall.diagnostics;

import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Checks integer walkway feet using native local shapes, without accessing a world. */
public final class WalkwayCollisionCheck {
    private static final AABB SUPPORT = new AABB(.201, .99, .201, .799, 1, .799);
    private static final AABB BODY_ABOVE_SUPPORT = new AABB(.201, 1.0001, .201, .799, 2.7999, .799);

    public record Result(boolean feetClear, boolean headClear, boolean supported, boolean baseBodyClear) {
        public boolean valid() { return feetClear && headClear && supported && baseBodyClear; }
    }

    public static Result inspect(VoxelShape feet, VoxelShape head, VoxelShape below) {
        Objects.requireNonNull(feet);
        Objects.requireNonNull(head);
        Objects.requireNonNull(below);
        boolean supported = false, baseBodyClear = true;
        // Match ComponentFixtures.CollisionView's body and support band; a full UP face is not required.
        for (var box : below.toAabbs()) {
            supported |= box.intersects(SUPPORT);
            baseBodyClear &= !box.intersects(BODY_ABOVE_SUPPORT);
        }
        return new Result(feet.isEmpty(), head.isEmpty(), supported, baseBodyClear);
    }

    private WalkwayCollisionCheck() {}
}
