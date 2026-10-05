/* 文件职责：协商门服务 —— 道路软依赖先到时，在交点处冻结一个 4 宽 × 5 高永久门交易。 */
package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes;
import local.plaincraft.greatwall.model.WallTypes.BreakInterval;
import local.plaincraft.greatwall.model.WallTypes.BreakScope;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.GeometryWindow;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.Priority;
import local.plaincraft.greatwall.model.WallTypes.RealizationCell;
import local.plaincraft.greatwall.model.WallTypes.SegmentGeometry;
import local.plaincraft.greatwall.model.WallTypes.SegmentPlan;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.SiteRecord;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import local.plaincraft.greatwall.persistence.GateLedger;
import local.plaincraft.greatwall.planning.PlannerScheduler;

/**
 * 协商门服务。道路（软依赖 RoadWeaver）在路径终检 {@code PROTECTED_GAP} 之后调用本服务，
 * 在首次交墙点 ±192S 内优先复用已有合法门；无可复用门时按 cell 生成 GateId(wallId,
 * "negotiated", cell)，对候选按固定顺序校验后冻结。冻结后的门是永久叠加交易，先于该墙
 * chunk 的初建存在。关闭时只释放租约与调用者 future，保留已冻结永久门。
 */
public final class NegotiatedGateService implements AutoCloseable {

    /** 一段墙的弧长（Q8）：2048 块 × 256。 */
    private static final long SEGMENT_LEN_Q8 = 2048L * 256L;
    /** 一个协商 cell 的长度：128 块 × 256（Q8）。 */
    private static final long CELL_LEN_Q8 = 128L * 256L;
    /** 复用窗口 ±192S：192 块 × 256（Q8）。 */
    private static final long REUSE_WINDOW_S_Q8 = 192L * 256L;
    /** 与其它有效门的最小间距 64S：64 块 × 256（Q8）。 */
    private static final long MIN_GATE_GAP_S_Q8 = 64L * 256L;
    /** 候选 SQ8 依序偏移：0, ±16, ±32, ±48 块。 */
    private static final long[] CANDIDATE_OFFSET_BLOCKS = {0L, -16L, 16L, -32L, 32L, -48L, 48L};
    /** 固定孔径：4 宽 × 5 高。 */
    private static final int APERTURE_WIDTH = 4;
    private static final int APERTURE_HEIGHT = 5;
    private static final Set<String> STATUSES =
            Set.of("REUSED", "FROZEN_PENDING", "READY", "PROTECTED_GAP", "CANCELLED");

    /** 道路协商请求。构造时深复制，session/roadId/from/to 非空，completeBatch 不可空。 */
    public record Request(UUID session, String roadId, long topologyRevision,
            Point3i from, Point3i to, List<RoadTypes.Approach> completeApproaches,
            List<String> completeBatchRoadIds) {
        public Request {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(roadId, "roadId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            completeApproaches = deepCopyApproaches(completeApproaches);
            completeBatchRoadIds =
                    List.copyOf(Objects.requireNonNull(completeBatchRoadIds, "completeBatchRoadIds"));
        }

        private static List<RoadTypes.Approach> deepCopyApproaches(List<RoadTypes.Approach> in) {
            Objects.requireNonNull(in, "completeApproaches");
            List<RoadTypes.Approach> copy = new ArrayList<>(in.size());
            for (RoadTypes.Approach a : in) {
                Objects.requireNonNull(a, "approach");
                copy.add(new RoadTypes.Approach(a.roadId(), List.copyOf(a.points())));
            }
            return List.copyOf(copy);
        }
    }

    /** 协商结果。status 白名单 REUSED/FROZEN_PENDING/READY/PROTECTED_GAP/CANCELLED。 */
    public record Result(Optional<RoadTypes.GateRecord> gate, String status,
            List<String> rejectionReasons) {
        public Result {
            gate = gate == null ? Optional.empty()
                    : gate.map(RoadTypes.GateRecord::snapshot);
            status = Objects.requireNonNull(status, "status");
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("unknown status: " + status);
            }
            rejectionReasons =
                    List.copyOf(Objects.requireNonNull(rejectionReasons, "rejectionReasons"));
        }
    }

