package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.decay.SupportGraph;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/**
 * The frozen 48x48 fort: foundation, perimeter ring, main passage, barbican,
 * four corner towers, ramps, barracks, armoury, stable, well and sockets.
 * Gameplay entities are emitted as data only, never created here.
 */
public final class Fort implements ComponentTemplate {
    private static final int HALF = 24;
    private static final int SOCKET_INSET = -5;

    private final DecayField decay;
    private final PaletteBinding palette;
    private final BeaconTower beacon;
    private final EnemyTower enemy;

    public Fort(DecayField decay, PaletteBinding palette, BeaconTower beacon, EnemyTower enemy) {
        this.decay = Objects.requireNonNull(decay, "decay");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.beacon = Objects.requireNonNull(beacon, "beacon");
        this.enemy = Objects.requireNonNull(enemy, "enemy");
    }

    @Override public Bounds localBounds(ComponentInput input) {
        Objects.requireNonNull(input);
        return new Bounds(-HALF, -32, -HALF, HALF, 8, HALF);
    }

    @Override public ComponentVoxels compile(ComponentInput input) {
        return compileWithSockets(input, List.of());
    }

    public ComponentVoxels compileWithSockets(ComponentInput input, List<SiteRecord> absorbed) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(absorbed, "absorbed");
        var sortedAbsorbed = new ArrayList<>(absorbed);
        sortedAbsorbed.sort(Comparator.comparing((SiteRecord record) -> record.id().kind().name())
                .thenComparingLong(record -> record.id().ordinal()));
        if (sortedAbsorbed.size() > 2) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "a fort absorbs at most two sites");
        }
        for (SiteRecord record : sortedAbsorbed) {
            if (!record.id().wallId().equals(input.site().id().wallId())) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE, "absorbed site belongs to another wall");
            }
        }
        boolean root = input.site().id().ordinal() == 0;
        if (root) {
            input = new ComponentInput(input.site(), input.stations(), input.supports(),
                    new DecaySample(DecayState.INTACT, 0, 20), input.family(), input.redSandstone(),
                    input.waters(), input.materials(), input.entranceEvidence());
        }
        var entrance = Ramp.entrance(input, -2, -HALF, 2, HALF);
        int groundFeet = entrance.map(GroundEntranceContract.Certificate::lowerFeetY).orElse(Integer.MIN_VALUE);
        if (groundFeet == Integer.MIN_VALUE) {
            if (root) throw new WallFailure(FailureCode.ROOT_NOT_FOUND, "fort0 ground road has no supported entrance");
            return ComponentAssembly.empty(input.site().id(), input.site().bounds());
        }
        int walkY = input.site().frame().walkY();
        int lower = groundFeet - walkY;
        int height = -lower;
        int g = walkY - 20;
        // Whole-footprint support check: any protected or over-height column suppresses the fort.
        if (!footprintSupported(input, walkY, g)) {
            return ComponentAssembly.empty(input.site().id(), input.site().bounds());
        }
        var main = new ComponentAssembly(input, localBounds(input), decay, palette);
        // Foundation and courtyard floor.
        main.foundation(-HALF, -HALF, HALF, HALF, lower - 1);
        main.floor(-HALF, -HALF, HALF, HALF, lower, Part.FLOOR, "north");
        var shell = new ComponentAssembly(input, localBounds(input), decay, palette);
        // Perimeter: 2-thick skin plus inner 2-wide support strip forming a 4-wide ring walk.
        for (int z = -HALF; z < HALF; z++) for (int x = -HALF; x < HALF; x++) {
            boolean outer = x < -22 || x >= 22 || z < -22 || z >= 22;
            boolean inner = x < -20 || x >= 20 || z < -20 || z >= 20;
            if (outer) {
                shell.foundation(x, z, x + 1, z + 1, -1);
                shell.box(x, -1, z, x + 1, 2, z + 1, Part.SKIN, true);
            } else if (inner) {
                shell.box(x, -1, z, x + 1, 1, z + 1, Part.CORE, true);
            }
        }
        // Six-wide crossing openings on both X sides.
        for (int x : new int[] {-HALF, 20}) {
            shell.box(x, -1, -3, x + 4, 2, 3, Part.AIR, true);
        }
        // Main passage and gate arch.
        shell.box(-2, lower, -HALF, 2, lower + 5, HALF, Part.AIR, true);
        shell.box(-2, lower + 5, -HALF, 2, lower + 7, HALF, Part.ARCH, true);
        // South/north gate houses.
        for (int zSide : new int[] {-HALF, 18}) {
            shell.box(-6, lower, zSide, 6, lower + 5, zSide + 6, Part.SKIN, true);
            shell.floor(-6, zSide, 6, zSide + 6, -1, Part.FLOOR, "north");
        }
        // Barbican.
        shell.box(-10, lower, 8, 10, lower + 8, 22, Part.SKIN, true);
        shell.box(-2, lower, 8, 2, lower + 5, 22, Part.AIR, true);
        // Corner towers: 6x6 shells with roofs.
        for (int cx : new int[] {-HALF, 18}) for (int cz : new int[] {-HALF, 18}) {
            shell.box(cx, -1, cz, cx + 6, 4, cz + 6, Part.SKIN, true);
            shell.floor(cx, cz, cx + 6, cz + 6, 5, Part.ROOF, "north");
            shell.box(cx, 5, cz, cx + 6, 7, cz + 6, Part.PARAPET, true);
            int innerX = cx < 0 ? cx + 6 : cx - 2;
            int innerZ = cz < 0 ? cz + 6 : cz - 2;
            if (cx < 0) shell.box(cx + 6, 0, cz + 2, cx + 8, 3, cz + 4, Part.AIR, true);
            else shell.box(cx - 2, 0, cz + 2, cx, 3, cz + 4, Part.AIR, true);
            if (cz < 0) shell.box(cx + 2, 0, cz + 6, cx + 4, 3, cz + 8, Part.AIR, true);
            else shell.box(cx + 2, 0, cz - 2, cx + 4, 3, cz, Part.AIR, true);
        }
        // Climb ramps on both sides.
        int stairStart = Math.max(-19, 10 - height);
        int stairEnd = stairStart + Math.max(1, height);
        for (int side : new int[] {-20, 17}) {
            for (int j = 0; j < Math.max(1, height); j++) {
                int feet = lower + j + 1;
                shell.foundation(side, stairStart + j, side + 3, stairStart + j + 1, feet - 1);
                shell.floor(side, stairStart + j, side + 3, stairStart + j + 1, feet, Part.STAIR,
                        shell.facing(0, 1));
            }
            int lo = side < 0 ? -HALF : 17;
            int hi = side < 0 ? -17 : HALF;
            shell.foundation(lo, stairEnd, hi, stairEnd + 3, -1);
            shell.floor(lo, stairEnd, hi, stairEnd + 3, 0, Part.FLOOR, "north");
        }
        // Barracks.
        shell.box(-16, lower, -12, -6, lower + 4, -4, Part.SKIN, true);
        shell.box(-16, lower, -12, -6, lower + 3, -4, Part.AIR, true);
        shell.box(-10, lower, -4, -8, lower + 2, -4, Part.AIR, true);
        shell.box(-16, lower + 4, -12, -6, lower + 5, -4, Part.ROOF, true);
        // Armoury.
        shell.box(8, lower, -12, 16, lower + 4, -4, Part.SKIN, true);
        shell.box(8, lower, -12, 16, lower + 3, -4, Part.AIR, true);
        shell.box(10, lower, -4, 12, lower + 2, -4, Part.AIR, true);
        shell.box(8, lower + 4, -12, 16, lower + 5, -4, Part.ROOF, true);
        // Stable.
        shell.box(-16, lower, 0, -8, lower + 4, 6, Part.SKIN, true);
        shell.box(-16, lower, 0, -8, lower + 3, 6, Part.AIR, true);
        shell.box(-8, lower, 1, -8, lower + 2, 5, Part.AIR, true);
        // Well.
        shell.box(7, lower, 1, 10, lower, 4, Part.SKIN, true);
        shell.box(8, lower - 1, 2, 9, lower - 1, 3, Part.WATER, false);
        // Sockets: NW and NE 11x11 reservations inside the footprint.
        List<Point2i> sockets = new ArrayList<>();
        for (SiteRecord record : sortedAbsorbed) {
            if (record.id().kind() != SiteKind.BEACON && record.id().kind() != SiteKind.ENEMY_TOWER) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE,
                        "fort sockets only absorb beacon/enemy tower: " + record.id().kind());
            }
            sockets.add(new Point2i(record.id().kind() == SiteKind.BEACON ? -12 : 12, -17));
        }
        var result = ComponentComposer.merge(input, List.of(main.finish(), shell.finish()));
        // Emit block entities, spawn slots and routes as data.
        var entities = new ArrayList<>(result.blockEntities());
        var spawns = new ArrayList<>(result.spawnSlots());
        entities.add(new BlockEntityIntent(new Point3i(-12, g, -9), "minecraft:chest",
                input.site().id(), 0, Map.of("loot", "fort-loot-0")));
        entities.add(new BlockEntityIntent(new Point3i(-8, g, -10), "minecraft:chest",
                input.site().id(), 1, Map.of("loot", "fort-loot-1")));
        entities.add(new BlockEntityIntent(new Point3i(13, g, -9), "minecraft:chest",
                input.site().id(), 2, Map.of("loot", "fort-loot-2")));
        if (root || result.realizedState() == DecayState.INTACT || result.realizedState() == DecayState.PARTIAL) {
            spawns.add(new SpawnSlot(input.site().id(), 0, new Point3i(-12, g, -9), "minecraft:zombie"));
            spawns.add(new SpawnSlot(input.site().id(), 1, new Point3i(4, g + 1, -21), "minecraft:zombie"));
        }
        var merged = ComponentAssembly.hashed(result.id(), result.bounds(), result.voxels(), entities,
                spawns, result.requiredRoutes());
        if (root && merged.voxels().isEmpty()) {
            throw new WallFailure(FailureCode.ROOT_NOT_FOUND, "fort0 footprint is not fully supported");
        }
        return merged;
    }

    private static boolean footprintSupported(ComponentInput input, int walkY, int groundFeet) {
        var supports = input.supports();
        var frame = input.site().frame();
        var world = Rasterizer.worldBounds(frame, new Bounds(-HALF,-32,-HALF,HALF,8,HALF));
        int columns = 0;
        for (int z = world.minZ(); z < world.maxZExclusive(); z++)
                for (int x = world.minX(); x < world.maxXExclusive(); x++) {
            var local = Rasterizer.localCell(frame, new Point3i(x,walkY,z));
            if (local.x() < -HALF || local.x() >= HALF || local.z() < -HALF || local.z() >= HALF) continue;
            var probe = supports.get(new Point3i(x, walkY, z));
            if (probe == null) return false;
            if (probe.kind() != ProbeKind.SUPPORTED && probe.kind() != ProbeKind.WATER) return false;
            if (probe.drop() > 12) return false;
            if (probe.groundTopY() >= walkY) return false;
            columns++;
        }
        return columns > 0;
    }

    private record Point2i(int x, int z) {}
}
