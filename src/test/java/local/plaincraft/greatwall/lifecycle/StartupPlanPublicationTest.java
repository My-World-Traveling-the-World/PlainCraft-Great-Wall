package local.plaincraft.greatwall.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupPlanPublicationTest {
    @Test void failingOwnerDoesNotLoseTheNextOwnerOrTurnItsOwnFailureIntoSuccess() {
        var cacheRejected = plan(0, "a");
        var maskRejected = plan(1, "b");
        var accepted = plan(2, "c");
        var failedCache = new IllegalStateException("测试缓存拒绝");
        var footprints = new ArrayList<ChunkKey>();
        var publication = new StartupPlanPublication();
        for (var plan : List.of(cacheRejected, maskRejected, accepted)) {
            publication.accept(plan, current -> {
                if (current == cacheRejected) throw failedCache;
            }, current -> {
                footprints.add(current.owner());
                if (current == maskRejected) throw new IllegalStateException("测试掩码拒绝");
            });
        }
        // 缓存未接纳不得排队；缓存成功的区块保留哈希以便仍可补建，掩码失败须另计。
        assertEquals(Map.of(maskRejected.owner(), maskRejected.hash(), accepted.owner(), accepted.hash()),
                publication.queuedHashes());
        assertEquals(List.of(maskRejected.owner(), accepted.owner()), footprints);
        assertEquals(1, publication.published());
        assertEquals(2, publication.failed());
        assertSame(failedCache, publication.firstFailure());
    }

    private static ChunkWritePlan plan(int x, String hashChar) {
        String hash = hashChar.repeat(64);
        return new ChunkWritePlan(new ChunkKey("minecraft:overworld", x, 0), hash, hash,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), hash);
    }
}
