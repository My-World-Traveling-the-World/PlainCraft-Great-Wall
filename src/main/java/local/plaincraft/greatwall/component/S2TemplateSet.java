package local.plaincraft.greatwall.component;

import java.util.EnumMap;
import java.util.Map;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 新世界采用配套墙和五件实物；保留历史夹具对旧构件的绑定。 */
public final class S2TemplateSet {
    private S2TemplateSet() {}

    public static Map<SiteKind, ComponentTemplate> create(long seed, PaletteBinding palette, int version) {
        var templates = new EnumMap<SiteKind, ComponentTemplate>(SiteKind.class);
        // 水门、下墙坡道和断头连接件继续提供原能力，它们不是被替换的普通墙素材。
        templates.putAll(S1TemplateSet.create(seed, palette));
        // 水门、收口与坡道也应使用当前配方；旧夹具维持原有方块结果。
        if (!WallRecipeRules.weathers(version)) {
            var decay = new DecayField(seed, version);
            templates.put(SiteKind.WATER_GATE, new WaterGate(decay, palette));
            templates.put(SiteKind.END_CAP, new EndCap(decay, palette));
            templates.put(SiteKind.RAMP, new Ramp(decay, palette));
        }
        templates.put(SiteKind.WALL, new ImportedRibbonSection(seed));
        var building = new ImportedBuildingTemplate(seed);
        for (var kind : new SiteKind[] {SiteKind.GATE, SiteKind.ENEMY_TOWER, SiteKind.BEACON, SiteKind.FORT})
            templates.put(kind, building);
        return Map.copyOf(templates);
    }
}
