package local.plaincraft.greatwall.component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallTypes.*;

final class TemplateFixtures {
    static final String HASH = "a".repeat(64);
    static final PaletteBinding PALETTE = (input, part, local, world, s, hash) -> {
        String base = switch (input.family()) {
            case STONE -> "stone_brick"; case DEEPSLATE -> "deepslate_brick";
            case SANDSTONE -> input.redSandstone() ? "red_sandstone" : "sandstone";
        };
        String solid = switch (input.family()) {
            case STONE -> "stone_bricks"; case DEEPSLATE -> "deepslate_bricks";
            case SANDSTONE -> input.redSandstone() ? "cut_red_sandstone" : "cut_sandstone";
        };
        String id = switch (part) {
            case FLOOR, SLAB -> base + "_slab"; case STAIR -> base + "_stairs";
            case CAMPFIRE -> "campfire"; case HAY -> "hay_block"; case FENCE -> "iron_bars";
            case LAMP -> "lantern"; case CHAIN -> "chain"; case CHEST -> "chest"; case SPAWNER -> "spawner";
            case AIR -> "air"; case WATER -> "water"; default -> solid;
        };
        return new BlockSpec("minecraft:" + id, Map.of());
    };

    static ComponentInput input(SiteKind kind, Bounds bounds) { return input(kind,bounds,0,0,0,PaletteFamily.STONE,DecayState.INTACT,0); }
    static ComponentInput input(SiteKind kind, Bounds bounds, int x, int z, int angle, PaletteFamily family, DecayState state, int drop) {
        double radians = angle * StrictMath.PI / 180;
        var frame = new Frame(new Point2q((long) x*256,(long) z*256),
                (int) StrictMath.round(StrictMath.cos(radians)*(1L<<30)), (int) StrictMath.round(StrictMath.sin(radians)*(1L<<30)),84,false);
        var worldBounds = Rasterizer.worldBounds(frame,bounds);
        var site = new SiteRecord(new SiteId("fixture",kind,kind == SiteKind.WALL ? 5 : 1),320L*256,frame,worldBounds,
                SiteState.PLANNED,Optional.empty(),Optional.empty());
        var supports = new HashMap<Point3i,ProbeResult>();
        for (int zz = worldBounds.minZ(); zz < worldBounds.maxZExclusive(); zz++)
            for (int xx = worldBounds.minX(); xx < worldBounds.maxXExclusive(); xx++)
                supports.put(new Point3i(xx,84,zz),new ProbeResult(drop <= 12 ? ProbeKind.SUPPORTED : ProbeKind.NO_SUPPORT,
                        64-drop,OptionalInt.empty(),OptionalInt.empty(),drop,HASH));
        return new ComponentInput(site,List.of(new Station(0,Projection.local(frame,-320L*256,0),84,0),
                new Station(640L*256,Projection.local(frame,320L*256,0),84,0)),supports,
                new DecaySample(state,state == DecayState.INTACT ? 0 : state == DecayState.PARTIAL ? 25000 : 55000,
                        state == DecayState.INTACT ? 20 : state == DecayState.PARTIAL ? 16 : 2),
                family,false,List.of(),List.of());
    }
}
