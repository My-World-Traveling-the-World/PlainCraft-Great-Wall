package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.decay.PaletteTypes.Validation;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import org.junit.jupiter.api.Test;

/** Error policy only. Actual registry evidence belongs to PaletteResourceValidatorGameTests. */
class PaletteResourceValidatorTests {
    @Test void requireValidAcceptsOnlyAnEmptyErrorList() {
        var valid = new Validation(List.of(), "a".repeat(64), "b".repeat(64));
        assertDoesNotThrow(() -> PaletteResourceValidator.requireValid(valid));
        var invalid = new Validation(List.of("palette/table: missing block"), valid.blocksHash(), valid.tagsHash());
        var failure = assertThrows(WallFailure.class, () -> PaletteResourceValidator.requireValid(invalid));
        assertEquals(FailureCode.INVALID_RESOURCE, failure.code());
        assertEquals("invalid palette resources:\npalette/table: missing block", failure.getMessage());
    }

    @Test void oneFailureRetainsTheEntireImmutableErrorList() {
        var errors = new ArrayList<>(List.of(
                "palette/CORE/minecraft:sand: gravity",
                "palette/HAY/minecraft:hay_bale: missing block",
                "palette/LAMP/minecraft:lantern: unknown property lit"));
        var validation = new Validation(errors, "a".repeat(64), "b".repeat(64));
        errors.clear();
        assertEquals(3, validation.errors().size());
        assertThrows(UnsupportedOperationException.class, () -> validation.errors().clear());
        var failure = assertThrows(WallFailure.class, () -> PaletteResourceValidator.requireValid(validation));
        assertEquals(FailureCode.INVALID_RESOURCE, failure.code());
        assertEquals("invalid palette resources:\n" + String.join("\n", validation.errors()), failure.getMessage());
    }
}
