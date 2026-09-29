package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.geometry.FixedMath;
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

    @Override public ComponentVoxels compile(ComponentInput input) {
        List<Station> stations = input.stations();
        long center = input.site().globalSQ8(), from = Math.max(0,center-32L*256), to = center+32L*256;
        if (stations.size() < 2 || stations.getFirst().globalSQ8() > from || stations.getLast().globalSQ8() < to)
            throw new IllegalArgumentException("wall requires complete station ribbon");
        Bounds bounds = curveBounds(stations,from,to);
        var assembly = new ComponentAssembly(input,localBounds(input),decay,palette);
        assembly.route(new Point3i((int) ((from-center)/256)+1,0,0),new Point3i(30,0,0));
        return assembly.finish(bounds,(x,z) -> column(stations,x,z,center,from,to,input.site().frame().mirror()),p -> {
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
                    throw new IllegalArgumentException("height contract jumps more than one block");
                int slope = next > w ? 1 : previous > w ? -1 : 0;
                return slope == 0 ? cell(Part.FLOOR,true,true,Map.of("type","top","waterlogged","false"))
                        : cell(Part.STAIR,true,true,Map.of("facing",assembly.facing(slope,0),
                                "half","bottom","shape","straight","waterlogged","false"));
            }
            if (z >= -3 && z < 3) return cell(Part.AIR,true,false,Map.of());
            int crenel = z == -4 && Math.floorMod(Math.floorDiv(globalS,256),4) < 2 ? 2 : 1;
            if (y < crenel) return cell(Part.PARAPET,false,false,Map.of());
            if (z == 3 && y == 1 && Math.floorMod(Math.floorDiv(globalS,256),16) == 0
                    && input.decay().state() == DecayState.INTACT)
                return cell(Part.LAMP,false,false,Map.of("hanging","false","waterlogged","false"));
            return null;
        });
    }

    private static ComponentAssembly.Cell cell(Part p, boolean required, boolean walk, Map<String,String> properties) {
        return new ComponentAssembly.Cell(p,required,walk,properties);
    }

    private static Bounds curveBounds(List<Station> stations, long from, long to) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (int i = 1; i < stations.size(); i++) {
            Station a = stations.get(i-1), b = stations.get(i);
            if (b.globalSQ8() < from || a.globalSQ8() > to) continue;
            for (long s : new long[] {Math.max(from,a.globalSQ8()),Math.min(to,b.globalSQ8())}) {
                long dx = b.point().xQ8()-a.point().xQ8(), dz = b.point().zQ8()-a.point().zQ8();
                int x = FixedMath.floorBlock(a.point().xQ8()+FixedMath.mulDiv(dx,s-a.globalSQ8(),b.globalSQ8()-a.globalSQ8()));
                int z = FixedMath.floorBlock(a.point().zQ8()+FixedMath.mulDiv(dz,s-a.globalSQ8(),b.globalSQ8()-a.globalSQ8()));
                x0 = Math.min(x0,x-7); x1 = Math.max(x1,x+8); z0 = Math.min(z0,z-7); z1 = Math.max(z1,z+8);
                int y = height(stations,s); y0 = Math.min(y0,y-32); y1 = Math.max(y1,y+3);
            }
        }
        return new Bounds(x0,y0,z0,x1,y1,z1);
    }

    private static ComponentAssembly.Column column(List<Station> stations, int x, int z,
            long center, long from, long to, boolean mirror) {
        long px = (long) x*256+128, pz = (long) z*256+128, best = Long.MAX_VALUE;
        ComponentAssembly.Column result = null;
        for (int i = 1; i < stations.size(); i++) {
            Station a = stations.get(i-1), b = stations.get(i);
            if (b.globalSQ8() < from-1024 || a.globalSQ8() > to+1024) continue;
            long vx = b.point().xQ8()-a.point().xQ8(), vz = b.point().zQ8()-a.point().zQ8();
            long wx = px-a.point().xQ8(), wz = pz-a.point().zQ8(), den = vx*vx+vz*vz;
            if (den == 0) throw new IllegalArgumentException("zero station edge");
            long num = Math.clamp(wx*vx+wz*vz,0,den);
            long nx = a.point().xQ8()+FixedMath.mulDiv(vx,num,den), nz = a.point().zQ8()+FixedMath.mulDiv(vz,num,den);
            long dx = px-nx, dz = pz-nz, dist = dx*dx+dz*dz;
            if (dist >= best) continue;
            best = dist;
            long s = a.globalSQ8()+FixedMath.mulDiv(b.globalSQ8()-a.globalSQ8(),num,den);
            long lateral = FixedMath.mulDiv(vx,wz,FixedMath.hypotQ8(vx,vz))
                    - FixedMath.mulDiv(vz,wx,FixedMath.hypotQ8(vx,vz));
            if (mirror) lateral = -lateral;
            result = s >= from && s < to ? new ComponentAssembly.Column(FixedMath.floorBlock(s-center),
                    FixedMath.floorBlock(lateral),height(stations,s),s) : null;
        }
        return result;
    }

    private static int height(List<Station> stations, long s) {
        for (int i = 1; i < stations.size(); i++) {
            Station a = stations.get(i-1), b = stations.get(i);
            if (s >= a.globalSQ8() && s <= b.globalSQ8())
                return a.walkY()+(int) FixedMath.roundDiv((long) (b.walkY()-a.walkY())*(s-a.globalSQ8()),b.globalSQ8()-a.globalSQ8());
        }
        throw new IllegalArgumentException("height outside station ribbon");
    }
}
