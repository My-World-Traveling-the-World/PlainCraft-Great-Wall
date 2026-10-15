package local.plaincraft.greatwall.terrain;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;
import java.util.OptionalInt;

import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;

/**
 * On-disk cache of accurately sampled terrain columns, keyed by block column {@code (x, z)}.
 *
 * <p>The fine sample is the wall's most expensive startup stage: every route point probes a whole
 * cross-section of REFERENCE columns, and each probe walks the density field downwards from the
 * surface. Nothing about one column changes between sessions - its value is a pure function of the
 * world seed and the terrain configuration - yet the stage re-walks every column on every world
 * entry. This cache turns the second and later entries into one file read.</p>
 *
 * <p>Only columns the oracle actually probed are stored. The conservative fills a cross-section
 * derives for its unprobed slots are deliberately not, because those depend on the sampling rules
 * rather than on the terrain; a run that hits the cache therefore emits exactly the values a cold
 * run would have produced, whatever the rules are.</p>
 *
 * <p>The file is bound to an identity string (recipe and terrain hash plus dimension). A file
 * written for a different identity is ignored rather than trusted.</p>
 */
public final class FineSampleCache {

    /** One resolved column: the sample at block column {@code (x, z)}. */
    public record Entry(int x, int z, ColumnSample sample) {
        public Entry {
            Objects.requireNonNull(sample, "sample");
        }
    }

    private static final int MAGIC = 0x50475743; // "PGWC"
    private static final int FORMAT = 1;
    private static final int MAX_ENTRIES = 8_000_000;
    private static final int MAX_BIOMES = 65_536;
    private static final int NO_Y = Integer.MIN_VALUE;
    private static final int UPPER_CENSORED = 1;

    /** A cache bound to no file: every lookup misses and nothing is ever written. */
    private static final FineSampleCache NONE = new FineSampleCache(null, null, new long[0], new int[0],
            new int[0], new int[0], new byte[0], new byte[0], new String[0]);

    private final Path file;
    private final String identity;
    private final long[] keys;
    private final int[] groundTopY;
    private final int[] waterTopY;
    private final int[] bedTopY;
    private final int[] biomeIndex;
    private final byte[] quality;
    private final byte[] flags;
    private final String[] biomes;
    private final java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder misses = new java.util.concurrent.atomic.LongAdder();

    private FineSampleCache(Path file, String identity, long[] keys, int[] groundTopY, int[] waterTopY,
                            int[] bedTopY, byte[] quality, byte[] flags, int[] biomeIndex, String[] biomes) {
        this.file = file;
        this.identity = identity;
        this.keys = keys;
        this.groundTopY = groundTopY;
        this.waterTopY = waterTopY;
        this.bedTopY = bedTopY;
        this.quality = quality;
        this.flags = flags;
        this.biomeIndex = biomeIndex;
        this.biomes = biomes;
    }

    private FineSampleCache(Path file, String identity, long[] keys, int[] groundTopY, int[] waterTopY,
                            int[] bedTopY, byte[] quality, byte[] flags, String[] biomes) {
        this(file, identity, keys, groundTopY, waterTopY, bedTopY, quality, flags, new int[0], biomes);
    }

    /** A cache that neither reads nor writes: used where fine samples must never be reused. */
    public static FineSampleCache none() {
        return NONE;
    }

