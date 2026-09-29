package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class WallContractEquivalenceTest {
    @Test
    void nearestEdgeOptimizationMatchesOriginalScanOnCurvesSlopesAndFarCoordinates() {
        var random = new Random(741821);
        for (int origin : new int[]{-1_000_000, -256, 0, 1_000_000}) {
            for (int profile = 0; profile < 4; profile++) {
                var ribbon = new ArrayList<Station>();
                for (int n = 0; n <= 128; n++) {
                    int bend = switch (profile) {
                        case 0 -> 0;
                        case 1 -> n < 64 ? n : 128-n;
                        case 2 -> (int)StrictMath.round(12*StrictMath.sin(n/8.0));
                        default -> n < 64 ? 0 : n-64;
                    };
                    ribbon.add(new Station(n*1024L, new Point2q((origin+n*4L)*256,(origin+bend)*256L),
                            6000+(n%17)/4,0));
                }
                for (int sample=0; sample<600; sample++) {
                    int x=origin-8+random.nextInt(536),z=origin-32+random.nextInt(132);
                    assertEquals(original(ribbon,x,z),WallSection.contract(ribbon,x,z),
                            "profile="+profile+" x="+x+" z="+z);
                }
            }
        }
    }

    @Test
    void equalDistanceRetainsFirstEdgeAndEndpointsRetainOriginalRounding() {
        var ribbon=List.of(new Station(0,new Point2q(0,0),84,0),
                new Station(1024,new Point2q(1024,0),85,0),
                new Station(1280,new Point2q(1024,256),85,0),
                new Station(2304,new Point2q(0,256),86,0));
        for(int x=-3;x<=7;x++)for(int z=-3;z<=4;z++)
            assertEquals(original(ribbon,x,z),WallSection.contract(ribbon,x,z));
        var tied=WallSection.contract(ribbon,1,0);
        assertTrue(tied.globalSQ8()<1024,"equidistant return edge must not replace first edge");
    }

    // Frozen pre-optimization implementation, kept independent of production helpers.
    private static WallSection.ColumnContract original(List<Station> stations,int x,int z) {
        long px=x*256L+128,pz=z*256L+128,best=Long.MAX_VALUE;
        WallSection.ColumnContract result=null;
        for(int i=1;i<stations.size();i++) {
            var a=stations.get(i-1);var b=stations.get(i);
            long vx=b.point().xQ8()-a.point().xQ8(),vz=b.point().zQ8()-a.point().zQ8();
            long wx=px-a.point().xQ8(),wz=pz-a.point().zQ8(),den=vx*vx+vz*vz;
            if(den==0)throw new IllegalArgumentException("zero station edge");
            long num=Math.clamp(wx*vx+wz*vz,0,den);
            long nx=a.point().xQ8()+FixedMath.mulDiv(vx,num,den),nz=a.point().zQ8()+FixedMath.mulDiv(vz,num,den);
            long dx=px-nx,dz=pz-nz,dist=dx*dx+dz*dz;
            if(dist>=best)continue;
            best=dist;
            long s=a.globalSQ8()+FixedMath.mulDiv(b.globalSQ8()-a.globalSQ8(),num,den);
            long lateral=FixedMath.mulDiv(vx,wz,FixedMath.hypotQ8(vx,vz))-FixedMath.mulDiv(vz,wx,FixedMath.hypotQ8(vx,vz));
            int height=0;boolean found=false;
            for(int n=1;n<stations.size();n++) {
                var left=stations.get(n-1);var right=stations.get(n);
                if(s>=left.globalSQ8()&&s<=right.globalSQ8()) {
                    height=left.walkY()+(int)FixedMath.roundDiv((right.walkY()-left.walkY())*(s-left.globalSQ8()),right.globalSQ8()-left.globalSQ8());
                    found=true;break;
                }
            }
            if(!found)throw new IllegalArgumentException("outside ribbon");
            result=new WallSection.ColumnContract(s,FixedMath.floorBlock(lateral),height);
        }
        return result;
    }
}
