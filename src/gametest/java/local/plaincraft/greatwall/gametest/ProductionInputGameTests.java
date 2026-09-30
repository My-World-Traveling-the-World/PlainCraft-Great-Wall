package local.plaincraft.greatwall.gametest;

import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.lifecycle.GenerationResourceFingerprint;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ProductionInputGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void baseMaterialsAndActualSourceFingerprintsAreComplete(GameTestHelper helper) throws java.io.IOException {
        for (var family : PaletteFamily.values()) for (boolean red : new boolean[] {false, true}) for (var part : Part.values()) {
            var spec = S1TemplateSet.material(family, red, part);
            helper.assertTrue(BlockCatalog.specification(BlockCatalog.resolveState(spec)).id().equals(spec.id()), "no missing block substitution");
        }
        var first = GenerationResourceFingerprint.captureProduction(helper.getLevel(), helper.getLevel().getServer().getResourceManager());
        var second = GenerationResourceFingerprint.captureProduction(helper.getLevel(), helper.getLevel().getServer().getResourceManager());
        helper.assertTrue(WorldRecipeGuard.fingerprint(first).equals(WorldRecipeGuard.fingerprint(second)), "stable actual source/expanded tag hash");
        helper.assertTrue(first.get("actual-source-sha256:plaincraft_great_wall").getAsString().matches("[0-9a-f]{64}"), "auditable mod code");
        helper.assertTrue(first.get("actual-source-sha256:minecraft").getAsString().matches("[0-9a-f]{64}"), "auditable native game artifact");
        helper.assertTrue(first.keySet().stream().anyMatch(key -> key.startsWith("expanded:block:")), "expanded tags included");
        System.out.println("S1C_PRODUCTION_INPUTS entries=" + first.size() + " hash=" + WorldRecipeGuard.fingerprint(first));
        helper.succeed();
    }
}
