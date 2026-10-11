package local.plaincraft.greatwall.compat.road;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.persistence.GateLedger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.interop.EndpointHeightAgreementApi;
import net.shiroha233.roadweaver.api.interop.PlanningInteropRegistry;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes;

/**
 * Soft-dependency bridge to RoadWeaver. It registers the wall's endpoint
 * height API once, and every side effect is deferred until the overworld
 * readiness promise completes so neither mod depends on the other's load
 * order. When RoadWeaver is absent the class is never loaded.
 */
public final class RoadWeaverAdapter implements EndpointHeightAgreementApi, AutoCloseable {
    private static boolean providerRegistered;
    private static final String ABI_ALLOWLIST = "/plaincraft_great_wall/roadweaver-interop.sha256";

    private final ServerLevel overworld;
    private final UUID session;
    private final Executor owner;
    private final RoadAdapterState state;

    public RoadWeaverAdapter(WorldServices services, GateLedger ledger, WallArchBackfill backfill) {
        this(services, ledger, backfill, Objects.requireNonNull(services).overworld());
    }

    public RoadWeaverAdapter(WorldServices services, GateLedger ledger, WallArchBackfill backfill,
            ServerLevel overworld) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(backfill, "backfill");
        this.overworld = Objects.requireNonNull(overworld, "overworld");
        if (services.overworld() != overworld) throw new IllegalArgumentException("not the service overworld");
        if (!overworld.getServer().isSameThread()) {
            throw new IllegalStateException("RoadWeaver adapter construction requires the server owner");
        }
        services.assertActiveSession();
        this.session = services.runtimeSession();
        this.owner = services.serverOwner();
        if (PlanningInteropRegistry.protocolVersion() != 1) throw new IllegalStateException("RoadWeaver protocol mismatch");
        if (PlanningInteropRegistry.height(session).isPresent()) {
            throw new IllegalStateException("RoadWeaver height API already registered for this runtime");
        }
        this.state = new RoadAdapterState(session, services.recipe().wallId(), ledger, owner,
                new RoadAdapterState.Port() {
                    @Override public void validateSession() {
                        services.assertActiveSession();
                        if (!session.equals(services.runtimeSession())
                                || !boundSession().filter(session::equals).isPresent()) {
                            throw new IllegalStateException("RoadWeaver wall runtime session is not bound to this level");
                        }
                    }
                    @Override public CompletionStage<Void> whenReady() {
                        Object result = invokeRegistryLevel("whenReady", new Class<?>[] {ServerLevel.class}, overworld);
                        if (!(result instanceof CompletionStage<?> ready)) {
                            throw new IllegalStateException("RoadWeaver readiness returned an invalid ABI value");
                        }
                        return ready.thenApply(value -> {
                            if (value != null) throw new IllegalStateException("invalid readiness completion value");
                            return null;
                        });
                    }
                    @Override public void registerEndpoint(RoadTypes.GateContract gate) {
                        assertOwner();
                        invoke("net.shiroha233.roadweaver.api.RoadNetworkApi", "registerStructureEndpointOnce",
                                new Class<?>[] {ServerLevel.class, BlockPos.class, String.class},
                                overworld, roadPosition(gate), stableId(gate.id()));
                    }
                    @Override public void ensureConnection(RoadTypes.GateContract from, RoadTypes.GateContract to) {
                        assertOwner();
                        invoke("net.shiroha233.roadweaver.api.RoadNetworkApi", "ensureConnection",
                                new Class<?>[] {ServerLevel.class, BlockPos.class, BlockPos.class, boolean.class},
                                overworld, roadPosition(from), roadPosition(to), false);
                    }
                    @Override public void failed(Throwable failure) {
                        GreatWallMod.LOGGER.error("RoadWeaver endpoint side effect failed for runtime {}", session, failure);
                    }
                });
        boolean wasBound = boundSession().isPresent();
        invokeRegistryLevel("bindSession", new Class<?>[] {ServerLevel.class, UUID.class},
                overworld, session);
        boolean heightRegistered = false;
        try {
            PlanningInteropRegistry.registerHeight(session, this);
            heightRegistered = true;
            // Wall obstacle provider registration is deliberately disabled. Publishing
            // WallObstacleProvider makes RoadWeaver's hasProviders() true, which flips every
            // road planning/generation entry onto the external-obstacle path (and throws on the
            // synchronous generateRoad() used by initial fine generation). The roads must plan
            // and build exactly as they do without a wall mod - the wall and the roads each
            // generate independently - and only the gate-height agreement (registerHeight above)
            // keeps the wall's gates and the roads' crossings aligned. The wall is never an
            // obstacle to road planning.
        } catch (RuntimeException failure) {
            state.stop();
            if (heightRegistered) {
                WallObstacleProvider.closeSession(session);
                PlanningInteropRegistry.closeSession(session);
            }
            if (!wasBound) invokeRegistryLevel("closeLevel", new Class<?>[] {ServerLevel.class}, overworld);
            throw failure;
        }
    }

    /**
     * Publishes the wall obstacle provider only after this adapter has bound a live
     * wall production session. When wall production is OFF this constructor never
     * runs, so RoadWeaver sees no provider and takes its normal (no-wall) branch
     * instead of failing with "external planning session pending or closed".
     */
    private static synchronized void registerProviderOnce() {
        if (providerRegistered) return;
        PlanningInteropRegistry.register(new WallObstacleProvider());
        providerRegistered = true;
    }

    /** Core must guard class loading with Loader before touching this soft-dependent class. */
    public static synchronized void initializeIfPresent() {
        if (!FabricLoader.getInstance().isModLoaded("roadweaver")) return;
        verifyLockedAbi();
        // Provider registration moved into the adapter constructor (after bindSession).
        // Registering here would publish a provider even when the wall production is OFF,
        // making RoadWeaver take the external-planning branch and then fail with
        // "external planning session pending or closed" (no session was ever bound).
    }

    /** Registers a gate endpoint once; repeated calls never add duplicate entries. */
    public void registerEndpoint(RoadTypes.GateContract gate) {
        state.registerEndpoint(gate);
    }

    public void ensureConnection(RoadTypes.GateContract from, RoadTypes.GateContract to) {
        state.ensureConnection(from, to);
    }

    @Override public CompletionStage<WallInteropTypes.Agreement> propose(UUID session, String gateId,
            long expectedRevision, List<WallInteropTypes.Approach> approaches,
            List<String> completeBatchRoadIds) {
        return state.propose(session, gateId, expectedRevision, approaches, completeBatchRoadIds);
    }

    @Override public Optional<WallInteropTypes.Agreement> frozen(UUID session, String gateId) {
        return state.frozen(session, gateId);
    }

    /** Compatibility entry point; caller-supplied profiles are not durable ledger proof. */
    public void adoptFrozen(RoadTypes.GateRecord record, int groundY, List<Integer> sharedProfile) {
        state.adoptFrozen(record, groundY, sharedProfile);
    }

    public CompletionStage<Void> pendingSideEffects() {
        return state.pendingSideEffects();
    }

    public static String stableId(GateId id) {
        return RoadAdapterState.encode(id);
    }

    private static BlockPos roadPosition(RoadTypes.GateContract gate) {
        return new BlockPos(FixedMath.floorBlock(gate.frame().center().xQ8()), 0,
                FixedMath.floorBlock(gate.frame().center().zQ8()));
    }

    private void assertOwner() {
        if (!overworld.getServer().isSameThread()) throw new IllegalStateException("Road side effect requires server owner");
    }

    @SuppressWarnings("unchecked")
    private Optional<UUID> boundSession() {
        Object result = invokeRegistryLevel("session", new Class<?>[] {ServerLevel.class}, overworld);
        if (!(result instanceof Optional<?> optional)
                || optional.filter(value -> !(value instanceof UUID)).isPresent()) {
            throw new IllegalStateException("RoadWeaver session query returned an invalid ABI value");
        }
        return (Optional<UUID>) result;
    }

    private static Object invokeRegistryLevel(String method, Class<?>[] types, Object... args) {
        return invoke("net.shiroha233.roadweaver.api.interop.PlanningInteropRegistry", method, types, args);
    }

    private static Object invoke(String className, String method, Class<?>[] types, Object... args) {
        try {
            return Class.forName(className).getMethod(method, types).invoke(null, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("RoadWeaver call failed: " + method, failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("RoadWeaver level interop failed: " + method, failure);
        }
    }

    private static void verifyLockedAbi() {
        if (PlanningInteropRegistry.protocolVersion() != 1) throw new IllegalStateException("RoadWeaver protocol mismatch");
        try (var locked = RoadWeaverAdapter.class.getResourceAsStream(ABI_ALLOWLIST)) {
            if (locked == null) {
                throw new IllegalStateException("S2 integration blocked: reviewed ABI SHA allowlist absent: " + ABI_ALLOWLIST);
            }
            var allowed = new BufferedReader(new InputStreamReader(locked, StandardCharsets.UTF_8))
                    .lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
            if (allowed.isEmpty() || allowed.stream().anyMatch(line -> !line.matches("[0-9a-f]{64}"))) {
                throw new IllegalStateException("invalid reviewed RoadWeaver artifact SHA allowlist");
            }
            var source = PlanningInteropRegistry.class.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) {
                throw new IllegalStateException("RoadWeaver artifact origin cannot be verified");
            }
            Path artifact = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(artifact)) throw new IllegalStateException("RoadWeaver origin is not a locked jar");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(artifact), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (!allowed.contains(HexFormat.of().formatHex(digest.digest()))) {
                throw new IllegalStateException("RoadWeaver loaded jar SHA is not approved");
            }
        } catch (java.io.IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("RoadWeaver ABI verification failed", failure);
        }
    }

    @Override public void close() {
        if (!state.stop()) return;
        owner.execute(() -> {
            assertOwner();
            try {
                if (boundSession().filter(session::equals).isPresent()) {
                    invokeRegistryLevel("closeLevel", new Class<?>[] {ServerLevel.class}, overworld);
                }
            } finally {
                WallObstacleProvider.closeSession(session);
                PlanningInteropRegistry.closeSession(session);
            }
        });
    }
}
