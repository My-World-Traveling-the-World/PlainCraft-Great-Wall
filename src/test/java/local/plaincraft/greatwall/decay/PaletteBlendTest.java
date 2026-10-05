package local.plaincraft.greatwall.decay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import local.plaincraft.greatwall.decay.PaletteTypes.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/**
 * S4 JUnit acceptance for the piecewise-constant eight-block biome blend and the
 * biome -> family classification. The window is [S-1024, S+1024) in Q8 units, so it
 * spans exactly eight blocks (2048 Q8). Boundaries sit at adjacent-station midpoints.
 */
class PaletteBlendTest {
    private static final long BLOCK = 256L;

    @Test
    void exactEightBlockBand() {
        // Two stations: STONE at 0, DEEPSLATE at 2048*256. Boundary = floorDiv(0 + 2048*256, 2).
        var ribbon = List.of(
                new BiomeStation(0, "minecraft:plains", Variant.STONE),
                new BiomeStation(2048L * BLOCK, "minecraft:plains", Variant.DEEPSLATE));
        long boundary = Math.floorDiv(0 + 2048L * BLOCK, 2);

        // b-4: the whole window sits left of the boundary -> all STONE.
        var left = PaletteBlend.at(boundary - 1024, ribbon);
        assertEquals(List.of(new Weight(Variant.STONE, 65536)), left.weights());
        assertEquals(65536, sum(left));

        // b: window is split exactly in half -> 50/50.
        var center = PaletteBlend.at(boundary, ribbon);
        assertEquals(List.of(
                new Weight(Variant.STONE, 32768),
                new Weight(Variant.DEEPSLATE, 32768)), center.weights());
        assertEquals(65536, sum(center));

        // b+4: the whole window sits right of the boundary -> all DEEPSLATE.
        var right = PaletteBlend.at(boundary + 1024, ribbon);
        assertEquals(List.of(new Weight(Variant.DEEPSLATE, 65536)), right.weights());
        assertEquals(65536, sum(right));

        // b±4 outside: no mixing ever bleeds past the eight-block window.
        assertEquals(Variant.STONE, PaletteBlend.choose(PaletteBlend.at(boundary - 1025, ribbon), 0));
        assertEquals(Variant.STONE, PaletteBlend.choose(PaletteBlend.at(boundary - 1025, ribbon), -1));
        assertEquals(Variant.DEEPSLATE, PaletteBlend.choose(PaletteBlend.at(boundary + 1025, ribbon), 0));
        assertEquals(Variant.DEEPSLATE, PaletteBlend.choose(PaletteBlend.at(boundary + 1025, ribbon), Long.MAX_VALUE));

        // Determinism: identical queries produce identical mixes and hashes.
        assertEquals(center, PaletteBlend.at(boundary, ribbon));
        assertEquals(center.hash(), PaletteBlend.at(boundary, ribbon).hash());
        assertTrue(center.hash().matches("[0-9a-f]{64}"), "hash must be 64 lowercase hex");
    }

    @Test
    void multipleBoundariesNormalizeExactly() {
        // Three switches inside eight blocks: stations at 0/512/1024/1536/2048 Q8.
        var ribbon = List.of(
                new BiomeStation(0, "minecraft:plains", Variant.STONE),
                new BiomeStation(512, "minecraft:plains", Variant.DEEPSLATE),
                new BiomeStation(1024, "minecraft:plains", Variant.SANDSTONE),
                new BiomeStation(1536, "minecraft:plains", Variant.RED_SANDSTONE),
                new BiomeStation(2048, "minecraft:plains", Variant.STONE));

        for (long s = -2048; s <= 4096; s += 1) {
            Mix mix = PaletteBlend.at(s, ribbon);
            assertEquals(65536, sum(mix), "mix at S=" + s + " must normalize to 65536");
            for (Weight w : mix.weights()) assertTrue(w.weightQ16() > 0, "no zero weights at S=" + s);
            // Hash is derived only from the sorted weight list, never the query order.
            assertEquals(expectedHash(mix.weights()), mix.hash(), "hash at S=" + s);
            // Determinism across repeated query and across a far chunk-aligned shift.
            assertEquals(mix, PaletteBlend.at(s, ribbon));
        }
        // A far station (across a chunk boundary) still normalizes with the same hash rule.
        var far = PaletteBlend.at(2048L * BLOCK * 3, ribbon);
        assertEquals(65536, sum(far));
        assertEquals(expectedHash(far.weights()), far.hash());
        // Every mix must be usable by choose() with an unsigned roll.
        for (long s : new long[] {0, 512, 768, 1024, 1280, 1792, 2048}) {
            Mix mix = PaletteBlend.at(s, ribbon);
            assertNotNull(PaletteBlend.choose(mix, 0));
            assertNotNull(PaletteBlend.choose(mix, Long.MIN_VALUE));
            assertNotNull(PaletteBlend.choose(mix, Long.MAX_VALUE));
        }
    }

