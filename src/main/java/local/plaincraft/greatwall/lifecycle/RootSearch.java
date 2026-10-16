package local.plaincraft.greatwall.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BiPredicate;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.component.GroundEntranceContract;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** Pure, lane-owned root continuation. Native initialization occurs before the first advance. */
public final class RootSearch {
    private static final long BATCH_GUARD_NANOS = 5_000_000L;
    /**
     * Buildability limits of the root pad (48x48 = 2304 columns), not a purity test.
     *
     * <p>History, in both directions. An early revision tolerated 115 unsupported columns on
     * the theory that the fort foundation backfills them; that was wrong at the time, because
     * {@code ComponentAssembly} rejected a footprint the moment one column lacked support. The
     * revision after it moved to <b>zero</b> tolerance - and stayed there while the reference
     * walk read its density one cell (four blocks) low. That walk error hid every water column,
     * which made a perfectly dry, perfectly supported pad look common.
     *
     * <p>With the walk corrected (the cell index is derived from the top of the chunk grid,
     * matching {@code NoiseBridgeGameTests}' ground truth) the ring of a coastline world no
     * longer offers such a pad at all: measured {@code ring=144 invalid=94 gateRejected=32
     * historicPass=0 wet[min=0 avg=67 max=81] spread[min=13 avg=113 max=487]}. The historic
     * triple gate would have rejected every candidate, and the tight pad gate rejected the two
     * that survived it, so the wall was never sited on such a world.
     *
     * <p>A wall is not a building plot. It bridges water with its water gate, and an unbuildable
     * fort0 now degrades to a recorded gap instead of failing the session, so water and
     * unsupported columns only have to be <b>bounded</b> - 20% of the pad may lack support and
     * 30% may be water - while the ranking still prefers the driest, flattest, highest pad the
     * ring offers. Relief stays capped because a pad that spans a cliff cannot carry a fort at
     * all; the cap is now stated in blocks instead of being the historic flat 12.
     */
    static final int MAX_UNSUPPORTED_FOOTPRINT_CELLS = 460;
    private static final int MAX_WET_FOOTPRINT_CELLS = 691;
    private static final int MAX_FOOTPRINT_RELIEF = 32;
    /**
     * Ring candidates whose 48x48 footprint is measured before the search gives up.
     *
     * <p>The ranking puts the driest, flattest and highest candidates first and the
     * footprint phase walks it in batches of eight. This cap bounds what that costs:
     * 2304 reference columns per candidate. Candidates past the cap are never
     * measured, which is the price of a bounded search.
     */
    private static final int FOOTPRINT_CANDIDATE_LIMIT = 24;
    /**
     * Walk height assumed above ring-centre ground when pre-screening an entrance.
     *
     * <p>A pre-screen only. Construction derives its own walkY from the site's station,
     * and the two are not equal, so this value can admit an entrance the build will
     * reject. That is tolerable because the build no longer aborts on it - fort0 degrades
     * to a recorded gap - and because the offset is deliberately left at its historical
     * value until a session measures the real one. Guessing a replacement would move the
     * disagreement rather than remove it.
     *
     * @see #PRESCREEN_OFFSET_DIAGNOSTIC
     */
    private static final int PRESCREEN_WALK_ABOVE_GROUND = 20;
    /**
     * Logs the pre-screen's walk height against the ground it was measured from.
     *
     * <p>Off by default. Enable with {@code -Dgreatwall.root.prescreenDiagnostics=true}
     * to capture the numbers that would let {@link #PRESCREEN_WALK_ABOVE_GROUND} be
     * replaced with the offset construction actually uses.
     */
    private static final String PRESCREEN_OFFSET_DIAGNOSTIC = "greatwall.root.prescreenDiagnostics";
    public record Selection(Axis axis, int radiusIndex, int angleIndex, int axisIndex, long ridgeScore,
            int columns, long maxBatchNanos, long over40msBatches) {}
    public record Progress(int phase, int candidate, int shortlisted, int footprintColumn,
            int axisIndex, int columns, long maxBatchNanos, long over40msBatches) {}
    public record Audit(String stage, Point3i origin, int radiusIndex, int angleIndex, int axisIndex,
            int wetColumns, int invalidSamples, int failedFoundations, int minY, int maxY, String result) {}
    private record Candidate(Point3i origin, int radius, int angle, int wet, int slope, long ridge) {}
    /**
     * Ring radii in blocks, innermost first.
     *
     * <p>The ladder starts at 200 because the contract is "somewhere the wall can stand", not
     * "outside the spawn island". A river-cut coastline world has its dry, high ground <em>inside</em>
     * the ring, and the previous ladder (800..2500) could not reach it: measured on seed
     * 842680683514394380 the 800..1500 ring averages 67 wet columns out of 81 with a median surface
     * of y=85 in the chunks that do exist, while the land within 800 blocks - the same land the
     * road's villages occupy, 385..904 blocks out - stands at y=130..164 with 3.7% water. Ranking is
     * unchanged (driest, then flattest, then most ridge-like, then nearest), so a far dry ridge still
     * beats a near wet flat; the extra rings only widen what can be considered at all.
     */
    private static final int[] RADII = {200, 400, 600, 800, 1000, 1250, 1500, 2000, 2500};
    /** Ring count, shared with the structure screen so its candidate index check cannot drift. */
    public static final int RADIUS_COUNT = RADII.length;
    private static final int[] AXIS_DEGREES = {-40, -20, 0, 20, 40};
    private static final int[] AXIS_DISTANCES = {256, 512, 1024};
    private static final int[] AXIS_ACROSS = {0, -48, 48};
    private final Point3i spawn;
    private final DimensionBounds bounds;
    private final List<ChunkKey> early;
    private final BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation;
    private final ArrayList<Candidate> candidates = new ArrayList<>();
    private final ArrayList<Audit> audit = new ArrayList<>();
    private List<Candidate> shortlisted = List.of();
    private List<Candidate> structureCandidates = List.of();
    private List<Candidate> remainingScreened = List.of();
    private int screenedOffset;
    private Map<Integer,List<Integer>> allowedAxes = Map.of();
    private final boolean screenStructures;
    private final local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance sanctuaryAvoidance;
    private NaturalTerrainOracle.ColumnCursor column;
    private Thread owner;
    private int phase, candidateIndex, sampleIndex, selectedIndex, axisIndex, columns;
    private int wet, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, centerHeight;
    private int invalidSamples, failedFoundations;
    private long sum, axisScore, maxBatchNanos, over40msBatches;
    private boolean invalid, lastSupport;
    private Axis axis;
    private Selection best;
    private Frame entranceFrame;
    private List<Point3i> entrancePositions=List.of();
    private final Map<Point3i,ProbeResult> entranceSupports=new java.util.HashMap<>();
    private final Map<Point3i,local.plaincraft.greatwall.generation.SurfaceProbe.ColumnView> entranceColumns=new java.util.HashMap<>();
    private int entranceIndex,entranceMin,entranceMax;
    /** Diagnostic-only ring counters behind {@link #logCoarseSummary(int)}; never an input to selection. */
    private int coarseInvalid, coarseHistoric, coarseAdmitted, coarseRejected;
    private int coarseWetMin = Integer.MAX_VALUE, coarseWetMax = Integer.MIN_VALUE;
    private int coarseSpreadMin = Integer.MAX_VALUE, coarseSpreadMax = Integer.MIN_VALUE;
    private long coarseWetSum, coarseSpreadSum;

