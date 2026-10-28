package local.plaincraft.greatwall.generation;

import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地基填充材质的选择规则。
 *
 * <p>地基用的是「模板全局最底层」那一层的材质。玩家会在建筑里放**光源方块**
 * （`minecraft:light`，不可见、无碰撞），它也可能正好落在最底层（例如门洞地面那一格放灯）。
 * 如果直接取 CSV 里第一个遇到的最底层方块，整个地基就可能被填成发光方块——从外面看像凭空多出一层光。
 *
 * <p>规则固定为：**取最底层出现次数最多的材质，且排除光源方块**；一个都挑不出来时退回石头。
 */
public final class FoundationMaterial {
    private FoundationMaterial() {
    }

    public static BlockState pick(Map<BlockState, Integer> counts) {
        BlockState best = null;
        int bestCount = 0;
        if (counts != null) {
            for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
                BlockState state = entry.getKey();
                if (state == null || state.is(Blocks.LIGHT)) continue;
                Integer count = entry.getValue();
                if (count == null) continue;
                if (count > bestCount) {
                    bestCount = count;
                    best = state;
                }
            }
        }
        return best == null ? Blocks.STONE.defaultBlockState() : best;
    }
}
