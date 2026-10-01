package local.plaincraft.greatwall.terrain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Width evidence uses natural reference columns, never decorated chunks or adjacent hydrology. */
public final class HydrologyClassifier {
    private static final List<Point2q> DIRECTIONS = directions();
    public HydrologyClassifier() {}

    public Cursor begin(RouteNeighborhood routes, NaturalTerrainOracle oracle, int columnBudget) {
        if (columnBudget < 1 || columnBudget > 24000) throw new IllegalArgumentException("hydrology budget 1..24000");
        var c = new Cursor();
        c.routes = Objects.requireNonNull(routes); c.oracle = Objects.requireNonNull(oracle); c.budget = columnBudget;
        var nodes = new ArrayList<Node>();
        routes.previous().ifPresent(r -> {
            long length = r.localSQ8().getLast();
            int start = ArcLength.edgeAt(r.localSQ8(), Math.max(0, length - 128L * 256));
            appendNodes(nodes, r, start, r.points().size() - 1, -length);
        });
        appendNodes(nodes, routes.center(), 0, routes.center().points().size() - 1, 0);
        routes.next().ifPresent(r -> {
            int end = Math.min(r.points().size() - 1, ArcLength.edgeAt(r.localSQ8(), Math.min(r.localSQ8().getLast(), 128L * 256)) + 1);
            appendNodes(nodes, r, 0, end, routes.center().localSQ8().getLast());
        });
        for (Node n : nodes) if (Math.floorMod(n.absoluteU, 4) == 0) c.coarse.add(new Cell(
                FixedMath.floorBlock(n.point.xQ8()), FixedMath.floorBlock(n.point.zQ8()), n.s));
        for (int i = 1; i < nodes.size(); i++) {
            Node a = nodes.get(i - 1), b = nodes.get(i);
            for (Cell cell : supercover(a.point, b.point, a.s, b.s)) {
                if (c.trace.isEmpty() || !sameColumn(c.trace.getLast(), cell)) c.trace.add(cell);
                else if (cell.error < c.trace.getLast().error) c.trace.set(c.trace.size() - 1, cell);
            }
        }
        if (c.trace.isEmpty()) throw new IllegalArgumentException("empty hydrology route");
        return c;
    }

    public WorkState advance(Cursor c, int primitiveLimit) {
        if (primitiveLimit < 1 || primitiveLimit > 256) throw new IllegalArgumentException("primitive limit 1..256");
        c.claimOwner();
        for (int op = 0; op < primitiveLimit && !c.done(); op++) {
            if (Thread.currentThread().isInterrupted()) throw new WallFailure(FailureCode.CLOSED, "hydrology interrupted");
            switch (c.phase) {
                case 0 -> coarse(c);
                case 1 -> scan(c);
                case 2 -> nextRun(c);
                case 3 -> transect(c);
                case 4 -> classify(c);
                case 5 -> pier(c);
                default -> throw new IllegalStateException("unknown hydrology phase");
            }
        }
        return c.done() ? WorkState.DONE : WorkState.MORE;
    }

    public List<WaterEvidence> finish(Cursor cursor) {
        if (!cursor.done()) throw new IllegalStateException("hydrology incomplete");
        return List.copyOf(cursor.completed);
    }

    public WaterEvidence resolveSupport(WaterEvidence evidence, ToIntFunction<Point3i> walkY) {
        Objects.requireNonNull(walkY);
        if (evidence.classification() != WaterClass.NARROW) return evidence;
        for (var p : evidence.piers()) {
            if (Math.max(0L, (long) walkY.applyAsInt(p) - 20 - p.y()) > 12) {
                return new WaterEvidence(evidence.crossing(), WaterClass.SUPPORT_GAP, evidence.channelWidthQ8(),
                        evidence.crossingSpanQ8(), evidence.waterTopY(), evidence.banks(), evidence.piers(), evidence.inputHash());
            }
        }
        return evidence;
    }

    private static void appendNodes(List<Node> result, RouteShape route, int from, int to, long base) {
        if (route.points().size() != 2049) throw new IllegalArgumentException("hydrology requires one-u route grid");
        for (int i = from; i <= to; i++) {
            var node = new Node(route.points().get(i), Math.addExact(base, route.localSQ8().get(i)),
                    Math.addExact(Math.multiplyExact(route.key().index(), 2048), i));
            if (result.isEmpty() || !result.getLast().point.equals(node.point)) result.add(node);
        }
    }

