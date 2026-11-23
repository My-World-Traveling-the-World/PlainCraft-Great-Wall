package local.plaincraft.greatwall.planning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** Deterministic owner-local continuation. Time controls yielding, never geometric decisions. */
public final class RidgePlanner {
    private static final List<Integer> DELTAS = List.of(-12, -8, -4, 0, 4, 8, 12);
    private static final List<Point2q> OFFSETS = List.of(new Point2q(0, 0), new Point2q(48, 0),
            new Point2q(-48, 0), new Point2q(0, 48), new Point2q(0, -48),
            new Point2q(32, 32), new Point2q(32, -32), new Point2q(-32, 32), new Point2q(-32, -32));
    private static final String ZERO = "0".repeat(64);
    private static final int[] SEAM_OFFSETS = {0, -16, 16, -32, 32, -64, 64};
    private final WorldRecipe recipe;
    private final AnchorField anchors;
    private final boolean gentle;
    /**
     * Resolved structure footprints along the corridor, injected after the planning-time scan
     * completes. Empty until then, so the ridge behaves exactly as before the refactor when no
     * scan has run. Replaced wholesale (not mutated) so the detour stage always observes one
     * immutable index.
     */
    private volatile StructureObstacleIndex obstacles = StructureObstacleIndex.empty(OBSTACLE_MARGIN_BLOCKS);
    /** Wall half-width plus safety margin; a structure is a hard obstacle when the centre line
     *  approaches within this many blocks of its footprint. */
    private static final int OBSTACLE_MARGIN_BLOCKS = 8;

    public RidgePlanner(WorldRecipe recipe, AnchorField anchors) {
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.gentle = recipe.config().templateVersion() >= WallRecipeRules.INTACT;
        if (recipe.config().columnBudget() != 150000 || recipe.config().corridorHalfWidth() != 512) {
            throw new IllegalArgumentException("requires frozen 150000/512 budget and corridor");
        }
    }

    /** Publishes the resolved obstacle index once the corridor scan settles; must precede the
     *  bulk prewarm so the detour stage observes the final value. */
    public void setObstacles(StructureObstacleIndex index) {
        this.obstacles = Objects.requireNonNull(index, "index");
    }

