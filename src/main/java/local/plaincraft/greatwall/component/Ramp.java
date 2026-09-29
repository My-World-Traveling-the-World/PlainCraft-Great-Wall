package local.plaincraft.greatwall.component;

import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Two filled three-wide stair runs, with a separate ground and wall landing. */
public final class Ramp implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;

    public Ramp(DecayField decay, PaletteBinding palette) {
        this.decay = Objects.requireNonNull(decay); this.palette = Objects.requireNonNull(palette);
    }
    @Override public Bounds localBounds(ComponentInput input) {
        Objects.requireNonNull(input); return new Bounds(-30,-32,4,-6,3,15);
    }
    @Override public ComponentVoxels compile(ComponentInput input) {
        return compileBetween(input,input.site().frame().walkY()-20,input.site().frame().walkY());
    }
    public ComponentVoxels compileBetween(ComponentInput input, int lowerFeetY, int upperFeetY) {
        int w = input.site().frame().walkY();
        long height = (long) upperFeetY-lowerFeetY;
        if (height < 1 || height > 32 || lowerFeetY < w-32 || upperFeetY > w)
            throw new IllegalArgumentException("ramp height/window contract");
        var a = new ComponentAssembly(input,localBounds(input),decay,palette);
        int lower = lowerFeetY-w, upper = upperFeetY-w;
        int left = ((int) height+1)/2, right = (int) height-left, e = -27+left;
        landing(a,-29,5,-26,8,lower);
        for (int j = 0; j < left; j++) {
            int x = -26+j, feet = lower+j+1;
            a.foundation(x,5,x+1,8,feet-1);
            a.floor(x,5,x+1,8,feet,Part.STAIR,a.facing(1,0));
        }
        landing(a,e+1,5,e+4,11,lower+left);
        for (int j = 0; j < right; j++) {
            int x = e-j, feet = lower+left+j+1;
            a.foundation(x,8,x+1,11,feet-1);
            a.floor(x,8,x+1,11,feet,Part.STAIR,a.facing(-1,0));
        }
        int t = right > 0 ? e-right+1 : e;
        landing(a,t-3,4,t,11,upper);
        if (right == 0) landing(a,t,8,e+4,11,upper);
        a.route(new Point3i(-28,lower,6),new Point3i(e+2,lower+left,9),new Point3i(t-2,upper,4));
        return a.finish();
    }

    private static void landing(ComponentAssembly a, int x0, int z0, int x1, int z1, int feet) {
        a.foundation(x0,z0,x1,z1,feet-1);
        a.floor(x0,z0,x1,z1,feet,Part.FLOOR,"north");
    }
}
