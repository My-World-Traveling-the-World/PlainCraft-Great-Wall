package local.plaincraft.greatwall.generation;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 与完整稳定排序对拍；快照原型不代表动态队列维护已经完成。 */
class BackfillCandidateIndexTest {
    @Test void randomQueriesMatchWholeStableSortWithDuplicatesAndIntWrap() {
        var random=new Random(91120);
        for(int size:new int[]{0,1,63,64,65,512,4096}) {
            var keys=new ArrayList<ChunkKey>();
            for(int i=0;i<size;i++)keys.add(key(i%7==0?random.nextInt():random.nextInt(2000)-1000,
                    i%7==0?random.nextInt():random.nextInt(2000)-1000));
            var index=new BackfillCandidateIndex(keys);
            for(int j=0;j<40;j++)for(int limit:new int[]{0,1,64,6000}) {
                double cx=random.nextInt(50000)-25000,cz=random.nextInt(50000)-25000;
                assertEquals(expected(keys,limit,cx,cz),index.select(limit,true,cx,cz).keys());
            }
        }
    }

    @Test void equalDistanceUsesSnapshotOrderEvenAcrossBranches() {
        var keys=new ArrayList<ChunkKey>();
        for(int i=0;i<256;i++)keys.add(new ChunkKey("fixture:dimension_"+i,i%2==0?-1:0,i%2==0?0:-1));
        Collections.shuffle(keys,new Random(910));
        var index=new BackfillCandidateIndex(keys);
        assertEquals(keys.subList(0,64),index.select(64,true,0,0).keys());
        assertEquals(keys.subList(0,64),index.select(64,false,999,999).keys());
        assertEquals(0,index.select(64,false,999,999).examined());
    }

    @Test void snapshotCannotSilentlyObserveLaterQueueMutationAndNonFiniteQueriesKeepOrder() {
        var keys=new ArrayList<>(List.of(key(0,0),key(300,300),key(Integer.MAX_VALUE,Integer.MIN_VALUE)));
        var frozen=List.copyOf(keys);var index=new BackfillCandidateIndex(keys);
        keys.clear();
        for(double cx:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,1e308})
            assertEquals(expected(frozen,2,cx,0),index.select(2,true,cx,0).keys());
        assertEquals(frozen,index.select(9,false,0,0).keys());
    }

    @Test void sparseQueryPrunesActualDistanceEvaluationsButKeepsAllSixtyFour() {
        var keys=new ArrayList<ChunkKey>();
        for(int x=0;x<128;x++)for(int z=0;z<128;z++)keys.add(key(x*32,z*32));
        var result=new BackfillCandidateIndex(keys).select(64,true,0,0);
        assertEquals(64,result.keys().size());
        assertEquals(expected(keys,64,0,0),result.keys());
        assertTrue(result.examined()<1024,"必须真实剪枝，而非对完整扫描换名字");
        assertTrue(result.examined()>=64,"不得用空候选少算工作量");
    }

    private static List<ChunkKey> expected(List<ChunkKey> keys,int limit,double cx,double cz) {
        var sorted=new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(key->{
            double dx=(key.x()<<4)+8-cx,dz=(key.z()<<4)+8-cz;
            return dx*dx+dz*dz;
        }));
        return sorted.subList(0,Math.min(limit,sorted.size()));
    }
    private static ChunkKey key(int x,int z) { return new ChunkKey("minecraft:overworld",x,z); }
}
