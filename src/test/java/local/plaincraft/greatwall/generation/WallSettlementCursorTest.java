package local.plaincraft.greatwall.generation;

import java.util.*;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 外层事务与独立冻结Painter联合对拍，不能用两个新游标互相证明等价。 */
class WallSettlementCursorTest {
    @Test void everyPauseMatchesFrozenOuterOrderAndRealPlan() {
        var plan = plan();
        int compared = 0;
        for (int flags = 0; flags < 32; flags++) {
            var old = new Fake(flags);
            var expected = frozen(plan, old);
            long tail = old.random.nextLong();
            for (int cut = 0; cut < 9; cut++) {
                var actual = new Fake(flags);
                var cursor = new WallSettlementCursor<>(plan, actual);
                for (int step = 0; step < cut && !cursor.terminal(); step++) cursor.advance(1);
                assertEquals(expected, cursor.runAll());
                assertEquals(old.events, actual.events, "flags=" + flags + " cut=" + cut);
                assertEquals(old.blocks, actual.blocks);
                assertEquals(old.satisfied, actual.satisfied);
                assertEquals(tail, actual.random.nextLong());
                assertEquals(1, Collections.frequency(actual.events, "release"));
                var before = List.copyOf(actual.events);
                assertEquals(expected, cursor.runAll()); assertEquals(before, actual.events);
                compared++;
            }
        }
        assertEquals(288, compared);
    }

    @Test void metadataIsASeparateStageAndCancellationNeverPublishes() {
        var actual = new Fake(0);
        var cursor = new WallSettlementCursor<>(plan(), actual);
        while (cursor.stage() != WallSettlementCursor.Stage.METADATA) {
            cursor.advance(1);
            assertFalse(actual.events.contains("finish:true"));
            assertFalse(actual.events.contains("publish"));
        }
        assertEquals(2, actual.blocks.size());
        cursor.cancel();
        assertEquals(WallSettlementCursor.Stage.CANCELLED, cursor.stage());
        assertFalse(actual.events.contains("finish:true"));
        assertFalse(actual.events.contains("publish"));
        assertThrows(IllegalStateException.class, cursor::result);
        assertEquals(1, Collections.frequency(actual.events, "release"));
    }

    @Test void worldChunkLeaseOrHashLossAtEveryBoundaryCancelsWithoutMoreWork() {
        int reached = 0;
        for (var target : List.of(WallSettlementCursor.Stage.CHECK, WallSettlementCursor.Stage.REUSE,
                WallSettlementCursor.Stage.PREPARE, WallSettlementCursor.Stage.PAINT,
                WallSettlementCursor.Stage.METADATA, WallSettlementCursor.Stage.PUBLISH)) {
            var actual = new Fake(target == WallSettlementCursor.Stage.REUSE ? 1 : 0);
            var cursor = new WallSettlementCursor<>(plan(), actual);
            for (int limit = 0; cursor.stage() != target && limit < 15; limit++) cursor.advance(1);
            assertEquals(target, cursor.stage());
            var before = new ArrayList<>(actual.events); actual.current = false;
            cursor.advance(1); before.add("release");
            assertEquals(before, actual.events);
            assertEquals(WallSettlementCursor.Stage.CANCELLED, cursor.stage());
            cursor.cancel(); assertEquals(before, actual.events); reached++;
        }
        assertEquals(6, reached);
    }

    @Test void outerPortExceptionsReleaseOnceAndCannotReplay() {
        for (String point : List.of("check", "reuse", "prepare", "world", "publish", "release")) {
            var old = new Fake(point.equals("reuse") ? 1 : 0); old.failure = point;
            var expected = assertThrows(IllegalStateException.class, () -> frozen(plan(), old));
            var actual = new Fake(point.equals("reuse") ? 1 : 0); actual.failure = point;
            var cursor = new WallSettlementCursor<>(plan(), actual);
            var failure = assertThrows(IllegalStateException.class, cursor::runAll);
            assertEquals(expected.getMessage(), failure.getMessage());
            assertEquals(old.events, actual.events);
            assertEquals(WallSettlementCursor.Stage.FAILED, cursor.stage());
            var before = List.copyOf(actual.events);
            assertThrows(IllegalStateException.class, cursor::runAll); assertEquals(before, actual.events);
        }
    }

