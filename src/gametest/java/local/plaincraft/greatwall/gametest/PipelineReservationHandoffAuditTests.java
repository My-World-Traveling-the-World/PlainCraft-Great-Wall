package local.plaincraft.greatwall.gametest;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.ChunkWallWriter;
import local.plaincraft.greatwall.generation.ProductionWallPipeline;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import local.plaincraft.greatwall.terrain.TerrainSnapshotService;
import local.plaincraft.greatwall.terrain.TerrainTile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Exercises the public pipeline entry and the real cell-pin handoff, without writing any chunks.
 * Registration is left to the main agent. No native scheduling claims are made by this fixture. */
public final class PipelineReservationHandoffAuditTests implements FabricGameTest {
    private static final String H = "a".repeat(64);

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void closingPipelineRetainsBothArenasUntilEnteredCaptureDrains(GameTestHelper helper) {
        var worker = new HandoffExecutor();var recipe = recipe();var engine = new HoldingEngine();
        var chunks = new HashMap<ChunkPos, ChunkAccess>();
        java.util.function.Function<ChunkPos, ChunkAccess> newChunk = p -> {
            var c = new ProtoChunk(p, UpgradeData.EMPTY, helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            c.setPersistedStatus(ChunkStatus.FEATURES);
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=61;y<64;y++)
                c.setBlockState(new BlockPos(p.getMinBlockX()+x,y,p.getMinBlockZ()+z),Blocks.STONE.defaultBlockState(),false);
            return c;
        };
        var owner=chunks.computeIfAbsent(new ChunkPos(0,0),newChunk);
        var store=ChunkEvidenceStore.production(Path.of("build","s1b-allocation-audit",UUID.randomUUID().toString()));
        var snapshots=new TerrainSnapshotService(store,worker,new BlockCatalog(helper.getLevel().registryAccess()));
        var ports=new ProductionWallPipeline.Ports(
                (key,margin)->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans()),()->{}),
                key->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plans()),()->{}),
                positions->{
                    var ready=new HashMap<ChunkPos,ChunkAccess>();
                    positions.forEach(p->ready.put(p,chunks.computeIfAbsent(p,newChunk)));
                    return new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(Map.copyOf(ready)),()->{});
                },()->{},failure->{},worker);
        var pipeline=new ProductionWallPipeline(recipe,ports,snapshots,
                new RealizationPlanner(recipe,new SurfaceProbe(),Map.of()),new ChunkPlanCompiler(),new ChunkWallWriter());
        try {
            var result=pipeline.beforeLight(new ChunkKey(recipe.dimension(),0,0),owner,engine);
            worker.drain();
            helper.assertTrue(engine.held!=null,"pause with an actual T0 page retained by the entered engine");
            pipeline.close();
            helper.assertTrue(result.isCompletedExceptionally(),"consumer view fails promptly on close");
            helper.assertTrue(pipeline.diagnostics().get("terrainReserved")==64L<<20,"entered capture retains its full T0 pin");
            helper.assertTrue(pipeline.diagnostics().get("compiledReserved")==32L<<20,"parent compilation waits for child capture quiescence");
            engine.release();worker.drain();
            helper.assertTrue(pipeline.diagnostics().get("terrainReserved")==0,"drained terrain arena released");
            helper.assertTrue(pipeline.diagnostics().get("compiledReserved")==0,"drained compiled arena released");
            helper.assertTrue(pipeline.diagnostics().get("leases")==0,"no delivered pins remain after drain");
            helper.succeed();
        } finally {pipeline.close();engine.release();worker.drain();}
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void rejectedInspectionContinuationReturnsTheSuccessfullyLoadedCellPin(GameTestHelper helper) {
        var worker = new HandoffExecutor();
        var recipe = recipe();
        var owner = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        owner.setPersistedStatus(ChunkStatus.FEATURES);
        var store = ChunkEvidenceStore.production(Path.of("build", "s1b-allocation-audit", UUID.randomUUID().toString()));
        var snapshots = new TerrainSnapshotService(store, worker, new BlockCatalog(helper.getLevel().registryAccess()));
        var ports = new ProductionWallPipeline.Ports(
                (key, margin) -> new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(plans()), () -> {}),
                cell -> new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())),
                        () -> worker.rejectNext = true),
                positions -> { throw new AssertionError("this handoff fixture must not request live FEATURES"); },
                () -> {}, error -> {}, worker);
        var pipeline = new ProductionWallPipeline(recipe, ports, snapshots,
                new RealizationPlanner(recipe, new SurfaceProbe(), Map.of()), new ChunkPlanCompiler(), new ChunkWallWriter());
        try {
            // Empty arc data isolates the successful loadCell ownership transfer from geometry work.
            // The actual production budgets remain 64 MiB / 32 MiB. No mock geometry is written.
            var result = pipeline.beforeLight(new ChunkKey(recipe.dimension(), 0, 0), owner, new InlineEngine());
            worker.drain();
            helper.assertTrue(result.isCompletedExceptionally(), "the rejected continuation terminates the owner request");
            var reason = result.handle((value, error) -> root(error)).resultNow();
            helper.assertTrue(reason == worker.rejection, "failure must be the injected handoff rejection, not a fixture error");
            helper.assertTrue(worker.rejections == 1, "only the inspection handoff was rejected");
            var diagnostics = pipeline.diagnostics();
            helper.assertTrue(diagnostics.get("compiledReserved") == 0, "failed owner releases its compiled working set");
            helper.assertTrue(diagnostics.get("flights") == 0, "failed owner has no unfinished consumer view");
            helper.assertTrue(diagnostics.get("terrainReserved") == 0,
                    "thenApplyAsync rejection must close the completed CellTerrain pin even though its body never ran");
            helper.assertTrue(diagnostics.get("leases") == 0, "no resource cleanup may be deferred to server close");
            helper.succeed();
        } finally {
            pipeline.close();
            worker.drain();
        }
    }

    private static Throwable root(Throwable error) {
        if (error == null) return null;
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    private static WorldRecipe recipe() {
        return new WorldRecipe(new UUID(0, 1), "allocation-audit", 3, "minecraft:overworld", new Point3i(0, 64, 0),
                new Axis(new Point3i(0, 64, 0), 1 << 30, 0), new DimensionBounds(-64, 320, 29_999_000),
                new GenerationConfig(1, 1, 2048, 4, 150000, 256, 512, 8, 64L << 20, 64L << 20, 32L << 20), H, H, H, H);
    }

    private static PlanWindow plans() {
        var start = new Boundary(0, new Point2q(0, 0), 84, H);
        var end = new Boundary(1, new Point2q(64L * 256, 0), 84, H);
        var geometry = new SegmentGeometry(new SegmentKey(H, "minecraft:overworld", 0),
                List.of(start.point(), end.point()), List.of(0L, 64L * 256), List.of(),
                Optional.of(new Terminal(0, 64L * 256, TerminalKind.WORLD_LIMIT)), start, end, 0, 0, H);
        var stations = new StationTable(List.of(new Station(0, start.point(), 84, 0),
                new Station(64L * 256, end.point(), 84, 0)), List.of(), H);
        return new PlanWindow(List.of(new SegmentPlan(geometry, new PrefixSummary(0, 0, Optional.empty(), H),
                stations, List.of(), H)));
    }

    private static final class HandoffExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final RejectedExecutionException rejection = new RejectedExecutionException("audit cell-pin handoff");
        private boolean rejectNext;
        private int rejections;
        @Override public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                rejections++;
                throw rejection;
            }
            tasks.addLast(task);
        }
        void drain() {
            int steps = 0;
            while (!tasks.isEmpty()) {
                if (++steps > 10000) throw new AssertionError("audit executor did not quiesce");
                tasks.removeFirst().run();
            }
        }
    }

    private static final class InlineEngine implements TerrainEngine {
        @Override public <T> CompletableFuture<T> exclusive(ChunkPos owner, Supplier<T> action) {
            try { return CompletableFuture.completedFuture(action.get()); }
            catch (Throwable error) { return CompletableFuture.failedFuture(error); }
        }
        @Override public String name() { return "allocation-audit-inline"; }
    }
    private static final class HoldingEngine implements TerrainEngine {
        private Runnable completion;
        private Object held;
        private boolean paused;
        @Override public <T> CompletableFuture<T> exclusive(ChunkPos owner,Supplier<T> action) {
            var result=new CompletableFuture<T>();
            try {
                T value=action.get();
                if(!paused&&value instanceof Map<?,?> pages&&pages.values().stream().anyMatch(TerrainTile.class::isInstance)) {
                    paused=true;held=value;completion=()->result.complete(value);
                } else result.complete(value);
            } catch(Throwable failure){result.completeExceptionally(failure);}
            return result;
        }
        void release(){var next=completion;completion=null;held=null;if(next!=null)next.run();}
        @Override public String name(){return "allocation-audit-held-page";}
    }
}
