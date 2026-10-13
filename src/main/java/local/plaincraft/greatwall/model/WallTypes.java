/* 文件职责：万里长城公共类型闭包 —— 全部纯模型 record/enum，零 Minecraft 依赖。 */
package local.plaincraft.greatwall.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * 冻结的公共类型闭包。所有嵌套类型均为纯数据（不可变），不 import Minecraft。
 * record 的 compact constructor 负责构造检查；集合字段统一深复制不可变。
 */
public final class WallTypes {

    private WallTypes() {
    }

    // ── 枚举 ────────────────────────────────────────────────

    public enum SiteKind { ENEMY_TOWER, BEACON, GATE, FORT, WATER_GATE, END_CAP, RAMP, WALL }

    public enum SiteState { PLANNED, ABSORBED, SUPPRESSED, BUILT }

    public enum DecayState { INTACT, PARTIAL, RUIN, BURIED }

    public enum PaletteFamily { STONE, DEEPSLATE, SANDSTONE }

    public enum BreakReason { SLOPE, NO_SUPPORT, STRUCTURE_CONFLICT, BURIED,
            RIVER_WIDE, HYDROLOGY_UNRESOLVED, WORLD_LIMIT, CONFLICT }

    public enum BreakScope { PLANNED, REALIZED }

    public enum SampleKind { COARSE, REFERENCE }

    public enum Quality { COARSE, REFERENCE, CENSORED }

    public enum WorkState { MORE, DONE }

    public enum Priority { PLAYER, ROAD, CHUNKY, PLAYER_PREFETCH, BACKGROUND }

    public enum TerminalKind { OCEAN, WORLD_LIMIT }

    public enum WaterClass { DRY, NARROW, WIDE, SUPPORT_GAP, UNRESOLVED }

    public enum ProbeKind { SUPPORTED, NO_SUPPORT, UPPER_CENSORED, PROTECTED, WATER }

    public enum Part { FOUNDATION, CORE, SKIN, FLOOR, STAIR, SLAB, PARAPET,
            ARCH, ROOF, RUBBLE, COVER, LAMP, CHAIN, FENCE, CAMPFIRE, HAY,
            CHEST, SPAWNER, WATER, AIR }

    public enum IntentKind { KEEP, SOLID, OWNED_VOID, FLUID }

    public enum WriteMode { INITIAL, GATE_BACKFILL }

    public enum WriteDecision { WRITE, ALREADY_TARGET, PROTECTED, CONFLICT }

    public enum FailureCode { OUT_OF_BOUNDS, ROOT_NOT_FOUND, RECIPE_MISMATCH,
            EXISTING_WORLD, BUDGET_EXHAUSTED, HYDROLOGY_UNRESOLVED, T0_MISSING,
            T0_CORRUPT, UNSUPPORTED_ADAPTER, OWNERSHIP_CONFLICT, IO_FAILED,
            STALE_SESSION, CLOSED, CAPACITY, INVALID_RESOURCE, MISSING_FEATURES_PLAN }

    // ── 基础坐标类型 ──────────────────────────────────────────

    public record Point3i(int x, int y, int z) {
        public Point3i {
            // 无额外约束：允许任意 int（含负），仅 y,z,x 排序用于 canonical sort。
        }
    }

    public record Point2q(long xQ8, long zQ8) {
        public Point2q {
            // Q8 定点坐标，允许负。
        }
    }

    public record ChunkKey(String dimension, int x, int z) {
        public ChunkKey {
            Objects.requireNonNull(dimension, "dimension");
            requireResourceKey(dimension);
        }
    }

    public record Bounds(int minX, int minY, int minZ,
                         int maxXExclusive, int maxYExclusive, int maxZExclusive) {
        public Bounds {
            if (maxXExclusive <= minX || maxYExclusive <= minY || maxZExclusive <= minZ) {
                throw new IllegalArgumentException("Bounds min must be < max");
            }
        }
    }

