package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.TreeMap;
import local.plaincraft.greatwall.generation.TreeBoundsCatalog;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;

/** Completed LOAD callbacks are a prerequisite. Only immutable adapters/factories are published. */
public record ProductionInputs(WorldBootstrap.Frozen bootstrap, BlockCatalog blocks, TreeBoundsCatalog trees) implements AutoCloseable {
    @Override public void close() {
        // 工厂生命周期跟随本会话，停止时唤醒等待者并释放所有lane共用的结果。
        if(bootstrap.factory() instanceof CpuTerrainOracle.SharedOracleFactory factory)factory.close();
    }

    public static ProductionInputs freeze(ServerLevel level) throws IOException {
        var blocks = new BlockCatalog(level.registryAccess());
        var trees = new TreeBoundsCatalog(level.registryAccess(),
                level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).stream().toList());
        var inputs = new TreeMap<>(GenerationResourceFingerprint.captureProduction(level, level.getServer().getResourceManager()));
        inputs.put("block-classification-sha256", new JsonPrimitive(blocks.hash()));
        inputs.put("tree-bounds-sha256", new JsonPrimitive(trees.hash()));
        String terrainHash = WorldRecipeGuard.fingerprint(inputs);
        var frozen = CpuTerrainOracle.freeze(level, terrainHash);
        var catalog = local.plaincraft.greatwall.decay.PaletteResolver.load(level.getServer().getResourceManager());
        new local.plaincraft.greatwall.decay.PaletteResolver(0, catalog);
        var biomeSnapshot = local.plaincraft.greatwall.decay.BiomePaletteIndex.freeze(level.registryAccess(), catalog);
        String paletteHash = local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                (catalog.hash() + ":" + biomeSnapshot.hash()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var versions = new TreeMap<String, JsonElement>();
        versions.put("templateVersion", new JsonPrimitive(1));
        // The source-byte SHA is intentionally not part of templatesHash: it changes on every
        // recompile even when the template/generation logic is untouched, which needlessly
        // invalidates an existing world. Generation changes must bump the mod version instead,
        // which already feeds terrainHash through actual-mod:*.
        String templatesHash = WorldRecipeGuard.fingerprint(versions);
        var root = new WorldBootstrap.Frozen(frozen.bounds(), terrainHash, paletteHash, templatesHash, frozen.sharedFactory(),
                (oracle, cursor) -> {
                    if (!(oracle instanceof CpuTerrainOracle cpu)) throw new IllegalStateException("production foundation requires its private CPU oracle");
                    var evidence = cpu.foundationEvidence(cursor);
                    return evidence.size() == 3 && evidence.stream()
                            .allMatch(spec -> blocks.flags(BlockCatalog.token(BlockCatalog.resolveState(spec), false)).support());
                },new local.plaincraft.greatwall.generation.NativeRootStructureScreen(level),
                local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.of(level));
        return new ProductionInputs(root, blocks, trees);
    }
}
