package local.plaincraft.greatwall.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Original CURVED_SITES index 58, with its complete unchanged native assertions. */
public final class CurvedGateRegressionTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-curved-gate-regression")
    public void originalFortGateBendPositiveSlopeNegative(GameTestHelper helper) {
        AdjacentComponentMatrixTests.curvedGateRegression(helper);
    }
}
