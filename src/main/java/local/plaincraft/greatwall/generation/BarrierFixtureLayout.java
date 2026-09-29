package local.plaincraft.greatwall.generation;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

/** Finite S0 laboratory footprint, unrelated to the future wall route. */
public final class BarrierFixtureLayout {
    public static final int WALK_Y = 112;
    public static final int MIN_Y = WALK_Y - 32;
    public static final int MAX_Y = WALK_Y + 6;
    private final int rounds;

    public BarrierFixtureLayout(int rounds) {
        if (rounds < 1 || rounds > 100) throw new IllegalArgumentException("S0 rounds must be 1..100");
        this.rounds = rounds;
    }
    public int rounds() { return rounds; }
    public List<ChunkPos> owners(int round) {
        if (round < 0 || round >= rounds) throw new IllegalArgumentException("Invalid fixture round");
        int x = 2000 + round % 10 * 8, z = 2000 + round / 10 * 8;
        return List.of(new ChunkPos(x, z), new ChunkPos(x + 1, z),
                new ChunkPos(x, z + 1), new ChunkPos(x + 1, z + 1));
    }
    public int round(ChunkPos pos) {
        int dx = pos.x - 2000, dz = pos.z - 2000;
        if (dx < 0 || dz < 0 || dx >= 80 || dz >= 80 || dx % 8 > 1 || dz % 8 > 1) return -1;
        int round = dz / 8 * 10 + dx / 8;
        return round < rounds ? round : -1;
    }
    public boolean owns(ChunkPos pos) { return round(pos) >= 0; }
    public List<ChunkPos> cell(ChunkPos owner) {
        List<ChunkPos> group = owners(round(owner));
        return switch (group.indexOf(owner)) {
            case 0 -> List.of(group.get(0), group.get(1));
            case 1 -> List.of(group.get(1), group.get(3));
            case 2 -> List.of(group.get(0), group.get(2), group.get(3));
            case 3 -> group;
            default -> throw new IllegalArgumentException("Not a fixture owner");
        };
    }
    public boolean featureSource(ChunkPos pos) { return owns(new ChunkPos(pos.x + 1, pos.z)); }
}
