package local.plaincraft.greatwall.gametest;

import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.lifecycle.GenerationResourceFingerprint;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The recipe freezes mod <em>versions</em> ({@code actual-mod:*}), not per-mod source bytes.
 *
 * <p>{@code aa89e1e3} ("stop binding the recipe hash to per-mod source-byte SHA") deleted the
 * {@code actual-source-sha256:*} entries together with {@code sourceHash}: a source-byte digest changes on
 * every recompile even when the generation logic is untouched, which forced a new world (and a full
 * prewarm) for startup/deadlock/far-chunk fixes. The surviving contract is the opposite one: the wall's own
 * version and every other worldgen-affecting mod version are in the recipe, and no source-byte key may
 * come back.
 */
public final class ProductionInputGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void baseMaterialsAndRecipeFingerprintBindModVersionsNotSourceBytes(GameTestHelper helper) throws java.io.IOException {
        for (var family : PaletteFamily.values()) for (boolean red : new boolean[] {false, true}) for (var part : Part.values()) {
            var spec = S1TemplateSet.material(family, red, part);
            helper.assertTrue(BlockCatalog.specification(BlockCatalog.resolveState(spec)).id().equals(spec.id()), "no missing block substitution");
        }
        var first = GenerationResourceFingerprint.captureProduction(helper.getLevel(), helper.getLevel().getServer().getResourceManager());
        var second = GenerationResourceFingerprint.captureProduction(helper.getLevel(), helper.getLevel().getServer().getResourceManager());
        helper.assertTrue(WorldRecipeGuard.fingerprint(first).equals(WorldRecipeGuard.fingerprint(second)), "stable actual source/expanded tag hash");
        // The deleted contract must stay deleted: a recompile may not invalidate a saved world again.
        helper.assertTrue(first.keySet().stream().noneMatch(key -> key.startsWith("actual-source-sha256:")),
                "per-mod source-byte SHA is not part of the recipe any more");
        helper.assertTrue(first.containsKey("actual-mod:minecraft"),
                "the native game version stays frozen in the recipe");
        int bound = 0;
        for (var entry : first.entrySet()) {
            if (!entry.getKey().startsWith("actual-mod:")) continue;
            String id = entry.getKey().substring("actual-mod:".length());
            var mod = FabricLoader.getInstance().getModContainer(id).orElseThrow(
                    () -> new AssertionError("recipe froze an unknown mod id: " + id));
            helper.assertTrue(mod.getMetadata().getVersion().getFriendlyString().equals(entry.getValue().getAsString()),
                    "recipe binds the version actually loaded for " + id);
            bound++;
        }
        helper.assertTrue(bound > 1, "the recipe still freezes the worldgen-affecting mod surface, not one entry");
        helper.assertTrue(first.keySet().stream().anyMatch(key -> key.startsWith("expanded:block:")), "expanded tags included");
        System.out.println("S1C_PRODUCTION_INPUTS entries=" + first.size() + " modVersions=" + bound
                + " hash=" + WorldRecipeGuard.fingerprint(first));
        helper.succeed();
    }
}
