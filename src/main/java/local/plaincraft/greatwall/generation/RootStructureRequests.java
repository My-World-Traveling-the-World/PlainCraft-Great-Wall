/* 文件职责：在一批候选内合并同阶段区块取证，不把其它候选的区块混入本次判定。 */
package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.world.level.ChunkPos;

final class RootStructureRequests<T> {
    private final Map<ChunkPos, CompletableFuture<T>> requested = new HashMap<>();

    CompletableFuture<Map<ChunkPos, T>> get(List<ChunkPos> positions,
            Function<List<ChunkPos>, Map<ChunkPos, CompletableFuture<T>>> request) {
        var missing = new ArrayList<ChunkPos>();
        for (var pos : positions) if (!requested.containsKey(pos)) missing.add(pos);
        if (!missing.isEmpty()) requested.putAll(request.apply(missing));
        var futures = positions.stream().map(requested::get).toList();
        // 只等自己的坐标：共享区块早到，不能被首个租借者独有的慢区块拖延。
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            var result = new HashMap<ChunkPos, T>();
            for (int i = 0; i < positions.size(); i++) result.put(positions.get(i), futures.get(i).resultNow());
            return Map.copyOf(result);
        });
    }
}
