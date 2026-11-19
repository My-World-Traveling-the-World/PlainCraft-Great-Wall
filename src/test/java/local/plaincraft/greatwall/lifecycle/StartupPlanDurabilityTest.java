package local.plaincraft.greatwall.lifecycle;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 正式持久化与发布计数一起执行；未落盘计划不得进入缓存/补建队列。 */
class StartupPlanDurabilityTest {
    @TempDir Path root;
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @AfterEach void uninstall(){WallBodyBackfill.install(null);}

    @Test void permanentIoFailureIsBoundedAndNeverAdmitted() {
        var calls=new AtomicInteger();var cause=new IOException("磁盘持续不可写");
        WallBodyBackfill.install(store(plan->{calls.incrementAndGet();throw cause;}));
        var cache=new ArrayList<ChunkWritePlan>();var publication=publish(plan(),cache);
        assertEquals(0,publication.published());assertEquals(1,publication.failed());
        assertTrue(publication.queuedHashes().isEmpty());assertTrue(cache.isEmpty());
        assertEquals(3,calls.get(),"每区块只作三次有界写入，不无限卡住整窗");
        assertSame(cause,publication.firstFailure().getCause());
    }

    @Test void transientIoFailureRetriesThenReopensTheActualWrittenPlan() throws Exception {
        var disk=ChunkEvidenceStore.production(root);var calls=new AtomicInteger();
        WallBodyBackfill.install(store(plan->{if(calls.incrementAndGet()==1)throw new IOException("第一次临时失败");
            disk.persistPlan(plan);}));
        var plan=plan();var cache=new ArrayList<ChunkWritePlan>();var publication=publish(plan,cache);
        assertEquals(2,calls.get());assertEquals(1,publication.published());assertEquals(0,publication.failed());
        assertEquals(List.of(plan),cache);
        assertEquals(plan,new ChunkEvidenceStore(root).readPlan(plan.owner()).orElseThrow());
    }

    @Test void invalidPayloadIsReportedWithoutRetryingIt() {
        var cause=new IllegalArgumentException("配方载荷非法");var calls=new AtomicInteger();
        WallBodyBackfill.install(store(plan->{calls.incrementAndGet();throw cause;}));
        var publication=publish(plan(),new ArrayList<>());
        assertEquals(0,publication.published());assertEquals(1,publication.failed());
        assertSame(cause,publication.firstFailure());assertEquals(1,calls.get());
    }

    @Test void missingStoreCannotClaimDurability() {
        WallBodyBackfill.install(null);
        var cache=new ArrayList<ChunkWritePlan>();var publication=publish(plan(),cache);
        assertEquals(0,publication.published());assertEquals(1,publication.failed());
        assertTrue(cache.isEmpty());assertTrue(publication.queuedHashes().isEmpty());
    }

    @Test void actualFilesystemFailureKeepsBytesAndRecoveryCanBeReopened() throws Exception {
        var evidence=root.resolve("evidence");Files.writeString(evidence,"不能覆盖的障碍文件");
        byte[] before=Files.readAllBytes(evidence);
        WallBodyBackfill.install(store(plan->ChunkEvidenceStore.production(evidence).persistPlan(plan)));
        var plan=plan();var cache=new ArrayList<ChunkWritePlan>();var failed=publish(plan,cache);
        assertEquals(0,failed.published());assertEquals(1,failed.failed());assertTrue(cache.isEmpty());
        assertArrayEquals(before,Files.readAllBytes(evidence));
        Files.delete(evidence);
        var recovered=publish(plan,cache);
        assertEquals(1,recovered.published());assertEquals(0,recovered.failed());
        assertEquals(plan,ChunkEvidenceStore.production(evidence).readPlan(plan.owner()).orElseThrow());
    }

    private static StartupPlanPublication publish(ChunkWritePlan plan,List<ChunkWritePlan> cache) {
        var publication=new StartupPlanPublication();
        publication.accept(plan,p->DurablePlanInstall.accept(p,cache::add),p->{});
        return publication;
    }
    private static ChunkWritePlan plan() {
        return new ChunkPlanCompiler().compile(new ChunkKey("minecraft:overworld",1,-2),"a".repeat(64),List.of(),Map.of());
    }
    @FunctionalInterface private interface Write {void write(ChunkWritePlan plan)throws IOException;}
    private static WallBodyBackfill.PlanStore store(Write write) {
        return new WallBodyBackfill.PlanStore() {
            public Optional<ChunkWritePlan> read(ChunkKey key){return Optional.empty();}
            public void write(ChunkWritePlan plan)throws IOException{write.write(plan);}
        };
    }
}
