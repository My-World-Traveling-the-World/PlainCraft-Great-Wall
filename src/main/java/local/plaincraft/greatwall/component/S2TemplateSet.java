package local.plaincraft.greatwall.component;

import java.util.EnumMap;
import java.util.Map;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 新世界采用配套墙和五件实物；旧配方只在旧世界重放时进入 S1 绑定。 */
public final class S2TemplateSet {
    private S2TemplateSet() {}

    public static Map<SiteKind, ComponentTemplate> create(long seed, PaletteBinding palette) {
        var templates = new EnumMap<SiteKind, ComponentTemplate>(SiteKind.class);
        // 水门、下墙坡道和断头连接件继续提供原能力，它们不是被替换的普通墙素材。
        templates.putAll(S1TemplateSet.create(seed, palette));
        templates.put(SiteKind.WALL, new ImportedRibbonSection(seed));
        var building = new ImportedBuildingTemplate(seed);
        for (var kind : new SiteKind[] {SiteKind.GATE, SiteKind.ENEMY_TOWER, SiteKind.BEACON, SiteKind.FORT})
            templates.put(kind, building);
        return Map.copyOf(templates);
    }
}
