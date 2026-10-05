package local.plaincraft.greatwall.decay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation;
import local.plaincraft.greatwall.decay.PaletteTypes.Snapshot;
import local.plaincraft.greatwall.decay.PaletteTypes.Variant;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import local.plaincraft.greatwall.model.WallTypes.Station;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome to material-family classification. Definitions come only from the
 * live biome registry and data-pack tags: an unknown key is never guessed
 * from its name, and a change to the observed tags changes the world recipe.
 */
public final class BiomePaletteIndex {
    private static final ResourceLocation SANDSTONE_TAG =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "palette_sandstone");
    private static final ResourceLocation RED_SANDSTONE_TAG =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "palette_red_sandstone");
    private static final ResourceLocation DEEPSLATE_TAG =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "palette_deepslate");

    private final PaletteTypes.Snapshot snapshot;

    public BiomePaletteIndex(PaletteTypes.Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * Expands the three own biome tags against the live registry. Never calls
     * possibleBiomes: iteration is over holder keys and tag membership only.
     */
    public static PaletteTypes.Snapshot freeze(RegistryAccess access, PaletteTypes.Catalog catalog) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(catalog, "catalog");
        var biomes = access.registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        Set<String> sandstone = new TreeSet<>();
        Set<String> deepslate = new TreeSet<>();
        Set<String> red = new TreeSet<>();
        var sandstoneTag = TagKey.create(net.minecraft.core.registries.Registries.BIOME, SANDSTONE_TAG);
        var redTag = TagKey.create(net.minecraft.core.registries.Registries.BIOME, RED_SANDSTONE_TAG);
        var deepslateTag = TagKey.create(net.minecraft.core.registries.Registries.BIOME, DEEPSLATE_TAG);
        for (Holder<Biome> holder : biomes.holders().toList()) {
            String key = holder.unwrapKey().map(k -> k.location().toString()).orElse(null);
            if (key == null) continue;
            if (holder.is(sandstoneTag)) sandstone.add(key);
            if (holder.is(redTag)) red.add(key);
            if (holder.is(deepslateTag)) deepslate.add(key);
        }
        for (String key : red) {
            if (!sandstone.contains(key)) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE,
                        "red sandstone biome is not part of the sandstone set: " + key);
            }
        }
        StringBuilder tags = new StringBuilder();
        for (String key : sandstone) tags.append("s:").append(key).append(';');
        for (String key : deepslate) tags.append("d:").append(key).append(';');
        for (String key : red) tags.append("r:").append(key).append(';');
        String tagsHash = local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                tags.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new PaletteTypes.Snapshot(Set.copyOf(sandstone), Set.copyOf(deepslate), Set.copyOf(red),
                catalog, catalog.hash());
    }

    /** Overlap precedence: sandstone > deepslate > stone, with red as a sandstone sub-family. */
    public Variant classify(String biomeKey) {
        Objects.requireNonNull(biomeKey, "biomeKey");
        if (snapshot.redSandstoneBiomes().contains(biomeKey)
                && snapshot.sandstoneBiomes().contains(biomeKey)) {
            return Variant.RED_SANDSTONE;
        }
        if (snapshot.sandstoneBiomes().contains(biomeKey)) return Variant.SANDSTONE;
        if (snapshot.deepslateBiomes().contains(biomeKey)) return Variant.DEEPSLATE;
        return Variant.STONE;
    }

    /**
     * Builds the material ribbon for the given stations. Every supplied
     * station must carry an already-sampled biome key; a missing column is a
     * caller error rather than a reason to query the world here.
     */
    public List<BiomeStation> ribbon(List<Station> stations, Map<Long, ColumnSample> alreadySampled) {
        Objects.requireNonNull(alreadySampled, "alreadySampled");
        var biomeKeys = new java.util.HashMap<Long, String>();
        for (var entry : alreadySampled.entrySet()) biomeKeys.put(entry.getKey(), entry.getValue().biomeKey());
        return ribbonByKey(stations, biomeKeys);
    }

    /**
     * Builds the material ribbon for the given stations from an already-frozen
     * globalSQ8 -> biomeKey map. A missing column is a caller error rather than
     * a reason to query the world here.
     */
    public List<BiomeStation> ribbonByKey(List<Station> stations, Map<Long, String> biomeKeys) {
        Objects.requireNonNull(stations, "stations");
        Objects.requireNonNull(biomeKeys, "biomeKeys");
        if (stations.isEmpty()) throw new WallFailure(FailureCode.INVALID_RESOURCE, "ribbon needs stations");
        List<BiomeStation> ribbon = new ArrayList<>(stations.size());
        long previous = Long.MIN_VALUE;
        for (Station station : stations) {
            if (station.globalSQ8() < 0) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE, "negative ribbon station");
            }
            if (station.globalSQ8() <= previous) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE, "ribbon stations must strictly increase");
            }
            previous = station.globalSQ8();
            String biomeKey = biomeKeys.get(station.globalSQ8());
            if (biomeKey == null) {
                throw new WallFailure(FailureCode.T0_MISSING,
                        "ribbon station has no sampled column: " + station.globalSQ8());
            }
            ribbon.add(new BiomeStation(station.globalSQ8(), biomeKey, classify(biomeKey)));
        }
        return List.copyOf(ribbon);
    }

    public String hash() { return snapshot.hash(); }

    public PaletteTypes.Snapshot snapshot() { return snapshot; }

    /** Seam-consistency check used by the scheduler before publishing a recipe. */
    public static boolean seamsAgree(List<BiomeStation> left, List<BiomeStation> right) {
        Map<Long, Variant> seen = new HashMap<>();
        for (BiomeStation station : concat(left, right)) {
            Variant previous = seen.putIfAbsent(station.globalSQ8(), station.variant());
            if (previous != null && previous != station.variant()) return false;
        }
        return true;
    }

    private static List<BiomeStation> concat(List<BiomeStation> a, List<BiomeStation> b) {
        List<BiomeStation> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        all.sort(Comparator.comparingLong(BiomeStation::globalSQ8));
        return all;
    }

    static Set<ResourceLocation> tagIds() {
        Set<ResourceLocation> ids = new HashSet<>();
        ids.add(SANDSTONE_TAG);
        ids.add(RED_SANDSTONE_TAG);
        ids.add(DEEPSLATE_TAG);
        return ids;
    }
}
