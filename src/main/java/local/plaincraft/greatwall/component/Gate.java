package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;

/** One frozen four-wide arch and two ground-supported ramps. Also used by later backfill. */
public final class Gate implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;
    public Gate(DecayField decay,PaletteBinding palette) {this.decay=Objects.requireNonNull(decay);this.palette=Objects.requireNonNull(palette);}
    @Override public Bounds localBounds(ComponentInput input) { Objects.requireNonNull(input);return new Bounds(-30,-33,-15,30,4,15); }
    @Override public ComponentVoxels compile(ComponentInput input) {return compileAtGround(input,input.site().frame().walkY()-20);}
    public ComponentVoxels compileAtGround(ComponentInput input,int frozenGroundY) {
        int w=input.site().frame().walkY(),ground=frozenGroundY-w;
        if((long)frozenGroundY+7>w-1 || ground< -32) throw new IllegalArgumentException("gate height outside frozen arch envelope");
        var a=new ComponentAssembly(input,localBounds(input),decay,palette);
        a.foundation(-30,-5,30,5,-16);
        a.box(-30,-16,-5,30,-8,5,Part.CORE,false);
        a.box(-30,-8,-4,30,0,4,Part.CORE,false);
        a.floor(-30,-3,30,3,0,Part.FLOOR,"east");
        for(int x=-30;x<30;x++) {a.put(x,0,-4,Part.PARAPET,false,false);a.put(x,0,3,Part.PARAPET,false,false);}
        // Ground route is planned before arch masonry and never clears foreign world blocks.
        a.floor(-2,-5,2,5,ground,Part.FLOOR,"north");
        a.box(-2,ground,-5,2,ground+5,5,Part.AIR,true);
        a.box(-1,ground+5,-5,1,ground+6,5,Part.AIR,true);
        a.box(-4,ground,-5,-2,ground+7,5,Part.ARCH,true);
        a.box(2,ground,-5,4,ground+7,5,Part.ARCH,true);
        a.box(-2,ground+6,-5,2,ground+8,5,Part.ARCH,true);
        a.floor(-29,3,-26,5,0,Part.FLOOR,"north");
        a.floor(-29,-5,-26,-3,0,Part.FLOOR,"north");
        a.route(new Point3i(0,ground,-5),new Point3i(0,ground,4));
        a.route(new Point3i(-29,0,0),new Point3i(29,0,0));
        var parts=new ArrayList<ComponentVoxels>(); parts.add(a.finish());
        var ramp=new Ramp(decay,palette);
        parts.add(ramp.compileBetween(input,frozenGroundY,w));
        var f=input.site().frame();
        parts.add(ramp.compileBetween(ComponentComposer.frame(input,new Frame(f.center(),f.txQ30(),f.tzQ30(),w,!f.mirror()),
                input.site().globalSQ8()),frozenGroundY,w));
        return ComponentComposer.merge(input,parts);
    }
}
