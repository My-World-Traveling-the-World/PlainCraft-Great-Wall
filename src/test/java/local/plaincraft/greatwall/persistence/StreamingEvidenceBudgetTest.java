package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.diagnostics.CodecMemory;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;
import local.plaincraft.greatwall.terrain.TerrainTile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent legacy protocol writers and bounded-scope regressions; no Minecraft runtime. */
class StreamingEvidenceBudgetTest {
    private static final String H = "a".repeat(64);
    private static final ChunkKey OWNER = new ChunkKey("minecraft:overworld", -1, -1);
    private static final SiteId SITE = new SiteId("codec_\u4e2d_\ud83d\ude00", SiteKind.WALL, 7);
    private static final Point3i POS = new Point3i(-1, 6000, -1);
    @TempDir Path directory;

    @Test void componentAndPlanHashesMatchIndependentLegacyBytes() throws Exception {
        var plan = plan(3);
        var component = component(plan);
        byte[] expectedPlan = referencePlan(plan);
        byte[] expectedComponent = referenceComponent(component);
        assertArrayEquals(expectedPlan, CanonicalCodec.writePlan(plan));
        assertArrayEquals(expectedComponent, CanonicalCodec.component(component));
        assertEquals(DeterministicHash.sha256(expectedPlan), CanonicalCodec.writePlanHash(plan));
        assertEquals(DeterministicHash.sha256(expectedComponent), CanonicalCodec.componentHash(component));
        var streamed = new ByteArrayOutputStream();
        CanonicalCodec.writePlanTo(streamed, plan);
        assertArrayEquals(expectedPlan, streamed.toByteArray());
    }

    @Test void largeComponentHashDoesNotRequirePayloadSizedCapacity() {
        var component = component(plan(16384));
        byte[] encoded = CanonicalCodec.component(component);
        assertTrue(encoded.length > 1024 * 1024);
        String expected = DeterministicHash.sha256(encoded);
        try (var budget = new AllocationBudget(16384)) {
            for (int iteration = 0; iteration < 16; iteration++) {
                String hash = CompiledAllocation.run(budget, () -> CanonicalCodec.componentHash(component), ignored -> 0);
                assertEquals(expected, hash);
                assertEquals(0, budget.stats().used());
            }
            assertTrue(budget.stats().peak() <= 16384);
        }
    }

    @Test void planDecodeRetainsExactlyTheReturnedObjectGraph() {
        var value = plan(12);
        byte[] bytes = CanonicalCodec.writePlan(value);
        try (var budget = new AllocationBudget(1024 * 1024)) {
            var decoded = CompiledAllocation.retainAll(budget, () -> io(() -> CanonicalCodec.readWritePlan(bytes)));
            assertEquals(value, decoded);
            assertEquals(CanonicalCodec.retainedBytes(decoded), budget.stats().used());
        }
    }

    @Test void dimensionValidationFailureDoesNotRetainDecodedResult() {
        byte[] bytes = CanonicalCodec.writePlan(plan(4));
        try (var budget = new AllocationBudget(1024 * 1024)) {
            CompiledAllocation.run(budget, () -> {
                assertThrows(IOException.class, () -> CanonicalCodec.readWritePlan(bytes, new DimensionBounds(-64, 320, 10000)));
                assertEquals(0, budget.stats().used(), "a rejected decoded result is scratch, not retained output");
                return 0L;
            }, Long::longValue);
        }
    }

    @Test void malformedDecodeCanRetryInsideTheSameArenaWithoutAccumulation() {
        var value = plan(4);
        byte[] bytes = CanonicalCodec.writePlan(value);
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        try (var budget = new AllocationBudget(1024 * 1024)) {
            CompiledAllocation.run(budget, () -> {
                for (int i = 0; i < 16; i++) {
                    assertThrows(IOException.class, () -> CanonicalCodec.readWritePlan(trailing));
                    assertEquals(0, budget.stats().used());
                }
                try (var scratch = CodecMemory.open()) {
                    assertEquals(value, io(() -> CanonicalCodec.readWritePlan(bytes)));
                }
                assertEquals(0, budget.stats().used());
                return 0L;
            }, Long::longValue);
        }
    }

