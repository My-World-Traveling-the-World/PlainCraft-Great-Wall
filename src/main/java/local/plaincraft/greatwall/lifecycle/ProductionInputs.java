package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.generation.TreeBoundsCatalog;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;

/** Completed LOAD callbacks are a prerequisite. Only immutable adapters/factories are published. */
public record ProductionInputs(WorldBootstrap.Frozen bootstrap, BlockCatalog blocks, TreeBoundsCatalog trees) {
    public static ProductionInputs freeze(ServerLevel level) throws IOException {
        var blocks = new BlockCatalog(level.registryAccess());
        var trees = new TreeBoundsCatalog(level.registryAccess(),
                level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).stream().toList());
        var inputs = new TreeMap<>(GenerationResourceFingerprint.captureProduction(level, level.getServer().getResourceManager()));
        inputs.put("block-classification-sha256", new JsonPrimitive(blocks.hash()));
        inputs.put("tree-bounds-sha256", new JsonPrimitive(trees.hash()));
        String terrainHash = WorldRecipeGuard.fingerprint(inputs);
        var frozen = CpuTerrainOracle.freeze(level, terrainHash);
        var materials = new TreeMap<String, JsonElement>();
        for (var family : PaletteFamily.values()) for (boolean red : new boolean[] {false, true}) for (var part : Part.values()) {
            var spec = S1TemplateSet.material(family, red, part);
            blocks.resolve(spec); // Missing material is fatal at freeze, never substituted with air.
            materials.put(family + ":" + red + ":" + part, new JsonPrimitive(spec.id()));
        }
        String paletteHash = WorldRecipeGuard.fingerprint(materials);
        var versions = new TreeMap<String, JsonElement>();
        versions.put("templateVersion", new JsonPrimitive(1));
        // Actual source hashes in terrain inputs bind all compiled templates, support and exit algorithms.
        versions.put("source", inputs.get("actual-source-sha256:plaincraft_great_wall"));
        String templatesHash = WorldRecipeGuard.fingerprint(versions);
        var root = new WorldBootstrap.Frozen(frozen.bounds(), terrainHash, paletteHash, templatesHash, frozen::createOracle,
                (oracle, cursor) -> {
                    if (!(oracle instanceof CpuTerrainOracle cpu)) throw new IllegalStateException("production foundation requires its private CPU oracle");
                    var evidence = cpu.supportEvidence(cursor);
                    return evidence.size() == 3 && evidence.stream()
                            .allMatch(spec -> blocks.flags(BlockCatalog.token(BlockCatalog.resolveState(spec), false)).support());
                });
        return new ProductionInputs(root, blocks, trees);
    }
}
