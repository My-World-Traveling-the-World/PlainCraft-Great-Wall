package local.plaincraft.greatwall.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.compat.road.RoadTypes;
import local.plaincraft.greatwall.compat.road.WallArchBackfill;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.GateLedger;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Negative owner-handoff tests only. Requests are not durable gate/T0 proof;
 * the stub's empty recipe hash rejects plan schema construction before
 * ownership preflight, ACK or mutation. This is not GW-011 certification.
 */
public final class WallArchPreparationGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void ownerPlanSchemaRejectionCompletesWithoutACommonPoolHop(GameTestHelper helper) {
        Fixture fixture = fixture(helper, RoadTypes.GateState.FROZEN);
        var failures = new ConcurrentHashMap<String, Throwable>();
        var ownerCallbacks = new ConcurrentHashMap<String, Boolean>();
        var result = fixture.backfill().prepare(fixture.request()).toCompletableFuture();
        result.whenComplete((ignored, failure) -> {
            if (failure != null) failures.put("failure", cause(failure));
            ownerCallbacks.put("owner", helper.getLevel().getServer().isSameThread());
        });
        helper.assertTrue(result.isDone() && result.isCompletedExceptionally(),
                "owner preparation must finish its rejected turn without a background wait");
        helper.assertTrue(Boolean.TRUE.equals(ownerCallbacks.get("owner")),
                "an inline owner turn publishes its failure on the owner");
        verifyPlanSchemaRejection(helper, fixture, failures.get("failure"));
        fixture.backfill().close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void workerReceivesOwnerPlanSchemaRejectionWithoutWritingOrAcknowledging(GameTestHelper helper) {
        Fixture fixture = fixture(helper, RoadTypes.GateState.PENDING_CHUNKS);
        var returned = new CompletableFuture<CompletableFuture<Void>>();
        var failures = new ConcurrentHashMap<String, Throwable>();
        var completion = returned.thenCompose(stage -> stage);
        completion.whenComplete((ignored, failure) -> {
            if (failure != null) failures.put("failure", cause(failure));
        });
        var worker = new Thread(() -> {
            try {
                returned.complete(fixture.backfill().prepare(fixture.request()).toCompletableFuture());
            } catch (Throwable failure) {
                returned.completeExceptionally(failure);
            }
        }, "pgw-arch-prepare-test");
        worker.setDaemon(true);
        worker.start();
        checkWorker(helper, fixture, returned, completion, failures, 0);
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void proposedGateIsRejectedBeforeLoadedOwnerPreparation(GameTestHelper helper) {
        Fixture fixture = fixture(helper, RoadTypes.GateState.PROPOSED);
        var failures = new ConcurrentHashMap<String, Throwable>();
        var result = fixture.backfill().prepare(fixture.request()).toCompletableFuture();
        result.whenComplete((ignored, failure) -> {
            if (failure != null) failures.put("failure", cause(failure));
        });
        helper.assertTrue(result.isDone() && result.isCompletedExceptionally(),
                "an unfrozen request must be rejected without dispatch");
        Throwable failure = failures.get("failure");
        helper.assertTrue(failure instanceof IllegalStateException
                        && failure.getMessage().contains("only a frozen gate can be prepared"),
                "state validation remains distinct from plan schema construction");
        verifyUnchanged(helper, fixture);
        fixture.backfill().close();
        helper.succeed();
    }

    private static void checkWorker(GameTestHelper helper, Fixture fixture,
            CompletableFuture<CompletableFuture<Void>> returned, CompletableFuture<Void> completion,
            ConcurrentHashMap<String, Throwable> failures, int ticks) {
        if (!completion.isDone() || !failures.containsKey("failure")) {
            if (ticks >= 180) {
                fixture.backfill().close();
                helper.fail("worker preparation did not deliver the owner failure");
                return;
            }
            helper.runAfterDelay(1, () ->
                    checkWorker(helper, fixture, returned, completion, failures, ticks + 1));
            return;
        }
        helper.assertTrue(returned.isDone() && !returned.isCompletedExceptionally(),
                "the worker returns a stage rather than throwing the owner failure");
        helper.assertTrue(completion.isCompletedExceptionally(),
                "the returned completion carries the rejected owner turn");
        verifyPlanSchemaRejection(helper, fixture, failures.get("failure"));
        fixture.backfill().close();
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, RoadTypes.GateState state) {
        var level = helper.getLevel();
        helper.assertTrue(level.getServer().isSameThread(), "fixture setup requires the real server owner");
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        ChunkKey owner = new ChunkKey(level.dimension().location().toString(),
                Math.floorDiv(position.getX(), 16), Math.floorDiv(position.getZ(), 16));
        var chunk = level.getChunkSource().getChunkNow(owner.x(), owner.z());
        helper.assertTrue(chunk != null, "the fixture must use an already loaded native chunk");
        var ledger = new GateLedger(new NoJournalAccess(), level.getServer()::execute);
        var gate = new RoadTypes.GateContract(new GateId("arch-preparation-request", "planned", 0), 0,
                new Frame(new Point2q((long) position.getX() * 256, (long) position.getZ() * 256),
                        1 << 30, 0, position.getY(), false),
                position.getY() - 10, 4, 5, List.of(owner), "a".repeat(64));
        var request = new RoadTypes.GateRecord(gate, 1, state, List.of(), List.of(), Map.of(), 0);
        return new Fixture(new WallArchBackfill(level, ledger), ledger, request,
                position, chunk.getBlockState(position));
    }

    private static void verifyPlanSchemaRejection(GameTestHelper helper, Fixture fixture, Throwable failure) {
        helper.assertTrue(failure instanceof IllegalArgumentException
                        && "64-char lowercase hex hash required: ".equals(failure.getMessage()),
                "the stub's empty recipe hash must reject plan construction");
        boolean fromPlanSchema = false;
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().equals("local.plaincraft.greatwall.model.WallTypes$ChunkWritePlan")
                    && frame.getMethodName().equals("<init>")) {
                fromPlanSchema = true;
            }
        }
        helper.assertTrue(fromPlanSchema,
                "the valid contract hash passes metadata validation; missing recipe hash fails in plan schema");
        verifyUnchanged(helper, fixture);
    }

    private static void verifyUnchanged(GameTestHelper helper, Fixture fixture) {
        helper.assertTrue(helper.getLevel().getBlockState(fixture.position()).equals(fixture.before()),
                "preparation never mutates the sampled native block");
        var result = fixture.backfill().applyLoaded(fixture.request().contract().id(), 1);
        helper.assertTrue(result.written() == 0 && !result.complete(),
                "a rejected preparation must not install a prepared plan");
        helper.assertTrue(fixture.ledger().activeSnapshot().isEmpty(),
                "the negative request never becomes ledger proof");
    }

    private static Throwable cause(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private record Fixture(WallArchBackfill backfill, GateLedger ledger, RoadTypes.GateRecord request,
            BlockPos position, BlockState before) {}

    /** Any journal access is a test failure; no fake BEGIN, ACK or receipt is supplied. */
    private static final class NoJournalAccess implements DurableLog {
        @Override public CompletionStage<Receipt> append(Entry entry) { throw unexpected(); }
        @Override public CompletionStage<Optional<Record>> lookup(String type, String eventKey) { throw unexpected(); }
        @Override public CompletionStage<HistoryTypes.BlobRef> storeBlob(String kind, byte[] bytes) { throw unexpected(); }
        @Override public CompletionStage<byte[]> loadBlob(HistoryTypes.BlobRef ref) { throw unexpected(); }
        @Override public CompletionStage<Void> flush() { throw unexpected(); }
        @Override public List<Record> replay(String type, long afterSequence, int limit) { throw unexpected(); }
        @Override public void close() { throw unexpected(); }
        private static AssertionError unexpected() { return new AssertionError("unexpected journal access"); }
    }
}
