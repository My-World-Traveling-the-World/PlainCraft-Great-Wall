package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.Direction;

/** 沿最终墙线反向栅格化配套剖面，整段生成后再按区块裁切，不以区块坐标重置垛口。 */
public final class ImportedRibbonSection implements IntervalWallTemplate {
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private final DecayField decay;

    public ImportedRibbonSection(long seed) { decay = new DecayField(seed); }

    @Override public Bounds localBounds(ComponentInput input) { return new Bounds(-32, -15, -5, 32, 3, 6); }

    @Override public ComponentVoxels compile(ComponentInput input) {
        long s = input.site().globalSQ8();
        var interval = new ArcInterval(Math.max(0, s - 32L * 256), s + 32L * 256);
        return compileIntervals(input, List.of(interval), List.of(interval), ignored -> input.decay(), List.of(), List.of());
    }

    @Override public ComponentVoxels compileIntervals(ComponentInput input, List<ArcInterval> intervals,
            List<ArcInterval> walkable, LongFunction<DecaySample> samples, List<Frame> gates, List<SiteRecord> sites) {
        var stations = input.stations();
        long from = Math.max(stations.getFirst().globalSQ8(), Math.max(0, input.site().globalSQ8() - 32L * 256));
        long to = Math.min(stations.getLast().globalSQ8(), input.site().globalSQ8() + 32L * 256);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        var candidates = new ArrayList<Station>();
        candidates.add(at(stations, from)); candidates.add(at(stations, to));
        for (var station : stations) if (station.globalSQ8() > from && station.globalSQ8() < to) candidates.add(station);
        for (var station : candidates) {
            int x = FixedMath.floorBlock(station.point().xQ8()), z = FixedMath.floorBlock(station.point().zQ8());
            x0 = Math.min(x0, x - 8); x1 = Math.max(x1, x + 9);
            z0 = Math.min(z0, z - 8); z1 = Math.max(z1, z + 9);
            y0 = Math.min(y0, station.walkY() - 15); y1 = Math.max(y1, station.walkY() + 3);
        }
        var voxels = new ArrayList<VoxelIntent>();
        var columns = input.entranceEvidence().columns();
        long identity = ComponentAssembly.identityHash(input.site().id());
        var buildingBoxes = sites.stream().filter(s -> s.state() == SiteState.PLANNED).map(ImportedSiteGeometry::bounds).toList();
        var projection = new ImportedRibbonProjection(stations, sites);
        for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) {
            var contract = projection.contract(x, z);
            if (contract == null) continue;
            long s = contract.globalSQ8();
            int lateral = contract.lateralCell(), w = contract.walkY();
            if (s < from || s >= to || lateral < -5 || lateral > 5 || !contains(intervals, s)) continue;
            final int columnX = x, columnZ = z;
            // 楼体内部及门洞都由完整模板拥有；不能把普通墙塞进整楼空气里。
            if (buildingBoxes.stream().anyMatch(b -> columnX >= b.minX() && columnX < b.maxXExclusive()
                    && columnZ >= b.minZ() && columnZ < b.maxZExclusive())) continue;
            var column = columns.get(new Point3i(x, 0, z));
            if (column == null) throw new WallFailure(FailureCode.T0_MISSING, "配套墙缺少终态列证据: " + x + "," + z);
            int surface = column.highestNonPlantFeet();
            if (surface >= w) throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "配套墙马道撞入自然地表: " + x + "," + z);
            var tangent = tangent(stations, s);
            Direction side = Math.abs(tangent[0]) >= Math.abs(tangent[1])
                    ? tangent[0] >= 0 ? Direction.SOUTH : Direction.NORTH
                    : tangent[1] >= 0 ? Direction.WEST : Direction.EAST;
            int phase = Math.floorMod(Math.floorDiv(s, 256), 4);
            boolean seam = false;
            for (var site : sites) if (site.state() == SiteState.PLANNED) {
                var ports = ImportedSiteGeometry.layout(site).ports();
                for (int p = 0; p < ports.size(); p++) {
                    long portS = ImportedSiteGeometry.portArc(site, p);
                    long distance = ports.size() == 2 && p == 0 ? portS - s : s - portS;
                    if (distance > 0 && distance <= ImportedSiteGeometry.STRAIGHT * 256L) {
                        phase = Math.floorMod(Math.toIntExact(FixedMath.roundDiv(distance, 256)) - 1, 4);
                        side = ports.get(p).merlonSide();
                        seam = true;
                    }
                }
            }
            boolean approach = false;
            for (var site : sites) if (site.state() == SiteState.PLANNED
                    && Math.abs(site.globalSQ8() - s) <= ImportedSiteGeometry.reach(site.id()) * 256L) approach = true;
            var sample = seam || approach ? new DecaySample(DecayState.INTACT, 0, 20) : samples.apply(s);
            boolean functional = sample.state() == DecayState.INTACT || sample.state() == DecayState.PARTIAL;
            y0 = Math.min(y0, surface);
            for (int y = surface; y < w + 3; y++) {
                int profileY = y - w + PairedWallProfile.FEET;
                var state = profileY < 0 ? PairedWallProfile.at(phase, lateral, 0, side)
                        : PairedWallProfile.at(phase, lateral, profileY, side);
                boolean floor = profileY == 14 && Math.abs(lateral) < 5 && functional;
                Part part = profileY < 0 ? Part.FOUNDATION : floor ? Part.FLOOR
                        : profileY >= 15 ? Part.PARAPET : Part.CORE;
                IntentKind kind = IntentKind.SOLID;
                if (state == null) {
                    if (!functional || Math.abs(lateral) >= 5 || profileY < 15 || profileY > 16) continue;
                    state = AIR; part = Part.AIR; kind = IntentKind.OWNED_VOID;
                }
                // 地基只补原始空气；真实水体仍由水门判定负责，不能当空地填平。
                if (profileY < 0 && !column.airOrReplaceablePlant(y)) continue;
                boolean required = functional && (part == Part.FLOOR || part == Part.AIR || profileY < 15);
                var local = new Point3i(Math.toIntExact(Math.floorDiv(s - from, 256)), y - w - 5, lateral);
                if (!decay.keep(sample, part, local, s, identity, required)) continue;
                if (floor) state = slopeFloor(state, stations, s, tangent);
                CompiledAllocation.charge(256);
                voxels.add(new VoxelIntent(new Point3i(x, y, z), kind, part, state, input.site().id(), 400, required, floor));
            }
        }
        var bounds = new Bounds(x0, y0, z0, x1, y1, z1);
        return ComponentAssembly.hashed(input.site().id(), bounds, voxels, List.of(), List.of(),
                ImportedWallRoutes.prove(voxels, stations, walkable));
    }

    private static boolean contains(List<ArcInterval> intervals, long s) {
        return intervals.stream().anyMatch(interval -> s >= interval.fromQ8() && s < interval.toQ8());
    }

    private static BlockSpec slopeFloor(BlockSpec flat, List<Station> stations, long s, long[] tangent) {
        int w = at(stations, s).walkY();
        int next = at(stations, Math.min(stations.getLast().globalSQ8(), s + 256)).walkY();
        int previous = at(stations, Math.max(stations.getFirst().globalSQ8(), s - 256)).walkY();
        if (Math.abs(next - w) > 1 || Math.abs(w - previous) > 1)
            throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "配套墙坡度超过每格一级");
        int sign = next > w ? 1 : previous > w ? -1 : 0;
        if (sign == 0) return flat;
        var facing = Math.abs(tangent[0]) >= Math.abs(tangent[1])
                ? tangent[0] * sign >= 0 ? Direction.EAST : Direction.WEST
                : tangent[1] * sign >= 0 ? Direction.SOUTH : Direction.NORTH;
        return new BlockSpec("minecraft:stone_brick_stairs", Map.of("facing", facing.getName(), "half", "bottom",
                "shape", "straight", "waterlogged", "false"));
    }

    private static long[] tangent(List<Station> stations, long s) {
        int i = edge(stations, s);
        var a = stations.get(i); var b = stations.get(i + 1);
        return new long[] {b.point().xQ8() - a.point().xQ8(), b.point().zQ8() - a.point().zQ8()};
    }

    private static int edge(List<Station> stations, long s) {
        int lo = 0, hi = stations.size() - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (stations.get(mid).globalSQ8() <= s) lo = mid; else hi = mid;
        }
        return lo;
    }

    static Station at(List<Station> stations, long s) {
        int i = edge(stations, s);
        var a = stations.get(i); var b = stations.get(i + 1);
        long t = s - a.globalSQ8(), span = b.globalSQ8() - a.globalSQ8();
        return new Station(s, new Point2q(a.point().xQ8() + FixedMath.mulDiv(b.point().xQ8() - a.point().xQ8(), t, span),
                a.point().zQ8() + FixedMath.mulDiv(b.point().zQ8() - a.point().zQ8(), t, span)),
                a.walkY() + (int) FixedMath.roundDiv((long) (b.walkY() - a.walkY()) * t, span), 0);
    }
}
