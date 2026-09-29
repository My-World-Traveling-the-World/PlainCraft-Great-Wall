package local.plaincraft.greatwall.component;

import local.plaincraft.greatwall.model.WallTypes.*;

/** Compiles the complete immutable component before any owner-chunk clipping. */
public interface ComponentTemplate {
    Bounds localBounds(ComponentInput input);
    ComponentVoxels compile(ComponentInput input);
}