    private static void coarse(Cursor c) {
        if (c.routeIndex == c.coarse.size()) { c.routeIndex = 0; c.phase = 1; return; }
        if (sample(c, c.coarse.get(c.routeIndex)) != null) c.routeIndex++;
    }

    private static void scan(Cursor c) {
        if (c.routeIndex == c.trace.size()) {
            if (c.runStart >= 0) c.runs.add(new Run(c.runStart, c.trace.size(), c.runStart != 0, false));
            c.phase = 2; return;
        }
        var value = sample(c, c.trace.get(c.routeIndex));
        if (value == null) return;
        c.traceSamples.add(value);
        if (wet(value)) { if (c.runStart < 0) c.runStart = c.routeIndex; }
        else if (c.runStart >= 0) { c.runs.add(new Run(c.runStart, c.routeIndex, c.runStart != 0, true)); c.runStart = -1; }
        c.routeIndex++;
    }

    private static void nextRun(Cursor c) {
        if (c.runIndex == c.runs.size()) { c.done = true; c.cache.clear(); return; }
        c.run = c.runs.get(c.runIndex++);
        long from = c.trace.get(c.run.from).s;
        long to = c.run.to < c.trace.size() ? c.trace.get(c.run.to).s : c.trace.getLast().s;
        if (to <= 0 || from >= c.routes.center().localSQ8().getLast() || to <= from) return;
        if (!c.run.leftBank && c.routes.center().key().index() == 0 && from <= 0) {
            throw new WallFailure(FailureCode.ROOT_NOT_FOUND, "wall root is wet without an entry bank");
        }
        c.crossing = new ArcInterval(from, to); c.centers.clear(); c.evidence.clear(); c.piers.clear();
        long span = to - from;
        for (long target : new long[] {from + Math.min(4L * 256, span / 2), from + span / 2, to - Math.min(4L * 256, span / 2)}) {
            int index = c.run.from;
            while (index + 1 < c.run.to && c.trace.get(index + 1).s <= target) index++;
            Cell center = c.trace.get(index);
            if (c.centers.stream().noneMatch(v -> sameColumn(v, center))) c.centers.add(center);
        }
        c.waterY = c.traceSamples.get(c.run.from).waterTopY().orElseThrow();
        c.waterMismatch = false;
        for (int i = c.run.from; i < c.run.to; i++) {
            var s = c.traceSamples.get(i);
            c.evidence.put(columnKey(c.trace.get(i)), s);
            if (Math.abs((long) s.waterTopY().orElseThrow() - c.waterY) > 1) c.waterMismatch = true;
        }
        // 水位不一致（碎岛典型特征）按宽水域收头。收头的水域无需 96 条横断射线
        // 认证宽度（宽度不影响收头结果），直接判 WIDE 跳过 transect，避免多段
        // 水域累加耗尽 per-segment 24000 列预算。width 记 0 表示"未测"，不伪造。
        if (c.waterMismatch) {
            c.width = 0L;
            c.classification = WaterClass.WIDE;
            completeEvidence(c);
            return;
        }
        c.results = new Side[c.centers.size()][16][2];
        c.centerIndex = 0; c.transect = 0; c.side = 0; c.ray = null; c.phase = 3;
    }

    private static void transect(Cursor c) {
        Cell center = c.centers.get(c.centerIndex);
        Point2q direction = DIRECTIONS.get(c.transect);
        if (c.ray == null) {
            var a = new Point2q(center.x * 256L + 128, center.z * 256L + 128);
            long sign = c.side == 0 ? -1 : 1;
            var b = new Point2q(a.xQ8() + FixedMath.mulDiv(sign * 58 * 256, direction.xQ8(), 1L << 30),
                    a.zQ8() + FixedMath.mulDiv(sign * 58 * 256, direction.zQ8(), 1L << 30));
            c.ray = supercover(a, b, 0, 58 * 256);
            c.distance = 0; c.dryRun = 0; c.lastWet = center; c.continuousLastWet = center;
            c.firstDry = null; c.continuous = true;
        }
        if (c.distance == c.ray.size() || c.dryRun >= 8) {
            long extent = extent(center, c.lastWet, direction);
            long lower = extent(center, c.continuousLastWet, direction);
            c.results[c.centerIndex][c.transect][c.side] = new Side(c.dryRun >= 8, extent, lower,
                    c.firstDry == null ? c.lastWet : c.firstDry);
            c.ray = null;
            if (++c.side == 2) { c.side = 0; if (++c.transect == 16) { c.transect = 0; c.centerIndex++; } }
            if (c.centerIndex == c.centers.size()) c.phase = 4;
            return;
        }
        Cell cell = c.ray.get(c.distance);
        var value = sample(c, cell);
        if (value == null) return;
        c.evidence.put(columnKey(cell), value);
        if (wet(value)) {
            c.dryRun = 0; c.firstDry = null; c.lastWet = cell;
            if (c.continuous) c.continuousLastWet = cell;
            if (Math.abs((long) value.waterTopY().orElseThrow() - c.waterY) > 1) c.waterMismatch = true;
        } else {
            c.continuous = false;
            if (c.firstDry == null) c.firstDry = cell;
            long cellWidth = FixedMath.mulDiv(Math.abs(direction.xQ8()) + Math.abs(direction.zQ8()), 256, 1L << 30);
            c.dryRun = Math.toIntExact((extent(c.firstDry, cell, direction) + cellWidth) / 256);
        }
        c.distance++;
    }

