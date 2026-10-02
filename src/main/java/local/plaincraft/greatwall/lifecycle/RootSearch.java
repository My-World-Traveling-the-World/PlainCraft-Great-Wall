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
     * Footprint support tolerance: <b>zero</b>.
     *
     * <p>An earlier revision admitted up to 115 of 2304 columns without natural
     * support, on the theory that the fort foundation backfills them. That was
     * wrong: {@code ComponentAssembly} rejects an entire footprint the moment
     * one column lacks support (its drop/kind check), so a tolerated root could
     * never actually be built and construction failed later with "fort0
     * footprint is not fully supported".
     *
     * <p>The measured seed-17 audit shows why zero still finds a start: the
     * shortlist is scanned in preference order and contains a candidate with
     * {@code failedFoundations=0} (radius 2, angle 29) carrying two
     * {@code DRY_AXIS_ELIGIBLE} axes. Strictness makes the loop skip the
     * partially supported candidates ahead of it and select that one, so root
     * and the artifact templates now agree on the same contract.
     */
    private static final int MAX_UNSUPPORTED_FOOTPRINT_CELLS = 0;
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
    private List<Candidate> structureCandidates = List.of();
    private List<Candidate> remainingScreened = List.of();
    private int screenedOffset;
    private Map<Integer,List<Integer>> allowedAxes = Map.of();
    private final boolean screenStructures;
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

    public RootSearch(Point3i spawn, DimensionBounds bounds, List<ChunkKey> early,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation) {
        this(spawn,bounds,early,foundation,false);
    }
    public RootSearch(Point3i spawn, DimensionBounds bounds, List<ChunkKey> early,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation,boolean screenStructures) {
        this.spawn = Objects.requireNonNull(spawn);
        this.bounds = Objects.requireNonNull(bounds);
        this.early = List.copyOf(early);
        this.foundation = Objects.requireNonNull(foundation);
        this.screenStructures=screenStructures;
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
            if(screenStructures){
                if(ordered.isEmpty())throw notFound();
                structureCandidates=ordered;phase=4;return;
            }
            shortlisted=ordered.stream().limit(8).toList();
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
            if(best==null&&screenStructures&&screenedOffset<remainingScreened.size()){
                int end=Math.min(remainingScreened.size(),screenedOffset+8);
                shortlisted=List.copyOf(remainingScreened.subList(screenedOffset,end));
                screenedOffset=end;selectedIndex=0;resetSamples();return;
            }
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
        // Bounded tolerated support, and the SAME bound is honoured by
        // ComponentAssembly so an accepted root is always buildable. Water stays
        // zero-tolerance: a wet column terminates or gets a water gate instead.
        if (value.waterTopY().isPresent()) invalid = true;
        if (failedFoundations > MAX_UNSUPPORTED_FOOTPRINT_CELLS) invalid = true;
        if (++sampleIndex < 48 * 48) return;
        audit.add(new Audit("footprint", current.origin(), current.radius(), current.angle(), -1,
                wet, invalidSamples, failedFoundations, min, max,
                invalid || max - min > 12 ? "REJECTED" : "FOUNDATION_ELIGIBLE"));
        if (invalid || max - min > 12) { selectedIndex++; resetSamples(); return; }
        phase = 2; axisIndex = 0; resetSamples();
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
        if (!invalid && wet == 0) {
            entranceFrame=new Frame(new Point2q((long)axis.origin().x()*256,(long)axis.origin().z()*256),
                    axis.exQ30(),axis.ezQ30(),Math.addExact(axis.origin().y(),20),false);
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
        if(lower.isPresent()&&(best==null||axisScore>best.ridgeScore()))
            best=new Selection(axis,current.radius(),current.angle(),axisIndex,axisScore,0,0,0);
        entranceSupports.clear();entranceColumns.clear();entrancePositions=List.of();
        axisIndex++;phase=2;resetSamples();
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