    private final WorldServices services;
    private final PlannerScheduler planner;
    private final GateLedger ledger;
    private final WallArchBackfill backfill;

    private final ConcurrentHashMap<UUID, PlanLease<?>> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Result>> callerFutures =
            new ConcurrentHashMap<>();
    /** 待 ack 关联：owner chunk → 该 chunk 已完成的 Realization。只在 prepared 时登记，不 ACK。 */
    private final ConcurrentHashMap<ChunkKey, RealizationCell> pendingAssociations =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    public NegotiatedGateService(WorldServices services, PlannerScheduler planner,
            GateLedger ledger, WallArchBackfill backfill) {
        this.services = Objects.requireNonNull(services, "services");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.backfill = Objects.requireNonNull(backfill, "backfill");
    }

    /**
     * 处理一次道路协商。只在 S2 真实 RoadWeaver 路径终检 PROTECTED_GAP 后调用，请求
     * stage 在 worldgen 锁外。返回的 CompletionStage 在服务关闭时以 CANCELLED 结束。
     */
    public CompletionStage<Result> request(Request request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);

        ChunkKey anchor = anchorChunk(request);
        PlanLease<PlanWindow> lease =
                planner.requireWindow(anchor, windowMargin(request), Priority.ROAD);
        UUID leaseId = lease.id();
        leases.put(leaseId, lease);

        CompletableFuture<Result> out = new CompletableFuture<>();
        UUID callId = UUID.randomUUID();
        callerFutures.put(callId, out);

