package local.plaincraft.greatwall.generation;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 盖章写入方块时使用的更新 flags。
 *
 * <p>模板里允许出现**光源方块**（`minecraft:light[level=0..15]`）——它不可见、无碰撞，是实现「建筑自带照明」的
 * 正确做法。但光源方块的亮度由光照引擎按区块重算，而盖章默认用的 {@code flag 2}（只同步客户端、不触发
 * 邻居更新）不会让光照引擎重算，结果是**方块在、光不亮**。所以光源方块必须走 {@code flag 3}
 * （{@code UPDATE_ALL}：邻居更新 + 客户端同步），其余方块维持 flag 2（盖章只覆盖自己的格子，不需要邻居更新，
 * 也避免连带唤醒大范围方块更新）。
 */
public final class StampWrite {
    /** 普通模板方块：只同步客户端。 */
    private static final int TEMPLATE = 2;
    /** 需要光照/邻居立即响应的方块（光源方块）。 */
    private static final int EMISSIVE = 3;

    private StampWrite() {
    }

    public static int flags(BlockState state) {
        return state.is(Blocks.LIGHT) ? EMISSIVE : TEMPLATE;
    }
}
