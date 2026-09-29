package local.plaincraft.greatwall.compat.c2me;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ProbeMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return true; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() {
        if (!FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-system")) return List.of();
        String version = FabricLoader.getInstance().getModContainer("c2me-rewrites-chunk-system")
                .orElseThrow().getMetadata().getVersion().getFriendlyString();
        if (!version.equals("0.4.0-alpha.0.27+1.21.1")) {
            throw new IllegalStateException("Uncertified C2ME adapter version: " + version);
        }
        return List.of("C2meGateMixin", "C2meFeatureSkipMixin");
    }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
