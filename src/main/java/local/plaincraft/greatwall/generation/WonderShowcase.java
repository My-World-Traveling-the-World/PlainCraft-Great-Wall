package local.plaincraft.greatwall.generation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Isolated showcase models, placed behind the spawn point so every building can be judged on its own
 * before it is merged with the wall. Nothing here is connected to a route: each model is a standalone
 * footprint with its own plinth, bands and roof.
 *
 * <pre>
 *   (-64,0)   敌台 24x24x30
 *   (-64,48)  城门 24x20x28
 *   (-64,96)  烽火台 16x16x26
 *   (-64,144) 入海端台 40x40x18
 *   (-64,200) 堡垒 14x14x10
 * </pre>
 */
public final class WonderShowcase {
    private static final BlockState FOUNDATION = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
    private static final BlockState BODY = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState TRIM = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState CAP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    private static final BlockState TILE = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WonderShowcase() {
    }

    /** True when the column belongs to one of the showcase models. */
    public static long paint(ServerLevel level, LevelChunk chunk, int x, int z, int ground) {
        long written = 0L;
        written += tower(level, chunk, x, z, ground, -64, 0);
        written += gate(level, chunk, x, z, ground, -64, 48);
        written += beacon(level, chunk, x, z, ground, -64, 96);
        written += platform(level, chunk, x, z, ground, -64, 144);
        written += fort(level, chunk, x, z, ground, -64, 200);
        return written;
    }

    private static long tower(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int cx, int cz) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > 12 || Math.abs(dz) > 12) return 0L;
        long written = 0L;
        int top = ground + 30;
        for (int y = ground - 1; y < top; y++) {
            int dy = top - 1 - y;
            int half = 12 - Math.min(2, dy / 12);
            if (Math.abs(dx) >= half || Math.abs(dz) >= half) continue;
            boolean rim = Math.abs(dx) >= half - 3 || Math.abs(dz) >= half - 3;
            if (!rim && dy > 3) continue;
            written += put(level, chunk, x, y, z, dy <= 2 ? FOUNDATION : (dy % 6 == 0 ? TRIM : BODY));
        }
        for (int y = 0; y < 2; y++) {
            if (Math.abs(dx) >= 12 || Math.abs(dz) >= 12) continue;
            int ph = Math.floorMod(x + z, 4);
            if (y == 1 && ph >= 2) continue;
            written += put(level, chunk, x, top + y, z, y == 1 ? CAP : BODY);
        }
        return written;
    }

    private static long gate(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int cx, int cz) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > 12 || Math.abs(dz) > 14) return 0L;
        long written = 0L;
        int top = ground + 28;
        for (int y = ground - 1; y < top; y++) {
            int dy = top - 1 - y;
            boolean opening = Math.abs(dx) <= 4 && dy < 10;
            if (opening) continue;
            written += put(level, chunk, x, y, z, dy <= 2 ? FOUNDATION : (dy % 6 == 0 ? TRIM : BODY));
        }
        if (Math.abs(dx) <= 6) written += put(level, chunk, x, top, z, TILE);
        return written;
    }

    private static long beacon(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int cx, int cz) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > 8 || Math.abs(dz) > 8) return 0L;
        long written = 0L;
        int top = ground + 26;
        for (int y = ground - 1; y < top; y++) {
            int dy = top - 1 - y;
            int half = 8 - Math.min(2, dy / 10);
            if (Math.abs(dx) >= half || Math.abs(dz) >= half) continue;
            written += put(level, chunk, x, y, z, dy <= 2 ? FOUNDATION : (dy % 6 == 0 ? TRIM : BODY));
        }
        return written;
    }

    private static long platform(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int cx, int cz) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > 20 || Math.abs(dz) > 20) return 0L;
        long written = 0L;
        int top = ground + 18;
        for (int y = ground - 1; y < top; y++) {
            int dy = top - 1 - y;
            int half = 20 - Math.min(12, dy / 2);
            if (Math.abs(dx) >= half || Math.abs(dz) >= half) continue;
            written += put(level, chunk, x, y, z, dy <= 2 ? FOUNDATION : (dy % 6 == 0 ? TRIM : BODY));
        }
        return written;
    }

    private static long fort(ServerLevel level, LevelChunk chunk, int x, int z, int ground, int cx, int cz) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > 7 || Math.abs(dz) > 7) return 0L;
        long written = 0L;
        int top = ground + 10;
        for (int y = ground - 1; y < top; y++) {
            int dy = top - 1 - y;
            boolean rim = Math.abs(dx) >= 5 || Math.abs(dz) >= 5;
            if (!rim && dy > 2) continue;
            written += put(level, chunk, x, y, z, dy <= 2 ? FOUNDATION : BODY);
        }
        return written;
    }

    private static long put(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (chunk.getBlockState(pos).equals(state)) return 0L;
        level.setBlock(pos, state, 2);
        return 1L;
    }
}
