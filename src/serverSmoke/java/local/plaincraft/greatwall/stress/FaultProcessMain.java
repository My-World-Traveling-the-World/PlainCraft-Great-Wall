package local.plaincraft.greatwall.stress;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import local.plaincraft.greatwall.diagnostics.FaultInjector;

/**
 * Standalone fault sub-process for the S5 crash matrix. It runs in its own JVM,
 * reaches the Nth hit of a chosen {@link FaultInjector.Point}, force-writes
 * {@code fault-hit.json}, then either throws (exception mode, parent expects a
 * failed run) or calls {@code Runtime.halt(137)} (halt mode, parent expects 137 —
 * never a build success). It never deletes or mutates the world to fake a pass.
 */
public final class FaultProcessMain {
    private static final String MARKER = "greatwall_test_world";
    private static final int MARKER_MAX_BYTES = 8192;
    private static final int TREE_MAX_ENTRIES = 4096;
    private static final long OCCURRENCE_MAX = 1_000_000_000_000_000_000L;

    private FaultProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path root = validateRoot(workspace, arguments.root());
        if (!System.getProperty("sun.java.command", "")
                .startsWith(FaultProcessMain.class.getName() + " ")) {
            throw new IllegalStateException("dedicated FaultProcessMain child JVM required");
        }
        ProcessFaultInjector injector = new ProcessFaultInjector(root, arguments);
        for (long sequence = 1; sequence <= arguments.occurrence(); sequence++) {
            injector.hit(arguments.point(), Long.toString(sequence));
        }
        throw new IllegalStateException("FAULT_NOT_HIT point=" + arguments.point()
                + " expected=" + arguments.occurrence());
    }

    private static Path validateRoot(Path workspace, Path requested) throws IOException {
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute: " + requested);
        }
        Path faultRuns = workspace.resolve("build").resolve("fault-runs");
        if (!requested.startsWith(faultRuns) || requested.equals(faultRuns)) {
            throw new IllegalArgumentException("root must be a strict descendant of " + faultRuns);
        }
        rejectReservedComponents(requested);
        rejectSymlinkEscape(requested);
        Path marker = requested.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("test marker missing at " + marker);
        }
        if (Files.size(marker) > MARKER_MAX_BYTES) {
            throw new IllegalStateException("test marker exceeds " + MARKER_MAX_BYTES + " bytes: " + marker);
        }
        return requested;
    }

    private static void rejectReservedComponents(Path path) {
        for (Path component : path) {
            String name = component.toString();
            if (name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("mods") || lower.equals("save") || lower.equals("saves")) {
                throw new IllegalStateException("root must not reference a real instance directory: " + name);
            }
            if (name.endsWith(".") || name.endsWith(" ") || name.endsWith(":")) {
                throw new IllegalStateException("root component has a reserved trailing char: " + name);
            }
        }
    }

    private static void rejectSymlinkEscape(Path path) throws IOException {
        Path probe = path;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        for (Path p = probe; p != null; p = p.getParent()) {
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IllegalStateException("symlink/special file forbidden in root path: " + p);
            }
        }
        // Bounded walk over whatever already exists below the requested root.
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        final int[] count = {0};
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IllegalStateException("symlink/special file forbidden in root tree: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IllegalStateException("symlink/special file forbidden in root tree: " + file);
                }
                if (++count[0] > TREE_MAX_ENTRIES) {
                    throw new IllegalStateException("root tree exceeds " + TREE_MAX_ENTRIES + " entries");
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Exact fixed argument order with no optional switches. */
    private record Arguments(Path root, FaultInjector.Point point, long occurrence, Mode mode) {
        static Arguments parse(String[] args) {
            if (args.length != 8) {
                throw new IllegalArgumentException(
                        "expected --root <absolute> --point <enum> --occurrence <positive> --mode exception|halt");
            }
            if (!"--root".equals(args[0]) || !"--point".equals(args[2])
                    || !"--occurrence".equals(args[4]) || !"--mode".equals(args[6])) {
                throw new IllegalArgumentException("flags must be --root --point --occurrence --mode in order");
            }
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            FaultInjector.Point point = FaultInjector.Point.valueOf(args[3]);
            long occurrence = parsePositive(args[5]);
            Mode mode = Mode.valueOf(args[7].toUpperCase(Locale.ROOT));
            return new Arguments(root, point, occurrence, mode);
        }

        private static long parsePositive(String value) {
            if (!value.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException("occurrence must be a positive integer: " + value);
            }
            long parsed = Long.parseLong(value);
            if (parsed > OCCURRENCE_MAX) {
                throw new IllegalArgumentException("occurrence exceeds safe bound: " + value);
            }
            return parsed;
        }
    }

    private enum Mode { EXCEPTION, HALT }

    /** Counts hits and faults exactly on the configured Nth hit, in this subprocess only. */
    private static final class ProcessFaultInjector implements FaultInjector {
        private final Path root;
        private final Arguments arguments;
        private long hits;

        ProcessFaultInjector(Path root, Arguments arguments) {
            this.root = root;
            this.arguments = arguments;
        }

        @Override
        public void hit(Point point, String key) throws IOException {
            if (point != arguments.point()) return;
            if (++hits != arguments.occurrence()) return;
            writeFaultHit(key);
            if (arguments.mode() == Mode.HALT) {
                Runtime.getRuntime().halt(137);
            }
            throw new IOException("INJECTED_FAULT point=" + point + " seq=" + hits);
        }

        private void writeFaultHit(String key) throws IOException {
            String json = "{\"root\":\"" + root + "\","
                    + "\"point\":\"" + arguments.point().name() + "\","
                    + "\"occurrence\":" + arguments.occurrence() + ","
                    + "\"mode\":\"" + arguments.mode().name().toLowerCase(Locale.ROOT) + "\","
                    + "\"pid\":" + ProcessHandle.current().pid() + ","
                    + "\"seq\":" + hits + ","
                    + "\"key\":\"" + key + "\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            // The witness goes in a per-run directory, never the shared root. The driver drives many
            // runs against one root, so a root-level CREATE_NEW file made every run after the first
            // fail with FileAlreadyExistsException and be reported as a failed fault injection.
            Path witness = root.resolve(runDirectoryName()).resolve("fault-hit.json");
            Files.createDirectories(witness.getParent());
            try (FileChannel channel = FileChannel.open(witness,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        /** The same directory name the driver uses, so the witness and the driver agree. */
        private String runDirectoryName() {
            return arguments.point().name() + "-" + arguments.occurrence();
        }
    }
}
