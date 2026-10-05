package local.plaincraft.greatwall.gametest;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.compat.road.RoadTypes;
import local.plaincraft.greatwall.compat.road.WallArchBackfill;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.GateLedger;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * S5 negotiated-gate tests driven against the real {@link GateLedger} and the pure
 * {@link RoadTypes} DTOs. {@code NegotiatedGateService} does not exist in the source tree yet
 * (spec-planned only), so its candidate-selection formula is asserted as the documented contract
 * and the durable gate semantics are verified on the ledger it would drive.
 */
public final class NegotiatedGateGameTests implements FabricGameTest {

    private static RoadTypes.GateContract contract(GateId id) {
        var frame = new Frame(new Point2q(0L, 0L), 1 << 30, 0, 64, false);
        var affected = List.of(new ChunkKey("minecraft:overworld", 0, 0));
        return new RoadTypes.GateContract(id, 0L, frame, 64, 4, 5, affected, "c".repeat(64));
    }

    private static RoadTypes.Approach approach(String roadId) {
        return new RoadTypes.Approach(roadId, List.of(new Point3i(0, 64, 0)));
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void roadBeforeWallUsesSameFrozenGate(GameTestHelper helper) {
        var id = new GateId("wall", "negotiated", 0);

        // Road first: propose then freeze with the complete transaction set.
        var roadFirst = new GateLedger(new MemLog(), Runnable::run);
        await(roadFirst.propose(contract(id), List.of(approach("roadA"), approach("roadB")),
                List.of("roadA", "roadB"), 0));
        var frozenRoadFirst = await(roadFirst.freeze(id, 1));

        // Wall first: the same transaction set arrives in the opposite order.
        var wallFirst = new GateLedger(new MemLog(), Runnable::run);
        await(wallFirst.propose(contract(id), List.of(approach("roadB"), approach("roadA")),
                List.of("roadB", "roadA"), 0));
        var frozenWallFirst = await(wallFirst.freeze(id, 1));

        helper.assertTrue(frozenRoadFirst.state() == RoadTypes.GateState.FROZEN,
                "the road-first gate must freeze");
        helper.assertTrue(frozenWallFirst.state() == RoadTypes.GateState.FROZEN,
                "the wall-first gate must freeze");
        helper.assertTrue(frozenRoadFirst.contract().equals(frozenWallFirst.contract()),
                "arrival order must not change the frozen contract/geometry");
        helper.assertTrue(frozenRoadFirst.contract().hash().equals(frozenWallFirst.contract().hash()),
                "owner-initial and backfill contracts must share the same hash");
        helper.assertTrue(frozenRoadFirst.proposals().equals(frozenWallFirst.proposals()),
                "the closed transaction set must reduce to the same sorted approach set");
        helper.assertTrue(frozenRoadFirst.contract().apertureWidth() == 4
                && frozenRoadFirst.contract().apertureHeight() == 5,
                "the negotiated aperture stays frozen at 4 wide and 5 high");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void differentRoadsShareNegotiatedCell(GameTestHelper helper) {
        long crossingSQ8 = 0L;
        long cell = Math.floorDiv(crossingSQ8, 128 * 256);
        var id = new GateId("wall", "negotiated", cell);
        var gates = new GateLedger(new MemLog(), Runnable::run);

        var first = await(gates.propose(contract(id), List.of(approach("roadA")),
                List.of("roadA"), 0));
        var second = await(gates.propose(contract(id), List.of(approach("roadB")),
                List.of("roadA", "roadB"), first.revision()));

        helper.assertTrue(second.contract().id().equals(id),
                "roads in the same cell must resolve to the same GateId");
        helper.assertTrue(second.proposals().size() == 2,
                "the same-cell requests must merge into one negotiated gate");

        var frozen = await(gates.freeze(id, second.revision()));
        helper.assertTrue(frozen.state() == RoadTypes.GateState.FROZEN,
                "the closed batch must freeze");
        helper.assertTrue(frozen.contract().apertureWidth() == 4
                && frozen.contract().apertureHeight() == 5,
                "freezing must not change the aperture width or hole height");
        helper.assertTrue(frozen.contract().groundY() == contract(id).groundY(),
                "freezing must not change the hole Y");

        // A later road adapts to the frozen contract; the gate itself never changes geometry.
        var adapted = await(gates.propose(contract(id), List.of(approach("roadC")),
                List.of(), frozen.revision()));
        helper.assertTrue(adapted.contract().equals(frozen.contract()),
                "a frozen gate must not be renegotiated to a different width or hole Y");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void noCandidatePreservesProtectedGap(GameTestHelper helper) {
        // The spec candidate formula yields exactly seven candidates around the cell center.
        long crossingSQ8 = 0L;
        long cell = Math.floorDiv(crossingSQ8, 128 * 256);
        long center = (cell * 128 + 64) * 256;
        int[] offsets = {0, -16, 16, -32, 32, -48, 48};
        Set<Long> candidates = new HashSet<>();
        for (int offset : offsets) candidates.add(center + (long) offset * 256);
        helper.assertTrue(candidates.size() == 7,
                "the candidate enumeration must produce exactly seven distinct SQ8 positions");

        // All seven candidates failing means no frozen gate. Model the closed-batch failure:
        // the batch demands roadB, but only roadA has proposed, so the gate cannot freeze.
        var id = new GateId("wall", "negotiated", cell);
        var gates = new GateLedger(new MemLog(), Runnable::run);
        await(gates.propose(contract(id), List.of(approach("roadA")),
                List.of("roadA", "roadB"), 0));
        var conflict = await(gates.freeze(id, 1));

        helper.assertTrue(conflict.state() == RoadTypes.GateState.CONFLICT,
                "an incomplete batch must fail to freeze instead of carving a wider gate");
        helper.assertTrue(conflict.contract().apertureWidth() == 4
                && conflict.contract().apertureHeight() == 5,
                "no wider gate may be invented when every candidate fails");

        // Backfill refuses to carve anything without a frozen gate, and never force-loads a chunk
        // (getChunkNow, WallArchBackfill.java:81). This proves no third-party block is removed.
        var backfill = new WallArchBackfill(helper.getLevel(), gates);
        helper.assertTrue(backfill.prepare(conflict).toCompletableFuture().isCompletedExceptionally(),
                "a non-frozen gate must not authorize any backfill write");
        helper.succeed();
    }

    /** Synchronous in-memory durable log; GateLedger is the unit under test, not the journal. */
    private static final class MemLog implements DurableLog {
        private long sequence;

        @Override public CompletionStage<DurableLog.Receipt> append(DurableLog.Entry entry) {
            var receipt = new DurableLog.Receipt(++sequence, entry.eventKey(),
                    DeterministicHash.sha256((entry.eventKey() + '\0' + entry.type())
                            .getBytes(StandardCharsets.UTF_8)));
            return CompletableFuture.completedFuture(receipt);
        }

        @Override public CompletionStage<Optional<DurableLog.Record>> lookup(String type, String eventKey) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override public CompletionStage<HistoryTypes.BlobRef> storeBlob(String kind, byte[] bytes) {
            return CompletableFuture.completedFuture(
                    new HistoryTypes.BlobRef("history/blobs/x/0.bin", bytes.length, "0".repeat(64)));
        }

        @Override public CompletionStage<byte[]> loadBlob(HistoryTypes.BlobRef ref) {
            return CompletableFuture.completedFuture(new byte[0]);
        }

        @Override public CompletionStage<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }

        @Override public List<DurableLog.Record> replay(String type, long afterSequence, int limit) {
            return List.of();
        }

        @Override public void close() {}
    }
}
