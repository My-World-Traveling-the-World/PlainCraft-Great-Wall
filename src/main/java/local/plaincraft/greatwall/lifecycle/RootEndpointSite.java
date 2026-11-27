package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 有限墙由中点还原端口，无限墙直接用起点；验地和投票必须与施工共用同一端口和旋转。 */
final class RootEndpointSite {
    private static final SiteId FORT = new SiteId("root-screen", SiteKind.FORT, 0);
    final Axis midpoint;
    private final Point3i port; private int portGround = Integer.MIN_VALUE;
    final Bounds template;
    final int radius, angle, direction;
    final long score;
    private int examined, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;

    RootEndpointSite(Axis midpoint, long length, int radius, int angle, int direction, long score) {
        this.midpoint = midpoint; this.radius = radius; this.angle = angle; this.direction = direction; this.score = score;
        var start = WallRecipeRules.centerFiniteAxis(midpoint, length, WallRecipeRules.INTACT);
        var p = start.origin();
        port = p;
        // 施工按首两个 Q8 顶点取切线；贴近 45 度时不能直接比较原始 Q30 而旋转到另一面。
        var dx = FixedMath.mulDiv(256, start.exQ30(), 1L << 30);
        var dz = FixedMath.mulDiv(256, start.ezQ30(), 1L << 30);
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        var frame = new Frame(new Point2q(256L * p.x(), 256L * p.z()),
                alongX ? Integer.signum((int)dx) * (1 << 30) : 0,
                alongX ? 0 : Integer.signum((int)dz) * (1 << 30), 0, false);
        template = ImportedSiteGeometry.layout(FORT, frame).boundsWithStraightWalls(0);
    }

    static boolean allowed(Axis axis, long length, Point3i spawn, List<ChunkKey> early, DimensionBounds bounds,
            local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance sanctuary) {
        if (crossesSanctuary(axis, length, sanctuary)) return false;
        if (length > 0) return canOccupy(axis, length, early, bounds);
        var origin = axis.origin();
        // 无限墙的端楼在候选本身，墙身向远离出生点的一侧无限延长。
        long dx = (long)origin.x()-spawn.x(), dz = (long)origin.z()-spawn.z();
        return Math.max(Math.abs((long)origin.x()), Math.abs((long)origin.z())) + 146 <= bounds.horizontalLimit()
                && dx*axis.exQ30() + dz*axis.ezQ30() > 0 && SpawnDomain.disjointFuture(axis, early);
    }

    static boolean canOccupy(Axis midpoint, long length, List<ChunkKey> early, DimensionBounds bounds) {
        if (early.isEmpty()) return false;
        var start = WallRecipeRules.centerFiniteAxis(midpoint, length, WallRecipeRules.INTACT);
        long x = start.origin().x(), z = start.origin().z();
        long endX = x + FixedMath.mulDiv(length, midpoint.exQ30(), 1L << 30);
        long endZ = z + FixedMath.mulDiv(length, midpoint.ezQ30(), 1L << 30);
        // 路线向侧面至多偏 512 格，外加模板/植被/安全间距。非整段终点不能当成实际末端楼址。
        int margin = 512 + 146;
        if (Math.max(Math.max(Math.abs(x), Math.abs(endX)), Math.max(Math.abs(z), Math.abs(endZ))) + margin
                > bounds.horizontalLimit()) return false;
        double ex = midpoint.exQ30() / (double)(1L << 30), ez = midpoint.ezQ30() / (double)(1L << 30);
        for (var chunk : early) {
            if (!chunk.dimension().equals("minecraft:overworld")) throw new IllegalArgumentException("非主世界出生区");
            // 区块中心至任一方块不超过 11 格，在两个投影方向上都覆盖整个区块。
            double dx = chunk.x() * 16L + 7.5 - x, dz = chunk.z() * 16L + 7.5 - z;
            double along = dx * ex + dz * ez, across = dz * ex - dx * ez;
            if (along >= -157 && along <= length + 157 && Math.abs(across) <= margin + 11) return false;
        }
        return true;
    }

