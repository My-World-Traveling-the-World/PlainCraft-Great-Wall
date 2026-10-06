package local.plaincraft.greatwall.compat.biome;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Owns the Great Wall biome identity for the current production run.
 *
 * <h2>Design boundaries</h2>
 * <ul>
 *   <li><b>No registry mutation.</b> The identity is registered by the bundled Lithostitched
 *       datapack; this class only records whether that happened and hands out the resolved holder.
 *       It never enumerates, extends or modifies the biome source's identity set — doing so is a
 *       frozen red line for this project.</li>
 *   <li><b>No sampler identity table.</b> The reference sanctuary keys its state by
 *       {@code Climate.Sampler} instance, which is fragile across dimension lifecycle. Here the
 *       key is an explicit, immutable {@link RouteBiomeMask.MaskKey} carrying session, dimension,
 *       recipe hash, registry epoch and owner chunk.</li>
 *   <li><b>Overworld only.</b> {@link #publish} rejects every other dimension outright, so the
 *       nether and end can never observe a mask or a holder.</li>
 *   <li><b>Fail closed.</b> A worldgen thread asking for an owner that was never published gets a
 *       {@link WallFailure}, never an empty mask that would silently look like "no wall here".</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Masks are published through a {@link ConcurrentHashMap} as deeply immutable values. The mutable
 * compiler lives on the caller's owner thread and is never shared. Closing stops new admissions,
 * fails pending work, and drops the published table only after the owner confirms it is done.
 */
public final class GreatWallBiomeRuntime {
    /** The biome resource this runtime paints; also the injector's target. */
    public static final ResourceLocation BIOME_ID =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "great_wall");
    /** Our own density function used purely to make the injector's match condition unsatisfiable. */
    public static final ResourceLocation REGISTRATION_ZERO_ID =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "registration_zero");
    /** The bundled data pack that carries the identity and the injector. */
    public static final ResourceLocation PACK_ID =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "great_wall_biome");

    private final UUID session;
    private final String dimension;
    private final String recipeHash;
    private final long registryEpoch;
    private final Executor owner;
    private final ConcurrentHashMap<Long, RouteBiomeMask> masks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    private volatile Holder<Biome> holder;

    public GreatWallBiomeRuntime(UUID session, String dimension, String recipeHash, long registryEpoch,
                                 Executor owner) {
        this.session = Objects.requireNonNull(session, "session");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.recipeHash = Objects.requireNonNull(recipeHash, "recipeHash");
        this.registryEpoch = registryEpoch;
        this.owner = Objects.requireNonNull(owner, "owner");
        if (!Level.OVERWORLD.location().toString().equals(dimension)) {
            throw new IllegalArgumentException("Great Wall biome identity is overworld-only: " + dimension);
        }
    }

    public UUID session() { return session; }
    public String dimension() { return dimension; }
    public String recipeHash() { return recipeHash; }
    public long registryEpoch() { return registryEpoch; }
    public Map<String, Object> diagnostics() { return Map.copyOf(state); }

    /** True once the resolved biome holder has been bound for this run. */
    public boolean identityBound() { return holder != null; }

    /** Records that the bundled pack was actually enabled; the caller owns that check. */
    public void markPackEnabled(boolean enabled) {
        state.put(enabled ? "pack-enabled" : "pack-disabled", true);
    }

    /** Binds the resolved biome holder. The holder must belong to the current registry epoch. */
    public void bindIdentity(Holder<Biome> resolved) {
        Objects.requireNonNull(resolved, "resolved");
        if (state.containsKey("closed")) throw new WallFailure(FailureCode.CLOSED, "biome runtime closed");
        var previous = holder;
        if (previous != null && previous != resolved) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "biome identity rebound with a different holder");
        }
        holder = resolved;
        state.put("identity-bound", true);
    }

    public Holder<Biome> requireIdentity() {
        var current = holder;
        if (current == null) {
            throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER,
                    "Great Wall biome identity is not bound; the biome data pack is missing or disabled");
        }
        return current;
    }

    /**
     * Compiles and publishes the mask for one FEATURES owner. Must be called on the owner thread.
     * Re-publishing the same owner is a protocol error, matching the existing guard's contract.
     */
    public RouteBiomeMask publish(ChunkKey owner, PlanWindow plans, int writeRadius) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plans, "plans");
        if (state.containsKey("closed")) throw new WallFailure(FailureCode.CLOSED, "biome runtime closed");
        if (!owner.dimension().equals(dimension)) {
            throw new IllegalArgumentException("foreign dimension mask publish: " + owner.dimension());
        }
        if (!plans.plans().isEmpty() && !plans.plans().get(0).geometry().key().dimension().equals(dimension)) {
            throw new IllegalArgumentException("foreign dimension plan window");
        }
        var mask = RouteBiomeMask.compile(key(owner), plans, writeRadius,
                owner.x() * 16, owner.z() * 16);
        if (masks.putIfAbsent(ownerKey(owner), mask) != null) {
            throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN,
                    "FEATURES owner already holds a biome mask " + owner);
        }
        state.merge("masks-published", 1L, (previous, one) -> ((Long) previous) + (Long) one);
        return mask;
    }

    private RouteBiomeMask.MaskKey key(ChunkKey owner) {
        return new RouteBiomeMask.MaskKey(session, dimension, recipeHash, registryEpoch, owner.x(), owner.z());
    }

    /** The mask for an owner, or empty when this owner was legitimately skipped (pre-recipe pass). */
    public Optional<RouteBiomeMask> find(ChunkKey owner) {
        if (!owner.dimension().equals(dimension)) return Optional.empty();
        return Optional.ofNullable(masks.get(ownerKey(owner)));
    }

    /** Fail-closed lookup used by the writer once the run is authorised. */
    public RouteBiomeMask require(ChunkKey owner) {
        return find(owner).orElseThrow(() -> new WallFailure(FailureCode.MISSING_FEATURES_PLAN,
                "missing biome mask for FEATURES owner " + owner));
    }

    public void release(ChunkKey owner) { masks.remove(ownerKey(owner)); }
    public int leases() { return masks.size(); }
    public boolean closed() { return state.containsKey("closed"); }

    /** Stops admission, fails pending work, then drops published masks. Idempotent. */
    public CompletableFuture<Void> closeAsync() {
        if (state.putIfAbsent("closed", true) == null) {
            state.put("closed-at", System.nanoTime());
        }
        var done = new CompletableFuture<Void>();
        owner.execute(() -> {
            masks.clear();
            holder = null;
            state.put("drained", true);
            done.complete(null);
        });
        return done;
    }

    private static long ownerKey(ChunkKey owner) {
        return ((long) owner.x() << 32) | (owner.z() & 0xFFFFFFFFL);
    }

    /** Diagnostics only: never used to make a decision. */
    public List<String> describe() {
        return List.of("session=" + session, "dimension=" + dimension, "epoch=" + registryEpoch,
                "masks=" + masks.size(), "identityBound=" + identityBound());
    }

    /** The registry key of the biome this runtime paints. */
    public static ResourceKey<Biome> biomeKey() {
        return ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, BIOME_ID);
    }
}