    public Cursor begin(SegmentKey key, NaturalTerrainOracle oracle) {
        if (!key.recipeHash().equals(recipe.recipeHash()) || !key.dimension().equals(recipe.dimension())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "route key differs from frozen recipe");
        }
        if (!oracle.fingerprint().equals(recipe.terrainHash())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "oracle fingerprint differs from recipe");
        }
        var cursor = new Cursor(key, oracle);
        cursor.first = anchors.at(key.index()); cursor.last = anchors.at(Math.addExact(key.index(), 1));
        cursor.controls.add(0L);
        return cursor;
    }

    public WorkState advance(Cursor c, int primitiveLimit) {
        if (primitiveLimit < 1 || primitiveLimit > 4096) throw new IllegalArgumentException("primitive limit 1..4096");
        c.claimOwner();
        for (int op = 0; op < primitiveLimit && !c.done(); op++) {
            if (Thread.currentThread().isInterrupted()) throw new WallFailure(FailureCode.CLOSED, "route interrupted");
            switch (c.phase) {
                case 0 -> boundaryStep(c);
                case 1 -> searchStep(c);
                case 2 -> prepareCurve(c);
                case 3 -> emitPoint(c);
                case 4 -> publish(c);
                default -> throw new IllegalStateException("unknown route phase");
            }
        }
        return c.done() ? WorkState.DONE : WorkState.MORE;
    }

    public RouteShape finish(Cursor cursor) {
        if (!cursor.done()) throw new IllegalStateException("route is incomplete");
        return cursor.completed;
    }

    private void boundaryStep(Cursor c) {
        var anchor = c.boundaryIndex == 0 ? c.first : c.last;
        int offset = gentle ? SEAM_OFFSETS[c.seamProbe] : 0;
        Point2q point = anchor.point();
        if (offset != 0) {
            // 左右段都从同一轴上取缝两侧的高点，各自独立调度也会得到同一个接缝高度。
            long u = anchor.index() * 2048L + offset;
            if (u < 0) {
                c.seamProbe++;
                return;
            }
            try {
                point = anchors.carrier(u / 2048, (int) (u % 2048) * 256);
            } catch (WallFailure outside) {
                if (outside.code() != FailureCode.OUT_OF_BOUNDS) throw outside;
                if (++c.seamProbe == SEAM_OFFSETS.length) finishBoundary(c, anchor);
                return;
            }
        }
        ColumnSample sample = sample(c, point, SampleKind.REFERENCE, offset == 0);
        if (sample == null) return;
        if (offset == 0) {
            c.seamGround = sample.groundTopY();
            c.seamWalkY = Math.addExact(c.seamGround, 20);
        } else {
            c.seamWalkY = Math.max(c.seamWalkY, sample.groundTopY() + 20 - Math.abs(offset) / 2);
        }
        if (!gentle || ++c.seamProbe == SEAM_OFFSETS.length) finishBoundary(c, anchor);
    }

    private void finishBoundary(Cursor c, Anchor anchor) {
        int y = gentle ? Math.min(c.seamWalkY, recipe.bounds().maxYExclusive() - 3) : c.seamWalkY;
        String identity = DeterministicHash.sha256((recipe.recipeHash() + ":boundary:" + anchor.index()
                + ":" + anchor.point().xQ8() + ":" + anchor.point().zQ8() + ":" + y).getBytes(StandardCharsets.UTF_8));
        var boundary = new Boundary(anchor.index(), anchor.point(), y, identity);
        c.seamProbe = 0;
        if (c.boundaryIndex++ == 0) { c.start = boundary; c.previousHeight = c.seamGround; }
        else { c.end = boundary; c.phase = 1; c.step = 1; }
    }

    private void searchStep(Cursor c) {
        if (c.step > 128) { c.phase = 2; return; }
        if (c.searchColumns >= 120000 && c.column == null) {
            c.controls.add(0L); c.previousOffsetQ8 = 0; c.previousDeltaQ8 = 0; c.degradation = 3; c.step++; return;
        }
        if (!c.candidateActive) {
            if (c.candidate == DELTAS.size()) { finishStep(c); return; }
            int delta = DELTAS.get(c.candidate);
            if (c.searchColumns >= 100000 && Math.abs(delta) > 4 || Math.abs(delta * 256L - c.previousDeltaQ8) > 8 * 256L) {
                c.candidate++; return;
            }
            // 新墙不奖励顶峰，用省下的侧点采样看远一点，给山腰和岸线绕行留空间。
            c.lookaheadLimit = c.searchColumns < 60000 ? (gentle ? 6 : 3) : 1;
            c.degradation = Math.max(c.degradation, c.searchColumns < 60000 ? 0 : c.searchColumns < 100000 ? 1 : 2);
            c.lookaheadLimit = Math.min(c.lookaheadLimit, 129 - c.step);
            c.candidateOffset = c.previousOffsetQ8 + delta * 256L;
            if (Math.abs(c.candidateOffset) > 512 * 256L
                    || Math.abs(c.candidateOffset) > (2048 - c.step * 16) * 512L
                    || Math.abs(delta) > (129 - c.step) * 8) { c.candidate++; return; }
            c.lookahead = 0; c.sampleIndex = 0; c.candidateScore = 0; c.candidatePreviousHeight = c.previousHeight;
            c.candidateClimb = 0;
            c.candidateActive = true;
        }
        int u = (c.step + c.lookahead) * 16;
        long rawOffset = c.candidateOffset + c.lookahead * DELTAS.get(c.candidate) * 256L;
        if (Math.abs(rawOffset) > 512 * 256L) { rejectCandidate(c); return; }
        Point2q center;
        try {
            center = position(c, u * 256, tapered(rawOffset, u));
            // A structure footprint is a hard obstacle: a candidate whose centre line enters (or
            // approaches within the margin of) any resolved structure box is rejected outright, so
            // the ridge jitters around the structure instead of cutting through it. The wall is
            // "宁断不悬空" — never穿模. This is the only planning-time detour hook; the fine pass
            // still re-checks the emitted polyline end-to-end.
            if (obstacles.blocked(center.xQ8(), center.zQ8(),
                    c.previousHeight - 30, c.previousHeight + 30)) { rejectCandidate(c); return; }
            Point2q prior = c.lookahead == 0 ? position(c, (c.step - 1) * 16 * 256, c.controls.getLast()) : c.lookaheadPoint;
            if (!heading(prior, center)) { rejectCandidate(c); return; }
            var offset = OFFSETS.get(c.sampleIndex);
            long dx = FixedMath.mulDiv(offset.xQ8() * 256, recipe.axis().exQ30(), 1L << 30)
                    - FixedMath.mulDiv(offset.zQ8() * 256, recipe.axis().ezQ30(), 1L << 30);
            long dz = FixedMath.mulDiv(offset.xQ8() * 256, recipe.axis().ezQ30(), 1L << 30)
                    + FixedMath.mulDiv(offset.zQ8() * 256, recipe.axis().exQ30(), 1L << 30);
            var point = new Point2q(center.xQ8() + dx, center.zQ8() + dz);
            if (!inside(point)) { rejectCandidate(c); return; }
            // REFERENCE, not COARSE: the ridge score reads groundTopY (ridge/prominence/slope/climb) and
            // waterTopY (the wet penalty), and a coarse probe answers neither honestly - it evaluates the
            // density router only, so its height is quantised to the noise cell height and it carries no
            // water at all, which silently zeroed the wet term. It is also not cheaper here: this planner
            // probes nine columns per candidate, not the full fine-sample band.
            ColumnSample value = sample(c, point, SampleKind.REFERENCE, false);
            if (value == null) return;
            c.samples[c.sampleIndex++] = value;
        } catch (WallFailure failure) {
            if (failure.code() != FailureCode.OUT_OF_BOUNDS) throw failure;
            rejectCandidate(c); return;
        }
        if (c.sampleIndex != (gentle ? 1 : 9)) return;
        var h = c.samples[0];
        long slope = Math.min(1024, Math.abs((long) h.groundTopY() - c.candidatePreviousHeight) * 256 / 16);
        // 累计爬升（0.1.0 补上实现）：只累加上坡，下坡不得奖励，刻度与 slope 一致（每格 16）。
        // slope 罚的是「任何单步高差」，这一项罚的是「一直往上爬」——同样爬 5 格，
        // 一步一歇与连爬五步在 slope 项上完全一样，靠这一项才分得开。
        // 与其余山脊权重一样读冻结快照：世界打开后改配置不得让后半段换一套评分。
        long climbDelta = h.groundTopY() - c.candidatePreviousHeight;
        if (climbDelta > 0) c.candidateClimb += climbDelta * 256 / 16;
        long turn = Math.abs(DELTAS.get(c.candidate) * 256L - c.previousDeltaQ8) / 16;
        long wet = h.waterTopY().isPresent() || h.upperCensored() ? 256 : 0;
        long departure = Math.abs(rawOffset) / 512;
        var cfg = local.plaincraft.greatwall.config.GreatWallConfig.parameters();
        long score = cfg.ridgeSlopeWeight() * slope
                + cfg.ridgeAccumulatedSlopeWeight() * c.candidateClimb
                + cfg.ridgeTurnWeight() * turn
                + cfg.ridgeWetWeight() * wet * (gentle ? 2 : 1)
                + cfg.ridgeDepartureWeight() * departure;
        if (!gentle) score += legacySummitScore(c, h, cfg);
        c.candidateScore += score * (c.lookahead == 0 ? 4 : c.lookahead == 1 ? 2 : 1);
        if (c.lookahead == 0) c.candidateFirstHeight = h.groundTopY();
        c.candidatePreviousHeight = h.groundTopY(); c.lookaheadPoint = center;
        if (++c.lookahead < c.lookaheadLimit) { c.sampleIndex = 0; return; }
        if (c.candidateScore > c.bestScore) {
            c.bestScore = c.candidateScore; c.bestCandidate = c.candidate;
            c.bestHeight = c.candidateFirstHeight; c.bestOffset = c.candidateOffset;
        }
        rejectCandidate(c);
    }

    /** 旧存档续建仍使用冻结的登顶权重；新配方不会为这些权重额外读八列。 */
    private static long legacySummitScore(Cursor c, ColumnSample h,
            local.plaincraft.greatwall.config.GreatWallConfig.Semantic cfg) {
        long ridge = Math.clamp(Math.floorDiv((2L * h.groundTopY() - c.samples[3].groundTopY() - c.samples[4].groundTopY()) * 256, 96), -256, 256);
        long sum = 0; for (int i = 1; i < 9; i++) sum += c.samples[i].groundTopY();
        long prominence = Math.clamp(Math.floorDiv((8L * h.groundTopY() - sum) * 256, 8 * 48), -256, 256);
        // 0.1.0 山顶宽度分界平衡：中心凸起量按「平坦山顶 / 窄刀锋」区分奖惩。
        // 凸起（prominence）是中心相对 8 个周边的突出度；ridge 是中心相对两侧（samples[3/4]）的高度。
        // 平坦山顶（凸起小）→ 奖励；窄刀锋（凸起大）→ 惩罚。
        // 读冻结快照而不是当前配置：世界打开后改配置不得让后半段路线按另一套山脊权重重算
        // （两半接缝处的路线会跳变）。摘要已随世界冻结，改了配置的世界会拒绝打开而不是生成一半。
        int flatThreshold = cfg.summitFlatThreshold();
        long centreLift = Math.abs(ridge); // 中心相对两侧的抬升，作为「山顶宽度」的代理
        long knifePart;
        if (centreLift <= flatThreshold) {
            knifePart = cfg.summitFlatReward() * centreLift;       // 平坦山顶：奖励，能走就走
        } else {
            knifePart = cfg.knifeRidgePenalty() * centreLift;      // 窄刀锋：惩罚，避开
        }
        // 对称性：中心两侧（samples[3] vs samples[4]）落差 = 斜坡，惩罚不对称。
        long asymmetry = Math.abs(c.samples[3].groundTopY() - c.samples[4].groundTopY());
        return cfg.ridgeWeight() * ridge
                + cfg.ridgeProminenceWeight() * prominence
                + knifePart
                + cfg.ridgeAsymmetryWeight() * asymmetry;
    }

    private static void rejectCandidate(Cursor c) { c.candidateActive = false; c.candidate++; }

    private static void finishStep(Cursor c) {
        if (c.bestCandidate < 0) { c.previousOffsetQ8 = 0; c.previousDeltaQ8 = 0; c.degradation = Math.max(c.degradation, 1); }
        else {
            c.previousOffsetQ8 = c.bestOffset; c.previousDeltaQ8 = DELTAS.get(c.bestCandidate) * 256L;
            c.previousHeight = c.bestHeight;
        }
        c.controls.add(tapered(c.previousOffsetQ8, c.step * 16));
        c.step++; c.candidate = 0; c.bestCandidate = -1; c.bestScore = Long.MIN_VALUE;
    }

    private ColumnSample sample(Cursor c, Point2q point, SampleKind kind, boolean boundary) {
        int x = FixedMath.floorBlock(point.xQ8()), z = FixedMath.floorBlock(point.zQ8());
        String key = x + ":" + z + ":" + kind;
        if (c.column == null) {
            if (boundary) c.boundaryColumns++; else c.searchColumns++;
            c.logicalColumns++;
            ColumnSample cached = c.ownerCache.get(key);
            if (cached != null) return cached;
            c.column = c.oracle.begin(x, z, kind);
            return null;
        }
        if (!c.column.done()) { c.oracle.advance(c.column, 1); return null; }
        var result = c.oracle.finish(c.column);
        c.column = null; c.ownerCache.put(key, result);
        return result;
    }

    private static long tapered(long residual, int u) {
        if (u <= 16 || u >= 2032) return 0;
        if (u <= 1792) return residual;
        return FixedMath.mulDiv(residual, 65536 - FixedMath.smoothQ16((u - 1792) * 256), 65536);
    }

    private Point2q position(Cursor c, int uQ8, long residual) {
        var p = c.carrierCache.computeIfAbsent(uQ8, u -> anchors.carrier(c.key.index(), u));
        return new Point2q(p.xQ8() - FixedMath.mulDiv(residual, recipe.axis().ezQ30(), 1L << 30),
                p.zQ8() + FixedMath.mulDiv(residual, recipe.axis().exQ30(), 1L << 30));
    }

    private boolean inside(Point2q p) {
        long bound = recipe.bounds().horizontalLimit() * 256L;
        return p.xQ8() >= -bound && p.xQ8() <= bound && p.zQ8() >= -bound && p.zQ8() <= bound;
    }

    private boolean heading(Point2q a, Point2q b) {
        long dx = b.xQ8() - a.xQ8(), dz = b.zQ8() - a.zQ8();
        double forward = dx * (double) recipe.axis().exQ30() + dz * (double) recipe.axis().ezQ30();
        double side = -dx * (double) recipe.axis().ezQ30() + dz * (double) recipe.axis().exQ30();
        return forward > 0 && StrictMath.abs(side) <= forward * StrictMath.tan(StrictMath.toRadians(40));
    }

    private void prepareCurve(Cursor c) {
        // Tangents are displacement over one 16-block cell, not per-world-block slopes.
        if (c.curveIndex == 0) {
            c.tangents = new long[129];
            for (int i = 1; i < 128; i++) c.tangents[i] = FixedMath.roundDiv(c.controls.get(i + 1) - c.controls.get(i - 1), 2);
            c.tangents[1] = 0;
            c.tangents[127] = 0;
        }
        int i = c.curveIndex;
        double a = 2 * c.controls.get(i) - 2 * c.controls.get(i + 1) + c.tangents[i] + c.tangents[i + 1];
        double b = -3 * c.controls.get(i) + 3 * c.controls.get(i + 1) - 2 * c.tangents[i] - c.tangents[i + 1];
        if (extreme(a, b, c.tangents[i], c.controls.get(i)) > 512 * 256) {
            c.tangents[i] = 0; c.tangents[i + 1] = 0;
        }
        if (++c.curveIndex == 128) {
            // Recheck all cells after shared tangent limiting.
            for (int e = 0; e < 128; e++) {
                if (!analyticHeading(c, e)) { c.fallback = true; c.degradation = 3; break; }
            }
            c.phase = 3;
        }
    }

    private static double extreme(double a, double b, double d, double e) {
        double max = StrictMath.max(StrictMath.abs(e), StrictMath.abs(a + b + d + e));
        if (a == 0) return b == 0 ? max : extremeAt(max, -d / (2 * b), a, b, d, e);
        double discriminant = 4 * b * b - 12 * a * d;
        if (discriminant < 0) return max;
        double root = StrictMath.sqrt(discriminant);
        return extremeAt(extremeAt(max, (-2 * b - root) / (6 * a), a, b, d, e), (-2 * b + root) / (6 * a), a, b, d, e);
    }

    private static double extremeAt(double max, double t, double a, double b, double d, double e) {
        return t > 0 && t < 1 ? StrictMath.max(max, StrictMath.abs(((a * t + b) * t + d) * t + e)) : max;
    }

    private boolean analyticHeading(Cursor c, int edge) {
        double b0 = c.first.lateralQ8(), b1 = c.last.lateralQ8();
        double m0 = c.first.tangentQ24() * (2048.0 * 256 / (1 << 24));
        double m1 = c.last.tangentQ24() * (2048.0 * 256 / (1 << 24));
        double a = 2 * b0 - 2 * b1 + m0 + m1, b = -3 * b0 + 3 * b1 - 2 * m0 - m1;
        double t = edge / 128.0;
        double ra = 2 * c.controls.get(edge) - 2 * c.controls.get(edge + 1) + c.tangents[edge] + c.tangents[edge + 1];
        double rb = -3 * c.controls.get(edge) + 3 * c.controls.get(edge + 1) - 2 * c.tangents[edge] - c.tangents[edge + 1];
        double qa = 3 * a / (128 * 128 * 2048.0) + 3 * ra / 16;
        double qb = (6 * a * t + 2 * b) / (128 * 2048.0) + 2 * rb / 16;
        double qc = (3 * a * t * t + 2 * b * t + m0) / 2048 + c.tangents[edge] / 16.0;
        double max = StrictMath.max(StrictMath.abs(qc), StrictMath.abs(qa + qb + qc));
        double root = qa == 0 ? -1 : -qb / (2 * qa);
        if (root > 0 && root < 1) max = StrictMath.max(max, StrictMath.abs((qa * root + qb) * root + qc));
        return max <= 256 * StrictMath.tan(StrictMath.toRadians(40));
    }

    private void emitPoint(Cursor c) {
        int u = c.points.size();
        long residual = 0;
        if (!c.fallback && u > 0 && u < 2048) {
            int edge = u / 16, t = u % 16;
            long t2 = (long) t * t, t3 = t2 * t;
            residual = FixedMath.roundDiv((2 * t3 - 48 * t2 + 4096) * c.controls.get(edge)
                    + (t3 - 32 * t2 + 256 * t) * c.tangents[edge]
                    + (-2 * t3 + 48 * t2) * c.controls.get(edge + 1)
                    + (t3 - 16 * t2) * c.tangents[edge + 1], 4096);
        }
        if (Math.abs(residual) > 512L * 256) {
            c.fallback = true; c.degradation = 3; c.points.clear(); return;
        }
        Point2q point = position(c, u * 256, residual);
        if (!inside(point)) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "route leaves frozen bounds");
        if (!c.fallback && obstacles.blocked(point.xQ8(), point.zQ8(),
                c.previousHeight - 30, c.previousHeight + 30)) {
            // A Hermite-interpolated point can graze a structure margin even when every control
            // point cleared it (the control-point rejection in searchStep only fires once per 16
            // blocks). Falling back to the straight carrier chord re-runs without interpolation;
            // the chord is the minimal-deviation path and is the last resort. Skip the check while
            // falling back so the chord cannot loop; a structure straddling the exact chord is
            // deferred to the break-interval pass rather than failing the whole route.
            c.fallback = true; c.degradation = 3; c.points.clear(); return;
        }
        if (!c.points.isEmpty() && !heading(c.points.getLast(), point)) {
            if (c.fallback) throw new WallFailure(FailureCode.INVALID_RESOURCE, "carrier chord violates heading");
            c.fallback = true; c.degradation = 3; c.points.clear(); return;
        }
        c.points.add(point);
        if (u == 2048) c.phase = 4;
    }

    private static void publish(Cursor c) {
        var lengths = ArcLength.accumulate(c.points);
        var value = new RouteShape(c.key, c.points, lengths, c.start, c.end, c.searchColumns, c.boundaryColumns, c.degradation, ZERO);
        c.completed = new RouteShape(c.key, c.points, lengths, c.start, c.end, c.searchColumns, c.boundaryColumns,
                c.degradation, DeterministicHash.sha256(CanonicalCodec.route(value)));
        c.ownerCache.clear();
        c.carrierCache.clear();
    }

    public static final class Cursor {
        private final SegmentKey key;
        private final NaturalTerrainOracle oracle;
        private Thread owner;
        private int phase, step, candidate, lookahead, sampleIndex, logicalColumns, searchColumns, boundaryColumns;
        private int boundaryIndex, seamProbe, seamGround, seamWalkY;
        private int degradation, lookaheadLimit, bestCandidate = -1, previousHeight, candidatePreviousHeight;
        private int candidateFirstHeight, bestHeight, curveIndex;
        private long previousOffsetQ8, previousDeltaQ8, bestScore = Long.MIN_VALUE, candidateScore, candidateOffset, bestOffset;
        /** 当前候选在自己那段前瞻里的累计爬升（只算上坡，刻度同 slope：每格 16）。 */
        private long candidateClimb;
        private boolean candidateActive, fallback;
        private Anchor first, last;
        private Boundary start, end;
        private Point2q lookaheadPoint;
        private NaturalTerrainOracle.ColumnCursor column;
        private final List<Long> controls = new ArrayList<>();
        private final List<Point2q> points = new ArrayList<>();
        private final Map<String, ColumnSample> ownerCache = new HashMap<>();
        private final Map<Integer, Point2q> carrierCache = new HashMap<>();
        private final ColumnSample[] samples = new ColumnSample[9];
        private long[] tangents;
        private RouteShape completed;

        private Cursor(SegmentKey key, NaturalTerrainOracle oracle) { this.key = key; this.oracle = Objects.requireNonNull(oracle); }
        private void claimOwner() {
            if (owner == null) owner = Thread.currentThread();
            else if (owner != Thread.currentThread()) throw new IllegalStateException("route cursor has a different owner");
        }
        public boolean done() { return completed != null; }
        public int logicalColumns() { return logicalColumns; }
    }
}
