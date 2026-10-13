package local.plaincraft.greatwall.smoke;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;

/** Actual CPU input + actual scheduler, but no worldgen/owner writes and no production-world authorization. */
final class NativeSchedulerFixture {
    private NativeSchedulerFixture() {}

    static Map<String, Object> verify(CpuTerrainOracle.FrozenInput input, Path directory) throws Exception {
        if (!Thread.currentThread().getName().equals("pgw-test-native-oracle-profile")) {
            throw new IllegalStateException("blocking test orchestration is confined to the diagnostic worker");
        }
        UUID session = UUID.randomUUID();
        var config = new GenerationConfig(1, 1, 2048, 4, 150000, 384, 512, 4, 67108864, 67108864, 33554432,
                "0".repeat(64));
        String dummy = "0".repeat(64);
        var raw = new WorldRecipe(session, dummy, input.seed(), "minecraft:overworld", new Point3i(0, 64, 0),
                new Axis(new Point3i(1000, 64, 0), 1 << 30, 0), input.bounds(), config,
                input.fingerprint(), dummy, dummy, dummy);
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(raw));
        var recipe = new WorldRecipe(session, hash, raw.seed(), raw.dimension(), raw.frozenSpawn(), raw.axis(),
                raw.bounds(), config, raw.terrainHash(), dummy, dummy, hash);
        try (var io = Executors.newSingleThreadExecutor()) {
            var scheduler = new PlannerScheduler(recipe, input::createOracle,
                    new SegmentCacheStore(directory, recipe), io);
            RouteShape first, second;
            long start = System.nanoTime();
            try {
                // Actual pre-tick pump. This call cannot wait for native compilation.
                scheduler.startupPump();
                long pumpCallNanos = System.nanoTime() - start;
                try (var a = scheduler.requireRoute(0, Priority.PLAYER);
                     var b = scheduler.requireRoute(1, Priority.PLAYER)) {
                    first = a.future().get(180, TimeUnit.SECONDS);
                    second = b.future().get(180, TimeUnit.SECONDS);
                }
                if (!first.end().equals(second.start())) throw new IllegalStateException("native scheduler boundary seam mismatch");
                scheduler.close();
                scheduler.drain().get(60, TimeUnit.SECONDS);
                var d = scheduler.diagnostics();
                for (int lane = 0; lane < 2; lane++) {
                    String prefix = "lane-" + lane + "-";
                    if (d.getOrDefault(prefix + "factory-calls", 0L) != 1
                            || d.getOrDefault(prefix + "native-constructions", 0L) != 1
                            || d.getOrDefault(prefix + "oracle-closes", 0L) != 1
                            || d.getOrDefault(prefix + "drained", 0L) != 1
                            || d.getOrDefault(prefix + "sentinel-WAIT_WHILE_LOCKED", 0L) != 0
                            || d.getOrDefault(prefix + "forbidden-init-thread", 0L) != 0) {
                        throw new IllegalStateException("actual scheduler producer protocol failed: " + d);
                    }
                }
                // 2026-10-05 用户授权：40ms 稳态批次不再是失败条件，仅作为观测值保留。
                // 2026-10-06 修正：原判断 `getOrDefault("max-batch-nanos", 0L) >= 0L` 恒为 true，
                // 使 BLOCKED_PERFORMANCE 成为死代码，等于连"计时是否真的被记录"都没验证。
                // 现在要求该字段确实存在且非负：缺失即视为观测链路断了，仍然报 BLOCKED。
                boolean batchesObserved = d.containsKey("max-batch-nanos")
                        && d.getOrDefault("max-batch-nanos", -1L) >= 0L
                        && d.containsKey("over-40ms-batches");
                return Map.of("status", batchesObserved ? "PASS_OBSERVED_BATCHES" : "BLOCKED_PERFORMANCE",
                        "session", session.toString(), "routes", 2, "diagnostics", d,
                        "startupPumpCallNanos", pumpCallNanos, "firstRouteHash", first.hash(), "secondRouteHash", second.hash(),
                        "purpose", "actual-native-scheduler-only-no-production-wall-writes");
            } finally { scheduler.close(); scheduler.drain().get(60, TimeUnit.SECONDS); }
        }
    }
}
