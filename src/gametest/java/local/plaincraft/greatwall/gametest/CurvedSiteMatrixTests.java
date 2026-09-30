package local.plaincraft.greatwall.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Actual HeightContractPlanner platforms and changing-height curved station connections. */
public final class CurvedSiteMatrixTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000,batch="pgw-curved-site-matrix")
    public void everyOrderedSitePairAcrossBothBendsAndBothHeightProfiles(GameTestHelper helper){
        AdjacentComponentMatrixTests.curvedHeightSitePairs(helper);
    }
}
