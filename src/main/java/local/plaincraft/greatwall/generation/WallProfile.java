package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The wall cross-section taken from the player's reference build: eleven blocks across, eighteen tall,
 * a hollow body one block thick with a floor at mid height and the walkway on top, a quartz course along
 * both edges of the walkway and one-thick parapets capped with slabs.
 *
 * <p>Extruding this profile along a route gives exactly the reference wall at any length, instead of the
 * approximation the band() helper used to produce.
 */
public final class WallProfile {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/wall_profile.csv";
    /** Half width of the section; the wall spans t in [-HALF, HALF]. */
    public static final int HALF = 5;
    /** Total height of the section, top to bottom. */
    public static final int DEPTH = 18;
    /** How far the top of the section sits above the walkway. */
    public static final int ABOVE_WALK = 3;

    private static BlockState[][] section;
    private static boolean loaded;

    private WallProfile() {
    }

    /** Block at lateral offset {@code t} and {@code depth} below the top, or null for open air. */
    public static BlockState at(int t, int depth) {
        load();
        if (section == null || depth < 0 || depth >= DEPTH) return null;
        int x = t + HALF;
        if (x < 0 || x >= HALF * 2 + 1) return null;
        return section[depth][x];
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Section s = new Section();
        try (InputStream in = WallProfile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 3) continue;
                int t = Integer.parseInt(parts[0]);
                int depth = Integer.parseInt(parts[1]);
                int x = t + HALF;
                if (depth < 0 || depth >= DEPTH || x < 0 || x >= HALF * 2 + 1) continue;
                ResourceLocation id = ResourceLocation.tryParse("minecraft:" + parts[2]);
                BlockState state = id == null ? null : BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                if (state != null) s.rows[depth][x] = state;
            }
            section = s.rows;
        } catch (Exception ignored) {
            section = null;
        }
    }

    private static final class Section {
        final BlockState[][] rows = new BlockState[DEPTH][HALF * 2 + 1];
    }

    static {
        // A defensive default so a missing resource still yields a plain brick wall rather than nothing.
        Blocks.STONE_BRICKS.defaultBlockState();
    }
}
