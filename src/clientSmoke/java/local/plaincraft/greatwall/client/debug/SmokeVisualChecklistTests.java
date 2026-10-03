package local.plaincraft.greatwall.client.debug;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;

/** Standalone ledger contracts and an explicit real-client runner; neither certifies visuals. */
public final class SmokeVisualChecklistTests {
    private SmokeVisualChecklistTests() {}

    /** Uses the existing clientSmoke runtime without starting or impersonating a Minecraft client. */
    public static void main(String[] args) throws IOException {
        System.out.println("human_visibility=NOT_RUN human_smoke_merging=NOT_RUN");
        System.out.println("actual_client_thread=NOT_RUN; invoke run() on the real client main thread");
        try {
            require(args.length == 0, "standalone ledger runner takes no arguments");
            Path root = Files.createTempDirectory("s4-smoke-checklist-");
            Path frame = root.resolve("frame.png");
            try {
                writeFrame(frame);
                ledgerContracts(root, frame);
                System.out.println("ledger_value_hash_path_thread_contracts=COMPLETED; link checks still required");
                linkedEvidenceFails(root, frame);
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    cleanup(root, frame);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            cleanup(root, frame);
            System.out.println("ledger_contract=PASS official_cases=72 roof_controls=72; NOT visual acceptance");
        } catch (IOException | RuntimeException | Error failure) {
            System.err.println("ledger_contract=FAIL; no visual acceptance");
            throw failure;
        }
    }

    /** Invoke on the real client's main thread; no automatic registration or production wiring. */
    public static CompletionStage<Void> run() throws IOException {
        Minecraft client = Minecraft.getInstance();
        require(client.isSameThread(), "test runner must execute on the actual client main thread");
        Path root = Files.createTempDirectory(client.gameDirectory.toPath(), "s4-smoke-checklist-");
        Path frame = root.resolve("frame.png");
        CompletionStage<Void> result;
        try {
            writeFrame(frame);
            ledgerContracts(root, frame);
            result = actualClientThreadCannotBeReplacedByWorker(client, frame);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                cleanup(root, frame);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return result.handleAsync((ignored, failure) -> {
            try {
                cleanup(root, frame);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw new java.util.concurrent.CompletionException(cleanup);
            }
            if (failure != null) throw new java.util.concurrent.CompletionException(failure);
            return null;
        }, client);
    }

    private static void writeFrame(Path frame) throws IOException {
        require(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", frame.toFile()),
                "test PNG encoder unavailable");
    }

    private static void cleanup(Path root, Path frame) throws IOException {
        Files.deleteIfExists(frame);
        Files.deleteIfExists(root);
    }

    private static void ledgerContracts(Path root, Path frame) throws IOException {
        matrixIsCompleteAndImmutable(root);
        humanAndStateEvidenceStayDistinct(root, frame);
        missingMismatchUnknownAndDuplicateEvidenceFail(root, frame);
        changedEmptyAndOversizedFramesFail(root, frame);
        pathsStayInsideClientDirectory(root, frame);
        rejectedClientThreadCannotRecord(root, frame);
        recordsRejectInvalidValues();
    }

    private static SmokeVisualChecklist ledger(Path root) {
        return new SmokeVisualChecklist(() -> true, () -> root);
    }

    private static void matrixIsCompleteAndImmutable(Path root) {
        var checklist = ledger(root);
        var tuples = new HashSet<List<Object>>();
        var ids = new HashSet<String>();
        for (var c : checklist.cases()) {
            require(ids.add(c.id()), "duplicate case id");
            require(tuples.add(List.of(c.betterSmoke(), c.litPits(), c.hay(), c.roof(), c.rain(),
                    c.distance(), c.particleSetting())), "duplicate matrix tuple");
            require(c.particleSetting() == ParticleStatus.ALL.getId(), "particles must remain ALL");
            require(c.id().startsWith(c.roof() ? "roof_control-" : "official-"), "roof control mislabeled");
        }
        require(ids.size() == 144 && tuples.size() == 144, "72 official cases plus 72 roof controls required");
        require(checklist.cases().stream().filter(c -> !c.roof()).count() == 72, "official matrix incomplete");
        require(checklist.cases().stream().filter(SmokeVisualChecklist.Case::roof).count() == 72,
                "roof negative-control matrix incomplete");
        for (boolean better : new boolean[] {false, true}) for (int pits : new int[] {0, 1, 4})
            for (boolean hay : new boolean[] {false, true}) for (boolean roof : new boolean[] {false, true})
                for (boolean rain : new boolean[] {false, true}) for (int distance : new int[] {32, 64, 128}) {
                    require(tuples.contains(List.of(better, pits, hay, roof, rain, distance, ParticleStatus.ALL.getId())),
                            "Cartesian matrix tuple missing");
                }
        rejects(UnsupportedOperationException.class, () -> checklist.cases().clear());
        require(checklist.observations().isEmpty(), "cases must not manufacture observations");
        require(checklist.cases().equals(ledger(root).cases()), "case order must repeat");
    }

    private static void humanAndStateEvidenceStayDistinct(Path root, Path frame) throws IOException {
        var checklist = ledger(root);
        String hash = hash(frame);
        String unlit = checklist.cases().getFirst().id();
        String lit = lit(checklist);
        var state = observation(unlit, "frame.png", hash, false, false, "automated_state");
        checklist.record(state);
        var snapshot = checklist.observations();
        var human = observation(lit, "frame.png", hash, true, true, "human");
        checklist.record(human);
        require(snapshot.equals(List.of(state)), "observation snapshots must not change after recording");
        require(checklist.observations().equals(List.of(state, human)), "observer evidence must remain explicit");
        rejects(UnsupportedOperationException.class, () -> checklist.observations().clear());
        require(checklist.observations().size() < checklist.cases().size(), "missing observations must stay missing");
        rejects(IllegalArgumentException.class,
                () -> observation(lit, "frame.png", hash, true, false, "automated_state"));
        rejects(IllegalArgumentException.class,
                () -> observation(lit, "frame.png", hash, true, true, "automated_state"));
        rejects(IllegalArgumentException.class,
                () -> observation(lit, "frame.png", hash, false, true, "automated_state"));
        rejects(IllegalArgumentException.class,
                () -> observation(lit, "frame.png", hash, false, true, "human"));
    }

    private static void missingMismatchUnknownAndDuplicateEvidenceFail(Path root, Path frame) throws IOException {
        var checklist = ledger(root);
        String id = lit(checklist);
        String hash = hash(frame);
        rejects(IllegalArgumentException.class,
                () -> checklist.record(observation("unknown", "frame.png", hash, false, false, "automated_state")));
        rejects(IllegalArgumentException.class,
                () -> checklist.record(observation(id, "missing.png", hash, false, false, "automated_state")));
        String wrong = (hash.startsWith("0") ? "1" : "0") + hash.substring(1);
        rejects(IllegalArgumentException.class,
                () -> checklist.record(observation(id, "frame.png", wrong, false, false, "automated_state")));
        require(checklist.observations().isEmpty(), "rejected evidence must not consume a case id");
        var accepted = observation(id, "frame.png", hash, false, false, "automated_state");
        checklist.record(accepted);
        rejects(IllegalArgumentException.class, () -> checklist.record(accepted));
        require(checklist.observations().equals(List.of(accepted)), "duplicate must not replace the original");
    }

    private static void changedEmptyAndOversizedFramesFail(Path root, Path frame) throws IOException {
        var checklist = ledger(root);
        String id = lit(checklist);
        Path changed = root.resolve("changed.png");
        Path empty = root.resolve("empty.png");
        Path oversized = root.resolve("oversized.png");
        try {
            Files.copy(frame, changed);
            String original = hash(changed);
            Files.write(changed, new byte[] {1}, StandardOpenOption.APPEND);
            require(!original.equals(hash(changed)), "fixture content change must alter SHA-256");
            rejects(IllegalArgumentException.class,
                    () -> checklist.record(observation(id, "changed.png", original, false, false, "automated_state")));
            Files.createFile(empty);
            String emptyHash = hash(empty);
            rejects(IllegalArgumentException.class,
                    () -> checklist.record(observation(id, "empty.png", emptyHash, false, false, "automated_state")));
            try (var channel = FileChannel.open(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(64L << 20);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
            require(Files.size(oversized) == (64L << 20) + 1, "oversized fixture must exceed 64 MiB");
            rejects(IllegalArgumentException.class,
                    () -> checklist.record(observation(id, "oversized.png", original, false, false, "automated_state")));
            require(checklist.observations().isEmpty(), "invalid frame content must not become evidence");
            var corrected = observation(id, "changed.png", hash(changed), false, false, "automated_state");
            checklist.record(corrected);
            require(checklist.observations().equals(List.of(corrected)), "only the current file hash may be recorded");
        } finally {
            Files.deleteIfExists(changed);
            Files.deleteIfExists(empty);
            Files.deleteIfExists(oversized);
        }
    }

    private static void pathsStayInsideClientDirectory(Path root, Path frame) throws IOException {
        var checklist = ledger(root);
        String id = lit(checklist);
        String hash = hash(frame);
        for (String path : List.of(frame.toAbsolutePath().toString(), "../frame.png", "..\\frame.png",
                "nested/../../frame.png", "C:frame.png", "\\frame.png", "\\\\server\\share\\frame.png", ".")) {
            rejects(IllegalArgumentException.class,
                    () -> checklist.record(observation(id, path, hash, false, false, "automated_state")));
        }
        require(checklist.observations().isEmpty(), "rejected paths cannot become evidence");
    }

    private static void linkedEvidenceFails(Path root, Path frame) throws IOException {
        var checklist = ledger(root);
        String id = lit(checklist);
        String hash = hash(frame);
        Path outside = Files.createTempDirectory(root.getParent(), "s4-smoke-outside-");
        Path outsideFrame = outside.resolve("frame.png");
        Path linkedFile = root.resolve("linked.png");
        Path linkedDirectory = root.resolve("linked-directory");
        Path internalLink = root.resolve("internal-link.png");
        try {
            Files.copy(frame, outsideFrame);
            try {
                Files.createSymbolicLink(linkedFile, outsideFrame);
                Files.createSymbolicLink(linkedDirectory, outside);
                Files.createSymbolicLink(internalLink, frame);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                throw new IOException("link_cases=NOT_RUN: symbolic-link fixtures unavailable; no overall PASS", unavailable);
            }
            for (String path : List.of("linked.png", "linked-directory/frame.png", "internal-link.png")) {
                rejects(IllegalArgumentException.class,
                        () -> checklist.record(observation(id, path, hash, false, false, "automated_state")));
            }
            require(checklist.observations().isEmpty(), "linked evidence must not consume a case id");
            var accepted = observation(id, "frame.png", hash, false, false, "automated_state");
            checklist.record(accepted);
            require(checklist.observations().equals(List.of(accepted)), "ordinary evidence must still be accepted");
        } finally {
            Files.deleteIfExists(linkedFile);
            Files.deleteIfExists(linkedDirectory);
            Files.deleteIfExists(internalLink);
            Files.deleteIfExists(outsideFrame);
            Files.deleteIfExists(outside);
        }
    }

    private static void rejectedClientThreadCannotRecord(Path root, Path frame) throws IOException {
        var checklist = new SmokeVisualChecklist(() -> false, () -> root);
        var observation = observation(lit(checklist), "frame.png", hash(frame), false, false, "automated_state");
        rejects(IllegalStateException.class, () -> checklist.record(observation));
        require(checklist.observations().isEmpty(), "rejected thread must not alter evidence");
    }

    private static CompletionStage<Void> actualClientThreadCannotBeReplacedByWorker(Minecraft client, Path frame)
            throws IOException {
        var checklist = new SmokeVisualChecklist();
        String relative = client.gameDirectory.toPath().toAbsolutePath().normalize()
                .relativize(frame.toAbsolutePath().normalize()).toString();
        var observation = observation(lit(checklist), relative, hash(frame), false, false, "automated_state");
        return CompletableFuture.runAsync(() ->
                rejects(IllegalStateException.class, () -> checklist.record(observation)), action -> {
                    Thread worker = new Thread(action, "s4-checklist-wrong-client-thread");
                    worker.setDaemon(true);
                    worker.start();
                }).thenRunAsync(() -> {
                    require(checklist.observations().isEmpty(), "native worker rejection must leave the ledger empty");
                    checklist.record(observation);
                    require(checklist.observations().equals(List.of(observation)),
                            "native client thread must accept valid state evidence");
                }, client);
    }

    private static void recordsRejectInvalidValues() {
        for (String blank : new String[] {null, "", " "}) {
            rejects(IllegalArgumentException.class,
                    () -> new SmokeVisualChecklist.Case(blank, false, 1, false, false, false, 32, ParticleStatus.ALL.getId()));
            rejects(IllegalArgumentException.class,
                    () -> observation(blank, "frame.png", "0".repeat(64), false, false, "human"));
            rejects(IllegalArgumentException.class,
                    () -> observation("case", blank, "0".repeat(64), false, false, "human"));
        }
        for (int pits : new int[] {-1, 2, 64}) {
            rejects(IllegalArgumentException.class,
                    () -> new SmokeVisualChecklist.Case("bad", false, pits, false, false, false, 32, ParticleStatus.ALL.getId()));
        }
        rejects(IllegalArgumentException.class,
                () -> new SmokeVisualChecklist.Case("bad", false, 1, false, false, false, 31, ParticleStatus.ALL.getId()));
        rejects(IllegalArgumentException.class,
                () -> new SmokeVisualChecklist.Case("bad", false, 1, false, false, false, 32, ParticleStatus.MINIMAL.getId()));
        rejects(IllegalArgumentException.class,
                () -> observation("case", "frame.png", "0".repeat(63), false, false, "human"));
        rejects(IllegalArgumentException.class,
                () -> observation("case", "frame.png", "A".repeat(64), false, false, "human"));
        rejects(IllegalArgumentException.class,
                () -> observation("case", "frame.png", "0".repeat(64), false, false, "video"));
        rejects(IllegalArgumentException.class,
                () -> new SmokeVisualChecklist.Observation("case", "frame.png", "0".repeat(64),
                        false, false, "human", ""));
        rejects(NullPointerException.class, () -> ledger(Path.of(".")).record(null));
    }

    private static String lit(SmokeVisualChecklist checklist) {
        return checklist.cases().stream().filter(c -> !c.roof() && c.litPits() == 4).findFirst().orElseThrow().id();
    }

    private static SmokeVisualChecklist.Observation observation(String id, String path, String hash,
            boolean visible, boolean merged, String observer) {
        return new SmokeVisualChecklist.Observation(id, path, hash, visible, merged, observer,
                "Synthetic contract-test PNG only; NOT human visual acceptance. build=test seed=none recipe=none");
    }

    private static String hash(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("unexpected rejection", failure);
        }
        throw new AssertionError("expected rejection: " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
