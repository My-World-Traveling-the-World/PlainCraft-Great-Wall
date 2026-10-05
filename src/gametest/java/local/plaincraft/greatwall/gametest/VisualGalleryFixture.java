package local.plaincraft.greatwall.gametest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import local.plaincraft.greatwall.component.BeaconTower;
import local.plaincraft.greatwall.component.ComponentTemplate;
import local.plaincraft.greatwall.component.EndCap;
import local.plaincraft.greatwall.component.EnemyTower;
import local.plaincraft.greatwall.component.FortPlaceholder;
import local.plaincraft.greatwall.component.Gate;
import local.plaincraft.greatwall.component.Ramp;
import local.plaincraft.greatwall.component.WallSection;
import local.plaincraft.greatwall.component.WaterGate;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.decay.PaletteResolver;
import local.plaincraft.greatwall.decay.PaletteTypes.BiomeStation;
import local.plaincraft.greatwall.decay.PaletteTypes.Variant;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.ArcInterval;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.ComponentInput;
import local.plaincraft.greatwall.model.WallTypes.ComponentVoxels;
import local.plaincraft.greatwall.model.WallTypes.DecaySample;
import local.plaincraft.greatwall.model.WallTypes.DecayState;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.PaletteFamily;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.ProbeKind;
import local.plaincraft.greatwall.model.WallTypes.ProbeResult;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.SiteRecord;
import local.plaincraft.greatwall.model.WallTypes.SiteState;
import local.plaincraft.greatwall.model.WallTypes.Station;
import local.plaincraft.greatwall.model.WallTypes.WaterClass;
import local.plaincraft.greatwall.model.WallTypes.WaterEvidence;
import net.minecraft.server.level.ServerLevel;

/**
 * S4 visual gallery fixture: 128 cells (3 families x 4 decay x 8 templates plus the 32
 * extra red-sandstone cells), compiled with the production templates and the real S4 material
 * resolver. Every cell is inverse-rasterized in local coordinates (the same path production
 * uses), so no chunk is force-loaded. The gallery index is keyed by {@link Spec}, never by a
 * forged {@link SiteKind#WALL} identity.
 */
public final class VisualGalleryFixture {
    private static final String H = "a".repeat(64);
    private static final DecayField DECAY = new DecayField(0);
    private static final PaletteResolver RESOLVER = new PaletteResolver(0, PaletteResolver.defaults());
    private static final List<String> TEMPLATES = List.of(
            "WallSection", "EnemyTower", "BeaconTower", "Gate",
            "WaterGate", "EndCap", "Ramp", "Fort");
    private static final Map<String, SiteKind> KINDS = Map.of(
            "WallSection", SiteKind.WALL,
            "EnemyTower", SiteKind.ENEMY_TOWER,
            "BeaconTower", SiteKind.BEACON,
            "Gate", SiteKind.GATE,
            "WaterGate", SiteKind.WATER_GATE,
            "EndCap", SiteKind.END_CAP,
            "Ramp", SiteKind.RAMP,
            "Fort", SiteKind.FORT);

    public record Spec(int index, String templateId, Variant variant, DecayState decay,
                       Point3i origin, int headingDegrees, String expectedGeometryHash) {
        public Spec {
            if (!KINDS.containsKey(templateId)) {
                throw new IllegalArgumentException("unknown templateId " + templateId);
            }
            if (headingDegrees < 0 || headingDegrees > 359) {
                throw new IllegalArgumentException("heading must be 0..359");
            }
            local.plaincraft.greatwall.model.WallTypes.requireHash(expectedGeometryHash);
        }
    }

    public record CellResult(Spec spec, String blockHash, boolean supportOk, boolean routesOk,
                             List<String> errors) {
        public CellResult {
            errors = List.copyOf(errors);
        }
    }

    public VisualGalleryFixture() {
    }

