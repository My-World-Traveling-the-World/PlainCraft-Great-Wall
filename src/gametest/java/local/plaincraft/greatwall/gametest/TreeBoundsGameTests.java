package local.plaincraft.greatwall.gametest;

import java.util.List;
import local.plaincraft.greatwall.generation.TreeBoundsCatalog;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;

public final class TreeBoundsGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
    public void allFrozenVanillaTreeConfigurationsEncodeAndProduceFiniteBounds(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var features = registries.registryOrThrow(Registries.CONFIGURED_FEATURE).stream().toList();
        var catalog = new TreeBoundsCatalog(registries, features);
        int count = 0;
        for (var feature : features) {
            if (feature.config() instanceof TreeConfiguration tree) {
                count++;
                var box = catalog.vanilla(new BlockPos(-1024, 64, -2048), tree, new DimensionBounds(-64, 320, 30000000));
                helper.assertTrue(box.minX() <= -1024 && box.maxXExclusive() > -1024
                        && box.minZ() <= -2048 && box.maxZExclusive() > -2048, "origin enclosed");
                helper.assertTrue(box.minY() == -64 && box.maxYExclusive() == 320, "full dimension Y used");
            }
        }
        helper.assertTrue(count > 20, "nonvacuous configured tree coverage");
        helper.assertTrue(catalog.maximumRadius() > 0 && catalog.maximumRadius() <= 2048, "finite radius");
        helper.assertTrue(catalog.hash().matches("[0-9a-f]{64}"), "canonical config table identity");
        System.out.println("S1C_TREE_BOUNDS_CONFIGS count=" + count + " maximumRadius=" + catalog.maximumRadius() + " hash=" + catalog.hash());
        helper.succeed();
    }
    @GameTest(template = EMPTY_STRUCTURE)
    public void actualPositionsIncludesNegativeExtremaAndDecoratorWithoutChangingInput(GameTestHelper helper) {
        var positions = List.of(new BlockPos(-100, 70, -200), new BlockPos(-50, 80, -160));
        var box = TreeBoundsCatalog.actualPositions(positions, 5, new DimensionBounds(-64, 320, 30000000));
        helper.assertTrue(box.equals(new Bounds(-105, -64, -205, -44, 320, -154)), "half-open extrema plus5");
        helper.assertTrue(positions.size() == 2 && positions.getFirst().getX() == -100, "input not altered");
        helper.succeed();
    }
}
