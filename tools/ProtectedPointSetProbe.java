import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** Pure JDK diagnostic: no world reads, metadata migrations or production edits. */
public final class ProtectedPointSetProbe {
    private static final Comparator<Point3i> ORDER = Comparator.comparingInt(Point3i::x)
            .thenComparingInt(Point3i::y).thenComparingInt(Point3i::z);

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments expected");
        System.out.println("jdk=" + System.getProperty("java.runtime.version"));
        System.out.println("scope=point-section-only; full_metadata_equivalence=NOT_RUN");
        System.out.println("s1c_hotspot_attribution=NOT_RUN; no production collection changed");
        for (int count : new int[] {3274, 140000}) inspect(count);
    }

    private static void inspect(int count) throws Exception {
        long started = System.nanoTime();
        var source = new HashSet<Point3i>();
        for (int i = 0; i < count; i++) {
            source.add(new Point3i(i % 16, i / 256, i / 16 % 16));
        }
        long buildNanos = System.nanoTime() - started;
        started = System.nanoTime();
        Set<Point3i> candidate = Collections.unmodifiableSet(new HashSet<>(source));
        long candidateNanos = System.nanoTime() - started;
        System.out.printf("count=%d build_ms=%.3f candidate_ms=%.3f old_copy=STARTED%n",
                count, buildNanos / 1_000_000.0, candidateNanos / 1_000_000.0);
        started = System.nanoTime();
        Set<Point3i> current = Set.copyOf(source);
        long currentNanos = System.nanoTime() - started;
        if (!current.equals(candidate) || current.hashCode() != candidate.hashCode()) {
            throw new AssertionError("collection membership or hash differs");
        }
        String hash = digest(current);
        if (!hash.equals(digest(candidate))) throw new AssertionError("point-section bytes differ");
        source.clear();
        if (current.size() != count || candidate.size() != count) {
            throw new AssertionError("snapshot aliased caller collection");
        }
        rejectsMutation(current);
        rejectsMutation(candidate);
        System.out.printf("count=%d old_copy_ms=%.3f candidate_ms=%.3f point_sha256=%s equivalence=PASS%n",
                count, currentNanos / 1_000_000.0, candidateNanos / 1_000_000.0, hash);
    }

    private static String digest(Set<Point3i> points) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(points.size());
            for (Point3i point : points.stream().sorted(ORDER).toList()) {
                out.writeInt(point.x());
                out.writeInt(point.y());
                out.writeInt(point.z());
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static void rejectsMutation(Set<Point3i> points) {
        try {
            points.clear();
            throw new AssertionError("frozen set accepted mutation");
        } catch (UnsupportedOperationException expected) {
            // Both candidates must expose the same read-only contract.
        }
    }
}
