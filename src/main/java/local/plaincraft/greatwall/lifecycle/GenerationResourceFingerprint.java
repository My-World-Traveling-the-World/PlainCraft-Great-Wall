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
                "tags/block", "plaincraft_great_wall",
                // Lithostitched's own registries live outside the vanilla namespace. A changed
                // biome injector, region or worldgen modifier alters which biomes a world can even
                // contain, so it must be part of the frozen inputs; hashing only the built jar
                // would miss a user datapack overriding them.
                "lithostitched"}) {
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

    /**
     * Frozen semantic inputs only. Optional code is not linked, and the biome source's identity set
     * is never enumerated; see the frozen red line recorded in docs/S1-S5实施规格/README.md.
     */
    public static Map<String, JsonElement> captureProduction(ServerLevel level, ResourceManager resources) throws IOException {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("production inputs freeze on server owner");
        var inputs = new TreeMap<>(capture(level, resources));
        inputs.remove("probeAlgorithm");
        inputs.put("wallAlgorithm", new JsonPrimitive(1));
        inputs.put("rootAlgorithm", new JsonPrimitive(2));
        inputs.put("rootStructureScreen",new JsonPrimitive("pre-recipe-native-references-v1"));
        inputs.put("naturalReplacementPolicy",new JsonPrimitive("explicit-ores-gravel-v1"));
        inputs.put("rootFoundationPolicy",new JsonPrimitive("surface-h-minus-1-2-3-v1"));
        inputs.put("groundEntrancePolicy",new JsonPrimitive("shared-level-floor-exterior-width-headroom-v1"));
        // Dedicated biome identity policy. Changing any of these changes which columns receive the
        // Great Wall biome, so they are part of the recipe rather than incidental runtime state.
        inputs.put("biomePolicy",new JsonPrimitive("route-union-hard-domain-thinY-v1"));
        inputs.put("biomeBandLow",new JsonPrimitive("[8,32)"));
        inputs.put("biomeBandEdge",new JsonPrimitive("[32,64)"));
        inputs.put("biomePaintBand",new JsonPrimitive("[-8,+32)"));
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
        java.util.List<String> outsideWorldgen = new java.util.ArrayList<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            // Version is part of the recipe: a worldgen mod that bumps its version must invalidate old
            // worlds so the new version's generation is never mixed with a saved recipe. The per-mod
            // source byte SHA is deliberately NOT frozen here - it changes on every recompile even when
            // the generation logic is untouched (startup ordering, deadlock, far-chunk fixes), which
            // would needlessly force a new world for a client that only wants to reuse a save across
            // such non-generation changes. A real generation change must bump the mod version.
            //
            // Only mods that can change terrain are frozen. Measured: with all 767 pack mods in the
            // digest, updating any unrelated mod (a UI tweak, a road fix) threw away the root search,
            // the structure scan, the fine-sample column cache and every segment plan - the whole
            // startup chain had to be re-walked, tens of minutes, for a change that cannot move a
            // single block. The ids kept are the pack's worldgen surface plus anything whose id or
            // name says world generation; the rest are logged so the list stays auditable.
            String name = mod.getMetadata().getName();
            if (affectsWorldgen(id, name)) {
                inputs.put("actual-mod:" + id, new JsonPrimitive(mod.getMetadata().getVersion().getFriendlyString()));
            } else {
                outsideWorldgen.add(id);
            }
        }
        if (!outsideWorldgen.isEmpty()) {
            java.util.Collections.sort(outsideWorldgen);
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:fingerprint] {} mod(s) stay out of the terrain digest (they cannot change "
                            + "terrain unless their version is added to WORLDGEN_MOD_IDS): {}",
                    outsideWorldgen.size(), String.join(", ", outsideWorldgen));
        }
        Path c2me = FabricLoader.getInstance().getConfigDir().resolve("c2me.toml");
        if (FabricLoader.getInstance().isModLoaded("c2me") && Files.isRegularFile(c2me)) {
            inputs.put("config:c2me-sha256", new JsonPrimitive(digest(c2me)));
        }
        return Map.copyOf(inputs);
    }

    /**
     * Mod ids whose version must stay in the terrain digest. A mod that is not listed here can, in
     * principle, still change terrain through a mixin: the keyword net below catches the ids that say
     * so, and one that is missed only costs a stale sample until its version is added here. That is the
     * direction this guard trades in - a wall built from stale samples is visible and repairable by
     * regenerating, while re-walking the entire startup chain on every unrelated mod update is not.
     */
    private static final java.util.Set<String> WORLDGEN_MOD_IDS = java.util.Set.of(
            "minecraft", "c2me", "c2me-base", "c2me-opts", "c2me-opts-accel-opencl", "c2me-opts-allocs",
            "tectonic", "lithostitched", "biomeswevegone", "biomesoplenty", "towns_and_towers", "ctov",
            "terralith", "streamsreflowing", "tanshugetrees", "plaincraft_tanshugetrees_worldgen_fix",
            "plaincraft-height-6000-patch", "plaincraft_erdtree_sanctuary", "plaincraft_great_wall",
            "roadweaver", "fabric-api");

    /** Keyword net for worldgen-surface mods whose exact id is not in the set above. */
    private static final String[] WORLDGEN_HINTS = {
            "worldgen", "world-gen", "world_gen", "terrain", "biome", "noise", "chunk", "structure",
            "height", "cave", "river", "tree", "tectonic", "lithostitched", "c2me", "tanshugetrees",
            "erdtree", "streams", "roadweaver", "greatwall", "great-wall", "great_wall"};

    private static boolean affectsWorldgen(String id, String name) {
        if (WORLDGEN_MOD_IDS.contains(id)) return true;
        String lowerId = id.toLowerCase(java.util.Locale.ROOT);
        String lowerName = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        for (String hint : WORLDGEN_HINTS) {
            if (lowerId.contains(hint) || lowerName.contains(hint)) return true;
        }
        return false;
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
