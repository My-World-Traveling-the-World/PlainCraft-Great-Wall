package local.plaincraft.greatwall.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 更新标志直接核对实际落块入口，旧版与实物版的形状策略分别固定。 */
class ImportedBlockUpdatesTest {
    @Test void authoredShapesAreFrozenAndLightSourcesNotifyNeighbors() {
        assertEquals(2, WallBodyBackfill.updateFlags(false, 0));
        assertEquals(2, WallBodyBackfill.updateFlags(false, 15));
        assertEquals(2 | 16, WallBodyBackfill.updateFlags(true, 0));
        assertEquals(2 | 16 | 1, WallBodyBackfill.updateFlags(true, 15));
    }
}
