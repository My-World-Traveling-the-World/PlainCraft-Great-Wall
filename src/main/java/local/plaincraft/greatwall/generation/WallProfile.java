package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The wall module, taken verbatim from the player's hand-polished reference wall.
 *
 * <p>Forty-eight blocks long, eleven wide and sixteen tall: a solid stone core faced with stone bricks,
 * a quartz belt along both edges of the walkway, a continuous slab-capped parapet on one side and
 * three-merlon-one-gap crenellation with stair caps on the other. Because the module carries the whole
 * rhythm itself, the generator just repeats it along the route.
 */
public final class WallProfile {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/wall_module.csv";
    /** Length of the repeating module along the wall. */
    public static final int PERIOD = 48;
    /** The wall spans t in [-HALF, HALF]; eleven blocks across. */
    public static final int HALF = 5;
    /** Total height of the module. */
    public static final int DEPTH = 18;
    /** How far the top of the module sits above the walkway. */
    public static final int ABOVE_WALK = 3;

    private static BlockState[][][] rows;   // [phase][depth][t + HALF]
    private static boolean loaded;

    private WallProfile() {
    }

    /** Block at module offset {@code phase}, lateral offset {@code t} and {@code depth}, or null. */
    public static BlockState at(int phase, int t, int depth) {
        load();
        if (rows == null || depth < 0 || depth >= DEPTH) return null;
        int x = t + HALF;
        if (x < 0 || x >= HALF * 2 + 1) return null;
        return rows[Math.floorMod(phase, PERIOD)][depth][x];
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        BlockState[][][] table = new BlockState[PERIOD][DEPTH][HALF * 2 + 1];
        try (InputStream in = WallProfile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // The block spec itself contains commas, so split only the three numeric fields off.
                String[] parts = line.split(",", 4);
                if (parts.length != 4) continue;
                int phase = Integer.parseInt(parts[0]);
                int t = Integer.parseInt(parts[1]);
                int depth = Integer.parseInt(parts[2]);
                int x = t + HALF;
                if (phase < 0 || phase >= PERIOD || depth < 0 || depth >= DEPTH || x < 0 || x >= HALF * 2 + 1) continue;
                table[phase][depth][x] = BlockSpec.parse(parts[3]);
            }
            rows = table;
        } catch (Exception broken) {
            // 过去静默吞掉：wall_module.csv 坏了会让整面墙「没有形状」，排查时毫无线索。
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] TEMPLATE_LOAD_FAILED resource={} ({})",
                    RESOURCE, broken.toString(), broken);
            rows = null;
        }
    }

    static {
        Blocks.STONE_BRICKS.defaultBlockState();
    }
}
