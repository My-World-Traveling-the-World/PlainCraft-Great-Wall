package local.plaincraft.greatwall.component;

import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Ten by ten platform with four unlit, independently indexed fires sharing one beacon identity. */
public final class BeaconTower implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;
    public BeaconTower(DecayField decay,PaletteBinding palette) {
        this.decay=Objects.requireNonNull(decay); this.palette=Objects.requireNonNull(palette);
    }
    @Override public Bounds localBounds(ComponentInput input) {
        Objects.requireNonNull(input); return new Bounds(-5,-32,-5,5,7,5);
    }
    @Override public ComponentVoxels compile(ComponentInput input) {
        var a=new ComponentAssembly(input,localBounds(input),decay,palette);
        a.foundation(-5,-5,5,5,-16);
        a.box(-5,-16,-5,5,2,5,Part.CORE,false);
        a.box(-5,2,-5,5,6,5,Part.AIR,false);
        for(int z=-5;z<5;z++) for(int x=-5;x<5;x++)
            if (!(z >= -2 && z < 2 && (x < -3 || x >= 3)))
                a.put(x,1,z,Part.FLOOR,true,true,Map.of("type","top","waterlogged","false"));
        for(int x:new int[]{-5,4}) a.floor(x,-2,x+1,2,0,Part.FLOOR,"north");
        a.floor(-4,-2,-3,2,1,Part.STAIR,a.facing(1,0));
        a.floor(3,-2,4,2,1,Part.STAIR,a.facing(-1,0));
        a.floor(-3,-2,-2,2,2,Part.STAIR,a.facing(1,0));
        a.floor(2,-2,3,2,2,Part.STAIR,a.facing(-1,0));
        // Deck is built after stair wells so steps do not collide with an earlier mandatory floor.
        for(int z=-1;z<=0;z++) for(int x=-1;x<=0;x++) {
            int index=(z+1)*2+x+1;
            a.put(x,1,z,Part.HAY,true,false,Map.of("axis","y"));
            a.put(x,2,z,Part.CAMPFIRE,true,false,Map.of("lit","false","waterlogged","false","signal_fire","true","facing",a.facing(1,0)));
            a.entity(new Point3i(x,2,z),"minecraft:campfire",index,
                    Map.of("beaconWall",input.site().id().wallId(),"beaconOrdinal",Long.toString(input.site().id().ordinal())));
        }
        for(int n=-2;n<2;n++) {
            a.put(n,2,-2,Part.FENCE,false,false);
            a.put(-2,2,n,Part.FENCE,false,false); a.put(1,2,n,Part.FENCE,false,false);
            if(n!=-1 && n!=0) a.put(n,2,1,Part.FENCE,false,false);
        }
        for(int n=-5;n<5;n++) {
            if(n < -2 || n >= 2) {
                a.put(-5,2,n,Part.PARAPET,false,false); a.put(4,2,n,Part.PARAPET,false,false);
            }
            a.put(n,2,-5,Part.PARAPET,false,false); a.put(n,2,4,Part.PARAPET,false,false);
        }
        a.route(new Point3i(-5,0,0),new Point3i(-3,2,0),new Point3i(0,2,2),new Point3i(2,2,0),new Point3i(4,0,0));
        return a.finish();
    }
}
