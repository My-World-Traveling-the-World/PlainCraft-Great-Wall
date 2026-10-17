package local.plaincraft.greatwall.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/**
 * Bounded, path-confined file IO with fsync + atomic rename. Reports what the
 * platform actually guarantees; it never claims cheap-disk power-loss safety.
 */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static Path resolveUnder(Path root, String relative) throws IOException {
        if (root == null) throw new IOException("root is required");
        if (relative == null || relative.isBlank()) throw new IOException("relative path is required");
        Path realRoot = root.toRealPath();
        Path candidate = Path.of(relative);
        if (candidate.isAbsolute()) throw new IOException("absolute paths are rejected: " + relative);
        Path resolved = realRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(realRoot)) {
            throw new IOException("path escapes the confinement root: " + relative);
        }
        // Walk each existing ancestor and reject symlinks that leave the root.
        Path cursor = realRoot;
        for (Path segment : realRoot.relativize(resolved)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)) {
                    Path target = cursor.toRealPath();
                    if (!target.startsWith(realRoot)) {
                        throw new IOException("symlink escapes the confinement root: " + cursor);
                    }
                }
            } else {
                break;
            }
        }
        return resolved;
    }

    public static void writeImmutable(Path root, String relative, byte[] content) throws IOException {
        if (content == null) throw new IOException("content is required");
        Path target = resolveUnder(root, relative);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (MessageDigest.isEqual(existing, content)) return;
            throw new WallFailure(FailureCode.IO_FAILED, "immutable file already exists with different content: " + relative);
        }
        Path temporary = temporarySibling(target);
        try {
            writeAndForce(temporary, content, StandardOpenOption.CREATE_NEW);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new WallFailure(FailureCode.IO_FAILED,
                        "atomic move is unavailable on this volume: " + relative, unsupported);
            } catch (FileAlreadyExistsException raced) {
                byte[] existing = Files.readAllBytes(target);
                if (!MessageDigest.isEqual(existing, content)) {
                    throw new WallFailure(FailureCode.IO_FAILED,
                            "immutable file was created concurrently with different content: " + relative);
                }
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void replacePointer(Path root, String relative, byte[] content) throws IOException {
        if (content == null) throw new IOException("content is required");
        Path target = resolveUnder(root, relative);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = temporarySibling(target);
        try {
            writeAndForce(temporary, content, StandardOpenOption.CREATE_NEW);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new WallFailure(FailureCode.IO_FAILED,
                        "atomic pointer replace is unavailable on this volume: " + relative, unsupported);
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static byte[] readChecked(Path root, String relative, int byteLimit, String expectedSha256)
            throws IOException {
        Path target = resolveUnder(root, relative);
        if (!Files.isRegularFile(target)) {
            throw new WallFailure(FailureCode.T0_MISSING, "missing file: " + relative);
        }
        long size = Files.size(target);
        if (size > byteLimit) {
            throw new WallFailure(FailureCode.CAPACITY, "file exceeds the byte limit: " + relative);
        }
        byte[] bytes = Files.readAllBytes(target);
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256(bytes);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new WallFailure(FailureCode.T0_CORRUPT, "checksum mismatch for " + relative);
            }
        }
        return bytes;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static void writeAndForce(Path file, byte[] content, StandardOpenOption createMode)
            throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, createMode)) {
            var buffer = java.nio.ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /**
     * Flushes the directory entry that publishing a rename created.
     *
     * <p>{@link #writeAndForce} forces the file's own bytes only; the name that makes it reachable lives
     * in the parent directory, so a power loss can leave a caller that already saw success with the
     * previous content - exactly the HEAD pointer and checkpoint state {@code HistoryJournal} exists to
     * keep. Windows cannot open a directory for reading and some file systems refuse the flush; both are
     * reported as a degraded guarantee rather than treated as a failure, which is the contract this class
     * already states for cheap disks.
     */
    private static void forceDirectory(Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException unavailable) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.debug(
                    "[GreatWall:io] directory flush unavailable for {} ({}); the rename is atomic but its "
                            + "directory entry is not forced on this platform",
                    directory, unavailable.toString());
        }
    }

    private static Path temporarySibling(Path target) {
        Path parent = target.getParent();
        String name = "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp";
        return parent == null ? Path.of(name) : parent.resolve(name);
    }
}
