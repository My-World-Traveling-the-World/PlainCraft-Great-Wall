package local.plaincraft.greatwall.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.Axis;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/**
 * Remembers where the root search landed, so a world that has already been searched
 * does not pay for it again.
 *
 * <h2>Why this exists</h2>
 * A measured session spent roughly 13 of its 22 prewarm minutes in the root search
 * before a single segment was planned: 16 oracle lanes initialised serially for
 * about 232 seconds, and the search then walked candidate radii, angles and axes.
 * The result is a handful of integers, but it was recomputed from scratch on every
 * launch of the same world — and a session that failed later, in construction, paid
 * the whole cost again on the next attempt.
 *
 * <h2>What is cached, and what is deliberately not</h2>
 * Only the chosen {@link Axis} plus the selection's identity fields. Terrain is not
 * cached here: the segment cache under {@code cache/} already stores geometry and
 * plans keyed by recipe hash, and those are far larger. Duplicating them would add a
 * second thing to invalidate.
 *
 * <p>The file is stored per save, under {@code <world>/greatwall/root-search.json},
 * so it travels with the world it describes and is deleted with it.
 *
 * <h2>When it is discarded</h2>
 * Every value carries the identity of the world state that produced it: the seed,
 * the dimension, the spawn, the four input hashes, and the axis origin. A mismatch
 * on any of them means the terrain the axis was chosen against has changed, so the
 * entry is ignored and the search runs again. A cache that silently returned a stale
 * axis would place the wall against terrain that no longer exists, which is worse
 * than paying the search cost.
 */
public final class RootSearchCache {
    private static final String FILE_NAME = "root-search.json";
    private static final GsonHolder GSON = new GsonHolder();

    private RootSearchCache() {
    }

    /** Everything a cached axis must still match for it to be usable. */
    public record Identity(long seed, String dimension, int spawnX, int spawnY, int spawnZ,
                           String terrainHash, String paletteHash, String templatesHash) {
        public Identity {
            java.util.Objects.requireNonNull(dimension, "dimension");
        }
    }

    /** A cached selection: the axis plus the numbers that produced it. */
    public record Entry(Identity identity, int axisOriginX, int axisOriginY, int axisOriginZ,
                        int exQ30, int ezQ30, int radiusIndex, int angleIndex, int axisIndex) {
        public Entry {
            java.util.Objects.requireNonNull(identity, "identity");
        }
    }

    /**
     * Where the cache for a save lives.
     *
     * <p>Takes the world directory rather than looking one up, because the caller
     * already holds it and because this must stay usable from tests with no loader.
     */
    public static Path file(Path worldDirectory) {
        return worldDirectory.resolve("greatwall").resolve(FILE_NAME);
    }

