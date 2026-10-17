package local.plaincraft.greatwall.component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.decay.SupportGraph;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;

/** Invocation-owned template buffer; inverse rasterizes full components before owner clipping. */
final class ComponentAssembly {
    static final Comparator<Point3i> ORDER = Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    /**
     * The air voxel, shared.
     *
     * <p>{@code BlockSpec} validates its resource key and copies the property map, so a fresh
     * {@code new BlockSpec("minecraft:air", Map.of())} per air voxel costs ~1 us and ~1.7 kB of garbage. A
     * wall section emits over a thousand of them and {@code BlockSpec} is an immutable record, so one
     * instance is indistinguishable.
     */
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private final ComponentInput input;
    private final Bounds localBounds;
    private final DecayField decay;
    private final PaletteBinding palette;
    private Map<Point3i,Cell> cells = new HashMap<>();
    private long cellBytes;
    private boolean finished;
    private final List<List<Point3i>> routes = new ArrayList<>();
    private final Map<Point3i,BlockEntityIntent> entities = new HashMap<>();
    private final List<SpawnSlot> spawns = new ArrayList<>();
    private record LocalSpan(Bounds load,Bounds left,Bounds right) {}
    private final List<LocalSpan> spans=new ArrayList<>();
    private boolean conflictingRequiredFloor;

    ComponentAssembly(ComponentInput input, Bounds localBounds, DecayField decay, PaletteBinding palette) {
        this.input = Objects.requireNonNull(input); this.localBounds = Objects.requireNonNull(localBounds);
        this.decay = Objects.requireNonNull(decay); this.palette = Objects.requireNonNull(palette);
        CompiledAllocation.charge(512);
    }

    void put(int x, int y, int z, Part part, boolean required, boolean walkable) {
        put(x, y, z, part, required, walkable, Map.of());
    }

    void put(int x, int y, int z, Part part, boolean required, boolean walkable, Map<String,String> properties) {
        if(finished)throw new IllegalStateException("assembly already finished");
        Point3i p = new Point3i(x,y,z);
        if (!contains(localBounds, p)) throw new IllegalArgumentException("template escaped frozen bounds: " + p);
        Cell previous = cells.get(p);
        if (previous != null && previous.required() && previous.walkable() && part == Part.FOUNDATION) return;
        if (previous != null && previous.part() == Part.AIR && previous.required() && part != Part.AIR) {
            if (required && walkable) conflictingRequiredFloor = true;
            return;
        }
        if (previous != null && previous.required() && previous.walkable() && part == Part.AIR && required)
            conflictingRequiredFloor = true;
        if(previous==null){CompiledAllocation.charge(256);cellBytes=Math.addExact(cellBytes,256);}
        cells.put(p, new Cell(part, required, walkable, Map.copyOf(properties)));
    }

