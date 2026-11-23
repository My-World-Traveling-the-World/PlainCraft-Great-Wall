package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.Locale;
import java.util.TreeMap;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.GenerationConfig;

/** 新世界选新版，已有凭证继续认旧版；版本变化不改存档字段，也不替旧世界升级构件。 */
final class ProductionRecipeVersions {
    private ProductionRecipeVersions() {}

    static GenerationConfig newWorld() {
        return withTemplates(WallRecipeRules.INTACT);
    }

    /** 仍承认已经发布的两代凭证，但它们只用于打开对应旧世界。 */
    static java.util.List<GenerationConfig> readable() {
        return java.util.List.of(WorldBootstrap.defaultConfig(), withTemplates(WallRecipeRules.AUTHORED));
    }

    private static GenerationConfig withTemplates(int version) {
        var old = WorldBootstrap.defaultConfig();
        return new GenerationConfig(old.algorithmVersion(), version, old.segmentBlocks(), old.stationBlocks(), old.columnBudget(),
                old.carrierAmplitude(), old.corridorHalfWidth(), old.maxGeometryInflight(), old.segmentBytes(),
                old.terrainBytes(), old.compiledBytes(), old.parametersHash());
    }

    static String templatesHash(int version) throws IOException {
        if (version != 1 && !WallRecipeRules.usesAuthoredTemplates(version)) {
            throw new IOException("不支持的长城构件配方版本: " + version);
        }
        var fields = new TreeMap<String, JsonElement>();
        fields.put("templateVersion", new JsonPrimitive(version));
        // v1/v2 摘要字节不变；v3 复用同一套实物，版本号区分不同的成品规则。
        if (WallRecipeRules.usesAuthoredTemplates(version)) {
            for (var asset : Asset.values()) {
                String name = asset.name().toLowerCase(Locale.ROOT);
                fields.put(name, new JsonPrimitive(resourceHash("/data/plaincraft_great_wall/wall_templates/v2/" + name + ".nbt")));
            }
            fields.put("paired-wall", new JsonPrimitive(resourceHash("/assets/plaincraft-great-wall/templates/wall_module.csv")));
        }
        return WorldRecipeGuard.fingerprint(fields);
    }

    private static String resourceHash(String name) throws IOException {
        try (var in = ProductionRecipeVersions.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("正式构件缺少资源: " + name);
            return DeterministicHash.sha256(in.readAllBytes());
        }
    }
}
