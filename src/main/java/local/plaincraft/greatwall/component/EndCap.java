package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** A terminal-side taper connected to a complete ground exit; never creates a far-bank cap. */
public final class EndCap implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;
    public EndCap(DecayField decay,PaletteBinding palette) {this.decay=Objects.requireNonNull(decay);this.palette=Objects.requireNonNull(palette);}
    @Override public Bounds localBounds(ComponentInput input) {Objects.requireNonNull(input);return new Bounds(-30,-33,-5,1,3,15);}
    @Override public ComponentVoxels compile(ComponentInput input) {
        ComponentVoxels last=null;
        for(int retreat=0;retreat<=128;retreat+=4) {
            if(input.site().globalSQ8()<retreat*256L) break;
            var f=input.site().frame();
            var moved=new Frame(Projection.local(f,-retreat*256L,0),f.txQ30(),f.tzQ30(),f.walkY(),f.mirror());
            var current=ComponentComposer.frame(input,moved,input.site().globalSQ8()-retreat*256L);
            try {
                var a=new ComponentAssembly(current,localBounds(current),decay,palette);
                a.foundation(-30,-5,0,5,-20);
                for(int x=-30;x<0;x++) {
                    int height=x< -8?20:Math.max(2,(-x*20+7)/8);
                    a.box(x,-20,-5,x+1,-20+Math.min(12,height),5,Part.CORE,false);
                    if(height>12) a.box(x,-8,-4,x+1,-20+height,4,Part.CORE,false);
                }
                a.floor(-30,-3,-8,3,0,Part.FLOOR,"east");
                a.floor(-29,3,-26,5,0,Part.FLOOR,"north");
                a.route(new Point3i(-29,0,0),new Point3i(-9,0,0),new Point3i(-28,0,4));
                last=ComponentComposer.merge(current,List.of(a.finish(),new Ramp(decay,palette).compile(current)));
                if(!last.voxels().isEmpty()) return last;
            } catch(WallFailure failure) {
                if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT) throw failure;
            }
        }
        return last==null?ComponentAssembly.empty(input.site().id(),input.site().bounds()):
                ComponentAssembly.empty(input.site().id(),last.bounds());
    }
}
