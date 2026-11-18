package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.ComponentVoxels;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import net.minecraft.world.level.block.Rotation;

/** 同一整数变换放置建筑、端面与保护范围，避免预告的占地和实际施工错一格。 */
public record ImportedWallLayout(Asset asset, Point3i feet, Rotation rotation) {
    public ImportedWallLayout {
        Objects.requireNonNull(asset);
        Objects.requireNonNull(feet);
        Objects.requireNonNull(rotation);
    }

    static final Point3i GATE_ANCHOR = new Point3i(56, 15, 19);
    static final Point3i GATE_SIZE = new Point3i(113, 42, 32);

    public Point3i localAnchor() {
        // 门楼用贯穿门洞的中心作锚；其他楼暂按素材第一个端面定位。
        return asset == Asset.GATE ? GATE_ANCHOR : ImportedWallPorts.of(asset).getFirst().feet();
    }

    public Point3i place(Point3i local) {
        return ImportedWallPorts.place(local, localAnchor(), feet, rotation);
    }

    public List<ImportedWallPorts.Port> ports() {
        var anchor = localAnchor();
        return ImportedWallPorts.of(asset).stream().map(port -> port.place(anchor, feet, rotation)).toList();
    }

    public Bounds boundsWithStraightWalls(int length) {
        if (length < 0 || length > 48) throw new IllegalArgumentException("接墙直段长度须在 0..48 格");
        var size = size();
        var a = place(new Point3i(0, 0, 0));
        var b = place(new Point3i(size.x() - 1, size.y() - 1, size.z() - 1));
        int minX = Math.min(a.x(), b.x()), maxX = Math.max(a.x(), b.x());
        int minZ = Math.min(a.z(), b.z()), maxZ = Math.max(a.z(), b.z());
        int minY = a.y(), maxY = b.y();
        // 四分之一圈旋转的两对角可定包络；端面内外两缘同样用真实侧向，不用整楼边缘代替。
        if (length > 0) for (var port : ports()) {
            for (int across : new int[] {-PairedWallProfile.HALF, PairedWallProfile.HALF}) {
                var p = port.cell(length, across, 0);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
                minY = Math.min(minY, p.y() - PairedWallProfile.FEET);
                maxY = Math.max(maxY, p.y() + PairedWallProfile.HEIGHT - PairedWallProfile.FEET - 1);
            }
        }
        return new Bounds(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public ComponentVoxels compileStraight(SiteId site, long seed, int length) {
        return ImportedWallSeam.attach(compileBody(site, seed), ports(), length);
    }

    private ComponentVoxels compileBody(SiteId site, long seed) {
        if (!size().equals(asset.template().size())) {
            throw new IllegalStateException("实物模板尺寸与预告的占地不符: " + asset);
        }
        return asset == Asset.GATE ? ImportedGateBody.compile(site, feet, rotation, seed)
                : ImportedTowerBody.compile(asset, site, localAnchor(), feet, rotation, seed, null, null);
    }

    private Point3i size() {
        // 预告占地只用元数据，不在服务端启动线程展开五座楼的 NBT。
        return switch (asset) {
            case GATE -> GATE_SIZE;
            case ENEMY_TOWER -> new Point3i(71, 39, 27);
            case BEACON -> new Point3i(51, 26, 11);
            case CORNER_TOWER -> new Point3i(32, 28, 30);
            case CHENGHAI_TOWER -> new Point3i(93, 58, 91);
        };
    }
}
