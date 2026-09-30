package local.plaincraft.greatwall.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.diagnostics.CodecMemory;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.EvidenceCodec;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.TerrainSnapshotService;
import local.plaincraft.greatwall.terrain.TerrainTile;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Real capture/store calls with detached native chunks and deterministic scheduling.
 * Fixture tokens are opaque T0 strings, not registered blocks. Logical allowance is not JVM peak.
 * Registration and execution belong to the main agent; this file does not change resources. */
public final class ColdCaptureAllocationTests implements FabricGameTest {
    private static final long MIB = 1L << 20;
    private static final long LIMIT = 64 * MIB;
    private static final long SCRATCH = 24 * MIB;
    private static final String RECIPE = "a".repeat(64);
    private static final ChunkKey OWNER = new ChunkKey("minecraft:overworld", -3, 5);

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 600)
    public void sixtyFourPalettePagesColdCaptureBorrowsOnlyAlreadyReservedPins(GameTestHelper helper) throws IOException {
        try (var fixture = fixture(helper, Payload.PALETTES, 0)) {
            helper.assertTrue(fixture.expected().tileRetained() > SCRATCH,
                    "64 returned pages alone exceed the old 24 MiB decode scope");
            assertOldScratchRefuses(helper, fixture);
            assertColdCapture(helper, fixture);
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 600)
    public void largeProtectionMetadataColdCaptureHasAConservativeForecast(GameTestHelper helper) throws IOException {
        try (var fixture = fixture(helper, Payload.PROTECTED, 0)) {
            var expected = fixture.expected();
            helper.assertTrue(expected.metadataRetained() > SCRATCH,
                    "140000 protected positions exceed 24 MiB retained allowance");
            helper.assertTrue(expected.legacyMetadataHint() < expected.metadataRetained(),
                    "16384 + 8 * raw is a real retained-size counterexample, not scratch shortage");
            helper.assertTrue(expected.metadataHint() >= expected.metadataRetained(),
                    "forecast must cover Optional<CaptureMetadata>, including every 192-byte point allowance");
            assertOldScratchRefuses(helper, fixture);
            assertColdCapture(helper, fixture);
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 600)
    public void oneByteShortForecastRefusesBothPayloadsBeforeAnyDataRead(GameTestHelper helper) throws IOException {
        for (var payload : List.of(Payload.PALETTES, Payload.PROTECTED)) {
            try (var fixture = fixture(helper, payload, 0)) {
                long occupied = LIMIT - SCRATCH - fixture.expected().forecast() + 1;
                helper.assertTrue(occupied > 0, "fixture must fit the unchanged 64 MiB parent when unoccupied");
                try (var resident = fixture.budget().reserve(occupied)) {
                    var capture = fixture.capture();
                    fixture.io().drain();
                    assertFailure(helper, capture, FailureCode.CAPACITY);
                    helper.assertTrue(fixture.service().dataProductions() == 0,
                            "forecast refusal precedes readSaved and payload allocation: " + payload);
                    helper.assertTrue(fixture.engine().calls() == 1, "only the counting forecast entered the engine");
                    helper.assertTrue(fixture.budget().stats().used() == resident.bytes(), "failed capture releases both owned pins");
                    helper.assertTrue(fixture.budget().stats().peak() == resident.bytes() + SCRATCH,
                            "rejected retained reservation must never contribute to peak");
                    assertQuiescent(helper, fixture);
                }
                helper.assertTrue(fixture.budget().stats().used() == 0, "unrelated resident pin remains independently releasable");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 300)
    public void closeBeforeColdIoRetainsReservationsUntilTheQueuedActionDrains(GameTestHelper helper) throws IOException {
        try (var fixture = fixture(helper, Payload.SMALL, 1)) {
            var capture = fixture.capture();
            fixture.io().drain();
            helper.assertTrue(fixture.engine().holding(), "forecast completion is held before retained admission");
            fixture.engine().release();
            fixture.io().runNext();
            assertBothPins(helper, fixture);
            helper.assertTrue(fixture.service().dataProductions() == 0, "cold IO remains queued");
            fixture.service().close();
            assertBothPins(helper, fixture);
            helper.assertTrue(!capture.isDone(), "close cannot publish quiescence while the cold IO action is queued");
            fixture.io().drain();
            assertFailure(helper, capture, FailureCode.CLOSED);
            helper.assertTrue(fixture.service().dataProductions() == 0, "closed queued action does not read evidence");
            helper.assertTrue(fixture.budget().stats().used() == 0, "failed terminal continuation releases both parent pins");
            assertQuiescent(helper, fixture);
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 600)
    public void closeAfterLargeColdReadDoesNotUnpinTheHeldDecodedResult(GameTestHelper helper) throws IOException {
        try (var fixture = fixture(helper, Payload.PROTECTED, 2)) {
            var capture = fixture.capture();
            fixture.io().drain();
            helper.assertTrue(fixture.engine().holding(), "marker lease holds the successfully decoded Saved result");
            helper.assertTrue(fixture.service().dataProductions() == 1, "the large cold payload has been read once");
            assertBothPins(helper, fixture);
            fixture.service().close();
            assertBothPins(helper, fixture);
            helper.assertTrue(!capture.isDone(), "held Saved remains owned until the engine continuation completes");
            fixture.engine().release();
            fixture.io().drain();
            assertFailure(helper, capture, FailureCode.CLOSED);
            helper.assertTrue(fixture.budget().stats().used() == 0, "cold read failure releases retained and scratch exactly once");
            assertQuiescent(helper, fixture);
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 300)
    public void rejectedColdIoSubmissionReleasesPinsWithoutProducingData(GameTestHelper helper) throws IOException {
        try (var fixture = fixture(helper, Payload.SMALL, 1)) {
            var capture = fixture.capture();
            fixture.io().drain();
            helper.assertTrue(fixture.engine().holding(), "forecast is ready but its future is held");
            fixture.engine().release();
            // The admitted continuation is queued; reject only its nested cold-IO submission.
            fixture.io().rejectNextSubmission();
            fixture.io().drain();
            helper.assertTrue(capture.isCompletedExceptionally(), "cold IO rejection must terminate the flight");
            helper.assertTrue(rootCause(capture.exceptionNow()) instanceof RejectedExecutionException,
                    "the actual cold submission rejection is propagated");
            helper.assertTrue(fixture.io().rejections() == 1, "exactly one nested IO submission was rejected");
            helper.assertTrue(fixture.budget().stats().peak() == SCRATCH + fixture.expected().forecast(),
                    "cold submission happens after both parent reservations");
            helper.assertTrue(fixture.budget().stats().used() == 0, "executor rejection returns both pins");
            helper.assertTrue(fixture.service().dataProductions() == 0, "rejected action never entered readSaved");
            assertQuiescent(helper, fixture);
            helper.succeed();
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "pgw-cold-allocation", timeoutTicks = 300)
    public void cancelledColdViewDoesNotCancelItsPeerOrReleaseTheForecastPin(GameTestHelper helper) throws IOException {
        try (var fixture = fixture(helper, Payload.SMALL, 0)) {
            var cancelled = fixture.capture();
            var peer = fixture.capture();
            helper.assertTrue(cancelled.cancel(false), "first view is cancelled before the shared cold producer runs");
            fixture.io().drain();
            helper.assertTrue(cancelled.isCancelled(), "cancelled view stays cancelled");
            assertCaptured(helper, fixture, peer);
            fixture.service().close();
            helper.assertTrue(fixture.budget().stats().used() == fixture.expected().forecast(),
                    "closing a quiescent service cannot release a result still owned by the caller");
            fixture.budget().close();
            helper.assertTrue(fixture.budget().stats().used() == 0, "the caller releases retained data with its owning arena");
            helper.succeed();
        }
    }

    private static void assertColdCapture(GameTestHelper helper, Fixture fixture) {
        helper.assertTrue(fixture.expected().forecast() + SCRATCH <= LIMIT, "fixture fits the production parent limit");
        var capture = fixture.capture();
        fixture.io().drain();
        assertCaptured(helper, fixture, capture);
    }

    private static void assertCaptured(GameTestHelper helper, Fixture fixture,
            CompletableFuture<TerrainSnapshotService.CapturedTerrain> capture) {
        helper.assertTrue(capture.isDone() && !capture.isCompletedExceptionally(), "actual cold capture must complete successfully");
        var actual = capture.resultNow();
        helper.assertTrue(actual.tiles().size() == fixture.payload().pages, "every saved page is delivered");
        for (var entry : fixture.expected().hashes().entrySet()) {
            var tile = actual.tiles().get(entry.getKey());
            helper.assertTrue(tile != null && tile.hash().equals(entry.getValue()), "saved page hash is unchanged: " + entry.getKey());
            helper.assertTrue(tile.palette().size() == fixture.payload().palette, "saved palette count is unchanged");
            helper.assertTrue(tile.palette().stream().allMatch(s -> s.length() == 80), "all fixture tokens retain their 80 characters");
            helper.assertTrue(tile.state(0, tile.key().minY(), 0).equals(token(0)), "first state preserves the saved evidence");
            helper.assertTrue(tile.state(15, tile.key().maxY(), 15).equals(token((tile.key().volume() - 1) % fixture.payload().palette)),
                    "last state preserves the saved evidence, not native air");
        }
        var metadata = actual.metadata().get(OWNER);
        helper.assertTrue(actual.metadata().size() == 1 && metadata != null, "one owner metadata is delivered");
        helper.assertTrue(metadata.hash().equals(fixture.expected().metadataHash()), "metadata hash is unchanged");
        helper.assertTrue(metadata.immutableBlocks().size() == fixture.payload().points, "all protected points survived decode");
        if (fixture.payload().points > 0) {
            helper.assertTrue(metadata.immutableBlocks().contains(point(0))
                    && metadata.immutableBlocks().contains(point(fixture.payload().points - 1)), "endpoint protected coordinates survive");
        }
        helper.assertTrue(fixture.engine().calls() == 2, "only forecast and marker checks ran; no native capturePage fallback");
        helper.assertTrue(fixture.service().dataProductions() == 1, "cold payload read exactly once");
        helper.assertTrue(fixture.budget().stats().limit() == LIMIT, "parent is still 64 MiB");
        helper.assertTrue(fixture.budget().stats().used() == fixture.expected().forecast(), "only the forecast pin survives publication");
        helper.assertTrue(fixture.budget().stats().used() >= fixture.expected().tileRetained() + fixture.expected().metadataRetained(),
                "delivered payload allowance fits the retained pin, without borrowing released scratch");
        helper.assertTrue(fixture.budget().stats().peak() == fixture.expected().forecast() + SCRATCH,
                "cold child only borrows the pre-reserved pair; it never reserves the parent again");
        assertQuiescent(helper, fixture);
    }

    private static void assertOldScratchRefuses(GameTestHelper helper, Fixture fixture) {
        try (var oldBudget = new AllocationBudget(SCRATCH)) {
            Throwable rejected = null;
            try {
                CompiledAllocation.run(oldBudget, () -> {
                    try {
                        var intent = fixture.store().readCoverageIntent(OWNER);
                        var metadata = fixture.store().readCoverage(OWNER);
                        var pages = new HashMap<TerrainTile.Key, TerrainTile>();
                        for (int base : fixture.coverage().tileBases()) {
                            var key = key(base);
                            pages.put(key, fixture.store().read(key, RECIPE).orElseThrow());
                        }
                        return List.of(intent, metadata, Map.copyOf(pages));
                    } catch (IOException error) { throw new UncheckedIOException(error); }
                }, ignored -> 0);
            } catch (RuntimeException error) { rejected = rootCause(error); }
            helper.assertTrue(rejected instanceof WallFailure failure && failure.code() == FailureCode.CAPACITY,
                    "old fixed scratch must fail specifically on capacity, not invalid fixture evidence");
            helper.assertTrue(oldBudget.stats().used() == 0, "failed control releases all temporary codec charges");
        }
    }

    private static void assertBothPins(GameTestHelper helper, Fixture fixture) {
        helper.assertTrue(fixture.budget().stats().used() == SCRATCH + fixture.expected().forecast(),
                "scratch and retained parent pins remain owned by the active capture");
    }

    private static void assertFailure(GameTestHelper helper, CompletableFuture<?> result, FailureCode code) {
        helper.assertTrue(result.isCompletedExceptionally(), "capture must fail with " + code);
        var error = rootCause(result.exceptionNow());
        helper.assertTrue(error instanceof WallFailure failure && failure.code() == code, "expected " + code + ", got " + error);
    }

    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    private static void assertQuiescent(GameTestHelper helper, Fixture fixture) {
        helper.assertTrue(fixture.service().diagnostics().values().stream().allMatch(n -> n == 0),
                "terminal capture releases flights, coverage claims and admission");
    }

    private static Fixture fixture(GameTestHelper helper, Payload payload, int holdAt) throws IOException {
        var bases = new ArrayList<Integer>();
        for (int page = 0; page < payload.pages; page++) bases.add(page * 128);
        var raw = new Coverage(OWNER, bases, new Bounds(OWNER.x() * 16, 0, OWNER.z() * 16,
                OWNER.x() * 16 + 16, payload.pages * 128, OWNER.z() * 16 + 16), RECIPE);
        var coverage = new Coverage(OWNER, bases, raw.readBounds(), EvidenceCodec.coverageHash(raw));
        var store = ChunkEvidenceStore.production(Path.of("build", "s1b-cold-allocation", UUID.randomUUID().toString()));
        // Only hashes and numeric expectations escape setup; no duplicate full T0/metadata remains retained.
        var expected = seedEvidence(store, coverage, payload);
        var height = new LevelHeightAccessor() {
            @Override public int getHeight() { return payload.pages * 128; }
            @Override public int getMinBuildHeight() { return 0; }
        };
        var chunks = new HashMap<ChunkKey, ChunkAccess>();
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            var chunkKey = new ChunkKey(OWNER.dimension(), OWNER.x() + dx, OWNER.z() + dz);
            var chunk = new ProtoChunk(new ChunkPos(chunkKey.x(), chunkKey.z()), UpgradeData.EMPTY, height,
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            chunk.setPersistedStatus(ChunkStatus.FEATURES);
            chunks.put(chunkKey, chunk);
        }
        var io = new ManualIo();
        var service = new TerrainSnapshotService(store, io, new BlockCatalog(helper.getLevel().registryAccess()));
        return new Fixture(payload, coverage, store, expected, Map.copyOf(chunks), io, new ControlledEngine(holdAt),
                service, new AllocationBudget(LIMIT));
    }

    private static Expected seedEvidence(ChunkEvidenceStore store, Coverage coverage, Payload payload) throws IOException {
        store.persistCoverageIntent(coverage);
        var palette = new ArrayList<String>();
        for (int i = 0; i < payload.palette; i++) palette.add(token(i));
        var hashes = new HashMap<TerrainTile.Key, String>();
        long forecast = 256L * 512 + 16384;
        long tileRetained = 0;
        for (int base : coverage.tileBases()) {
            var key = key(base);
            int[] states = new int[key.volume()];
            for (int i = 0; i < states.length; i++) states[i] = i % payload.palette;
            var tile = new TerrainTile(key, RECIPE, palette, states);
            store.persistBeforeApply(tile);
            hashes.put(key, tile.hash());
            forecast = Math.addExact(forecast, store.tileAllocationHint(key).orElseThrow());
            tileRetained = Math.addExact(tileRetained, CodecMemory.retainedBytes(Optional.of(tile)));
        }
        var columns = new ArrayList<ColumnEvidence>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            columns.add(new ColumnEvidence(OWNER.x() * 16 + x, OWNER.z() * 16 + z, 11, false));
        }
        var protectedPoints = new HashSet<Point3i>();
        for (int i = 0; i < payload.points; i++) protectedPoints.add(point(i));
        var metadata = EvidenceCodec.hashed(new CaptureMetadata(coverage, columns, List.of(), Set.of(), protectedPoints, RECIPE));
        store.persistCoverage(metadata);
        long metadataHint = store.metadataAllocationHint(OWNER).orElseThrow();
        long metadataRetained = CodecMemory.retainedBytes(Optional.of(metadata));
        long legacyHint = 16384L + 8L * EvidenceCodec.metadata(metadata).length;
        return new Expected(Map.copyOf(hashes), metadata.hash(), Math.addExact(forecast, metadataHint),
                tileRetained, metadataRetained, metadataHint, legacyHint);
    }

    private static TerrainTile.Key key(int base) {
        return new TerrainTile.Key(OWNER.dimension(), OWNER.x(), OWNER.z(), base, base + 127);
    }

    private static String token(int index) {
        String prefix = "fixture:state_" + index + "_";
        return prefix + "x".repeat(80 - prefix.length());
    }

    private static Point3i point(int index) {
        return new Point3i(OWNER.x() * 16 + index % 16, index / 256, OWNER.z() * 16 + index / 16 % 16);
    }

    private enum Payload {
        PALETTES(64, 1024, 0), PROTECTED(8, 1, 140000), SMALL(1, 1, 0);
        final int pages;
        final int palette;
        final int points;
        Payload(int pages, int palette, int points) { this.pages = pages; this.palette = palette; this.points = points; }
    }

    private record Expected(Map<TerrainTile.Key, String> hashes, String metadataHash, long forecast,
                            long tileRetained, long metadataRetained, long metadataHint, long legacyMetadataHint) {}

    private record Fixture(Payload payload, Coverage coverage, ChunkEvidenceStore store, Expected expected,
                           Map<ChunkKey, ChunkAccess> chunks, ManualIo io, ControlledEngine engine,
                           TerrainSnapshotService service, AllocationBudget budget) implements AutoCloseable {
        CompletableFuture<TerrainSnapshotService.CapturedTerrain> capture() {
            return service.capture(coverage, chunks, engine, RECIPE, budget);
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
        private boolean rejectNext;
        private int rejections;
        @Override public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                rejections++;
                throw new RejectedExecutionException("cold IO fixture rejection");
            }
            tasks.addLast(task);
        }
        void rejectNextSubmission() { rejectNext = true; }
        int rejections() { return rejections; }
        void runNext() {
            if (tasks.isEmpty()) throw new AssertionError("expected a queued cold continuation");
            tasks.removeFirst().run();
        }
        void drain() {
            int steps = 0;
            while (!tasks.isEmpty()) {
                if (++steps > 10000) throw new AssertionError("cold IO fixture failed to quiesce");
                runNext();
            }
        }
    }

    private static final class ControlledEngine implements TerrainEngine {
        private final int holdAt;
        private int calls;
        private Runnable completion;
        ControlledEngine(int holdAt) { this.holdAt = holdAt; }
        @Override public <T> CompletableFuture<T> exclusive(ChunkPos owner, Supplier<T> action) {
            var future = new CompletableFuture<T>();
            try {
                T value = action.get();
                if (++calls == holdAt) completion = () -> future.complete(value);
                else future.complete(value);
            } catch (Throwable error) { future.completeExceptionally(error); }
            return future;
        }
        int calls() { return calls; }
        boolean holding() { return completion != null; }
        void release() {
            var next = completion;
            completion = null;
            if (next != null) next.run();
        }
        @Override public String name() { return "cold-allocation-fixture-inline"; }
    }
}
