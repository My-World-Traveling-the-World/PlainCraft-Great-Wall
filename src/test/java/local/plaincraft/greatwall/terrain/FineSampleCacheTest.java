package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache exists to skip terrain walks, so every test here is about the two ways it could lie: by
 * answering with a column from another world, or by trusting a file it could not read completely.
 */
class FineSampleCacheTest {

    private static ColumnSample reference(int groundTopY) {
        return new ColumnSample(groundTopY, OptionalInt.of(groundTopY + 1), OptionalInt.empty(),
                "minecraft:plains", Quality.REFERENCE, false);
    }

    @Test
    void storedColumnsSurviveAReload(@TempDir Path dir) {
        Path file = dir.resolve("fine-columns.bin");
        var cache = FineSampleCache.load(file, "identity");
        assertEquals(0, cache.size());
        assertTrue(cache.persistent(), "a missing file must still leave the cache writable");

        cache.store(List.of(
                new FineSampleCache.Entry(10, -20, reference(64)),
                new FineSampleCache.Entry(-3, 7, reference(70))));
        assertTrue(Files.isRegularFile(file), "the first run must persist what it sampled");

        var reloaded = FineSampleCache.load(file, "identity");
        assertEquals(2, reloaded.size());
        var column = reloaded.get(10, -20);
        assertNotNull(column);
        assertEquals(64, column.groundTopY());
        assertEquals(OptionalInt.of(65), column.waterTopY());
        assertEquals(OptionalInt.empty(), column.bedTopY());
        assertEquals("minecraft:plains", column.biomeKey());
        assertEquals(Quality.REFERENCE, column.quality());
        assertEquals(1, reloaded.hits());
        assertNull(reloaded.get(10, -19));
        assertEquals(1, reloaded.misses());
    }

    @Test
    void columnsWhoseValueDependsOnTheWalkStartAreNotPersisted(@TempDir Path dir) {
        Path file = dir.resolve("fine-columns.bin");
        var cache = FineSampleCache.load(file, "identity");
        cache.store(List.of(
                new FineSampleCache.Entry(0, 0, new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(),
                        "minecraft:plains", Quality.CENSORED, false)),
                new FineSampleCache.Entry(1, 0, new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(),
                        "minecraft:plains", Quality.REFERENCE, true)),
                new FineSampleCache.Entry(2, 0, reference(66))));

        var reloaded = FineSampleCache.load(file, "identity");
        assertEquals(1, reloaded.size(), "only the plain reference column may be reused");
        assertNotNull(reloaded.get(2, 0));
        assertNull(reloaded.get(0, 0));
        assertNull(reloaded.get(1, 0));
    }

    @Test
    void anotherIdentityIsIgnoredRatherThanTrusted(@TempDir Path dir) {
        Path file = dir.resolve("fine-columns.bin");
        FineSampleCache.load(file, "identity-a")
                .store(List.of(new FineSampleCache.Entry(4, 4, reference(64))));

        var other = FineSampleCache.load(file, "identity-b");
        assertEquals(0, other.size());
        assertNull(other.get(4, 4));
        assertTrue(other.persistent());
    }

    @Test
    void aTruncatedFileIsIgnoredRatherThanTrusted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fine-columns.bin");
        FineSampleCache.load(file, "identity")
                .store(List.of(new FineSampleCache.Entry(4, 4, reference(64))));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 6));

        var damaged = FineSampleCache.load(file, "identity");
        assertEquals(0, damaged.size());
        assertNull(damaged.get(4, 4));
    }

    @Test
    void aCacheThatIsNotPersistentNeverAnswers() {
        var none = FineSampleCache.none();
        none.store(List.of(new FineSampleCache.Entry(1, 1, reference(64))));
        assertEquals(0, none.size());
        assertNull(none.get(1, 1));
    }
}
