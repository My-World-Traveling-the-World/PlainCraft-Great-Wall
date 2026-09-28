package local.plaincraft.greatwall.gametest;

import local.plaincraft.greatwall.BuildContract;
import local.plaincraft.greatwall.diagnostics.RuntimeVersions;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class SkeletonGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
    public void coreLoadsWithoutOptionalIntegrations(GameTestHelper helper) {
        helper.assertTrue(FabricLoader.getInstance().isModLoaded(BuildContract.MOD_ID),
                "Core mod must load on the dedicated GameTest server");
        helper.assertTrue(RuntimeVersions.capture().stream().anyMatch(v ->
                v.id().equals("minecraft") && v.version().equals(BuildContract.MINECRAFT_VERSION)),
                "Minecraft version must match the frozen build contract");
        helper.succeed();
    }
}

