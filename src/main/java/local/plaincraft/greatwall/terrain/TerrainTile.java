package local.plaincraft.greatwall.terrain;

import java.util.List;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import java.nio.charset.StandardCharsets;

/** An immutable, bounded T0 window; coordinates are three ints, never packed BlockPos. */
public final class TerrainTile {
    public record Key(String dimension, int chunkX, int chunkZ, int minY, int maxY) {
        public Key {
            if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || maxY < minY || (long) maxY - minY > 127) {
                throw new IllegalArgumentException("Invalid bounded terrain window");
            }
        }
        public int volume() { return (maxY - minY + 1) * 256; }
        public String identity() { return dimension + ":" + chunkX + ":" + chunkZ + ":" + minY + ":" + maxY; }
    }

    private final Key key;
    private final String recipe;
    private final List<String> palette;
    private final int[] states;
    private final String hash;

    public TerrainTile(Key key, String recipe, List<String> palette, int[] states) {
        if (recipe == null || !recipe.matches("[0-9a-f]{64}") || states.length != key.volume()
                || palette.isEmpty() || palette.size() > key.volume()) {
            throw new IllegalArgumentException("Invalid T0 payload");
        }
        this.key = key;
        this.recipe = recipe;
        this.palette = List.copyOf(palette);
        this.states = states.clone();
        var canonical = new StringBuilder("T0-v1\n").append(recipe).append('\n').append(key.identity()).append('\n');
        for (int state : this.states) {
            if (state < 0 || state >= palette.size()) throw new IllegalArgumentException("Invalid palette index");
            canonical.append(this.palette.get(state)).append('\n');
        }
        hash = DeterministicHash.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
    public Key key() { return key; }
    public String recipe() { return recipe; }
    public String hash() { return hash; }
    public List<String> palette() { return palette; }
    public int[] copyStates() { return states.clone(); }
    public String state(int localX, int y, int localZ) {
        if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16 || y < key.minY() || y > key.maxY()) {
            throw new IllegalArgumentException("Outside captured T0");
        }
        return palette.get(states[((y - key.minY()) * 16 + localZ) * 16 + localX]);
    }
}
