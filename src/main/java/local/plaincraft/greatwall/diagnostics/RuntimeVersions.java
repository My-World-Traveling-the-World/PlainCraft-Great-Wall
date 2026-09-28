package local.plaincraft.greatwall.diagnostics;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class RuntimeVersions {
    private RuntimeVersions() {
    }

    public record Version(String id, String version) {
    }

    public static List<Version> capture() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(container -> new Version(container.getMetadata().getId(),
                        container.getMetadata().getVersion().getFriendlyString()))
                .sorted(java.util.Comparator.comparing(Version::id))
                .toList();
    }
}

