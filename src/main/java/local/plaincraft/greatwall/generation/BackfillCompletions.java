package local.plaincraft.greatwall.generation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;

/** 内存结案与实际写完的计划绑定；磁盘 DONE 列表仍只存坐标，原格式不变。 */
final class BackfillCompletions {
    private final ConcurrentHashMap<ChunkKey, String> hashes = new ConcurrentHashMap<>();

    boolean matches(ChunkKey key, String planHash) {
        return planHash.equals(hashes.get(key));
    }

    void record(ChunkKey key, String planHash) {
        hashes.put(key, planHash);
    }

    boolean forget(ChunkKey key) {
        return hashes.remove(key) != null;
    }

    boolean contains(ChunkKey key) {
        return hashes.containsKey(key);
    }

    Set<ChunkKey> keys() {
        return hashes.keySet();
    }

    int size() {
        return hashes.size();
    }
}