    /**
     * Reads a cached entry when it still matches {@code expected}.
     *
     * <p>Every failure mode — missing file, unreadable file, malformed JSON, identity
     * mismatch — returns empty and lets the caller search again. None of them throw:
     * a cache is an optimisation and must never be able to stop a world from loading.
     */
    public static Optional<Entry> read(Path worldDirectory, Identity expected) {
        java.util.Objects.requireNonNull(worldDirectory, "worldDirectory");
        java.util.Objects.requireNonNull(expected, "expected");
        Path path = file(worldDirectory);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            var json = GSON.parse(text);
            if (json == null) return Optional.empty();
            var entry = fromJson(json);
            if (entry == null || !entry.identity().equals(expected)) return Optional.empty();
            return Optional.of(entry);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Writes the chosen axis for the next launch. Never throws: a cache that cannot be
     * written costs time, not correctness, and must not fail the session that owns it.
     */
    public static void write(Path worldDirectory, Entry entry) {
        java.util.Objects.requireNonNull(worldDirectory, "worldDirectory");
        java.util.Objects.requireNonNull(entry, "entry");
        Path path = file(worldDirectory);
        try {
            Files.createDirectories(path.getParent());
            // Write-then-move, so a crash mid-write cannot leave a half file that the
            // next launch would have to reject and re-derive.
            Path temporary = Files.createTempFile(path.getParent(), FILE_NAME, ".tmp");
            Files.writeString(temporary, toJson(entry), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException unwritable) {
            // Deliberately swallowed; see the method contract above.
        }
    }

    /** A human-readable view of what is cached, for diagnostics. */
    public static List<String> describe(Path worldDirectory) {
        Path path = file(worldDirectory);
        if (!Files.isRegularFile(path)) return List.of("(no cached root search)");
        try {
            var entry = fromJson(GSON.parse(Files.readString(path, StandardCharsets.UTF_8)));
            if (entry == null) return List.of("(cached root search is unreadable)");
            return List.of("origin=(" + entry.axisOriginX() + "," + entry.axisOriginY() + "," + entry.axisOriginZ()
                    + ") radiusIndex=" + entry.radiusIndex() + " angleIndex=" + entry.angleIndex()
                    + " axisIndex=" + entry.axisIndex());
        } catch (IOException | RuntimeException unreadable) {
            return List.of("(cached root search is unreadable)");
        }
    }

    /** Identity of the state a selection was made against. */
    public static Identity identityOf(long seed, String dimension, Point3i spawn,
                                      String terrainHash, String paletteHash, String templatesHash) {
        java.util.Objects.requireNonNull(spawn, "spawn");
        return new Identity(seed, dimension, spawn.x(), spawn.y(), spawn.z(),
                terrainHash, paletteHash, templatesHash);
    }

    /** Builds an entry from a completed search. */
    public static Entry of(Identity identity, Axis axis, int radiusIndex, int angleIndex, int axisIndex) {
        java.util.Objects.requireNonNull(axis, "axis");
        return new Entry(identity, axis.origin().x(), axis.origin().y(), axis.origin().z(),
                axis.exQ30(), axis.ezQ30(), radiusIndex, angleIndex, axisIndex);
    }

    /** Rebuilds the axis a cached entry describes. */
    public static Axis axisOf(Entry entry) {
        return new Axis(new Point3i(entry.axisOriginX(), entry.axisOriginY(), entry.axisOriginZ()),
                entry.exQ30(), entry.ezQ30());
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────
    // Written by hand rather than through a reflective binder: the field set is small,
    // every field is required, and an explicit mapping cannot silently start reading a
    // renamed or retyped property as its default.

    private static String toJson(Entry entry) {
        var identity = entry.identity();
        var json = new com.google.gson.JsonObject();
        json.addProperty("version", 1);
        var id = new com.google.gson.JsonObject();
        id.addProperty("seed", identity.seed());
        id.addProperty("dimension", identity.dimension());
        id.addProperty("spawnX", identity.spawnX());
        id.addProperty("spawnY", identity.spawnY());
        id.addProperty("spawnZ", identity.spawnZ());
        id.addProperty("terrainHash", identity.terrainHash());
        id.addProperty("paletteHash", identity.paletteHash());
        id.addProperty("templatesHash", identity.templatesHash());
        json.add("identity", id);
        json.addProperty("axisOriginX", entry.axisOriginX());
        json.addProperty("axisOriginY", entry.axisOriginY());
        json.addProperty("axisOriginZ", entry.axisOriginZ());
        json.addProperty("exQ30", entry.exQ30());
        json.addProperty("ezQ30", entry.ezQ30());
        json.addProperty("radiusIndex", entry.radiusIndex());
        json.addProperty("angleIndex", entry.angleIndex());
        json.addProperty("axisIndex", entry.axisIndex());
        // A checksum over the payload makes a truncated or hand-edited file detectable
        // as malformed rather than merely different.
        json.addProperty("checksum", DeterministicHash.sha256(
                checksumSource(entry).getBytes(StandardCharsets.UTF_8)));
        return GSON.write(json);
    }

    private static Entry fromJson(com.google.gson.JsonObject json) {
        if (json == null || !json.has("version") || json.get("version").getAsInt() != 1) return null;
        if (!json.has("identity") || !json.get("identity").isJsonObject()) return null;
        var id = json.getAsJsonObject("identity");
        var identity = new Identity(
                id.get("seed").getAsLong(),
                id.get("dimension").getAsString(),
                id.get("spawnX").getAsInt(), id.get("spawnY").getAsInt(), id.get("spawnZ").getAsInt(),
                id.get("terrainHash").getAsString(), id.get("paletteHash").getAsString(),
                id.get("templatesHash").getAsString());
        var entry = new Entry(identity,
                json.get("axisOriginX").getAsInt(), json.get("axisOriginY").getAsInt(),
                json.get("axisOriginZ").getAsInt(), json.get("exQ30").getAsInt(), json.get("ezQ30").getAsInt(),
                json.get("radiusIndex").getAsInt(), json.get("angleIndex").getAsInt(),
                json.get("axisIndex").getAsInt());
        if (!json.has("checksum")
                || !json.get("checksum").getAsString().equals(DeterministicHash.sha256(
                        checksumSource(entry).getBytes(StandardCharsets.UTF_8)))) {
            return null;
        }
        return entry;
    }

    private static String checksumSource(Entry entry) {
        var identity = entry.identity();
        return "root-search-v1\n" + identity.seed() + "\n" + identity.dimension() + "\n"
                + identity.spawnX() + "," + identity.spawnY() + "," + identity.spawnZ() + "\n"
                + identity.terrainHash() + "\n" + identity.paletteHash() + "\n" + identity.templatesHash() + "\n"
                + entry.axisOriginX() + "," + entry.axisOriginY() + "," + entry.axisOriginZ() + "\n"
                + entry.exQ30() + "," + entry.ezQ30() + "\n"
                + entry.radiusIndex() + "," + entry.angleIndex() + "," + entry.axisIndex();
    }

    /**
     * Keeps the Gson dependency in one place and documents why it is used directly.
     *
     * <p>The rest of the project already depends on Gson for the world manifest and the
     * player config, so this adds no new dependency.
     */
    private static final class GsonHolder {
        private final com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

        com.google.gson.JsonObject parse(String text) {
            return gson.fromJson(text, com.google.gson.JsonObject.class);
        }

        String write(com.google.gson.JsonObject json) {
            return gson.toJson(json);
        }
    }
}
