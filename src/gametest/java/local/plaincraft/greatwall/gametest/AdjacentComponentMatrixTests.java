package local.plaincraft.greatwall.gametest;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.geometry.*;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.GroundExitCodec;
import local.plaincraft.greatwall.planning.HeightContractPlanner;
import local.plaincraft.greatwall.planning.SiteScheduler;
import local.plaincraft.greatwall.terrain.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Complete-template adjacency, distinct from isolated survival and wall-only curve tests. */
public final class AdjacentComponentMatrixTests implements FabricGameTest {
    private static final String H = ComponentFixtures.H;
    private static final String DIMENSION = "minecraft:overworld";
    private static final String WALL = "adjacency";
    private static final int W = 84;
    private static final List<SiteKind> KINDS = List.of(SiteKind.WALL, SiteKind.ENEMY_TOWER,
            SiteKind.BEACON, SiteKind.GATE, SiteKind.FORT, SiteKind.WATER_GATE, SiteKind.END_CAP, SiteKind.RAMP);
    private static final List<SiteKind> SITES = List.of(SiteKind.ENEMY_TOWER, SiteKind.BEACON, SiteKind.GATE, SiteKind.FORT);
    private static final Comparator<Point3i> POSITION = Comparator.comparingInt(Point3i::y)
            .thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private static final Comparator<ChunkKey> OWNER = Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z);

    private record Pair(SiteKind left, SiteKind right, boolean mixed, int gap) {}
    private record Hydro(SiteKind site, int width, boolean after, int gap) {}
    private record CapCase(SiteKind obstacle, boolean reverse, int gap) {}
    private enum TerminalPath { DIRECT, RETREAT, EXHAUSTED }
    private record TerminalCase(SiteKind obstacle, TerminalPath path) {}
    private record RejectionCase(SiteKind site, boolean protectedFootprint) {}
    private record GapCase(boolean leftSupport, boolean rightSupport, int islandLength) {}
    private record Curve(int bend,int slope) {}
    private record CurvePair(SiteKind left,SiteKind right,Curve curve) {}
    private record Built(ComponentVoxels component, Map<Point3i, SurfaceProbe.ColumnView> columns) {}
    private record Resolved(List<RealizationCell> cells, Map<Long, List<GroundExitDecision>> decisions,
            Map<Long, GroundExitDecision.Slice> surveys, Map<GroundExitDecision.Edge, GroundExitDecision.CapVote> votes) {}

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-matrix")
    public void orderedTouchAndOneBlockBoundaryMatrix(GameTestHelper helper) {
        var cases = new ArrayList<Pair>();
        for (var left : KINDS) for (var right : KINDS) for (boolean mixed : List.of(false, true))
            for (int gap : new int[]{0, 1}) cases.add(new Pair(left, right, mixed, gap));
        require(cases.size() == 256, "8 x 8 x 2 profiles x 2 boundaries");
        matrix(helper, "BOUNDARY", cases, 0, new ArrayList<>(), test -> {
            var a = build(test.left(), 100, 0, test.mixed());
            var b = build(test.right(), 200, test.mixed() ? 45 : 0, test.mixed());
            int dx = occupied(a.component()).maxXExclusive() + test.gap() - occupied(b.component()).minX();
            b = translate(b, dx, 0, 0);
            require(occupied(b.component()).minX() - occupied(a.component()).maxXExclusive() == test.gap(),
                    "boundary is measured from complete occupied voxels, not padded template bounds");
            var cells = List.of(cell(0, List.of(a.component())), cell(1, List.of(b.component())));
            verifyMerged(helper, cells, before(List.of(a, b)), boundaryColumns(a, b), true);
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-matrix")
    public void orderedRequiredClearanceConflictMatrix(GameTestHelper helper) {
        var cases = new ArrayList<Pair>();
        for (var left : KINDS) for (var right : KINDS) for (boolean crossed : List.of(false, true))
            cases.add(new Pair(left, right, crossed, 0));
        require(cases.size() == 128, "8 x 8 x 2 full-template collision cases");
        matrix(helper, "CONFLICT", cases, 0, new ArrayList<>(), test -> {
            var a = build(test.left(), 100, 0, false);
            var b = build(test.right(), 200, test.mixed() ? 45 : 0, false);
            if (test.left() == SiteKind.WALL && test.right() == SiteKind.WALL) {
                // Parallel duplicate coverage and a genuine 45-degree crossing are separate inputs.
                verifyMerged(helper, List.of(cell(0, List.of(a.component())), cell(1, List.of(b.component()))),
                        before(List.of(a, b)), a.columns(), true);
                return;
            }
            boolean voidOnLeft = test.right() != SiteKind.WALL;
            var empty = (voidOnLeft ? a : b).component().voxels().stream()
                    .filter(v -> v.kind() == IntentKind.OWNED_VOID && v.required())
                    .min(Comparator.comparing(VoxelIntent::pos, POSITION)).orElseThrow();
            var solid = (voidOnLeft ? b : a).component().voxels().stream()
                    .filter(v -> v.kind() == IntentKind.SOLID && v.required())
                    .min(Comparator.comparing(VoxelIntent::pos, POSITION)).orElseThrow();
            Point3i anchor = empty.pos();
            int dx = voidOnLeft ? anchor.x() - solid.pos().x() : solid.pos().x() - anchor.x();
            int dy = voidOnLeft ? anchor.y() - solid.pos().y() : solid.pos().y() - anchor.y();
            int dz = voidOnLeft ? anchor.z() - solid.pos().z() : solid.pos().z() - anchor.z();
            b = translate(b, dx, dy, dz);
            if (!voidOnLeft) anchor = solid.pos();
            final Point3i witness = anchor;
            var av = at(a.component(), witness); var bv = at(b.component(), witness);
            require(av != null && bv != null && av.required() && bv.required(), "complete-template collision witness exists");
            require((av.kind() == IntentKind.OWNED_VOID && bv.kind() == IntentKind.SOLID)
                    || (bv.kind() == IntentKind.OWNED_VOID && av.kind() == IntentKind.SOLID), "mandatory air versus solid witness");
            var air = av.kind() == IntentKind.OWNED_VOID ? av : bv;
            var obstruction = air == av ? bv : av;
            require(obstruction.component().kind() != SiteKind.WALL, "wall carving exemption cannot certify this rejection");
            var cells = List.of(cell(0, List.of(a.component())), cell(1, List.of(b.component())));
            var before = new HashMap<Point3i, String>();
            cells.forEach(c -> c.components().forEach(v -> v.voxels().forEach(p -> before.put(p.pos(), Column.AIR))));
            var owner = owner(witness);
            for (var order : List.of(cells, cells.reversed())) {
                try {
                    new ChunkPlanCompiler().compile(owner, H, order, before);
                    throw new AssertionError("accepted forbidden mandatory clearance collision at " + witness);
                } catch (WallFailure failure) {
                    require(failure.code() == FailureCode.OWNERSHIP_CONFLICT
                                    && failure.getMessage().contains("void is not authorized to erase this component"),
                            "only the specified mandatory-clearance rejection is accepted: " + failure);
                }
            }
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-decision")
    public void orderedSitesUseActualSharedExitDecisions(GameTestHelper helper) {
        var cases = new ArrayList<Pair>();
        for (var a : SITES) for (var b : SITES) for (boolean mixed : List.of(false, true))
            cases.add(new Pair(a, b, mixed, 0));
        require(cases.size() == 32, "4 x 4 ordered scheduled-site types x 2 terrain profiles");
        matrix(helper, "DECISION", cases, 0, new ArrayList<>(), test -> {
            var f = new Fixture(List.of(site(SiteKind.FORT, 0, 0, false), site(test.left(), 100, 128, false),
                    site(test.right(), 200, 256, false)), Optional.empty(), test.mixed(), 480);
            var forward = resolve(f, false); var reverse = resolve(f, true);
            assertSameResolution(forward, reverse);
            assertSiteOutcomes(f, forward);
            var states = verifyMerged(helper, forward.cells(), f.before(forward.cells()), f.columns, true);
            assertCertifiedConnectivity(f, forward, states);
            assertNativeGroundExits(f, forward, states);
        });
    }

    static void curvedHeightSitePairs(GameTestHelper helper) {
        curvedHeightSitePairs(helper, curvedSiteCases());
    }

    private static List<CurvePair> curvedSiteCases() {
        var cases=new ArrayList<CurvePair>();
        for(var left:SITES)for(var right:SITES)for(int bend:new int[]{-1,1})for(int slope:new int[]{-1,1})
            cases.add(new CurvePair(left,right,new Curve(bend,slope)));
        require(cases.size()==64,"4 x 4 ordered sites x 2 bends x 2 changing-height profiles");
        return List.copyOf(cases);
    }

    static void curvedGateRegression(GameTestHelper helper) {
        System.out.println("S1B_CURVED_GATE_ORIGINAL_INDEX=58; assertions and fixture shared with full matrix");
        curvedHeightSitePairs(helper,List.of(curvedSiteCases().get(58)));
    }

    private static void curvedHeightSitePairs(GameTestHelper helper,List<CurvePair> cases) {
        matrix(helper,"CURVED_SITES",cases,0,new ArrayList<>(),test->{
            var f=new Fixture(List.of(site(SiteKind.FORT,0,0,false),site(test.left(),100,128,false),
                    site(test.right(),200,256,false)),Optional.empty(),true,480,List.of(),List.of(),(x,z)->null,test.curve());
            require(f.stations.stream().map(Station::walkY).distinct().count()>1,"W must actually vary");
            require(f.stations.stream().map(s->s.point().zQ8()).distinct().count()>2,"ribbon must actually bend");
            var forward=resolve(f,false);var reverse=resolve(f,true);
            assertSameResolution(forward,reverse);assertSiteOutcomes(f,forward);
            if(test.right()==SiteKind.GATE) {
                var gate=f.sites.stream().filter(s->s.id().ordinal()==200).findFirst().orElseThrow();
                var witness=new Point3i(223,84,-3);
                var local=Rasterizer.localCell(gate.frame(),witness);
                var contract=WallSection.contract(f.stations,witness.x(),witness.z());
                System.out.println("S1B_CURVED_GATE_WITNESS case="+test+" frame="+gate.frame()
                        +" gateLocal="+local+" wallContract="+contract+" scalarFrom="+(gate.globalSQ8()-30L*256));
            }
            var states=verifyMerged(helper,forward.cells(),f.before(forward.cells()),f.columns,true);
            assertCertifiedConnectivity(f,forward,states);assertNativeGroundExits(f,forward,states);
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-decision")
    public void water49_50_51AgainstEverySiteAndBoundary(GameTestHelper helper) {
        var cases = new ArrayList<Hydro>();
        for (var site : SITES) for (int width : new int[]{49, 50, 51})
            for (boolean after : List.of(false, true)) for (int gap : new int[]{-1, 0, 1})
                cases.add(new Hydro(site, width, after, gap));
        require(cases.size() == 72, "4 sites x 3 widths x 2 ordered sides x 3 hydro-footprint boundaries");
        hydroCases(helper, cases);
    }

    static void touchingNarrowWaterRegression(GameTestHelper helper) {
        // Retain the original 49-wide, half-block-center counterexample independently of the fix.
        var frame = new Frame(new Point2q(256L*256+128,0),1<<30,0,W,false);
        int oldMin = FixedMath.floorBlock(232L*256-frame.center().xQ8())-8;
        require(oldMin == -33 && Rasterizer.localCell(frame,new Point3i(223,W,0)).x() == oldMin,
                "legacy floor boundary must include x=223 outside the reserved [224,289) apron");
        hydroCases(helper, List.of(new Hydro(SiteKind.ENEMY_TOWER,49,false,0),
                new Hydro(SiteKind.BEACON,49,false,0)));
    }

    private static void hydroCases(GameTestHelper helper, List<Hydro> cases) {
        matrix(helper, "HYDRO", cases, 0, new ArrayList<>(), test -> {
            int from = 256 - test.width() / 2, to = from + test.width();
            int s = test.after() ? to + 8 + extent(test.site()) + test.gap()
                    : from - 8 - extent(test.site()) - test.gap();
            var scheduled = site(test.site(), 100, s, false);
            var water = water(test.width(), from, to);
            var f = new Fixture(List.of(site(SiteKind.FORT, 0, 0, false), scheduled), Optional.of(water), false,
                    test.width() < 50 ? 480 : from);
            var resolved = resolve(f, false);
            assertSameResolution(resolved, resolve(f, true));
            assertSiteOutcomes(f, resolved);
            var values = components(resolved.cells());
            boolean excluded = excluded(resolved, scheduled.globalSQ8());
            boolean forbidden = test.gap() < 0 || s >= f.terminal || excluded;
            if (test.gap() >= 0 && s < f.terminal)
                require(!f.scheduledComponent(scheduled).voxels().isEmpty(), "legal hydro boundary needs a complete site fixture: " + test);
            require(values.containsKey(scheduled.id()) != forbidden,
                    "hydrology buffer/terminal/shared exclusion did not govern site acceptance: " + test);
            long crossings = values.keySet().stream().filter(id -> id.kind() == SiteKind.WATER_GATE).count();
            if (test.width() < 50) {
                long center = water.crossing().fromQ8() + (water.crossing().toQ8() - water.crossing().fromQ8()) / 2;
                require(!f.waterComponent(water).voxels().isEmpty(), "49-wide crossing must first be physically admissible");
                boolean crossingWithdrawn = excluded(resolved, center);
                require(crossings == (crossingWithdrawn ? 0 : 1), "49-wide crossing must be complete or explicitly withdrawn");
            } else {
                require(crossings == 0, "50/51 must never publish a far-bank crossing");
                require(values.keySet().stream().filter(id -> id.kind() == SiteKind.END_CAP).count() <= 1,
                        "wide-river terminal cannot acquire a second/far-bank cap");
                for (var c : values.values()) for (var v : c.voxels())
                    require(WallSection.contract(f.stations, v.pos().x(), v.pos().z()).globalSQ8() < (long) from * 256,
                            "terminal body crossed the near bank: " + v.pos());
            }
            var states = verifyMerged(helper, resolved.cells(), f.before(resolved.cells()), f.columns, true);
            assertNativeGroundExits(f, resolved, states);
            for (var c : values.values()) if (c.id().kind() == SiteKind.WATER_GATE) {
                for (var v : c.voxels()) require(v.pos().x() >= from-8 && v.pos().x() < to+8,
                        "water gate escaped its scheduled eight-block bank apron: "+v.pos());
                long fluids = 0, mandatoryBoatAir = 0;
                for (var v : c.voxels()) {
                    if (v.kind() == IntentKind.FLUID) {
                        fluids++;
                        require(v.pos().y() < 65 && states.get(v.pos()).getFluidState().isSource(), "source water exceeded captured level");
                        require(f.columns.get(new Point3i(v.pos().x(), 0, v.pos().z())).water(v.pos().y()),
                                "water gate flooded an originally dry column");
                    }
                    if (v.kind() == IntentKind.OWNED_VOID && v.required() && v.pos().y() >= 65 && v.pos().y() < 68) {
                        mandatoryBoatAir++; require(states.get(v.pos()).isAir(), "adjacent site erased boat clearance");
                    }
                }
                require(fluids > 0 && mandatoryBoatAir >= 4 * 10 * 3, "whole main channel, not an empty template");
                int middle = FixedMath.floorBlock((water.crossing().fromQ8() + water.crossing().toQ8()) / 2);
                for (int x = middle - 2; x < middle + 2; x++) for (int z = -5; z < 5; z++) for (int y = 65; y < 68; y++) {
                    var p = new Point3i(x, y, z); var intent = at(c, p);
                    require(intent != null && intent.kind() == IntentKind.OWNED_VOID && intent.required()
                            && states.get(p).isAir(), "exact four-wide main boat channel is missing: " + p);
                }
            }
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-decision")
    public void capAvoidsActualSiteAndWaterFootprints(GameTestHelper helper) {
        var obstacles = new ArrayList<>(SITES); obstacles.add(SiteKind.WATER_GATE);
        var cases = new ArrayList<CapCase>();
        for (var obstacle : obstacles) for (boolean reverse : List.of(false, true))
            for (int gap : new int[]{-1, 0, 1}) cases.add(new CapCase(obstacle, reverse, gap));
        require(cases.size() == 30, "5 obstacles x 2 cap directions x overlap/touch/gap");
        matrix(helper, "CAP", cases, 0, new ArrayList<>(), test -> {
            boolean wet = test.obstacle() == SiteKind.WATER_GATE;
            var scheduled = site(test.obstacle(), 100, 256, false);
            var evidence = water(49, 232, 281);
            var f = new Fixture(wet ? List.of(site(SiteKind.FORT, 0, 0, false))
                    : List.of(site(SiteKind.FORT, 0, 0, false), scheduled), wet ? Optional.of(evidence) : Optional.empty(), false, 480);
            long low = (wet ? 232 - 8 : 256 - extent(test.obstacle())) * 256L;
            long high = (wet ? 281 + 8 : 256 + extent(test.obstacle())) * 256L;
            // Calibrate contact from a full, independently compiled body, not its padded envelope.
            long referenceS = test.reverse() ? low - 64L * 256 : high + 64L * 256;
            var reference = capAt(f, referenceS, test.reverse());
            var referenceFootprint = footprint(reference, f.stations);
            long edgeS = test.reverse() ? low - test.gap() * 256L - (referenceFootprint.toQ8() - referenceS)
                    : high + test.gap() * 256L - (referenceFootprint.fromQ8() - referenceS);
            var unopposed = capAt(f, edgeS, test.reverse());
            var unopposedFootprint = footprint(unopposed, f.stations);
            long measuredGap = test.reverse() ? low - unopposedFootprint.toQ8() : unopposedFootprint.fromQ8() - high;
            require(measuredGap == test.gap() * 256L, "fixture did not produce the specified full-body contact");
            var edge = new GroundExitDecision.Edge(edgeS, test.reverse(), test.reverse()
                    ? new ArcInterval(edgeS, 480L * 256) : new ArcInterval(64L * 256, edgeS));
            var vote = f.planner.probeCap(f.input(Math.floorDiv(edgeS, 64L * 256)), edge);
            var cap = vote.cap().orElseThrow(() -> new AssertionError("flat support has an admissible cap within 128: " + test));
            long retreat = test.reverse() ? cap.actualSQ8() - edgeS : edgeS - cap.actualSQ8();
            require(retreat >= 0 && retreat <= 128L * 256 && retreat % (4L * 256) == 0, "frozen signed 0,4,...,128 retreat schedule");
            require(!intersects(cap.footprint(), new ArcInterval(low, high)), "cap footprint intersected accepted obstacle");
            if (test.gap() >= 0) require(retreat == 0, "tangent/disjoint obstacle must not force a gratuitous retreat");
            else require(retreat > 0, "one-block overlap must actually retreat, not erase another component");
            var id = new SiteId(WALL, SiteKind.END_CAP, edgeS * 2 + (test.reverse() ? 1 : 0));
            var original = site(id, edgeS, test.reverse());
            var chosen = EndCap.candidates(original, f.stations).stream().filter(s -> s.globalSQ8() == cap.actualSQ8()).findFirst().orElseThrow();
            var body = new EndCap(new DecayField(8), ComponentFixtures.PALETTE)
                    .compileAt(f.componentInput(chosen, List.of()), c -> c.hash().equals(cap.componentHash()));
            require(!body.voxels().isEmpty() && body.hash().equals(cap.componentHash()), "exact voted full cap must replay");
            require(footprint(body, f.stations).equals(cap.footprint()), "voted footprint must equal every replayed cap voxel");
            ComponentVoxels obstacle;
            if (wet) {
                long middle = (evidence.crossing().fromQ8() + evidence.crossing().toQ8()) / 2;
                var waterSite = site(new SiteId(WALL, SiteKind.WATER_GATE, evidence.crossing().fromQ8()), middle, false);
                obstacle = template(SiteKind.WATER_GATE, 8).compile(f.componentInput(waterSite, List.of(evidence)));
            } else obstacle = template(test.obstacle(), 8).compile(f.componentInput(scheduled, List.of()));
            require(!obstacle.voxels().isEmpty(), "obstacle cannot be an empty placeholder");
            var cells = List.of(cell(0, List.of(obstacle)), cell(1, List.of(body)));
            verifyMerged(helper, cells, f.before(cells), f.columns, true);
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-decision")
    public void terminalRetreatAndExhaustionUseActualResolver(GameTestHelper helper) {
        var obstacles = new ArrayList<>(SITES); obstacles.add(SiteKind.WATER_GATE);
        var cases = new ArrayList<TerminalCase>();
        for (var obstacle : obstacles) for (var path : TerminalPath.values()) cases.add(new TerminalCase(obstacle, path));
        require(cases.size() == 15, "5 full obstacles x direct/retreat/exhausted terminal paths");
        matrix(helper, "TERMINAL", cases, 0, new ArrayList<>(), test -> {
            boolean wet = test.obstacle() == SiteKind.WATER_GATE;
            var obstacleSite = site(test.obstacle(), 100, 256, false);
            var evidence = water(49, 232, 281);
            int ridgeFrom = switch (test.path()) { case DIRECT -> 576; case RETREAT -> 456; case EXHAUSTED -> 320; };
            var f = new Fixture(wet ? List.of(site(SiteKind.FORT, 0, 0, false))
                    : List.of(site(SiteKind.FORT, 0, 0, false), obstacleSite), wet ? Optional.of(evidence) : Optional.empty(),
                    false, 480, List.of(), List.of(), (x, z) -> x >= ridgeFrom && (z < -5 || z >= 5) ? new Column(0, -1) : null);
            var initial = wet ? f.waterComponent(evidence) : f.scheduledComponent(obstacleSite);
            require(!initial.voxels().isEmpty(), "terminal fixture must start with an actual complete obstacle");
            var forward = resolve(f, false); var reverse = resolve(f, true);
            assertSameResolution(forward, reverse); assertSiteOutcomes(f, forward);
            var edgeVote = forward.votes().entrySet().stream()
                    .filter(e -> !e.getKey().reverse() && e.getKey().sQ8() == 480L * 256).findFirst().orElseThrow();
            var values = components(forward.cells());
            if (test.path() == TerminalPath.EXHAUSTED) {
                require(edgeVote.getValue().cap().isEmpty(), "all frozen retreat candidates must fail on the narrow ridge");
                require(uniqueDecisions(forward).stream().flatMap(d -> d.caps().stream())
                        .noneMatch(c -> c.edge().equals(edgeVote.getKey())), "exhaustion fabricated a terminal cap");
                require(uniqueDecisions(forward).stream().flatMap(d -> d.exclusions().stream())
                        .anyMatch(r -> r.equals(edgeVote.getKey().allowed())), "resolver did not withdraw the failed run");
                require(values.values().stream().flatMap(c -> c.voxels().stream()).filter(VoxelIntent::walkable)
                        .noneMatch(v -> contains(edgeVote.getKey().allowed(), WallSection.contract(f.stations, v.pos().x(), v.pos().z()).globalSQ8())),
                        "withdrawn interval retained an isolated walkable body");
                if (wet || test.obstacle() == SiteKind.BEACON) {
                    require(excluded(forward, 256L * 256) && !values.containsKey(initial.id()),
                            "whole non-exit site/water gate must be withdrawn with its walking interval");
                }
            } else {
                var cap = edgeVote.getValue().cap().orElseThrow();
                long retreat = 480L * 256 - cap.actualSQ8();
                require(test.path() == TerminalPath.DIRECT ? retreat == 0 : retreat > 0 && retreat <= 128L * 256,
                        "terminal path did not exercise the specified direct/retreat branch");
                require(retreat % (4L * 256) == 0 && values.containsKey(cap.site()), "actual terminal cap was not published intact");
                require(values.containsKey(initial.id()), "successful terminal path erased its upstream obstacle");
            }
            var states = verifyOwners(helper, forward.cells(), f.before(forward.cells()), f.columns, true);
            assertNativeGroundExits(f, forward, states);
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-decision")
    public void unsupportedOrProtectedSitesAreRejectedWhole(GameTestHelper helper) {
        var cases = new ArrayList<RejectionCase>();
        for (var kind : SITES) for (boolean protect : List.of(false, true)) cases.add(new RejectionCase(kind, protect));
        require(cases.size() == 8, "4 sites x unsupported/protected complete footprint");
        matrix(helper, "REJECTION", cases, 0, new ArrayList<>(), test -> {
            var rejected = site(test.site(), 100, 256, false);
            var protectedBounds = test.protectedFootprint() ? List.of(new Bounds(216, 48, -40, 297, 96, 41)) : List.<Bounds>of();
            var f = new Fixture(List.of(site(SiteKind.FORT, 0, 0, false), rejected, site(SiteKind.FORT, 200, 440, false)),
                    Optional.empty(), false, 480, List.of(), protectedBounds,
                    (x, z) -> !test.protectedFootprint() && x >= 216 && x < 297 ? new Column(51, -1) : null);
            var forward = resolve(f, false); assertSameResolution(forward, resolve(f, true));
            var values = components(forward.cells());
            require(!values.containsKey(rejected.id()), "infeasible/protected site leaked a partial body");
            require(forward.surveys().values().stream().flatMap(s -> s.exits().stream()).noneMatch(e -> e.site().equals(rejected.id())),
                    "rejected site became a fabricated ground exit");
            require(uniqueDecisions(forward).stream().flatMap(d -> d.breaks().stream()).anyMatch(b -> contains(b.range(), rejected.globalSQ8())),
                    "whole-site rejection must leave a certified break");
            require(values.containsKey(new SiteId(WALL, SiteKind.FORT, 0)) && values.containsKey(new SiteId(WALL, SiteKind.FORT, 200)),
                    "rejection renumbered or removed the unaffected root/far exit");
            for (var c : values.values()) for (var v : c.voxels()) for (var b : protectedBounds)
                require(!inside(b, v.pos()), "another component overwrote the protected site footprint");
            var states = verifyMerged(helper, forward.cells(), f.before(forward.cells()), f.columns, true);
            assertNativeGroundExits(f, forward, states);
        });
    }

    @GameTest(template=EMPTY_STRUCTURE, timeoutTicks=1000000, batch="pgw-adjacency-decision")
    public void ordinaryBreaksAndShortIslandsUseActualVotes(GameTestHelper helper) {
        var cases = new ArrayList<GapCase>();
        for (boolean left : List.of(false, true)) for (boolean right : List.of(false, true)) cases.add(new GapCase(left, right, 0));
        for (int length : new int[]{48, 64}) cases.add(new GapCase(true, true, length));
        require(cases.size() == 6, "4 ordinary-break support combinations + 2 opposed-cap island lengths");
        matrix(helper, "GAPS", cases, 0, new ArrayList<>(), test -> {
            boolean island = test.islandLength() > 0;
            var gaps = island ? List.of(gap(96, 192), gap(192 + test.islandLength(), 352)) : List.of(gap(200, 224));
            var f = new Fixture(List.of(site(SiteKind.FORT, 0, 0, false), site(SiteKind.FORT, 200, 440, false)),
                    Optional.empty(), false, 480, gaps, List.of(), (x, z) -> {
                        boolean failLeft = !test.leftSupport() && x >= 40 && x < 200;
                        boolean failRight = !test.rightSupport() && x >= 224 && x < 390;
                        return (failLeft || failRight) && (z < -5 || z >= 5) ? new Column(0, -1) : null;
                    });
            var forward = resolve(f, false); assertSameResolution(forward, resolve(f, true));
            var decisions = uniqueDecisions(forward); var values = components(forward.cells());
            if (island) {
                var interval = new ArcInterval(192L * 256, (192L + test.islandLength()) * 256);
                var votes = forward.votes().entrySet().stream().filter(e -> e.getKey().allowed().equals(interval)).toList();
                require(votes.size() == 2 && votes.stream().allMatch(e -> e.getValue().cap().isPresent()),
                        "island must first get two individually feasible complete cap votes");
                var footprints = votes.stream().map(e -> e.getValue().cap().orElseThrow().footprint()).toList();
                require(intersects(footprints.get(0), footprints.get(1)) == (test.islandLength() == 48),
                        "island fixture failed to exercise opposed-cap footprint collision");
                if (test.islandLength() == 48) {
                    require(decisions.stream().flatMap(d -> d.exclusions().stream()).anyMatch(interval::equals), "overlapping caps did not withdraw the island");
                    require(votes.stream().noneMatch(e -> values.containsKey(e.getValue().cap().orElseThrow().site())), "overlapping voted caps were published");
                } else require(votes.stream().allMatch(e -> values.containsKey(e.getValue().cap().orElseThrow().site())), "nonoverlapping island lost either full cap");
            } else {
                for (boolean reverse : List.of(false, true)) {
                    long s = (reverse ? 224L : 200L) * 256;
                    boolean supported = reverse ? test.rightSupport() : test.leftSupport();
                    var vote = forward.votes().entrySet().stream().filter(e -> e.getKey().sQ8() == s && e.getKey().reverse() == reverse)
                            .findFirst().orElseThrow();
                    require(vote.getValue().cap().isPresent() == supported, "ordinary break did not exercise the requested support branch");
                    if (supported) require(values.containsKey(vote.getValue().cap().orElseThrow().site()), "accepted ordinary endpoint was not published");
                    else require(decisions.stream().flatMap(d -> d.exclusions().stream()).anyMatch(vote.getKey().allowed()::equals),
                            "unsupported ordinary endpoint did not withdraw its entire run");
                }
            }
            var states = verifyMerged(helper, forward.cells(), f.before(forward.cells()), f.columns, true);
            assertNativeGroundExits(f, forward, states);
        });
    }

    private static <T> void matrix(GameTestHelper helper, String label, List<T> cases, int index,
            List<String> errors, Consumer<T> execute) {
        if (index == 0) System.out.println("S1B_ADJACENCY_" + label + " planned=" + cases.size());
        if (index == cases.size()) {
            System.out.println("S1B_ADJACENCY_" + label + " attempted=" + index + " passed=" + (index - errors.size()) + " failed=" + errors.size());
            errors.forEach(e -> System.out.println("S1B_ADJACENCY_FAILURE " + e));
            helper.assertTrue(errors.isEmpty(), label + " failures=" + errors.size() + " " + errors.stream().limit(3).toList());
            helper.succeed(); return;
        }
        try {
            execute.accept(cases.get(index));
            System.out.println("S1B_ADJACENCY_CASE " + label + " index=" + index + " case=" + cases.get(index) + " status=PASS");
        }
        catch (Exception | AssertionError failure) {
            errors.add(label + " index=" + index + " case=" + cases.get(index) + " " + failure);
            failure.printStackTrace(System.out);
        }
        helper.runAfterDelay(1, () -> matrix(helper, label, cases, index + 1, errors, execute));
    }

    private static ComponentTemplate template(SiteKind kind, long seed) {
        var d = new DecayField(seed); var p = ComponentFixtures.PALETTE;
        return switch (kind) {
            case WALL -> new WallSection(d, p); case ENEMY_TOWER -> new EnemyTower(d, p);
            case BEACON -> new BeaconTower(d, p); case GATE -> new Gate(d, p);
            case FORT -> new FortPlaceholder(d, p); case WATER_GATE -> new WaterGate(d, p);
            case END_CAP -> new EndCap(d, p); case RAMP -> new Ramp(d, p);
        };
    }

    private static Bounds envelope(SiteKind kind) {
        return switch (kind) {
            case WALL -> new Bounds(-32,-32,-5,32,3,5);
            case ENEMY_TOWER -> new Bounds(-5,-33,-5,6,8,6);
            case BEACON -> new Bounds(-5,-32,-5,5,7,5);
            case GATE, END_CAP -> new Bounds(-30,-33,-15,30,4,15);
            case WATER_GATE -> new Bounds(-40,-32,-6,40,3,6);
            case RAMP -> new Bounds(-30,-33,4,-6,3,15);
            case FORT -> new Bounds(-24,-33,-24,24,8,24);
        };
    }

    private static Built build(SiteKind kind, long ordinal, int heading, boolean mixed) {
        var base = ComponentFixtures.input(kind, envelope(kind), 0, 0, heading, DecayState.INTACT);
        var f = base.site().frame();
        var s = new SiteRecord(new SiteId(WALL, kind, ordinal), base.site().globalSQ8(), f, base.site().bounds(),
                SiteState.PLANNED, Optional.empty(), Optional.empty());
        List<Station> stations = base.stations();
        if (mixed && kind == SiteKind.WALL) {
            var points = new ArrayList<Point2q>(); var lengths = new ArrayList<Long>();
            for (int x = -96; x <= 96; x += 4) {
                var p = Projection.local(f, x * 256L, StrictMath.round(2 * StrictMath.sin(x * StrictMath.PI / 32) * 256));
                lengths.add(points.isEmpty() ? 0 : lengths.getLast() + FixedMath.hypotQ8(p.xQ8() - points.getLast().xQ8(), p.zQ8() - points.getLast().zQ8()));
                points.add(p);
            }
            var ribbon = new ArrayList<Station>();
            for (int n = 0; n < points.size(); n++) {
                int x = -96 + n * 4;
                ribbon.add(new Station(1024L * 256 + lengths.get(n) - lengths.get(24), points.get(n),
                        W + (int) StrictMath.round(2 * StrictMath.sin(x * StrictMath.PI / 32)), 0));
            }
            stations = List.copyOf(ribbon);
        }
        var supports = new HashMap<Point3i, ProbeResult>(); var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        var probe = new SurfaceProbe();
        for (int z = -72; z <= 72; z++) for (int x = -72; x <= 72; x++) {
            var local = Rasterizer.localCell(f, new Point3i(x, W, z));
            boolean wet = kind == SiteKind.WATER_GATE && local.x() >= -24 && local.x() < 25;
            int walk = kind == SiteKind.WALL ? WallSection.contract(stations, x, z).walkY() : W;
            int drop = wet ? 8 : mixed ? 4 + Math.floorMod(x + z, 2) : 0;
            var column = new Column(walk - 20 - drop, wet ? 65 : -1);
            columns.put(new Point3i(x, 0, z), column);
            supports.put(new Point3i(x, walk, z), probe.probe(column, walk, new DimensionBounds(0, 128, 29_999_000)));
        }
        List<WaterEvidence> waters = kind == SiteKind.WATER_GATE ? List.of(new WaterEvidence(
                new ArcInterval(1000L * 256, 1049L * 256), WaterClass.NARROW, 49L * 256, 49L * 256, 65,
                List.of(new Point3i(-25,64,0), new Point3i(25,64,0)), List.of(new Point3i(0,56,0)), H)) : List.of();
        var input = new ComponentInput(s, stations, supports, new DecaySample(DecayState.INTACT, 0, 20),
                PaletteFamily.STONE, false, waters, List.of());
        var component = kind == SiteKind.END_CAP ? new EndCap(new DecayField(0), ComponentFixtures.PALETTE).compileAt(input)
                : template(kind, 0).compile(input);
        require(!component.voxels().isEmpty() && !component.requiredRoutes().isEmpty(), "whole functional fixture unexpectedly empty: " + kind);
        return new Built(component, Map.copyOf(columns));
    }

    /** Rigid translation preserves every voxel, entity, spawn slot, route, and full component identity. */
    private static Built translate(Built built, int dx, int dy, int dz) {
        var c = built.component();
        var voxels = c.voxels().stream().map(v -> new VoxelIntent(shift(v.pos(), dx, dy, dz), v.kind(), v.part(), v.state(),
                v.component(), v.priority(), v.required(), v.walkable())).toList();
        var entities = c.blockEntities().stream().map(e -> new BlockEntityIntent(shift(e.pos(), dx, dy, dz), e.type(), e.site(), e.localId(), e.fields())).toList();
        var slots = c.spawnSlots().stream().map(e -> new SpawnSlot(e.site(), e.slot(), shift(e.feet(), dx, dy, dz), e.entityType())).toList();
        var routes = c.requiredRoutes().stream().map(r -> r.stream().map(p -> shift(p, dx, dy, dz)).toList()).toList();
        var b = c.bounds();
        var bounds = new Bounds(b.minX()+dx,b.minY()+dy,b.minZ()+dz,b.maxXExclusive()+dx,b.maxYExclusive()+dy,b.maxZExclusive()+dz);
        var draft = new ComponentVoxels(c.id(), bounds, voxels, entities, slots, routes, c.realizedState(), "0".repeat(64));
        var result = new ComponentVoxels(c.id(), bounds, voxels, entities, slots, routes, c.realizedState(),
                DeterministicHash.sha256(CanonicalCodec.component(draft)));
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        built.columns().forEach((p, v) -> { var old = (Column) v;
            columns.put(new Point3i(p.x()+dx,0,p.z()+dz), new Column(old.ground+dy,old.waterTop<0?-1:old.waterTop+dy)); });
        return new Built(result, Map.copyOf(columns));
    }

    private static Map<Point3i, String> before(List<Built> built) {
        var states = new HashMap<Point3i, String>();
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        for (var b : built) for (var v : b.component().voxels()) {
            var key = new Point3i(v.pos().x(), 0, v.pos().z());
            var column = Objects.requireNonNull(b.columns().get(key), "missing fixture column");
            var priorColumn = columns.putIfAbsent(key, column);
            require(priorColumn == null || priorColumn.hash().equals(column.hash()), "one XZ column has incompatible complete T0 profiles");
            String state = column.state(v.pos().y());
            var old = states.putIfAbsent(v.pos(), state);
            require(old == null || old.equals(state), "overlap has inconsistent immutable T0");
        }
        return Map.copyOf(states);
    }

    private static Map<Point3i, SurfaceProbe.ColumnView> boundaryColumns(Built left, Built right) {
        int split = occupied(left.component()).maxXExclusive();
        require(occupied(right.component()).minX() >= split, "natural boundary requires disjoint X footprints");
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        left.columns().forEach((p, c) -> { if (p.x() < split) columns.put(p, c); });
        right.columns().forEach((p, c) -> { if (p.x() >= split) columns.put(p, c); });
        // The one-block gap, when present, belongs to the right captured terrain profile.
        for (var built : List.of(left, right)) for (var v : built.component().voxels()) {
            var key = new Point3i(v.pos().x(), 0, v.pos().z());
            var actual = Objects.requireNonNull(columns.get(key), "missing stitched natural column: " + key);
            var original = Objects.requireNonNull(built.columns().get(key), "missing original natural column: " + key);
            require(actual.hash().equals(original.hash()), "natural boundary changed a complete template's T0 column: " + key);
        }
        return Map.copyOf(columns);
    }

    private static Map<Point3i, BlockState> verifyMerged(GameTestHelper helper, List<RealizationCell> cells,
            Map<Point3i, String> before, Map<Point3i, SurfaceProbe.ColumnView> columns, boolean nativeReplay) {
        require(components(cells).size() >= 2, "an adjacency assertion must consume multiple whole components");
        return verifyOwners(helper, cells, before, columns, nativeReplay);
    }

    private static Map<Point3i, BlockState> verifyOwners(GameTestHelper helper, List<RealizationCell> cells,
            Map<Point3i, String> before, Map<Point3i, SurfaceProbe.ColumnView> columns, boolean nativeReplay) {
        var owners = new TreeSet<>(OWNER); var expectedPositions = new HashSet<Point3i>();
        var complete = components(cells);
        require(!complete.isEmpty(), "even complete withdrawal must retain its independently accepted root exit");
        for (var c : complete.values()) for (var v : c.voxels()) if (v.kind() != IntentKind.KEEP) {
            owners.add(owner(v.pos())); expectedPositions.add(v.pos());
        }
        var compiler = new ChunkPlanCompiler(); var plans = new LinkedHashMap<ChunkKey, ChunkWritePlan>();
        var states = new HashMap<Point3i, BlockState>();
        for (var owner : owners) {
            var a = compiler.bindCoverage(compiler.compile(owner, H, cells, before), H);
            var b = compiler.bindCoverage(compiler.compile(owner, H, cells.reversed(), before), H);
            require(Arrays.equals(CanonicalCodec.writePlan(a), CanonicalCodec.writePlan(b)), "opposite cell arrival changed owner bytes: " + owner);
            require(a.voxels().size() == a.proofs().size(), "every merged write has exactly one proof");
            for (var v : a.voxels()) {
                require(owner(v.pos()).equals(owner), "owner clipping escaped its chunk");
                require(states.put(v.pos(), BlockCatalog.resolveState(v.state())) == null, "two owners wrote the same coordinate");
            }
            plans.put(owner, a);
        }
        require(states.keySet().equals(expectedPositions), "whole-component owner union lost or invented coordinates");
        for (var p : states.keySet()) {
            var key = new Point3i(p.x(), 0, p.z());
            var column = Objects.requireNonNull(columns.get(key), "missing native T0 column: " + key);
            require(column.state(p.y()).equals(before.get(p)), "native background differs from exact captured before-state: " + p);
        }
        var natural = withNaturalBackground(states, columns, complete.values().stream().map(ComponentVoxels::bounds).toList());
        var view = ComponentFixtures.view(natural);
        for (var c : complete.values()) for (var v : c.voxels()) if (v.kind() == IntentKind.OWNED_VOID && v.required())
            require(states.get(v.pos()).isAir(), "merged component obstructed mandatory clearance: " + c.id() + " " + v.pos());
        states.forEach((p, state) -> {
            require(!(state.getBlock() instanceof FallingBlock), "gravity hazard in merged template at " + p);
            require(state.canSurvive(view, new BlockPos(p.x(),p.y(),p.z())), "merged decoration cannot survive at " + p + " " + state);
        });
        var collisions = new ComponentFixtures.CollisionView(natural);
        for (var c : complete.values()) {
            Set<ComponentFixtures.Foot> reached = Set.of();
            for (var route : c.requiredRoutes()) {
                var first = foot(route.getFirst());
                if (reached.isEmpty() || c.id().kind() == SiteKind.WALL && !reached.contains(first))
                    reached = collisions.reachable(route.getFirst(), c.bounds());
                for (var p : route) require(reached.contains(foot(p)), "merged native route disconnected: " + c.id() + " " + p);
            }
            var entities = plans.values().stream().flatMap(p -> p.blockEntities().stream()).filter(e -> e.site().equals(c.id())).toList();
            require(new HashSet<>(entities).equals(new HashSet<>(c.blockEntities())) && entities.size() == c.blockEntities().size(),
                    "adjacency lost or duplicated a full component's functional block entities: " + c.id());
            var slots = plans.values().stream().flatMap(p -> p.metadata().spawnSlots().stream()).filter(s -> s.site().equals(c.id())).toList();
            require(new HashSet<>(slots).equals(new HashSet<>(c.spawnSlots())) && slots.size() == c.spawnSlots().size(),
                    "adjacency lost or duplicated a complete component's spawn slots: " + c.id());
        }
        if (nativeReplay) {
            var order = new ArrayList<>(plans.keySet());
            var forward = write(helper, plans, before, order);
            Collections.reverse(order);
            var reverse = write(helper, plans, before, order);
            require(forward.equals(reverse), "opposite native owner replay changed blocks or entities");
            require(forward.keySet().equals(states.keySet()), "native replay did not cover every merged voxel");
        }
        return Map.copyOf(states);
    }

    /** Enumerate bounded collision shapes, but resolve every native neighbor read from captured T0. */
    private static Map<Point3i, BlockState> withNaturalBackground(Map<Point3i, BlockState> writes,
            Map<Point3i, SurfaceProbe.ColumnView> columns, List<Bounds> scopes) {
        var overlay = new HashMap<>(writes);
        for (var b : scopes) for (int z = b.minZ() - 2; z < b.maxZExclusive() + 2; z++)
            for (int x = b.minX() - 2; x < b.maxXExclusive() + 2; x++) {
                var key = new Point3i(x, 0, z);
                var column = Objects.requireNonNull(columns.get(key), "missing collision-background T0 column: " + key);
                for (int y = b.minY() - 1; y < b.maxYExclusive() + 2; y++) {
                    var state = naturalState(column.state(y));
                    if (!state.isAir()) overlay.putIfAbsent(new Point3i(x, y, z), state);
                }
            }
        writes.forEach((p, s) -> require(overlay.get(p).equals(s), "natural background changed a published write: " + p));
        var immutable = Collections.unmodifiableMap(overlay);
        return new AbstractMap<Point3i, BlockState>() {
            @Override public Set<Map.Entry<Point3i, BlockState>> entrySet() { return immutable.entrySet(); }
            @Override public BlockState get(Object key) { return getOrDefault(key, null); }
            @Override public BlockState getOrDefault(Object key, BlockState ignored) {
                if (!(key instanceof Point3i p)) throw new IllegalArgumentException("native background requires a voxel coordinate");
                var column = Objects.requireNonNull(columns.get(new Point3i(p.x(), 0, p.z())), "missing native neighbor T0 column: " + p);
                var written = immutable.get(p);
                return written != null ? written : naturalState(column.state(p.y()));
            }
        };
    }

    private static BlockState naturalState(String token) {
        if (Column.AIR.equals(token)) return Blocks.AIR.defaultBlockState();
        if (Column.STONE.equals(token)) return Blocks.STONE.defaultBlockState();
        if (Column.WATER.equals(token)) return Blocks.WATER.defaultBlockState();
        throw new AssertionError("unknown captured natural state, not assumed AIR: " + token);
    }

    private static Map<Point3i, String> write(GameTestHelper helper, Map<ChunkKey, ChunkWritePlan> plans,
            Map<Point3i, String> before, List<ChunkKey> order) {
        var written = new HashMap<Point3i, String>(); var writer = new ChunkWallWriter();
        for (var key : order) {
            var chunk = new ProtoChunk(new ChunkPos(key.x(),key.z()), UpgradeData.EMPTY, helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            chunk.setPersistedStatus(ChunkStatus.FEATURES);
            var plan = plans.get(key);
            for (var v : plan.voxels()) {
                String state = before.get(v.pos());
                chunk.setBlockState(pos(v.pos()), naturalState(state), false);
            }
            StageTrace.enterLockAction();
            try {
                require(writer.apply(chunk, plan, WriteMode.INITIAL).complete(), "native owner apply failed: " + key);
                require(writer.apply(chunk, plan, WriteMode.INITIAL).written() == 0, "complete marker replay wrote again");
            } finally { StageTrace.leaveLockAction(); }
            require(chunk.getAttached(TerrainMarkers.PRODUCTION).planHash().equals(plan.hash()), "complete marker belongs to merged plan");
            var actualEntities = Set.copyOf(chunk.getBlockEntitiesPos());
            for (var proof : plan.proofs()) {
                var p = pos(proof.pos());
                String actual = BlockCatalog.token(chunk.getBlockState(p), actualEntities.contains(p));
                require(actual.equals(proof.afterState()), "native written state/entity differs from exact proof: " + proof.pos());
                written.put(proof.pos(), actual);
            }
            var playerEdit = plan.proofs().stream().filter(p -> !p.afterState().endsWith(":be=true")).findFirst().orElseThrow().pos();
            chunk.setBlockState(pos(playerEdit), Blocks.GOLD_BLOCK.defaultBlockState(), false);
            StageTrace.enterLockAction();
            try { require(writer.apply(chunk, plan, WriteMode.INITIAL).written() == 0, "complete-marker replay rewrote an edited owner"); }
            finally { StageTrace.leaveLockAction(); }
            require(chunk.getBlockState(pos(playerEdit)).is(Blocks.GOLD_BLOCK), "complete-marker replay destroyed the player's edit");
        }
        return Map.copyOf(written);
    }

    private static final class Column implements SurfaceProbe.ColumnView {
        static final String AIR = BlockCatalog.token(Blocks.AIR.defaultBlockState(),false);
        static final String STONE = BlockCatalog.token(Blocks.STONE.defaultBlockState(),false);
        static final String WATER = BlockCatalog.token(Blocks.WATER.defaultBlockState(),false);
        final int ground, waterTop; final String hash;
        Column(int ground, int waterTop) { this.ground=ground;this.waterTop=waterTop;this.hash=digest("adjacency:"+ground+":"+waterTop); }
        public String state(int y) { return y<ground?STONE:water(y)?WATER:AIR; }
        public boolean solidSupport(int y) { return y<ground; }
        public boolean protectedAt(int y) { return false; }
        public boolean water(int y) { return waterTop>=0&&y>=ground&&y<waterTop; }
        public boolean airOrReplaceablePlant(int y) { return y>=Math.max(ground,waterTop); }
        public int highestNonPlantFeet() { return Math.max(ground,waterTop); }
        public String hash() { return hash; }
    }

    private static final class Fixture {
        final List<SiteRecord> sites;
        final List<Station> stations;
        final PlanWindow plans;
        final Map<TerrainTile.Key, TerrainTile> tiles;
        final Map<Point3i, SurfaceProbe.ColumnView> columns;
        final RealizationPlanner planner;
        final int terminal;
        final Optional<WaterEvidence> water;
        final List<Bounds> protectedBounds;
        final ConcurrentHashMap<Long, RealizationPlanner.Input> inputs = new ConcurrentHashMap<>();
        Fixture(List<SiteRecord> sites, Optional<WaterEvidence> water, boolean mixed, int terminal) {
            this(sites, water, mixed, terminal, List.of(), List.of(), (x, z) -> null);
        }
        Fixture(List<SiteRecord> sites, Optional<WaterEvidence> water, boolean mixed, int terminal,
                List<BreakInterval> breaks, List<Bounds> protectedBounds, BiFunction<Integer, Integer, Column> terrain) {
            this(sites,water,mixed,terminal,breaks,protectedBounds,terrain,null);
        }
        Fixture(List<SiteRecord> sites, Optional<WaterEvidence> water, boolean mixed, int terminal,
                List<BreakInterval> breaks, List<Bounds> protectedBounds, BiFunction<Integer, Integer, Column> terrain,Curve curve) {
            this.terminal=terminal;this.water=water;this.protectedBounds=List.copyOf(protectedBounds);
            var recipe=new WorldRecipe(new UUID(0,8),WALL,8,DIMENSION,new Point3i(0,64,0),
                    new Axis(new Point3i(0,64,0),1<<30,0),new DimensionBounds(0,128,29_999_000),
                    new GenerationConfig(1,1,2048,4,150000,256,512,8,64L<<20,64L<<20,32L<<20,H),H,H,H,H);
            var ribbon=new ArrayList<Station>();
            for(int x=0;x<=512;x+=4)ribbon.add(new Station(x*256L,new Point2q(x*256L,0),W,0));
            var start=new Boundary(0,new Point2q(0,0),W,H);var end=new Boundary(1,new Point2q(512L*256,0),W,H);
            List<Point2q> points=curve==null?List.of(start.point(),end.point()):java.util.stream.IntStream.rangeClosed(0,128)
                    .mapToObj(n->new Point2q(n*1024L,StrictMath.round(curve.bend()*4*StrictMath.sin(n*StrictMath.PI/16)*256))).toList();
            var geometry=new SegmentGeometry(new SegmentKey(H,DIMENSION,0),List.of(start.point(),end.point()),List.of(0L,512L*256),
                    water.stream().toList(),Optional.of(new Terminal(0,terminal*256L,water.isPresent()&&water.get().classification()==WaterClass.WIDE
                    ?TerminalKind.OCEAN:TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
            var prefix=new PrefixSummary(0,0,Optional.empty(),H);
            var table=new StationTable(ribbon,breaks,H);
            if(curve!=null){
                geometry=new SegmentGeometry(geometry.key(),points,ArcLength.accumulate(points),List.of(),geometry.terminal(),start,end,0,0,H);
                var actual=new ArrayList<SiteRecord>();
                for(var old:sites){
                    long s=old.globalSQ8();var p=ArcLength.pointAt(geometry,s);int edge=ArcLength.edgeAt(geometry.localSQ8(),s);
                    var a=points.get(edge);var b=points.get(edge+1);
                    long dx=b.xQ8()-a.xQ8(),dz=b.zQ8()-a.zQ8();double length=StrictMath.hypot(dx,dz);
                    var frame=new Frame(p,(int)StrictMath.round(dx*(1L<<30)/length),(int)StrictMath.round(dz*(1L<<30)/length),W,false);
                    actual.add(new SiteRecord(old.id(),s,frame,Rasterizer.worldBounds(frame,envelope(old.id().kind())),
                            old.state(),old.absorbedBy(),old.reason()));
                }
                var heights=new HeightContractPlanner(recipe);var samples=new HashMap<Point2q,ColumnSample>();
                for(var p:heights.samplePoints(geometry,prefix,actual)){
                    int ground=64+curve.slope()*(int)StrictMath.round(2*StrictMath.sin(p.xQ8()/256.0*StrictMath.PI/256));
                    samples.put(p,new ColumnSample(ground,OptionalInt.empty(),OptionalInt.empty(),"minecraft:plains",Quality.REFERENCE,false));
                }
                table=heights.solve(geometry,prefix,actual,samples);
                require(table.breaks().isEmpty(),"gentle analytic height fixture must not hide site seams behind planned cuts");
                sites=new SiteScheduler(recipe).bindHeights(actual,table);
            }
            this.sites=List.copyOf(sites);stations=table.stations();
            plans=new PlanWindow(List.of(new SegmentPlan(geometry,prefix,table,this.sites,H)));
            var templates=new EnumMap<SiteKind,ComponentTemplate>(SiteKind.class);
            KINDS.forEach(k->templates.put(k,template(k,8)));
            planner=new RealizationPlanner(recipe,new SurfaceProbe(),templates);
            var allColumns=new HashMap<Point3i,SurfaceProbe.ColumnView>();var allTiles=new HashMap<TerrainTile.Key,TerrainTile>();
            var dry=new Column(64,-1);var low=new Column(59,-1);var high=new Column(60,-1);var wet=new Column(56,65);
            for(int cz=-4;cz<=3;cz++)for(int cx=-4;cx<=35;cx++) {
                int[] data=new int[48*256];
                for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                    int wx=cx*16+x,wz=cz*16+z;
                    boolean river=water.isPresent()&&contains(water.get().crossing(),wx*256L);
                    var override=terrain.apply(wx,wz);
                    int curveWalk=curve==null?W:WallSection.contract(stations,wx,wz).walkY();
                    if(curve!=null)for(var platform:this.sites){
                        var local=Rasterizer.localCell(platform.frame(),new Point3i(wx,platform.frame().walkY(),wz));
                        var footprint=envelope(platform.id().kind());
                        if(local.x()>=footprint.minX()&&local.x()<footprint.maxXExclusive()
                                &&local.z()>=footprint.minZ()&&local.z()<footprint.maxZExclusive())
                            curveWalk=platform.frame().walkY();
                    }
                    var column=override!=null?override:river?wet:curve!=null?
                            new Column(curveWalk-20-4-Math.floorMod(wx+wz,2),-1):
                            mixed?(Math.floorMod(wx+wz,2)==0?low:high):dry;
                    allColumns.put(new Point3i(wx,0,wz),column);
                    for(int y=48;y<96;y++)data[((y-48)*16+z)*16+x]=y<column.ground?1:column.water(y)?2:0;
                }
                var key=new TerrainTile.Key(DIMENSION,cx,cz,48,95);
                allTiles.put(key,new TerrainTile(key,H,List.of(Column.AIR,Column.STONE,Column.WATER),data));
            }
            columns=Map.copyOf(allColumns);tiles=Map.copyOf(allTiles);
        }
        RealizationPlanner.Input input(long cell) {
            return inputs.computeIfAbsent(cell,c->new RealizationPlanner.Input(new CellKey(H,DIMENSION,c),plans,tiles,columns,
                    protectedBounds,Map.of(),0,digest(protectedBounds.toString())));
        }
        ComponentInput componentInput(SiteRecord site, List<WaterEvidence> waters) {
            return componentInput(site, waters, new DecaySample(DecayState.INTACT,0,20));
        }
        ComponentInput componentInput(SiteRecord site, List<WaterEvidence> waters, DecaySample decay) {
            require(protectedBounds.isEmpty(), "direct template preconditions must not bypass a protection fixture");
            var supports=new HashMap<Point3i,ProbeResult>();var probe=new SurfaceProbe();
            columns.forEach((p,c)->supports.put(new Point3i(p.x(),site.frame().walkY(),p.z()),
                    probe.probe(c,site.frame().walkY(),new DimensionBounds(0,128,29_999_000))));
            return new ComponentInput(site,stations,supports,decay,PaletteFamily.STONE,false,waters,List.of());
        }
        boolean scheduledByHydrology(SiteRecord site) {
            var range=new ArcInterval(Math.max(0,site.globalSQ8()-extent(site.id().kind())*256L),site.globalSQ8()+extent(site.id().kind())*256L);
            return site.state()!=SiteState.SUPPRESSED&&site.state()!=SiteState.ABSORBED&&site.globalSQ8()<terminal*256L
                    &&water.stream().filter(w->w.classification()!=WaterClass.DRY)
                    .noneMatch(w->intersects(range,new ArcInterval(Math.max(0,w.crossing().fromQ8()-8L*256),w.crossing().toQ8()+8L*256)));
        }
        ComponentVoxels scheduledComponent(SiteRecord site) {
            return template(site.id().kind(),8).compile(componentInput(site,List.of(),
                    new DecayField(8).sample(site.globalSQ8(),PaletteFamily.STONE,List.of(site.globalSQ8()))));
        }
        ComponentVoxels waterComponent(WaterEvidence evidence) {
            var maintained=new ArrayList<Long>();
            for(var s:sites)if(scheduledByHydrology(s)&&!scheduledComponent(s).voxels().isEmpty())maintained.add(s.globalSQ8());
            long middle=(evidence.crossing().fromQ8()+evidence.crossing().toQ8())/2;
            var site=site(new SiteId(WALL,SiteKind.WATER_GATE,evidence.crossing().fromQ8()),middle,false);
            return template(SiteKind.WATER_GATE,8).compile(componentInput(site,List.of(evidence),
                    new DecayField(8).sample(middle,PaletteFamily.STONE,maintained)));
        }
        Map<Point3i,String> before(List<RealizationCell> cells) {
            var result=new HashMap<Point3i,String>();
            for(var c:components(cells).values())for(var v:c.voxels()) {
                var column=Objects.requireNonNull(columns.get(new Point3i(v.pos().x(),0,v.pos().z())),"missing full-footprint T0");
                var page=new TerrainTile.Key(DIMENSION,Math.floorDiv(v.pos().x(),16),Math.floorDiv(v.pos().z(),16),48,95);
                require(tiles.containsKey(page)&&v.pos().y()>=48&&v.pos().y()<96,"write outside finite fixture T0 page");
                String captured=tiles.get(page).state(Math.floorMod(v.pos().x(),16),v.pos().y(),Math.floorMod(v.pos().z(),16));
                require(captured.equals(column.state(v.pos().y())),"analytic column and captured page disagree");
                result.put(v.pos(),captured);
            }
            return Map.copyOf(result);
        }
    }

    private static Resolved resolve(Fixture fixture, boolean reverse) {
        var persisted=new ConcurrentHashMap<GroundExitDecision.Key,GroundExitDecision>();
        var surveys=new ConcurrentHashMap<Long,GroundExitDecision.Slice>();
        var votes=new ConcurrentHashMap<GroundExitDecision.Edge,GroundExitDecision.CapVote>();
        var source=new GroundExitResolver.Source() {
            public CompletableFuture<GroundExitDecision.Slice> inspect(CellKey key) {
                try {
                    var slice=fixture.planner.inspect(fixture.input(key.arcCell()));
                    var old=surveys.putIfAbsent(key.arcCell(),slice);
                    require(old==null||old.equals(slice),"same immutable T0 returned contradictory exit surveys");
                    return CompletableFuture.completedFuture(slice);
                }
                catch(Exception|AssertionError e) { return CompletableFuture.failedFuture(e); }
            }
            public CompletableFuture<GroundExitDecision.CapVote> cap(GroundExitDecision.Key key,GroundExitDecision.Edge edge) {
                try {
                    var vote=fixture.planner.probeCap(fixture.input(Math.floorDiv(edge.sQ8(),64L*256)),edge);
                    var old=votes.putIfAbsent(edge,vote);
                    require(old==null||old.equals(vote),"same edge returned contradictory complete cap votes");
                    return CompletableFuture.completedFuture(vote);
                }
                catch(Exception|AssertionError e) { return CompletableFuture.failedFuture(e); }
            }
            public CompletableFuture<Void> persist(GroundExitDecision decision) {
                var previous=persisted.putIfAbsent(decision.key(),decision);
                require(previous==null||previous.equals(decision),"one interval published contradictory decisions");
                return CompletableFuture.completedFuture(null);
            }
        };
        var decisions=new TreeMap<Long,List<GroundExitDecision>>();var cells=new TreeMap<Long,RealizationCell>();
        // Synchronous analytic ports, bounded eight-cell ribbon. This is not a worldgen executor.
        try(var resolver=new GroundExitResolver(source,Runnable::run,4L<<20)) {
            for(int n=0;n<8;n++) {
                long cell=reverse?7-n:n;
                var result=resolver.require(new CellKey(H,DIMENSION,cell));
                require(result.isDone(),"analytic source unexpectedly scheduled external work");
                var agreed=Objects.requireNonNull(result.getNow(null));
                require(!agreed.isEmpty(),"a cell cannot bypass the shared exit certificate");
                for(var decision:agreed) {
                    require(decision.equals(persisted.get(decision.key())),"cell consumed an unpersisted or invented certificate");
                    require(decision.hash().equals(GroundExitCodec.hashed(decision).hash()),"resolver certificate payload/hash mismatch");
                }
                decisions.put(cell,agreed);
                cells.put(cell,fixture.planner.resolve(fixture.input(cell),agreed));
            }
        }
        require(!persisted.isEmpty(),"matrix cannot use invented/prehashed decision certificates");
        var result=new Resolved(List.copyOf(cells.values()),Map.copyOf(decisions),Map.copyOf(surveys),Map.copyOf(votes));
        for(var decision:persisted.values()) {
            for(var exit:java.util.stream.Stream.concat(java.util.stream.Stream.of(decision.left()),decision.right().stream()).toList())
                require(surveys.values().stream().flatMap(s->s.exits().stream()).anyMatch(exit::equals),"certificate substituted a planned site for an inspected natural exit");
            for(var cap:decision.caps()) {
                var vote=votes.get(cap.edge());
                require(vote!=null&&vote.cap().filter(cap::equals).isPresent(),"certificate did not come from the actual complete cap vote");
                var body=components(result.cells()).get(cap.site());
                require(body!=null&&body.hash().equals(cap.componentHash())&&footprint(body,fixture.stations).equals(cap.footprint()),
                        "certified cap was not replayed with the same identity, complete body and footprint");
            }
        }
        return result;
    }

    private static void assertCertifiedConnectivity(Fixture f,Resolved resolved,Map<Point3i,BlockState> states) {
        var values=components(resolved.cells());var collision=new ComponentFixtures.CollisionView(states);
        var ordered=f.sites.stream().sorted(Comparator.comparingLong(SiteRecord::globalSQ8)).toList();
        int checked=0,brokenPairs=0,withdrawnPairs=0;
        for(int i=1;i<ordered.size();i++) {
            var a=ordered.get(i-1);var b=ordered.get(i);var interval=new ArcInterval(a.globalSQ8(),b.globalSQ8());
            boolean broken=resolved.decisions().values().stream().flatMap(List::stream).flatMap(d->d.breaks().stream())
                    .anyMatch(g->intersects(interval,g.range()));
            if(broken){brokenPairs++;continue;}
            if(!values.containsKey(a.id())||!values.containsKey(b.id())){withdrawnPairs++;continue;}
            var ca=values.get(a.id());var cb=values.get(b.id());
            var pa=ca.requiredRoutes().stream().flatMap(List::stream).filter(p->p.y()==a.frame().walkY()).findFirst().orElseThrow();
            var pb=cb.requiredRoutes().stream().flatMap(List::stream).filter(p->p.y()==b.frame().walkY()).findFirst().orElseThrow();
            require(collision.reachable(pa,new Bounds(-64,48,-64,576,96,64)).contains(foot(pb)),
                    "certified unbroken interval has disconnected neighboring station walkways: "+a.id()+" -> "+b.id());
            checked++;
        }
        require(checked>0,"station matrix checked no unbroken neighboring walkway pair");
        System.out.println("S1B_ADJACENCY_CONNECTIVITY checked="+checked+" broken="+brokenPairs+" withdrawn="+withdrawnPairs);
    }

    private static void assertSameResolution(Resolved forward, Resolved reverse) {
        require(forward.decisions().equals(reverse.decisions()), "request order changed an actual exit certificate");
        require(cellHashes(forward.cells()).equals(cellHashes(reverse.cells())), "request order changed a certified cell");
    }

    private static List<GroundExitDecision> uniqueDecisions(Resolved resolved) {
        var result=new HashMap<GroundExitDecision.Key,GroundExitDecision>();
        for(var decisions:resolved.decisions().values())for(var d:decisions) {
            var old=result.putIfAbsent(d.key(),d);
            require(old==null||old.equals(d),"one interval has contradictory consumer certificates");
        }
        return result.values().stream().sorted(Comparator.comparingLong(d->d.key().range().fromQ8())).toList();
    }

    private static boolean excluded(Resolved resolved,long s) {
        return uniqueDecisions(resolved).stream().flatMap(d->d.exclusions().stream()).anyMatch(r->contains(r,s));
    }

    private static void assertSiteOutcomes(Fixture f,Resolved resolved) {
        var values=components(resolved.cells());
        for(var scheduled:f.sites) {
            if(!f.scheduledByHydrology(scheduled)) {
                require(!values.containsKey(scheduled.id()),"hydrology/terminal/suppression allowed a forbidden site");
                continue;
            }
            var compiled=f.scheduledComponent(scheduled);
            require(!compiled.voxels().isEmpty()&&!compiled.requiredRoutes().isEmpty(),
                    "legal station fixture is not independently functional: "+scheduled.id());
            boolean withdrawn=excluded(resolved,scheduled.globalSQ8());
            require(values.containsKey(scheduled.id())!=withdrawn,
                    "complete accepted site must be present or removed by the actual shared exclusion: "+scheduled.id());
            if(!withdrawn)require(values.get(scheduled.id()).hash().equals(compiled.hash()),
                    "site was truncated or replaced instead of publishing its whole actual template: "+scheduled.id());
        }
    }

    /** Check certified ground exits against native shapes and the complete captured natural background. */
    private static void assertNativeGroundExits(Fixture f,Resolved resolved,Map<Point3i,BlockState> states) {
        var exits=new LinkedHashMap<SiteId,GroundExitDecision.Exit>();
        for(var d:uniqueDecisions(resolved)) {
            exits.put(d.left().site(),d.left());d.right().ifPresent(e->exits.put(e.site(),e));
        }
        require(!exits.isEmpty(),"ground-exit assertion must include a real root survey");
        var values=components(resolved.cells());
        for(var exit:exits.values()) {
            var c=values.get(exit.site());
            require(c!=null&&c.hash().equals(exit.componentHash()),"certificate's natural exit body is missing or changed");
            var b=c.bounds();var bounds=new Bounds(b.minX()-2,48,b.minZ()-2,b.maxXExclusive()+2,96,b.maxZExclusive()+2);
            var collisionBounds=new Bounds(bounds.minX()-2,bounds.minY()-1,bounds.minZ()-2,
                    bounds.maxXExclusive()+2,bounds.maxYExclusive()+2,bounds.maxZExclusive()+2);
            var localWrites=new HashMap<Point3i,BlockState>();
            states.forEach((p,s)->{if(inside(collisionBounds,p))localWrites.put(p,s);});
            var local=withNaturalBackground(localWrites,f.columns,List.of(bounds));
            var targets=new HashSet<ComponentFixtures.Foot>();
            for(int z=bounds.minZ();z<bounds.maxZExclusive();z++)for(int x=bounds.minX();x<bounds.maxXExclusive();x++) {
                var column=Objects.requireNonNull(f.columns.get(new Point3i(x,0,z)),"missing native exit apron evidence");
                int ground=column.highestNonPlantFeet();
                if(!column.solidSupport(ground-1)||!column.airOrReplaceablePlant(ground)||!column.airOrReplaceablePlant(ground+1))continue;
                var floor=new Point3i(x,ground-1,z);var feet=new Point3i(x,ground,z);
                if(f.protectedBounds.stream().anyMatch(p->inside(p,floor)||inside(p,feet)))continue;
                require(column.state(ground-1).equals(Column.STONE),"native ground witness differs from analytic stone T0");
                if(x<b.minX()||x>=b.maxXExclusive()||z<b.minZ()||z>=b.maxZExclusive())targets.add(foot(feet));
            }
            int exitW=f.sites.stream().filter(s->s.id().equals(exit.site())).findFirst().orElseThrow().frame().walkY();
            var start=c.requiredRoutes().stream().flatMap(List::stream).filter(p->p.y()==exitW).findFirst().orElseThrow();
            var reached=new ComponentFixtures.CollisionView(local).reachable(start,bounds);
            require(targets.stream().anyMatch(reached::contains),"certified exit cannot reach captured natural ground with native shapes: "+exit.site());
        }
    }

    private static ComponentVoxels capAt(Fixture f,long s,boolean reverse) {
        var original=site(new SiteId(WALL,SiteKind.END_CAP,s*2+(reverse?1:0)),s,reverse);
        var attempt=EndCap.candidates(original,f.stations).getFirst();
        var result=new EndCap(new DecayField(8),ComponentFixtures.PALETTE).compileAt(f.componentInput(attempt,List.of()));
        require(!result.voxels().isEmpty(),"cap boundary calibration needs a real full template");
        return result;
    }

    private static ArcInterval footprint(ComponentVoxels c,List<Station> stations) {
        require(!c.voxels().isEmpty(),"cannot infer an occupied S interval from an empty component");
        long lo=Long.MAX_VALUE,hi=Long.MIN_VALUE;
        for(var v:c.voxels()) {
            long bin=Math.floorDiv(WallSection.contract(stations,v.pos().x(),v.pos().z()).globalSQ8(),256)*256;
            lo=Math.min(lo,bin);hi=Math.max(hi,bin+256);
        }
        return new ArcInterval(lo,hi);
    }

    private static BreakInterval gap(int from,int to) { return new BreakInterval(new ArcInterval(from*256L,to*256L),BreakReason.SLOPE,BreakScope.PLANNED); }
    private static boolean inside(Bounds b,Point3i p) { return p.x()>=b.minX()&&p.x()<b.maxXExclusive()
            &&p.y()>=b.minY()&&p.y()<b.maxYExclusive()&&p.z()>=b.minZ()&&p.z()<b.maxZExclusive(); }

    private static SiteRecord site(SiteKind kind,long ordinal,int s,boolean reverse) { return site(new SiteId(WALL,kind,ordinal),s*256L,reverse); }
    private static SiteRecord site(SiteId id,long s,boolean reverse) {
        var frame=new Frame(new Point2q(s,0),reverse?-(1<<30):1<<30,0,W,reverse);
        int x=FixedMath.floorBlock(s);
        return new SiteRecord(id,s,frame,new Bounds(x-40,48,-40,x+41,96,41),SiteState.PLANNED,Optional.empty(),Optional.empty());
    }
    private static WaterEvidence water(int width,int from,int to) {
        return new WaterEvidence(new ArcInterval(from*256L,to*256L),width<50?WaterClass.NARROW:WaterClass.WIDE,
                width*256L,(to-from)*256L,65,List.of(new Point3i(from-1,64,0),new Point3i(to,64,0)),List.of(new Point3i(256,56,0)),H);
    }
    private static int extent(SiteKind kind) { return switch(kind) {
        case ENEMY_TOWER -> 6;case BEACON -> 5;case GATE -> 30;case FORT -> 24;
        default -> throw new IllegalArgumentException("not a scheduled station");}; }
    private static RealizationCell cell(long ordinal,List<ComponentVoxels> components) {
        return new RealizationCell(new CellKey(H,DIMENSION,ordinal),List.of(),components,H,0,H,
                digest(components.stream().map(ComponentVoxels::hash).sorted().toList().toString()));
    }
    private static Map<SiteId,ComponentVoxels> components(List<RealizationCell> cells) {
        var result=new LinkedHashMap<SiteId,ComponentVoxels>();
        for(var cell:cells)for(var c:cell.components()) {
            var prior=result.putIfAbsent(c.id(),c);
            require(prior==null||prior.hash().equals(c.hash()),"same site has conflicting complete bodies");
        }
        return result;
    }
    private static List<String> cellHashes(List<RealizationCell> cells) { return cells.stream().map(RealizationCell::hash).toList(); }
    private static Bounds occupied(ComponentVoxels c) {
        require(!c.voxels().isEmpty(),"empty body has no adjacency boundary");
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(var v:c.voxels()) {var p=v.pos();x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());
            x1=Math.max(x1,p.x()+1);y1=Math.max(y1,p.y()+1);z1=Math.max(z1,p.z()+1);}
        return new Bounds(x0,y0,z0,x1,y1,z1);
    }
    private static VoxelIntent at(ComponentVoxels c,Point3i p) { return c.voxels().stream().filter(v->v.pos().equals(p)).findFirst().orElse(null); }
    private static Point3i shift(Point3i p,int x,int y,int z) { return new Point3i(p.x()+x,p.y()+y,p.z()+z); }
    private static BlockPos pos(Point3i p) { return new BlockPos(p.x(),p.y(),p.z()); }
    private static ComponentFixtures.Foot foot(Point3i p) { return new ComponentFixtures.Foot(p.x()*2,p.y()*2,p.z()*2); }
    private static ChunkKey owner(Point3i p) { return new ChunkKey(DIMENSION,Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)); }
    private static boolean contains(ArcInterval range,long s) { return s>=range.fromQ8()&&s<range.toQ8(); }
    private static boolean intersects(ArcInterval a,ArcInterval b) { return a.fromQ8()<b.toQ8()&&a.toQ8()>b.fromQ8(); }
    private static String digest(String text) { return DeterministicHash.sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
