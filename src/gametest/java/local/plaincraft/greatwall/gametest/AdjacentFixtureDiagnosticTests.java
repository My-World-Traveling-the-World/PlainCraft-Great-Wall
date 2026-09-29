package local.plaincraft.greatwall.gametest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import local.plaincraft.greatwall.component.EnemyTower;
import local.plaincraft.greatwall.component.Gate;
import local.plaincraft.greatwall.component.WallSection;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.ChunkWallWriter;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.generation.WallStageCoordinator;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Diagnostic controls only; no change to the running 547-case matrix or production serializers. */
public final class AdjacentFixtureDiagnosticTests implements FabricGameTest {
    private static final String H = ComponentFixtures.H;
    private static final String DIMENSION = "minecraft:overworld";
    private static final int W = 84;
    private static final int GROUND = 64;
    private static final String AIR = BlockCatalog.token(Blocks.AIR.defaultBlockState(), false);
    private static final String STONE = BlockCatalog.token(Blocks.STONE.defaultBlockState(), false);
    private record Built(ComponentVoxels component, Map<Point3i, SurfaceProbe.ColumnView> columns) {}

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-fixture-diagnostic")
    public void flatGateMustConnectWithCapturedGroundNotAnAirBackground(GameTestHelper helper) {
        var wall = build(SiteKind.WALL, 100);
        var rawGate = build(SiteKind.GATE, 200);
        int wallEnd = wall.component().voxels().stream().mapToInt(v -> v.pos().x() + 1).max().orElseThrow();
        int gateStart = rawGate.component().voxels().stream().mapToInt(v -> v.pos().x()).min().orElseThrow();
        int dx = wallEnd - gateStart;
        require(dx == 62, "control no longer matches BOUNDARY index=12 translation");
        var gate = translate(rawGate, dx);
        var plans = plans(List.of(wall, gate));
        var writes = states(plans);
        var c = gate.component();
        require(c.requiredRoutes().size() == 4, "expected door, wall-top and two complete ramp routes");
        var door = c.requiredRoutes().get(0);
        var upper = c.requiredRoutes().get(1);
        require(door.equals(List.of(new Point3i(62, 64, -5), new Point3i(62, 64, 4))), "unexpected door anchors");
        require(upper.equals(List.of(new Point3i(33, 84, 0), new Point3i(91, 84, 0))), "unexpected wall-top anchors");

        var airOnly = new ComponentFixtures.CollisionView(writes);
        var independentRoutes = new ArrayList<Boolean>();
        for (var route : c.requiredRoutes()) {
            var reached = airOnly.reachable(route.getFirst(), c.bounds());
            independentRoutes.add(route.stream().allMatch(p -> reached.contains(foot(p))));
        }
        boolean airDoorToTop = airOnly.reachable(door.getFirst(), c.bounds()).contains(foot(upper.getFirst()));
        var upperReached = airOnly.reachable(upper.getFirst(), c.bounds());
        var rampLinks = c.requiredRoutes().subList(2, 4).stream()
                .map(r -> upperReached.contains(foot(r.getFirst()))).toList();

        var missingApron = new Point3i(62, 63, 5);
        require(!writes.containsKey(missingApron), "control apron was already part of the write set");
        require(ComponentFixtures.state(writes, pos(missingApron)).isAir(), "legacy collision view is not AIR outside writes");
        var column = gate.columns().get(new Point3i(missingApron.x(), 0, missingApron.z()));
        require(column != null && column.state(missingApron.y()).equals(STONE), "captured apron is not real fixture ground");

        var b = c.bounds();
        var bounds = new Bounds(b.minX() - 2, b.minY(), b.minZ() - 2,
                b.maxXExclusive() + 2, b.maxYExclusive(), b.maxZExclusive() + 2);
        var withTerrain = new HashMap<>(writes);
        for (int z = bounds.minZ(); z < bounds.maxZExclusive(); z++) for (int x = bounds.minX(); x < bounds.maxXExclusive(); x++) {
            var captured = gate.columns().get(new Point3i(x, 0, z));
            require(captured != null, "diagnostic may not guess a missing natural column");
            for (int y = bounds.minY(); y < bounds.maxYExclusive(); y++) {
                String token = captured.state(y);
                require(token.equals(STONE) || token.equals(AIR), "unexpected control T0 material");
                withTerrain.putIfAbsent(new Point3i(x, y, z), token.equals(STONE) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        writes.forEach((p, s) -> require(withTerrain.get(p).equals(s), "adding background changed a published write"));
        var complete = new ComponentFixtures.CollisionView(withTerrain);
        var doorReached = complete.reachable(door.getFirst(), bounds);
        var allFromDoor = c.requiredRoutes().stream().map(r -> r.stream().allMatch(p -> doorReached.contains(foot(p)))).toList();
        var legacyClosed=new HashMap<>(withTerrain);
        for(int z:new int[]{5,6,7,-6,-7,-8})for(int y=GROUND;y<GROUND+3;y++)
            legacyClosed.put(new Point3i(dx-30,y,z),Blocks.STONE_BRICKS.defaultBlockState());
        var legacyReach=new ComponentFixtures.CollisionView(legacyClosed).reachable(door.getFirst(),bounds);
        require(!legacyReach.contains(foot(upper.getFirst())),"old upper-rail foundation no longer reproduces the sealed ground entrance");
        var topWithTerrain = complete.reachable(upper.getFirst(), bounds);
        boolean exteriorGround = false;
        for (int z = bounds.minZ(); z < bounds.maxZExclusive(); z++) for (int x = bounds.minX(); x < bounds.maxXExclusive(); x++) {
            if (x < b.minX() || x >= b.maxXExclusive() || z < b.minZ() || z >= b.maxZExclusive())
                exteriorGround |= topWithTerrain.contains(foot(new Point3i(x, GROUND, z)));
        }
        var entryBoundaryStates = List.of(new Point3i(32, 64, 6), new Point3i(34, 64, 4), new Point3i(34, 64, 8), new Point3i(36, 65, 6))
                .stream().map(p -> p + "=" + BlockCatalog.token(ComponentFixtures.state(withTerrain, pos(p)), false)).toList();
        System.out.println("S1B_ADJACENCY_GATE_DIAGNOSTIC dx=" + dx + " routes=" + c.requiredRoutes()
                + " independentWriteRoutes=" + independentRoutes + " writeOnlyTopToRamp=" + rampLinks
                + " writeOnlyDoorToTop=" + airDoorToTop + " capturedT0DoorToRoutes=" + allFromDoor
                + " capturedT0TopToExteriorGround=" + exteriorGround + " lowerEntranceBoundary=" + entryBoundaryStates
                + " gateHash=" + c.hash() + " apron=" + missingApron + " apronT0=" + column.state(missingApron.y()));
        require(independentRoutes.stream().allMatch(Boolean::booleanValue), "Gate has an independently broken required route, not merely an AIR-background problem");
        require(rampLinks.stream().allMatch(Boolean::booleanValue), "Gate wall-top is disconnected from a ramp even before ground-apron traversal");
        require(!airDoorToTop, "the original AIR-only cross-route failure was not reproduced");
        require(allFromDoor.stream().allMatch(Boolean::booleanValue),
                "captured T0 did not repair cross-route connectivity; do not classify the Gate failure as a proven fixture-only false positive");
        require(exteriorGround, "Gate still cannot reach natural ground outside its full bounds with complete T0");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-fixture-diagnostic")
    public void nativeEnemyChestUsesCanonicalTokenAndActualEntityPosition(GameTestHelper helper) {
        for (var type : ChestType.values()) {
            String serialized = ChestBlock.TYPE.getName(type);
            require(!type.toString().equals(serialized), "legacy ChestType spelling counterexample disappeared: " + type);
            System.out.println("S1B_ADJACENCY_CHEST_ENUM java=" + type + " serialized=" + serialized);
        }
        require(ChestType.SINGLE.toString().equals("SINGLE") && ChestBlock.TYPE.getName(ChestType.SINGLE).equals("single"),
                "expected precise SINGLE versus single counterexample");

        var tower = build(SiteKind.ENEMY_TOWER, 200);
        var entity = tower.component().blockEntities().stream().filter(e -> e.type().equals("minecraft:chest")).findFirst().orElseThrow();
        require(entity.pos().equals(new Point3i(-1, 75, 0)), "control no longer reproduces the enemy chest at W-9");
        var plans = plans(List.of(tower));
        var plan = plans.get(owner(entity.pos()));
        require(plan != null && plan.blockEntities().contains(entity), "compiler dropped the actual chest entity intent");
        var chunk = new ProtoChunk(new ChunkPos(plan.owner().x(), plan.owner().z()), UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        for (var proof : plan.proofs()) {
            require(proof.beforeState().equals(STONE) || proof.beforeState().equals(AIR), "unexpected control before-state");
            chunk.setBlockState(pos(proof.pos()), proof.beforeState().equals(STONE) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
        }
        StageTrace.enterLockAction();
        try { require(new ChunkWallWriter().apply(chunk, plan, WriteMode.INITIAL).complete(), "native owner apply failed"); }
        finally { StageTrace.leaveLockAction(); }
        var actualEntities = chunk.getBlockEntitiesPos();
        require(actualEntities.contains(pos(entity.pos())), "real native chest entity position is missing");
        for (var proof : plan.proofs()) {
            var p = pos(proof.pos());
            String canonical = BlockCatalog.token(chunk.getBlockState(p), actualEntities.contains(p));
            require(canonical.equals(proof.afterState()), "canonical native state or real BE presence differs: " + proof.pos()
                    + " expected=" + proof.afterState() + " actual=" + canonical);
        }
        var chestProof = plan.proofs().stream().filter(p -> p.pos().equals(entity.pos())).findFirst().orElseThrow();
        var nativeState = chunk.getBlockState(pos(entity.pos()));
        require(nativeState.is(Blocks.CHEST) && nativeState.getValue(ChestBlock.TYPE) == ChestType.SINGLE, "native chest type is wrong");
        String canonical = BlockCatalog.token(nativeState, actualEntities.contains(pos(entity.pos())));
        String legacy = WallStageCoordinator.token(chunk, pos(entity.pos()));
        require(canonical.equals(chestProof.afterState()) && !legacy.equals(canonical), "legacy-versus-canonical proof mismatch was not reproduced");
        require(legacy.replace("type=SINGLE", "type=single").equals(canonical), "observed mismatch includes more than ChestType serialization");
        System.out.println("S1B_ADJACENCY_CHEST_TOKEN pos=" + entity.pos() + " nativeBE=" + actualEntities.contains(pos(entity.pos()))
                + " proof=" + chestProof.afterState() + " canonical=" + canonical + " legacy=" + legacy);
        helper.succeed();
    }

    private static Built build(SiteKind kind, long ordinal) {
        var bounds = switch (kind) {
            case WALL -> new Bounds(-32, -32, -5, 32, 3, 5);
            case GATE -> new Bounds(-30, -33, -15, 30, 4, 15);
            case ENEMY_TOWER -> new Bounds(-5, -33, -5, 6, 8, 6);
            default -> throw new IllegalArgumentException("unsupported diagnostic kind");
        };
        var base = ComponentFixtures.input(kind, bounds, 0, 0, 0, DecayState.INTACT);
        var site = new SiteRecord(new SiteId("adjacency", kind, ordinal), base.site().globalSQ8(), base.site().frame(),
                base.site().bounds(), SiteState.PLANNED, Optional.empty(), Optional.empty());
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        var supports = new HashMap<Point3i, ProbeResult>();
        var flat = new Flat(); var probe = new SurfaceProbe();
        for (int z = -72; z <= 72; z++) for (int x = -72; x <= 72; x++) {
            columns.put(new Point3i(x, 0, z), flat);
            supports.put(new Point3i(x, W, z), probe.probe(flat, W, new DimensionBounds(0, 128, 29_999_000)));
        }
        var input = new ComponentInput(site, base.stations(), supports, new DecaySample(DecayState.INTACT, 0, 20),
                PaletteFamily.STONE, false, List.of(), List.of());
        var decay = new DecayField(0);
        var component = switch (kind) {
            case WALL -> new WallSection(decay, ComponentFixtures.PALETTE).compile(input);
            case GATE -> new Gate(decay, ComponentFixtures.PALETTE).compile(input);
            case ENEMY_TOWER -> new EnemyTower(decay, ComponentFixtures.PALETTE).compile(input);
            default -> throw new IllegalArgumentException("unsupported diagnostic kind");
        };
        require(!component.voxels().isEmpty(), "diagnostic needs a real complete template");
        return new Built(component, Map.copyOf(columns));
    }

    private static Built translate(Built built, int dx) {
        var c = built.component();
        require(c.blockEntities().isEmpty() && c.spawnSlots().isEmpty(), "only the entity-free Gate control is translated");
        var voxels = c.voxels().stream().map(v -> new VoxelIntent(shift(v.pos(), dx), v.kind(), v.part(), v.state(),
                v.component(), v.priority(), v.required(), v.walkable())).toList();
        var routes = c.requiredRoutes().stream().map(r -> r.stream().map(p -> shift(p, dx)).toList()).toList();
        var b = c.bounds(); var bounds = new Bounds(b.minX() + dx, b.minY(), b.minZ(), b.maxXExclusive() + dx, b.maxYExclusive(), b.maxZExclusive());
        var draft = new ComponentVoxels(c.id(), bounds, voxels, List.of(), List.of(), routes, c.realizedState(), "0".repeat(64));
        var translated = new ComponentVoxels(c.id(), bounds, voxels, List.of(), List.of(), routes, c.realizedState(),
                DeterministicHash.sha256(CanonicalCodec.component(draft)));
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        built.columns().forEach((p, column) -> columns.put(shift(p, dx), column));
        return new Built(translated, Map.copyOf(columns));
    }

    private static Map<ChunkKey, ChunkWritePlan> plans(List<Built> built) {
        var cells = new ArrayList<RealizationCell>(); var before = new HashMap<Point3i, String>(); var owners = new HashSet<ChunkKey>();
        for (int n = 0; n < built.size(); n++) {
            var b = built.get(n); var c = b.component();
            cells.add(new RealizationCell(new CellKey(H, DIMENSION, n), List.of(), List.of(c), H, 0, H,
                    digest(List.of(c.hash()).toString())));
            for (var v : c.voxels()) {
                var column = b.columns().get(new Point3i(v.pos().x(), 0, v.pos().z()));
                require(column != null, "diagnostic write escaped captured columns");
                String state = column.state(v.pos().y()); String old = before.putIfAbsent(v.pos(), state);
                require(old == null || old.equals(state), "diagnostic T0 disagrees between components");
                if (v.kind() != IntentKind.KEEP) owners.add(owner(v.pos()));
            }
        }
        var result = new HashMap<ChunkKey, ChunkWritePlan>(); var compiler = new ChunkPlanCompiler();
        for (var owner : owners) result.put(owner, compiler.bindCoverage(compiler.compile(owner, H, cells, before), H));
        return Map.copyOf(result);
    }

    private static Map<Point3i, BlockState> states(Map<ChunkKey, ChunkWritePlan> plans) {
        var result = new HashMap<Point3i, BlockState>();
        for (var plan : plans.values()) for (var v : plan.voxels())
            require(result.put(v.pos(), BlockCatalog.resolveState(v.state())) == null, "diagnostic owners overlapped");
        return Map.copyOf(result);
    }

    private static final class Flat implements SurfaceProbe.ColumnView {
        public String state(int y) { return y < GROUND ? STONE : AIR; }
        public boolean solidSupport(int y) { return y < GROUND; }
        public boolean protectedAt(int y) { return false; }
        public boolean water(int y) { return false; }
        public boolean airOrReplaceablePlant(int y) { return y >= GROUND; }
        public int highestNonPlantFeet() { return GROUND; }
        public String hash() { return digest("adjacency:64:-1"); }
    }
    private static Point3i shift(Point3i p, int dx) { return new Point3i(p.x() + dx, p.y(), p.z()); }
    private static BlockPos pos(Point3i p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private static ComponentFixtures.Foot foot(Point3i p) { return new ComponentFixtures.Foot(p.x() * 2, p.y() * 2, p.z() * 2); }
    private static ChunkKey owner(Point3i p) { return new ChunkKey(DIMENSION, Math.floorDiv(p.x(), 16), Math.floorDiv(p.z(), 16)); }
    private static String digest(String value) { return DeterministicHash.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
