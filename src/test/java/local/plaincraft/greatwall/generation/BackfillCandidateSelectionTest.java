package local.plaincraft.greatwall.generation;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 对照冻结的完整稳定排序，断言非空及完整前缀，防止空集合使优化测试全绿。 */
class BackfillCandidateSelectionTest {
    @Test void heapAndSmallQueueKeepExactStablePrefix() {
        var random=new Random(39551);
        for(int n:new int[]{1,63,64,65,512,4096}) {
            var input=new ArrayList<ChunkKey>();
            for(int i=0;i<n;i++)input.add(new ChunkKey("minecraft:overworld",random.nextInt(2000)-1000,random.nextInt(2000)-1000));
            for(int run=0;run<40;run++) {
                double cx=random.nextInt(5000)-2500,cz=random.nextInt(5000)-2500;
                var expected=new ArrayList<>(input);
                expected.sort(Comparator.comparingDouble(key->{
                    double dx=(key.x()<<4)+8-cx,dz=(key.z()<<4)+8-cz;
                    return dx*dx+dz*dz;
                }));
                var actual=BackfillCandidateSelection.select(input,64,true,cx,cz);
                assertEquals(Math.min(64,n),actual.size());
                assertEquals(expected.subList(0,Math.min(64,n)),actual);
            }
        }
    }

    @Test void tiedDistancesKeepEncounterOrderAndNoPlayerKeepsUnsortedPrefix() {
        var tied=new ArrayList<ChunkKey>();
        for(int i=0;i<200;i++)tied.add(new ChunkKey("fixture:dimension_"+i,i%2==0?-1:0,i%2==0?0:-1));
        assertEquals(tied.subList(0,64),BackfillCandidateSelection.select(tied,64,true,0,0));
        Collections.reverse(tied);
        assertEquals(tied.subList(0,64),BackfillCandidateSelection.select(tied,64,false,900,700));
        assertEquals(List.of(),BackfillCandidateSelection.select(tied,0,true,0,0));
        assertEquals(List.of(),BackfillCandidateSelection.select(List.of(),64,true,0,0));
    }
}
