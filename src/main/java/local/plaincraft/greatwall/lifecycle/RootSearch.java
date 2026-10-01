package local.plaincraft.greatwall.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** Pure, lane-owned root continuation. Native initialization occurs before the first advance. */
public final class RootSearch {
    private static final long BATCH_GUARD_NANOS = 5_000_000L;
    public record Selection(Axis axis, int radiusIndex, int angleIndex, int axisIndex, long ridgeScore,
            int columns, long maxBatchNanos, long over40msBatches) {}
    public record Progress(int phase, int candidate, int shortlisted, int footprintColumn,
            int axisIndex, int columns, long maxBatchNanos, long over40msBatches) {}
    public record Audit(String stage, Point3i origin, int radiusIndex, int angleIndex, int axisIndex,
            int wetColumns, int invalidSamples, int failedFoundations, int minY, int maxY, String result) {}
    private record Candidate(Point3i origin, int radius, int angle, int wet, int slope, long ridge) {}
    private static final int[] RADII = {800, 1000, 1250, 1500};
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
    private NaturalTerrainOracle.ColumnCursor column;
    private Thread owner;
    private int phase, candidateIndex, sampleIndex, selectedIndex, axisIndex, columns;
    private int wet, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, centerHeight;
    private int invalidSamples, failedFoundations;
    private long sum, axisScore, maxBatchNanos, over40msBatches;
    private boolean invalid, lastSupport;
    private Axis axis;
    private Selection best;

    public RootSearch(Point3i spawn, DimensionBounds bounds, List<ChunkKey> early,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation) {
        this.spawn = Objects.requireNonNull(spawn);
        this.bounds = Objects.requireNonNull(bounds);
        this.early = List.copyOf(early);
        this.foundation = Objects.requireNonNull(foundation);
        if (early.isEmpty()) throw new IllegalArgumentException("native spawn domain must be recorded");
    }