    @Test void repeatedLargePlanPersistenceUsesBoundedScratchAndNeverOverwrites() throws Exception {
        var value = plan(16384);
        var store = ChunkEvidenceStore.production(directory);
        assertTrue(CanonicalCodec.writePlan(value).length > 1024 * 1024);
        try (var budget = new AllocationBudget(65536)) {
            CompiledAllocation.run(budget, () -> {
                for (int i = 0; i < 4; i++) {
                    io(() -> { store.persistPlan(value); return null; });
                    assertEquals(0, budget.stats().used(), "persist owns no returned buffer");
                }
                return 0L;
            }, Long::longValue);
            assertTrue(budget.stats().peak() <= 65536);
        }
        assertEquals(value, store.readPlan(OWNER).orElseThrow());
        assertThrows(IOException.class, () -> store.persistPlan(plan(1)));
        assertEquals(value, store.readPlan(OWNER).orElseThrow());
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void storeReadRetainsOnlyOptionalAndDecodedPlanNotPayloadBytes() throws Exception {
        var value = plan(16);
        var store = ChunkEvidenceStore.production(directory);
        store.persistPlan(value);
        try (var budget = new AllocationBudget(1024 * 1024)) {
            var result = CompiledAllocation.retainAll(budget, () -> io(() -> store.readPlan(OWNER)));
            assertEquals(value, result.orElseThrow());
            assertEquals(ChunkEvidenceStore.retainedBytes(result), budget.stats().used());
        }
    }

    @Test void absentReadsLeaveNoPathHashOrWrapperCharges() {
        var store = ChunkEvidenceStore.production(directory);
        try (var budget = new AllocationBudget(65536)) {
            CompiledAllocation.run(budget, () -> {
                for (int i = 0; i < 20; i++) {
                    assertTrue(io(() -> store.readPlan(OWNER)).isEmpty());
                    assertTrue(io(() -> store.readCoverage(OWNER)).isEmpty());
                    assertEquals(0, budget.stats().used());
                }
                return 0L;
            }, Long::longValue);
        }
    }

    @Test void metadataBytesAndStreamingHashMatchIndependentLegacyProtocol() throws Exception {
        var value = metadata();
        byte[] expected = referenceMetadata(value);
        assertArrayEquals(expected, EvidenceCodec.metadata(value));
        assertEquals(DeterministicHash.sha256(expected), EvidenceCodec.metadataHash(value));
        try (var budget = new AllocationBudget(1024 * 1024)) {
            var decoded = CompiledAllocation.retainAll(budget, () -> io(() -> EvidenceCodec.readMetadata(expected)));
            assertEquals(value, decoded);
            assertEquals(ChunkEvidenceStore.retainedBytes(decoded), budget.stats().used());
        }
    }

    @Test void exitCertificateBytesIncludeLegacyModifiedUtfAndCapFootprints() throws Exception {
        var value = decision();
        byte[] expected = referenceDecision(value);
        assertArrayEquals(expected, GroundExitCodec.encode(value));
        assertEquals(DeterministicHash.sha256(expected), GroundExitCodec.hash(value));
        try (var budget = new AllocationBudget(65536)) {
            var decoded = CompiledAllocation.retainAll(budget, () -> io(() -> GroundExitCodec.decode(expected)));
            assertEquals(value, decoded);
            assertEquals(ChunkEvidenceStore.retainedBytes(decoded), budget.stats().used());
        }
    }

    @Test void repeatedExitProofVerificationReleasesEachCertificateScratch() throws Exception {
        var value = plan(1);
        var decision = decision();
        var store = ChunkEvidenceStore.production(directory);
        store.persistExitDecision(decision);
        store.persistExitProof(value, List.of(decision));
        try (var budget = new AllocationBudget(131072)) {
            CompiledAllocation.run(budget, () -> {
                for (int i = 0; i < 32; i++) {
                    io(() -> { store.verifyExitProof(value); return null; });
                    assertEquals(0, budget.stats().used());
                }
                return 0L;
            }, Long::longValue);
        }
    }

    @Test void t0StreamingPersistenceAndReadKeepStableHashAndNoScratch() throws Exception {
        var key = new TerrainTile.Key(OWNER.dimension(), OWNER.x(), OWNER.z(), 5888, 6015);
        var palette = List.of("minecraft:stone", "fixture:\u4e2d_\u0000_" + "x".repeat(4096));
        int[] states = new int[key.volume()];
        for (int i = 0; i < states.length; i++) states[i] = i & 1;
        var tile = new TerrainTile(key, H, palette, states);
        var store = ChunkEvidenceStore.production(directory);
        try (var budget = new AllocationBudget(1024 * 1024)) {
            CompiledAllocation.run(budget, () -> {
                for (int n = 0; n < 3; n++) {
                    assertSame(tile, io(() -> store.persistBeforeApply(tile)));
                    assertEquals(0, budget.stats().used());
                }
                return 0L;
            }, Long::longValue);
            var restored = CompiledAllocation.retainAll(budget, () -> io(() -> store.read(key, H)));
            assertEquals(tile.hash(), restored.orElseThrow().hash());
            assertEquals(palette, restored.orElseThrow().palette());
            assertArrayEquals(states, restored.orElseThrow().copyStates());
            assertEquals(ChunkEvidenceStore.retainedBytes(restored), budget.stats().used());
        }
    }

    @Test void tileForecastCoversDecodedRetainedAllowanceEvenForTinyPalettes() throws Exception {
        var store = ChunkEvidenceStore.production(directory);
        var key = new TerrainTile.Key(OWNER.dimension(), OWNER.x(), OWNER.z(), 0, 127);
        var tile = new TerrainTile(key, H, List.of("minecraft:stone"), new int[key.volume()]);
        store.persistBeforeApply(tile);
        long hint = store.tileAllocationHint(key).orElseThrow();
        assertTrue(hint >= ChunkEvidenceStore.retainedBytes(Optional.of(tile)));
    }

    @Test void metadataForecastCoversProtectedPointRetainedAllowance() throws Exception {
        var base = metadata();
        var points = new java.util.HashSet<Point3i>();
        for (int y = 5900; y < 5964; y++) for (int x = -16; x < 0; x++) points.add(new Point3i(x, y, -1));
        var value = EvidenceCodec.hashed(new CaptureMetadata(base.coverage(), base.columns(),
                base.protectedStructures(), points, points, H));
        var store = ChunkEvidenceStore.production(directory);
        store.persistCoverage(value);
        assertTrue(store.metadataAllocationHint(OWNER).orElseThrow() >= ChunkEvidenceStore.retainedBytes(Optional.of(value)));
    }

    @Test void persistenceStillRejectsMapKeysThatCollideAfterLegacyUtf8Replacement() throws Exception {
        var base = plan(1);
        var entity = new BlockEntityIntent(POS, "minecraft:chest", SITE, 4, Map.of("?", "first", "\ud800", "second"));
        var raw = new ChunkWritePlan(OWNER, H, H, base.voxels(), base.proofs(), List.of(entity),
                base.postprocess(), base.fluidTicks(), base.walkwayFeet(), base.metadata(), H);
        var value = new ChunkWritePlan(OWNER, H, H, raw.voxels(), raw.proofs(), raw.blockEntities(),
                raw.postprocess(), raw.fluidTicks(), raw.walkwayFeet(), raw.metadata(), CanonicalCodec.writePlanHash(raw));
        assertThrows(IOException.class, () -> CanonicalCodec.readWritePlan(CanonicalCodec.writePlan(value)),
                "legacy encoded payload is ambiguous and cannot be read back");
        var store = ChunkEvidenceStore.production(directory);
        assertThrows(IOException.class, () -> store.persistPlan(value));
        assertTrue(store.readPlan(OWNER).isEmpty());
    }

    private static ChunkWritePlan plan(int count) {
        var voxel = new VoxelIntent(POS, IntentKind.SOLID, Part.SKIN,
                new BlockSpec("minecraft:stone", Map.of("z", "last", "a", "first")), SITE, 400, true, false);
        var entity = new BlockEntityIntent(POS, "minecraft:chest", SITE, 4,
                Map.of("\ue000", "bmp", "\ud800\udc00", "supplementary", "text", "\u0000\u4e2d"));
        var slots = List.of(new SpawnSlot(SITE, 0, POS, "minecraft:pillager"));
        var metadata = new WriteMetadata(H, 3, slots, Map.of(SITE, DecayState.PARTIAL));
        var raw = new ChunkWritePlan(OWNER, H, H, Collections.nCopies(count, voxel),
                List.of(new BlockProof(POS, "minecraft:air", "minecraft:stone", false, SITE)),
                List.of(entity), List.of(POS), List.of(POS), List.of(POS), metadata, H);
        return new ChunkWritePlan(raw.owner(), raw.recipeHash(), raw.inputHash(), raw.voxels(), raw.proofs(),
                raw.blockEntities(), raw.postprocess(), raw.fluidTicks(), raw.walkwayFeet(), metadata, CanonicalCodec.writePlanHash(raw));
    }

    private static ComponentVoxels component(ChunkWritePlan value) {
        return new ComponentVoxels(SITE, new Bounds(-16, 5900, -16, 0, 6100, 0), value.voxels(), value.blockEntities(),
                value.metadata().spawnSlots(), List.of(List.of(POS)), DecayState.PARTIAL, H);
    }

    private static CaptureMetadata metadata() {
        var rawCoverage = new Coverage(OWNER, List.of(5888, 6016), new Bounds(-16, 5900, -16, 0, 6100, 0), H);
        var coverage = new Coverage(OWNER, rawCoverage.tileBases(), rawCoverage.readBounds(), EvidenceCodec.coverageHash(rawCoverage));
        var columns = new ArrayList<ColumnEvidence>();
        for (int z = -16; z < 0; z++) for (int x = -16; x < 0; x++) columns.add(new ColumnEvidence(x, z, 5990, (x & 1) == 0));
        return EvidenceCodec.hashed(new CaptureMetadata(coverage, columns, List.of(coverage.readBounds()), Set.of(POS), Set.of(POS), H));
    }

    private static GroundExitDecision decision() {
        var key = new Key(H, OWNER.dimension(), new ArcInterval(0, 100L * 256));
        var left = new Exit(new SiteId("exit_\u0000_\ud800", SiteKind.GATE, 0), 0, new ArcInterval(0, 256), H, H);
        var right = new Exit(new SiteId("exit_\u4e2d", SiteKind.GATE, 1), key.range().toQ8(), new ArcInterval(99L * 256, 101L * 256), H, H);
        var edge = new Edge(80L * 256, false, new ArcInterval(44L * 256, 80L * 256));
        var cap = new Cap(new SiteId("cap", SiteKind.END_CAP, 0), edge, edge.sQ8(), H);
        return GroundExitCodec.hashed(new GroundExitDecision(key, left, Optional.of(right), Long.MAX_VALUE,
                List.of(new BreakInterval(new ArcInterval(40L * 256, 44L * 256), BreakReason.NO_SUPPORT, BreakScope.REALIZED)),
                List.of(new ArcInterval(10L * 256, 12L * 256)), List.of(cap), H, H));
    }

    private static byte[] referenceComponent(ComponentVoxels value) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeInt(1); site(out, value.id()); bounds(out, value.bounds());
        list(out, value.voxels(), StreamingEvidenceBudgetTest::voxel);
        list(out, value.blockEntities(), StreamingEvidenceBudgetTest::entity);
        list(out, value.spawnSlots(), StreamingEvidenceBudgetTest::slot);
        list(out, value.requiredRoutes(), (o, route) -> list(o, route, StreamingEvidenceBudgetTest::point));
        string(out, value.realizedState().name()); return bytes.toByteArray();
    }

