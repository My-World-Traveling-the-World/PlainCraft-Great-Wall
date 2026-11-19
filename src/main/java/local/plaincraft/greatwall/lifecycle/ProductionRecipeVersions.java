package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.Locale;
import java.util.TreeMap;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.GenerationConfig;

/** 新世界选新版，已有凭证继续认旧版；版本变化不改存档字段，也不替旧世界升级构件。 */
final class ProductionRecipeVersions {
    private ProductionRecipeVersions() {}

    static GenerationConfig newWorld() {
        var old = WorldBootstrap.defaultConfig();
        return new GenerationConfig(old.algorithmVersion(), 2, old.segmentBlocks(), old.stationBlocks(), old.columnBudget(),
                old.carrierAmplitude(), old.corridorHalfWidth(), old.maxGeometryInflight(), old.segmentBytes(),
                old.terrainBytes(), old.compiledBytes(), old.parametersHash());
    }

    static String templatesHash(int version) throws IOException {
        if (version != 1 && version != 2) throw new IOException("不支持的长城构件配方版本: " + version);
        var fields = new TreeMap<String, JsonElement>();
        fields.put("templateVersion", new JsonPrimitive(version));
        // v1 保持原摘要字节口径；v2 将实物资源也冻结，防止悄悄换素材后续建出另一套楼。
        if (version == 2) {
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
