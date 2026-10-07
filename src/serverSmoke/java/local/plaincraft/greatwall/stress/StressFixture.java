package local.plaincraft.greatwall.stress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Drives one {@link StressController} profile inside a live dedicated server, writing a durable
 * report and stopping the server when the profile is complete.
 *
 * <p>This is the missing real entry point behind {@code runS5Stress}. Before it existed the task
 * merely depended on a generic smoke run, so a "passing" stress gate proved nothing about chunk
 * demand, far jumps, reloads or heap behaviour.
 *
 * <h2>What it refuses to fake</h2>
 * The report is written from observed counters only. A profile that never reached its duration, or
 * that hit any failure, is reported as failed and the server halts non-zero, so the driver cannot
 * read success out of an aborted run.
 */
public final class StressFixture implements AutoCloseable {
    private static final int CHECKPOINT_INTERVAL_TICKS = 600;
    /**
     * Slack allowed between the observed run length and the requested one. Elapsed time is taken from
     * the last checkpoint, so a profile that ends between checkpoints would otherwise look short by up
     * to one interval and be failed on rounding rather than on substance.
     */
    private static final long DURATION_TOLERANCE_SECONDS = 120L;

    private final Path reportDir;
    private final long minutes;
    private final List<StressController.Checkpoint> checkpoints = new ArrayList<>();
    private StressController controller;
    /** Non-null once the end-of-run drain has been started; polled, never waited on, by tick(). */
    private CompletableFuture<Void> draining;
    private int ticks;
    private boolean started;
    private boolean finished;
    private Throwable failure;
    private long peakHeapBytes;
    /** Latest observed elapsed time, taken from a checkpoint rather than guessed. */
    private long lastElapsedSeconds;
    private final Path reportFile;

    public StressFixture(Path reportDir, long minutes) {
        this.reportDir = reportDir;
        this.minutes = minutes;
        this.reportFile = reportDir.resolve("stress-report.json");
    }

    /** Configuration read from the child-process system properties. */
    public static StressFixture fromSystemProperties(Path workspace) {
        String raw = System.getProperty("greatwall.stress.minutes");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("greatwall.stress.minutes is required for the stress profile");
        }
        long minutes;
        try {
            minutes = Long.parseLong(raw.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException("malformed greatwall.stress.minutes: " + raw, malformed);
        }
        if (minutes <= 0) throw new IllegalStateException("stress minutes must be positive, found " + minutes);
        String modeName = System.getProperty("greatwall.stress.mode", "MIXED_24H");
        if (!modeName.equals("MIXED_24H")) {
            throw new IllegalStateException("only MIXED_24H is certified for the release profile, found " + modeName);
        }
        Path reports = workspace.resolve("build").resolve("stress-reports")
                .resolve("run-" + System.currentTimeMillis());
        return new StressFixture(reports, minutes);
    }

    /** Begins the profile. Throws rather than silently skipping when the world is unsuitable. */
    public void start(MinecraftServer server) throws IOException {
        if (started) return;
        started = true;
        // The controller creates and owns its report directory, and deliberately refuses one that
        // already exists so a previous run's evidence can never be overwritten. Creating it here
        // defeated that guard and made the profile fail to start, so the parent is prepared instead
        // and the leaf is left for the controller.
        Files.createDirectories(Objects.requireNonNull(reportDir.getParent()));
        ServerLevel level = server.overworld();
        // Concurrency and seed are read here rather than hardcoded, because the driver's
        // -PgreatwallStressConcurrency / -PgreatwallStressSeed were reaching Gradle and then being
        // dropped: neither was forwarded to this process, so a request for concurrency 16 silently ran
        // at 8, and the seed recorded in the evidence was never the one used. Concurrency is the
        // parameter the profile's whole shape depends on, so a silently ignored value is the worse half.
        int concurrency = Integer.getInteger("greatwall.stress.concurrency", 8);
        if (!java.util.Set.of(1, 2, 8, 16).contains(concurrency)) {
            throw new IllegalStateException("greatwall.stress.concurrency must be 1, 2, 8 or 16, found "
                    + concurrency);
        }
        // The world seed is authoritative and cannot be overridden from here: it comes from
        // server.properties, which the smoke task writes. A mismatch is reported rather than papered
        // over, so evidence never claims a seed the world does not have.
        long requestedSeed = Long.getLong("greatwall.stress.seed", level.getSeed());
        if (requestedSeed != level.getSeed()) {
            throw new IllegalStateException("greatwall.stress.seed=" + requestedSeed
                    + " but the world seed is " + level.getSeed()
                    + "; the seed is set through server.properties (s1cSeed), not this property");
        }
        var config = new StressController.Config(level.getSeed(), StressController.Mode.MIXED_24H,
                TimeUnit.MINUTES.toSeconds(minutes), concurrency, List.of(), null, true);
        controller = new StressController(level, config, reportDir);
        controller.start().toCompletableFuture().join();
    }

