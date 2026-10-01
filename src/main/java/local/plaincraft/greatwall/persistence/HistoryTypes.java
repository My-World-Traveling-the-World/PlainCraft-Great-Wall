package local.plaincraft.greatwall.persistence;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import local.plaincraft.greatwall.model.WallTypes.SiteId;

/** Pure model for permanent wall history. No Minecraft types, no mutable cursors. */
public final class HistoryTypes {
    private HistoryTypes() {}

    public enum Health { READY, PENDING_IO, DEGRADED, CORRUPT, CLOSED }

    public enum Kind { GATE_PROPOSED, GATE_FROZEN, GATE_BEGIN, GATE_ACK, GATE_CONFLICT,
        BEACON_CREDIT, SLOT_CONSUMED, MOB_SPAWN_RESULT, MOB_RETIRED, LOOT_ASSIGNED }

    public enum Tool { FLINT_AND_STEEL, FIRE_CHARGE, TORCH }

    public enum SpawnResult { ADDED, REFUSED, LOST_BEFORE_ADD, REMOVED_BY_CAP }

    public record ShardKey(String kind, long bucket, int page) {
        public ShardKey {
            Objects.requireNonNull(kind, "kind");
            if (bucket < 0 || page < 0) throw new IllegalArgumentException("bucket/page >= 0 required");
        }
    }

    public record BlobRef(String relativePath, long length, String sha256) {
        public BlobRef {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(sha256, "sha256");
            if (length < 0) throw new IllegalArgumentException("length >= 0 required");
            if (relativePath.contains("..") || relativePath.contains(":")
                    || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
                throw new IllegalArgumentException("blob path must be a confined relative path: " + relativePath);
            }
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 lowercase hex required");
        }
    }

    public record ShardRef(ShardKey key, long throughSequence, long count, BlobRef blob) {
        public ShardRef {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(blob, "blob");
            if (throughSequence < 0 || count < 0) {
                throw new IllegalArgumentException("throughSequence/count >= 0 required");
            }
        }
    }

    public record IndexBranch(String firstKey, String lastKey, BlobRef child) {
        public IndexBranch {
            Objects.requireNonNull(firstKey, "firstKey");
            Objects.requireNonNull(lastKey, "lastKey");
            Objects.requireNonNull(child, "child");
        }
    }

    public record IndexPage(int level, List<IndexBranch> branches,
            List<DurableLog.Record> records, String hash) {
        public IndexPage {
            Objects.requireNonNull(branches, "branches");
            Objects.requireNonNull(records, "records");
            Objects.requireNonNull(hash, "hash");
            if (level < 0) throw new IllegalArgumentException("level >= 0 required");
            if (level == 0 && !branches.isEmpty()) {
                throw new IllegalArgumentException("level 0 index page may only hold records");
            }
            if (level > 0 && !records.isEmpty()) {
                throw new IllegalArgumentException("level > 0 index page may only hold branches");
            }
            branches = List.copyOf(branches);
            records = List.copyOf(records);
        }
    }

    public record Head(UUID world, long generation, long sequence, long beaconCount,
            Map<String, BlobRef> roots, String hash) {
        public Head {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(roots, "roots");
            Objects.requireNonNull(hash, "hash");
            if (generation < 0 || sequence < 0 || beaconCount < 0) {
                throw new IllegalArgumentException("generation/sequence/count >= 0 required");
            }
            roots = Map.copyOf(roots);
            if (sequence == 0) {
                if (!roots.isEmpty()) throw new IllegalArgumentException("empty history must have no roots");
            } else if (!roots.keySet().equals(java.util.Set.of("by_key", "by_sequence"))) {
                throw new IllegalArgumentException("non-empty history must carry exactly by_key/by_sequence roots");
            }
        }
    }

    public record Credit(BeaconId id, UUID player, long gameTime, Tool tool, long sequence) {
        public Credit {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(tool, "tool");
            if (gameTime < 0 || sequence < 0) throw new IllegalArgumentException("gameTime/sequence >= 0 required");
        }
    }

    public record SlotKey(SiteId site, int localSlot, long attempt) {
        public SlotKey {
            Objects.requireNonNull(site, "site");
            if (localSlot < 0 || attempt < 0) throw new IllegalArgumentException("slot/attempt >= 0 required");
            switch (site.kind()) {
                case ENEMY_TOWER -> {
                    if (localSlot > 2) throw new IllegalArgumentException("enemy tower slots are 0..2");
                    if (attempt != 0) throw new IllegalArgumentException("enemy tower attempt is fixed at 0");
                }
                case FORT -> {
                    if (localSlot > 1) throw new IllegalArgumentException("fort slots are 0..1");
                }
                default -> throw new IllegalArgumentException("slot owners are tower/fort only: " + site.kind());
            }
        }
    }

    public record Consumption(SlotKey key, UUID entity, long sequence, Optional<SpawnResult> result) {
        public Consumption {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(result, "result");
            if (sequence < 0) throw new IllegalArgumentException("sequence >= 0 required");
        }
    }

    public record State(UUID world, String manifestHash, long sequence, long beaconCount,
            long revision, Health health, String checkpointHash) {
        public State {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(manifestHash, "manifestHash");
            Objects.requireNonNull(health, "health");
            Objects.requireNonNull(checkpointHash, "checkpointHash");
            if (sequence < 0 || beaconCount < 0 || revision < 0) {
                throw new IllegalArgumentException("sequence/count/revision >= 0 required");
            }
        }
    }

    public record Recovery(Head head, List<DurableLog.Record> tail, boolean archivedPartialTail) {
        public Recovery {
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(tail, "tail");
            tail = List.copyOf(tail);
        }
    }
}
