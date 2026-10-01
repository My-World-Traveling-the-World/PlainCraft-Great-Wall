package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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

    /** Frozen semantic inputs only. Optional code is not linked, and possibleBiomes is never consulted. */
    public static Map<String, JsonElement> captureProduction(ServerLevel level, ResourceManager resources) throws IOException {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("production inputs freeze on server owner");
        var inputs = new TreeMap<>(capture(level, resources));
        inputs.remove("probeAlgorithm");
        inputs.put("wallAlgorithm", new JsonPrimitive(1));
        inputs.put("rootAlgorithm", new JsonPrimitive(2));
        inputs.put("rootStructureScreen",new JsonPrimitive("pre-recipe-native-references-v1"));
        inputs.put("naturalReplacementPolicy",new JsonPrimitive("explicit-ores-gravel-v1"));
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
            var values = new com.google.gson.JsonArray();
            pair.getSecond().stream().map(holder -> BuiltInRegistries.BLOCK.getKey(holder.value()).toString())
                    .sorted().forEach(values::add);
            inputs.put("expanded:block:" + pair.getFirst().location(), values);
        });
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        biomes.getTags().forEach(pair -> {
            var values = new com.google.gson.JsonArray();
            pair.getSecond().stream().map(holder -> holder.unwrapKey().orElseThrow().location().toString())
                    .sorted().forEach(values::add);
            inputs.put("expanded:biome:" + pair.getFirst().location(), values);
        });
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            inputs.put("actual-mod:" + id, new JsonPrimitive(mod.getMetadata().getVersion().getFriendlyString()));
            if (!id.equals("java")) inputs.put("actual-source-sha256:" + id, new JsonPrimitive(sourceHash(mod)));
        }
        Path c2me = FabricLoader.getInstance().getConfigDir().resolve("c2me.toml");
        if (FabricLoader.getInstance().isModLoaded("c2me") && Files.isRegularFile(c2me)) {
            inputs.put("config:c2me-sha256", new JsonPrimitive(digest(c2me)));
        }
        return Map.copyOf(inputs);
    }

    static String sourceHash(ModContainer mod) throws IOException {
        var origin = mod.getOrigin();
        if (origin.getKind().name().equals("NESTED")) {
            var parent = mod.getContainingMod().orElseThrow(() -> new IOException("nested mod has no parent: " + mod.getMetadata().getId()));
            return digest(parent.findPath(origin.getParentSubLocation())
                    .orElseThrow(() -> new IOException("nested source artifact missing: " + mod.getMetadata().getId())));
        }
        var paths = origin.getPaths();
        if (paths.isEmpty()) throw new IOException("mod has no auditable source: " + mod.getMetadata().getId());
        var values = new TreeMap<String, JsonElement>();
        int index = 0;
        for (var path : paths) {
            if (Files.isRegularFile(path)) values.put("artifact:" + index++, new JsonPrimitive(digest(path)));
            else if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    var entries = files.filter(Files::isRegularFile).sorted().limit(100001).toList();
                    if (entries.size() > 100000) throw new IOException("source directory file allowance exceeded");
                    for (var file : entries) values.put("directory:" + index + ":" + path.relativize(file).toString().replace('\\', '/'),
                            new JsonPrimitive(digest(file)));
                }
                index++;
            } else throw new IOException("source artifact is not readable: " + path);
        }
        if (values.isEmpty()) throw new IOException("empty mod source cannot be certified");
        return WorldRecipeGuard.fingerprint(values);
    }

    private static String digest(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
