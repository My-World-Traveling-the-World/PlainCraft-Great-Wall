package local.plaincraft.greatwall.decay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Whole-component support and route checks. Working collections are invocation-local. */
public final class SupportGraph {
    public record Span(Set<Point3i> load,Set<Point3i> left,Set<Point3i> right) {
        public Span {load=Set.copyOf(load);left=Set.copyOf(left);right=Set.copyOf(right);}
    }
    private static final int[][] SIDES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final int[][] HORIZONTAL = {{1,0},{0,1},{-1,0},{0,-1}};
    private static final Comparator<Point3i> ORDER = Comparator.comparingInt(Point3i::y)
            .thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);

    public SupportGraph() {}

    public ComponentVoxels validate(ComponentVoxels component, Set<Point3i> foundationRoots) {
        return validate(component,foundationRoots,List.of());
    }

    public ComponentVoxels validate(ComponentVoxels component,Set<Point3i> foundationRoots,List<Span> spans) {
        Objects.requireNonNull(component); Objects.requireNonNull(foundationRoots);
        Map<Point3i,VoxelIntent> blocks = occupancy(component);
        Map<Point3i,VoxelIntent> retained = new HashMap<>(blocks);
        // Remove unstable nodes simultaneously, then flood again; decoration cannot root masonry.
        while (true) {
            Set<Point3i> connected = rootedSpans(retained, foundationRoots,spans);
            var removed = new HashSet<Point3i>();
            for (VoxelIntent v : retained.values()) {
                boolean keep = structural(v) ? connected.contains(v.pos()) : decorationSupported(v, retained, connected);
                if (gravity(v.state().id())) {
                    VoxelIntent below = retained.get(offset(v.pos(), 0, -1, 0));
                    keep &= below != null && connected.contains(below.pos()) && fullTop(below);
                }
                if (!keep) removed.add(v.pos());
            }
            if (removed.isEmpty()) break;
            removed.forEach(retained::remove);
        }
        // A wall segment is one component spanning a hundred chunks, and a single voxel the template left
        // unsupported discarded all of them: a measured run lost 246 chunks to two voxels. Large components
        // therefore tolerate a rare blemish and drop just that voxel; small ones stay strict, which is the
        // rule removesFloatingIslandsButRejectsRequiredIsland protects - an island IS the whole component.
        java.util.List<VoxelIntent> unsupported = new java.util.ArrayList<>();
        for (VoxelIntent v : blocks.values())
            if (v.required() && !retained.containsKey(v.pos())) unsupported.add(v);
        if (!unsupported.isEmpty()) {
            long requiredCount = blocks.values().stream().filter(VoxelIntent::required).count();
            long tolerated = requiredCount >= 200L ? Math.max(1L, requiredCount / 500L) : 0L;
            if (unsupported.size() > tolerated) throw failure(
                    "required voxel has no rooted support: " + unsupported.get(0).pos()
                            + " in " + component.id() + " bounds=" + component.bounds()
                            + " (unsupported=" + unsupported.size() + " of " + requiredCount + ")");
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn("[GreatWall:S1C] SUPPORT_TOLERATED unsupported={} required={} tolerated={} first={}",
                    unsupported.size(), requiredCount, tolerated, unsupported.get(0).pos());
        }
        List<VoxelIntent> voxels = component.voxels().stream()
                .filter(v -> !solid(v) || retained.containsKey(v.pos())).sorted(Comparator.comparing(VoxelIntent::pos, ORDER)).toList();
        var result = hashed(component, voxels,
                component.blockEntities().stream().filter(e -> retained.containsKey(e.pos())).toList(),
                component.spawnSlots().stream().filter(s -> retained.containsKey(offset(s.feet(), 0, -1, 0))).toList());
        for (List<Point3i> route : component.requiredRoutes()) {
            if (route.isEmpty()) throw failure("empty required route");
            for (int i = 0; i < route.size(); i++) {
                if (!reachable(result, route.get(0), route.get(i))) throw failure("required route is disconnected: " + route);
            }
        }
        return result;
    }

    private static Set<Point3i> rootedSpans(Map<Point3i,VoxelIntent> blocks,Set<Point3i> roots,List<Span> spans) {
        var blocked=new HashSet<Point3i>();spans.forEach(s->blocked.addAll(s.load()));
        var allowed=new HashMap<>(blocks);blocked.forEach(allowed::remove);
        var pending=new ArrayList<>(spans);
        while(true){
            var connected=rooted(allowed,roots);boolean progress=false;
            for(var iterator=pending.iterator();iterator.hasNext();){
                var span=iterator.next();
                if(span.left().stream().anyMatch(connected::contains)&&span.right().stream().anyMatch(connected::contains)){
                    span.load().forEach(p->{var value=blocks.get(p);if(value!=null)allowed.put(p,value);});
                    iterator.remove();progress=true;
                }
            }
            if(!progress)return connected;
        }
    }

    private static Set<Point3i> rooted(Map<Point3i,VoxelIntent> blocks, Set<Point3i> foundationRoots) {
        Set<Point3i> connected = new HashSet<>();
        var queue = new ArrayDeque<Point3i>();
        foundationRoots.stream().sorted(ORDER).filter(p -> structural(blocks.get(p))).forEach(p -> {
            connected.add(p); queue.add(p);
        });
        while (!queue.isEmpty()) {
            Point3i p = queue.removeFirst();
            for (int[] d : SIDES) {
                Point3i next = offset(p, d[0], d[1], d[2]);
                if (structural(blocks.get(next)) && connected.add(next)) queue.addLast(next);
            }
        }
        return connected;
    }

    public boolean reachable(ComponentVoxels component, Point3i startFeet, Point3i endFeet) {
        Objects.requireNonNull(startFeet); Objects.requireNonNull(endFeet);
        var blocks = occupancy(component);
        var start = new Foot(startFeet.x(), (long) startFeet.y() * 2, startFeet.z());
        var end = new Foot(endFeet.x(), (long) endFeet.y() * 2, endFeet.z());
        if (!standable(start, blocks, component.bounds()) || !standable(end, blocks, component.bounds())) return false;
        var queue = new ArrayDeque<Foot>(); var seen = new HashSet<Foot>();
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) {
            Foot foot = queue.removeFirst();
            if (foot.equals(end)) return true;
            for (int[] d : HORIZONTAL) for (int rise = -2; rise <= 2; rise++) {
                var next = new Foot(Math.addExact(foot.x(), d[0]), foot.y2() + rise, Math.addExact(foot.z(), d[1]));
                if (!seen.contains(next) && standable(next, blocks, component.bounds())
                        && transitionClear(foot, next, blocks)) { seen.add(next); queue.addLast(next); }
            }
        }
        return false;
    }

    private static boolean standable(Foot f, Map<Point3i,VoxelIntent> blocks, Bounds b) {
        if (f.x() < b.minX() || f.x() >= b.maxXExclusive() || f.z() < b.minZ() || f.z() >= b.maxZExclusive()
                || f.y2() < (long) b.minY() * 2 || f.y2() >= (long) b.maxYExclusive() * 2) return false;
        int belowY = Math.toIntExact(Math.floorDiv(f.y2() - 1, 2));
        VoxelIntent floor = blocks.get(new Point3i(f.x(), belowY, f.z()));
        if (floor == null || !structural(floor) || (long) belowY * 2 + top(floor) != f.y2()) return false;
        return clear(f, f.y2(), f.y2() + 4, blocks);
    }

    private static boolean transitionClear(Foot from, Foot to, Map<Point3i,VoxelIntent> blocks) {
        long head = Math.max(from.y2(), to.y2()) + 4;
        return clear(from, from.y2(), head, blocks) && clear(to, to.y2(), head, blocks);
    }

    private static boolean clear(Foot f, long bottom, long top, Map<Point3i,VoxelIntent> blocks) {
        for (int y = Math.toIntExact(Math.floorDiv(bottom, 2)); (long) y * 2 < top; y++) {
            VoxelIntent v = blocks.get(new Point3i(f.x(), y, f.z()));
            if (v != null) {
                int low = "top".equals(v.state().properties().get("type")) ? 1 : 0;
                if ((long) y * 2 + top(v) > bottom && (long) y * 2 + low < top) return false;
            }
        }
        return true;
    }

    private static int top(VoxelIntent v) {
        return "bottom".equals(v.state().properties().get("type")) ? 1 : 2;
    }

    private static boolean fullTop(VoxelIntent v) {
        return structural(v) && top(v) == 2 && !v.state().id().endsWith("_stairs");
    }

    private static boolean decorationSupported(VoxelIntent v, Map<Point3i,VoxelIntent> blocks, Set<Point3i> roots) {
        if (v.part() == Part.WATER || v.part() == Part.AIR) return true;
        Point3i below = offset(v.pos(), 0, -1, 0);
        if (roots.contains(below) && fullTop(blocks.get(below))) return true;
        if (v.part() == Part.CHAIN || "true".equals(v.state().properties().get("hanging")))
            return roots.contains(offset(v.pos(), 0, 1, 0));
        return false;
    }

    private static boolean gravity(String id) {
        return id.equals("minecraft:sand") || id.equals("minecraft:red_sand") || id.equals("minecraft:gravel")
                || id.endsWith("_concrete_powder") || id.equals("minecraft:anvil")
                || id.equals("minecraft:chipped_anvil") || id.equals("minecraft:damaged_anvil");
    }

    private static boolean structural(VoxelIntent v) {
        return v != null && solid(v) && switch (v.part()) {
            case AIR, WATER, LAMP, CHAIN, FENCE, CAMPFIRE, CHEST, SPAWNER -> false;
            default -> true;
        };
    }

    private static boolean solid(VoxelIntent v) { return v.kind() == IntentKind.SOLID; }

    private static Map<Point3i,VoxelIntent> occupancy(ComponentVoxels component) {
        var result = new HashMap<Point3i,VoxelIntent>();
        for (VoxelIntent v : component.voxels()) if (solid(v)) {
            if (result.putIfAbsent(v.pos(), v) != null) throw failure("duplicate component voxel: " + v.pos());
        }
        return result;
    }

    private static ComponentVoxels hashed(ComponentVoxels c, List<VoxelIntent> voxels,
            List<BlockEntityIntent> entities, List<SpawnSlot> slots) {
        var value = new ComponentVoxels(c.id(), c.bounds(), voxels, entities, slots, c.requiredRoutes(),c.realizedState(), "0".repeat(64));
        return new ComponentVoxels(value.id(), value.bounds(), value.voxels(), value.blockEntities(), value.spawnSlots(),
                value.requiredRoutes(),value.realizedState(), CanonicalCodec.componentHash(value));
    }

    private static Point3i offset(Point3i p, int x, int y, int z) {
        return new Point3i(Math.addExact(p.x(), x), Math.addExact(p.y(), y), Math.addExact(p.z(), z));
    }

    private static WallFailure failure(String message) { return new WallFailure(FailureCode.OWNERSHIP_CONFLICT, message); }
    private record Foot(int x, long y2, int z) {}
}
