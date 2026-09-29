package local.plaincraft.greatwall.gametest;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.EvidenceCodec;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.TerrainSnapshotService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Detached native chunks plus a deterministic executor. Not a native-lock/real-world smoke test.
 * Registration is deliberately left to the main agent; this audit does not edit mod resources. */
public final class SnapshotAllocationBoundaryAuditTests implements FabricGameTest {
    private static final String RECIPE = "a".repeat(64);
    private static final long MIB = 1L << 20;

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void sameFlightCannotBorrowAnotherAllocationOwner(GameTestHelper helper) {
        try (var fixture = fixture(helper, new ControlledEngine(0));
             var foreign = new AllocationBudget(64 * MIB)) {
            var first = fixture.capture(0, RECIPE);
            var second = fixture.service().capture(coverage(0), fixture.chunks(), fixture.engine(), RECIPE, foreign);
            helper.assertTrue(second.isCompletedExceptionally(), "a different arena must not inherit an unowned pin");
            helper.assertTrue(foreign.stats().used() == 0, "foreign arena allocated no payload");
            fixture.io().drain();
            helper.assertTrue(first.isDone() && !first.isCompletedExceptionally(), "original capture still completes");
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void sameCoverageCannotJoinAFlightForAnotherRecipe(GameTestHelper helper) {
        try (var fixture = fixture(helper, new ControlledEngine(0))) {
            var first = fixture.capture(0, RECIPE);
            var foreign = fixture.capture(0, "b".repeat(64));
            fixture.io().drain();
            helper.assertTrue(first.isDone() && !first.isCompletedExceptionally(), "original recipe completes");
            helper.assertTrue(foreign.isCompletedExceptionally(),
                    "Coverage and AllocationBudget identity alone must not authorize foreign-recipe T0");
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void cancellingOneViewDoesNotCancelItsPeerOrUnpinDeliveredT0(GameTestHelper helper) {
        try (var fixture = fixture(helper, new ControlledEngine(0))) {
            var cancelled = fixture.capture(0, RECIPE);
            var peer = fixture.capture(0, RECIPE);
            cancelled.cancel(false);
            fixture.io().drain();
            helper.assertTrue(cancelled.isCancelled(), "only the requested view was cancelled");
            helper.assertTrue(peer.isDone() && !peer.isCompletedExceptionally(), "shared producer still completes");
            helper.assertTrue(peer.resultNow().tiles().size() == 1, "peer retains the page");
            helper.assertTrue(fixture.service().dataProductions() == 1, "single flight produced data once");
            helper.assertTrue(fixture.budget().stats().used() > 0, "successful T0 remains pinned to its working set");
            helper.assertTrue(fixture.budget().stats().used() < 24 * MIB, "scratch is released at quiescence");
            helper.assertTrue(fixture.service().diagnostics().values().stream().allMatch(n -> n == 0),
                    "completed flight no longer owns admission or a coverage claim");
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void completionReleasesScratchBeforeStartingTheNextSequentialCapture(GameTestHelper helper) {
        try (var fixture = fixture(helper, new ControlledEngine(0));
             var previousPages = fixture.budget().reserve(38 * MIB)) {
            // Keep the production 64 MiB limit. Prior retained pages leave room for ONE 24 MiB scratch arena.
            var sequence = fixture.capture(0, RECIPE).thenCompose(first -> fixture.capture(1, RECIPE));
            fixture.io().drain();
            helper.assertTrue(sequence.isDone(), "controlled capture sequence terminated");
            helper.assertTrue(!sequence.isCompletedExceptionally(),
                    "two sequential captures must reuse scratch, not temporarily require two scratch arenas");
            helper.assertTrue(fixture.service().dataProductions() == 2, "both distinct owners were captured");
            helper.assertTrue(fixture.budget().stats().used() > previousPages.bytes(), "both retained results remain accounted");
            helper.assertTrue(fixture.budget().stats().used() < 40 * MIB, "no scratch arena remains after completion");
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-allocation-audit", timeoutTicks = 300)
    public void closeDoesNotReturnPinsBeforeAnEnteredProducerDrains(GameTestHelper helper) {
        // Calls 1/2 are forecast and marker checks. Call 3 has produced a real TerrainTile page.
        var engine = new ControlledEngine(3);
        try (var fixture = fixture(helper, engine)) {
            var capture = fixture.capture(0, RECIPE);
            fixture.io().drain();
            helper.assertTrue(engine.holdingPage(), "fixture paused after producing, before publishing a T0 page");
            long heldBeforeClose = fixture.budget().stats().used();
            helper.assertTrue(heldBeforeClose > 24 * MIB, "scratch and retained payload were reserved");
            fixture.service().close();
            long heldWhileProducerOwnsPage = fixture.budget().stats().used();
            engine.release();
            fixture.io().drain();
            helper.assertTrue(capture.isCompletedExceptionally(), "close terminates the consumer view");
            helper.assertTrue(fixture.budget().stats().used() == 0, "failed producer returns pins after quiescence");
            helper.assertTrue(heldWhileProducerOwnsPage > 0,
                    "close must not advertise zero usage while the entered producer still retains its T0 page");
            helper.succeed();
        }
    }

    private static Coverage coverage(int x) {
        var raw = new Coverage(new ChunkKey("minecraft:overworld", x, 0), List.of(0),
                new Bounds(x * 16, 49, 0, x * 16 + 16, 92, 16), RECIPE);
        return new Coverage(raw.chunk(), raw.tileBases(), raw.readBounds(),
                DeterministicHash.sha256(EvidenceCodec.coverage(raw)));
    }

    private static Fixture fixture(GameTestHelper helper, ControlledEngine engine) {
        var chunks = new HashMap<ChunkKey, ChunkAccess>();
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
        for (int x = -radius; x <= 1 + radius; x++) for (int z = -radius; z <= radius; z++) {
            var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            chunk.setPersistedStatus(ChunkStatus.FEATURES);
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) for (int y = 61; y < 64; y++) {
                chunk.setBlockState(new BlockPos(x * 16 + dx, y, z * 16 + dz), Blocks.STONE.defaultBlockState(), false);
            }
            chunks.put(new ChunkKey("minecraft:overworld", x, z), chunk);
        }
        var io = new ManualIo();
        var store = ChunkEvidenceStore.production(Path.of("build", "s1b-allocation-audit", UUID.randomUUID().toString()));
        var service = new TerrainSnapshotService(store, io, new BlockCatalog(helper.getLevel().registryAccess()));
        return new Fixture(Map.copyOf(chunks), io, engine, service, new AllocationBudget(64 * MIB));
    }

    private record Fixture(Map<ChunkKey, ChunkAccess> chunks, ManualIo io, ControlledEngine engine,
                           TerrainSnapshotService service, AllocationBudget budget) implements AutoCloseable {
        CompletableFuture<TerrainSnapshotService.CapturedTerrain> capture(int x, String recipe) {
            return service.capture(coverage(x), chunks, engine, recipe, budget);
        }
        @Override public void close() {
            service.close();
            engine.release();
            io.drain();
            budget.close();
        }
    }

    private static final class ManualIo implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void drain() {
            int steps = 0;
            while (!tasks.isEmpty()) {
                if (++steps > 10000) throw new AssertionError("audit executor failed to quiesce");
                tasks.removeFirst().run();
            }
        }
    }

    private static final class ControlledEngine implements TerrainEngine {
        private final int holdAt;
        private int calls;
        private Object heldValue;
        private Runnable completion;
        ControlledEngine(int holdAt) { this.holdAt = holdAt; }
        @Override public <T> CompletableFuture<T> exclusive(ChunkPos owner, Supplier<T> action) {
            var future = new CompletableFuture<T>();
            int call = ++calls;
            try {
                T value = action.get();
                if (call == holdAt) {
                    heldValue = value;
                    completion = () -> future.complete(value);
                } else future.complete(value);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
            return future;
        }
        boolean holdingPage() { return heldValue instanceof Map<?, ?> pages && !pages.isEmpty(); }
        void release() {
            var next = completion;
            completion = null;
            if (next != null) next.run();
            heldValue = null;
        }
        @Override public String name() { return "allocation-audit-inline"; }
    }
}
