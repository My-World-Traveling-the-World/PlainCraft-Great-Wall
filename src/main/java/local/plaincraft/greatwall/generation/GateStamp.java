package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stamps the player's reference gate - a 1.12 build of a complete gate complex (wall segment, arched
 * gateway, stairs and the two-tier wooden gatehouse) - straight into the world.
 *
 * <p>The model is 128 x 43 x 32 and is stamped at full size: this is the gate, not a decoration on top
 * of a procedurally generated one. The rule generator leaves the footprint empty so the two never
 * overlap.
 */
public final class GateStamp {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/gate.csv";
    /** Footprint in blocks, from the exported model. */
    public static final int WIDTH = 128;
    public static final int HEIGHT = 43;
    public static final int DEPTH = 32;

    private static List<int[]> coords;
    private static List<BlockState> states;
    private static boolean loaded;
    private static boolean done;
    private static boolean autoGround = true;
    private static int originX, originY, originZ;
    private static int cursor;

    private GateStamp() {
    }

    /** Centres the gate on ({@code centreX}, {@code centreZ}); the base sits on the local surface. */
    public static void start(int centreX, int centreZ) {
        originX = centreX - WIDTH / 2;
        originZ = centreZ - DEPTH / 2;
        originY = 0;
        autoGround = true;
        cursor = 0;
        done = false;
        load();
    }

    public static void tick(ServerLevel level) {
        if (done || !loaded || coords == null || coords.isEmpty()) return;
        if (autoGround) {
            int ground = level.getChunkSource().getChunkNow(originX >> 4, originZ >> 4) != null
                    ? level.getChunkSource().getChunkNow(originX >> 4, originZ >> 4)
                            .getHeight(Heightmap.Types.WORLD_SURFACE, originX & 15, originZ & 15)
                    : 64;
            originY = ground;
            autoGround = false;
        }
        int budget = 2000;
        while (cursor < coords.size() && budget-- > 0) {
            int[] p = coords.get(cursor);
            level.setBlock(new BlockPos(originX + p[0], originY + p[1], originZ + p[2]), states.get(cursor), 2);
            cursor++;
        }
        if (cursor >= coords.size()) {
            done = true;
            GreatWallMod.LOGGER.info("[GreatWall:wonder] GATE_STAMPED blocks={} origin=({},{},{})",
                    coords.size(), originX, originY, originZ);
        }
    }

    public static boolean finished() {
        return done;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        coords = new ArrayList<>();
        states = new ArrayList<>();
        try (InputStream in = GateStamp.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 4) continue;
                ResourceLocation id = ResourceLocation.tryParse("minecraft:" + parts[3]);
                BlockState state = id == null ? null : BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                coords.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                states.add(state == null ? Blocks.STONE_BRICKS.defaultBlockState() : state);
            }
        } catch (Exception ignored) {
            // A missing template simply means nothing is stamped.
        }
    }
}
