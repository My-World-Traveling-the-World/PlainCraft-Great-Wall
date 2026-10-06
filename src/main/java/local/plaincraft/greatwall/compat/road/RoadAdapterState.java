package local.plaincraft.greatwall.compat.road;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.persistence.GateLedger;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes;

/** Minecraft-free request ownership and lifecycle for the optional road bridge. */
final class RoadAdapterState {
    private static final String PREFIX = "plaincraft_great_wall:v1/";
    static final int MAX_PLANNED_GATES = 4096;

    interface Port {
        void validateSession();
        CompletionStage<Void> whenReady();
        void registerEndpoint(RoadTypes.GateContract gate);
        void ensureConnection(RoadTypes.GateContract from, RoadTypes.GateContract to);
        void failed(Throwable failure);
    }

    private final UUID session;
    private final String wallId;
    private final GateLedger ledger;
    private final Executor owner;
    private final Port port;
    private final ConcurrentHashMap<String, Boolean> lifecycle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GateId, RoadTypes.GateContract> contracts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompletableFuture<?>, Boolean> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompletableFuture<?>, Boolean> sideEffects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Failure> failures = new ConcurrentHashMap<>();
    /** Solved shared profiles, keyed by gate. Owner-confined. */
    private final ConcurrentHashMap<GateId, GateFreezeSolver.Outcome.Frozen> agreements = new ConcurrentHashMap<>();
    /**
     * Admissible gate-height bounds for this wall.
     *
     * <p>A gate is a fixed 4-wide, 5-high aperture, so the floor must leave room for the opening below
     * the parapet while never cutting through the wall's crown. These bounds are the wall's own
     * contract, not a per-road preference.
     */
    private final int wallFloorY;
    private final int wallCeilingY;
    /** False when no explicit wall bounds were supplied, so the contract alone defines the window. */
    private final boolean wallBoundsConfigured;

    private record Failure(String type, String message) {
        RuntimeException exception() { return new IllegalStateException(type + ": " + message); }
    }

    RoadAdapterState(UUID session, String wallId, GateLedger ledger, Executor owner, Port port) {
        this(session, wallId, ledger, owner, port, 0, 0);
    }

    /**
     * @param wallFloor   lowest Y a gate floor may occupy on this wall
     * @param wallCeiling highest Y a gate floor may occupy; the 5-high aperture sits above it
     */
    RoadAdapterState(UUID session, String wallId, GateLedger ledger, Executor owner, Port port,
                     int wallFloor, int wallCeiling) {
        this.session = Objects.requireNonNull(session);
        this.wallId = Objects.requireNonNull(wallId);
        this.ledger = Objects.requireNonNull(ledger);
        this.owner = Objects.requireNonNull(owner);
        this.port = Objects.requireNonNull(port);
        // Unset bounds mean "not configured": the solver then treats the whole range as inadmissible
        // and reports a conflict rather than inventing a window.
        this.wallFloorY = wallFloor;
        this.wallCeilingY = wallCeiling;
        // A non-positive ceiling cannot be a real gate height, so treat it as "not supplied" rather
        // than clamping every gate against an impossible bound.
        this.wallBoundsConfigured = wallCeiling > wallFloor;
    }

    static String encode(GateId id) {
        Objects.requireNonNull(id);
        if (id.wallId().isEmpty()) throw new IllegalArgumentException("empty wall id");
        byte[] bytes = id.wallId().getBytes(StandardCharsets.UTF_8);
        if (!new String(bytes, StandardCharsets.UTF_8).equals(id.wallId())) {
            throw new IllegalArgumentException("wall id is not valid UTF-8");
        }
        return PREFIX + HexFormat.of().formatHex(bytes) + "/" + id.namespace() + "/" + id.ordinal();
    }

