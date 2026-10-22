package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stamps the reference gate (a 1.12 build the player supplied, exported to CSV at authoring time) into
 * the world one slice per tick. The template is plain data - 14,817 blocks with their palette - so the
 * gate keeps every detail of the original build instead of being approximated by rules.
 */
public final class GateTemplate {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/gate.csv";
    private static List<int[]> blocks;   // x, y, z, state id
    private static List<BlockState> states;
    private static int cursor;
    private static boolean loaded;
    private static boolean done;
    private static int originX, originY, originZ;

    private GateTemplate() {
    }

    /** Starts stamping at the given origin, once per world session. */
    public static void start(ServerLevel level, int x, int y, int z) {
        if (done || loaded) return;
        originX = x;
        originY = y;
        originZ = z;
        load();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        blocks = new ArrayList<>();
        states = new ArrayList<>();
        try (InputStream in = GateTemplate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 4) continue;
                ResourceLocation id = ResourceLocation.tryParse("minecraft:" + parts[3]);
                BlockState state = id == null ? null : BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                if (state == null) state = Blocks.STONE_BRICKS.defaultBlockState();
                blocks.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                states.add(state);
            }
        } catch (Exception ignored) {
            // A missing template simply means nothing is stamped.
        }
    }

    /** One bounded slice per tick. */
    public static void tick(ServerLevel level) {
        if (done || !loaded || blocks == null || blocks.isEmpty()) return;
        int budget = 2000;
        while (cursor < blocks.size() && budget-- > 0) {
            int[] p = blocks.get(cursor);
            BlockPos pos = new BlockPos(originX + p[0], originY + p[1], originZ + p[2]);
            level.setBlock(pos, states.get(cursor), 2);
            cursor++;
        }
        if (cursor >= blocks.size()) {
            done = true;
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:wonder] GATE_TEMPLATE_DONE blocks={} origin=({},{},{})",
                    blocks.size(), originX, originY, originZ);
        }
    }

    public static boolean finished() {
        return done;
    }
}