    static boolean crossesSanctuary(Axis midpoint, long length, local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance area) {
        if (!area.active()) return false;
        var start = WallRecipeRules.centerFiniteAxis(midpoint, length, WallRecipeRules.INTACT).origin();
        double vx = FixedMath.mulDiv(length, midpoint.exQ30(), 1L << 30);
        double vz = FixedMath.mulDiv(length, midpoint.ezQ30(), 1L << 30);
        double dx = area.centerX() - start.x(), dz = area.centerZ() - start.z();
        // 圣域原有的游走、廊宽和安全余量不变；端点外只需保护端部，不该把延长线也当成城墙。
        // 一格长的正向对角线取整后端点可重合；无限模式则沿出墙方向保留整条射线。
        if (length == 0) { vx = midpoint.exQ30(); vz = midpoint.ezQ30(); }
        double squared = vx*vx + vz*vz;
        double t = squared == 0 ? 0 : Math.clamp((dx * vx + dz * vz) / squared, 0,
                length == 0 ? Double.POSITIVE_INFINITY : 1);
        double x = dx - t * vx, z = dz - t * vz;
        double minimum = area.radiusBlocks() + 384.0 + 512 + 64;
        return x*x + z*z <= minimum*minimum;
    }

    int size() { return (template.maxXExclusive()-template.minX())*(template.maxZExclusive()-template.minZ()); }
    int examined() { return examined; }
    RootSearch.ColumnKey column(int i) {
        // 先散看整幅岸线以尽早排除进水的楼址；随后每一列都恰好检查一次。
        int n = size(), width = template.maxXExclusive()-template.minX();
        // 257 与楼底 93×91 互质；步幅同时跨越横纵坐标，拒绝窄海岸时无需先扫完一侧。
        int k = (int)((long)i * 257 % n);
        // 此时只读取原始地形；模板实际下填的承重面，必须等冻结的施工前方块状态。
        return new RootSearch.ColumnKey(template.minX()+k%width, template.minZ()+k/width,false);
    }

    Axis selectedMidpoint() {
        if (examined != size() || portGround == Integer.MIN_VALUE) throw new IllegalStateException("端口地表尚未取得");
        // XZ 仍为主轴中点；配方平移半长后会保留这里的 Y，因此取的是实际端口列的地面高度。
        return new Axis(new Point3i(midpoint.origin().x(),portGround,midpoint.origin().z()),midpoint.exQ30(),midpoint.ezQ30());
    }

    String record(ColumnSample value, DimensionBounds bounds) {
        if (value.upperCensored() || value.quality()!=Quality.REFERENCE)
            return "REJECTED_TERRAIN_UNVERIFIED";
        if (value.waterTopY().isPresent()) return "REJECTED_WATER";
        var key = column(examined);
        if (key.x() == port.x() && key.z() == port.z()) portGround=value.groundTopY();
        low = Math.min(low, value.groundTopY()); high = Math.max(high, value.groundTopY()); examined++;
        if (high + 15L + template.maxYExclusive() > bounds.maxYExclusive() || low < bounds.minY())
            return "REJECTED_WORLD_HEIGHT";
        return null;
    }

    Bounds protection(int margin) {
        var b = protection();
        // 只扩水平避让距离，仍使地面与屋顶取得的高带有效，不误拒无关的悬空和地下结构。
        return new Bounds(b.minX()-margin,b.minY(),b.minZ()-margin,b.maxXExclusive()+margin,
                b.maxYExclusive(),b.maxZExclusive()+margin);
    }

    Bounds protection() {
        if (examined != size()) throw new IllegalStateException("端楼地基尚未验完");
        // 地基从各列地面往上补，塔楼从最高地面起算；低处碰撞同样必须进入结构投票。
        return new Bounds(template.minX(),low,template.minZ(),template.maxXExclusive(),
                Math.addExact(high,15+template.maxYExclusive()),template.maxZExclusive());
    }
}
