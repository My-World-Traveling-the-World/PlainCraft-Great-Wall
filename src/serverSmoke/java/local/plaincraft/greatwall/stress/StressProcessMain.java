package local.plaincraft.greatwall.stress;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Standalone validator for the S5 stress profile parameters.
 *
 * <p>The stress profile itself must run inside a live dedicated server, because it demands chunks,
 * jumps across the corridor and triggers reloads — none of which exist in a bare JVM. So unlike
 * {@link FaultProcessMain}, this class does not execute the profile. It exists so the pipeline can
 * fail fast and loudly on a malformed or unsafe request before paying for a server start, and it
 * prints the exact properties the in-server fixture will read.
 *
 * <p>Validation mirrors the fault runner's isolation contract: the root must be an absolute strict
 * descendant of {@code build/stress-runs} and must contain the isolated world marker.
 */
public final class StressProcessMain {
    private static final String MARKER = "greatwall_test_world";
    private static final long MINUTES_MAX = 24L * 60L;

    private StressProcessMain() {}

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path root = null;
        long minutes = -1L;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--root" -> root = Path.of(requireValue(args, ++index, "--root"));
                case "--minutes" -> minutes = parseMinutes(requireValue(args, ++index, "--minutes"));
                default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
            }
        }
        if (root == null) throw new IllegalArgumentException("--root is required");
        if (minutes <= 0) throw new IllegalArgumentException("--minutes is required and must be positive");
        if (!root.isAbsolute()) throw new IllegalArgumentException("root must be absolute: " + root);
        // The stress server reuses the isolated smoke directory. Accept only that subtree so a
        // mistyped root can never point the profile at a real instance.
        Path smokeRuns = workspace.resolve("build").resolve("run").normalize();
        Path normalized = root.normalize();
        if (!normalized.startsWith(smokeRuns) || normalized.equals(smokeRuns)) {
            throw new IllegalArgumentException("root must be a strict descendant of " + smokeRuns);
        }
        Path marker = normalized.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("isolated test-world marker missing at " + marker);
        }
        // Printed rather than set: the server-side fixture reads these as system properties, and they
        // are supplied by the Gradle task, not by this validator process.
        System.out.println("STRESS_PROFILE_VALIDATED root=" + normalized + " minutes=" + minutes
                + " mode=MIXED_24H requires=-Dgreatwall.s5.stress=true -Dgreatwall.stress.minutes=" + minutes);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException(flag + " requires a value");
        return args[index];
    }

    private static long parseMinutes(String raw) {
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("malformed --minutes value: " + raw, malformed);
        }
        if (value <= 0 || value > MINUTES_MAX) {
            throw new IllegalArgumentException("--minutes must be within 1.." + MINUTES_MAX + ", found " + value);
        }
        return value;
    }
}
