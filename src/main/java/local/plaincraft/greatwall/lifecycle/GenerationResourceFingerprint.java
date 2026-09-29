package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public final class GenerationResourceFingerprint {
    private GenerationResourceFingerprint() {}

    public static Map<String, JsonElement> capture(ServerLevel level, ResourceManager resources) throws IOException {
        var inputs = new TreeMap<String, JsonElement>();
        inputs.put("probeAlgorithm", new JsonPrimitive(1));
        inputs.put("seed", new JsonPrimitive(level.getSeed()));
        inputs.put("dimension", new JsonPrimitive(level.dimension().location().toString()));
        inputs.put("minY", new JsonPrimitive(level.getMinBuildHeight()));
        inputs.put("maxYExclusive", new JsonPrimitive(level.getMaxBuildHeight()));
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var generator = level.getChunkSource().getGenerator();
        inputs.put("effectiveGenerator", ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow());
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            inputs.put("effectiveNoiseSettings", NoiseGeneratorSettings.DIRECT_CODEC
                    .encodeStart(ops, noise.generatorSettings().value()).getOrThrow());
        }
        for (String prefix : new String[]{"worldgen", "dimension", "dimension_type", "tags/worldgen",
                "tags/block", "plaincraft_great_wall"}) {
            for (var entry : resources.listResources(prefix, name -> name.getPath().endsWith(".json")).entrySet()) {
                try (var reader = entry.getValue().openAsReader()) {
                    inputs.put("resource:" + entry.getKey(), JsonParser.parseReader(reader));
                }
            }
        }
        var config = FabricLoader.getInstance().getConfigDir().resolve("tectonic.json");
        if (FabricLoader.getInstance().isModLoaded("tectonic") && Files.isRegularFile(config)) {
            try (var reader = Files.newBufferedReader(config)) {
                inputs.put("config:tectonic", JsonParser.parseReader(reader));
            }
        }
        for (String id : new String[]{"minecraft", "tectonic", "lithostitched", "c2me", "c2me-opts-accel-opencl"}) {
            FabricLoader.getInstance().getModContainer(id).ifPresent(mod ->
                    inputs.put("mod:" + id, new JsonPrimitive(mod.getMetadata().getVersion().getFriendlyString())));
        }
        return Map.copyOf(inputs);
    }
}