    public record DimensionBounds(int minY, int maxYExclusive, int horizontalLimit) {
        public DimensionBounds {
            if (maxYExclusive <= minY) throw new IllegalArgumentException("maxY>minY required");
            if (horizontalLimit <= 0) throw new IllegalArgumentException("horizontalLimit>0 required");
        }
    }

    public record Axis(Point3i origin, int exQ30, int ezQ30) {
        public Axis {
            Objects.requireNonNull(origin, "origin");
            requireDirection(exQ30, ezQ30);
        }
    }

    public record WorldKey(UUID session, String dimension) {
        public WorldKey {
            Objects.requireNonNull(session, "session");
            requireResourceKey(dimension);
        }
    }

    public record SegmentKey(String recipeHash, String dimension, long index) {
        public SegmentKey {
            requireHash(recipeHash);
            requireResourceKey(dimension);
            if (index < 0) throw new IllegalArgumentException("segment index>=0 required");
        }
    }

    public record SiteId(String wallId, SiteKind kind, long ordinal) {
        public SiteId {
            Objects.requireNonNull(wallId, "wallId");
            Objects.requireNonNull(kind, "kind");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal>=0 required");
        }
    }

    public record BeaconId(String wallId, long ordinal) {
        public BeaconId {
            Objects.requireNonNull(wallId, "wallId");
            if (ordinal < 1) throw new IllegalArgumentException("beacon ordinal>=1 required");
        }
    }

    public record GateId(String wallId, String namespace, long ordinal) {
        public GateId {
            Objects.requireNonNull(wallId, "wallId");
            if (!"planned".equals(namespace) && !"fort".equals(namespace) && !"negotiated".equals(namespace)) {
                throw new IllegalArgumentException("gate namespace must be planned|fort|negotiated");
            }
            if (ordinal < 0) throw new IllegalArgumentException("gate ordinal>=0 required");
        }
    }

