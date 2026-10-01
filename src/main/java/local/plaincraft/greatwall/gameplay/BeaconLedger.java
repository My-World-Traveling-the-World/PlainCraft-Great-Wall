package local.plaincraft.greatwall.gameplay;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.HistoryTypes;

/**
 * Permanent beacon-credit ledger. A credit exists only after the durable
 * receipt returns; the in-memory state is an immutable published snapshot.
 * Membership is a hot exact map with an empty-means-unread contract.
 */
public final class BeaconLedger implements AutoCloseable {
    private static final int MAX_PENDING = 128;
    private static final int MAX_HOT_ENTRIES = 4096;

    private final UUID session;
    private final String wallId;
    private final DurableLog journal;
    private final Executor serverOwner;
    private final ConcurrentHashMap<BeaconId, CompletableFuture<GameplayTypes.CreditResult>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BeaconId, Boolean> hotMembership = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile GameplayTypes.BeaconProgress progress;
    private volatile String pendingError;

    public BeaconLedger(UUID session, String wallId, DurableLog journal, Executor serverOwner,
            GameplayTypes.BeaconProgress recovered) {
        this.session = Objects.requireNonNull(session, "session");
        this.wallId = Objects.requireNonNull(wallId, "wallId");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.serverOwner = Objects.requireNonNull(serverOwner, "serverOwner");
        this.progress = Objects.requireNonNull(recovered, "recovered");
    }

    public GameplayTypes.BeaconProgress progress() { return progress; }

    public String pendingError() { return pendingError; }

    /** Empty means "not yet read", never "false". */
    public Optional<Boolean> known(BeaconId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(hotMembership.get(id));
    }

    public CompletionStage<Boolean> requireCredited(BeaconId id) {
        Objects.requireNonNull(id, "id");
        if (!wallId.equals(id.wallId())) {
            return CompletableFuture.completedFuture(false);
        }
        Boolean hot = hotMembership.get(id);
        if (hot != null) return CompletableFuture.completedFuture(hot);
        String eventKey = eventKey(id);
        return journal.lookup(HistoryTypes.Kind.BEACON_CREDIT.name(), eventKey)
                .thenApplyAsync(found -> {
                    boolean credited = found.isPresent();
                    rememberMembership(id, credited);
                    return credited;
                }, serverOwner);
    }

    public CompletionStage<GameplayTypes.CreditResult> credit(GameplayTypes.VerifiedIgnition ignition) {
        Objects.requireNonNull(ignition, "ignition");
        if (closed.get()) {
            return CompletableFuture.completedFuture(new GameplayTypes.CreditResult(
                    GameplayTypes.CreditStatus.REFUSED_SESSION, progress.credited(), ignition.player(),
                    0L, true, "ledger is closed"));
        }
        if (!ignition.lit() || ignition.waterlogged() || !ignition.accepted()) {
            return CompletableFuture.completedFuture(new GameplayTypes.CreditResult(
                    GameplayTypes.CreditStatus.REFUSED_NOT_LIT, progress.credited(), ignition.player(),
                    0L, true, "ignition was not an accepted lit non-waterlogged pit"));
        }
        BeaconId id = ignition.id();
        if (!wallId.equals(id.wallId())) {
            return CompletableFuture.completedFuture(new GameplayTypes.CreditResult(
                    GameplayTypes.CreditStatus.REFUSED_SESSION, progress.credited(), ignition.player(),
                    0L, true, "beacon belongs to another wall"));
        }
        if (hotMembership.getOrDefault(id, false)) {
            return CompletableFuture.completedFuture(new GameplayTypes.CreditResult(
                    GameplayTypes.CreditStatus.ALREADY_COMMITTED, progress.credited(), ignition.player(),
                    0L, true, "already credited"));
        }
        if (pending.size() >= MAX_PENDING && !pending.containsKey(id)) {
            return CompletableFuture.completedFuture(new GameplayTypes.CreditResult(
                    GameplayTypes.CreditStatus.DEFERRED_IO, progress.credited(), ignition.player(),
                    0L, false, "pending set is full"));
        }
        CompletableFuture<GameplayTypes.CreditResult> fresh = new CompletableFuture<>();
        CompletableFuture<GameplayTypes.CreditResult> existing = pending.putIfAbsent(id, fresh);
        if (existing != null) {
            return CompletableFuture.completedFuture(new GameplayTypes.CreditResult(
                    GameplayTypes.CreditStatus.ALREADY_PENDING, progress.credited(), ignition.player(),
                    0L, false, "a credit for this beacon is already in flight"));
        }
        Boolean known = hotMembership.get(id);
        CompletionStage<Boolean> gate = known != null
                ? CompletableFuture.completedFuture(known)
                : requireCredited(id);
        gate.thenComposeAsync(credited -> {
            if (credited) {
                fresh.complete(new GameplayTypes.CreditResult(GameplayTypes.CreditStatus.ALREADY_COMMITTED,
                        progress.credited(), ignition.player(), 0L, true, "already credited on disk"));
                return CompletableFuture.<Void>completedFuture(null);
            }
            String eventKey = eventKey(id);
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("wallId", wallId);
            fields.put("ordinal", Long.toString(id.ordinal()));
            fields.put("player", ignition.player().toString());
            fields.put("tool", ignition.tool());
            fields.put("gameTime", Long.toString(ignition.gameTime()));
            fields.put("pitX", Integer.toString(ignition.pitFeet().x()));
            fields.put("pitY", Integer.toString(ignition.pitFeet().y()));
            fields.put("pitZ", Integer.toString(ignition.pitFeet().z()));
            DurableLog.Entry entry = new DurableLog.Entry(eventKey,
                    HistoryTypes.Kind.BEACON_CREDIT.name(), 1, fields);
            return journal.append(entry).thenAcceptAsync(receipt -> {
                // The durable receipt is the only thing that increments the count.
                long creditedCount = progress.credited() + 1L;
                rememberMembership(id, true);
                progress = new GameplayTypes.BeaconProgress(creditedCount, progress.pendingUnknown(),
                        progress.targetCount(), progress.degraded(), progress.ready());
                fresh.complete(new GameplayTypes.CreditResult(GameplayTypes.CreditStatus.COMMITTED,
                        creditedCount, ignition.player(), receipt.sequence(), true, "committed"));
            }, serverOwner);
        }, serverOwner).exceptionally(failure -> {
            pendingError = failure.getMessage();
            fresh.complete(new GameplayTypes.CreditResult(GameplayTypes.CreditStatus.DEFERRED_IO,
                    progress.credited(), ignition.player(), 0L, false,
                    "durable append failed: " + failure.getMessage()));
            return null;
        }).whenComplete((ignored, failure) -> pending.remove(id, fresh));
        return fresh;
    }

    /** Retries at most one deferred credit; exponential backoff is owned by the caller. */
    public void retryPending() {
        pendingError = null;
    }

    private void rememberMembership(BeaconId id, boolean credited) {
        if (hotMembership.size() >= MAX_HOT_ENTRIES && !hotMembership.containsKey(id)) {
            return; // pinned guard: never evict a live entry, just stop growing
        }
        hotMembership.put(id, credited);
    }

    private String eventKey(BeaconId id) {
        return "beacon/" + id.wallId() + "/" + id.ordinal();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (CompletableFuture<GameplayTypes.CreditResult> view : pending.values()) {
            view.complete(new GameplayTypes.CreditResult(GameplayTypes.CreditStatus.REFUSED_SESSION,
                    progress.credited(), session, 0L, false, "ledger closed"));
        }
        pending.clear();
    }
}