    /**
     * Loads the cache stored at {@code file}, or an empty cache bound to that file when it is
     * missing, unreadable, truncated or written for another identity. A load failure is never
     * fatal: an empty cache simply means this run probes every column again.
     */
    public static FineSampleCache load(Path file, String identity) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(identity, "identity");
        var empty = new FineSampleCache(file, identity, new long[0], new int[0], new int[0], new int[0],
                new byte[0], new byte[0], new int[0], new String[0]);
        if (!Files.isRegularFile(file)) return empty;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != MAGIC) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] fine sample cache {} has an unknown format; ignoring", file);
                return empty;
            }
            int format = in.readInt();
            if (format != FORMAT) {
                GreatWallMod.LOGGER.info("[GreatWall:S1C] fine sample cache {} is format {} (expected {}); ignoring",
                        file, format, FORMAT);
                return empty;
            }
            String stored = in.readUTF();
            if (!stored.equals(identity)) {
                GreatWallMod.LOGGER.info("[GreatWall:S1C] fine sample cache {} belongs to another world recipe; ignoring",
                        file);
                return empty;
            }
            int biomeCount = in.readInt();
            if (biomeCount < 0 || biomeCount > MAX_BIOMES) throw new EOFException("biome table " + biomeCount);
            var biomes = new String[biomeCount];
            for (int i = 0; i < biomeCount; i++) biomes[i] = in.readUTF();
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new EOFException("entry count " + count);
            var keys = new long[count];
            var ground = new int[count];
            var water = new int[count];
            var bed = new int[count];
            var quality = new byte[count];
            var flags = new byte[count];
            var biomeIndex = new int[count];
            for (int i = 0; i < count; i++) {
                keys[i] = in.readLong();
                ground[i] = in.readInt();
                water[i] = in.readInt();
                bed[i] = in.readInt();
                quality[i] = in.readByte();
                flags[i] = in.readByte();
                biomeIndex[i] = in.readInt();
                if (biomeIndex[i] < 0 || biomeIndex[i] >= biomeCount) throw new EOFException("biome index");
            }
            for (int i = 1; i < count; i++) {
                if (keys[i - 1] >= keys[i]) throw new EOFException("keys are not strictly increasing");
            }
            GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CACHE loaded columns={} file={}", count, file);
            return new FineSampleCache(file, identity, keys, ground, water, bed, quality, flags, biomeIndex, biomes);
        } catch (IOException | RuntimeException e) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] fine sample cache {} could not be read ({}); probing every column",
                    file, e.toString());
            return empty;
        }
    }

    /** True when this cache is bound to a file and may therefore be written back. */
    public boolean persistent() {
        return file != null;
    }

    public int size() {
        return keys.length;
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    /** The cached sample at {@code (x, z)}, or {@code null} when the column is not cached. */
    public ColumnSample get(int x, int z) {
        int index = Arrays.binarySearch(keys, pack(x, z));
        if (index < 0) {
            misses.increment();
            return null;
        }
        hits.increment();
        return sample(index);
    }

    private ColumnSample sample(int index) {
        OptionalInt water = waterTopY[index] == NO_Y ? OptionalInt.empty() : OptionalInt.of(waterTopY[index]);
        OptionalInt bed = bedTopY[index] == NO_Y ? OptionalInt.empty() : OptionalInt.of(bedTopY[index]);
        return new ColumnSample(groundTopY[index], water, bed, biomes[biomeIndex[index]],
                Quality.values()[quality[index]], (flags[index] & UPPER_CENSORED) != 0);
    }

    /**
     * Writes the columns this run resolved. Columns the oracle censored are dropped: their value
     * depends on where the walk started, which is a performance decision that may legitimately
     * change between versions, so they are re-probed rather than trusted from disk.
     *
     * <p>The write goes to a sibling temp file and is moved into place, so an interrupted session
     * cannot leave a half-written cache behind.</p>
     */
    public void store(Collection<Entry> entries) {
        if (file == null || identity == null || entries.isEmpty()) return;
        var kept = new ArrayList<Entry>(entries.size());
        for (var entry : entries) {
            var sample = entry.sample();
            if (sample.quality() == Quality.CENSORED || sample.upperCensored()) continue;
            kept.add(entry);
        }
        if (kept.isEmpty()) return;
        kept.sort(Comparator.comparingLong(entry -> pack(entry.x(), entry.z())));
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            var biomeTable = new HashMap<String, Integer>();
            var biomeNames = new ArrayList<String>();
            int written = 0;
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeUTF(identity);
                // The entries are buffered in memory first: their biome references have to be
                // numbered before the table that defines them can be written. Duplicate columns are
                // skipped on the way - two cross-sections may share a column, and both resolve it
                // to the same REFERENCE sample.
                var body = new java.io.ByteArrayOutputStream();
                long previous = Long.MIN_VALUE;
                try (var bodyOut = new DataOutputStream(new BufferedOutputStream(body, 1 << 16))) {
                    for (var entry : kept) {
                        long key = pack(entry.x(), entry.z());
                        if (key == previous) continue;
                        previous = key;
                        var sample = entry.sample();
                        Integer biome = biomeTable.get(sample.biomeKey());
                        if (biome == null) {
                            biome = biomeNames.size();
                            biomeTable.put(sample.biomeKey(), biome);
                            biomeNames.add(sample.biomeKey());
                        }
                        bodyOut.writeLong(key);
                        bodyOut.writeInt(sample.groundTopY());
                        bodyOut.writeInt(sample.waterTopY().orElse(NO_Y));
                        bodyOut.writeInt(sample.bedTopY().orElse(NO_Y));
                        bodyOut.writeByte(sample.quality().ordinal());
                        bodyOut.writeByte(sample.upperCensored() ? UPPER_CENSORED : 0);
                        bodyOut.writeInt(biome);
                        written++;
                    }
                }
                out.writeInt(biomeNames.size());
                for (String name : biomeNames) out.writeUTF(name);
                out.writeInt(written);
                body.writeTo(out);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CACHE stored columns={} file={}", written, file);
        } catch (IOException e) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] fine sample cache {} could not be written ({})", file, e.toString());
        }
    }

    /** The packed column key used for lookups; sorted order is this value's natural order. */
    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
