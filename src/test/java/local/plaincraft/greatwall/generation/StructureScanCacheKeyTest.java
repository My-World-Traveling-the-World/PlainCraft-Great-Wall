package local.plaincraft.greatwall.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureScanCacheKeyTest {
    @Test void sameChunkBoundsDoNotAliasDifferentCorridorsOrRecipes() {
        var a = new CorridorStructureScan.Corridor(0, 0, 20, 17, 576);
        var b = new CorridorStructureScan.Corridor(0, 0, 20, 18, 576);
        var c = new CorridorStructureScan.Corridor(0, 0, 20, 17, 575);
        // 区块外框及结构键完全相同，只有筛选条件改变，验证重开不会命中旧扫描。
        assertNotEquals(key(a, "recipe-a"), key(b, "recipe-a"));
        assertNotEquals(key(a, "recipe-a"), key(c, "recipe-a"));
        assertNotEquals(key(a, "recipe-a"), key(a, "recipe-b"));
        assertEquals(key(a, "recipe-a"), key(a, "recipe-a"));
    }

    private static String key(CorridorStructureScan.Corridor corridor, String recipe) {
        return StructureScanCache.key("minecraft:overworld", 1L, -36, -36, 37, 37, corridor, recipe, "same-registry");
    }
}
