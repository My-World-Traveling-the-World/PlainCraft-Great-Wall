package local.plaincraft.greatwall.component;

import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallFailure;

/** Two filled three-wide stair runs, with a separate ground and wall landing. */
public final class Ramp implements ComponentTemplate {
    private static final Bounds LOCAL=new Bounds(-30,-33,4,-6,3,15);
    private final DecayField decay;
    private final PaletteBinding palette;

    public Ramp(DecayField decay, PaletteBinding palette) {
        this.decay = Objects.requireNonNull(decay); this.palette = Objects.requireNonNull(palette);
    }
    @Override public Bounds localBounds(ComponentInput input) {
        Objects.requireNonNull(input); return new Bounds(-30,-33,-15,30,3,15);
    }
    @Override public ComponentVoxels compile(ComponentInput input) {
        var f=input.site().frame();
        for(int n=0;n<4;n++) {
            boolean reverse=n>=2,mirror=(n%2==1)^reverse^f.mirror();
            var frame=new Frame(f.center(),reverse?-f.txQ30():f.txQ30(),reverse?-f.tzQ30():f.tzQ30(),f.walkY(),mirror);
            var candidate=ComponentComposer.frame(input,frame,input.site().globalSQ8());
            try {
                var result=compileInFrame(candidate);
                if(!result.voxels().isEmpty())return result;
            }catch(WallFailure failure){if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT)throw failure;}
        }
        return ComponentAssembly.empty(input.site().id(),input.site().bounds());
    }
    /** A compound template fixes its own connector frame and must not silently rotate one child. */
    ComponentVoxels compileInFrame(ComponentInput input) {
        int lower=ground(input,-30,5,-26,12);
        if(lower==Integer.MIN_VALUE)return ComponentAssembly.empty(input.site().id(),input.site().bounds());
        return compileBetween(input,lower,input.site().frame().walkY());
    }
    static int ground(ComponentInput input,int x0,int z0,int x1,int z1) {
        return entrance(input,x0,z0,x1,z1).map(GroundEntranceContract.Certificate::lowerFeetY).orElse(Integer.MIN_VALUE);
    }
    static java.util.Optional<GroundEntranceContract.Certificate> entrance(ComponentInput input,int x0,int z0,int x1,int z1) {
        return GroundEntranceContract.inspect(input.site().frame(),
                GroundEntranceContract.Corridor.forSite(input.site().id().kind(),x0,z0,x1,z1),
                input.supports(),input.entranceEvidence());
    }
    public ComponentVoxels compileBetween(ComponentInput input, int lowerFeetY, int upperFeetY) {
        int w = input.site().frame().walkY();
        long height = (long) upperFeetY-lowerFeetY;
        if (height < 1 || height > 32 || lowerFeetY < w-32 || upperFeetY > w)
            throw new IllegalArgumentException("ramp height/window contract");
        var a = new ComponentAssembly(input,LOCAL,decay,palette);
        int lower = lowerFeetY-w, upper = upperFeetY-w;
        if (height <= 3) {
            landing(a,-29,5,-26,8,lower);
            for (int j = 0; j < height; j++) {
                int x = -26+j, feet = lower+j+1;
                a.foundation(x,5,x+1,8,feet-1);
                a.floor(x,5,x+1,8,feet,Part.STAIR,a.facing(1,0));
            }
            int end = -26+(int) height;
            landing(a,end,4,end+3,11,upper);
            a.route(new Point3i(-28,lower,6),new Point3i(end+1,upper,6),new Point3i(end+1,upper,4));
            lowerRails(a,lower);
            for(int j=0;j<height;j++)a.rail(-26+j,lower+j+1,4);
            for(int x=end;x<end+3;x++)a.rail(x,upper,11);
            for(int z=4;z<=10;z++)a.rail(end+3,upper,z);
            groundEntrance(a,lower);
            return a.finish();
        }
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
        lowerRails(a,lower);
        for(int j=0;j<left;j++)a.rail(-26+j,lower+j+1,4);
        for(int j=0;j<right;j++)a.rail(e-j,lower+left+j+1,11);
        for(int x=e+1;x<e+4;x++){a.rail(x,lower+left,4);a.rail(x,lower+left,11);}
        for(int z=5;z<11;z++)a.rail(e+4,lower+left,z);
        for(int x=t-3;x<t;x++)a.rail(x,upper,11);
        for(int z=4;z<11;z++)a.rail(t-4,upper,z);
        groundEntrance(a,lower);
        return a.finish();
    }

    private static void groundEntrance(ComponentAssembly a,int lower) {
        // The upper landing's filled end rail must not seal the ground landing underneath.
        landing(a,-30,5,-29,8,lower);
        // A tangent neighboring fort may occupy the front apron; retain a three-wide side exit.
        landing(a,-29,8,-26,12,lower);
    }

    private static void lowerRails(ComponentAssembly a,int lower) {
        for(int x=-29;x<-26;x++){a.rail(x,lower,4);a.rail(x,lower,8);}
    }

    private static void landing(ComponentAssembly a, int x0, int z0, int x1, int z1, int feet) {
        a.foundation(x0,z0,x1,z1,feet-1);
        a.floor(x0,z0,x1,z1,feet,Part.FLOOR,"north");
    }
}