    /** Fixed order: WallSection, EnemyTower, BeaconTower, Gate, WaterGate, EndCap, Ramp, Fort. */
    public List<Spec> specs(int baseY) {
        var result = new ArrayList<Spec>(128);
        int index = 0;
        // 3 families (STONE/DEEPSLATE/SANDSTONE) x 4 decay x 8 templates = 96, then the 32 red extras.
        for (int col = 0; col < 4; col++) {
            Variant variant = Variant.values()[col];
            for (int d = 0; d < DecayState.values().length; d++) {
                DecayState decay = DecayState.values()[d];
                for (int t = 0; t < TEMPLATES.size(); t++) {
                    String templateId = TEMPLATES.get(t);
                    var origin = new Point3i(col * 128, baseY, (d * TEMPLATES.size() + t) * 128);
                    String geometry = geometryHash(KINDS.get(templateId), decay, origin, 30);
                    result.add(new Spec(index++, templateId, variant, decay, origin, 30, geometry));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Compiles every cell and reports block-hash, support and route health. No force-load. */
    public CompletionStage<List<CellResult>> generate(ServerLevel level) {
        var results = new ArrayList<CellResult>(128);
        for (var spec : specs(0)) {
            results.add(cell(spec));
        }
        return CompletableFuture.completedFuture(List.copyOf(results));
    }

    /** Recompiles one spec and reports geometry, support and route defects. */
    public List<String> check(ServerLevel level, Spec spec) {
        var errors = new ArrayList<String>();
        try {
            var component = compile(spec);
            if (!spec.expectedGeometryHash().equals(geometryHash(component))) {
                errors.add("geometry hash changed for " + spec.templateId());
            }
            if (component.voxels().isEmpty()) {
                errors.add("component suppressed or empty: " + spec.templateId());
            } else if (!routesOk(component)) {
                errors.add("required route escapes bounds: " + spec.templateId());
            }
        } catch (RuntimeException failure) {
            errors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return List.copyOf(errors);
    }

    /** Package-private compile for PaletteGameTests; same production path as generate/check. */
    static ComponentVoxels compile(Spec spec) {
        var kind = KINDS.get(spec.templateId());
        return template(kind, DECAY, RESOLVER)
                .compile(input(kind, spec.variant(), spec.decay(), spec.origin(), spec.headingDegrees()));
    }

    private static CellResult cell(Spec spec) {
        var errors = new ArrayList<String>();
        boolean supportOk = false;
        boolean routesOk = false;
        String blockHash = null;
        try {
            var component = compile(spec);
            if (!spec.expectedGeometryHash().equals(geometryHash(component))) {
                errors.add("geometry hash changed for " + spec.templateId());
            }
            supportOk = !component.voxels().isEmpty();
            routesOk = supportOk && routesOk(component);
            blockHash = blockHash(component);
        } catch (RuntimeException failure) {
            errors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return new CellResult(spec, blockHash, supportOk, routesOk, errors);
    }

    /** Route points must exist within the compiled component bounds (deep reachability is covered by ComponentGameTests). */
    private static boolean routesOk(ComponentVoxels component) {
        if (component.requiredRoutes().isEmpty()) return true;
        var bounds = component.bounds();
        for (var route : component.requiredRoutes()) {
            for (var p : route) {
                if (p.x() < bounds.minX() || p.x() >= bounds.maxXExclusive()
                        || p.y() < bounds.minY() || p.y() >= bounds.maxYExclusive()
                        || p.z() < bounds.minZ() || p.z() >= bounds.maxZExclusive()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ComponentTemplate template(SiteKind kind, DecayField decay, PaletteBinding palette) {
        return switch (kind) {
            case WALL -> new WallSection(decay, palette);
            case ENEMY_TOWER -> new EnemyTower(decay, palette);
            case BEACON -> new BeaconTower(decay, palette);
            case GATE -> new Gate(decay, palette);
            case WATER_GATE -> new WaterGate(decay, palette);
            case END_CAP -> new EndCap(decay, palette);
            case RAMP -> new Ramp(decay, palette);
            case FORT -> new FortPlaceholder(decay, palette);
        };
    }

    private static Bounds localBounds(SiteKind kind) {
        return switch (kind) {
            case WALL -> new Bounds(-32, -32, -5, 32, 3, 5);
            case ENEMY_TOWER -> new Bounds(-5, -33, -5, 6, 8, 6);
            case BEACON -> new Bounds(-5, -32, -5, 5, 7, 5);
            case GATE -> new Bounds(-30, -33, -15, 30, 4, 15);
            case WATER_GATE -> new Bounds(-40, -32, -6, 40, 3, 6);
            case END_CAP, RAMP -> new Bounds(-30, -33, -15, 30, 3, 15);
            case FORT -> new Bounds(-24, -32, -24, 24, 8, 24);
        };
    }

    private static ComponentInput input(SiteKind kind, Variant variant, DecayState state,
                                        Point3i origin, int heading) {
        double radians = heading * StrictMath.PI / 180;
        var frame = new Frame(new Point2q(origin.x() * 256L, origin.z() * 256L),
                (int) StrictMath.round(StrictMath.cos(radians) * (1L << 30)),
                (int) StrictMath.round(StrictMath.sin(radians) * (1L << 30)), 84, false);
        var local = localBounds(kind);
        var worldBounds = Rasterizer.worldBounds(frame, local);
        var site = new SiteRecord(new SiteId("gallery", kind, 1), 1024L * 256, frame,
                worldBounds, SiteState.PLANNED, Optional.empty(), Optional.empty());
        var supports = new HashMap<Point3i, ProbeResult>();
        for (int z = worldBounds.minZ(); z < worldBounds.maxZExclusive(); z++) {
            for (int x = worldBounds.minX(); x < worldBounds.maxXExclusive(); x++) {
                supports.put(new Point3i(x, 84, z), new ProbeResult(ProbeKind.SUPPORTED, 64,
                        OptionalInt.empty(), OptionalInt.empty(), 0, H));
            }
        }
        var waters = new ArrayList<WaterEvidence>();
        if (kind == SiteKind.WATER_GATE) {
            // Narrow 49-wide crossing; every column is a supported water bed so the gate compiles
            // at any heading (the crossing span, not the support map, drives the arch layout).
            for (var entry : supports.entrySet()) {
                entry.setValue(new ProbeResult(ProbeKind.WATER, 56, OptionalInt.of(65), OptionalInt.of(56), 8, H));
            }
            var pier = new Point3i(origin.x(), 56, origin.z());
            supports.put(new Point3i(origin.x(), 84, origin.z()),
                    new ProbeResult(ProbeKind.WATER, 56, OptionalInt.of(65), OptionalInt.of(56), 8, H));
            waters.add(new WaterEvidence(
                    new ArcInterval((1024 - 24) * 256L, (1024 + 25) * 256L),
                    WaterClass.NARROW, 49L * 256, 49L * 256, 65,
                    List.of(new Point3i(origin.x() - 25, 64, origin.z()),
                            new Point3i(origin.x() + 25, 64, origin.z())),
                    List.of(pier), H));
        }
        int amount = state == DecayState.INTACT ? 0 : state == DecayState.PARTIAL ? 24000 : 54000;
        int height = state == DecayState.INTACT ? 20 : state == DecayState.PARTIAL ? 15 : 2;
        return new ComponentInput(site,
                List.of(new Station(0, Projection.local(frame, -1024L * 256, 0), 84, 0),
                        new Station(2048L * 256, Projection.local(frame, 1024L * 256, 0), 84, 0)),
                supports, new DecaySample(state, amount, height), PaletteFamily.STONE,
                variant == Variant.RED_SANDSTONE, waters,
                List.of(new BiomeStation(0, "minecraft:plains", variant),
                        new BiomeStation(2048L * 256, "minecraft:plains", variant)));
    }

    /** Occupancy-only fingerprint: identical across material families, sensitive to geometry drift. */
    private static String geometryHash(ComponentVoxels component) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            for (var voxel : component.voxels()) {
                out.writeInt(voxel.pos().x());
                out.writeInt(voxel.pos().y());
                out.writeInt(voxel.pos().z());
                out.writeInt(voxel.kind().ordinal());
                out.writeInt(voxel.part().ordinal());
                out.writeBoolean(voxel.required());
                out.writeBoolean(voxel.walkable());
            }
            for (var route : component.requiredRoutes()) {
                out.writeInt(route.size());
                for (var p : route) {
                    out.writeInt(p.x());
                    out.writeInt(p.y());
                    out.writeInt(p.z());
                }
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return DeterministicHash.sha256(bytes.toByteArray());
    }

    private static String geometryHash(SiteKind kind, DecayState state, Point3i origin, int heading) {
        // Occupancy is palette-independent (kind/part/required/walkable); the S1 fixture palette is
        // used to prove the S4 resolver does not drift the geometry fingerprint.
        return geometryHash(template(kind, DECAY, ComponentFixtures.PALETTE)
                .compile(input(kind, Variant.STONE, state, origin, heading)));
    }

    private static String blockHash(ComponentVoxels component) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            for (var voxel : component.voxels()) {
                out.writeUTF(voxel.state().id());
                var properties = new TreeMap<>(voxel.state().properties());
                out.writeInt(properties.size());
                for (var entry : properties.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return DeterministicHash.sha256(bytes.toByteArray());
    }
}
