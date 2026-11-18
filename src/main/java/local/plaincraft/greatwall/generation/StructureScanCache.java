package local.plaincraft.greatwall.generation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Durable cache for the planning-time corridor structure scan.
 *
 * <h2>Why</h2>
 * The scan resolves every structure candidate in the corridor before the detour stage can run, and
 * the measured session spent 268,569 ms - 4.5 minutes - on it, every single startup. RoadWeaver has
 * cached its structure starts for the same reason ("exact structure start plan: total=2752
 * cached=596"): a structure start is a pure function of the seed, the structure sets and the chunk,
 * so recomputing it per session is pure waste.
 *
 * <h2>Key</h2>
 * Seed, dimension, corridor bounds and a hash of every registered structure id. A datapack that adds
 * or removes a structure changes the hash and the cache misses, so a stale scan can never be served
 * for a changed world.
 *
 * <h2>Failure</h2>
 * Any IO or format problem reads as "no cache": a corrupt cache must cost a scan, never a session.
 */
final class StructureScanCache {
    /** Bump when the scan's own shape changes, so old entries are ignored rather than mis-read. */
    private static final int FORMAT_VERSION = 1;
    private static final int MAGIC = 0x5347_5331;

    private StructureScanCache() {
    }

    static Optional<List<CorridorStructureScan.OwnedBox>> load(ServerLevel level, String key) {
        Path file = file(level, key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(
                new java.util.zip.GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) return Optional.empty();
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) return Optional.empty();
            List<CorridorStructureScan.OwnedBox> boxes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String structureId = in.readUTF();
                long startChunkKey = in.readLong();
                int minX = in.readInt(), minY = in.readInt(), minZ = in.readInt();
                int maxX = in.readInt(), maxY = in.readInt(), maxZ = in.readInt();
                boxes.add(new CorridorStructureScan.OwnedBox(structureId, startChunkKey,
                        new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)));
            }
            return Optional.of(List.copyOf(boxes));
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    static void store(ServerLevel level, String key, List<CorridorStructureScan.OwnedBox> boxes) {
        if (boxes == null) return;
        Path file = file(level, key);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new java.util.zip.GZIPOutputStream(
                    Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeInt(boxes.size());
                for (var box : boxes) {
                    out.writeUTF(box.structureId());
                    out.writeLong(box.startChunkKey());
                    out.writeInt(box.box().minX());
                    out.writeInt(box.box().minY());
                    out.writeInt(box.box().minZ());
                    out.writeInt(box.box().maxX());
                    out.writeInt(box.box().maxY());
                    out.writeInt(box.box().maxZ());
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // A cache that cannot be written costs a scan next time; it must never fail the startup.
        }
    }

    /** {@return the cache key for this level and corridor} */
    static String key(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            CorridorStructureScan.Corridor corridor, String recipeScope) {
        return key(level.dimension().location().toString(), level.getSeed(), minChunkX, minChunkZ,
                maxChunkX, maxChunkZ, corridor, recipeScope, structureIdsHash(level));
    }

    static String key(String dimension, long seed, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            CorridorStructureScan.Corridor corridor, String recipeScope, String structureIds) {
        // 同外框下的端点和半宽仍决定候选集合，缓存身份必须覆盖整个筛选条件。
        StringBuilder material = new StringBuilder(256)
                .append(dimension).append('|')
                .append(seed).append('|')
                .append(minChunkX).append(',').append(minChunkZ).append(',')
                .append(maxChunkX).append(',').append(maxChunkZ).append('|')
                .append(corridor).append('|').append(recipeScope).append('|').append(structureIds);
        return sha256(material.toString()).substring(0, 32);
    }

    private static String structureIdsHash(ServerLevel level) {
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            List<String> ids = new ArrayList<>();
            registry.keySet().forEach(id -> ids.add(id.toString()));
            ids.sort(String::compareTo);
            return sha256(String.join(",", ids)).substring(0, 16);
        } catch (Exception missing) {
            return "unknown";
        }
    }

    private static Path file(ServerLevel level, String key) {
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("greatwall").resolve("structure-scan").resolve(key + ".bin");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) text.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return text.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
