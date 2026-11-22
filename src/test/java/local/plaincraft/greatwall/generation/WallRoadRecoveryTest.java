package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 使用真实证明文件，区分只做过捕获的区块、合法空计划和旧墙损坏。 */
class WallRoadRecoveryTest {
    @TempDir Path root;
    private static final String OVERWORLD = "minecraft:overworld", HASH = "a".repeat(64);
    private static final ChunkKey SOLID = new ChunkKey(OVERWORLD, -3, 1);
    private static final ChunkKey EMPTY = new ChunkKey(OVERWORLD, -2, 1);

    @AfterEach void clear() { WallRoadGuard.clear(); }

    @Test void inventoryOnlyDiscoversProofsFromTheRequestedDimension() throws Exception {
        var store = ChunkEvidenceStore.production(root);
        store.persistPlan(plan(SOLID, true));
        store.persistPlan(plan(EMPTY, false));
        store.persistPlan(plan(new ChunkKey("minecraft:the_nether", 9, 2), false));
        Path directory = root.resolve(DeterministicHash.sha256(OVERWORLD.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.createDirectory(directory.resolve("-4_1"));
        Files.writeString(directory.resolve("-4_1").resolve("coverage.bin"), "地形文件不代表墙");
        assertEquals(Set.of(SOLID, EMPTY), Set.copyOf(store.planOwners(OVERWORLD)));
        assertTrue(store.planOwners("minecraft:the_end").isEmpty());
    }

    @Test void republishedEmptyAndNonEmptyOldPlansFillTheExactGuard() throws Exception {
        var store = ChunkEvidenceStore.production(root);
        store.persistPlan(plan(SOLID, true));
        store.persistPlan(plan(EMPTY, false));
        var cache = new HashMap<ChunkKey,ChunkWritePlan>();
        assertFalse(WallRoadGuard.isMarked(-48, 16));
        var report = restore(store, new LinkedHashSet<>(store.planOwners(OVERWORLD)), cache);
        assertEquals(new WallRoadRecovery.Report(2, 1, 2, 0, 0, 2, false), report);
        assertTrue(report.complete());
        assertTrue(WallRoadGuard.isMarked(-48, 16));
        assertFalse(WallRoadGuard.isMarked(-47, 16));
        assertEquals(Set.of(SOLID, EMPTY), cache.keySet());
    }

    @Test void missingKnownChunkAndUnreadableProofAreNotACleanEmptyWorld() throws Exception {
        var store = ChunkEvidenceStore.production(root);
        store.persistPlan(plan(SOLID, true));
        try (var paths = Files.walk(root)) {
            var proof = paths.filter(p -> p.getFileName().toString().equals("build-proof.bin")).findFirst().orElseThrow();
            Files.writeString(proof, "施工证明已损坏");
        }
        var keys = new LinkedHashSet<>(store.planOwners(OVERWORLD));
        keys.add(EMPTY); // 旧完工提示仍在，但证明文件已经丢失。
        var cache = new HashMap<ChunkKey,ChunkWritePlan>();
        var report = restore(store, keys, cache);
        assertEquals(new WallRoadRecovery.Report(2, 0, 0, 1, 1, 0, false), report);
        assertFalse(report.complete());
        assertTrue(cache.isEmpty());
    }

    private static WallRoadRecovery.Report restore(ChunkEvidenceStore store, Set<ChunkKey> keys,
                                                    Map<ChunkKey,ChunkWritePlan> cache) {
        return WallRoadRecovery.restore(keys, false, cache::get, key -> store.readPlan(key).orElse(null),
                plan -> cache.put(plan.owner(), plan));
    }

    private static ChunkWritePlan plan(ChunkKey owner, boolean voxels) {
        var compiler = new ChunkPlanCompiler();
        if (!voxels) return compiler.compile(owner, HASH, List.of(), Map.of());
        int x = owner.x() * 16, z = owner.z() * 16;
        var palette = new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:stone", Map.of());
        var site = new SiteId("wall", SiteKind.WALL, 0);
        var a = new Point3i(x, -60, z);
        var b = new Point3i(x, -59, z);
        var intents = List.of(new VoxelIntent(a, IntentKind.SOLID, Part.CORE, palette, site, 100, true, false),
                new VoxelIntent(b, IntentKind.SOLID, Part.CORE, palette, site, 100, true, false));
        var bounds = new Bounds(x, -60, z, x + 1, -58, z + 1);
        var raw = new ComponentVoxels(site, bounds, intents, List.of(), List.of(), List.of(), HASH);
        var body = new ComponentVoxels(site, bounds, intents, List.of(), List.of(), List.of(), CanonicalCodec.componentHash(raw));
        var cell = new RealizationCell(new CellKey(HASH, owner.dimension(), 0), List.of(), List.of(body), HASH, 0, HASH, HASH);
        return compiler.compile(owner, HASH, List.of(cell), Map.of(a, "minecraft:air{}", b, "minecraft:air{}"));
    }
}
