package local.plaincraft.greatwall.generation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;

/** Frozen configuration bounds. Unknown types/configurations fail before any tree writes. */
public final class TreeBoundsCatalog {
    private static final Set<String> FOLIAGE = Set.of("blob", "spruce", "pine", "acacia", "bush", "fancy",
            "jungle", "mega_pine", "dark_oak", "random_spread", "cherry");
    private final HolderLookup.Provider registries;
    private final Map<String, Integer> radii;
    private final int maximumRadius;

    public TreeBoundsCatalog(HolderLookup.Provider registries, Collection<ConfiguredFeature<?, ?>> configured) {
        this.registries = Objects.requireNonNull(registries);
        var values = new TreeMap<String, Integer>();
        long[] visited = {0};
        // ConfiguredFeature is a value record whose getFeatures() walks nested
        // configurations; a third-party RandomFeatureConfiguration that references an
        // ancestor makes that walk (and even equals()) recurse forever. Walk the
        // reference graph ourselves with an identity set and an explicit stack so a
        // cycle terminates by construction instead of overflowing the JVM stack.
        Set<ConfiguredFeature<?, ?>> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        var pending = new java.util.ArrayDeque<ConfiguredFeature<?, ?>>();
        for (var feature : configured) pending.add(feature);
        while (!pending.isEmpty()) {
            var feature = pending.poll();
            if (!seen.add(feature)) continue;  // already visited this exact instance
            if (++visited[0] > 262144) throw unsupported("configured feature traversal exceeds finite registry allowance");
            if (feature.config() instanceof TreeConfiguration tree) {
                JsonObject encoded;
                int radius;
                try {
                    encoded = encode(tree);
                    radius = radius(encoded);
                } catch (Throwable failure) {
                    // Real modpacks ship third-party tree features that cannot be
                    // round-tripped by the vanilla codec (null fields, custom decorators,
                    // broken registry entries) or use a trunk/foliage placer outside the
                    // frozen whitelist. A tree we cannot encode/measure is simply excluded
                    // from the protection catalog: fail-closed only applies to trees we
                    // actually manage, never to an unrelated mod's feature. This keeps
                    // production freeze from NPE-ing or aborting on the first exotic tree.
                    GreatWallMod.LOGGER.warn("Skipping unencodable tree feature during freeze: {}",
                            feature.feature().toString(), failure);
                    continue;
                }
                if (radius > 2048) throw unsupported("tree radius exceeds 2048: " + radius);
                values.put(hash(encoded), radius);
                continue;
            }
            for (var child : nestedFeatures(feature)) {
                if (!seen.contains(child)) pending.add(child);
            }
        }
        radii = Map.copyOf(values);
        maximumRadius = values.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public Bounds vanilla(BlockPos origin, TreeConfiguration config, DimensionBounds dimension) {
        Integer radius = radii.get(hash(encode(config)));
        if (radius == null) throw unsupported("tree configuration was not in the frozen registry");
        return new Bounds(Math.subtractExact(origin.getX(), radius), dimension.minY(), Math.subtractExact(origin.getZ(), radius),
                Math.addExact(origin.getX(), radius + 1), dimension.maxYExclusive(), Math.addExact(origin.getZ(), radius + 1));
    }

    public int maximumRadius() { return maximumRadius; }
    public String hash() {
        var json = new JsonObject();
        new TreeMap<>(radii).forEach(json::addProperty);
        return WorldRecipeGuard.fingerprint(Map.of("treeBounds-v1", json));
    }

    public static int decoratorMargin(List<TreeDecorator> decorators) {
        int result = 0;
        for (var decorator : decorators) {
            var encoded = TreeDecorator.CODEC.encodeStart(JsonOps.INSTANCE, decorator).getOrThrow().getAsJsonObject();
            result = Math.max(result, margin(encoded));
        }
        return result;
    }

    public static Bounds actualPositions(Collection<BlockPos> positions, int margin, DimensionBounds dimension) {
        if (positions.isEmpty() || margin < 0 || margin > 2048) throw new IllegalArgumentException("nonempty positions and bounded margin required");
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var pos : positions) {
            minX = Math.min(minX, pos.getX()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxZ = Math.max(maxZ, pos.getZ());
        }
        return new Bounds(Math.subtractExact(minX, margin), dimension.minY(), Math.subtractExact(minZ, margin),
                Math.addExact(maxX, margin + 1), dimension.maxYExclusive(), Math.addExact(maxZ, margin + 1));
    }

    static int radius(JsonObject config) {
        var trunk = config.getAsJsonObject("trunk_placer");
        int height = Math.addExact(integer(trunk, "base_height"),
                Math.addExact(integer(trunk, "height_rand_a"), integer(trunk, "height_rand_b")));
        int trunkRadius = switch (type(trunk, "_trunk_placer")) {
            case "straight" -> 0;
            case "giant" -> 1;
            case "forking", "fancy", "dark_oak", "mega_jungle" -> Math.addExact(height, 4);
            case "bending" -> Math.addExact(height, maximum(trunk.get("bend_length")));
            case "upwards_branching" -> Math.addExact(height,
                    Math.addExact(maximum(trunk.get("extra_branch_steps")), maximum(trunk.get("extra_branch_length"))));
            case "cherry" -> Math.addExact(maximum(trunk.get("branch_horizontal_length")), 2);
            default -> throw unsupported("unknown trunk placer " + trunk);
        };
        var foliage = config.getAsJsonObject("foliage_placer");
        if (!FOLIAGE.contains(type(foliage, "_foliage_placer"))) throw unsupported("unknown foliage placer " + foliage);
        int foliageRadius = Math.addExact(maximum(foliage.get("radius")), 4);
        int rootRadius = 0;
        if (config.has("root_placer")) {
            var root = config.getAsJsonObject("root_placer");
            if (!type(root, "_root_placer").equals("mangrove")) throw unsupported("unknown root placer " + root);
            var placement = root.getAsJsonObject("mangrove_root_placement");
            rootRadius = Math.addExact(Math.addExact(integer(placement, "max_root_width"),
                    integer(placement, "max_root_length")), 1);
        }
        int decorator = 0;
        for (var value : config.getAsJsonArray("decorators")) decorator = Math.max(decorator, margin(value.getAsJsonObject()));
        return Math.addExact(Math.max(Math.addExact(trunkRadius, foliageRadius), rootRadius), decorator);
    }

    private JsonObject encode(TreeConfiguration tree) {
        return TreeConfiguration.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), tree).getOrThrow().getAsJsonObject();
    }

    /**
     * Direct child features of one configuration, without the vanilla recursive walk.
     *
     * <p>{@code ConfiguredFeature.getFeatures()} concatenates itself with
     * {@code config.getFeatures()}, and {@code RandomFeatureConfiguration} returns its
     * placed features, which point back at configured features. Following that API on a
     * self-referential pack recurses until {@code StackOverflowError}. Reading the
     * public {@code features} list and resolving each placed feature's holder one level
     * lets the caller keep an identity set and terminate cycles safely.</p>
     */
    private static List<ConfiguredFeature<?, ?>> nestedFeatures(ConfiguredFeature<?, ?> feature) {
        // 原版有三个配置类覆盖 getFeatures()（javap 实测）：
        //   RandomFeatureConfiguration        -> features(List<WeightedPlacedFeature>) + defaultFeature
        //   RandomBooleanFeatureConfiguration -> featureTrue + featureFalse
        //   SimpleRandomFeatureConfiguration  -> features(HolderSet<PlacedFeature>)
        // 早期实现只展开第一个，另外两类里的树会被静默漏出保护目录，因此这里三类都展开。
        var result = new java.util.ArrayList<ConfiguredFeature<?, ?>>();
        var config = feature.config();
        if (config instanceof RandomFeatureConfiguration random) {
            for (var weighted : random.features) {
                if (weighted == null) continue;
                addPlaced(result, weighted.feature);
            }
            addPlaced(result, random.defaultFeature);
        } else if (config instanceof RandomBooleanFeatureConfiguration both) {
            addPlaced(result, both.featureTrue);
            addPlaced(result, both.featureFalse);
        } else if (config instanceof SimpleRandomFeatureConfiguration simple) {
            for (var placed : simple.features) addPlaced(result, placed);
        }
        return result;
    }

    /** Resolves one placed-feature holder to its configured feature, tolerating absent entries. */
    private static void addPlaced(List<ConfiguredFeature<?, ?>> into,
            net.minecraft.core.Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature> holder) {
        if (holder == null) return;
        var placed = holder.value();
        if (placed == null || placed.feature() == null) return;
        var nested = placed.feature().value();
        if (nested != null) into.add(nested);
    }
    private static String hash(JsonObject tree) { return WorldRecipeGuard.fingerprint(Map.of("tree", tree)); }
    private static int integer(JsonObject value, String name) {
        int result = value.get(name).getAsBigDecimal().intValueExact();
        if (result < 0 || result > 2048) throw unsupported("invalid tree integer " + name + "=" + result);
        return result;
    }
    private static int maximum(JsonElement value) {
        var provider = IntProvider.CODEC.parse(JsonOps.INSTANCE, Objects.requireNonNull(value, "missing tree provider")).getOrThrow();
        if (provider.getMinValue() < 0 || provider.getMaxValue() > 2048) throw unsupported("invalid tree provider range");
        return provider.getMaxValue();
    }
    private static String type(JsonObject value, String suffix) {
        String type = value.get("type").getAsString();
        if (!type.startsWith("minecraft:") || !type.endsWith(suffix)) throw unsupported("unknown tree type " + type);
        return type.substring("minecraft:".length(), type.length() - suffix.length());
    }
    private static int margin(JsonObject decorator) {
        String type = decorator.get("type").getAsString();
        return switch (type) {
            case "minecraft:trunk_vine", "minecraft:leave_vine", "minecraft:cocoa", "minecraft:beehive",
                    "minecraft:attached_to_leaves" -> 1;
            case "minecraft:alter_ground" -> 5;
            default -> throw unsupported("unknown tree decorator " + type);
        };
    }
    private static WallFailure unsupported(String message) { return new WallFailure(FailureCode.UNSUPPORTED_ADAPTER, message); }
}
