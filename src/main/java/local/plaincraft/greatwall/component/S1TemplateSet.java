package local.plaincraft.greatwall.component;

import java.util.Map;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;

/** S1's frozen base binding, promoted unchanged from the three-family component fixtures. Not S4 weights. */
public final class S1TemplateSet {
    private S1TemplateSet() {}
    public static Map<SiteKind, ComponentTemplate> create(long seed) {
        var decay = new DecayField(seed);
        PaletteBinding palette = S1TemplateSet::resolve;
        return Map.of(SiteKind.WALL, new WallSection(decay, palette), SiteKind.ENEMY_TOWER, new EnemyTower(decay, palette),
                SiteKind.BEACON, new BeaconTower(decay, palette), SiteKind.GATE, new Gate(decay, palette),
                SiteKind.WATER_GATE, new WaterGate(decay, palette), SiteKind.END_CAP, new EndCap(decay, palette),
                SiteKind.RAMP, new Ramp(decay, palette), SiteKind.FORT, new FortPlaceholder(decay, palette));
    }
    public static BlockSpec material(PaletteFamily family, boolean red, Part part) {
        String base = switch (family) {
            case STONE -> "stone_brick"; case DEEPSLATE -> "deepslate_brick";
            case SANDSTONE -> red ? "red_sandstone" : "sandstone";
        };
        String solid = switch (family) {
            case STONE -> "stone_bricks"; case DEEPSLATE -> "deepslate_bricks";
            case SANDSTONE -> red ? "cut_red_sandstone" : "cut_sandstone";
        };
        String id = switch (part) {
            case FLOOR, SLAB -> base + "_slab"; case STAIR -> base + "_stairs";
            case CAMPFIRE -> "campfire"; case HAY -> "hay_block"; case FENCE -> "iron_bars";
            case LAMP -> "lantern"; case CHAIN -> "chain"; case CHEST -> "chest"; case SPAWNER -> "spawner";
            case AIR -> "air"; case WATER -> "water"; default -> solid;
        };
        return new BlockSpec("minecraft:" + id, Map.of());
    }
    private static BlockSpec resolve(ComponentInput input, Part part, Point3i local, Point3i world, long s, long hash) {
        return material(input.family(), input.redSandstone(), part);
    }
}
