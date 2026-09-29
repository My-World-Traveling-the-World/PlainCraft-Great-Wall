package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import local.plaincraft.greatwall.planning.PrefixSummaryIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentCacheTest {
    @TempDir Path directory;

    @Test void crcFailureRecomputesCacheOnly() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var cache = new SegmentCacheStore(directory.resolve("cache"), recipe);
        Path history = directory.resolve("history");
        Files.createDirectories(history); Files.writeString(history.resolve("HEAD"), "permanent");
        var value = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        cache.writeGeometry(value);
        assertEquals(value, cache.readGeometry(value.key()).orElseThrow());
        Path blob;
        try (var files = Files.walk(directory.resolve("cache"))) { blob = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(blob); bytes[bytes.length - 3] ^= 1; Files.write(blob, bytes);
        assertTrue(cache.readGeometry(value.key()).isEmpty());
        assertEquals(1, cache.corruptCount());
        assertEquals("permanent", Files.readString(history.resolve("HEAD")));
        cache.writeGeometry(value);
        assertEquals(value, cache.readGeometry(value.key()).orElseThrow());
    }

    @Test void invalidPrefixCannotSkipGap() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var cache = new SegmentCacheStore(directory.resolve("cache"), recipe);
        cache.writePrefix(new PrefixSummary(256, 123456, Optional.empty(), PlanningFixtures.HASH), recipe.recipeHash());
        assertTrue(cache.readPrefix(256, recipe.recipeHash()).isEmpty());
        var prefix = PrefixSummaryIndex.origin();
        for (int i = 0; i < 256; i++) {
            var g = PlanningFixtures.geometry(recipe, i, Optional.empty());
            cache.writeGeometry(g); prefix = PrefixSummaryIndex.reduce(prefix, g);
        }
        assertTrue(cache.readPrefix(256, recipe.recipeHash()).isEmpty());
        cache.writePrefix(prefix, recipe.recipeHash());
        assertEquals(prefix, cache.readPrefix(256, recipe.recipeHash()).orElseThrow());
        cache.invalidate(17, 18, recipe.recipeHash());
        assertTrue(cache.readPrefix(256, recipe.recipeHash()).isEmpty());
        assertTrue(cache.readGeometry(PlanningFixtures.geometry(recipe, 16, Optional.empty()).key()).isPresent());
    }

    @Test void malformedLengthsRejected() throws Exception {
        var recipe = PlanningFixtures.recipe(1);
        var cache = new SegmentCacheStore(directory.resolve("cache"), recipe);
        var value = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        cache.writeGeometry(value);
        Path blob;
        try (var files = Files.walk(directory.resolve("cache"))) { blob = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(blob);
        int rawLengthOffset = 4 + 4 + 4 + 8 + 4 + recipe.dimension().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32 + 8;
        ByteBuffer.wrap(bytes).putInt(rawLengthOffset, Integer.MAX_VALUE);
        Files.write(blob, bytes);
        assertTrue(cache.readGeometry(value.key()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> cache.invalidate(0, 4097, recipe.recipeHash()));
        assertThrows(IllegalArgumentException.class, () -> cache.readGeometry(new SegmentKey(PlanningFixtures.HASH, recipe.dimension(), 0)));
    }

    @Test void parallelVolumeWritersCannotLoseBlobs() throws Exception {
        var recipe = PlanningFixtures.recipe(1);
        var cache = new SegmentCacheStore(directory.resolve("cache"), recipe);
        var values = new ArrayList<SegmentGeometry>();
        for (int i = 0; i < 32; i++) values.add(PlanningFixtures.geometry(recipe, i, Optional.empty()));
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (var value : values) jobs.add(workers.submit(() -> { cache.writeGeometry(value); return null; }));
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
        }
        for (var value : values) assertEquals(value, cache.readGeometry(value.key()).orElseThrow());
        try (var files = Files.walk(directory)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
}