    public WorkState advance(NaturalTerrainOracle oracle, int primitiveLimit) {
        claimOwner();
        if (primitiveLimit < 1 || primitiveLimit > 64) throw new IllegalArgumentException("root batch limit 1..64");
        long started = System.nanoTime(), deadline = started + 25_000_000L;
        try {
            for (int operation = 0; operation < primitiveLimit && phase != 3; operation++) {
                if (Thread.currentThread().isInterrupted()) throw new WallFailure(FailureCode.CLOSED, "root search interrupted");
                // Yield before the next primitive in the guard window; completed batches
                // still include the full duration of any individually slow primitive.
                if (System.nanoTime() >= deadline - BATCH_GUARD_NANOS) break;
                switch (phase) {
                    case 0 -> coarseStep(oracle);
                    case 1 -> footprintStep(oracle);
                    case 2 -> axisStep(oracle);
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
        if (over40msBatches != 0) throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER, "root steady sampling exceeded 40ms");
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

    private void coarseStep(NaturalTerrainOracle oracle) {
        if (candidateIndex == RADII.length * 36) {
            shortlisted = candidates.stream().sorted(Comparator.comparingInt(Candidate::wet)
                    .thenComparingInt(Candidate::slope).thenComparing(Comparator.comparingLong(Candidate::ridge).reversed())
                    .thenComparingInt(Candidate::radius).thenComparingInt(Candidate::angle)).limit(8).toList();
            if (shortlisted.isEmpty()) throw notFound();
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
        if (++sampleIndex < 81) return;
        if (!invalid && wet * 10 <= 81 && max - min <= 12) {
            candidates.add(new Candidate(new Point3i(origin.x(), centerHeight, origin.z()), candidateIndex / 36,
                    candidateIndex % 36, wet, max - min, 81L * centerHeight - sum));
        }
        audit.add(new Audit("coarse", origin, candidateIndex / 36, candidateIndex % 36, -1,
                wet, invalidSamples, 0, min, max, !invalid && wet * 10 <= 81 && max - min <= 12 ? "SHORTLIST_ELIGIBLE" : "REJECTED"));
        candidateIndex++; resetSamples();
    }

    private void footprintStep(NaturalTerrainOracle oracle) {
        if (selectedIndex == shortlisted.size()) {
            if (best == null) throw notFound();
            phase = 3; return;
        }
        var current = shortlisted.get(selectedIndex);
        int x = current.origin().x() + sampleIndex / 48 - 24;
        int z = current.origin().z() + sampleIndex % 48 - 24;
        ColumnSample value = read(oracle, x, z, true);
        if (value == null) return;
        acceptHeight(value);
        if (!lastSupport) failedFoundations++;
        if (!lastSupport || value.waterTopY().isPresent()) invalid = true;
        if (++sampleIndex < 48 * 48) return;
        audit.add(new Audit("footprint", current.origin(), current.radius(), current.angle(), -1,
                wet, invalidSamples, failedFoundations, min, max, invalid || max - min > 12 ? "REJECTED" : "FOUNDATION_ELIGIBLE"));
        if (invalid || max - min > 12) { selectedIndex++; resetSamples(); return; }
        phase = 2; axisIndex = 0; resetSamples();
    }

    private void axisStep(NaturalTerrainOracle oracle) {
        var current = shortlisted.get(selectedIndex);
        if (axisIndex == AXIS_DEGREES.length) {
            selectedIndex++; phase = 1; resetSamples(); return;
        }
        if (sampleIndex == 0 && column == null) {
            axis = axis(current.origin(), axisIndex);
            long dx = (long) current.origin().x() - spawn.x(), dz = (long) current.origin().z() - spawn.z();
            if (dx * axis.exQ30() + dz * axis.ezQ30() <= 0 || !SpawnDomain.disjointFuture(axis, early)) {
                audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex,
                        0, 0, 0, 0, 0, "REJECTED_EARLY_DOMAIN_OR_HEADING"));
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
        if (++sampleIndex < 9) return;
        audit.add(new Audit("axis", current.origin(), current.radius(), current.angle(), axisIndex, wet,
                invalidSamples, 0, min, max, !invalid && wet == 0 ? "DRY_AXIS_ELIGIBLE" : "REJECTED"));
        if (!invalid && wet == 0 && (best == null || axisScore > best.ridgeScore())) {
            best = new Selection(axis, current.radius(), current.angle(), axisIndex, axisScore, 0, 0, 0);
        }
        axisIndex++; resetSamples();
    }

    private ColumnSample read(NaturalTerrainOracle oracle, int x, int z, boolean requireFoundation) {
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

    private void acceptHeight(ColumnSample value) {
        if (value.upperCensored() || value.quality() != Quality.REFERENCE) invalidSamples++;
        invalid |= value.upperCensored() || value.quality() != Quality.REFERENCE;
        if (value.waterTopY().isPresent()) wet++;
        min = Math.min(min, value.groundTopY()); max = Math.max(max, value.groundTopY());
        sum += value.groundTopY();
    }

    private void resetSamples() {
        invalidSamples = 0; failedFoundations = 0;
        sampleIndex = 0; wet = 0; min = Integer.MAX_VALUE; max = Integer.MIN_VALUE;
        sum = 0; axisScore = 0; invalid = false;
    }

    public Point3i ringPoint(int radiusIndex, int angleIndex) {
        if (radiusIndex < 0 || radiusIndex >= 4 || angleIndex < 0 || angleIndex >= 36) throw new IllegalArgumentException("root ring index");
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

    private boolean inside(int x, int z, int margin) {
        return Math.abs((long) x) + margin <= bounds.horizontalLimit() && Math.abs((long) z) + margin <= bounds.horizontalLimit();
    }

    private boolean inRing(Point3i origin) {
        long distance = FixedMath.distanceSquared(origin.x(), origin.z(), spawn.x(), spawn.z());
        return distance >= 800L * 800 && distance <= 1500L * 1500;
    }

    private void claimOwner() {
        if (owner == null) owner = Thread.currentThread();
        if (owner != Thread.currentThread()) throw new IllegalStateException("root continuation belongs to another lane");
    }

    private static WallFailure notFound() {
        return new WallFailure(FailureCode.ROOT_NOT_FOUND, "all frozen 800..1500 ring/footprint/axis candidates rejected");
    }
}
