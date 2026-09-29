package local.plaincraft.greatwall.planning;

import java.util.UUID;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;

public final class PlanningFixtures {
    public static final String HASH = "a".repeat(64);
    private PlanningFixtures() {}

    public static WorldRecipe recipe(long seed) { return recipe(seed, 1 << 30, 0, 29_000_000); }

    public static WorldRecipe recipe(long seed, int ex, int ez, int limit) {
        var r = new WorldRecipe(new UUID(0, 1), HASH, seed, "minecraft:overworld", new Point3i(0, 64, 0),
                new Axis(new Point3i(1000, 64, 0), ex, ez), new DimensionBounds(-64, 7000, limit),
                new GenerationConfig(1, 1, 2048, 4, 150000, 384, 192, 4, 67108864, 67108864, 33554432),
                HASH, HASH, HASH, HASH);
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(r));
        return new WorldRecipe(r.instance(), hash, seed, r.dimension(), r.frozenSpawn(), r.axis(), r.bounds(),
                r.config(), r.terrainHash(), r.paletteHash(), r.templatesHash(), hash);
    }
}
