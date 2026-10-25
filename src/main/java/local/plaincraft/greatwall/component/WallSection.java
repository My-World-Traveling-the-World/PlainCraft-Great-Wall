package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** A 64-block arc cell, rasterized against the station chain rather than its straight chord. */
public final class WallSection implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;

    public WallSection(DecayField decay, PaletteBinding palette) {
        this.decay = Objects.requireNonNull(decay); this.palette = Objects.requireNonNull(palette);
    }

    @Override public Bounds localBounds(ComponentInput input) {
        Objects.requireNonNull(input);
        return new Bounds(-32,-32,-5,32,3,5);
    }

    /** The explicit fill contract, not a window-wide lowest-value bound. */
    private static int foundationFloor(ComponentInput input, List<Station> stations,
                                       long from, long to) {
        int floor = Integer.MAX_VALUE;
        for (var core : input.foundations()) {
            if (core.globalSQ8() < from || core.globalSQ8() >= to) continue;
            for (var fill : core.fills()) {
                floor = Math.min(floor, fill.surfaceY() - height(stations, core.globalSQ8()) - 1);
            }
        }
        return floor == Integer.MAX_VALUE ? -32 : Math.min(-32, floor);
    }

    @Override public ComponentVoxels compile(ComponentInput input) {
        long center=input.site().globalSQ8();
        return compileIntervals(input,List.of(new ArcInterval(Math.max(0,center-32L*256),center+32L*256)));
    }

    public record ColumnContract(long globalSQ8,int lateralCell,int walkY) {}
    public static ColumnContract contract(List<Station> stations,int x,int z) {
        if(stations.size()<2)throw new IllegalArgumentException("station ribbon needs two points");
        var c=column(stations,x,z,stations.getFirst().globalSQ8(),stations.getFirst().globalSQ8(),
                Math.addExact(stations.getLast().globalSQ8(),1),false);
        if(c==null)throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"column outside ribbon");
        return new ColumnContract(c.globalS(),c.z(),c.walkY());
    }

    public ComponentVoxels compileIntervals(ComponentInput input,List<ArcInterval> intervals) {
        return compileIntervals(input,intervals,
                input.decay().state()==DecayState.INTACT||input.decay().state()==DecayState.PARTIAL?intervals:List.of(),s->input.decay());
    }

    /** Rendering and walkability are separate: low ruins remain visible without claiming a walkway. */
    public ComponentVoxels compileIntervals(ComponentInput input,List<ArcInterval> intervals,
            List<ArcInterval> walkable,java.util.function.LongFunction<DecaySample> samples) {
        return compileIntervals(input,intervals,walkable,samples,List.of());
    }

    /** A rigid gate replaces wall columns between its own end planes, not a curved S proxy. */
    public ComponentVoxels compileIntervals(ComponentInput input,List<ArcInterval> intervals,
            List<ArcInterval> walkable,java.util.function.LongFunction<DecaySample> samples,List<Frame> gates) {
        final List<Frame> replacements=List.copyOf(gates);
        List<Station> stations = input.stations();
        long center = input.site().globalSQ8(), from = Math.max(0,center-32L*256);
        if(stations.size()<2)throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"wall requires complete station ribbon");
        long to = Math.min(stations.getLast().globalSQ8(),center+32L*256);
        if (stations.size() < 2 || stations.getFirst().globalSQ8() > from || stations.getLast().globalSQ8() < to)
            throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"wall requires complete station ribbon");
        Bounds bounds = curveBounds(input,stations,from,to);
        var assembly = new ComponentAssembly(input,localBounds(input),decay,palette);
        for(var interval:intervals)
            if(interval.fromQ8()<from||interval.toQ8()>to)throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"build interval outside cell");
        for(var interval:walkable) {
            if(interval.fromQ8()<from||interval.toQ8()>to)throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"walkable interval outside cell");
            int left=FixedMath.floorBlock(interval.fromQ8()-center)+1,right=FixedMath.floorBlock(interval.toQ8()-center)-2;
            while(left<=right&&replacedAnchor(input,left,replacements))left++;
            while(right>=left&&replacedAnchor(input,right,replacements))right--;
            if(right>=left)assembly.route(new Point3i(left,0,0),new Point3i(right,0,0));
        }
        return assembly.finish(bounds,(x,z) -> {
            if(replaced(x,z,replacements))return null;
            var column=column(stations,x,z,center,from,to,input.site().frame().mirror());
            return column!=null&&intervals.stream().anyMatch(i->column.globalS()>=i.fromQ8()&&column.globalS()<i.toQ8())?column:null;
        },p -> {
            // Explicit foundation contract: base is the highest sampled surface of this section.
            // Every lower column is filled from its own natural surface to that base, never to a
            // different station's floor. This is independent of the fixed -16 wall body above it.
            for (var core : input.foundations()) {
                if (core.globalSQ8() != center + (long) p.x() * 256L) continue;
                for (var fill : core.fills()) {
                    if (fill.lateral() != p.z()) continue;
                    int localY = p.y();
                    int columnWalkY = height(stations, core.globalSQ8());
                    if (localY < fill.baseY() - columnWalkY
                            && localY >= fill.surfaceY() - columnWalkY) {
                        return cell(Part.FOUNDATION,true,false,Map.of());
                    }
                }
            }
            if (!ComponentAssembly.contains(localBounds(input),p)) return null;
            int y = p.y(), z = p.z();
            long globalS = center + (long) p.x()*256;
            if (globalS < 0) return null;
            if (y < -16) return cell(Part.FOUNDATION,true,false,Map.of());
            if (y < -8) return cell(z == -5 || z == 4 ? Part.SKIN : Part.CORE,z > -5 && z < 4,false,Map.of());
            if (z < -4 || z >= 4) return null;
            if (y < -1) {
                if (y == -2 && (z == -4 || z == 3) && Math.floorMod(Math.floorDiv(globalS,256),12) == 0)
                    return cell(Part.AIR,false,false,Map.of());
                return cell(z == -4 || z == 3 ? Part.SKIN : Part.CORE,z > -4 && z < 3,false,Map.of());
            }
            if (y == -1) {
                if (z < -3 || z >= 3) return cell(Part.PARAPET,true,false,Map.of());
                int w = height(stations,globalS);
                int next = height(stations,Math.min(to,globalS+256));
                int previous = height(stations,Math.max(from,globalS-256));
                if (Math.abs(next-w) > 1 || Math.abs(w-previous) > 1)
                    throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"height contract jumps more than one block");
                int slope = next > w ? 1 : previous > w ? -1 : 0;
                return slope == 0 ? cell(Part.FLOOR,true,true,Map.of("type","top","waterlogged","false"))
                        : cell(Part.STAIR,true,true,Map.of("facing",assembly.facing(slope,0),
                                "half","bottom","shape","straight","waterlogged","false"));
            }
            if (z >= -3 && z < 3) return cell(Part.AIR,true,false,Map.of());
            int crenel = z == -4 && Math.floorMod(Math.floorDiv(globalS,256),4) < 2 ? 2 : 1;
            if (y < crenel) return cell(Part.PARAPET,false,false,Map.of());
            if (z == 3 && y == 1 && Math.floorMod(Math.floorDiv(globalS,256),16) == 0
                    && samples.apply(globalS).state() == DecayState.INTACT)
                return cell(Part.LAMP,false,false,Map.of("hanging","false","waterlogged","false"));
            return null;
        },samples,p->routeAnchor(input,p));
    }

    private static boolean replacedAnchor(ComponentInput input,int localX,List<Frame> gates) {
        if(gates.isEmpty())return false;
        var p=routeAnchor(input,new Point3i(localX,0,0)).center();
        return replaced(FixedMath.floorBlock(p.xQ8()),FixedMath.floorBlock(p.zQ8()),gates);
    }

    private static boolean replaced(int x,int z,List<Frame> gates) {
        for(var gate:gates) {
            int along=Rasterizer.localCell(gate,new Point3i(x,gate.walkY(),z)).x();
            if(along>=-30&&along<30)return true;
        }
        return false;
    }

    /** Curve routes use the same station ribbon as the rasterizer, not the center's straight tangent. */
    private static ComponentAssembly.RouteAnchor routeAnchor(ComponentInput input,Point3i local) {
        long s=input.site().globalSQ8()+local.x()*256L+128;
        var stations=input.stations();
        for(int i=1;i<stations.size();i++) {
            var a=stations.get(i-1);var b=stations.get(i);
            if(s<a.globalSQ8()||s>b.globalSQ8())continue;
            long dx=b.point().xQ8()-a.point().xQ8(),dz=b.point().zQ8()-a.point().zQ8();
            long span=b.globalSQ8()-a.globalSQ8(),offset=s-a.globalSQ8(),length=FixedMath.hypotQ8(dx,dz);
            long n=local.z()*256L+128;if(input.site().frame().mirror())n=-n;
            var point=new Point2q(a.point().xQ8()+FixedMath.mulDiv(dx,offset,span)-FixedMath.mulDiv(dz,n,length),
                    a.point().zQ8()+FixedMath.mulDiv(dz,offset,span)+FixedMath.mulDiv(dx,n,length));
            return new ComponentAssembly.RouteAnchor(point,height(stations,s)+local.y());
        }
        throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"route anchor outside station ribbon");
    }

    private static ComponentAssembly.Cell cell(Part p, boolean required, boolean walk, Map<String,String> properties) {
        return new ComponentAssembly.Cell(p,required,walk,properties);
    }

    private static Bounds curveBounds(ComponentInput input, List<Station> stations, long from, long to) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        int foundationFloor = foundationFloor(input, stations, from, to);
        for (int i = 1; i < stations.size(); i++) {
            Station a = stations.get(i-1), b = stations.get(i);
            if (b.globalSQ8() < from || a.globalSQ8() > to) continue;
            for (long s : new long[] {Math.max(from,a.globalSQ8()),Math.min(to,b.globalSQ8())}) {
                long dx = b.point().xQ8()-a.point().xQ8(), dz = b.point().zQ8()-a.point().zQ8();
                int x = FixedMath.floorBlock(a.point().xQ8()+FixedMath.mulDiv(dx,s-a.globalSQ8(),b.globalSQ8()-a.globalSQ8()));
                int z = FixedMath.floorBlock(a.point().zQ8()+FixedMath.mulDiv(dz,s-a.globalSQ8(),b.globalSQ8()-a.globalSQ8()));
                x0 = Math.min(x0,x-7); x1 = Math.max(x1,x+8); z0 = Math.min(z0,z-7); z1 = Math.max(z1,z+8);
                int y = height(stations,s);
                y0 = Math.min(y0, y + foundationFloor);
                y1 = Math.max(y1, y + 3);
            }
        }
        return new Bounds(x0,y0,z0,x1,y1,z1);
    }

    private static ComponentAssembly.Column column(List<Station> stations, int x, int z,
            long center, long from, long to, boolean mirror) {
        long px = (long) x*256+128, pz = (long) z*256+128, best = Long.MAX_VALUE;
        int selected = -1;
        long selectedNumerator = 0, selectedDenominator = 1;
        for (int i = 1; i < stations.size(); i++) {
            Station a = stations.get(i-1), b = stations.get(i);
            if (b.globalSQ8() < from-1024 || a.globalSQ8() > to+1024) continue;
            long vx = b.point().xQ8()-a.point().xQ8(), vz = b.point().zQ8()-a.point().zQ8();
            long wx = px-a.point().xQ8(), wz = pz-a.point().zQ8(), den = vx*vx+vz*vz;
            if (den == 0) throw new IllegalArgumentException("zero station edge");
            // The rounded projection stays inside the integer endpoint AABB. Equal-distance edges lose.
            long boxX=Math.max(0,Math.max(Math.min(a.point().xQ8(),b.point().xQ8())-px,
                    px-Math.max(a.point().xQ8(),b.point().xQ8())));
            long boxZ=Math.max(0,Math.max(Math.min(a.point().zQ8(),b.point().zQ8())-pz,
                    pz-Math.max(a.point().zQ8(),b.point().zQ8())));
            if(boxX<=Integer.MAX_VALUE&&boxZ<=Integer.MAX_VALUE&&boxX*boxX+boxZ*boxZ>=best)continue;
            long num = Math.clamp(wx*vx+wz*vz,0,den);
            long nx = num==0?a.point().xQ8():num==den?b.point().xQ8():a.point().xQ8()+FixedMath.mulDiv(vx,num,den);
            long nz = num==0?a.point().zQ8():num==den?b.point().zQ8():a.point().zQ8()+FixedMath.mulDiv(vz,num,den);
            long dx = px-nx, dz = pz-nz, dist = dx*dx+dz*dz;
            if (dist >= best) continue;
            best = dist;
            selected = i; selectedNumerator = num; selectedDenominator = den;
        }
        if (selected < 0) return null;
        // Preserve the first nearest edge and its original rounding; interpolate only the winner.
        Station a = stations.get(selected-1), b = stations.get(selected);
        long s = a.globalSQ8()+FixedMath.mulDiv(b.globalSQ8()-a.globalSQ8(),selectedNumerator,selectedDenominator);
        if (s < from || s >= to) return null;
        long vx = b.point().xQ8()-a.point().xQ8(), vz = b.point().zQ8()-a.point().zQ8();
        long length = FixedMath.hypotQ8(vx,vz);
        long lateral = FixedMath.mulDiv(vx,pz-a.point().zQ8(),length)
                - FixedMath.mulDiv(vz,px-a.point().xQ8(),length);
        if (mirror) lateral = -lateral;
        return new ComponentAssembly.Column(FixedMath.floorBlock(s-center),
                FixedMath.floorBlock(lateral),height(stations,s),s);
    }

    private static int height(List<Station> stations, long s) {
        for (int i = 1; i < stations.size(); i++) {
            Station a = stations.get(i-1), b = stations.get(i);
            if (s >= a.globalSQ8() && s <= b.globalSQ8())
                return a.walkY()+(int) FixedMath.roundDiv((long) (b.walkY()-a.walkY())*(s-a.globalSQ8()),b.globalSQ8()-a.globalSQ8());
        }
        throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,"height outside station ribbon");
    }
}
