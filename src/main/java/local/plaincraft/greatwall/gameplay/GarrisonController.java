package local.plaincraft.greatwall.gameplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallTypes.DecayState;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.raid.Raider;

/**
 * Permanent-garrison policy. Every birth consumes a one-shot durable slot;
 * a kill, an unload, a peace setting or a restart never refunds it, so the
 * wall can never be farmed for infinite mobs. This is deliberately
 * at-most-once: a crash between the durable consume and the entity add
 * loses one mob and is reported instead of being silently regenerated.
 */
public final class GarrisonController implements AutoCloseable {
    /** Global live+reserved ceiling across all owned sites. */
    public static final int MAX_LIVE_AND_RESERVED = 64;
    /** Only these spawn when a tower slot fires; the 2:1 mix is fixed by slot order. */
    private static final List<String> TOWER_SLOT_TYPES =
            List.of("minecraft:pillager", "minecraft:pillager", "minecraft:vindicator");
    private static final long MIN_PLAYER_DISTANCE_SQUARED = 250_000L;

    /** Pure spawn policy; unit-testable with no world. */
    public static boolean allowed(GameplayTypes.PolicyInput policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.peaceful() || !policy.mobSpawning()) return false;
        if (!policy.playerNear()) return false;
        if (policy.nearestPlayerDistanceSquared() <= MIN_PLAYER_DISTANCE_SQUARED) return false;
        long dayTime = Math.floorMod(policy.dayTime(), 24_000L);
        if (dayTime < 13_000L || dayTime >= 23_000L) return false;
        return policy.decay() == DecayState.INTACT || policy.decay() == DecayState.PARTIAL;
    }

    private final ServerLevel level;
    private final WorldRecipe recipe;
    private final DurableLog journal;
    private final SiteRuntimeIndex sites;
    private final ConcurrentHashMap<UUID, GameplayTypes.OwnedMob> alive = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, GameplayTypes.SpawnPermit> reserved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Optional<GameplayTypes.SpawnPermit>>> inFlight =
            new ConcurrentHashMap<>();

    public GarrisonController(ServerLevel level, WorldRecipe recipe, DurableLog journal,
            SiteRuntimeIndex sites) {
        this.level = Objects.requireNonNull(level, "level");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.sites = Objects.requireNonNull(sites, "sites");
    }

    public int loadedAndReserved() {
        return alive.size() + reserved.size() + inFlight.size();
    }

    /** Reserves a slot by consuming it durably before any entity exists. */
    public CompletionStage<Optional<GameplayTypes.SpawnPermit>> reserve(GameplayTypes.SpawnRequest request) {
        Objects.requireNonNull(request, "request");
        if (loadedAndReserved() >= MAX_LIVE_AND_RESERVED) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (request.site().kind() != SiteKind.ENEMY_TOWER && request.site().kind() != SiteKind.FORT) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID entity = local.plaincraft.greatwall.geometry.DeterministicHash.entityUuid(
                request.site(), request.ordinal());
        String key = slotKey(request.site(), request.localSlot(), request.ordinal());
        CompletableFuture<Optional<GameplayTypes.SpawnPermit>> fresh = new CompletableFuture<>();
        CompletableFuture<Optional<GameplayTypes.SpawnPermit>> existing = inFlight.putIfAbsent(key, fresh);
        if (existing != null) return existing;
        HistoryTypes.SlotKey slot;
        try {
            slot = new HistoryTypes.SlotKey(request.site(), request.localSlot(), 0L);
        } catch (IllegalArgumentException unsupported) {
            inFlight.remove(key, fresh);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // The durable consume is the authority: once it exists the slot is gone.
        journal.lookup(HistoryTypes.Kind.SLOT_CONSUMED.name(),
                        "slot/" + key)
                .thenComposeAsync(found -> {
                    if (found.isPresent()) {
                        fresh.complete(Optional.empty());
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    var fields = new java.util.LinkedHashMap<String, String>();
                    fields.put("site", request.site().wallId() + "/" + request.site().kind().name()
                            + "/" + request.site().ordinal());
                    fields.put("slot", Integer.toString(request.localSlot()));
                    fields.put("entity", entity.toString());
                    fields.put("type", request.entityType());
                    DurableLog.Entry entry = new DurableLog.Entry("slot/" + key,
                            HistoryTypes.Kind.SLOT_CONSUMED.name(), 1, fields);
                    return journal.append(entry).thenAcceptAsync(receipt -> {
                        var permit = new GameplayTypes.SpawnPermit(request.site(), request.localSlot(),
                                request.ordinal(), request.feet(), request.entityType(), entity,
                                receipt.sequence(), request.session());
                        reserved.put(entity, permit);
                        fresh.complete(Optional.of(permit));
                    });
                }, level.getServer())
                .exceptionally(failure -> {
                    fresh.complete(Optional.empty());
                    return null;
                })
                .whenComplete((ignored, failure) -> inFlight.remove(key, fresh));
        return fresh;
    }

    /** Adds the entity on the server owner; the slot stays consumed either way. */
    public boolean spawn(GameplayTypes.SpawnPermit permit) {
        Objects.requireNonNull(permit, "permit");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("spawn requires the server owner");
        if (!reserved.containsKey(permit.entity())) return false;
        EntityType<?> type = EntityType.byString(permit.entityType()).orElse(null);
        if (type == null) {
            release(permit, HistoryTypes.SpawnResult.REFUSED);
            return false;
        }
        Entity created = type.create(level);
        if (!(created instanceof Mob mob)) {
            release(permit, HistoryTypes.SpawnResult.REFUSED);
            return false;
        }
        mob.setUUID(permit.entity());
        mob.moveTo(permit.feet().x() + 0.5, permit.feet().y(), permit.feet().z() + 0.5, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        if (mob instanceof Raider raider) raider.setCanJoinRaid(false);
        reserved.remove(permit.entity());
        alive.put(permit.entity(), new GameplayTypes.OwnedMob(permit.site(), permit.ordinal(),
                permit.entity(), permit.sequence()));
        // addFreshEntity can still refuse; the slot is not refunded either way.
        boolean added = level.addFreshEntity(mob);
        appendResult(permit, added ? HistoryTypes.SpawnResult.ADDED : HistoryTypes.SpawnResult.REFUSED);
        if (!added) alive.remove(permit.entity());
        return added;
    }

    public void loaded(Entity entity) {
        if (entity == null) return;
        var owned = alive.get(entity.getUUID());
        if (owned == null) return; // a player-summoned or vanilla mob is not ours
        // Conservative: a re-loaded owned mob keeps occupying quota.
        alive.putIfAbsent(entity.getUUID(), owned);
    }

    public void unloaded(Entity entity) {
        if (entity == null) return;
        alive.remove(entity.getUUID());
    }

    /** Releases a reservation and records the outcome; the slot stays consumed. */
    public void release(GameplayTypes.SpawnPermit permit, HistoryTypes.SpawnResult result) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(result, "result");
        if (reserved.remove(permit.entity()) == null && alive.remove(permit.entity()) == null) return;
        appendResult(permit, result);
    }

    /** Retires the newest owned mobs when a reload pushes past the ceiling. */
    public List<UUID> retiredOnOverflow() {
        if (loadedAndReserved() <= MAX_LIVE_AND_RESERVED) return List.of();
        List<GameplayTypes.OwnedMob> ordered = new ArrayList<>(alive.values());
        ordered.sort(Comparator.comparingLong(GameplayTypes.OwnedMob::sequence)
                .thenComparing(mob -> mob.entity().toString()));
        int keep = Math.max(0, MAX_LIVE_AND_RESERVED - reserved.size());
        List<UUID> retired = new ArrayList<>();
        for (int i = keep; i < ordered.size(); i++) {
            UUID entity = ordered.get(i).entity();
            alive.remove(entity);
            retired.add(entity);
        }
        return List.copyOf(retired);
    }

    public int liveOwned() { return alive.size(); }

    public void close() {
        for (var pending : inFlight.values()) pending.complete(Optional.empty());
        inFlight.clear();
        reserved.clear();
        alive.clear();
    }

    private void appendResult(GameplayTypes.SpawnPermit permit, HistoryTypes.SpawnResult result) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("entity", permit.entity().toString());
        fields.put("result", result.name());
        fields.put("type", permit.entityType());
        DurableLog.Entry entry = new DurableLog.Entry(
                "spawn/" + permit.entity() + "/" + result.name(),
                HistoryTypes.Kind.MOB_SPAWN_RESULT.name(), 1, fields);
        journal.append(entry);
    }

    private static String slotKey(SiteId site, int slot, int ordinal) {
        return site.wallId() + "/" + site.kind().name() + "/" + site.ordinal() + "/" + slot + "/" + ordinal;
    }

    static List<String> towerSlotTypes() { return TOWER_SLOT_TYPES; }

    static boolean isOwnedType(Entity entity) {
        return entity instanceof Pillager || entity instanceof Vindicator;
    }

    static Point3i feetOf(Entity entity) {
        return new Point3i((int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()),
                (int) Math.floor(entity.getZ()));
    }
}
