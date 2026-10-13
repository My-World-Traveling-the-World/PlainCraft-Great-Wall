package local.plaincraft.greatwall.command;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.diagnostics.DebugPayload;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.AtomicFiles;
import local.plaincraft.greatwall.persistence.HistoryJournal;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import local.plaincraft.greatwall.persistence.WorldManifest;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Command/diagnostics S5 tests. These assert the range/path half against
 * the pure-IO confinement primitives DiagnosticExporter reuses, and the payload half against DebugPayload
 * directly (Minecraft is on the test classpath — see diagnostics/DebugPayloadTest).
 */
class DiagnosticsTest {
    private static final UUID SESSION = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
    private static final Point3i POINT = new Point3i(-1_000_000, 5999, 1_000_000);

    @TempDir Path temp;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void rangeAndPathTraversalAreRejected() throws Exception {
        Path exportsRoot = temp.resolve("greatwall-exports");
        Path other = temp.resolve("elsewhere");
        java.nio.file.Files.createDirectories(exportsRoot);

        // (1) The exporter's filename is the job UUID; a user-supplied path is never accepted.
        //     Any relative path that escapes the confined root must be rejected.
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(exportsRoot, "../escape.json"),
                "a path escaping the export root must be rejected");
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(exportsRoot, "a/../../escape.json"),
                "a normalized path escaping the export root must be rejected");
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(exportsRoot, "..\\escape.json"),
                "a backslash path escaping the export root must be rejected");
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(exportsRoot, ""),
                "a blank relative path must be rejected");
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(exportsRoot, null),
                "a null relative path must be rejected");
        assertThrows(IOException.class,
                () -> AtomicFiles.resolveUnder(exportsRoot, other.toAbsolutePath().toString()),
                "an absolute injected path must be rejected");

        // (2) An injected path must never reach the filesystem outside the export root.
        try {
            AtomicFiles.writeImmutable(exportsRoot, "../escaped.json",
                    "x".getBytes(StandardCharsets.UTF_8));
            fail("writeImmutable must reject an escaping relative path before any APPLY");
        } catch (IOException expected) {
            // confinement holds
        }
        assertFalse(other.resolve("escaped.json").toFile().exists(),
                "no file may be written outside the confined export root");

        // (3) Negative indices are rejected at the replay/boundary layer (DiagnosticExporter's
        //     >64-segment range clamp lives in the not-yet-present class; the negative-index
        //     contract is asserted on the durable log's own replay bound, which must be called
        //     from its IO-owner thread).
        var recipe = freeze(temp.resolve("world"));
        var io = Executors.newSingleThreadExecutor();
        var journal = new HistoryJournal(temp.resolve("world"), recipe.instance(), io);
        try {
            await(journal.open());
            var negative = assertThrows(ExecutionException.class,
                    () -> io.submit(() -> journal.replay(HistoryTypes.Kind.BEACON_CREDIT.name(), -1, 1))
                            .get(30, TimeUnit.SECONDS),
                    "a negative replay index must be rejected on the IO owner");
            assertEquals(IllegalArgumentException.class, negative.getCause().getClass());
            var zero = assertThrows(ExecutionException.class,
                    () -> io.submit(() -> journal.replay(HistoryTypes.Kind.BEACON_CREDIT.name(), 0, 0))
                            .get(30, TimeUnit.SECONDS),
                    "a non-positive replay limit must be rejected on the IO owner");
            assertEquals(IllegalArgumentException.class, zero.getCause().getClass());
        } finally {
            journal.close();
            assertTrue(io.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void payloadUsesThreeIntsAndBounds() {
        var value = new DebugPayload(SESSION, 123L, 0, 1,
                List.of(new DebugPayload.Line(POINT, new Point3i(Integer.MAX_VALUE, 6000, Integer.MIN_VALUE),
                        0xff00ff00)),
                List.of(new DebugPayload.Box(new Bounds(-16, 5999, -16, 16, 6001, 16), 0x8000ffff)),
                List.of(new DebugPayload.Label(POINT, "edge")));
        var buffer = buffer();
        try {
            DebugPayload.CODEC.encode(buffer, value);
            // y = 5999 is a three-int coordinate, not a packed Y: it survives the round-trip verbatim.
            assertEquals(5999, buffer.getInt(40));
            assertEquals(POINT.y(), buffer.getInt(40));
            assertEquals(value, DebugPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }

        // Count bounds are enforced before any huge array is allocated.
        assertThrows(IllegalArgumentException.class,
                () -> new DebugPayload(SESSION, 0, 0, 1,
                        java.util.Collections.nCopies(257, new DebugPayload.Line(POINT, POINT, 0)),
                        List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugPayload(SESSION, 0, 0, 1,
                        java.util.Collections.nCopies(Integer.MAX_VALUE, new DebugPayload.Line(POINT, POINT, 0)),
                        List.of(), List.of()));

        // The fixed limits keep every legal payload under the 32 KiB wire bound: the maximum page
        // (256 lines + 64 boxes + 32 labels of 128 UTF-8 bytes) encodes to exactly 13612 bytes.
        var maxLabel = new DebugPayload.Label(POINT, "x".repeat(128));
        var maxPage = new DebugPayload(SESSION, 0, 0, 1,
                java.util.Collections.nCopies(256, new DebugPayload.Line(POINT, POINT, 0)),
                java.util.Collections.nCopies(64, new DebugPayload.Box(new Bounds(0, 0, 0, 1, 1, 1), 0)),
                java.util.Collections.nCopies(32, maxLabel));
        var maxBuffer = buffer();
        try {
            DebugPayload.CODEC.encode(maxBuffer, maxPage);
            assertEquals(13_612, maxBuffer.writerIndex());
            assertTrue(maxBuffer.writerIndex() <= 32_768,
                    "the bounded count limits must keep the wire page under 32 KiB");
        } finally {
            maxBuffer.release();
        }

        // Decode rejects a huge declared count before allocating the backing list.
        for (int count : new int[] {-1, 257, Integer.MAX_VALUE}) {
            var wire = buffer();
            try {
                wire.writeLong(SESSION.getMostSignificantBits());
                wire.writeLong(SESSION.getLeastSignificantBits());
                wire.writeLong(123L);
                wire.writeInt(0);
                wire.writeInt(1);
                wire.writeInt(count);
                assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(wire),
                        "a declared line count of " + count + " must be rejected before allocation");
            } finally {
                wire.release();
            }
        }
    }

    private WorldRecipe freeze(Path root) throws Exception {
        var sample = PlanningFixtures.recipe(1);
        var manifest = new WorldManifest(root.resolve("manifest"));
        UUID world = manifest.authorize(false, sample.seed(), WorldManifest.configHash(sample.config()),
                sample.config().parametersHash()).instance();
        var provisional = new WorldRecipe(world, sample.wallId(), sample.seed(), sample.dimension(),
                sample.frozenSpawn(), sample.axis(), sample.bounds(), sample.config(), sample.terrainHash(),
                sample.paletteHash(), sample.templatesHash(), sample.recipeHash());
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(provisional));
        var recipe = new WorldRecipe(world, hash, sample.seed(), sample.dimension(), sample.frozenSpawn(),
                sample.axis(), sample.bounds(), sample.config(), sample.terrainHash(),
                sample.paletteHash(), sample.templatesHash(), hash);
        manifest.freeze(recipe);
        return recipe;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
