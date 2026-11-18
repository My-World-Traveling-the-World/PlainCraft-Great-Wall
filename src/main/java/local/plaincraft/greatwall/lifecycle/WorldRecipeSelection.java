package local.plaincraft.greatwall.lifecycle;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallTypes.GenerationConfig;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import local.plaincraft.greatwall.persistence.WorldManifest;

/** 从永久凭证选择本次启动的配方，避免把创建到一半的旧世界当成新世界。 */
record WorldRecipeSelection(WorldManifest.Creation creation, GenerationConfig config, Optional<WorldRecipe> saved) {
    static WorldRecipeSelection open(WorldManifest manifest, boolean initialized, long seed,
            GenerationConfig newWorld, List<GenerationConfig> existing) throws IOException {
        var configs = new HashMap<String, GenerationConfig>();
        configs.put(WorldManifest.configHash(newWorld), newWorld);
        for (var candidate : existing) {
            // 所有版本共享同一份当前调参快照；旧配方支持不意味着允许改中途的地形参数。
            if (!candidate.parametersHash().equals(newWorld.parametersHash())) {
                throw new IllegalArgumentException("可读配方必须使用同一份参数快照");
            }
            configs.put(WorldManifest.configHash(candidate), candidate);
        }
        var token = manifest.authorize(initialized, seed, WorldManifest.configHash(newWorld),
                configs.keySet(), newWorld.parametersHash());
        return new WorldRecipeSelection(token, configs.get(token.configHash()), manifest.read());
    }
}
