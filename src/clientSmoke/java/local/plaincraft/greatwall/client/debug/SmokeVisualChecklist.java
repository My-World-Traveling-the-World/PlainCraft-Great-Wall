package local.plaincraft.greatwall.client.debug;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;

/** Test-only evidence ledger. Neither case creation nor state observations certify visuals. */
public final class SmokeVisualChecklist {
    public record Case(String id, boolean betterSmoke, int litPits, boolean hay,
            boolean roof, boolean rain, int distance, int particleSetting) {
        public Case {
            requireText(id, "case id");
            if (litPits != 0 && litPits != 1 && litPits != 4) {
                throw new IllegalArgumentException("litPits must be 0, 1 or 4");
            }
            if (distance != 32 && distance != 64 && distance != 128) {
                throw new IllegalArgumentException("distance must be 32, 64 or 128");
            }
            if (particleSetting != ParticleStatus.ALL.getId()) {
                throw new IllegalArgumentException("particles must be ALL");
            }
        }
    }

    public record Observation(String id, String screenshotRelativePath, String frameSha256,
            boolean visible, boolean merged, String observer, String note) {
        public Observation {
            requireText(id, "case id");
            requireText(screenshotRelativePath, "screenshot path");
            if (frameSha256 == null || !frameSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("64-character lowercase SHA-256 required");
            }
            if (!"human".equals(observer) && !"automated_state".equals(observer)) {
                throw new IllegalArgumentException("observer must be human or automated_state");
            }
            requireText(note, "observation note");
            // The frozen schema has no quantified video evidence field.
            if ("automated_state".equals(observer) && (visible || merged)) {
                throw new IllegalArgumentException("state evidence cannot attest visibility or smoke merging");
            }
            if (merged && !visible) {
                throw new IllegalArgumentException("merged smoke must also be visible");
            }
        }
    }

    private static final long MAX_FRAME_BYTES = 64L << 20;
    private final List<Case> cases;
    private final Set<String> ids;
    private final Map<String, Observation> recorded = new LinkedHashMap<>();
    private final BooleanSupplier clientThread;
    private final Supplier<Path> screenshotRoot;

    public SmokeVisualChecklist() {
        this(() -> Minecraft.getInstance().isSameThread(),
                () -> Minecraft.getInstance().gameDirectory.toPath());
    }

    // Package-local ports let focused tests isolate filesystem and thread rejection.
    SmokeVisualChecklist(BooleanSupplier clientThread, Supplier<Path> screenshotRoot) {
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
        this.screenshotRoot = Objects.requireNonNull(screenshotRoot, "screenshotRoot");
        var matrix = new ArrayList<Case>();
        // Official cases first; every roofed case is explicitly a negative control.
        for (boolean roof : new boolean[] {false, true}) {
            for (boolean better : new boolean[] {false, true}) {
                for (int pits : new int[] {0, 1, 4}) {
                    for (boolean hay : new boolean[] {false, true}) {
                        for (boolean rain : new boolean[] {false, true}) {
                            for (int distance : new int[] {32, 64, 128}) {
                                String id = (roof ? "roof_control" : "official")
                                        + "-better_" + better + "-lit_" + pits + "-hay_" + hay
                                        + "-rain_" + rain + "-distance_" + distance;
                                matrix.add(new Case(id, better, pits, hay, roof, rain, distance,
                                        ParticleStatus.ALL.getId()));
                            }
                        }
                    }
                }
            }
        }
        cases = List.copyOf(matrix);
        ids = cases.stream().map(Case::id).collect(Collectors.toUnmodifiableSet());
    }

    public List<Case> cases() {
        return cases;
    }

    public synchronized void record(Observation observation) {
        if (!clientThread.getAsBoolean()) {
            throw new IllegalStateException("smoke observations require the client main thread");
        }
        Objects.requireNonNull(observation, "observation");
        if (!ids.contains(observation.id())) {
            throw new IllegalArgumentException("unknown smoke case: " + observation.id());
        }
        if (recorded.containsKey(observation.id())) {
            throw new IllegalArgumentException("duplicate smoke case: " + observation.id());
        }
        try {
            Path frame = screenshot(observation.screenshotRelativePath());
            if (!sha256(frame).equals(observation.frameSha256())) {
                throw new IllegalArgumentException("screenshot SHA-256 mismatch");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("screenshot evidence is unavailable", failure);
        }
        recorded.put(observation.id(), observation);
    }

    public synchronized List<Observation> observations() {
        return List.copyOf(recorded.values());
    }

    private Path screenshot(String relativePath) throws IOException {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new IllegalArgumentException("screenshot path must be relative to the client game directory");
        }
        for (Path component : relative) {
            if ("..".equals(component.toString())) {
                throw new IllegalArgumentException("screenshot path cannot traverse parent directories");
            }
        }
        Path root = Objects.requireNonNull(screenshotRoot.get(), "screenshotRoot").toRealPath();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("screenshot path escapes the client game directory");
        }
        // Reject linked ancestors as well as linked files, including Windows junctions.
        Path cursor = root;
        for (Path component : root.relativize(resolved)) {
            cursor = cursor.resolve(component);
            var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IllegalArgumentException("screenshot path cannot contain symbolic links, junctions or special files");
            }
        }
        if (!resolved.toRealPath().startsWith(root)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("screenshot must be a regular file inside the client game directory");
        }
        long size = Files.size(resolved);
        if (size == 0 || size > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("screenshot size must be 1..64 MiB");
        }
        return resolved;
    }

    private static String sha256(Path frame) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (InputStream input = Files.newInputStream(frame, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                count = Math.addExact(count, read);
                if (count > MAX_FRAME_BYTES) {
                    throw new IOException("screenshot grew beyond 64 MiB");
                }
                digest.update(buffer, 0, read);
            }
            if (count == 0) {
                throw new IOException("screenshot became empty");
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
