package local.plaincraft.greatwall.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Exact original HYDRO cases 1 and 19, using the unchanged matrix assertions. */
public final class HydroBoundaryRegressionTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-hydro-boundary")
    public void touchingNarrowWaterRetainsWholeTowers(GameTestHelper helper) {
        AdjacentComponentMatrixTests.touchingNarrowWaterRegression(helper);
    }
}
