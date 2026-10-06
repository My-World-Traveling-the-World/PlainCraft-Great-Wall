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
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.model.WallTypes.DecayState;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.SpawnSlot;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;

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
    /**
     * 出生触发窗口：玩家必须在这个距离带内，驻军才会被激活。
     *
     * <p>2026-10-06 修正：规格 S3 同时写了「distance² &gt; 250000」（&ge;500 格）和
     * 「64 格内且至少 24 格」两条距离规则，两者数学上不可同时满足（24..64 格的
     * distance² 恒 &le; 4096 &lt; 250000），导致 {@link #allowed} 对 tick 传入的任何
     * 合法值都返回 false、驻军永远不出生。这里按规格自身的触发语义统一为
     * 「24..64 格」一带：远离玩家由下限 24 格保证（不在玩家脸上刷怪），
     * 上限 64 格保证只在该站点附近有玩家时才激活。全局 500 格门已删除。</p>
     */
    public static final double SPAWN_MIN_DISTANCE = 24.0D;
    public static final double SPAWN_MAX_DISTANCE = 64.0D;
    private static final long SPAWN_MIN_DISTANCE_SQUARED = 24L * 24L;
    private static final long SPAWN_MAX_DISTANCE_SQUARED = 64L * 64L;

    /** Pure spawn policy; unit-testable with no world. */
    public static boolean allowed(GameplayTypes.PolicyInput policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.peaceful() || !policy.mobSpawning()) return false;
        if (!policy.playerNear()) return false;
        // 单一距离带：太近不在玩家脸上刷怪，太远说明该站点无人激活。
        long distanceSquared = policy.nearestPlayerDistanceSquared();
        if (distanceSquared < SPAWN_MIN_DISTANCE_SQUARED
                || distanceSquared > SPAWN_MAX_DISTANCE_SQUARED) return false;
        long dayTime = Math.floorMod(policy.dayTime(), 24_000L);
        if (dayTime < 13_000L || dayTime >= 23_000L) return false;
        return policy.decay() == DecayState.INTACT || policy.decay() == DecayState.PARTIAL;
    }

    private final ServerLevel level;
    private final WorldRecipe recipe;
    private final DurableLog journal;
    private final SiteRuntimeIndex sites;
    private final UUID session;
    private final ConcurrentHashMap<UUID, GameplayTypes.OwnedMob> alive = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, GameplayTypes.SpawnPermit> reserved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Optional<GameplayTypes.SpawnPermit>>> inFlight =
            new ConcurrentHashMap<>();
    private int tickCounter;
    private int slotCursor;

    public GarrisonController(UUID session, ServerLevel level, WorldRecipe recipe, DurableLog journal,
            SiteRuntimeIndex sites) {
        this.session = Objects.requireNonNull(session, "session");
        this.level = Objects.requireNonNull(level, "level");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.sites = Objects.requireNonNull(sites, "sites");
    }

    public int loadedAndReserved() {
        return alive.size() + reserved.size() + inFlight.size();
    }

    /**
     * The garrison birth loop. Runs every 20 ticks on the server owner within a
     * shared 2ms main-thread budget. Scans loaded owned ENEMY_TOWER/FORT spawn
     * slots, requires a non-spectator player within 64 blocks and at least 24
     * blocks away, re-checks {@link #allowed}, and reserves at most two tower
     * slots per pass. Queue rotation is a simple cursor so one loaded site can
     * never starve the rest.
     */
    public void tick() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("tick requires the server owner");
        if (++tickCounter % 20 != 0) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
        var slots = sites.allSlots();
        int examined = 0;
        int reservedThisPass = 0;
        for (int i = 0; i < slots.size() && examined < 8 && reservedThisPass < 2; i++) {
            if (System.nanoTime() > deadline) return;
            int index = (slotCursor + i) % slots.size();
            SpawnSlot slot = slots.get(index);
            if (slot.site().kind() != SiteKind.ENEMY_TOWER && slot.site().kind() != SiteKind.FORT) continue;
            examined++;
            var state = sites.realized(slot.site()).orElse(DecayState.RUIN);
            if (state != DecayState.INTACT && state != DecayState.PARTIAL) continue;
            Point3i feet = slot.feet();
            BlockPos pos = new BlockPos(feet.x(), feet.y(), feet.z());
            if (level.getChunkSource().getChunkNow(feet.x() >> 4, feet.z() >> 4) == null) continue;
            // Nearest non-spectator player must be within 64 and at least 24 blocks away.
            ServerPlayer nearest = null;
            double nearestSq = Double.MAX_VALUE;
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) continue;
                double d = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (d < nearestSq) { nearestSq = d; nearest = player; }
            }
            if (nearest == null) continue;
            // 与 allowed() 共用同一距离带，避免两处字面量再次漂移成矛盾条件。
            if (nearestSq > SPAWN_MAX_DISTANCE_SQUARED || nearestSq < SPAWN_MIN_DISTANCE_SQUARED) continue;
            var policy = new GameplayTypes.PolicyInput(
                    level.getDifficulty() == Difficulty.PEACEFUL,
                    !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING),
                    true, (long) nearestSq, level.getDayTime(), state);
            if (!allowed(policy)) continue;
            int ordinal = slotOrdinal(slot);
            if (ordinal < 0) continue;
            var request = new GameplayTypes.SpawnRequest(slot.site(), slot.slot(), ordinal, feet,
                    slot.entityType(), 1, GameplayTypes.GarrisonState.valueOf(state.name()), 0, session);
            reserve(request);
            reservedThisPass++;
        }
        slotCursor = (slotCursor + examined) % Math.max(1, slots.size());
    }

    private static int slotOrdinal(SpawnSlot slot) {
        // The birth ordinal is the local slot index: entityUuid(site, slot) is the
        // deterministic per-slot entity identity, and SlotKey(site, slot, 0) is the
        // one-shot durable consumption key. Stable across restarts, never re-rolled.
        return slot.slot();
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
                        // Durable consume is done; add the entity on this same owner
                        // step so the birth completes while the slot is still fresh.
                        spawn(permit);
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
        if (!permit.session().equals(session)) return false;
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
        // Finalize on the server owner before adding, matching the vanilla STRUCTURE path.
        BlockPos feet = new BlockPos(permit.feet().x(), permit.feet().y(), permit.feet().z());
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.STRUCTURE, null);
        if (mob instanceof Pillager) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        } else if (mob instanceof Vindicator) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
        if (mob instanceof Raider raider) raider.setCanJoinRaid(false);
        if (mob instanceof PatrollingMonster patroller) patroller.setPatrolLeader(false);
        reserved.remove(permit.entity());
        alive.put(permit.entity(), new GameplayTypes.OwnedMob(permit.site(), permit.ordinal(),
                permit.entity(), permit.sequence()));
        mob.setAttached(TerrainMarkers.OWNED_MOB, new GameplayTypes.OwnedMob(permit.site(),
                permit.ordinal(), permit.entity(), permit.sequence()));
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
