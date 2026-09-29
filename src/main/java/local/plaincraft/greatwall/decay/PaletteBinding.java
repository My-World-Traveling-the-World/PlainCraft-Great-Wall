package local.plaincraft.greatwall.decay;

import local.plaincraft.greatwall.model.WallTypes.*;

/** Pure binding port. S1 fixtures and the later S4 catalog share identical occupancy. */
@FunctionalInterface
public interface PaletteBinding {
    BlockSpec resolve(ComponentInput input, Part part, Point3i local,
            Point3i world, long globalSQ8, long voxelHash);
}