    void box(int x0, int y0, int z0, int x1, int y1, int z1, Part part, boolean required) {
        for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++)
            put(x,y,z,part,required,false);
    }

    void foundation(int x0, int z0, int x1, int z1, int topExclusive) {
        box(x0,localBounds.minY(),z0,x1,topExclusive,z1,Part.FOUNDATION,true);
    }

    void floor(int x0, int z0, int x1, int z1, int feet, Part part, String facing) {
        floor(x0,z0,x1,z1,feet,part,facing,3);
    }

    void floor(int x0, int z0, int x1, int z1, int feet, Part part, String facing, int clearance) {
        if (clearance < 2 || clearance > 3) throw new IllegalArgumentException("clearance must be two or three blocks");
        for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) {
            Map<String,String> properties = part == Part.STAIR
                    ? Map.of("facing", facing, "half", "bottom", "shape", "straight", "waterlogged", "false")
                    : part == Part.FLOOR || part == Part.SLAB ? Map.of("type","top","waterlogged","false") : Map.of();
            put(x,feet-1,z,part,true,true,properties);
            for (int h = 0; h < clearance; h++) put(x,feet+h,z,Part.AIR,true,false);
        }
    }

    void route(Point3i... points) { routes.add(List.of(points)); }

    void rail(int x,int feet,int z) {
        var p=new Point3i(x,feet,z);var prior=cells.get(p);
        if(prior!=null&&prior.required())return;
        foundation(x,z,x+1,z+1,feet);
        put(x,feet,z,Part.PARAPET,false,false);
    }

    void entity(Point3i local, String type, int index, Map<String,String> fields) {
        entities.put(local, new BlockEntityIntent(local,type,input.site().id(),index,fields));
    }

    void spawn(Point3i feet, int index) { spawns.add(new SpawnSlot(input.site().id(),index,feet,"minecraft:pillager")); }
    void span(Bounds load,Bounds left,Bounds right){spans.add(new LocalSpan(load,left,right));}

    ComponentVoxels finish() {
        return finish(Rasterizer.worldBounds(input.site().frame(), localBounds), (x,z) -> {
            Frame f = input.site().frame();
            Point3i p = Rasterizer.localCell(f, new Point3i(x, f.walkY(), z));
            long dx = (long) x * 256 + 128 - f.center().xQ8();
            long dz = (long) z * 256 + 128 - f.center().zQ8();
            long along = FixedMath.mulDiv(dx, f.txQ30(), 1L << 30) + FixedMath.mulDiv(dz, f.tzQ30(), 1L << 30);
            return new Column(p.x(),p.z(),f.walkY(),Math.max(0, Math.addExact(input.site().globalSQ8(),along)));
        }, p -> cells.get(p));
    }

    ComponentVoxels finish(Bounds worldBounds, ColumnMapper mapper, CellRule rule) {
        return finish(worldBounds,mapper,rule,s -> input.decay());
    }

    ComponentVoxels finish(Bounds worldBounds, ColumnMapper mapper, CellRule rule,
            java.util.function.LongFunction<DecaySample> samples) {
        return finish(worldBounds,mapper,rule,samples,null);
    }

    ComponentVoxels finish(Bounds worldBounds, ColumnMapper mapper, CellRule rule,
            java.util.function.LongFunction<DecaySample> samples,java.util.function.Function<Point3i,RouteAnchor> anchors) {
        if(finished)throw new IllegalStateException("assembly already finished");
        finished=true;
        try {
        if (input.site().state() == SiteState.SUPPRESSED || conflictingRequiredFloor) return empty(input.site().id(), worldBounds);
        var voxels = new ArrayList<VoxelIntent>(); var roots = new HashSet<Point3i>();
        var loads=new ArrayList<Set<Point3i>>();var lefts=new ArrayList<Set<Point3i>>();var rights=new ArrayList<Set<Point3i>>();
        for(var ignored:spans){loads.add(new HashSet<>());lefts.add(new HashSet<>());rights.add(new HashSet<>());}
        var localToWorld = new HashMap<Point3i,List<Point3i>>();
        long componentHash = identityHash(input.site().id());
        boolean functional = input.decay().state() == DecayState.INTACT || input.decay().state() == DecayState.PARTIAL;
        DecayState realizedState=input.decay().state();
        for (int z = worldBounds.minZ(); z < worldBounds.maxZExclusive(); z++) for (int x = worldBounds.minX(); x < worldBounds.maxXExclusive(); x++) {
            Column column = mapper.map(x,z);
            if (column == null) continue;
            DecaySample sample=Objects.requireNonNull(samples.apply(column.globalS()));
            boolean columnFunctional=sample.state()==DecayState.INTACT||sample.state()==DecayState.PARTIAL;
            if(sample.state().ordinal()>realizedState.ordinal())realizedState=sample.state();
            ProbeResult support = null;
            boolean checked = false;
            for (int y = worldBounds.minY(); y < worldBounds.maxYExclusive(); y++) {
                Point3i local = new Point3i(column.x(),y-column.walkY(),column.z());
                Cell cell = rule.cell(local);
                if (cell == null) continue;
                if (!checked) {
                    checked = true;
                    support = input.supports().get(new Point3i(x,column.walkY(),z));
                    if (support == null || support.kind() != ProbeKind.SUPPORTED && support.kind() != ProbeKind.WATER
                            || support.drop() > 12 || support.groundTopY() >= column.walkY())
                        return empty(input.site().id(),worldBounds);
                }
                if (y < support.groundTopY() - 1) continue;
                if (support.thinIceY().isPresent()&&y==support.thinIceY().getAsInt()) {
                    boolean main=input.site().id().kind()==SiteKind.WATER_GATE&&local.x()>=-2&&local.x()<2&&cell.part()==Part.WATER;
                    if(!main) {
                        if(cell.required()&&cell.part()!=Part.AIR)return empty(input.site().id(),worldBounds);
                        continue;
                    }
                }
                if (cell.part() == Part.WATER && (y < support.groundTopY()||support.waterTopY().isEmpty()||y>=support.waterTopY().getAsInt())) continue;
                if (cell.part() == Part.AIR && !columnFunctional) continue;
                boolean required = cell.required() && (columnFunctional || local.y() < -20 + sample.keepHeight());
                if (!decay.keep(sample, cell.part(), local, column.globalS(), componentHash, required)) continue;
                Point3i world = new Point3i(x,y,z);
                CompiledAllocation.charge(256);
                long hash = DeterministicHash.fields(componentHash,"voxel",x,y,z);
                BlockSpec block = cell.part() == Part.AIR ? AIR
                        : palette.resolve(input,cell.part(),local,world,column.globalS(),hash);
                if (!cell.properties().isEmpty()) {
                    var properties = new HashMap<>(block.properties()); properties.putAll(cell.properties());
                    block = new BlockSpec(block.id(),properties);
                }
                IntentKind kind = cell.part() == Part.AIR ? IntentKind.OWNED_VOID : cell.part() == Part.WATER ? IntentKind.FLUID : IntentKind.SOLID;
                voxels.add(new VoxelIntent(world,kind,cell.part(),block,input.site().id(),
                        required && cell.part() == Part.AIR ? 900 : priority(input.site().id().kind()),required,cell.walkable() && columnFunctional));
                if (kind == IntentKind.SOLID && y <= support.groundTopY()) roots.add(world);
                if(kind==IntentKind.SOLID)for(int n=0;n<spans.size();n++){
                    var span=spans.get(n);
                    if(contains(span.load(),local))loads.get(n).add(world);
                    if(contains(span.left(),local))lefts.get(n).add(world);
                    if(contains(span.right(),local))rights.get(n).add(world);
                }
                if (columnFunctional && (entities.containsKey(local) || routes.stream().anyMatch(r -> r.contains(local))
                        || spawns.stream().anyMatch(s -> s.feet().equals(local))))
                    localToWorld.computeIfAbsent(local, unused -> new ArrayList<>()).add(world);
            }
        }
        voxels.sort(Comparator.comparing(VoxelIntent::pos,ORDER));
        var occupied = new HashMap<Point3i,VoxelIntent>();
        for (var voxel : voxels) if (voxel.kind() == IntentKind.SOLID) occupied.put(voxel.pos(),voxel);
        var worldRoutes = new ArrayList<List<Point3i>>();
        if (functional || input.site().id().kind()==SiteKind.WALL) for (List<Point3i> route : routes) {
            var converted = new ArrayList<Point3i>();
            for (Point3i p : route) {
                Point3i world = representative(p,localToWorld);
                if (world == null) world = anchors==null?nearbyFeet(p,voxels,occupied):nearbyFeet(anchors.apply(p),voxels,occupied);
                if (world == null) return empty(input.site().id(),worldBounds);
                converted.add(world);
            }
            worldRoutes.add(List.copyOf(converted));
        }
        var worldEntities = new ArrayList<BlockEntityIntent>();
        if (functional) {
            var used = new HashSet<Point3i>();
            for (var entry : entities.entrySet().stream().sorted(Map.Entry.comparingByKey(ORDER)).toList()) {
                Point3i local = entry.getKey();
                var entity = entry.getValue();
                long normal=(long)local.z()*256+128;
                if(input.site().frame().mirror()) normal=-normal;
                Point2q target=Projection.local(input.site().frame(),(long)local.x()*256+128,normal);
                int y=input.site().frame().walkY()+local.y();
                Point3i world=voxels.stream().filter(v->v.pos().y()==y && !used.contains(v.pos()))
                        .filter(v->v.state().id().equals(entity.type()) || v.kind()==IntentKind.OWNED_VOID && !v.required())
                        .map(VoxelIntent::pos).filter(p->distance(p,target)<=384L*384)
                        .filter(p->occupied.containsKey(new Point3i(p.x(),y-1,p.z())))
                        .min(Comparator.<Point3i>comparingInt(p->{
                            var current=occupied.get(p);
                            return current!=null && current.state().id().equals(entity.type()) ? 0 : 1;
                        }).thenComparingLong(p->distance(p,target)).thenComparing(ORDER)).orElseThrow(()->
                                new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"cannot bind unique functional voxel: "+entity));
                used.add(world);
                var cell=cells.get(local);
                BlockSpec state=palette.resolve(input,cell.part(),local,world,input.site().globalSQ8(),
                        DeterministicHash.fields(componentHash,"functional",entity.localId()));
                var properties=new HashMap<>(state.properties()); properties.putAll(cell.properties());
                var placed=new VoxelIntent(world,IntentKind.SOLID,cell.part(),new BlockSpec(state.id(),properties),
                        input.site().id(),priority(input.site().id().kind()),true,false);
                voxels.removeIf(v->v.pos().equals(world)); voxels.add(placed); occupied.put(world,placed);
                if(cell.part()==Part.CAMPFIRE) {
                    var below=new Point3i(world.x(),y-1,world.z());
                    var hay=new VoxelIntent(below,IntentKind.SOLID,Part.HAY,new BlockSpec("minecraft:hay_block",Map.of("axis","y")),
                            input.site().id(),priority(input.site().id().kind()),true,false);
                    voxels.removeIf(v->v.pos().equals(below)); voxels.add(hay); occupied.put(below,hay);
                }
                CompiledAllocation.charge(256);
                worldEntities.add(new BlockEntityIntent(world,entity.type(),entity.site(),entity.localId(),entity.fields()));
            }
            Set<String> types=entities.values().stream().map(BlockEntityIntent::type).collect(java.util.stream.Collectors.toSet());
            voxels.removeIf(v->types.contains(v.state().id()) && !used.contains(v.pos()));
        }
        var worldSpawns = new ArrayList<SpawnSlot>();
        if (functional) for (SpawnSlot s : spawns) {
            Point3i world = representative(s.feet(),localToWorld);
            if (world == null) world = nearbyFeet(s.feet(),voxels,occupied);
            if (world != null) worldSpawns.add(new SpawnSlot(s.site(),s.slot(),world,s.entityType()));
        }
        voxels.sort(Comparator.comparing(VoxelIntent::pos,ORDER));
        var result = state(hashed(input.site().id(),worldBounds,voxels,worldEntities,worldSpawns,worldRoutes),realizedState);
        var groups=new ArrayList<SupportGraph.Span>();
        for(int n=0;n<spans.size();n++)groups.add(new SupportGraph.Span(loads.get(n),lefts.get(n),rights.get(n)));
        return new SupportGraph().validate(result,roots,groups);
        } finally {
            // No returned immutable component refers to this builder's local cell table.
            cells=Map.of();routes.clear();entities.clear();spawns.clear();spans.clear();
            CompiledAllocation.discard(cellBytes);cellBytes=0;
        }
    }

    String facing(int dx, int dz) {
        Frame f = input.site().frame();
        long n = f.mirror() ? -(long) dz : dz;
        long x = (long) dx * f.txQ30() - n * f.tzQ30(), z = (long) dx * f.tzQ30() + n * f.txQ30();
        long best = -z; String result = "north";
        if (x > best) { best = x; result = "east"; }
        if (z > best) { best = z; result = "south"; }
        if (-x > best) result = "west";
        return result;
    }

    static ComponentVoxels empty(SiteId id, Bounds bounds) { return hashed(id,bounds,List.of(),List.of(),List.of(),List.of()); }

    static ComponentVoxels hashed(SiteId id, Bounds b, List<VoxelIntent> v, List<BlockEntityIntent> be,
            List<SpawnSlot> spawns, List<List<Point3i>> routes) {
        var value = new ComponentVoxels(id,b,v,be,spawns,routes,"0".repeat(64));
        return new ComponentVoxels(id,b,v,be,spawns,routes,CanonicalCodec.componentHash(value));
    }

    static ComponentVoxels state(ComponentVoxels c,DecayState state) {
        var value=new ComponentVoxels(c.id(),c.bounds(),c.voxels(),c.blockEntities(),c.spawnSlots(),c.requiredRoutes(),state,"0".repeat(64));
        return new ComponentVoxels(value.id(),value.bounds(),value.voxels(),value.blockEntities(),value.spawnSlots(),
                value.requiredRoutes(),state,CanonicalCodec.componentHash(value));
    }

    static int priority(SiteKind kind) {
        return switch (kind) {
            case FORT -> 800; case GATE, WATER_GATE -> 700; case ENEMY_TOWER, BEACON -> 600;
            case RAMP, END_CAP -> 550; case WALL -> 400;
        };
    }

    static long identityHash(SiteId id) {
        // Length-delimited identity avoids collisions between wallId, kind and ordinal.
        byte[] wall = id.wallId().getBytes(StandardCharsets.UTF_8);
        long hash = DeterministicHash.fields(0,"component-id",wall.length);
        for (byte b : wall) hash = DeterministicHash.fields(hash,"byte",b & 255);
        return DeterministicHash.fields(hash,id.kind().name(),id.ordinal());
    }

    private Point3i representative(Point3i local, Map<Point3i,List<Point3i>> map) {
        List<Point3i> candidates = map.get(local);
        if (candidates == null || candidates.isEmpty()) return null;
        long normal = (long) local.z() * 256 + 128;
        if (input.site().frame().mirror()) normal = -normal;
        Point2q center = Projection.local(input.site().frame(),(long) local.x() * 256 + 128,normal);
        return candidates.stream().min(Comparator.<Point3i>comparingLong(p -> {
            long dx = (long) p.x() * 256 + 128 - center.xQ8(), dz = (long) p.z() * 256 + 128 - center.zQ8();
            return dx * dx + dz * dz;
        }).thenComparing(ORDER)).orElseThrow();
    }

    private Point3i nearbyFeet(Point3i local,List<VoxelIntent> voxels,Map<Point3i,VoxelIntent> occupied) {
        long normal=(long)local.z()*256+128;
        if(input.site().frame().mirror()) normal=-normal;
        Point2q center=Projection.local(input.site().frame(),(long)local.x()*256+128,normal);
        int y=input.site().frame().walkY()+local.y();
        return nearbyFeet(new RouteAnchor(center,y),voxels,occupied);
    }
    private Point3i nearbyFeet(RouteAnchor anchor,List<VoxelIntent> voxels,Map<Point3i,VoxelIntent> occupied) {
        Point2q center=anchor.center();int y=anchor.feetY();
        return voxels.stream().filter(v->v.walkable() && v.pos().y()==y-1)
                .map(v->new Point3i(v.pos().x(),y,v.pos().z()))
                .filter(p->!occupied.containsKey(p) && !occupied.containsKey(new Point3i(p.x(),y+1,p.z())))
                .filter(p->distance(p,center)<=384L*384)
                .min(Comparator.<Point3i>comparingLong(p->distance(p,center)).thenComparing(ORDER)).orElse(null);
    }

    private static long distance(Point3i p,Point2q center) {
        long dx=(long)p.x()*256+128-center.xQ8(), dz=(long)p.z()*256+128-center.zQ8();
        return dx*dx+dz*dz;
    }

    static boolean contains(Bounds b, Point3i p) {
        return p.x() >= b.minX() && p.x() < b.maxXExclusive() && p.y() >= b.minY() && p.y() < b.maxYExclusive()
                && p.z() >= b.minZ() && p.z() < b.maxZExclusive();
    }

    record Cell(Part part, boolean required, boolean walkable, Map<String,String> properties) {}
    record RouteAnchor(Point2q center,int feetY) {}
    record Column(int x, int z, int walkY, long globalS) {}
    @FunctionalInterface interface ColumnMapper { Column map(int worldX, int worldZ); }
    @FunctionalInterface interface CellRule { Cell cell(Point3i local); }
}