    /** 迁移前settle次序加原外层finally，内核使用已有独立Frozen实现。 */
    private static PublishedPlanPainter.Result frozen(ChunkWritePlan plan, Fake world) {
        try {
            if (world.preserveCompleted()) {
                world.reuseCompleted();
                return new PublishedPlanPainter.Result(true, 0, 0);
            }
            world.prepare(); world.world();
            var result = FrozenPublishedPlanPainter.paint(plan, world);
            if (result.complete()) world.publish();
            return new PublishedPlanPainter.Result(result.complete(), result.written(), result.failed());
        } finally { world.release(); }
    }

    private static ChunkWritePlan plan() {
        String hash = "a".repeat(64);
        var id = new SiteId("wall", SiteKind.WALL, 1);
        var voxels = List.of(0, 1).stream().map(x -> new VoxelIntent(new Point3i(x, 1, 0),
                IntentKind.SOLID, Part.CORE, new BlockSpec("minecraft:stone", Map.of()), id, 400, true, true)).toList();
        var body = new ComponentVoxels(id, new Bounds(0, 0, 0, 2, 3, 1), voxels, List.of(), List.of(), List.of(), hash);
        var component = new ComponentVoxels(body.id(), body.bounds(), body.voxels(), body.blockEntities(),
                body.spawnSlots(), body.requiredRoutes(), CanonicalCodec.componentHash(body));
        var cell = new RealizationCell(new CellKey(hash, "minecraft:overworld", 0), List.of(), List.of(component),
                hash, 0, hash, hash);
        var plan = new ChunkPlanCompiler().compile(new ChunkKey("minecraft:overworld", 0, 0), hash, List.of(cell),
                Map.of(new Point3i(0, 1, 0), "minecraft:air{}", new Point3i(1, 1, 0), "minecraft:air{}"));
        assertEquals(2, plan.voxels().size());
        return plan;
    }

    private static final class Fake implements WallSettlementCursor.Boundary<String>,
            PublishedPlanPainter.World<String>, FrozenPublishedPlanPainter.World<String> {
        final List<String> events = new ArrayList<>();
        final Map<Point3i, String> blocks = new HashMap<>();
        final Random random = new Random(17);
        Set<Point3i> satisfied = Set.of();
        final int flags;
        String failure;
        boolean current = true;
        Fake(int flags) {
            this.flags = flags;
            if ((flags & 8) != 0) blocks.put(new Point3i(0, 1, 0), "minecraft:stone");
        }
        void event(String text) { events.add(text); if (text.equals(failure)) throw new IllegalStateException(text); }
        public boolean preserveCompleted() { event("check"); return (flags & 1) != 0; }
        public void reuseCompleted() { event("reuse"); }
        public void prepare() { event("prepare"); }
        public PublishedPlanPainter.World<String> world() { event("world"); return this; }
        public void publish() { event("publish"); }
        public void release() { event("release"); }
        public boolean current() { return current; }
        public boolean validHeight(int y) { return true; }
        public String resolve(BlockSpec spec) {
            if ((flags & 16) != 0) throw new IllegalStateException("材料故障");
            return spec.id();
        }
        public String read(Point3i p) { return blocks.getOrDefault(p, "minecraft:air"); }
        public boolean write(Point3i p, String target) {
            event("write:" + p.x()); event("random:" + random.nextLong());
            if ((flags & 2) != 0 && p.x() == 0) return false;
            blocks.put(p, target); return true;
        }
        public void finish(Set<Point3i> points, boolean complete) {
            satisfied = points;
            if ((flags & 4) != 0) throw new IllegalStateException("元数据故障");
            event("finish:" + complete);
        }
        public void failure(Point3i p, RuntimeException failure) { event("failure:" + failure.getMessage()); }
        public void written(Point3i p, String target, int count) {}
    }
}
