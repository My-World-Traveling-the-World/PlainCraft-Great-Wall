package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

final class RootFootprintGuardTest {
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