    public record Terminal(long segment, long localSQ8, TerminalKind kind) {
        public Terminal {
            if (segment < 0) throw new IllegalArgumentException("terminal segment>=0 required");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record ArcInterval(long fromQ8, long toQ8) {
        public ArcInterval {
            if (toQ8 <= fromQ8) throw new IllegalArgumentException("arc interval to>from required");
        }
    }

    public record BreakInterval(ArcInterval range, BreakReason reason, BreakScope scope) {
        public BreakInterval {
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public record Frame(Point2q center, int txQ30, int tzQ30, int walkY, boolean mirror) {
        public Frame {
            Objects.requireNonNull(center, "center");
            requireDirection(txQ30, tzQ30);
        }
    }

    public record Anchor(long index, Point2q point, long lateralQ8, long tangentQ24) {
        public Anchor {
            if (index < 0) throw new IllegalArgumentException("anchor index>=0 required");
            Objects.requireNonNull(point, "point");
        }
    }

    public record Boundary(long index, Point2q point, int walkY, String identity) {
        public Boundary {
            if (index < 0) throw new IllegalArgumentException("boundary index>=0 required");
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(identity, "identity");
        }
    }

    public record ColumnSample(int groundTopY, OptionalInt waterTopY,
            OptionalInt bedTopY, String biomeKey, Quality quality, boolean upperCensored) {
        public ColumnSample {
            Objects.requireNonNull(waterTopY, "waterTopY");
            Objects.requireNonNull(bedTopY, "bedTopY");
            requireResourceKey(biomeKey);
            Objects.requireNonNull(quality, "quality");
        }
    }

    public record WaterEvidence(ArcInterval crossing, WaterClass classification,
            long channelWidthQ8, long crossingSpanQ8, int waterTopY,
            List<Point3i> banks, List<Point3i> piers, String inputHash) {
        public WaterEvidence {
            Objects.requireNonNull(crossing, "crossing");
            Objects.requireNonNull(classification, "classification");
            if (channelWidthQ8 < 0 || crossingSpanQ8 < 0) throw new IllegalArgumentException("width>=0 required");
            banks = List.copyOf(banks);
            piers = List.copyOf(piers);
            requireHash(inputHash);
        }
    }

    public record PrefixSummary(long beforeSegment, long lengthBeforeQ8,
            Optional<Terminal> terminal, String proofHash) {
        public PrefixSummary {
            if (beforeSegment < 0) throw new IllegalArgumentException("beforeSegment>=0 required");
            if (lengthBeforeQ8 < 0) throw new IllegalArgumentException("lengthBefore>=0 required");
            Objects.requireNonNull(terminal, "terminal");
            requireHash(proofHash);
        }
    }

    public record SiteRecord(SiteId id, long globalSQ8, Frame frame, Bounds bounds,
            SiteState state, Optional<SiteId> absorbedBy, Optional<BreakReason> reason) {
        public SiteRecord {
            Objects.requireNonNull(id, "id");
            if (globalSQ8 < 0) throw new IllegalArgumentException("globalSQ8>=0 required");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(absorbedBy, "absorbedBy");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Station(long globalSQ8, Point2q point, int walkY, int flags) {
        public Station {
            if (globalSQ8 < 0) throw new IllegalArgumentException("station globalSQ8>=0 required");
            Objects.requireNonNull(point, "point");
            if ((flags & ~(1 | 2 | 4 | 8 | 16)) != 0) throw new IllegalArgumentException("unknown station flags");
        }
    }

    public record StationTable(List<Station> stations, List<BreakInterval> breaks, String hash,
            Map<Long, String> biomeKeys) {
        public StationTable {
            stations = List.copyOf(stations);
            breaks = List.copyOf(breaks);
            requireHash(hash);
            long prev = Long.MIN_VALUE;
            for (Station s : stations) {
                if (s.globalSQ8() <= prev) throw new IllegalArgumentException("station globalSQ8 strictly increasing required");
                prev = s.globalSQ8();
            }
            var keys = new java.util.TreeMap<Long, String>();
            for (var entry : biomeKeys.entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 0) throw new IllegalArgumentException("biome station key >=0 required");
                if (entry.getValue() == null) throw new IllegalArgumentException("biome key required");
                keys.put(entry.getKey(), entry.getValue());
            }
            biomeKeys = java.util.Collections.unmodifiableMap(keys);
        }
        public StationTable(List<Station> stations, List<BreakInterval> breaks, String hash) {
            this(stations, breaks, hash, Map.of());
        }
    }

    public record RouteShape(SegmentKey key, List<Point2q> points, List<Long> localSQ8,
            Boundary start, Boundary end, int searchColumns, int boundaryColumns,
            int degradation, String hash) {
        public RouteShape {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            points = List.copyOf(points);
            localSQ8 = List.copyOf(localSQ8);
            if (points.size() < 2) throw new IllegalArgumentException("points>=2 required");
            if (points.size() != localSQ8.size()) throw new IllegalArgumentException("points/localSQ8 size mismatch");
            if (localSQ8.get(0) != 0) throw new IllegalArgumentException("localSQ8[0]==0 required");
            long prev = localSQ8.get(0);
            for (int i = 1; i < localSQ8.size(); i++) {
                if (localSQ8.get(i) <= prev) throw new IllegalArgumentException("localSQ8 strictly increasing required");
                prev = localSQ8.get(i);
            }
            if (searchColumns < 0 || searchColumns > 120000) throw new IllegalArgumentException("searchColumns 0..120000 required");
            if (boundaryColumns < 0 || boundaryColumns > 2) throw new IllegalArgumentException("boundaryColumns 0..2 required");
            if (degradation < 0 || degradation > 3) throw new IllegalArgumentException("degradation 0..3 required");
            requireHash(hash);
        }
    }

    public record RouteNeighborhood(RouteShape center, Optional<RouteShape> previous,
            Optional<RouteShape> next) {
        public RouteNeighborhood {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(next, "next");
            previous.ifPresent(value -> {
                if (center.key().index() == 0 || value.key().index() != center.key().index() - 1
                        || !value.key().recipeHash().equals(center.key().recipeHash())
                        || !value.key().dimension().equals(center.key().dimension())) {
                    throw new IllegalArgumentException("previous must be the adjacent segment of the same recipe");
                }
            });
            next.ifPresent(value -> {
                if (center.key().index() == Long.MAX_VALUE || value.key().index() != center.key().index() + 1
                        || !value.key().recipeHash().equals(center.key().recipeHash())
                        || !value.key().dimension().equals(center.key().dimension())) {
                    throw new IllegalArgumentException("next must be the adjacent segment of the same recipe");
                }
            });
        }
    }

    public record SegmentGeometry(SegmentKey key, List<Point2q> points,
            List<Long> localSQ8, List<WaterEvidence> water, Optional<Terminal> terminal,
            Boundary start, Boundary end, int logicalColumns, int degradation, String hash) {
        public SegmentGeometry {
            Objects.requireNonNull(key, "key");
            points = List.copyOf(points);
            localSQ8 = List.copyOf(localSQ8);
            water = List.copyOf(water);
            if (points.size() < 2) throw new IllegalArgumentException("points>=2 required");
            if (points.size() != localSQ8.size()) throw new IllegalArgumentException("points/localSQ8 size mismatch");
            if (localSQ8.get(0) != 0) throw new IllegalArgumentException("localSQ8[0]==0 required");
            long prev = localSQ8.get(0);
            for (int i = 1; i < localSQ8.size(); i++) {
                if (localSQ8.get(i) <= prev) throw new IllegalArgumentException("localSQ8 strictly increasing required");
                prev = localSQ8.get(i);
            }
            Objects.requireNonNull(terminal, "terminal");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (logicalColumns < 0 || logicalColumns > 150000) throw new IllegalArgumentException("logicalColumns 0..150000 required");
            if (degradation < 0 || degradation > 3) throw new IllegalArgumentException("degradation 0..3 required");
            requireHash(hash);
        }
    }

    public record SegmentPlan(SegmentGeometry geometry, PrefixSummary prefix,
            StationTable stations, List<SiteRecord> sites, String hash) {
        public SegmentPlan {
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(stations, "stations");
            sites = List.copyOf(sites);
            requireHash(hash);
        }
    }

    public record GeometryWindow(List<SegmentGeometry> segments, List<PrefixSummary> prefixes) {
        public GeometryWindow {
            segments = List.copyOf(segments);
            prefixes = List.copyOf(prefixes);
        }
    }

    public record PlanWindow(List<SegmentPlan> plans) {
        public PlanWindow {
            plans = List.copyOf(plans);
        }
    }

    public record ProjectionResult(long segment, int edge, long globalSQ8,
            long lateralQ8, Point2q nearest, int tangentXQ30, int tangentZQ30) {
        public ProjectionResult {
            if (segment < 0) throw new IllegalArgumentException("segment>=0 required");
            Objects.requireNonNull(nearest, "nearest");
            requireDirection(tangentXQ30, tangentZQ30);
        }
    }

    public record DecaySample(DecayState state, int amountQ16, int keepHeight) {
        public DecaySample {
            Objects.requireNonNull(state, "state");
        }
    }

    public record BlockSpec(String id, Map<String, String> properties) {
        public BlockSpec {
            requireResourceKey(id);
            properties = Map.copyOf(properties);
            for (Map.Entry<String, String> e : properties.entrySet()) {
                requireBoundedAscii(e.getKey());
                requireBoundedAscii(e.getValue());
            }
        }
    }

    public record VoxelIntent(Point3i pos, IntentKind kind, Part part, BlockSpec state,
            SiteId component, int priority, boolean required, boolean walkable) {
        public VoxelIntent {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(component, "component");
        }
    }

    public record BlockProof(Point3i pos, String beforeState, String afterState,
            boolean beforeHasBlockEntity, SiteId owner) {
        public BlockProof {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(beforeState, "beforeState");
            Objects.requireNonNull(afterState, "afterState");
            Objects.requireNonNull(owner, "owner");
        }
    }

    public record BlockEntityIntent(Point3i pos, String type, SiteId site, int localId,
            Map<String, String> fields) {
        public BlockEntityIntent {
            Objects.requireNonNull(pos, "pos");
            requireResourceKey(type);
            Objects.requireNonNull(site, "site");
            fields = Map.copyOf(fields);
        }
    }

    public record SpawnSlot(SiteId site, int slot, Point3i feet, String entityType) {
        public SpawnSlot {
            Objects.requireNonNull(site, "site");
            if (slot < 0) throw new IllegalArgumentException("slot>=0 required");
            Objects.requireNonNull(feet, "feet");
            requireResourceKey(entityType);
        }
    }

    public record ProbeResult(ProbeKind kind, int groundTopY, OptionalInt waterTopY,
            OptionalInt bedTopY, int drop, String evidenceHash, OptionalInt thinIceY) {
        public ProbeResult(ProbeKind kind,int groundTopY,OptionalInt waterTopY,OptionalInt bedTopY,int drop,String evidenceHash) {
            this(kind,groundTopY,waterTopY,bedTopY,drop,evidenceHash,OptionalInt.empty());
        }
        public ProbeResult {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(waterTopY, "waterTopY");
            Objects.requireNonNull(bedTopY, "bedTopY");
            requireHash(evidenceHash);
            Objects.requireNonNull(thinIceY,"thinIceY");
            if(thinIceY.isPresent()&&(kind!=ProbeKind.WATER||waterTopY.isEmpty()||thinIceY.getAsInt()+1!=waterTopY.getAsInt()))
                throw new IllegalArgumentException("thin ice must be the exposed water surface");
        }
    }

    public record CellKey(String recipeHash, String dimension, long arcCell) {
        public CellKey {
            requireHash(recipeHash);
            requireResourceKey(dimension);
        }
    }

    public record Coverage(ChunkKey chunk, List<Integer> tileBases, Bounds readBounds, String hash) {
        public Coverage {
            Objects.requireNonNull(chunk, "chunk");
            tileBases = List.copyOf(tileBases);
            Objects.requireNonNull(readBounds, "readBounds");
            requireHash(hash);
            int prev = Integer.MIN_VALUE;
            for (int b : tileBases) {
                if (Math.floorMod(b, 128) != 0) throw new IllegalArgumentException("tileBase must be 128-aligned");
                if (b <= prev) throw new IllegalArgumentException("tileBases strictly increasing required");
                prev = b;
            }
        }
    }

    public record ColumnEvidence(int x, int z, int highestNonPlantFeet, boolean upperCensored) {
        public ColumnEvidence {
        }
    }

    public record CaptureMetadata(Coverage coverage, List<ColumnEvidence> columns,
            List<Bounds> protectedStructures, Set<Point3i> blockEntities, Set<Point3i> immutableBlocks,
            String hash) {
        public CaptureMetadata {
            Objects.requireNonNull(coverage, "coverage");
            columns = List.copyOf(columns);
            protectedStructures = List.copyOf(protectedStructures);
            blockEntities = ImmutablePointSet.copyOf(blockEntities);
            immutableBlocks = ImmutablePointSet.copyOf(immutableBlocks);
            requireHash(hash);
        }
    }

    public record ComponentInput(SiteRecord site, List<Station> stations,
            Map<Point3i, ProbeResult> supports, DecaySample decay, PaletteFamily family,
            boolean redSandstone, List<WaterEvidence> waters,
            List<local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation> materials,
            local.plaincraft.greatwall.component.GroundEntranceContract.Evidence entranceEvidence) {
        /** Analytic fixture compatibility; production supplies its actual immutable column evidence. */
        public ComponentInput(SiteRecord site,List<Station> stations,Map<Point3i,ProbeResult> supports,
                DecaySample decay,PaletteFamily family,boolean redSandstone,List<WaterEvidence> waters,
                List<local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation> materials) {
            this(site,stations,supports,decay,family,redSandstone,waters,materials,
                    local.plaincraft.greatwall.component.GroundEntranceContract.Evidence.analytic(supports));
        }
        public ComponentInput {
            Objects.requireNonNull(site, "site");
            stations = List.copyOf(stations);
            supports = Map.copyOf(supports);
            Objects.requireNonNull(decay, "decay");
            Objects.requireNonNull(family, "family");
            waters = List.copyOf(waters);
            materials = List.copyOf(materials);
            Objects.requireNonNull(entranceEvidence,"entranceEvidence");
        }
    }

    public record ComponentVoxels(SiteId id, Bounds bounds, List<VoxelIntent> voxels,
            List<BlockEntityIntent> blockEntities, List<SpawnSlot> spawnSlots,
            List<List<Point3i>> requiredRoutes, DecayState realizedState, String hash) {
        public ComponentVoxels(SiteId id, Bounds bounds, List<VoxelIntent> voxels, List<BlockEntityIntent> blockEntities,
                List<SpawnSlot> spawnSlots, List<List<Point3i>> requiredRoutes, String hash) {
            this(id,bounds,voxels,blockEntities,spawnSlots,requiredRoutes,DecayState.INTACT,hash);
        }
        public ComponentVoxels {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            voxels = List.copyOf(voxels);
            blockEntities = List.copyOf(blockEntities);
            spawnSlots = List.copyOf(spawnSlots);
            requiredRoutes = requiredRoutes.stream().map(List::copyOf).toList();
            Objects.requireNonNull(realizedState,"realizedState");
            requireHash(hash);
        }
    }

    public record RealizationCell(CellKey key, List<BreakInterval> breaks,
            List<ComponentVoxels> components, String t0Hash, long gateRevision,
            String protectionHash, String hash) {
        public RealizationCell {
            Objects.requireNonNull(key, "key");
            breaks = List.copyOf(breaks);
            components = List.copyOf(components);
            requireHash(t0Hash);
            if (gateRevision < 0) throw new IllegalArgumentException("gateRevision>=0 required");
            requireHash(protectionHash);
            requireHash(hash);
        }
    }

    public record WriteMetadata(String coverageHash, long gateRevision, List<SpawnSlot> spawnSlots,
            Map<SiteId,DecayState> realizedStates) {
        public static final WriteMetadata EMPTY = new WriteMetadata("0".repeat(64),0,List.of(),Map.of());
        public WriteMetadata {
            requireHash(coverageHash);
            if(gateRevision<0) throw new IllegalArgumentException("gateRevision>=0 required");
            spawnSlots=List.copyOf(spawnSlots); realizedStates=Map.copyOf(realizedStates);
        }
    }

    public record ChunkWritePlan(ChunkKey owner, String recipeHash, String inputHash,
            List<VoxelIntent> voxels, List<BlockProof> proofs,
            List<BlockEntityIntent> blockEntities, List<Point3i> postprocess,
            List<Point3i> fluidTicks, List<Point3i> walkwayFeet, WriteMetadata metadata, String hash) {
        public ChunkWritePlan(ChunkKey owner,String recipeHash,String inputHash,List<VoxelIntent> voxels,List<BlockProof> proofs,
                List<BlockEntityIntent> blockEntities,List<Point3i> postprocess,List<Point3i> fluidTicks,List<Point3i> walkwayFeet,String hash) {
            this(owner,recipeHash,inputHash,voxels,proofs,blockEntities,postprocess,fluidTicks,walkwayFeet,WriteMetadata.EMPTY,hash);
        }
        public ChunkWritePlan {
            Objects.requireNonNull(owner, "owner");
            requireHash(recipeHash);
            requireHash(inputHash);
            voxels = List.copyOf(voxels);
            proofs = List.copyOf(proofs);
            blockEntities = List.copyOf(blockEntities);
            postprocess = List.copyOf(postprocess);
            fluidTicks = List.copyOf(fluidTicks);
            walkwayFeet = List.copyOf(walkwayFeet);
            Objects.requireNonNull(metadata,"metadata");
            requireHash(hash);
        }
    }

    public record WriteReport(ChunkKey owner, int written, int unchanged,
            List<Point3i> conflicts, boolean complete, String outputHash) {
        public WriteReport {
            Objects.requireNonNull(owner, "owner");
            if (written < 0 || unchanged < 0) throw new IllegalArgumentException("counts>=0 required");
            conflicts = List.copyOf(conflicts);
            requireHash(outputHash);
        }
    }

    public record SliceBudget(int maxOperations, long deadlineNanos) {
        public SliceBudget {
            if (maxOperations <= 0) throw new IllegalArgumentException("maxOperations>0 required");
        }
    }

    public record JobProgress(UUID id, String phase, long done, long total, boolean cancellable) {
        public JobProgress {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(phase, "phase");
            if (done < 0 || total < 0) throw new IllegalArgumentException("progress>=0 required");
        }
    }

    /**
     * The frozen numbers that decide what a world generates, plus the identity of the parameters
     * those numbers were produced with.
     *
     * <h2>Why the parameter digest lives here and not in {@link WorldRecipe}</h2>
     * The recipe's three hashes describe inputs (terrain, palette, templates); this record describes
     * the algorithm and the choices it was run with. Putting the digest with the config means every
     * record that already carries a {@code GenerationConfig} — the frozen recipe, the canonical recipe
     * hash, the creation token — carries the parameter identity too, without a new mechanism and
     * without a second place that could forget to include it. It is a digest of the
     * <em>parameters</em> rather than the values themselves so that adding a knob later cannot
     * silently escape the guard: the digest changes, or {@code GreatWallConfig.Semantic.SCHEMA} is
     * bumped on purpose.
     */
    public record GenerationConfig(int algorithmVersion, int templateVersion,
            int segmentBlocks, int stationBlocks, int columnBudget,
            int carrierAmplitude, int corridorHalfWidth, int maxGeometryInflight,
            long segmentBytes, long terrainBytes, long compiledBytes, String parametersHash) {
        public GenerationConfig {
            if (algorithmVersion <= 0 || templateVersion <= 0) throw new IllegalArgumentException("version>0 required");
            if (segmentBlocks <= 0 || stationBlocks <= 0 || columnBudget <= 0) throw new IllegalArgumentException("block/count>0 required");
            if (carrierAmplitude < 0 || corridorHalfWidth <= 0) throw new IllegalArgumentException("amplitude/corridor invalid");
            if (maxGeometryInflight <= 0) throw new IllegalArgumentException("maxGeometryInflight>0 required");
            if (segmentBytes <= 0 || terrainBytes <= 0 || compiledBytes <= 0) throw new IllegalArgumentException("bytes>0 required");
            requireHash(parametersHash);
        }
    }

    public record WorldRecipe(UUID instance, String wallId, long seed, String dimension,
            Point3i frozenSpawn, Axis axis, DimensionBounds bounds, GenerationConfig config,
            String terrainHash, String paletteHash, String templatesHash, String recipeHash) {
        public WorldRecipe {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(wallId, "wallId");
            requireResourceKey(dimension);
            Objects.requireNonNull(frozenSpawn, "frozenSpawn");
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(config, "config");
            requireHash(terrainHash);
            requireHash(paletteHash);
            requireHash(templatesHash);
            requireHash(recipeHash);
        }
    }

    // ── 共享校验 helper（跨包可见，供 geometry/decay/persistence 复用）──────

    public static void requireHash(String v) {
        if (v == null || !v.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("64-char lowercase hex hash required: " + v);
        }
    }

    public static void requireResourceKey(String v) {
        if (v == null || !v.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("namespace:path resource key required: " + v);
        }
    }

    public static void requireBoundedAscii(String v) {
        if (v == null || v.length() > 512) throw new IllegalArgumentException("bounded ascii required");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7E) throw new IllegalArgumentException("ascii required");
        }
    }

    static void requireDirection(int x, int z) {
        final long squared;
        try {
            squared = Math.addExact((long) x * x, (long) z * z);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("direction magnitude overflow", overflow);
        }
        if (Math.abs(squared - (1L << 60)) > (1L << 33)) {
            throw new IllegalArgumentException("direction magnitude must be ~2^30");
        }
    }

    /** 供同包与子包校验用的常量。 */
    static final java.util.List<String> GATE_NAMESPACES = List.of("planned", "fort", "negotiated");
}
