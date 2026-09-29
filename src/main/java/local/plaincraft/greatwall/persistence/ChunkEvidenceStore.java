package local.plaincraft.greatwall.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Optional;
import java.util.zip.CRC32C;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import local.plaincraft.greatwall.planning.ProbePlanner;
import local.plaincraft.greatwall.terrain.TerrainTile;

/** Authoritative generation evidence, NOT a regenerable station cache. Called on the IO owner. */
public final class ChunkEvidenceStore {
    private static final int MAGIC = 0x50475430;
    private static final int LIMIT = 2 * 1024 * 1024;
    private final Path root;

    public ChunkEvidenceStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path path(TerrainTile.Key key) { return root.resolve(ProbePlanner.hash(key.identity()) + ".t0"); }

    public Optional<TerrainTile> read(TerrainTile.Key key, String recipe) throws IOException {
        Path file = path(key);
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > LIMIT) throw new IOException("T0_CORRUPT: oversized evidence");
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("T0_CORRUPT: header");
            int size = input.readInt();
            long checksum = input.readLong();
            byte[] compressed = input.readNBytes(LIMIT + 1);
            if (size < 0 || size > LIMIT || compressed.length > LIMIT) throw new IOException("T0_CORRUPT: length");
            byte[] raw;
            try (var inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                raw = inflater.readNBytes(LIMIT + 1);
            }
            CRC32C crc = new CRC32C();
            crc.update(raw);
            if (raw.length != size || crc.getValue() != checksum) throw new IOException("T0_CORRUPT: CRC/length");
            try (var payload = new DataInputStream(new ByteArrayInputStream(raw))) {
                String savedRecipe = payload.readUTF();
                var savedKey = new TerrainTile.Key(payload.readUTF(), payload.readInt(), payload.readInt(),
                        payload.readInt(), payload.readInt());
                if (!savedKey.equals(key) || !savedRecipe.equals(recipe)) throw new IOException("T0_RECIPE_MISMATCH");
                String hash = payload.readUTF();
                int count = payload.readInt();
                if (count <= 0 || count > key.volume()) throw new IOException("T0_CORRUPT: palette");
                var palette = new ArrayList<String>(count);
                for (int i = 0; i < count; i++) palette.add(payload.readUTF());
                int[] states = new int[key.volume()];
                for (int i = 0; i < states.length; i++) states[i] = payload.readInt();
                if (payload.read() != -1) throw new IOException("T0_CORRUPT: trailing payload");
                var tile = new TerrainTile(key, recipe, palette, states);
                if (!tile.hash().equals(hash)) throw new IOException("T0_CORRUPT: hash");
                return Optional.of(tile);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("T0_CORRUPT: invalid payload", invalid);
        }
    }

    public TerrainTile persistBeforeApply(TerrainTile tile) throws IOException {
        Optional<TerrainTile> prior = read(tile.key(), tile.recipe());
        if (prior.isPresent()) {
            if (!prior.get().hash().equals(tile.hash())) throw new IOException("T0_CONFLICT: attempted overwrite");
            return prior.get();
        }
        var raw = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(raw)) {
            out.writeUTF(tile.recipe());
            out.writeUTF(tile.key().dimension());
            out.writeInt(tile.key().chunkX()); out.writeInt(tile.key().chunkZ());
            out.writeInt(tile.key().minY()); out.writeInt(tile.key().maxY());
            out.writeUTF(tile.hash());
            out.writeInt(tile.palette().size());
            for (String state : tile.palette()) out.writeUTF(state);
            for (int state : tile.copyStates()) out.writeInt(state);
        }
        byte[] bytes = raw.toByteArray();
        if (bytes.length > LIMIT) throw new IOException("T0 evidence exceeds bounded record size");
        CRC32C crc = new CRC32C(); crc.update(bytes);
        var encoded = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(encoded)) {
            out.writeInt(MAGIC); out.writeInt(1); out.writeInt(bytes.length); out.writeLong(crc.getValue());
            try (var deflater = new DeflaterOutputStream(out)) { deflater.write(bytes); }
        }
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "t0-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(encoded.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: an unsupported filesystem fails before any APPLY.
            Files.move(temporary, path(tile.key()), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return tile;
    }
}