    public RootSearch(Point3i spawn, DimensionBounds bounds, List<ChunkKey> early,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation) {
        this(spawn,bounds,early,foundation,false,
                local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none());
    }
    public RootSearch(Point3i spawn, DimensionBounds bounds, List<ChunkKey> early,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation,boolean screenStructures) {
        this(spawn,bounds,early,foundation,screenStructures,
                local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none());
    }
    public RootSearch(Point3i spawn, DimensionBounds bounds, List<ChunkKey> early,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation,boolean screenStructures,
            local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance sanctuaryAvoidance) {
        this.spawn = Objects.requireNonNull(spawn);
        this.bounds = Objects.requireNonNull(bounds);
        this.early = List.copyOf(early);
        this.foundation = Objects.requireNonNull(foundation);
        this.screenStructures=screenStructures;
        this.sanctuaryAvoidance = sanctuaryAvoidance == null
                ? local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none() : sanctuaryAvoidance;
        if (early.isEmpty()) throw new IllegalArgumentException("native spawn domain must be recorded");
    }

    public WorkState advance(NaturalTerrainOracle oracle, int primitiveLimit) {
        claimOwner();
        if (primitiveLimit < 1 || primitiveLimit > 64) throw new IllegalArgumentException("root batch limit 1..64");
        long started = System.nanoTime(), deadline = started + 25_000_000L;
        try {
            for (int operation = 0; operation < primitiveLimit && phase != 3 && phase != 4; operation++) {
                if (Thread.currentThread().isInterrupted()) throw new WallFailure(FailureCode.CLOSED, "root search interrupted");
                // Yield before the next primitive in the guard window; completed batches
                // still include the full duration of any individually slow primitive.
                if (System.nanoTime() >= deadline - BATCH_GUARD_NANOS) break;
                switch (phase) {
                    case 0 -> coarseStep(oracle);
                    case 1 -> footprintStep(oracle);
                    case 2 -> axisStep(oracle);
                    case 5 -> entranceStep(oracle);
                    default -> throw new IllegalStateException("unknown root phase");
                }
                if (System.nanoTime() >= deadline) break;
            }
            return phase == 3 ? WorkState.DONE : WorkState.MORE;
        } finally {
            long elapsed = System.nanoTime() - started;
            maxBatchNanos = Math.max(maxBatchNanos, elapsed);
            if (elapsed > 40_000_000L) over40msBatches++;
        }
    }

    public Selection finish() {
        claimOwner();
        if (phase != 3 || best == null) throw new IllegalStateException("root search is incomplete");
        // ═══════════════════════════════════════════════════════════════════════════
        // 【用户明确授权的保留项 — 2026-10-05 拍板，禁止在未重新授权前回退】
        // 授权内容：移除 40ms 稳态采样的硬拒绝（原 `if (over40msBatches != 0) throw ...`）。
        // 授权理由（真机取证）：真实整合包 + 手动放大的道路/河流范围 + Streams 采样满载时，
        //   单批 advance 会被抢占而超过 40ms；原实现据此把整个 S1C 判失败，
        //   使长城在达标硬件上完全无法生成。
        // 保留的观测：maxBatchNanos / over40msBatches 仍如实记录，并写入 diagnostics 与
        //   Progress/Selection，只是不再作为选址的拒绝条件。
        // 已知代价（用户知情）：采样过慢不再阻止生成，慢只会表现为生成耗时变长。
        // 验收口径已同步调整：docs/release/acceptance.json 的 stress-24h 改为
        //   "slice timing observed (no hard 40ms gate)"，docs/S5.md 与 preview-status.md 同步。
        // 恢复条件：仅在用户重新授权后。
        // ═══════════════════════════════════════════════════════════════════════════
        return new Selection(best.axis(), best.radiusIndex(), best.angleIndex(), best.axisIndex(), best.ridgeScore(),
                columns, maxBatchNanos, over40msBatches);
    }