    private static long extent(Cell center, Cell edge, Point2q direction) {
        long projection = ((long) edge.x - center.x) * direction.xQ8() + ((long) edge.z - center.z) * direction.zQ8();
        return FixedMath.mulDiv(Math.abs(projection), 256, 1L << 30);
    }

    private static void classify(Cursor c) {
        long best = Long.MAX_VALUE;
        int bestDirection = -1;
        boolean allWide = true;
        for (int k = 0; k < 16; k++) {
            boolean certified = true;
            long maximum = 0;
            long cellWidth = FixedMath.mulDiv(Math.abs(DIRECTIONS.get(k).xQ8()) + Math.abs(DIRECTIONS.get(k).zQ8()), 256, 1L << 30);
            for (int i = 0; i < c.centers.size(); i++) {
                Side left = c.results[i][k][0], right = c.results[i][k][1];
                long width = left.extent + right.extent + cellWidth;
                maximum = Math.max(maximum, width);
                certified &= left.bank && right.bank;
                long lower = left.lower + right.lower + cellWidth;
                if (!(left.bank && right.bank && width >= 50L * 256) && lower < 50L * 256) allWide = false;
            }
            if (certified && maximum < best) { best = maximum; bestDirection = k; }
        }
        c.width = best == Long.MAX_VALUE ? 50L * 256 : best;
        c.banks.clear();
        if (c.run.leftBank) {
            var b = c.trace.get(c.run.from - 1); c.banks.add(new Point3i(b.x, c.waterY, b.z));
        }
        if (c.run.rightBank) {
            var b = c.trace.get(c.run.to); c.banks.add(new Point3i(b.x, c.waterY, b.z));
        }
        if (c.waterMismatch) c.classification = WaterClass.WIDE;
        else if (bestDirection >= 0 && best < 50L * 256) {
            c.classification = c.crossing.toQ8() - c.crossing.fromQ8() > 128L * 256 || !c.run.leftBank || !c.run.rightBank
                    ? WaterClass.SUPPORT_GAP : WaterClass.NARROW;
        } else if (allWide) c.classification = WaterClass.WIDE;
        else c.classification = WaterClass.WIDE;
        c.pierS = c.crossing.fromQ8() + 4L * 256;
        if (c.classification == WaterClass.NARROW) c.phase = 5; else completeEvidence(c);
    }

