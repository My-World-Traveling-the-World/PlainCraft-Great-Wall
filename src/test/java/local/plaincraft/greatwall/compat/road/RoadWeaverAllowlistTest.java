package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the RoadWeaver interop allowlist against the release documentation.
 *
 * <p>The allowlist is a runtime ABI gate: {@code RoadWeaverAdapter} refuses to initialise unless the
 * loaded jar's SHA-256 appears in {@code roadweaver-interop.sha256}. The compatibility document
 * therefore describes an artifact the mod must actually be able to start with. These two drifted
 * apart once already — the document named an older build whose SHA the allowlist did not admit — so
 * the relationship is asserted here rather than left to review.
 */
class RoadWeaverAllowlistTest {
    private static final Path ALLOWLIST = Path.of(
            "src/main/resources/plaincraft_great_wall/roadweaver-interop.sha256");
    private static final Path COMPATIBILITY = Path.of("docs/release/compatibility.json");

    @Test void allowlistCarriesExactlyOneReviewedDigest() throws IOException {
        var digests = digests();
        assertEquals(1, digests.size(),
                "the allowlist should admit exactly the reviewed build, found " + digests);
        assertTrue(digests.getFirst().matches("[0-9a-f]{64}"),
                "digests must be lowercase hex SHA-256: " + digests.getFirst());
    }

    @Test void compatibilityDocumentNamesTheSameBuildTheAllowlistAdmits() throws IOException {
        var admitted = digests().getFirst();
        var document = new String(Files.readAllBytes(COMPATIBILITY), StandardCharsets.UTF_8);
        assertTrue(document.contains(admitted),
                "docs/release/compatibility.json must document the same RoadWeaver SHA the runtime "
                        + "allowlist admits (" + admitted + "); otherwise it describes a build the mod "
                        + "would refuse to start with");
    }

    /** Parses the allowlist, ignoring comments and blank lines. */
    private static List<String> digests() throws IOException {
        assertTrue(Files.isRegularFile(ALLOWLIST), "allowlist must exist at " + ALLOWLIST);
        var lines = Files.readAllLines(ALLOWLIST, StandardCharsets.UTF_8);
        return lines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
