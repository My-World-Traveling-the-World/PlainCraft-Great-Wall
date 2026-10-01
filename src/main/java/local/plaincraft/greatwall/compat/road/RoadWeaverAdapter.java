package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.persistence.GateLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.RoadNetworkApi;
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
    private static volatile RoadWeaverAdapter instance;

    private final WorldServices services;
    private final GateLedger ledger;
    private final WallArchBackfill backfill;
    private final ServerLevel overworld;
    private final UUID session;
    private final Map<String, WallInteropTypes.Agreement> frozenByGate = new LinkedHashMap<>();

    public RoadWeaverAdapter(WorldServices services, GateLedger ledger, WallArchBackfill backfill,
            ServerLevel overworld) {
        this.services = Objects.requireNonNull(services, "services");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.backfill = Objects.requireNonNull(backfill, "backfill");
        this.overworld = Objects.requireNonNull(overworld, "overworld");
        this.session = UUID.randomUUID();
        PlanningInteropRegistry.registerHeight(session, this);
    }

    /** Called only from a guarded integration entry point. */
    public static void initializeIfPresent() {
        // The caller proves RoadWeaver is present (Fabric Loader check) before
        // this class is touched, so reaching here already means the ABI exists.
        if (instance != null) return;
        instance = null; // constructed by the world-services owner that holds the gate ledger
    }

    /** Registers a gate endpoint once; repeated calls never add duplicate entries. */
    public void registerEndpoint(RoadTypes.GateContract gate) {
        Objects.requireNonNull(gate, "gate");
        ServerLevel level = overworld();
        if (level == null) return;
        String stableId = stableId(gate);
        Point3i center = new Point3i(
                local.plaincraft.greatwall.geometry.FixedMath.floorBlock(gate.frame().center().xQ8()),
                gate.groundY(),
                local.plaincraft.greatwall.geometry.FixedMath.floorBlock(gate.frame().center().zQ8()));
        // RoadWeaver ships remapped Minecraft names, so the server-level calls
        // go through reflection: the interop DTOs are the only compile-time ABI.
        invokeServerLevel("registerStructureEndpointOnce",
                new Class<?>[] {ServerLevel.class, BlockPos.class, String.class},
                level, new BlockPos(center.x(), 0, center.z()), stableId);
    }

    public void ensureConnection(RoadTypes.GateContract from, RoadTypes.GateContract to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        ServerLevel level = overworld();
        if (level == null) return;
        Point3i a = new Point3i(
                local.plaincraft.greatwall.geometry.FixedMath.floorBlock(from.frame().center().xQ8()),
                0,
                local.plaincraft.greatwall.geometry.FixedMath.floorBlock(from.frame().center().zQ8()));
        Point3i b = new Point3i(
                local.plaincraft.greatwall.geometry.FixedMath.floorBlock(to.frame().center().xQ8()),
                0,
                local.plaincraft.greatwall.geometry.FixedMath.floorBlock(to.frame().center().zQ8()));
        invokeServerLevel("ensureConnection",
                new Class<?>[] {ServerLevel.class, BlockPos.class, BlockPos.class, boolean.class},
                level, new BlockPos(a.x(), a.y(), a.z()), new BlockPos(b.x(), b.y(), b.z()), false);
    }

    private static void invokeServerLevel(String method, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> api = Class.forName("net.shiroha233.roadweaver.api.RoadNetworkApi");
            var target = api.getMethod(method, parameterTypes);
            target.invoke(null, arguments);
        } catch (ClassNotFoundException absent) {
            // RoadWeaver is not installed; the wall simply has no road links.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("RoadWeaver interop call failed: " + method, failure);
        }
    }

    @Override public CompletionStage<WallInteropTypes.Agreement> propose(UUID session, String gateId,
            long expectedRevision, List<WallInteropTypes.Approach> approaches,
            List<String> completeBatchRoadIds) {
        Objects.requireNonNull(session, "session");
        if (gateId == null || !gateId.startsWith("plaincraft_great_wall:")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "gate id must be namespaced plaincraft_great_wall:..."));
        }
        List<RoadTypes.Approach> converted = new ArrayList<>(approaches.size());
        for (WallInteropTypes.Approach approach : approaches) {
            List<Point3i> points = new ArrayList<>(approach.stations().size());
            for (WallInteropTypes.Pos pos : approach.stations()) {
                points.add(new Point3i(pos.x(), pos.y(), pos.z()));
            }
            converted.add(new RoadTypes.Approach(approach.roadId(), points));
        }
        // Height agreement is only reachable through an already-frozen gate.
        var existing = frozenByGate.get(gateId);
        if (existing != null) return CompletableFuture.completedFuture(existing);
        return CompletableFuture.completedFuture(new WallInteropTypes.Agreement(gateId,
                expectedRevision, 0, List.of(0), false, false,
                local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                        gateId.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Override public Optional<WallInteropTypes.Agreement> frozen(UUID session, String gateId) {
        if (gateId == null) return Optional.empty();
        return Optional.ofNullable(frozenByGate.get(gateId));
    }

    /** Records the agreement after the ledger freezes the gate. */
    public void adoptFrozen(RoadTypes.GateRecord record, int groundY, List<Integer> sharedProfile) {
        Objects.requireNonNull(record, "record");
        if (record.state() != RoadTypes.GateState.FROZEN
                && record.state() != RoadTypes.GateState.PENDING_CHUNKS
                && record.state() != RoadTypes.GateState.APPLIED) {
            throw new IllegalStateException("only a frozen gate can publish an agreement");
        }
        String gateId = stableId(record.contract());
        boolean ready = record.state() == RoadTypes.GateState.APPLIED;
        frozenByGate.put(gateId, new WallInteropTypes.Agreement(gateId, record.revision(), groundY,
                List.copyOf(sharedProfile), true, ready, record.contract().hash()));
    }

    private ServerLevel overworld() {
        return overworld;
    }

    private static String stableId(RoadTypes.GateContract gate) {
        return "plaincraft_great_wall:" + gate.id().wallId().substring(0, Math.min(16, gate.id().wallId().length()))
                + "/" + gate.id().namespace() + "/" + gate.id().ordinal();
    }

    @Override public void close() {
        PlanningInteropRegistry.closeSession(session);
        frozenByGate.clear();
    }
}
