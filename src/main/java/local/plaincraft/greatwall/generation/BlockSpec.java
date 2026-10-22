package local.plaincraft.greatwall.generation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Parses a block specification of the form {@code stone_brick_stairs[facing=north,half=top]} into a
 * {@link BlockState}.
 *
 * <p>Carrying the properties matters: without them every stair faces north and every slab sits on the
 * bottom half, which is exactly what made the exported gatehouse look stripped of detail.
 */
public final class BlockSpec {
    private BlockSpec() {
    }

    public static BlockState parse(String spec) {
        if (spec == null || spec.isEmpty()) return Blocks.STONE_BRICKS.defaultBlockState();
        String name = spec;
        String props = null;
        int bracket = spec.indexOf('[');
        if (bracket >= 0) {
            name = spec.substring(0, bracket);
            int end = spec.lastIndexOf(']');
            props = spec.substring(bracket + 1, end > bracket ? end : spec.length());
        }
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
        if (block == null) block = Blocks.STONE_BRICKS;
        BlockState state = block.defaultBlockState();
        if (props == null || props.isEmpty()) return state;
        for (String pair : props.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            state = apply(state, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return state;
    }

    private static BlockState apply(BlockState state, String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return set(state, property, value);
        }
        return state;   // unknown property for this block: ignore rather than fail
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }
}
