package local.plaincraft.greatwall.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import local.plaincraft.greatwall.persistence.AtomicFiles;

/** 新展区独立授权：旧展台标记缺失时也不能擅自占用旧档里的土地。 */
record ImportedShowcaseReceipt(long seed, int x, int z, int completed) {
    private static final String FILE = "greatwall/imported-showcase-v2.txt";
    private static final int ALL = 0b11111;

    static Optional<ImportedShowcaseReceipt> open(Path root, boolean newWorld, long seed, int x, int z)
            throws IOException {
        var path = AtomicFiles.resolveUnder(root, FILE);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!newWorld) return Optional.empty();
            var created = new ImportedShowcaseReceipt(seed, x, z, 0);
            // 先落授权再施工，关档后即使出生点改过也只在首次坐标续建。
            AtomicFiles.writeImmutable(root, FILE, created.bytes());
            return Optional.of(created);
        }
        String[] values = new String(AtomicFiles.readChecked(root, FILE, 128, null), StandardCharsets.UTF_8)
                .strip().split(" ", -1);
        try {
            if (values.length != 4 || Long.parseLong(values[0]) != seed) throw new IllegalArgumentException();
            int completed = Integer.parseInt(values[3]);
            if ((completed & ~ALL) != 0) throw new IllegalArgumentException();
            return Optional.of(new ImportedShowcaseReceipt(seed, Integer.parseInt(values[1]),
                    Integer.parseInt(values[2]), completed));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("实物展区凭证无效: " + path, invalid);
        }
    }

    boolean contains(int ordinal) {
        return (completed & (1 << ordinal)) != 0;
    }

    ImportedShowcaseReceipt save(Path root, int completed) throws IOException {
        if (completed == this.completed) return this;
        var updated = new ImportedShowcaseReceipt(seed, x, z, completed);
        AtomicFiles.replacePointer(root, FILE, updated.bytes());
        return updated;
    }

    private byte[] bytes() {
        return (seed + " " + x + " " + z + " " + completed + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