    public Progress progress() {
        claimOwner();
        return new Progress(phase, candidateIndex, shortlisted.size(), sampleIndex, axisIndex, columns,
                maxBatchNanos, over40msBatches);
    }
    /** Diagnostic-only, lane-owned copy. It is never an input to selection or persisted recipe identity. */
    public List<Audit> audit() { claimOwner(); return List.copyOf(audit); }
    public boolean needsStructureScreen(){claimOwner();return phase==4;}
    public List<RootStructureScreen.Candidate> pendingStructureCandidates(){
        claimOwner();
        if(phase!=4)throw new IllegalStateException("root is not waiting for structure votes");
        return structureCandidates.stream().map(c->new RootStructureScreen.Candidate(c.origin(),c.radius(),c.angle(),
                java.util.stream.IntStream.range(0,5).mapToObj(i->axis(c.origin(),i)).toList())).toList();
    }
    public void acceptStructureVotes(List<RootStructureScreen.Vote> votes){
        claimOwner();
        if(phase!=4||votes.size()!=structureCandidates.size())throw new IllegalArgumentException("complete pending root votes required");
        var map=new java.util.HashMap<Integer,List<Integer>>();
        for(int n=0;n<votes.size();n++){
            var candidate=structureCandidates.get(n);var vote=votes.get(n);
            if(vote.radiusIndex()!=candidate.radius()||vote.angleIndex()!=candidate.angle())
                throw new IllegalArgumentException("structure votes must preserve deterministic candidate order");
            map.put(candidate.radius()*36+candidate.angle(),vote.allowedAxes());
            for(int i=0;i<5;i++)audit.add(new Audit("structure",candidate.origin(),candidate.radius(),candidate.angle(),i,
                    0,0,0,candidate.origin().y(),candidate.origin().y(),
                    vote.allowedAxes().contains(i)?"STRUCTURE_CLEAR":vote.reasons().get(i)));
        }
        allowedAxes=Map.copyOf(map);
        remainingScreened=structureCandidates.stream().filter(c->!allowedAxes.get(c.radius()*36+c.angle()).isEmpty()).toList();
        shortlisted=remainingScreened.stream().limit(8).toList();screenedOffset=shortlisted.size();
        structureCandidates=List.of();
        if(shortlisted.isEmpty())throw notFound();
        phase=1;resetSamples();
    }

    private void coarseStep(NaturalTerrainOracle oracle) {
        if (candidateIndex == RADII.length * 36) {
            var ordered = candidates.stream().sorted(Comparator.comparingInt(Candidate::wet)
                    .thenComparingInt(Candidate::slope).thenComparing(Comparator.comparingLong(Candidate::ridge).reversed())
                    .thenComparingInt(Candidate::radius).thenComparingInt(Candidate::angle)).toList();
            logCoarseSummary(ordered.size());
            if(screenStructures){
                if(ordered.isEmpty())throw notFound();
                structureCandidates=ordered;phase=4;return;
            }
            if (ordered.isEmpty()) throw notFound();
            shortlisted=ordered.stream().limit(8).toList();
            // The historic wet/slope gate is now the RANKING instead of a filter: every candidate
            // that produced usable samples is measured, driest and flattest first, and the
            // footprint phase walks the ranking in batches. The gate rejected all 144 candidates
            // once the reference walk stopped reading its density one cell low.
            remainingScreened=ordered.stream().limit(FOOTPRINT_CANDIDATE_LIMIT).toList();
            screenedOffset=shortlisted.size();
            phase = 1; resetSamples(); return;
        }
        Point3i origin = ringPoint(candidateIndex / 36, candidateIndex % 36);
        if (!inRing(origin) || !inside(origin.x(), origin.z(), 32)) {
            candidateIndex++; resetSamples(); return;
        }
        int x = origin.x() + (sampleIndex / 9 - 4) * 8;
        int z = origin.z() + (sampleIndex % 9 - 4) * 8;
        ColumnSample value = read(oracle, x, z, false);
        if (value == null) return;
        acceptHeight(value);
        if (sampleIndex == 40) centerHeight = value.groundTopY();
        sampleIndex++;
        // (A) Exact-equivalence early exit: an unusable candidate is one with invalidSamples*8 > 81,
        // i.e. at least 11 unusable columns of 81, and that count only grows. Walking the remaining
        // columns cannot change the verdict, it only spends the walk - the ring is already refused.
        if (invalidSamples * 8 > 81) {
            coarseInvalid++;
            audit.add(new Audit("coarse", origin, candidateIndex / 36, candidateIndex % 36, -1,
                    wet, invalidSamples, 0, min, max, "REJECTED_INVALID"));
            candidateIndex++; resetSamples(); return;
        }
        if (sampleIndex < 81) return;
        boolean historic = !invalid && wet * 10 <= 81 && max - min <= 12;
        // Admission is wide, the ranking is strict. The historic gate (<=8 wet columns of 81, relief
        // <=12) rejected all 144 ring candidates once the reference walk stopped reading its density
        // one cell low and started seeing water at all; it is now only the head of the ranking. What
        // still rejects outright is a ring point that is mostly water or mostly relief - a wall is
        // not sited there - and samples that are individually unusable.
        //
        // Measured on a coastline ring: ring=144 invalid=94 ranked=2. A single unusable column out of
        // 81 discarded a candidate, because the flag is set by any one sample. The count is the
        // honest measure, so a rocky or cave-riddled ring point is no longer thrown away for one
        // censored column.
        boolean unusable = invalidSamples * 8 > 81;
        boolean admitted = !unusable && wet * 2 <= 81 && max - min <= 32;
        if (!unusable) noteCoarse(wet, max - min);
        if (admitted) {
            candidates.add(new Candidate(new Point3i(origin.x(), centerHeight, origin.z()), candidateIndex / 36,
                    candidateIndex % 36, wet, max - min, 81L * centerHeight - sum));
        } else if (unusable) {
            coarseInvalid++;
        } else {
            coarseRejected++;
        }
        if (historic) coarseHistoric++;
        audit.add(new Audit("coarse", origin, candidateIndex / 36, candidateIndex % 36, -1,
                wet, invalidSamples, 0, min, max,
                historic ? "SHORTLIST_ELIGIBLE" : admitted ? "RANKED_ONLY"
                        : unusable ? "REJECTED_INVALID" : "REJECTED"));
        candidateIndex++; resetSamples();
    }

