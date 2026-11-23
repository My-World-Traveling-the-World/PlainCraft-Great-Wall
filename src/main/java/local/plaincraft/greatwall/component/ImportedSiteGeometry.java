package local.plaincraft.greatwall.component;

import java.util.List;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.world.level.block.Rotation;

/** 冻结建筑的整数锚点和接墙范围；规划、取证与落块必须认同一组实物端面。 */
public final class ImportedSiteGeometry {
    public static final int STRAIGHT = 24;
    private static final int TRANSITION = 64;

    private ImportedSiteGeometry() {}

    public static boolean enabled(WorldRecipe recipe) {
        return recipe != null && local.plaincraft.greatwall.model.WallRecipeRules.usesAuthoredTemplates(recipe.config().templateVersion());
    }

    public static Asset asset(SiteId id) {
        return switch (id.kind()) {
            case GATE -> Asset.GATE;
            case ENEMY_TOWER -> Asset.ENEMY_TOWER;
            case BEACON -> Asset.BEACON;
            // 澄海楼只有一个接头，只能作起点；后续堡垒站用两头成直角的角楼。
            case FORT -> id.ordinal() == 0 ? Asset.CHENGHAI_TOWER : Asset.CORNER_TOWER;
            default -> throw new IllegalArgumentException("不是实物建筑站点: " + id);
        };
    }

    public static int extent(SiteId id) {
        return switch (asset(id)) {
            case GATE -> 56;
            case ENEMY_TOWER -> 35;
            case BEACON -> 25;
            case CORNER_TOWER -> 24;
            case CHENGHAI_TOWER -> 0;
        };
    }

    public static int reach(SiteId id) { return extent(id) + STRAIGHT + TRANSITION; }

    public static Frame cardinal(Frame frame) {
        // 建筑固定正南北/正东西是用户确定的生成规则，不能继续用路线任意切线拉伸模板。
        boolean x = Math.abs((long) frame.txQ30()) >= Math.abs((long) frame.tzQ30());
        int tx = x ? Integer.signum(frame.txQ30()) * (1 << 30) : 0;
        int tz = x ? 0 : Integer.signum(frame.tzQ30()) * (1 << 30);
        return new Frame(center(FixedMath.floorBlock(frame.center().xQ8()), FixedMath.floorBlock(frame.center().zQ8())),
                tx, tz, frame.walkY(), frame.mirror());
    }

    public static ImportedWallLayout layout(SiteRecord site) {
        return layout(site.id(), site.frame());
    }

    public static ImportedWallLayout layout(SiteId id, Frame f) {
        if ((Math.abs((long) f.txQ30()) != 1L << 30 || f.tzQ30() != 0)
                && (Math.abs((long) f.tzQ30()) != 1L << 30 || f.txQ30() != 0)) {
            throw new IllegalArgumentException("实物建筑必须固定在正交轴向: " + id);
        }
        var asset = asset(id);
        Rotation rotation = f.txQ30() > 0 ? Rotation.NONE : f.tzQ30() > 0 ? Rotation.CLOCKWISE_90
                : f.txQ30() < 0 ? Rotation.CLOCKWISE_180 : Rotation.COUNTERCLOCKWISE_90;
        // 起点楼的唯一接口原本朝北，转到路线的出发方向；其余模板按局部 +X 对齐路线。
        if (asset == Asset.CHENGHAI_TOWER) rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        var anchor = switch (asset) {
            case GATE -> ImportedWallLayout.GATE_ANCHOR;
            case ENEMY_TOWER -> new Point3i(35, 15, 13);
            case BEACON -> new Point3i(25, 15, 5);
            case CORNER_TOWER -> new Point3i(15, 15, 15);
            case CHENGHAI_TOWER -> new Point3i(46, 15, 0);
        };
        var feet = new Point3i(FixedMath.floorBlock(f.center().xQ8()), f.walkY(), FixedMath.floorBlock(f.center().zQ8()));
        var local = asset == Asset.GATE ? anchor : ImportedWallPorts.of(asset).getFirst().feet();
        return new ImportedWallLayout(asset, ImportedWallPorts.place(local, anchor, feet, rotation), rotation);
    }

    public static Bounds bounds(SiteRecord site) { return layout(site).boundsWithStraightWalls(0); }

    public static long portArc(SiteRecord site, int port) {
        return site.globalSQ8() + (port == 0 && asset(site.id()) != Asset.CHENGHAI_TOWER ? -1L : 1L)
                * extent(site.id()) * 256;
    }

    public static List<Long> knots(SiteRecord site) {
        long s = site.globalSQ8(), e = extent(site.id()) * 256L;
        long straight = STRAIGHT * 256L, reach = reach(site.id()) * 256L;
        return List.of(s - reach, s - e - straight, s - e, s, s + e, s + e + straight, s + reach);
    }

    public static boolean levelAt(SiteRecord site, long s) {
        return Math.abs(s - site.globalSQ8()) <= (extent(site.id()) + STRAIGHT) * 256L;
    }

    public static Point2q routePoint(Point2q original, long s, List<SiteRecord> sites) {
        for (var site : sites) {
            if (site.state() != SiteState.PLANNED) continue;
            long delta = s - site.globalSQ8(), distance = Math.abs(delta);
            int extent = extent(site.id()), reach = reach(site.id());
            if (distance >= reach * 256L) continue;
            var ports = layout(site).ports();
            if (ports.size() == 1 && delta < 0) continue;
            Point2q guide;
            if (ports.size() == 2 && distance < extent * 256L) {
                // 楼体内部不铺普通墙，但站表保持连续，供所有权投影及两侧高度合同使用。
                var a = ports.getFirst().feet(); var b = ports.getLast().feet();
                guide = new Point2q((long) a.x() * 256 + 128
                        + FixedMath.mulDiv((long) (b.x() - a.x()) * 256, delta + extent * 256L, 2L * extent * 256),
                        (long) a.z() * 256 + 128
                        + FixedMath.mulDiv((long) (b.z() - a.z()) * 256, delta + extent * 256L, 2L * extent * 256));
            } else {
                var port = delta < 0 ? ports.getFirst() : ports.getLast();
                long stepQ8 = distance - extent * 256L;
                guide = new Point2q((long) port.feet().x() * 256 + 128 + stepQ8 * port.outward().getStepX(),
                        (long) port.feet().z() * 256 + 128 + stepQ8 * port.outward().getStepZ());
            }
            long outside = Math.max(0, distance - (extent + STRAIGHT) * 256L);
            // 在建筑外平滑回到原路线，端面旁的 24 格始终是正交直线，不让楼体歪转。
            int weight = 65536 - FixedMath.smoothQ16((int) Math.min(65536, outside * 65536 / (TRANSITION * 256L)));
            return new Point2q(FixedMath.lerp(original.xQ8(), guide.xQ8(), weight),
                    FixedMath.lerp(original.zQ8(), guide.zQ8(), weight));
        }
        return original;
    }

    private static Point2q center(int x, int z) { return new Point2q((long) x * 256 + 128, (long) z * 256 + 128); }
}
