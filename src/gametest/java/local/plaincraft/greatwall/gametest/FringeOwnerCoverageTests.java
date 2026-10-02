package local.plaincraft.greatwall.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Independent selection of the retained pre-fix owner-domain counterexample. */
public final class FringeOwnerCoverageTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void fringeOwnerOutsideCaptureDomainIsSkippedBeforeApply(GameTestHelper helper) {
        new ProductionPipelineTests().fringeOwnerOutsideCaptureDomainIsSkippedBeforeApply(helper);
    }
}