    private void footprintStep(NaturalTerrainOracle oracle) {
        if (selectedIndex == shortlisted.size()) {
            // Walk the whole ranked list in batches, in both paths: the historic gate no longer
            // pre-selects eight candidates that are known to satisfy it.
            if(best==null&&screenedOffset<remainingScreened.size()){
                int end=Math.min(remainingScreened.size(),screenedOffset+8);
                shortlisted=List.copyOf(remainingScreened.subList(screenedOffset,end));
                screenedOffset=end;selectedIndex=0;resetSamples();return;
            }
            if (best == null) {
                logAxisSummary();
                throw notFound();
            }
            phase = 3; return;
        }
        var current = shortlisted.get(selectedIndex);
        int x = current.origin().x() + sampleIndex / 48 - 24;
        int z = current.origin().z() + sampleIndex % 48 - 24;
        ColumnSample value = read(oracle, x, z, true);
        if (value == null) return;
        acceptHeight(value);
        if (!lastSupport) failedFoundations++;
        // Bounded tolerated support and a bounded amount of water. Water is no longer an instant
        // rejection: a stream crossing the pad now costs tolerated columns - and at worst a recorded
        // gap at fort0 - instead of rejecting every candidate of a ring that happens to be
        // river-cut. The bound still keeps a fort out of a lake, and off ground that cannot carry it.
        if (failedFoundations > MAX_UNSUPPORTED_FOOTPRINT_CELLS) invalid = true;
        if (wet > MAX_WET_FOOTPRINT_CELLS) invalid = true;
        sampleIndex++;
        // (A) Exact-equivalence early exit: both tolerated bounds only grow while the pad is walked, so
        // once either is crossed the pad is refused whatever the remaining cells hold.
        if (invalid) {
            rejectFootprint(current, wet > MAX_WET_FOOTPRINT_CELLS,
                    failedFoundations > MAX_UNSUPPORTED_FOOTPRINT_CELLS, false);
            return;
        }
        if (sampleIndex < 48 * 48) return;
        boolean reliefOk = max - min <= MAX_FOOTPRINT_RELIEF;
        if (!reliefOk) {
            rejectFootprint(current, wet > MAX_WET_FOOTPRINT_CELLS,
                    failedFoundations > MAX_UNSUPPORTED_FOOTPRINT_CELLS, true);
            return;
        }
        audit.add(new Audit("footprint", current.origin(), current.radius(), current.angle(), -1,
                wet, invalidSamples, failedFoundations, min, max, "FOUNDATION_ELIGIBLE"));
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] ROOT_FOOTPRINT_ACCEPTED radius={} angle={} unsupported={}/{} wet={} spread={} height={}",
                current.radius(), current.angle(), failedFoundations, 48 * 48, wet, max - min, current.origin().y());
        phase = 2; axisIndex = 0; resetSamples();
    }

    /**
     * Records and logs a refused pad. The (A) early exit reaches it mid-walk as well, so the printed
     * counts are the ones that decided the refusal - not always the full 48x48 totals.
     */
    private void rejectFootprint(Candidate current, boolean waterFired, boolean supportFired, boolean reliefFired) {
        // Every refusal lands in the audit exactly once, whichever path reached it: the early exit
        // mid-walk, or the full 48x48 walk that only the relief check can still refuse.
        audit.add(new Audit("footprint", current.origin(), current.radius(), current.angle(), -1,
                wet, invalidSamples, failedFoundations, min, max, "REJECTED"));
        // A rejected pad has to say why it was rejected, otherwise the next session can only
        // repeat "no site" without saying whether the water, the support or the relief did it.
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] ROOT_FOOTPRINT_REJECTED radius={} angle={} unsupported={}/{} wet={}/{} spread={}/{} height={} water={} support={} relief={}",
                current.radius(), current.angle(), failedFoundations, 48 * 48, wet, 48 * 48,
                max - min, MAX_FOOTPRINT_RELIEF, current.origin().y(),
                waterFired, supportFired, reliefFired);
        selectedIndex++; resetSamples();
    }

    private void axisStep(NaturalTerrainOracle oracle) {
        var current = shortlisted.get(selectedIndex);
        if (axisIndex == AXIS_DEGREES.length) {
            selectedIndex++; phase = 1; resetSamples(); return;
        }
        if (sampleIndex == 0 && column == null) {
            if(screenStructures&&!allowedAxes.get(current.radius()*36+current.angle()).contains(axisIndex)){
                axisIndex++;resetSamples();return;
            }
            axis = axis(current.origin(), axisIndex);
            long dx = (long) current.origin().x() - spawn.x(), dz = (long) current.origin().z() - spawn.z();
            if (dx * axis.exQ30() + dz * axis.ezQ30() <= 0 || !SpawnDomain.disjointFuture(axis, early)) {
                audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex,
                        0, 0, 0, 0, 0, "REJECTED_EARLY_DOMAIN_OR_HEADING"));
                axisIndex++; resetSamples(); return;
            }
            if (crossesSanctuary(axis)) {
                audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex,
                        0, 0, 0, 0, 0, "REJECTED_SANCTUARY"));
                axisIndex++; resetSamples(); return;
            }
        }
        int distance = AXIS_DISTANCES[sampleIndex / 3];
        int across = AXIS_ACROSS[sampleIndex % 3];
        int x = Math.toIntExact(current.origin().x() + FixedMath.roundDiv(
                (long) distance * axis.exQ30() - (long) across * axis.ezQ30(), 1L << 30));
        int z = Math.toIntExact(current.origin().z() + FixedMath.roundDiv(
                (long) distance * axis.ezQ30() + (long) across * axis.exQ30(), 1L << 30));
        if (!inside(x, z, 0)) {
            audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex, wet,
                    invalidSamples, 0, min, max, "REJECTED_WORLD_BOUNDS"));
            axisIndex++; resetSamples(); return;
        }
        ColumnSample value = read(oracle, x, z, false);
        if (value == null) return;
        acceptHeight(value);
        if (sampleIndex % 3 == 0) centerHeight = value.groundTopY();
        else axisScore += centerHeight - (long) value.groundTopY();
        sampleIndex++;
        // (A) Exact-equivalence early exit: axisOk needs invalidSamples*3 <= 9 (at most three censored
        // columns of nine) and wet <= 3, and both counts only grow. A line that has already spent its
        // tolerance cannot recover, so the remaining columns would only be walk time.
        if (invalidSamples * 3 > 9 || wet > 3) {
            audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex, wet,
                    invalidSamples, 0, min, max, "REJECTED"));
            axisIndex++; resetSamples(); return;
        }
        if (sampleIndex < 9) return;
        // One wet column in nine is tolerated. The axis is a line the wall can bridge - that is what
        // its water gate is for - and the pad itself was already cleared by the footprint phase.
        // Zero tolerance here is what a water-blind probe made look free.
        // The axis is the line the wall itself occupies. Water on it is bridged by its water gate and a
        // censored column is not re-sampled anywhere else, so both are bounded rather than forbidden:
        // three wet columns of nine is a river crossing, not a lake. The count is used instead of the
        // flag for the same reason as in the coarse phase - one bad column must not discard a line.
        boolean axisOk = invalidSamples * 3 <= 9 && wet <= 3;
        audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex, wet,
                invalidSamples, 0, min, max, axisOk ? "DRY_AXIS_ELIGIBLE" : "REJECTED"));
        if (axisOk) {
            // The entrance frame's walk height, as a pre-screen only.
            //
            // Construction derives a site's walkY from its station - a terrain-contract
            // target clamped into an interval - which is not the same quantity as the ring
            // centre's ground height plus a constant. The two therefore disagree slightly,
            // and because GroundEntranceContract tests groundTopY < walkY, a pre-screen
            // walkY that is too high admits entrances construction then rejects. That is
            // measured: ENTRY_ELIGIBLE:lower=117 at selection, then "fort0 ground road has
            // no supported entrance" at build time.
            //
            // The exact relationship is not yet measured, so this deliberately keeps the
            // historical offset rather than guessing a replacement - a wrong constant would
            // move the disagreement instead of removing it, which is how the previous
            // attempt at this failed. What makes the mismatch harmless is that the build no
            // longer aborts on it: fort0 degrades to a recorded gap and the wall is built.
            // FORT0_ENTRANCE_PRESCREEN below records both heights so the real offset can be
            // read off a session and this constant replaced with evidence.
            entranceFrame=new Frame(new Point2q((long)axis.origin().x()*256,(long)axis.origin().z()*256),
                    axis.exQ30(),axis.ezQ30(),Math.addExact(axis.origin().y(),PRESCREEN_WALK_ABOVE_GROUND),false);
            entrancePositions=GroundEntranceContract.columns(entranceFrame,GroundEntranceContract.Corridor.fort());
            entranceSupports.clear();entranceColumns.clear();entranceIndex=0;
            entranceMin=Integer.MAX_VALUE;entranceMax=Integer.MIN_VALUE;phase=5;return;
        }
        axisIndex++; resetSamples();
    }

    private void entranceStep(NaturalTerrainOracle oracle){
        var current=shortlisted.get(selectedIndex);
        var p=entrancePositions.get(entranceIndex);
        if(!inside(p.x(),p.z(),0)){
            finishEntrance(current,OptionalInt.empty());return;
        }
        var value=read(oracle,p.x(),p.z(),true);
        if(value==null)return;
        int drop=Math.max(0,entranceFrame.walkY()-20-value.groundTopY());
        ProbeKind kind=value.upperCensored()||value.quality()!=Quality.REFERENCE?ProbeKind.UPPER_CENSORED:
                !lastSupport||drop>12?ProbeKind.NO_SUPPORT:value.waterTopY().isPresent()?ProbeKind.WATER:ProbeKind.SUPPORTED;
        String hash=DeterministicHash.sha256((oracle.fingerprint()+"\n"+p+"\n"+value+"\n"+lastSupport)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var probe=new ProbeResult(kind,value.groundTopY(),value.waterTopY(),value.bedTopY(),drop,hash);
        entranceSupports.put(p,probe);
        entranceColumns.put(new Point3i(p.x(),0,p.z()),new GroundEntranceContract.ClearSkyColumn(probe));
        entranceMin=Math.min(entranceMin,value.groundTopY());entranceMax=Math.max(entranceMax,value.groundTopY());
        if(++entranceIndex<entrancePositions.size())return;
        var evidence=new GroundEntranceContract.Evidence(GroundEntranceContract.Source.NATURAL_REFERENCE,bounds,entranceColumns);
        var certificate=GroundEntranceContract.inspect(entranceFrame,GroundEntranceContract.Corridor.fort(),entranceSupports,evidence);
        finishEntrance(current,certificate.isPresent()?OptionalInt.of(certificate.get().lowerFeetY()):OptionalInt.empty());
    }
    private void finishEntrance(Candidate current,OptionalInt lower){
        audit.add(new Audit("entrance-rotated-reference",current.origin(),current.radius(),current.angle(),axisIndex,
                0,0,0,entranceMin,entranceMax,lower.isPresent()?"ENTRY_ELIGIBLE:lower="+lower.getAsInt():"REJECTED"));
        if(Boolean.getBoolean(PRESCREEN_OFFSET_DIAGNOSTIC)){
            // The two heights this pre-screen has to approximate: what it assumed for the
            // walkway, and the ground it actually measured. A session with the property set
            // shows the real gap between them, which is what a corrected offset needs.
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] FORT0_ENTRANCE_PRESCREEN axis={} origin={} assumedWalkY={} groundMin={} "
                            + "groundMax={} offset={} lower={} accepted={}",
                    axis,current.origin(),entranceFrame==null?null:entranceFrame.walkY(),
                    entranceMin==Integer.MAX_VALUE?null:entranceMin,
                    entranceMax==Integer.MIN_VALUE?null:entranceMax,PRESCREEN_WALK_ABOVE_GROUND,
                    lower.isPresent()?lower.getAsInt():null,lower.isPresent());
        }
        if(lower.isPresent()&&(best==null||axisScore>best.ridgeScore()))
            best=new Selection(axis,current.radius(),current.angle(),axisIndex,axisScore,0,0,0);
        entranceSupports.clear();entranceColumns.clear();entrancePositions=List.of();
        axisIndex++;phase=2;resetSamples();
    }

    private ColumnSample read(NaturalTerrainOracle oracle, int x, int z, boolean requireFoundation) {
        // A prefetched column answers in one call instead of many. The values are
        // identical: every lane's oracle is constructed from the same frozen inputs
        // and verified against the same terrain fingerprint, so the noise is a pure
        // function of (x, z). Selection therefore cannot change.
        Prefetched hit = prefetched.remove(new ColumnKey(x, z, requireFoundation));
        if (hit != null) {
            if (column != null) {
                // A prefetch arrived for a position whose serial cursor is still open.
                // Dropping it keeps the serial path authoritative rather than mixing
                // two samplers for one column.
                prefetched.put(new ColumnKey(x, z, requireFoundation), hit);
            } else {
                if (++columns > 150000) throw new WallFailure(FailureCode.BUDGET_EXHAUSTED, "root column budget exceeds 150000");
                lastSupport = hit.lastSupport();
                return hit.sample();
            }
        }
        if (column == null) {
            if (++columns > 150000) throw new WallFailure(FailureCode.BUDGET_EXHAUSTED, "root column budget exceeds 150000");
            column = oracle.begin(x, z, SampleKind.REFERENCE);
            return null;
        }
        if (!column.done()) { oracle.advance(column, 1); return null; }
        ColumnSample result = oracle.finish(column);
        lastSupport = !requireFoundation || foundation.test(oracle, column);
        column = null;
        return result;
    }

    /**
     * A column sampled ahead of time on some lane, with the foundation verdict that
     * {@link #read} would have computed alongside it.
     *
     * <p>{@code lastSupport} is carried because it is a side effect of the serial
     * read: {@code footprintStep} counts a column as a failed foundation from it, so
     * a prefetched column that omitted the verdict would silently change the
     * accept/reject decision.
     */
    private record Prefetched(ColumnSample sample, boolean lastSupport) {}

    /** Identity of one column request. {@code requireFoundation} is part of it: the serial path computes two different things for the same coordinates. */
    public record ColumnKey(int x, int z, boolean requireFoundation) {}

    private final java.util.concurrent.ConcurrentHashMap<ColumnKey, Prefetched> prefetched =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Positions the current footprint candidate will still request.
     *
     * <p>Phase 1 is the only phase whose coordinates are pure arithmetic over the
     * candidate origin and {@code sampleIndex}, with no early-exit state in
     * between, so it is the one phase that can be enumerated exactly. The returned
     * list starts one past the in-flight column when a serial cursor is open,
     * because that column is already being sampled.
     *
     * <p>Deliberately does NOT call {@link #claimOwner()}: it is called from the
     * root pump to hand work to the other lanes, before lane 0 gets a turn. Taking
     * ownership here would bind the search to the pump thread and make every later
     * {@code advance} on lane 0 fail the ownership check.
     */
    public List<ColumnKey> pendingFootprintReads() {
        if (phase != 1 || shortlisted.isEmpty()) return List.of();
        var current = shortlisted.get(selectedIndex);
        int from = column == null ? sampleIndex : sampleIndex + 1;
        var result = new ArrayList<ColumnKey>(Math.max(0, 48 * 48 - from));
        for (int i = from; i < 48 * 48; i++) {
            result.add(new ColumnKey(current.origin().x() + i / 48 - 24,
                    current.origin().z() + i % 48 - 24, true));
        }
        return List.copyOf(result);
    }

    /**
     * Positions the coarse phase will still sample, for the prefetch lanes.
     *
     * <p>Phase 0 is also pure arithmetic over the candidate ring and {@code sampleIndex}: each
     * candidate samples an 81-point grid (9x9) around its ring origin, with no early-exit state
     * between points. The only skip is the {@code inRing}/{@code inside} rejection of a whole
     * candidate, which is a property of the origin alone, so an over-approximation that prefetches
     * a skipped candidate simply wastes a lane turn - it never changes the decision.
     *
     * <p>Enumerates the current candidate's remaining points plus the next candidate, so the
     * prefetch stays incremental instead of materialising all 144x81 coordinates at once.
     * Like {@link #pendingFootprintReads} it does not call {@link #claimOwner()}: it runs on the
     * root pump to hand work to the other lanes.
     */
    public List<ColumnKey> pendingCoarseReads() {
        if (phase != 0) return List.of();
        var result = new ArrayList<ColumnKey>(81 * 2);
        for (int ahead = 0; ahead < 2; ahead++) {
            int ci = candidateIndex + ahead;
            if (ci >= RADII.length * 36) break;
            var origin = ringPoint(ci / 36, ci % 36);
            if (!inRing(origin) || !inside(origin.x(), origin.z(), 32)) continue;
            int from = (ahead == 0 && column == null) ? sampleIndex : (ahead == 0 ? sampleIndex + 1 : 0);
            for (int s = from; s < 81; s++) {
                result.add(new ColumnKey(origin.x() + (s / 9 - 4) * 8,
                        origin.z() + (s % 9 - 4) * 8, false));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Samples one column to completion on the calling lane and caches it.
     *
     * <p>Deliberately does NOT call {@link #claimOwner()}: this runs on the other
     * lanes, while the search state itself stays owned by lane 0. The only shared
     * object touched is the prefetch map, which is concurrent by construction, and
     * the oracle, which belongs to the calling lane.
     *
     * <p>{@code advance(cursor, 64)} rather than one primitive per call: the loop
     * inside the oracle is identical, so the finished sample is bit-for-bit the
     * same, only reached in fewer calls.
     */
    public void prefetch(ColumnKey key, NaturalTerrainOracle oracle) {
        if (prefetched.containsKey(key)) return;
        var cursor = oracle.begin(key.x(), key.z(), SampleKind.REFERENCE);
        while (!cursor.done()) oracle.advance(cursor, 64);
        // The foundation test reads the cursor's support evidence and must run
        // before finish(), which releases it. The serial path does the same.
        boolean support = !key.requireFoundation() || foundation.test(oracle, cursor);
        var sample = oracle.finish(cursor);
        prefetched.put(key, new Prefetched(sample, support));
    }

    /** Columns sampled by a prefetch but never consumed, e.g. because the candidate was rejected early. */
    public int discardPrefetched() {
        int count = prefetched.size();
        prefetched.clear();
        return count;
    }

    private void acceptHeight(ColumnSample value) {
        if (value.upperCensored() || value.quality() != Quality.REFERENCE) invalidSamples++;
        invalid |= value.upperCensored() || value.quality() != Quality.REFERENCE;
        if (value.waterTopY().isPresent()) wet++;
        min = Math.min(min, value.groundTopY()); max = Math.max(max, value.groundTopY());
        sum += value.groundTopY();
    }

    private void noteCoarse(int wet, int spread) {
        coarseAdmitted++;
        coarseWetMin = Math.min(coarseWetMin, wet); coarseWetMax = Math.max(coarseWetMax, wet);
        coarseWetSum += wet;
        coarseSpreadMin = Math.min(coarseSpreadMin, spread); coarseSpreadMax = Math.max(coarseSpreadMax, spread);
        coarseSpreadSum += spread;
    }

    /**
     * One line per search describing what the ring actually offered.
     *
     * <p>Without it a rejected ring is invisible: the caller only ever sees "all frozen
     * &lt;inner&gt;..&lt;outer&gt; ring/footprint/axis candidates rejected", and there is no way to tell a
     * wet world from a steep one from a probe that returns nothing usable.
     */
    private void logCoarseSummary(int ranked) {
        if (ranked == 0) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                    "[GreatWall:S1C] ROOT_SCREEN_SUMMARY ring={} ranked=0 invalid={} gateRejected={} historicPass={} -- no ring point produced usable samples",
                    RADII.length * 36, coarseInvalid, coarseRejected, coarseHistoric);
            return;
        }
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] ROOT_SCREEN_SUMMARY ring={} ranked={} invalid={} gateRejected={} historicPass={} wet[min={} avg={} max={}] spread[min={} avg={} max={}]",
                RADII.length * 36, ranked, coarseInvalid, coarseRejected, coarseHistoric,
                coarseWetMin, coarseWetSum / rank(coarseAdmitted), coarseWetMax,
                coarseSpreadMin, coarseSpreadSum / rank(coarseAdmitted), coarseSpreadMax);
    }

    /** Divisor for the screen summary: the stats cover every candidate with usable samples. */
    private int rank(int measured) { return measured == 0 ? 1 : measured; }

    /**
     * One line naming why the axis stage refused, so an empty-handed search says more than "no site".
     *
     * <p>The axis phase only writes to the in-memory audit, which nothing prints. The 2026-10-16
     * session therefore reported two admitted candidates, no footprint rejection and no axis reason at
     * all, and the only way to tell a wet axis from a steep one was to re-derive it by hand.
     */
    private void logAxisSummary() {
        java.util.Map<String,Integer> reasons = new java.util.TreeMap<>();
        for (Audit row : audit) {
            if ("axis".equals(row.stage())) reasons.merge(row.result(), 1, Integer::sum);
        }
        if (reasons.isEmpty()) return;
        local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                "[GreatWall:S1C] ROOT_AXIS_SUMMARY screened={} reasons={}", screenedOffset, reasons);
    }

    private void resetSamples() {
        invalidSamples = 0; failedFoundations = 0;
        sampleIndex = 0; wet = 0; min = Integer.MAX_VALUE; max = Integer.MIN_VALUE;
        sum = 0; axisScore = 0; invalid = false;
        // Every candidate and phase transition passes through here, which makes it
        // the one place that reliably drops samples the search has moved past. A
        // rejected candidate can leave thousands of prefetched columns behind, and
        // holding them would trade the outage this fixes for a memory leak.
        int dropped = prefetched.size();
        if (dropped > 0) {
            prefetched.clear();
            discardedPrefetched.addAndGet(dropped);
        }
    }

    private final java.util.concurrent.atomic.AtomicLong discardedPrefetched =
            new java.util.concurrent.atomic.AtomicLong();

    /** Columns sampled by a prefetch that the search then moved past; pure diagnostics. */
    public long discardedPrefetchedCount() { return discardedPrefetched.get(); }

    /** Columns currently held in the prefetch cache; pure diagnostics. */
    public int prefetchedCount() { return prefetched.size(); }

    public Point3i ringPoint(int radiusIndex, int angleIndex) {
        if (radiusIndex < 0 || radiusIndex >= RADII.length || angleIndex < 0 || angleIndex >= 36) throw new IllegalArgumentException("root ring index");
        double angle = StrictMath.toRadians(angleIndex * 10);
        int x = Math.addExact(spawn.x(), Math.toIntExact(StrictMath.round(RADII[radiusIndex] * StrictMath.cos(angle))));
        int z = Math.addExact(spawn.z(), Math.toIntExact(StrictMath.round(RADII[radiusIndex] * StrictMath.sin(angle))));
        return new Point3i(x, spawn.y(), z);
    }

    private Axis axis(Point3i origin, int index) {
        double angle = StrictMath.atan2((long) origin.z() - spawn.z(), (long) origin.x() - spawn.x())
                + StrictMath.toRadians(AXIS_DEGREES[index]);
        return new Axis(origin, Math.toIntExact(StrictMath.round(StrictMath.cos(angle) * (1L << 30))),
                Math.toIntExact(StrictMath.round(StrictMath.sin(angle) * (1L << 30))));
    }

    /**
     * Rejects an axis whose infinite line passes within the sanctuary biome circle.
     * The wall may wander up to {@code carrierAmplitude + corridorHalfWidth} laterally
     * (384 + 512), so the axis must stay at least {@code radius + 384 + 512 + buffer}
     * away from the sanctuary centre.
     *
     * <p>The perpendicular distance from centre to the axis line is
     * {@code |dx*ez - dz*ex| / 2^30}, where (dx,dz) = centre - origin and
     * (ex,ez) is the Q30 unit axis direction.</p>
     */
    private boolean crossesSanctuary(Axis candidate) {
        if (!sanctuaryAvoidance.active()) return false;
        long dx = sanctuaryAvoidance.centerX() - candidate.origin().x();
        long dz = sanctuaryAvoidance.centerZ() - candidate.origin().z();
        long cross = Math.subtractExact(Math.multiplyExact(dx, candidate.ezQ30()),
                Math.multiplyExact(dz, candidate.exQ30()));
        long distanceQ30 = StrictMath.abs(cross);  // blocks * 2^30
        long safeQ30 = Math.multiplyExact(Math.addExact(sanctuaryAvoidance.radiusBlocks(), 384L + 512L + 64L), 1L << 30);
        return distanceQ30 < safeQ30;
    }

    private boolean inside(int x, int z, int margin) {
        return Math.abs((long) x) + margin <= bounds.horizontalLimit() && Math.abs((long) z) + margin <= bounds.horizontalLimit();
    }

    private boolean inRing(Point3i origin) {
        long distance = FixedMath.distanceSquared(origin.x(), origin.z(), spawn.x(), spawn.z());
        // The band is derived from RADII so the ladder and the band cannot drift apart, and so the
        // innermost ring is reachable at all (it used to be hardcoded to 800..2500).
        long inner = (long) RADII[0] * RADII[0];
        long outer = (long) RADII[RADII.length - 1] * RADII[RADII.length - 1];
        return distance >= inner && distance <= outer;
    }

    private void claimOwner() {
        if (owner == null) owner = Thread.currentThread();
        if (owner != Thread.currentThread()) throw new IllegalStateException("root continuation belongs to another lane");
    }

    private static WallFailure notFound() {
        return new WallFailure(FailureCode.ROOT_NOT_FOUND, "all frozen " + RADII[0] + ".."
                + RADII[RADII.length - 1] + " ring/footprint/axis candidates rejected");
    }
}
