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

/** 正式入口使用现行配方；旧模板摘要只保留给离线历史夹具。 */
final class ProductionRecipeVersions {
    private ProductionRecipeVersions() {}

    static GenerationConfig newWorld() {
        return withVersions(WallRecipeRules.LAND_FIRST_PLANNING, WallRecipeRules.INTACT);
    }

    private static GenerationConfig withVersions(int algorithm, int version) {
        var old = WorldBootstrap.defaultConfig();
        return new GenerationConfig(algorithm, version, old.segmentBlocks(), old.stationBlocks(), old.columnBudget(),
                old.carrierAmplitude(), old.corridorHalfWidth(), old.maxGeometryInflight(), old.segmentBytes(),
                old.terrainBytes(), old.compiledBytes(),
                local.plaincraft.greatwall.config.GreatWallConfig.parameters().landFirstDigest());
    }

    /** 保留历史查找键；同一套模板升级规划后不能捡到旧主轴。 */
    static String rootAxisParameters(GenerationConfig config) {
        return config.algorithmVersion() == 1 ? config.parametersHash()
                : DeterministicHash.sha256(("root-axis-planning:" + config.algorithmVersion() + ":" + config.parametersHash())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
