package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

final class S1TemplateSetTest {
    @Test void frozenBaseBindingPreservesAllThreeFamiliesAndFunctionalParts() {
        assertEquals("minecraft:stone_bricks", S1TemplateSet.material(PaletteFamily.STONE, false, Part.CORE).id());
        assertEquals("minecraft:deepslate_brick_stairs", S1TemplateSet.material(PaletteFamily.DEEPSLATE, false, Part.STAIR).id());
        assertEquals("minecraft:red_sandstone_slab", S1TemplateSet.material(PaletteFamily.SANDSTONE, true, Part.FLOOR).id());
        for (var family : PaletteFamily.values()) {
            assertEquals("minecraft:campfire", S1TemplateSet.material(family, false, Part.CAMPFIRE).id());
            assertEquals("minecraft:iron_bars", S1TemplateSet.material(family, false, Part.FENCE).id());
            assertEquals("minecraft:chest", S1TemplateSet.material(family, false, Part.CHEST).id());
            assertEquals("minecraft:air", S1TemplateSet.material(family, false, Part.AIR).id());
            assertEquals("minecraft:water", S1TemplateSet.material(family, false, Part.WATER).id());
        }
        assertEquals(8, S1TemplateSet.create(0).size());
        assertThrows(UnsupportedOperationException.class, () -> S1TemplateSet.create(0).clear());
    }
}