        lease.future().thenCompose(window -> decide(request, window))
                .whenComplete((result, err) -> {
                    releaseLease(leaseId);
                    callerFutures.remove(callId);
                    if (err != null) {
                        out.completeExceptionally(err);
                    } else {
                        out.complete(result);
                    }
                });
        return out;
    }

    /**
     * 在交点 ±48 块内依序枚举候选 SQ8：cell 中心 (cell*128+64)*256，offset
     * [0,-16,+16,-32,+32,-48,+48]*256，限在 [0, terminal)。按完整候选列表与不可变输入
     * 判定，不取最先 chunk 就绪者。
     */
    public List<Long> candidateSQ8(long crossingSQ8, PlanWindow plans) {
        Objects.requireNonNull(plans, "plans");
        long cell = Math.floorDiv(crossingSQ8, CELL_LEN_Q8);
        long center = (cell * 128L + 64L) * 256L;
        long terminal = terminalSQ8(plans);
        List<Long> out = new ArrayList<>(CANDIDATE_OFFSET_BLOCKS.length);
        for (long offsetBlocks : CANDIDATE_OFFSET_BLOCKS) {
            long s = Math.addExact(center, Math.multiplyExact(offsetBlocks, 256L));
            if (s < 0L || s >= terminal) {
                continue;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /**
     * chunk 已完成 T0/Realization 后登记待 ack 关联。journal ACK 必须在 owner 实际
     * apply 成功且 proof 核对之后，绝不在仅 prepared 时 ACK。
     */
    public CompletionStage<Void> onChunkPrepared(ChunkKey owner, RealizationCell realization) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(realization, "realization");
        if (closed) {
            return CompletableFuture.failedFuture(
                    new WallFailure(WallTypes.FailureCode.CLOSED, "NegotiatedGateService closed"));
        }
        pendingAssociations.put(owner, realization);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 释放所有 plan 租约（只取消本服务的独占视图，不取消共享 planner 计算）。
        for (PlanLease<?> lease : leases.values()) {
            lease.close();
        }
        leases.clear();
        // 取消调用者 future：未提交 proposal 不可继续晚到 publish。
        for (CompletableFuture<Result> future : callerFutures.values()) {
            future.complete(new Result(Optional.empty(), "CANCELLED",
                    List.of("NegotiatedGateService closed")));
        }
        callerFutures.clear();
        pendingAssociations.clear();
        // 已冻结永久门保留：此处绝不触碰 ledger 或 backfill 的冻结状态。
    }

    // ------------------------------------------------------------------
    // 私有实现
    // ------------------------------------------------------------------

    private void validateRequest(Request request) {
        if (closed) {
            throw new WallFailure(WallTypes.FailureCode.CLOSED, "NegotiatedGateService closed");
        }
        int dx = Math.abs(Math.subtractExact(request.from().x(), request.to().x()));
        int dz = Math.abs(Math.subtractExact(request.from().z(), request.to().z()));
        if (dx > 2048 || dz > 2048) {
            throw new IllegalArgumentException("request domain exceeds 2048x2048");
        }
    }

    private ChunkKey anchorChunk(Request request) {
        WorldRecipe recipe = services.recipe();
        int mx = (request.from().x() + request.to().x()) / 2;
        int mz = (request.from().z() + request.to().z()) / 2;
        return new ChunkKey(recipe.dimension(), FixedMath.chunk(mx), FixedMath.chunk(mz));
    }

    private int windowMargin(Request request) {
        int corridor = services.recipe().config().corridorHalfWidth();
        return Math.min(512, Math.max(0, corridor + 4));
    }

    private void releaseLease(UUID leaseId) {
        PlanLease<?> lease = leases.remove(leaseId);
        if (lease != null) {
            lease.close();
        }
    }

    private CompletionStage<Result> decide(Request request, PlanWindow window) {
        GeometryWindow geo = buildWindow(window);
        Optional<Long> crossing = firstCrossing(request, geo);
        if (crossing.isEmpty()) {
            return completed(new Result(Optional.empty(), "PROTECTED_GAP",
                    List.of("no wall crossing in projection window")));
        }
        long terminal = terminalSQ8(window);
        if (crossing.get() >= terminal) {
            return completed(new Result(Optional.empty(), "PROTECTED_GAP",
                    List.of("crossing S=" + crossing.get() + " at or beyond terminal S=" + terminal)));
        }
        Optional<RoadTypes.GateRecord> reusable = findReusable(crossing.get(), request);
        if (reusable.isPresent()) {
            return completed(new Result(reusable, "REUSED", List.of()));
        }
        List<Long> candidates = candidateSQ8(crossing.get(), window);
        List<String> reasons = new ArrayList<>();
        for (long candidateS : candidates) {
            List<String> rs = validateCandidate(candidateS, window, request);
            if (rs.isEmpty()) {
                return negotiate(candidateS, request);
            }
            for (String r : rs) {
                reasons.add("candidate S=" + candidateS + ": " + r);
            }
        }
        return completed(new Result(Optional.empty(), "PROTECTED_GAP", List.copyOf(reasons)));
    }

    private GeometryWindow buildWindow(PlanWindow window) {
        List<SegmentGeometry> segments = new ArrayList<>();
        List<WallTypes.PrefixSummary> prefixes = new ArrayList<>();
        for (SegmentPlan plan : window.plans()) {
            segments.add(plan.geometry());
            prefixes.add(plan.prefix());
        }
        return new GeometryWindow(List.copyOf(segments), List.copyOf(prefixes));
    }

    private Optional<Long> firstCrossing(Request request, GeometryWindow geo) {
        try {
            long a = Projection.nearest(request.from().x(), request.from().z(), geo).globalSQ8();
            long b = Projection.nearest(request.to().x(), request.to().z(), geo).globalSQ8();
            return Optional.of(Math.min(a, b));
        } catch (WallFailure e) {
            // 无投影边：道路未交墙，不凭两端横跨判穿墙。
            return Optional.empty();
        }
    }

    private long terminalSQ8(PlanWindow window) {
        long terminal = Long.MAX_VALUE;
        for (SegmentPlan plan : window.plans()) {
            Optional<WallTypes.Terminal> t = plan.prefix().terminal();
            if (t.isPresent()) {
                long s = Math.addExact(
                        Math.multiplyExact(t.get().segment(), SEGMENT_LEN_Q8), t.get().localSQ8());
                terminal = Math.min(terminal, s);
            }
        }
        return terminal;
    }

    private Optional<RoadTypes.GateRecord> findReusable(long crossingS, Request request) {
        List<RoadTypes.GateRecord> reusable = new ArrayList<>();
        for (RoadTypes.GateRecord record : ledger.activeSnapshot().values()) {
            if (!isCommitted(record.state())) {
                continue;
            }
            long delta = Math.abs(record.contract().globalSQ8() - crossingS);
            if (delta <= REUSE_WINDOW_S_Q8) {
                reusable.add(record);
            }
        }
        reusable.sort(Comparator
                .comparingLong((RoadTypes.GateRecord r) ->
                        Math.abs(r.contract().globalSQ8() - crossingS))
                .thenComparing(r -> r.contract().id().wallId())
                .thenComparing(r -> r.contract().id().namespace())
                .thenComparingLong(r -> r.contract().id().ordinal()));
        for (RoadTypes.GateRecord record : reusable) {
            if (envelopeFits(record, request)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /** 完整 4 宽 road envelope 测试：门孔径至少 4×5，且道路地面落在孔径高度窗口内。 */
    private boolean envelopeFits(RoadTypes.GateRecord record, Request request) {
        if (record.contract().apertureWidth() < APERTURE_WIDTH
                || record.contract().apertureHeight() < APERTURE_HEIGHT) {
            return false;
        }
        int roadGround = Math.min(request.from().y(), request.to().y());
        int halfHeight = record.contract().apertureHeight() / 2;
        return Math.abs(roadGround - record.contract().groundY()) <= halfHeight;
    }

    private List<String> validateCandidate(long candidateS, PlanWindow window, Request request) {
        List<String> reasons = new ArrayList<>();
        // 1. 非 planned/realized break。
        for (SegmentPlan plan : window.plans()) {
            for (BreakInterval breakInterval : plan.stations().breaks()) {
                if (breakInterval.range().fromQ8() <= candidateS
                        && candidateS < breakInterval.range().toQ8()
                        && (breakInterval.scope() == BreakScope.PLANNED
                                || breakInterval.scope() == BreakScope.REALIZED)) {
                    reasons.add("falls in " + breakInterval.scope() + " break "
                            + breakInterval.reason());
                }
            }
        }
        // 2. 非水门/fort/tower 保护足印。
        for (SegmentPlan plan : window.plans()) {
            for (SiteRecord site : plan.sites()) {
                SiteKind kind = site.id().kind();
                if (kind == SiteKind.WATER_GATE || kind == SiteKind.FORT
                        || kind == SiteKind.ENEMY_TOWER) {
                    long delta = Math.abs(site.globalSQ8() - candidateS);
                    if (delta < MIN_GATE_GAP_S_Q8) {
                        reasons.add("within protected footprint of " + kind + " " + site.id());
                    }
                }
            }
        }
        // 3. 距其它有效门至少 64S。
        for (RoadTypes.GateRecord record : ledger.activeSnapshot().values()) {
            if (!isCommitted(record.state())) {
                continue;
            }
            long delta = Math.abs(record.contract().globalSQ8() - candidateS);
            if (delta < MIN_GATE_GAP_S_Q8) {
                reasons.add("within 64S of gate " + record.contract().id());
            }
        }
        // 4. 高度窗口与马道足印按同 Gate 模板：孔径固定 4×5，地面取道路地面，
        //    模板足印检查并入 2/3（与站点/已有门间距），无额外高度 oracle。
        return reasons;
    }

    private CompletionStage<Result> negotiate(long candidateS, Request request) {
        RoadTypes.GateContract contract = buildContract(candidateS, request);
        return ledger
                .propose(contract, request.completeApproaches(), request.completeBatchRoadIds(),
                        request.topologyRevision())
                .thenCompose(proposed -> ledger.freeze(proposed.contract().id(),
                        request.topologyRevision()))
                .thenCompose(frozen -> {
                    if (!isCommitted(frozen.state())) {
                        return completed(new Result(Optional.of(frozen), "PROTECTED_GAP",
                                List.of("gate " + frozen.contract().id()
                                        + " could not freeze: " + frozen.state())));
                    }
                    return backfill.prepare(frozen).handle((v, err) -> {
                        if (err != null) {
                            // 已冻结永久门保留；回填预检失败仅保持 PENDING，不吞失败为成功。
                            return new Result(Optional.of(frozen), "FROZEN_PENDING",
                                    List.of("backfill prepare failed: " + err.getMessage()));
                        }
                        boolean ready = allChunksMeasured(frozen);
                        return new Result(Optional.of(frozen),
                                ready ? "READY" : "FROZEN_PENDING", List.of());
                    });
                });
    }

    private RoadTypes.GateContract buildContract(long candidateS, Request request) {
        WorldRecipe recipe = services.recipe();
        long cell = Math.floorDiv(candidateS, CELL_LEN_Q8);
        GateId id = new GateId(recipe.wallId(), "negotiated", cell);
        Point2q center = centerAt(candidateS, recipe);
        int groundY = Math.min(request.from().y(), request.to().y());
        Frame frame = new Frame(center, recipe.axis().exQ30(), recipe.axis().ezQ30(), groundY, false);
        List<ChunkKey> affected = affectedChunks(center, recipe.dimension());
        String hash = DeterministicHash.sha256(canonicalContract(id, candidateS, frame, groundY));
        return new RoadTypes.GateContract(id, candidateS, frame, groundY, APERTURE_WIDTH,
                APERTURE_HEIGHT, affected, hash);
    }

    private Point2q centerAt(long s, WorldRecipe recipe) {
        WallTypes.Axis axis = recipe.axis();
        long ox = Math.multiplyExact(axis.origin().x(), 256L);
        long oz = Math.multiplyExact(axis.origin().z(), 256L);
        long dx = FixedMath.mulDiv(axis.exQ30(), s, 1L << 30);
        long dz = FixedMath.mulDiv(axis.ezQ30(), s, 1L << 30);
        return new Point2q(Math.addExact(ox, dx), Math.addExact(oz, dz));
    }

    private List<ChunkKey> affectedChunks(Point2q center, String dimension) {
        int cx = FixedMath.floorBlock(center.xQ8());
        int cz = FixedMath.floorBlock(center.zQ8());
        int x0 = FixedMath.chunk(cx - 2);
        int x1 = FixedMath.chunk(cx + 2);
        int z0 = FixedMath.chunk(cz - 2);
        int z1 = FixedMath.chunk(cz + 2);
        List<ChunkKey> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                out.add(new ChunkKey(dimension, x, z));
            }
        }
        return List.copyOf(out);
    }

    private byte[] canonicalContract(GateId id, long s, Frame frame, int groundY) {
        return Long.toString(DeterministicHash.fields(id.ordinal(), "negotiated-contract",
                s, frame.center().xQ8(), frame.center().zQ8(), frame.walkY(), groundY))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean isCommitted(RoadTypes.GateState state) {
        return state == RoadTypes.GateState.FROZEN
                || state == RoadTypes.GateState.PENDING_CHUNKS
                || state == RoadTypes.GateState.APPLIED;
    }

    private boolean allChunksMeasured(RoadTypes.GateRecord frozen) {
        for (Map.Entry<ChunkKey, RoadTypes.ChunkProgress> e : frozen.progress().entrySet()) {
            if (e.getValue().state() != RoadTypes.ChunkGateState.ACKED) {
                return false;
            }
        }
        return true;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
