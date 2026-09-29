/* 文件职责：材质族类型闭包。S1B 前置数据类型，S4 只补真实分类与权重。 */
package local.plaincraft.greatwall.decay;

import local.plaincraft.greatwall.model.WallTypes;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 材质族类型闭包。Variant 为三族（+红砂岩细分），权重 Q16，Mix 由 PaletteBlend 生成。
 */
public final class PaletteTypes {

    private PaletteTypes() {
    }

    public enum Variant { STONE, DEEPSLATE, SANDSTONE, RED_SANDSTONE }

    public record BiomeStation(long globalSQ8, String biomeKey, Variant variant) {
        public BiomeStation {
            if (globalSQ8 < 0) throw new IllegalArgumentException("BiomeStation.S>=0 required");
            if (biomeKey == null || !biomeKey.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("biome key required");
            }
            if (variant == null) throw new IllegalArgumentException("variant required");
        }
    }

    public record Weight(Variant variant, int weightQ16) {
        public Weight {
            if (variant == null) throw new IllegalArgumentException("variant required");
            if (weightQ16 < 0 || weightQ16 > 65536) throw new IllegalArgumentException("weight 0..65536 required");
        }
    }

    public record Mix(List<Weight> weights, String hash) {
        public Mix {
            weights = List.copyOf(weights);
            WallTypes.requireHash(hash);
            // 仅非零项且 sum==65536
            long sum = 0;
            for (Weight w : weights) {
                if (w.weightQ16() == 0) throw new IllegalArgumentException("zero weight not allowed in Mix");
                sum += w.weightQ16();
            }
            if (sum != 65536) throw new IllegalArgumentException("Mix sum must be 65536");
        }
    }

    public record WeightedState(WallTypes.BlockSpec block, int weight) {
        public WeightedState {
            if (block == null) throw new IllegalArgumentException("block required");
            if (weight <= 0) throw new IllegalArgumentException("weight>0 required");
        }
    }

    public record Table(Variant variant, WallTypes.DecayState decay, WallTypes.Part part,
            List<WeightedState> entries) {
        public Table {
            if (variant == null || decay == null || part == null) throw new IllegalArgumentException("null key required");
            entries = List.copyOf(entries);
            long sum = 0;
            for (WeightedState e : entries) {
                sum += e.weight();
            }
            if (sum != 100) throw new IllegalArgumentException("Table entries must sum to 100");
        }
    }

    public record Catalog(Map<Variant, Map<WallTypes.DecayState, Map<WallTypes.Part, Table>>> tables,
            String hash) {
        public Catalog {
            // 深复制
            var copy = new java.util.EnumMap<Variant, Map<WallTypes.DecayState, Map<WallTypes.Part, Table>>>(Variant.class);
            for (Map.Entry<Variant, Map<WallTypes.DecayState, Map<WallTypes.Part, Table>>> e : tables.entrySet()) {
                var dmap = new java.util.EnumMap<WallTypes.DecayState, Map<WallTypes.Part, Table>>(WallTypes.DecayState.class);
                for (Map.Entry<WallTypes.DecayState, Map<WallTypes.Part, Table>> de : e.getValue().entrySet()) {
                    var pmap = new java.util.EnumMap<WallTypes.Part, Table>(WallTypes.Part.class);
                    pmap.putAll(de.getValue());
                    dmap.put(de.getKey(), java.util.Collections.unmodifiableMap(pmap));
                }
                copy.put(e.getKey(), java.util.Collections.unmodifiableMap(dmap));
            }
            tables = java.util.Collections.unmodifiableMap(copy);
            WallTypes.requireHash(hash);
        }
    }

    public record Snapshot(Set<String> sandstoneBiomes, Set<String> deepslateBiomes,
            Set<String> redSandstoneBiomes, Catalog catalog, String hash) {
        public Snapshot {
            sandstoneBiomes = Set.copyOf(sandstoneBiomes);
            deepslateBiomes = Set.copyOf(deepslateBiomes);
            redSandstoneBiomes = Set.copyOf(redSandstoneBiomes);
            if (catalog == null) throw new IllegalArgumentException("catalog required");
            WallTypes.requireHash(hash);
        }
    }

    public record Validation(List<String> errors, String blocksHash, String tagsHash) {
        public Validation {
            errors = List.copyOf(errors);
            WallTypes.requireHash(blocksHash);
            WallTypes.requireHash(tagsHash);
        }
    }
}
