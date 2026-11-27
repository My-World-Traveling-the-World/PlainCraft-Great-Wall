package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

final class RootFootprintGuardTest {
    @Test void endpointJudgesTheBuildingVolumeRegardlessOfMidpointHeight() {
        var mid=new Point3i(1000,65,1000);
        var ax=new Axis(mid,1<<30,0); var axes=java.util.Collections.nCopies(5,ax);
        var footprint=new Bounds(-3090,81,954,-2999,139,1047);
        var building=new local.plaincraft.greatwall.lifecycle.RootStructureScreen.Candidate(mid,0,0,axes,2,footprint);
        assertTrue(NativeRootStructureScreen.blocksRoot(building,footprint,new Bounds(-3080,132,990,-3074,138,997)));
        assertFalse(NativeRootStructureScreen.blocksRoot(building,footprint,new Bounds(-3080,140,990,-3074,143,997)));
        assertTrue(NativeRootStructureScreen.referencePositions(java.util.List.of(footprint)).size()<=81);
        var maxMargin = new Bounds(0,81,0,93+64,139,91+64);
        int chunks=NativeRootStructureScreen.referencePositions(java.util.List.of(maxMargin),121).size();
        assertTrue(chunks>81 && chunks<=121,"最大合法保护圈可以跨越一个租约");
    }

    @Test void everyInitialTangentAndItsEntranceGuardFitThePreRecipeStructureEnvelope() {
        var origin=new Point3i(206,67,953);
        var envelope=NativeRootStructureScreen.footprint(new Axis(origin,1<<30,0));
        for(int degrees=0;degrees<360;degrees++){
            double radians=StrictMath.toRadians(degrees);
            var frame=new Frame(new Point2q(origin.x()*256L,origin.z()*256L),
                    (int)StrictMath.round(StrictMath.cos(radians)*(1L<<30)),
                    (int)StrictMath.round(StrictMath.sin(radians)*(1L<<30)),87,false);
            var actual=Rasterizer.worldBounds(frame,new Bounds(-24,-33,-24,24,8,24));
            assertTrue(envelope.minX()<=actual.minX()-1&&envelope.maxXExclusive()>=actual.maxXExclusive()+1,"X heading="+degrees);
            assertTrue(envelope.minZ()<=actual.minZ()-1&&envelope.maxZExclusive()>=actual.maxZExclusive()+1,"Z heading="+degrees);
            assertTrue(envelope.minY()<=actual.minY()&&envelope.maxYExclusive()>=actual.maxYExclusive());
        }
    }
}
