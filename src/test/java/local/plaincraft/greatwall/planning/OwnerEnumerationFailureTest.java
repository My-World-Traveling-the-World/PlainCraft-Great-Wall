package local.plaincraft.greatwall.planning;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 实际调用枚举及正式编译入口，避免仅测日志却漏掉启动成功的错误结案。 */
class OwnerEnumerationFailureTest {
    private static final String H = PlanningFixtures.HASH;

    @Test void incompleteFootprintCannotDisappearFromTheOwnerList() {
        var plan = oversizedFootprint();
        var coverage = new CoveragePlanner();
        // 先证明覆盖预算真实失败，不能用夹具无墙、零循环得到预期异常。
        var reason = assertThrows(WallFailure.class, () -> coverage.prepareOwners(
                new PlanWindow(List.of(plan))).forCell(new CellKey(H, "minecraft:overworld", 0)));
        assertEquals(FailureCode.CAPACITY, reason.code());
        var failure = assertThrows(WallFailure.class, () -> PlanningOwnerIndex.prepare(
                new PlanWindow(List.of(plan)), H, "minecraft:overworld", coverage));
        assertEquals(FailureCode.CAPACITY, failure.code());
        assertEquals(reason.getMessage(), failure.getMessage());
    }

    @Test void incompleteEnumerationNeverReturnsASuccessfulCompileReport() {
        var resolved = new AtomicInteger();
        var published = new AtomicInteger();
        var compiler = new PlanningChunkCompiler(input -> {
            resolved.incrementAndGet();
            throw new AssertionError("枚举失败前不应开始编译");
        }, new ChunkPlanCompiler(), FineHeightIndex.of(List.of()), List.of());
        var failure = assertThrows(WallFailure.class, () -> compiler.compileIntoReport(
                new PlanWindow(List.of(oversizedFootprint())), H, "minecraft:overworld",
                null, ignored -> published.incrementAndGet(), () -> {}));
        assertEquals(FailureCode.CAPACITY, failure.code());
        assertEquals(0, resolved.get());
        assertEquals(0, published.get());
    }

    private static SegmentPlan oversizedFootprint() {
        var recipe = PlanningFixtures.recipe(1);
        var geometry = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        var first = new Station(0, new Point2q(128,128),84,0);
        var last = new Station(2048L*256,new Point2q(2048L*256+128,128),84,0);
        var sites = new java.util.ArrayList<SiteRecord>();
        // 多个同弧长但分散的构件触发真实覆盖上限；不靠空站表或不存在的模板造异常。
        for(int i=0;i<32;i++) {
            var frame=new Frame(new Point2q((i*100L)*256+128,128),1<<30,0,84,false);
            var id=new SiteId(recipe.wallId(),SiteKind.FORT,i);
            sites.add(new SiteRecord(id,128L*256,frame,new Bounds(i*100-50,0,-50,i*100+50,120,50),
                    SiteState.PLANNED,Optional.empty(),Optional.empty()));
        }
        return new SegmentPlan(geometry,PrefixSummaryIndex.origin(),
                new StationTable(List.of(first,last),List.of(),H),sites,H);
    }
}
