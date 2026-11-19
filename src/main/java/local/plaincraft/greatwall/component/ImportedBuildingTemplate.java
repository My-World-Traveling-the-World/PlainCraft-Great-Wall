package local.plaincraft.greatwall.component;

import local.plaincraft.greatwall.model.WallTypes.*;

/** 正常世界只编译实物楼体；接引墙由冻结站表统一施工，不能再叠一份展台直墙。 */
public final class ImportedBuildingTemplate implements ExactFootprintTemplate {
    private final long seed;

    public ImportedBuildingTemplate(long seed) { this.seed = seed; }

    @Override public Bounds localBounds(ComponentInput input) {
        var b = worldBounds(input);
        var frame = input.site().frame();
        int x = local.plaincraft.greatwall.geometry.FixedMath.floorBlock(frame.center().xQ8());
        int z = local.plaincraft.greatwall.geometry.FixedMath.floorBlock(frame.center().zQ8());
        int radius = Math.max(Math.max(x - b.minX(), b.maxXExclusive() - x), Math.max(z - b.minZ(), b.maxZExclusive() - z));
        return new Bounds(-radius, b.minY() - frame.walkY(), -radius, radius + 1, b.maxYExclusive() - frame.walkY(), radius + 1);
    }

    @Override public Bounds worldBounds(ComponentInput input) { return ImportedSiteGeometry.bounds(input.site()); }

    @Override public ComponentVoxels compile(ComponentInput input) {
        var layout = ImportedSiteGeometry.layout(input.site());
        var evidence = input.entranceEvidence();
        if (evidence.source() != GroundEntranceContract.Source.DURABLE_T0) {
            throw new IllegalArgumentException("正式建筑必须使用已冻结的施工前地表");
        }
        var body = layout.asset() == ImportedWallTemplate.Asset.GATE
                ? ImportedGateBody.compileOnTerrain(input.site().id(), layout.feet(), layout.rotation(), seed,
                        evidence.bounds(), evidence.columns()::get)
                : ImportedTowerBody.compile(layout.asset(), input.site().id(), layout.localAnchor(), layout.feet(),
                        layout.rotation(), seed, evidence.bounds(), evidence.columns()::get);
        return ImportedBuildingGameplay.attach(body);
    }
}
