package local.plaincraft.greatwall.generation;

import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 只读校验：盖章完成后，把"规划里每一格"和世界里的实际方块对一遍。
 *
 * <p>它 <b>不写任何方块</b>，只打日志 —— 目的是定位"盖完却缺格"到底是
 * 「写失败（应为实心却是空气）」还是「列表里根本没有这一格」。
 */
public final class StampVerify {
    private StampVerify() {
    }

    public static void verify(ServerLevel level, String name, int originX, int originY, int originZ,
                              List<int[]> coords, List<BlockState> states) {
        if (level == null || coords == null || states == null) return;
        int missing = 0;
        int wrong = 0;
        int samples = 0;
        int n = Math.min(coords.size(), states.size());
        for (int i = 0; i < n; i++) {
            int[] p = coords.get(i);
            BlockPos pos = new BlockPos(originX + p[0], originY + p[1], originZ + p[2]);
            BlockState want = states.get(i);
            if (want == null) continue;
            BlockState got = level.getBlockState(pos);
            if (got == want) continue;
            if (got.isAir()) missing++; else wrong++;
            if (samples++ < 30) {
                GreatWallMod.LOGGER.info("[GreatWall:verify] {} pos={} want={} got={}",
                        name, pos, want.getBlock(), got.getBlock());
            }
        }
        GreatWallMod.LOGGER.info("[GreatWall:verify] {} expected={} missing={} wrong={} (read-only, nothing written)",
                name, n, missing, wrong);
    }
}
