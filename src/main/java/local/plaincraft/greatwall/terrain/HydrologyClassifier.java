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
    /**
     * Documented crossing limit: a water span narrower than this crosses, at or above it terminates.
     *
     * <p>From the handover note - rivers under 50 blocks cross, 50 and wider terminate. The classifier has
     * never had a reliable way to measure the span, so a width it cannot certify is treated as WIDE, which
     * is the policy approved at S1C. This constant exists so the measurement below can report what the
     * documented rule would have decided, without changing what the code does.
     */
    public static final int RIVER_CROSS_MAX_BLOCKS = 50;
    private static final List<Point2q> DIRECTIONS = directions();
    public HydrologyClassifier() {}

    public Cursor begin(RouteNeighborhood routes, NaturalTerrainOracle oracle, int columnBudget) {
        return begin(routes, oracle, columnBudget, false);
    }

    public Cursor begin(RouteNeighborhood routes, NaturalTerrainOracle oracle, int columnBudget, boolean useRouteSpan) {
        if (columnBudget < 1 || columnBudget > 120000) throw new IllegalArgumentException("hydrology budget 1..120000");
        var c = new Cursor();
        c.routes = Objects.requireNonNull(routes); c.oracle = Objects.requireNonNull(oracle); c.budget = columnBudget;
        // 新墙验证实际要跨的线路而不是横扫水域；短且两岸齐全才有资格试水门。
        c.useRouteSpan = useRouteSpan;
        if (useRouteSpan) c.phase = 1;
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
        for (Node n : nodes) if (!useRouteSpan && Math.floorMod(n.absoluteU, 4) == 0) c.coarse.add(new Cell(
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
        if (primitiveLimit < 1 || primitiveLimit > 4096) throw new IllegalArgumentException("primitive limit 1..4096");
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

    /**
     * Terminates the remaining unresolved water as WIDE (the authorized
     * terminate-into-sea policy) without running further transects. Used when
     * a segment's hydrology exceeds its operation budget: an island water
     * never needs an exact width because it terminates either way, so
     * continuing to certify it only burns time. Already certified NARROW /
     * WIDE / SUPPORT_GAP evidence is preserved untouched.
     */
    /**
     * Blocks of continuous water around one station, measured on the route scan that already ran.
     *
     * <p>This is the quantity every forced completion was missing. The coarse and route scans have already
     * classified each station as wet or dry, so the span is a count over data in hand: no riverbed, no new
     * sampling, no extra budget.
     */
    private static long continuousWaterBlocks(Cursor c, int around) {
        int stations = Math.min(c.trace.size(), c.traceSamples.size());
        if (stations == 0) return 0L;
        int start = Math.max(0, Math.min(stations - 1, around));
        int from = start;
        int to = start;
        while (from > 0 && waterColumnAt(c, from - 1)) from--;
        while (to + 1 < stations && waterColumnAt(c, to + 1)) to++;
        return Math.max(0L, c.trace.get(to).s - c.trace.get(from).s);
    }

    private static boolean waterColumnAt(Cursor c, int index) {
        var sample = c.traceSamples.get(index);
        return sample != null && sample.waterTopY().isPresent();
    }

    public void forceComplete(Cursor c) {
        Objects.requireNonNull(c, "cursor");
        c.claimOwner();
        if (c.done) return;
        // Measure the span before abandoning the segment, and report what the documented rule would have
        // decided. The outcome stays WIDE - that is the approved policy - but this line turns "how much are
        // we losing to an uncertifiable width" into a number instead of a guess, which is the first of the
        // four missing definitions the final review named.
        long span = continuousWaterBlocks(c, c.run != null ? c.run.from
                : (c.runs.isEmpty() ? 0 : c.runs.get(Math.min(c.runIndex, c.runs.size() - 1)).from));
        String documented = span < RIVER_CROSS_MAX_BLOCKS ? "CROSS" : "WIDE";
        // When the documented rule says CROSS and the policy still ends the wall, the narrow span is the
        // only thing standing between "there is no river here" and "we censored the column that would have
        // proved it". A clamped sample fakes a narrow span, so those two cases must not look identical in
        // the log - report the censored count and raise the level whenever the two disagree.
        long censored = c.traceSamples.stream()
                .filter(s -> s.quality() == Quality.CENSORED || s.upperCensored()).count();
        if (span < RIVER_CROSS_MAX_BLOCKS) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:hydro] RIVER_SPAN span={} threshold={} documentedDecision={} outcome=WIDE "
                            + "phase={} stations={} censoredSamples={} - policy unchanged; a non-zero "
                            + "censoredSamples means the narrow span may be an artefact of clamping",
                    span, RIVER_CROSS_MAX_BLOCKS, documented, c.phase, c.trace.size(), censored);
        } else {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:hydro] RIVER_SPAN span={} threshold={} documentedDecision={} outcome=WIDE "
                            + "phase={} stations={} censoredSamples={} - policy unchanged",
                    span, RIVER_CROSS_MAX_BLOCKS, documented, c.phase, c.trace.size(), censored);
        }
        // A column that was mid-sample must be finished before the cursor is
        // abandoned, otherwise the oracle still owns an open column and the
        // next begin() refuses with "finish the current natural column".
        drainOpenColumn(c);
        if (c.phase == 0) {
            // Coarse scan had not finished; there is nothing certified yet. Clearing this used to be
            // silent, which left no trace anywhere that a segment lost every certified span it had.
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:hydro] HYDROLOGY_COMPLETED_CLEARED evidence={} phase=0 - the coarse scan "
                            + "never finished, so every certified span in this segment is discarded",
                    c.completed.size());
            c.completed.clear();
            c.done = true;
            c.cache.clear();
            return;
        }
        if (c.phase == 1) {
            // The route scan was still running: close the open run, then treat
            // every discovered run as WIDE without any transect work.
            if (c.runStart >= 0) {
                c.runs.add(new Run(c.runStart, c.trace.size(), c.runStart != 0, false));
                c.runStart = -1;
            }
            for (Run run : c.runs) {
                long from = c.trace.get(run.from).s;
                long to = run.to < c.trace.size() ? c.trace.get(run.to).s : c.trace.getLast().s;
                if (to <= 0 || from >= c.routes.center().localSQ8().getLast() || to <= from) continue;
                c.completed.add(new WaterEvidence(new ArcInterval(from, to), WaterClass.WIDE, 0L,
                        to - from, c.traceSamples.get(run.from).waterTopY().orElse(0),
                        List.of(), List.of(), evidenceHash(c.evidence)));
            }
            c.done = true;
            c.cache.clear();
            return;
        }
        // Transect / classify was in progress: keep the current run's partial
        // evidence as WIDE (width unmeasured, never fabricated) and terminate
        // every remaining run the same way.
        c.classification = WaterClass.WIDE;
        c.width = 0L;
        c.banks.clear();
        c.piers.clear();
        completeEvidence(c);
        for (int index = c.runIndex; index < c.runs.size(); index++) {
            Run run = c.runs.get(index);
            long from = c.trace.get(run.from).s;
            long to = run.to < c.trace.size() ? c.trace.get(run.to).s : c.trace.getLast().s;
            if (to <= 0 || from >= c.routes.center().localSQ8().getLast() || to <= from) continue;
            c.completed.add(new WaterEvidence(new ArcInterval(from, to), WaterClass.WIDE, 0L,
                    to - from, c.traceSamples.get(run.from).waterTopY().orElse(0),
                    List.of(), List.of(), evidenceHash(c.evidence)));
        }
        c.done = true;
        c.cache.clear();
    }

    /**
     * Downgrades a NARROW crossing to {@link WaterClass#SUPPORT_GAP} when a pier has no reachable support.
     *
     * <p>Staged on purpose: 1A delivers this as a pure function covered by {@code HydrologyTest} and by the
     * analytic fixtures in {@code docs/S1A-补充合同.md} §7; production calls it in 1B once the full
     * cross-segment height contract exists. It has no production caller today, and that is not dead code -
     * wiring it in 1A would judge support against an incomplete {@code walkY} and turn deep rivers into
     * SUPPORT_GAP. Do not delete it and do not mark it deprecated.
     */
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

    /**
     * Finishes an in-flight oracle column so the cursor leaves no open
     * natural column behind. Sampling errors are swallowed here because the
     * whole point of a forced completion is to stop spending work; the
     * evidence already certified stays authoritative.
     */
    private static void drainOpenColumn(Cursor c) {
        if (c.column == null) return;
        try {
            while (!c.column.done()) c.oracle.advance(c.column, 1);
            c.oracle.finish(c.column);
        } catch (RuntimeException ignored) {
            // A forced completion accepts a partially sampled last column.
        } finally {
            c.column = null;
        }
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
        if (c.useRouteSpan ? value.waterTopY().isPresent() : wet(value)) {
            if (c.runStart < 0) c.runStart = c.routeIndex;
        }
        else if (c.runStart >= 0) { c.runs.add(new Run(c.runStart, c.routeIndex, c.runStart != 0, true)); c.runStart = -1; }
        c.routeIndex++;
    }

    private static void nextRun(Cursor c) {
        if (c.runIndex == c.runs.size()) { c.done = true; c.cache.clear(); return; }
        c.run = c.runs.get(c.runIndex++);
        long from = c.trace.get(c.run.from).s;
        long to = c.run.to < c.trace.size() ? c.trace.get(c.run.to).s : c.trace.getLast().s;
        if (to <= 0 || from >= c.routes.center().localSQ8().getLast() || to <= from) return;
        if (!c.useRouteSpan && !c.run.leftBank && c.routes.center().key().index() == 0 && from <= 0) {
            throw new WallFailure(FailureCode.ROOT_NOT_FOUND, "wall root is wet without an entry bank");
        }
        c.crossing = new ArcInterval(from, to); c.centers.clear(); c.evidence.clear(); c.piers.clear();
        long span = to - from;
        if (c.useRouteSpan) {
            classifyRouteSpan(c, span);
            return;
        }
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

    private static void classifyRouteSpan(Cursor c, long span) {
        c.waterY = c.traceSamples.get(c.run.from).waterTopY().orElseThrow();
        c.banks.clear();
        boolean narrow = span < RIVER_CROSS_MAX_BLOCKS * 256L && c.run.leftBank && c.run.rightBank
                && isCertifiedDry(c.traceSamples.get(c.run.from - 1))
                && isCertifiedDry(c.traceSamples.get(c.run.to));
        for (int i = c.run.from; i < c.run.to; i++) {
            var sample = c.traceSamples.get(i);
            c.evidence.put(columnKey(c.trace.get(i)), sample);
            narrow &= wet(sample) && Math.abs((long) sample.waterTopY().orElseThrow() - c.waterY) <= 1;
        }
        c.width = narrow ? span : 0;
        c.classification = narrow ? WaterClass.NARROW : WaterClass.WIDE;
        if (!narrow) {
            completeEvidence(c);
            return;
        }
        for (int i : new int[]{c.run.from - 1, c.run.to}) {
            var bank = c.trace.get(i);
            c.banks.add(new Point3i(bank.x, c.waterY, bank.z));
        }
        // 同一条走线上的真实河床继续供水门验证，宽水和缺证无需另外射线。
        c.pierS = c.crossing.fromQ8() + 4L * 256;
        c.phase = 5;
    }

    private static boolean isCertifiedDry(ColumnSample sample) {
        return sample.quality() == Quality.REFERENCE && !sample.upperCensored() && sample.waterTopY().isEmpty();
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
        // Which branch decided, and on what evidence. Every fail-open branch lands on WIDE, and WIDE is
        // what ends a segment - so "the wall is meant to stop here" and "the classifier could not measure
        // this span and gave up" were the same word in every line that existed. This one separates them,
        // and the segment-level consequence is logged by TERMINAL_SOURCE / PLAN_DEAD.
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:diag] HYDRO_VERDICT class={} branch={} waterMismatch={} best={} bestDirection={} "
                        + "allWide={} waterY={} widthQ8={} crossingFrom={} crossingTo={}",
                c.classification,
                c.waterMismatch ? "MISMATCH" : (bestDirection >= 0 && best < 50L * 256 ? "MEASURED"
                        : (allWide ? "ALL_WIDE" : "FALLBACK")),
                c.waterMismatch, best, bestDirection, allWide, c.waterY, c.width,
                c.crossing.fromQ8(), c.crossing.toQ8());
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
        if (!c.useRouteSpan && (value.quality() != Quality.REFERENCE || value.upperCensored()
                || value.waterTopY().isPresent() && value.bedTopY().isEmpty())) {
            // Name the column before refusing: the failure itself is handled a level up (the job turns
            // it into the authorised WIDE outcome), and "which column and why" is the only thing an
            // operator can act on afterwards. A measured world died on this line at a wall site on a
            // y=501 plateau, with nothing in the log but the bare sentence.
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn("[GreatWall:hydro] "
                            + "HYDROLOGY_UNRESOLVED_COLUMN at x={} z={} quality={} upperCensored={} "
                            + "water={} bed={}",
                    cell.x, cell.z, value.quality(), value.upperCensored(),
                    value.waterTopY().isPresent(), value.bedTopY().isPresent());
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
        private boolean done, continuous, waterMismatch, useRouteSpan;
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