    private static byte[] referencePlan(ChunkWritePlan value) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeInt(1); string(out, value.owner().dimension()); out.writeInt(value.owner().x()); out.writeInt(value.owner().z());
        string(out, value.recipeHash()); string(out, value.inputHash());
        list(out, value.voxels(), StreamingEvidenceBudgetTest::voxel);
        list(out, value.proofs(), (o, p) -> {
            point(o, p.pos()); string(o, p.beforeState()); string(o, p.afterState()); o.writeBoolean(p.beforeHasBlockEntity()); site(o, p.owner());
        });
        list(out, value.blockEntities(), StreamingEvidenceBudgetTest::entity);
        list(out, value.postprocess(), StreamingEvidenceBudgetTest::point);
        list(out, value.fluidTicks(), StreamingEvidenceBudgetTest::point);
        list(out, value.walkwayFeet(), StreamingEvidenceBudgetTest::point);
        if (!value.metadata().equals(WriteMetadata.EMPTY)) {
            out.writeInt(0x5047574d); string(out, value.metadata().coverageHash()); out.writeLong(value.metadata().gateRevision());
            list(out, value.metadata().spawnSlots(), StreamingEvidenceBudgetTest::slot);
            var entries = new ArrayList<>(value.metadata().realizedStates().entrySet());
            entries.sort(Comparator.comparing((Map.Entry<SiteId, DecayState> e) -> e.getKey().wallId(), StreamingEvidenceBudgetTest::utf8Compare)
                    .thenComparing(e -> e.getKey().kind().name()).thenComparingLong(e -> e.getKey().ordinal()));
            list(out, entries, (o, e) -> { site(o, e.getKey()); string(o, e.getValue().name()); });
        }
        return bytes.toByteArray();
    }

    private static byte[] referenceMetadata(CaptureMetadata value) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); var coverage = value.coverage();
        string(out, coverage.chunk().dimension()); out.writeInt(coverage.chunk().x()); out.writeInt(coverage.chunk().z());
        list(out, coverage.tileBases(), DataOutputStream::writeInt); bounds(out, coverage.readBounds()); string(out, coverage.hash());
        list(out, value.columns().stream().sorted(Comparator.comparingInt(ColumnEvidence::z).thenComparingInt(ColumnEvidence::x)).toList(),
                (o, c) -> { o.writeInt(c.x()); o.writeInt(c.z()); o.writeInt(c.highestNonPlantFeet()); o.writeBoolean(c.upperCensored()); });
        list(out, value.protectedStructures().stream().sorted(Comparator.comparingInt(Bounds::minY).thenComparingInt(Bounds::minZ)
                .thenComparingInt(Bounds::minX).thenComparingInt(Bounds::maxYExclusive).thenComparingInt(Bounds::maxZExclusive).thenComparingInt(Bounds::maxXExclusive)).toList(),
                StreamingEvidenceBudgetTest::bounds);
        var order = Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
        list(out, value.blockEntities().stream().sorted(order).toList(), StreamingEvidenceBudgetTest::point);
        list(out, value.immutableBlocks().stream().sorted(order).toList(), StreamingEvidenceBudgetTest::point);
        return bytes.toByteArray();
    }

    private static byte[] referenceDecision(GroundExitDecision value) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeInt(0x50474531); out.writeInt(2); out.writeUTF(value.key().recipeHash()); out.writeUTF(value.key().dimension()); range(out, value.key().range());
        exit(out, value.left()); out.writeBoolean(value.right().isPresent()); if (value.right().isPresent()) exit(out, value.right().get());
        out.writeLong(value.terminalSQ8()); out.writeUTF(value.t0Hash());
        list(out, value.breaks(), (o, b) -> { range(o, b.range()); o.writeUTF(b.reason().name()); o.writeUTF(b.scope().name()); });
        list(out, value.exclusions(), StreamingEvidenceBudgetTest::range);
        list(out, value.caps(), (o, c) -> {
            siteUtf(o, c.site()); o.writeLong(c.edge().sQ8()); o.writeBoolean(c.edge().reverse()); range(o, c.edge().allowed());
            o.writeLong(c.actualSQ8()); o.writeUTF(c.componentHash()); range(o, c.footprint());
        });
        return bytes.toByteArray();
    }

    private static void string(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
    private static void site(DataOutputStream out, SiteId value) throws IOException { string(out, value.wallId()); string(out, value.kind().name()); out.writeLong(value.ordinal()); }
    private static void siteUtf(DataOutputStream out, SiteId value) throws IOException { out.writeUTF(value.wallId()); out.writeUTF(value.kind().name()); out.writeLong(value.ordinal()); }
    private static void point(DataOutputStream out, Point3i value) throws IOException { out.writeInt(value.x()); out.writeInt(value.y()); out.writeInt(value.z()); }
    private static void bounds(DataOutputStream out, Bounds value) throws IOException {
        out.writeInt(value.minX()); out.writeInt(value.minY()); out.writeInt(value.minZ()); out.writeInt(value.maxXExclusive()); out.writeInt(value.maxYExclusive()); out.writeInt(value.maxZExclusive());
    }
    private static void range(DataOutputStream out, ArcInterval value) throws IOException { out.writeLong(value.fromQ8()); out.writeLong(value.toQ8()); }
    private static void exit(DataOutputStream out, Exit value) throws IOException { siteUtf(out, value.site()); out.writeLong(value.sQ8()); range(out, value.footprint()); out.writeUTF(value.componentHash()); out.writeUTF(value.t0Hash()); }
    private static void slot(DataOutputStream out, SpawnSlot value) throws IOException { site(out, value.site()); out.writeInt(value.slot()); point(out, value.feet()); string(out, value.entityType()); }
    private static void voxel(DataOutputStream out, VoxelIntent value) throws IOException {
        point(out, value.pos()); string(out, value.kind().name()); string(out, value.part().name()); string(out, value.state().id()); map(out, value.state().properties());
        site(out, value.component()); out.writeInt(value.priority()); out.writeBoolean(value.required()); out.writeBoolean(value.walkable());
    }
    private static void entity(DataOutputStream out, BlockEntityIntent value) throws IOException { point(out, value.pos()); string(out, value.type()); site(out, value.site()); out.writeInt(value.localId()); map(out, value.fields()); }
    private static void map(DataOutputStream out, Map<String, String> value) throws IOException {
        var keys = new ArrayList<>(value.keySet()); keys.sort(StreamingEvidenceBudgetTest::utf8Compare);
        out.writeInt(keys.size()); for (String key : keys) { string(out, key); string(out, value.get(key)); }
    }
    private static int utf8Compare(String left, String right) { return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)); }
    private static <T> void list(DataOutputStream out, List<T> values, Writer<T> writer) throws IOException { out.writeInt(values.size()); for (T value : values) writer.write(out, value); }
    private static <T> T io(IoSupplier<T> action) { try { return action.get(); } catch (IOException error) { throw new UncheckedIOException(error); } }
    @FunctionalInterface private interface Writer<T> { void write(DataOutputStream out, T value) throws IOException; }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
}
