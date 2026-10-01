package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** Pure DTO layer for the S2 road-gate negotiation. No RoadWeaver types cross this boundary. */
public final class RoadTypes {
    private RoadTypes() {}

    public enum GateState { PROPOSED, FROZEN, PENDING_CHUNKS, APPLIED, CONFLICT, RETIRED }

    public enum ChunkGateState { PENDING, APPLYING, ACKED, CONFLICT }

    public record Approach(String roadId, List<Point3i> points) {
        public Approach {
            Objects.requireNonNull(roadId, "roadId");
            Objects.requireNonNull(points, "points");
            points = List.copyOf(points);
            if (points.size() > 16) throw new IllegalArgumentException("approach points <= 16 required");
        }
    }

    public record ChunkProgress(ChunkKey owner, int cursor, long revision, String inputHash,
            ChunkGateState state, List<Point3i> conflicts) {
        public ChunkProgress {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(inputHash, "inputHash");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(conflicts, "conflicts");
            if (cursor < 0 || revision < 0) throw new IllegalArgumentException("cursor/revision >= 0 required");
            conflicts = List.copyOf(conflicts);
        }
    }

    public record GateContract(GateId id, long globalSQ8, Frame frame, int groundY,
            int apertureWidth, int apertureHeight, List<ChunkKey> affected, String hash) {
        public GateContract {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(affected, "affected");
            Objects.requireNonNull(hash, "hash");
            if (globalSQ8 < 0) throw new IllegalArgumentException("globalSQ8 >= 0 required");
            if (apertureWidth != 4 || apertureHeight != 5) {
                throw new IllegalArgumentException("gate aperture is frozen at 4 wide and 5 high");
            }
            affected = List.copyOf(affected);
        }
    }

    public record GateRecord(GateContract contract, long revision, GateState state,
            List<Approach> proposals, List<String> batchRoadIds,
            Map<ChunkKey, ChunkProgress> progress, long journalSeq) {
        public GateRecord {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(proposals, "proposals");
            Objects.requireNonNull(batchRoadIds, "batchRoadIds");
            Objects.requireNonNull(progress, "progress");
            if (revision < 0 || journalSeq < 0) throw new IllegalArgumentException("revision/seq >= 0 required");
            proposals = List.copyOf(proposals);
            batchRoadIds = List.copyOf(batchRoadIds);
            progress = Map.copyOf(progress);
        }

        /** Deep-copied defensive snapshot for command/diagnostic consumers. */
        public GateRecord snapshot() {
            List<Approach> copies = new ArrayList<>(proposals.size());
            for (Approach approach : proposals) copies.add(new Approach(approach.roadId(), approach.points()));
            Map<ChunkKey, ChunkProgress> progressCopies = new LinkedHashMap<>();
            for (Map.Entry<ChunkKey, ChunkProgress> entry : progress.entrySet()) {
                ChunkProgress value = entry.getValue();
                progressCopies.put(entry.getKey(), new ChunkProgress(value.owner(), value.cursor(),
                        value.revision(), value.inputHash(), value.state(), value.conflicts()));
            }
            return new GateRecord(contract, revision, state, copies, new ArrayList<>(batchRoadIds),
                    progressCopies, journalSeq);
        }
    }

    public record BackfillResult(GateId gate, int written, int pendingChunks,
            List<Point3i> conflicts, boolean complete) {
        public BackfillResult {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(conflicts, "conflicts");
            if (written < 0 || pendingChunks < 0) throw new IllegalArgumentException("counts >= 0 required");
            conflicts = List.copyOf(conflicts);
        }
    }
}