    static GateId decode(String value) {
        Objects.requireNonNull(value, "gateId");
        if (!value.startsWith(PREFIX)) throw new IllegalArgumentException("unsupported gate id encoding");
        String[] parts = value.substring(PREFIX.length()).split("/", -1);
        if (parts.length != 3 || parts[0].isEmpty()) throw new IllegalArgumentException("invalid gate id");
        try {
            String wall = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(HexFormat.of().parseHex(parts[0]))).toString();
            GateId id = new GateId(wall, parts[1], Long.parseLong(parts[2]));
            if (!encode(id).equals(value)) throw new IllegalArgumentException("noncanonical gate id");
            return id;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("invalid gate id UTF-8", malformed);
        }
    }

    CompletableFuture<Void> registerEndpoint(RoadTypes.GateContract gate) {
        remember(gate);
        return dispatch(() -> port.registerEndpoint(gate));
    }

    CompletableFuture<Void> ensureConnection(RoadTypes.GateContract from, RoadTypes.GateContract to) {
        remember(from);
        remember(to);
        Comparator<RoadTypes.GateContract> order = Comparator
                .comparingInt((RoadTypes.GateContract gate) -> FixedMath.floorBlock(gate.frame().center().xQ8()))
                .thenComparingInt(gate -> FixedMath.floorBlock(gate.frame().center().zQ8()))
                .thenComparing(gate -> encode(gate.id()));
        RoadTypes.GateContract first = order.compare(from, to) <= 0 ? from : to;
        RoadTypes.GateContract second = first == from ? to : from;
        return dispatch(() -> {
            port.registerEndpoint(first);
            checkSession(session);
            port.registerEndpoint(second);
            checkSession(session);
            port.ensureConnection(first, second);
        });
    }

    CompletionStage<WallInteropTypes.Agreement> propose(UUID requestSession, String gateId,
            long expectedRevision, List<WallInteropTypes.Approach> approaches, List<String> batch) {
        try {
            checkSession(requestSession);
            GateId id = checkedId(gateId);
            if (expectedRevision < 0) throw new IllegalArgumentException("negative revision");
            List<String> copiedBatch = List.copyOf(batch).stream().distinct().sorted().toList();
            if (copiedBatch.isEmpty() || copiedBatch.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("a closed, nonempty road batch is required");
            }
            List<RoadTypes.Approach> copiedApproaches = convert(approaches);
            if (copiedApproaches.stream().anyMatch(value -> !copiedBatch.contains(value.roadId()))) {
                throw new IllegalArgumentException("approach is not in the closed batch");
            }
            CompletionStage<WallInteropTypes.Agreement> work = CompletableFuture
                    .supplyAsync(() -> {
                        checkSession(requestSession);
                        var existing = ledger.find(id);
                        if (existing.isPresent()) {
                            var record = existing.orElseThrow();
                            if (record.state() == RoadTypes.GateState.CONFLICT
                                    || record.state() == RoadTypes.GateState.RETIRED) {
                                throw new IllegalStateException("gate is " + record.state());
                            }
                            if (!isFrozen(record) && !record.batchRoadIds().equals(copiedBatch)) {
                                throw new IllegalStateException("gate closed batch cannot change");
                            }
                            return record.contract();
                        }
                        var planned = contracts.get(id);
                        if (planned == null) {
                            throw new IllegalStateException("gate plan/history not prepared; agreement pending");
                        }
                        return planned;
                    }, owner)
                    .thenComposeAsync(planned -> {
                        checkSession(requestSession);
                        return ledger.propose(planned, copiedApproaches, copiedBatch, expectedRevision);
                    }, owner)
                    .thenApplyAsync(record -> {
                        checkSession(requestSession);
                        contracts.remove(record.contract().id(), record.contract());
                        // The gate height is solved from every approach that must share this opening,
                        // not taken from one road. A conflict is a real answer: the caller is told the
                        // gate cannot be frozen and the roads must adapt, rather than being handed a
                        // height only some of them can reach.
                        // The gate floor must equal the ground the wall was durably built to, so the
                        // solver is given a window that pins exactly that height. Expressing it through
                        // the solver's parapet-relative convention keeps one definition of "admissible
                        // gate height" instead of a second one here. Terrain that cannot reach this
                        // ground is then a genuine conflict, which is the correct answer: the roads
                        // must adapt to the wall, not the reverse.
                        int groundY = record.contract().groundY();
                        var outcome = GateFreezeSolver.solve(copiedApproaches,
                                terrainSamples(record), groundY + GATE_CROWN_CLEARANCE, groundY);
                        if (outcome instanceof GateFreezeSolver.Outcome.Conflict conflict) {
                            throw new IllegalStateException("gate " + encode(record.contract().id())
                                    + " cannot be frozen: " + conflict.reason());
                        }
                        var solved = (GateFreezeSolver.Outcome.Frozen) outcome;
                        agreements.put(record.contract().id(), solved);
                        return new WallInteropTypes.Agreement(
                                encode(record.contract().id()), record.revision(),
                                solved.groundY(), solved.sharedProfile(), true,
                                physicallyAcked(record), record.contract().hash());
                    }, owner);
            return track(work);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    Optional<WallInteropTypes.Agreement> frozen(UUID requestSession, String gateId) {
        checkSession(requestSession);
        GateId id = checkedId(gateId);
        Optional<RoadTypes.GateRecord> record = ledger.find(id);
        if (record.isEmpty() || !isFrozen(record.orElseThrow())) return Optional.empty();
        var frozenRecord = record.orElseThrow();
        var solved = agreements.get(id);
        if (solved == null) return Optional.empty();
        return Optional.of(new WallInteropTypes.Agreement(encode(id), frozenRecord.revision(),
                solved.groundY(), solved.sharedProfile(), true, physicallyAcked(frozenRecord),
                frozenRecord.contract().hash()));
    }

    void adoptFrozen(RoadTypes.GateRecord record, int groundY, List<Integer> profile) {
        checkSession(session);
        Objects.requireNonNull(record);
        checkedId(encode(record.contract().id()));
        if (!isFrozen(record) || !ledger.find(record.contract().id()).filter(record::equals).isPresent()) {
            throw new IllegalStateException("only the current durable frozen record can be adopted");
        }
        // An adopted profile is external input, so it is validated against the same rules the solver
        // guarantees rather than trusted because it arrived with a frozen record.
        GateFreezeSolver.requireAdoptable(groundY, profile);
        if (record.contract().groundY() != groundY) {
            throw new IllegalArgumentException("profile does not match the durable gate ground");
        }
        agreements.put(record.contract().id(),
                new GateFreezeSolver.Outcome.Frozen(groundY, List.copyOf(profile)));
    }

    /**
     * True only when every affected chunk has been ACKed.
     *
     * <p>Readiness is derived from durable chunk progress rather than a flag: a gate whose opening has
     * not actually been cut in every affected chunk must not be advertised as physically passable, and
     * a conflicting chunk keeps it unready by construction.
     */
    private static boolean physicallyAcked(RoadTypes.GateRecord record) {
        var affected = record.contract().affected();
        if (affected.isEmpty()) return false;
        for (var owner : affected) {
            var progress = record.progress().get(owner);
            if (progress == null || progress.state() != RoadTypes.ChunkGateState.ACKED) return false;
        }
        return true;
    }

    /**
     * Terrain heights the roads present at this gate.
     *
     * <p>The contract already records the ground the wall was built to, and every approach must meet
     * that ground; the aperture is a fixed 4×5 opening, so there is a single height to agree on rather
     * than a per-road profile. The solver still validates it against the wall window, which is what
     * turns an impossible meeting into an explicit conflict.
     */
    private List<Integer> terrainSamples(RoadTypes.GateRecord record) {
        return List.of(record.contract().groundY());
    }

    /** The admissible gate height window, derived from the wall's own bounds. */
    private int wallFloor() { return wallFloorY; }

    /** The highest admissible gate floor for this wall. */
    private int wallCeiling() { return wallCeilingY; }

    /** Halves of the admissible window around a frozen gate ground. */
    private static final int GATE_WINDOW_TOLERANCE = 4;
    /**
     * Offset that pins the solver's admissible window to the gate's durable ground.
     *
     * <p>The solver derives its window as {@code [top - 32, top - 7]}. Supplying
     * {@code top = ground + 7} therefore yields exactly {@code [ground - 25, ground]}, and the
     * intersection with the ground-inclusive lower bound leaves precisely the durable ground. That
     * keeps a single definition of "admissible gate height" — the solver's — instead of duplicating the
     * rule here.
     */
    private static final int GATE_CROWN_CLEARANCE = 7;

    CompletionStage<Void> pendingSideEffects() {
        Failure failure = failures.get("sideEffect");
        if (failure != null) return CompletableFuture.failedFuture(failure.exception());
        return CompletableFuture.allOf(sideEffects.keySet().toArray(CompletableFuture[]::new))
                .thenRun(() -> {
                    Failure later = failures.get("sideEffect");
                    if (later != null) throw later.exception();
                });
    }

    boolean stop() {
        if (lifecycle.putIfAbsent("closed", Boolean.TRUE) != null) return false;
        pending.keySet().forEach(future -> future.completeExceptionally(
                new CancellationException("RoadWeaver adapter closed")));
        synchronized (contracts) {
            contracts.clear();
        }
        return true;
    }

    private CompletableFuture<Void> dispatch(Runnable action) {
        checkSession(session);
        CompletionStage<Void> work = port.whenReady().thenRunAsync(() -> {
            checkSession(session);
            action.run();
        }, owner);
        CompletableFuture<Void> result = track(work);
        sideEffects.put(result, Boolean.TRUE);
        result.whenComplete((ignored, failure) -> {
            if (failure != null) {
                failures.putIfAbsent("sideEffect", new Failure(failure.getClass().getName(),
                        Objects.toString(failure.getMessage(), "")));
                port.failed(failure);
            }
            sideEffects.remove(result);
        });
        return result;
    }

    private <T> CompletableFuture<T> track(CompletionStage<T> work) {
        var result = new CompletableFuture<T>();
        pending.put(result, Boolean.TRUE);
        result.whenComplete((ignored, failure) -> pending.remove(result));
        if (lifecycle.containsKey("closed")) result.completeExceptionally(new CancellationException("RoadWeaver adapter closed"));
        work.whenComplete((value, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
            else if (lifecycle.containsKey("closed")) result.completeExceptionally(new CancellationException("RoadWeaver adapter closed"));
            else result.complete(value);
        });
        return result;
    }

    private void remember(RoadTypes.GateContract gate) {
        checkSession(session);
        Objects.requireNonNull(gate);
        checkedId(encode(gate.id()));
        synchronized (contracts) {
            checkSession(session);
            var previous = contracts.get(gate.id());
            if (previous != null && !previous.equals(gate)) {
                throw new IllegalStateException("same gate id with different planned contract");
            }
            if (previous == null) {
                if (contracts.size() >= MAX_PLANNED_GATES) {
                    throw new IllegalStateException("planned gate registry full; endpoint pending");
                }
                contracts.put(gate.id(), gate);
            }
        }
    }

    private GateId checkedId(String gateId) {
        GateId id = decode(gateId);
        if (!wallId.equals(id.wallId())) throw new IllegalArgumentException("gate belongs to another wall");
        return id;
    }

    private void checkSession(UUID requestSession) {
        if (lifecycle.containsKey("closed")) throw new CancellationException("RoadWeaver adapter closed");
        if (!session.equals(Objects.requireNonNull(requestSession))) {
            throw new IllegalStateException("RoadWeaver wall runtime session mismatch");
        }
        port.validateSession();
    }

    private static boolean isFrozen(RoadTypes.GateRecord record) {
        return record.state() == RoadTypes.GateState.FROZEN
                || record.state() == RoadTypes.GateState.PENDING_CHUNKS
                || record.state() == RoadTypes.GateState.APPLIED;
    }

    private static List<RoadTypes.Approach> convert(List<WallInteropTypes.Approach> approaches) {
        var sorted = new TreeMap<String, RoadTypes.Approach>();
        for (var approach : List.copyOf(approaches)) {
            if (approach.roadId().isBlank()) throw new IllegalArgumentException("empty road id");
            var points = new ArrayList<Point3i>();
            for (var pos : approach.stations()) points.add(new Point3i(pos.x(), pos.y(), pos.z()));
            var converted = new RoadTypes.Approach(approach.roadId(), points);
            var previous = sorted.putIfAbsent(converted.roadId(), converted);
            if (previous != null && !previous.equals(converted)) {
                throw new IllegalArgumentException("same road id with different approach");
            }
        }
        if (sorted.isEmpty()) throw new IllegalArgumentException("nonempty approaches required");
        return List.copyOf(sorted.values());
    }
}
