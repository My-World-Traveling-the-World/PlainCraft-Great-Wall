package local.plaincraft.greatwall.lifecycle;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.generation.CorridorIndex;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import local.plaincraft.greatwall.planning.PrefixSummaryIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 直接执行生产发布编排；严格门仍要等实际施工，发布标志不能冒充已经建成。 */
class StartupWindowPublisherTest {
    private static final String H=PlanningFixtures.HASH;
    @BeforeEach void start(){PrewarmProgress.clear();PrewarmProgress.begin(1,2);}
    @AfterEach void clear(){PrewarmProgress.clear();}

    @Test void publicationFinishesAfterAllFootprintsAndNeverOpensPaintGateEarly() {
        var access=new Access();var release=new int[1];
        var publisher=new StartupWindowPublisher(PlanningFixtures.recipe(0),()->{
            assertFalse(PrewarmProgress.isPublished());release[0]++;access.events.add("release");
        },(window,progress,sink)->{
            for(int x=0;x<3;x++){assertFalse(PrewarmProgress.isPublished());sink.accept(plan(x));progress.accept(x+1,3);}
            return 3;
        },access);
        publisher.publish(List.of(segment()));
        assertEquals(List.of("active","install:0","footprint:0","active","install:1","footprint:1",
                "active","install:2","footprint:2","active","enqueue","release"),access.events);
        assertEquals(3,access.queued.size());assertEquals(1,release[0]);
        assertTrue(PrewarmProgress.isPublished());
        assertFalse(PrewarmProgress.awaitWorldgenReadyStrict(0,TimeUnit.MILLISECONDS),"只有落块阶段能结束严格门");
    }

    @Test void emptyWindowReleasesWithoutCallingCompilerOrClaimingPublication() {
        var released=new int[1];
        new StartupWindowPublisher(PlanningFixtures.recipe(0),()->released[0]++,
                (window,progress,sink)->fail("无计划不得编译"),new Access()).publish(List.of());
        assertEquals(1,released[0]);assertFalse(PrewarmProgress.isPublished());
    }

    @Test void zeroSuccessfulOwnersCannotBeDeclaredPublished() {
        var access=new Access();access.rejectAll=true;var release=new int[1];
        new StartupWindowPublisher(PlanningFixtures.recipe(0),()->release[0]++,(w,p,s)->{
            s.accept(plan(0));s.accept(plan(1));return 2;
        },access).publish(List.of(segment()));
        assertFalse(PrewarmProgress.isPublished());assertEquals(1,release[0]);assertTrue(access.queued.isEmpty());
    }

    @Test void originalFailureThresholdAndQueueBeforeFailureRemainUnchanged() {
        for(int failures:new int[]{8,9}) {
            PrewarmProgress.clear();PrewarmProgress.begin(1,2);
            var access=new Access();access.rejected=failures;var releases=new int[1];
            new StartupWindowPublisher(PlanningFixtures.recipe(0),()->releases[0]++,(w,p,s)->{
                for(int x=0;x<=failures;x++)s.accept(plan(x));return failures+1;
            },access).publish(List.of(segment()));
            assertEquals(failures<=8,PrewarmProgress.isPublished(),"原8项容忍边界不能在拆分时改变");
            assertEquals(1,access.queued.size(),"已接纳计划仍须进入补建，不因其他区块失败而丢弃");
            assertEquals(1,releases[0]);
        }
    }

    @Test void lateBatchAfterClosureDoesNotInstallOrPublish() {
        var access=new Access();
        var publisher=new StartupWindowPublisher(PlanningFixtures.recipe(0),()->{},(w,p,s)->{
            s.accept(plan(0));access.closed=true;s.accept(plan(1));return 2;
        },access);
        assertThrows(CancellationException.class,()->publisher.publish(List.of(segment())));
        assertEquals(List.of("active","install:0","footprint:0","active"),access.events);
        assertFalse(PrewarmProgress.isPublished());assertTrue(access.queued.isEmpty());
    }

    private static SegmentPlan segment() {
        var recipe=PlanningFixtures.recipe(0);var g=PlanningFixtures.geometry(recipe,0,Optional.empty());
        return new SegmentPlan(g,PrefixSummaryIndex.origin(),new StationTable(List.of(
                new Station(0,g.points().getFirst(),84,0),new Station(g.localSQ8().getLast(),g.points().getLast(),84,0)),
                List.of(),H),List.of(),H);
    }
    private static ChunkWritePlan plan(int x) {
        return new ChunkWritePlan(new ChunkKey("minecraft:overworld",x,0),H,H,List.of(),List.of(),List.of(),
                List.of(),List.of(),List.of(),H);
    }
    private static final class Access implements StartupWindowPublisher.Access {
        final List<String> events=new ArrayList<>();
        Map<ChunkKey,String> queued=Map.of();boolean closed,rejectAll;int rejected;
        public void active(){events.add("active");if(closed)throw new CancellationException("测试会话关闭");}
        public void install(ChunkWritePlan plan){events.add("install:"+plan.owner().x());
            if(rejectAll||plan.owner().x()<rejected)throw new IllegalStateException("测试发布拒绝");}
        public void footprint(ChunkWritePlan plan,CorridorIndex corridor){
            assertFalse(corridor.boxes().isEmpty());events.add("footprint:"+plan.owner().x());}
        public void enqueue(Map<ChunkKey,String> hashes){events.add("enqueue");queued=Map.copyOf(hashes);}
        public long leases(){return 0;}public long misses(){return 0;}public long suppressed(){return 0;}
    }
}
