package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 门楼容器「兜底战利品规则」的回归（0.7.100）。
 *
 * <p>玩家在存档里新放一个箱子后，只要重采模板，它就应该自带战利品 —— 这靠 {@code GateBlockEntities}
 * 里的兜底规则完成，而不是每次人工往 {@code gate_be.jsonl} 补坐标。这里钉住：
 * <ul>
 *   <li>箱子/木桶按「上层 / 下层」分配表（上层军械，下层补给与杂物）；</li>
 *   <li>不是容器的方块（石砖等）与雕纹书架（走塞书规则）都不得被分到战利品表；</li>
 *   <li>兜底用到的表 id 必须都有真实资源文件（否则开箱时才报错就晚了）；</li>
 *   <li>雕纹书架兜底塞 2~3 本，其他方块为 0。</li>
 * </ul>
 */
final class GateBlockEntityDefaultsTest {

    @Test
    void chestsAndBarrelsSplitByFloor() {
        assertEquals("supplies", GateBlockEntities.defaultTableFor("minecraft:chest", 16));
        assertEquals("armory", GateBlockEntities.defaultTableFor("minecraft:chest", 24));
        assertEquals("supplies", GateBlockEntities.defaultTableFor("minecraft:trapped_chest", 0));
        assertEquals("armory", GateBlockEntities.defaultTableFor("minecraft:trapped_chest", 30));
        assertEquals("ordinary", GateBlockEntities.defaultTableFor("minecraft:barrel", 16));
        assertEquals("armory", GateBlockEntities.defaultTableFor("minecraft:barrel", 24));
        // 分界点必须按设计（>= UPPER_FLOOR_Y 才是上层）
        assertEquals("supplies", GateBlockEntities.defaultTableFor("minecraft:chest",
                19));
        assertEquals("armory", GateBlockEntities.defaultTableFor("minecraft:chest",
                20));
    }

    @Test
    void nonContainersAreLeftAlone() {
        assertNull(GateBlockEntities.defaultTableFor("minecraft:stone_bricks", 16));
        assertNull(GateBlockEntities.defaultTableFor("minecraft:chiseled_bookshelf", 16));
        assertNull(GateBlockEntities.defaultTableFor("minecraft:shulker_box", 16));
        assertNull(GateBlockEntities.defaultTableFor(null, 16));
        assertEquals(0, GateBlockEntities.defaultBooksFor("minecraft:chest", 7L));
        assertEquals(0, GateBlockEntities.defaultBooksFor("minecraft:stone_bricks", 7L));
    }

    @Test
    void bookshelvesGetTwoOrThreeBooks() {
        for (long seed = -16L; seed < 16L; seed++) {
            int books = GateBlockEntities.defaultBooksFor("minecraft:chiseled_bookshelf", seed);
            assertTrue(books >= 2 && books <= 3, "seed=" + seed + " books=" + books);
        }
    }

    @Test
    void fallbackTablesExistAsResources() {
        for (String table : List.of("supplies", "armory", "ordinary")) {
            assertNotNull(GateBlockEntityDefaultsTest.class.getClassLoader()
                            .getResourceAsStream("data/plaincraft_great_wall/loot_table/chests/" + table + ".json"),
                    "兜底引用的表不存在: " + table);
        }
    }
}
