package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import org.junit.jupiter.api.Test;

class BackfillCompletionsTest {
    @Test
    void positionAloneDoesNotProveTheCurrentPlanCompleted() {
        var key = new ChunkKey("minecraft:overworld", 1, 2);
        var completed = new BackfillCompletions();
        assertFalse(completed.matches(key, "old"));
        completed.record(key, "old");
        assertTrue(completed.matches(key, "old"));
        assertFalse(completed.matches(key, "new"));
        assertTrue(completed.forget(key));
        assertFalse(completed.matches(key, "old"));
        completed.record(key, "new");
        assertTrue(completed.matches(key, "new"));
    }

    @Test
    void loadHintQueuesForVerificationRatherThanRecordingCompletion() throws Exception {
        var source = Files.readString(Path.of("src/main/java/local/plaincraft/greatwall/generation/WallBodyBackfill.java"));
        var onLoad = method(source, "public static void onChunkLoaded(");
        // Test 不启动 LevelChunk；对真实事件入口钉住不得直接结案，哈希匹配行为由组件验证。
        assertFalse(onLoad.contains("state.completed.record("));
        assertTrue(onLoad.contains("state.doneHint.remove(key) || plan != null) state.pending.add(key)"));
        var enqueue = method(source, "public static void enqueueCompiled(");
        assertTrue(enqueue.contains("state.completed.matches(key, entry.getValue())"));
        assertTrue(enqueue.contains("getChunkNow(key.x(), key.z()) == null"));
    }

    @Test
    void lateReadChecksOriginalWorldAndStoreBeforeEnteringQueue() throws Exception {
        var source = Files.readString(Path.of("src/main/java/local/plaincraft/greatwall/generation/WallBodyBackfill.java"));
        var read = method(source, "private static void requestPlanRead(");
        assertTrue(read.contains("STATES.get(level) == state && planStore == store"));
        assertTrue(read.contains("WallWorldgenPlanCache.isReadSessionCurrent(level, session)"));
        assertTrue(read.contains("session.finishRead(key, ticket, current ? loaded : null)"));
        assertFalse(read.contains("state(level)"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, signature);
        return source.substring(start, end);
    }
}