    private static String unresolvedDetails(Cursor c, int bestDirection, boolean allWide) {
        int minWater = c.waterY, maxWater = c.waterY, mismatchedColumns = 0;
        for (var sample : c.evidence.values()) if (sample.waterTopY().isPresent()) {
            int y = sample.waterTopY().getAsInt();
            minWater = Math.min(minWater, y); maxWater = Math.max(maxWater, y);
            if (Math.abs((long) y - c.waterY) > 1) mismatchedColumns++;
        }
        var out = new StringBuilder("no consistent two-bank width proof: segment=")
                .append(c.routes.center().key().index()).append(" crossing=").append(c.crossing)
                .append(" waterY=").append(c.waterY).append(" waterMinY=").append(minWater)
                .append(" waterMaxY=").append(maxWater).append(" waterMismatch=").append(c.waterMismatch)
                .append(" mismatchedColumns=").append(mismatchedColumns).append(" bestDirection=")
                .append(bestDirection).append(" allWide=").append(allWide).append(" chargedColumns=")
                .append(c.charged).append(" centers=").append(c.centers).append(" transects=[");
        for (int k = 0; k < DIRECTIONS.size(); k++) {
            long cellWidth = FixedMath.mulDiv(Math.abs(DIRECTIONS.get(k).xQ8())
                    + Math.abs(DIRECTIONS.get(k).zQ8()), 256, 1L << 30);
            out.append(" direction=").append(k).append("{");
            for (int i = 0; i < c.centers.size(); i++) {
                Side left = c.results[i][k][0], right = c.results[i][k][1];
                out.append("center=").append(i).append(" left=").append(left)
                        .append(" right=").append(right).append(" widthQ8=")
                        .append(left.extent + right.extent + cellWidth).append(" lowerQ8=")
                        .append(left.lower + right.lower + cellWidth).append(';');
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static void pier(Cursor c) {
        if (c.pierS >= c.crossing.toQ8() - 4L * 256) { completeEvidence(c); return; }
        long midpoint = c.crossing.fromQ8() + (c.crossing.toQ8() - c.crossing.fromQ8()) / 2;
        if (Math.abs(c.pierS - midpoint) < 2 * 256) { c.pierS += 4 * 256; return; }
        int index = c.run.from;
        while (index + 1 < c.run.to && c.trace.get(index + 1).s <= c.pierS) index++;
        Cell p = c.trace.get(index);
        var sample = sample(c, p);
        if (sample == null) return;
        c.evidence.put(columnKey(p), sample);
        if (sample.bedTopY().isEmpty()) c.classification = WaterClass.SUPPORT_GAP;
        else c.piers.add(new Point3i(p.x, sample.bedTopY().getAsInt(), p.z));
        c.pierS += 4 * 256;
    }

    private static void completeEvidence(Cursor c) {
        c.completed.add(new WaterEvidence(c.crossing, c.classification, c.width,
                c.crossing.toQ8() - c.crossing.fromQ8(), c.waterY, c.banks, c.piers, evidenceHash(c.evidence)));
        c.phase = 2;
    }

    private static ColumnSample sample(Cursor c, Cell cell) {
        long key = columnKey(cell);
        if (c.column == null) {
            if (c.charged == c.budget) throw new WallFailure(FailureCode.HYDROLOGY_UNRESOLVED, "hydrology budget exhausted");
            c.charged++;
            var cached = c.cache.get(key);
            if (cached != null) return cached;
            c.column = c.oracle.begin(cell.x, cell.z, SampleKind.REFERENCE); return null;
        }
        if (!c.column.done()) { c.oracle.advance(c.column, 1); return null; }
        var value = c.oracle.finish(c.column); c.column = null;
        if (value.quality() != Quality.REFERENCE || value.upperCensored()
                || value.waterTopY().isPresent() && value.bedTopY().isEmpty()) {
            throw new WallFailure(FailureCode.HYDROLOGY_UNRESOLVED, "reference column cannot certify ground or river bed");
        }
        c.cache.put(key, value); return value;
    }

    private static boolean wet(ColumnSample sample) {
        return !sample.upperCensored() && sample.quality() == Quality.REFERENCE && sample.waterTopY().isPresent()
                && sample.bedTopY().isPresent() && sample.waterTopY().getAsInt() > sample.bedTopY().getAsInt();
    }

    private static long columnKey(Cell p) { return ((long) p.x << 32) ^ Integer.toUnsignedLong(p.z); }
    private static boolean sameColumn(Cell a, Cell b) { return a.x == b.x && a.z == b.z; }

    private static String evidenceHash(Map<Long, ColumnSample> values) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            for (long key : values.keySet().stream().sorted().toList()) {
                var v = values.get(key);
                out.writeInt((int) (key >> 32)); out.writeInt((int) key); out.writeInt(v.groundTopY());
                out.writeBoolean(v.waterTopY().isPresent()); if (v.waterTopY().isPresent()) out.writeInt(v.waterTopY().getAsInt());
                out.writeBoolean(v.bedTopY().isPresent()); if (v.bedTopY().isPresent()) out.writeInt(v.bedTopY().getAsInt());
                byte[] biome = v.biomeKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(biome.length); out.write(biome); out.writeBoolean(v.upperCensored());
            }
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        return DeterministicHash.sha256(bytes.toByteArray());
    }

    private static List<Point2q> directions() {
        var result = new ArrayList<Point2q>();
        for (int k = 0; k < 16; k++) result.add(new Point2q(
                (long) StrictMath.floor(StrictMath.cos(k * StrictMath.PI / 16) * (1L << 30) + 0.5),
                (long) StrictMath.floor(StrictMath.sin(k * StrictMath.PI / 16) * (1L << 30) + 0.5)));
        return List.copyOf(result);
    }

    private static List<Cell> supercover(Point2q a, Point2q b, long fromS, long toS) {
        var result = new ArrayList<Cell>();
        int x = FixedMath.floorBlock(a.xQ8()), z = FixedMath.floorBlock(a.zQ8());
        int ex = FixedMath.floorBlock(b.xQ8()), ez = FixedMath.floorBlock(b.zQ8());
        long dx = b.xQ8() - a.xQ8(), dz = b.zQ8() - a.zQ8();
        int sx = Long.signum(dx), sz = Long.signum(dz);
        addCell(result, x, z, a, dx, dz, fromS, toS);
        while (x != ex || z != ez) {
            long nx = sx > 0 ? (x + 1L) * 256 - a.xQ8() : a.xQ8() - x * 256L;
            long nz = sz > 0 ? (z + 1L) * 256 - a.zQ8() : a.zQ8() - z * 256L;
            long left = sx == 0 ? Long.MAX_VALUE : nx * Math.abs(dz);
            long right = sz == 0 ? Long.MAX_VALUE : nz * Math.abs(dx);
            if (x == ex) left = Long.MAX_VALUE;
            if (z == ez) right = Long.MAX_VALUE;
            if (left < right) { x += sx; addCell(result, x, z, a, dx, dz, fromS, toS); }
            else if (right < left) { z += sz; addCell(result, x, z, a, dx, dz, fromS, toS); }
            else {
                addCell(result, x + sx, z, a, dx, dz, fromS, toS);
                addCell(result, x, z + sz, a, dx, dz, fromS, toS);
                x += sx; z += sz; addCell(result, x, z, a, dx, dz, fromS, toS);
            }
            if (result.size() > 1024) throw new IllegalArgumentException("unexpectedly long supercover edge");
        }
        result.sort(Comparator.comparingLong(Cell::s).thenComparingInt(Cell::x).thenComparingInt(Cell::z));
        return List.copyOf(result);
    }

    private static void addCell(List<Cell> result, int x, int z, Point2q a, long dx, long dz, long from, long to) {
        long den = dx * dx + dz * dz;
        long num = den == 0 ? 0 : Math.clamp((x * 256L + 128 - a.xQ8()) * dx + (z * 256L + 128 - a.zQ8()) * dz, 0L, den);
        long s = den == 0 ? from : from + FixedMath.roundDiv(Math.multiplyExact(to - from, num), den);
        long nx = den == 0 ? a.xQ8() : a.xQ8() + FixedMath.roundDiv(dx * num, den);
        long nz = den == 0 ? a.zQ8() : a.zQ8() + FixedMath.roundDiv(dz * num, den);
        long errorX = x * 256L + 128 - nx, errorZ = z * 256L + 128 - nz;
        result.add(new Cell(x, z, s, errorX * errorX + errorZ * errorZ));
    }

    private record Node(Point2q point, long s, long absoluteU) {}
    private record Cell(int x, int z, long s, long error) {
        private Cell(int x, int z, long s) { this(x, z, s, 0); }
    }
    private record Run(int from, int to, boolean leftBank, boolean rightBank) {}
    private record Side(boolean bank, long extent, long lower, Cell bankCell) {}

    public static final class Cursor {
        private Thread owner;
        private RouteNeighborhood routes;
        private NaturalTerrainOracle oracle;
        private int budget, phase, routeIndex, transect, centerIndex, side, distance, dryRun, charged, runStart = -1, runIndex;
        private int waterY;
        private long width, pierS;
        private boolean done, continuous, waterMismatch;
        private NaturalTerrainOracle.ColumnCursor column;
        private final List<WaterEvidence> completed = new ArrayList<>();
        private final List<Cell> coarse = new ArrayList<>(), trace = new ArrayList<>(), centers = new ArrayList<>();
        private final List<ColumnSample> traceSamples = new ArrayList<>();
        private final List<Run> runs = new ArrayList<>();
        private final List<Point3i> banks = new ArrayList<>(), piers = new ArrayList<>();
        private final Map<Long, ColumnSample> cache = new HashMap<>(), evidence = new HashMap<>();
        private Run run;
        private List<Cell> ray;
        private Cell lastWet, continuousLastWet, firstDry;
        private Side[][][] results;
        private ArcInterval crossing;
        private WaterClass classification;
        private Cursor() {}
        private void claimOwner() {
            if (owner == null) owner = Thread.currentThread();
            else if (owner != Thread.currentThread()) throw new IllegalStateException("hydrology cursor has another owner");
        }
        public boolean done() { return done; }
        public int logicalColumns() { return charged; }
    }
}