    /**
     * Advances the profile. Returns true once the run has terminated (successfully or not), so the
     * caller can stop the server.
     *
     * <p>This runs on the server thread, from the end-of-tick event. It must therefore never wait for
     * the drain: chunk completions are delivered on this very thread, so blocking here would prevent
     * the work being waited for from ever completing. An earlier revision called {@code stop().get()}
     * with a 120s budget, which turned the end of every run into a single 120s tick and let the
     * watchdog kill the server at 60s. The drain is now started once and polled.
     */
    public boolean tick(MinecraftServer server) {
        if (!started || finished) return finished;
        if (failure != null) {
            finish(server);
            return true;
        }
        try {
            // A drain already in progress owns the end of the run; keep ticking so its completions can
            // be delivered, and only finish once it actually settles.
            if (draining != null) {
                if (!draining.isDone()) return false;
                if (draining.isCompletedExceptionally()) {
                    draining.join();
                }
                finish(server);
                return true;
            }
            controller.tick();
            ticks++;
            if (ticks % CHECKPOINT_INTERVAL_TICKS == 0 || ticks == 1) {
                var checkpoint = controller.sample().toCompletableFuture().get(60, TimeUnit.SECONDS);
                checkpoints.add(checkpoint);
                lastElapsedSeconds = checkpoint.elapsedSeconds();
                peakHeapBytes = Math.max(peakHeapBytes, checkpoint.usedHeapBytes());
            }
            if (lastElapsedSeconds >= TimeUnit.MINUTES.toSeconds(minutes)) {
                // Start the drain and return without blocking; the next ticks poll it to completion.
                draining = controller.stop().toCompletableFuture();
                return false;
            }
        } catch (Throwable error) {
            failure = error;
            finish(server);
            return true;
        }
        return false;
    }

    private void finish(MinecraftServer server) {
        if (finished) return;
        finished = true;
        long completed = checkpoints.isEmpty() ? -1L : checkpoints.getLast().completedChunks();
        // The profile is only passed when it actually ran for the requested duration.
        //
        // The controller stops itself as soon as its pending queue drains, and that queue is finite
        // (MAX_PENDING positions, polled without refill), so a long profile can end far short of its
        // window: a 24h request was on course to finish in about 4.5h. Judging only "did any chunk
        // complete" reported that as passed, which is an acceptance gate that certifies a duration it
        // never observed. The elapsed time is therefore part of the verdict, with a small tolerance so
        // a run that stops a tick or two early is not failed on rounding.
        long requestedSeconds = TimeUnit.MINUTES.toSeconds(minutes);
        boolean durationMet = lastElapsedSeconds >= requestedSeconds - DURATION_TOLERANCE_SECONDS;
        boolean passed = failure == null && completed > 0 && !checkpoints.isEmpty() && durationMet;
        if (!durationMet && failure == null) {
            failure = new IllegalStateException("profile ran " + lastElapsedSeconds + "s of the requested "
                    + requestedSeconds + "s; the workload queue drained early, so this run does not "
                    + "certify the requested duration");
        }
        try {
            writeReport(passed, completed, durationMet, requestedSeconds);
        } catch (IOException writeFailure) {
            failure = failure == null ? writeFailure : failure;
            passed = false;
        }
        if (!passed) {
            // A halted or failed profile must not look like a completed one.
            server.halt(false);
        }
    }

    private void writeReport(boolean passed, long completedChunks, boolean durationMet, long requestedSeconds)
            throws IOException {
        var text = new StringBuilder();
        text.append("{\n");
        text.append("  \"profile\": \"MIXED_24H\",\n");
        text.append("  \"requestedMinutes\": ").append(minutes).append(",\n");
        text.append("  \"requestedSeconds\": ").append(requestedSeconds).append(",\n");
        text.append("  \"durationMet\": ").append(durationMet).append(",\n");
        text.append("  \"observedSeconds\": ").append(lastElapsedSeconds).append(",\n");
        text.append("  \"completedChunks\": ").append(completedChunks).append(",\n");
        text.append("  \"peakHeapBytes\": ").append(peakHeapBytes).append(",\n");
        text.append("  \"checkpoints\": ").append(checkpoints.size()).append(",\n");
        text.append("  \"ticks\": ").append(ticks).append(",\n");
        text.append("  \"failure\": ")
                .append(failure == null ? "null" : '"' + String.valueOf(failure) + '"').append(",\n");
        text.append("  \"passed\": ").append(passed).append('\n');
        text.append("}\n");
        Files.writeString(reportFile, text.toString(), StandardCharsets.UTF_8);
    }

    /** The report path, for the driver to locate. */
    public Path reportFile() { return reportFile; }

    public boolean finished() { return finished; }
    public Throwable failure() { return failure; }

    @Override public void close() {
        if (controller != null) {
            try {
                controller.close();
            } catch (RuntimeException ignored) {
                // Closing during shutdown must not mask the recorded failure.
            }
        }
    }

    /** Guard used by the smoke entry point so a stress run cannot silently degrade to a normal run. */
    public static void requireProfile(MinecraftServer server, String expected) {
        String profile = System.getProperty("greatwall.smoke.profile", "vanilla");
        if (!expected.equals(profile)) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE,
                    "stress profile requires greatwall.smoke.profile=" + expected + ", found " + profile);
        }
        if (server.overworld() == null) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "stress profile requires an overworld");
        }
    }
}
