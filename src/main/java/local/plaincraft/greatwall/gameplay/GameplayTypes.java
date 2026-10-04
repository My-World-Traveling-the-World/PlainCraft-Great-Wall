package local.plaincraft.greatwall.gameplay;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;

/** Pure model for S3 gameplay. No world handles, no entity references. */
public final class GameplayTypes {
    private GameplayTypes() {}

    public enum CreditStatus { COMMITTED, ALREADY_COMMITTED, ALREADY_PENDING, REFUSED_NOT_LIT,
        REFUSED_PROTECTED, REFUSED_SESSION, DEFERRED_IO }

    public enum GarrisonState { INTACT, PARTIAL, RUIN, BURIED }

    public record VerifiedIgnition(BeaconId id, UUID player, long gameTime, Point3i pitFeet,
            boolean lit, boolean waterlogged, boolean accepted, String tool) {
        public VerifiedIgnition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(pitFeet, "pitFeet");
            Objects.requireNonNull(tool, "tool");
            if (gameTime < 0) throw new IllegalArgumentException("gameTime >= 0 required");
        }
    }

    public record CreditResult(CreditStatus status, long committedCount, UUID player,
            long sequence, boolean durable, String detail) {
        public CreditResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(detail, "detail");
            if (committedCount < 0 || sequence < 0) {
                throw new IllegalArgumentException("count/sequence >= 0 required");
            }
        }
    }

    public record BeaconProgress(long credited, long pendingUnknown, long targetCount,
            boolean degraded, boolean ready) {
        public BeaconProgress {
            if (credited < 0 || pendingUnknown < 0 || targetCount < 0) {
                throw new IllegalArgumentException("counts >= 0 required");
            }
        }
        public boolean progressComplete() { return targetCount > 0 && credited >= targetCount; }
    }

    public record BeforeUse(BeaconId beacon, UUID player, long gameTime, Point3i pitFeet,
            String tool, int tickBudget) {
        public BeforeUse {
            Objects.requireNonNull(beacon, "beacon");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(pitFeet, "pitFeet");
            Objects.requireNonNull(tool, "tool");
            if (gameTime < 0 || tickBudget < 0) throw new IllegalArgumentException("time/budget >= 0 required");
        }
    }

    public record BeaconSite(BeaconId id, SiteId owner, Point3i center, List<Point3i> pits,
            int walkY, String dimension) {
        public BeaconSite {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(pits, "pits");
            Objects.requireNonNull(dimension, "dimension");
            if (pits.size() != 4) throw new IllegalArgumentException("a beacon owns exactly four fire pits");
            pits = List.copyOf(pits);
        }
    }

    public record GarrisonSlot(SiteId site, int localSlot, int ordinal, Point3i feet,
            String entityType, int quota, boolean naturalSpawn) {
        public GarrisonSlot {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(feet, "feet");
            Objects.requireNonNull(entityType, "entityType");
            if (localSlot < 0 || ordinal < 0 || quota < 0) {
                throw new IllegalArgumentException("slot/ordinal/quota >= 0 required");
            }
        }
    }

    public record GarrisonInventory(SiteId site, GarrisonState state, List<GarrisonSlot> slots,
            Map<Integer, Integer> consumed, int quotaTotal, int liveTotal) {
        public GarrisonInventory {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(slots, "slots");
            Objects.requireNonNull(consumed, "consumed");
            if (quotaTotal < 0 || liveTotal < 0) throw new IllegalArgumentException("totals >= 0 required");
            slots = List.copyOf(slots);
            consumed = Map.copyOf(consumed);
        }
    }

    public record SpawnRequest(SiteId site, int localSlot, int ordinal, Point3i feet,
            String entityType, int quota, GarrisonState state, int liveInRadius, UUID session) {
        public SpawnRequest {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(feet, "feet");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(session, "session");
            if (localSlot < 0 || ordinal < 0 || quota < 0 || liveInRadius < 0) {
                throw new IllegalArgumentException("slot/ordinal/quota/live >= 0 required");
            }
        }
    }

    public record SpawnDecision(boolean allowed, String reason, int remainingQuota, long sequence) {
        public SpawnDecision {
            Objects.requireNonNull(reason, "reason");
            if (remainingQuota < 0 || sequence < 0) throw new IllegalArgumentException("quota/sequence >= 0 required");
        }
    }

    public record Firepit(BeaconId beacon, SiteId owner, int ordinal, Point3i feet, boolean lit,
            boolean waterlogged, boolean credited) {
        public Firepit {
            Objects.requireNonNull(beacon, "beacon");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(feet, "feet");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal >= 0 required");
        }
    }

    /** Frozen inputs for the pure garrison spawn policy. */
    public record PolicyInput(boolean peaceful, boolean mobSpawning, boolean playerNear,
            long nearestPlayerDistanceSquared, long dayTime, local.plaincraft.greatwall.model.WallTypes.DecayState decay) {
        public PolicyInput {
            Objects.requireNonNull(decay, "decay");
            if (nearestPlayerDistanceSquared < 0) {
                throw new IllegalArgumentException("distanceSquared >= 0 required");
            }
        }
    }

    public record OwnedMob(SiteId site, int ordinal, UUID entity, long sequence) {
        public OwnedMob {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(entity, "entity");
            if (ordinal < 0 || sequence < 0) throw new IllegalArgumentException("ordinal/sequence >= 0 required");
        }
    }

    public record SpawnPermit(SiteId site, int localSlot, int ordinal, Point3i feet, String entityType,
            UUID entity, long sequence, UUID session) {
        public SpawnPermit {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(feet, "feet");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(session, "session");
            if (localSlot < 0 || ordinal < 0 || sequence < 0) {
                throw new IllegalArgumentException("slot/ordinal/sequence >= 0 required");
            }
        }
    }

    public record Approval(boolean allowed, String reason, Optional<UUID> entity) {
        public Approval {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(entity, "entity");
        }
        public static Approval deny(String reason) { return new Approval(false, reason, Optional.empty()); }
        public static Approval allow(UUID entity) { return new Approval(true, "ok", Optional.of(entity)); }
    }

    /** Frozen loot-table choice for a single container; the seed is derived, never a random UUID. */
    public record LootAssignment(SiteId site, int container, Point3i pos, String table, long seed, String inputHash) {
        public LootAssignment {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(inputHash, "inputHash");
            if (container < 0) throw new IllegalArgumentException("container >= 0 required");
            local.plaincraft.greatwall.model.WallTypes.requireResourceKey(table);
            local.plaincraft.greatwall.model.WallTypes.requireHash(inputHash);
        }
    }
}
