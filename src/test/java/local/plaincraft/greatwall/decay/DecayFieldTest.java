package local.plaincraft.greatwall.decay;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class DecayFieldTest {
    @Test void fieldIsDeterministicContinuousAndMaintenanceIsOrderIndependent() {
        var field = new DecayField(73);
        for (long s = 0; s <= 4096L * 256; s += 256) {
            var a = field.sample(s, PaletteFamily.STONE, List.of(300L * 256, 600L * 256));
            var b = new DecayField(73).sample(s, PaletteFamily.STONE, List.of(600L * 256, 300L * 256));
            assertEquals(a, b); assertTrue(a.keepHeight() >= 2 && a.keepHeight() <= 20);
            var next = field.sample(s + 1, PaletteFamily.STONE, List.of(300L * 256, 600L * 256));
            assertTrue(Math.abs(a.amountQ16() - next.amountQ16()) <= 20);
        }
        assertTrue(field.sample(300L * 256, PaletteFamily.STONE, List.of(300L * 256)).amountQ16()
                <= field.sample(300L * 256, PaletteFamily.STONE, List.of()).amountQ16());
    }

    @Test void familyBiasDoesNotChangeNoiseOrRequiredSupport() {
        var field = new DecayField(1);
        for (int i = 0; i < 100; i++) {
            int stone = field.sample(i * 256L, PaletteFamily.STONE, List.of()).amountQ16();
            assertEquals(Math.min(65535, stone + 3277), field.sample(i * 256L, PaletteFamily.DEEPSLATE, List.of()).amountQ16());
        }
        for (DecayState state : DecayState.values())
            assertTrue(field.keep(new DecaySample(state, 65535, 2), Part.CORE, new Point3i(0, 5, 0), 500, 2, true));
    }

    @Test void ruinHeightIsRelativeToGroundAndPartialDetailsAreGrouped() {
        var field = new DecayField(3); var ruin = new DecaySample(DecayState.RUIN, 40000, 5);
        assertTrue(field.keep(ruin, Part.CORE, new Point3i(0, -16, 0), 0, 10, false));
        assertFalse(field.keep(ruin, Part.CORE, new Point3i(0, -15, 0), 0, 10, false));
        var partial = new DecaySample(DecayState.PARTIAL, 30000, 10);
        assertEquals(field.keep(partial, Part.PARAPET, new Point3i(0, 0, 0), 8L * 256, 10, false),
                field.keep(partial, Part.PARAPET, new Point3i(20, 1, 3), 11L * 256, 10, false));
    }
}
