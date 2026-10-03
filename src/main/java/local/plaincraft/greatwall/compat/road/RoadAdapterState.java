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

    private record Failure(String type, String message) {
        RuntimeException exception() { return new IllegalStateException(type + ": " + message); }
    }

    RoadAdapterState(UUID session, String wallId, GateLedger ledger, Executor owner, Port port) {
        this.session = Objects.requireNonNull(session);
        this.wallId = Objects.requireNonNull(wallId);
        this.ledger = Objects.requireNonNull(ledger);
        this.owner = Objects.requireNonNull(owner);
        this.port = Objects.requireNonNull(port);
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
                        // freeze() currently only flips state. Do not open a provider passage
                        // until the ledger can persist and expose its computed shared profile.
                        throw profilePending(record);
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
        throw profilePending(record.orElseThrow());
    }

    void adoptFrozen(RoadTypes.GateRecord record, int groundY, List<Integer> profile) {
        checkSession(session);
        Objects.requireNonNull(record);
        checkedId(encode(record.contract().id()));
        if (!isFrozen(record) || !ledger.find(record.contract().id()).filter(record::equals).isPresent()) {
            throw new IllegalStateException("only the current durable frozen record can be adopted");
        }
        if (record.contract().groundY() != groundY || List.copyOf(profile).isEmpty()
                || profile.getFirst() != groundY) {
            throw new IllegalArgumentException("profile does not match the durable gate ground");
        }
        throw profilePending(record);
    }

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

    private static IllegalStateException profilePending(RoadTypes.GateRecord record) {
        return new IllegalStateException("GateLedger freeze solver/durable sharedProfile unavailable; "
                + "agreement pending for " + encode(record.contract().id())
                + " revision=" + record.revision() + " groundY=" + record.contract().groundY());
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