    @Test
    void unknownBiomeFallsBackAndTagsWin() {
        // Hand-built snapshot mirrors freeze(): red is a sandstone sub-family and only
        // wins when the biome is in BOTH sets. classify() never queries the world and
        // never calls possibleBiomes (verified by source: iteration is holder keys + tag
        // membership only).
        var snapshot = new Snapshot(
                Set.of("minecraft:desert", "minecraft:badlands"),
                Set.of("minecraft:dark_forest"),
                Set.of("minecraft:badlands", "minecraft:mesa_red"),
                PaletteResolver.defaults(),
                "a".repeat(64));
        var index = new BiomePaletteIndex(snapshot);

        // Tags win.
        assertEquals(Variant.SANDSTONE, index.classify("minecraft:desert"));
        assertEquals(Variant.DEEPSLATE, index.classify("minecraft:dark_forest"));
        // red in BOTH red and sandstone sets -> red sandstone wins.
        assertEquals(Variant.RED_SANDSTONE, index.classify("minecraft:badlands"));
        // red alone (not part of the sandstone set) never wins on its own.
        assertEquals(Variant.STONE, index.classify("minecraft:mesa_red"));
        // Unknown biome falls back to stone.
        assertEquals(Variant.STONE, index.classify("minecraft:plains"));
        assertEquals(Variant.STONE, index.classify("minecraft:no_such_biome"));
        assertEquals(Variant.STONE, index.classify("examplemod:custom_forest"));
    }

    @Test
    void emptyAndUnorderedRibbonsFailClosed() {
        // Empty ribbon.
        invalid(() -> PaletteBlend.at(0, List.of()));
        // Decreasing station order.
        invalid(() -> PaletteBlend.at(0, List.of(
                new BiomeStation(2048L * BLOCK, "minecraft:plains", Variant.STONE),
                new BiomeStation(0, "minecraft:plains", Variant.DEEPSLATE))));
        // Duplicate station (not strictly increasing).
        invalid(() -> PaletteBlend.at(0, List.of(
                new BiomeStation(0, "minecraft:plains", Variant.STONE),
                new BiomeStation(0, "minecraft:plains", Variant.DEEPSLATE))));
        // A single station still normalizes to one variant (positive sanity, not a failure).
        var single = PaletteBlend.at(0, List.of(
                new BiomeStation(1024L * BLOCK, "minecraft:plains", Variant.STONE)));
        assertEquals(List.of(new Weight(Variant.STONE, 65536)), single.weights());
        assertEquals(Variant.STONE, PaletteBlend.choose(single, 0));
        assertEquals(Variant.STONE, PaletteBlend.choose(single, Long.MAX_VALUE));
    }

    private static long sum(Mix mix) {
        long total = 0;
        for (Weight w : mix.weights()) total += w.weightQ16();
        return total;
    }

    private static String expectedHash(List<Weight> weights) {
        StringBuilder text = new StringBuilder();
        for (Weight w : weights) text.append(w.variant().name()).append(':').append(w.weightQ16()).append(';');
        return DeterministicHash.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void invalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(FailureCode.INVALID_RESOURCE, assertThrows(WallFailure.class, action).code());
    }
}
