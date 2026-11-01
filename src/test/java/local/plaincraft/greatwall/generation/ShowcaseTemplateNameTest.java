package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 展示区「这块模板该不该套门楼方块实体坐标表」判定的回归。
 *
 * <p>0.7.101 用 {@code resource.endsWith("gate.csv")} 判断「这是不是门楼」，可
 * {@code "watergate.csv".endsWith("gate.csv")} 也为真：水门盖章时套用了门楼手写的
 * {@code gate_be.jsonl}（x 63..76，而水门模板只有 x 0..45），17 条坐标全部落在空地上 ——
 * 超平坦真机日志 {@code GATE_BE_APPLIED loot=0 books=0 nbt=0 missing=17 autoLoot=0 autoBooks=0}，
 * 而同一份表在门楼上 {@code missing=0 loot=13 books=4}。
 */
final class ShowcaseTemplateNameTest {

    @Test
    void waterGateIsNotTheGate() {
        assertTrue(ShowcaseStamp.ownsGateBlockEntityTable(
                ShowcaseStamp.templateName("/assets/plaincraft-great-wall/templates/gate.csv")));
        assertFalse(ShowcaseStamp.ownsGateBlockEntityTable(
                ShowcaseStamp.templateName("/assets/plaincraft-great-wall/templates/watergate.csv")),
                "watergate.csv must never borrow the gate's block-entity table");
    }

    @Test
    void everyOtherShowcaseTemplateKeepsItsOwnName() {
        for (String name : List.of("beacon.csv", "fort.csv", "stair.csv", "stair2.csv", "corner.csv",
                "tower2.csv", "palace2.csv", "kitset.csv", "wall_module.csv", "wall_segment.csv")) {
            String resource = "/assets/plaincraft-great-wall/templates/" + name;
            assertEquals(name, ShowcaseStamp.templateName(resource));
            assertFalse(ShowcaseStamp.ownsGateBlockEntityTable(ShowcaseStamp.templateName(resource)), name);
        }
    }

    @Test
    void missingResourceIsNotTheGate() {
        assertEquals("", ShowcaseStamp.templateName(null));
        assertFalse(ShowcaseStamp.ownsGateBlockEntityTable(ShowcaseStamp.templateName(null)));
        assertEquals("gate.csv", ShowcaseStamp.templateName("gate.csv"));
    }
}
