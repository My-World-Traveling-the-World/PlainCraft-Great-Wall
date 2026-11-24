package local.plaincraft.greatwall.gametest;

import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** 必须在真实Mixin环境加载目标类，普通Java编译抓不到注入签名或注册错误。 */
public final class StructureMixinGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
    public void standardStructurePlacementHookIsTransformed(GameTestHelper helper) {
        helper.assertTrue(Arrays.stream(StructureStart.class.getDeclaredMethods()).anyMatch(
                m -> m.getName().contains("greatwall$protectFootprint")), "结构放置Mixin未注入");
        helper.succeed();
    }
}
