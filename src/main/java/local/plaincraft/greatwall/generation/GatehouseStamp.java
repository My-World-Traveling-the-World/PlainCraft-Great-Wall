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

/**
 * Stamps the reference gatehouse (the wooden two-tier tower the player supplied, cropped from their
 * 1.12 build) on top of a gate. The source is sampled at half size, so the 48x30x32 original lands as
 * a 24x15x16 building that sits one block wider than the wall on each side - the way a real gatehouse
 * overhangs its wall.
 */
public final class GatehouseStamp {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/gatehouse.csv";

    private static List<BlockState[]> grid;   // source rows, y-major
    private static int sx, sy, sz;
    private static boolean loaded;
    private static boolean done;
    private static int originX, originY, originZ;
    private static int cursor;
    private static boolean autoGround;
    private static int budget;

    private GatehouseStamp() {
    }

    /** Stamps the gatehouse so its base sits on the walkway at ({@code x}, {@code y}, {@code z}). */
    public static void start(int x, int y, int z) {
        originX = x;
        originY = y;
        originZ = z;
        autoGround = true;
        cursor = 0;
        done = false;
        load();
    }

    public static void tick(ServerLevel level) {
        if (done || !loaded || grid == null) return;
        if (autoGround && level != null) {
            int ground = level.getChunkSource().getChunkNow(0, 0) != null
                    ? level.getChunkSource().getChunkNow(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 0, 0)
                    : 40;
            originY = ground + 20;
            autoGround = false;
        }
        int tx = sx / 2, ty = sy / 2, tz = sz / 2;
        int total = tx * ty * tz;
        int remaining = 2000;
        while (cursor < total && remaining-- > 0) {
            int i = cursor++;
            int gx = i % tx;
            int gy = (i / tx) % ty;
            int gz = i / (tx * ty);
            BlockState state = grid.get(gy * sz + gz)[gx];
            if (state == null) continue;
            level.setBlock(new BlockPos(originX + gx, originY + gy, originZ + gz), state, 2);
        }
        if (cursor >= total) {
            done = true;
            GreatWallMod.LOGGER.info("[GreatWall:wonder] GATEHOUSE_STAMPED origin=({},{},{}) size={}x{}x{}",
                    originX, originY, originZ, tx, ty, tz);
        }
    }

    public static boolean finished() {
        return done;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        List<BlockState[]> rows = new ArrayList<>();
        try (InputStream in = GatehouseStamp.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<int[]> coords = new ArrayList<>();
            List<BlockState> states = new ArrayList<>();
            int maxX = 0, maxY = 0, maxZ = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 4) continue;
                int px = Integer.parseInt(parts[0]);
                int py = Integer.parseInt(parts[1]);
                int pz = Integer.parseInt(parts[2]);
                if (px > maxX) maxX = px;
                if (py > maxY) maxY = py;
                if (pz > maxZ) maxZ = pz;
                ResourceLocation id = ResourceLocation.tryParse("minecraft:" + parts[3]);
                BlockState state = id == null ? null : BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                coords.add(new int[] {px, py, pz});
                states.add(state);
            }
            sx = maxX + 1;
            sy = maxY + 1;
            sz = maxZ + 1;
            BlockState[][] volume = new BlockState[sy * sz][sx];
            for (int i = 0; i < coords.size(); i++) {
                int[] p = coords.get(i);
                volume[p[1] * sz + p[2]][p[0]] = states.get(i);
            }
            // Downsample by 2: each output voxel reads the centre of its 2x2x2 source cell.
            grid = new ArrayList<>();
            int ty = sy / 2, tz = sz / 2;
            for (int gy = 0; gy < ty; gy++) {
                for (int gz = 0; gz < tz; gz++) {
                    BlockState[] row = new BlockState[sx / 2];
                    for (int gx = 0; gx < sx / 2; gx++) {
                        row[gx] = volume[(gy * 2 + 1) * sz + (gz * 2 + 1)][gx * 2 + 1];
                    }
                    grid.add(row);
                }
            }
        } catch (Exception ignored) {
            // A missing template simply means nothing is stamped.
        }
    }
}
