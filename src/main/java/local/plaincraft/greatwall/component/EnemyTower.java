package local.plaincraft.greatwall.component;

import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Full 11 by 11 tower: two chambers, five stair runs and a reachable roof. */
public final class EnemyTower implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;
    public EnemyTower(DecayField decay, PaletteBinding palette) {
        this.decay=Objects.requireNonNull(decay); this.palette=Objects.requireNonNull(palette);
    }
    @Override public Bounds localBounds(ComponentInput input) {
        Objects.requireNonNull(input); return new Bounds(-5,-32,-5,6,8,6);
    }
    @Override public ComponentVoxels compile(ComponentInput input) {
        var a=new ComponentAssembly(input,localBounds(input),decay,palette);
        a.foundation(-5,-5,6,6,-20);
        a.box(-5,-20,-5,6,5,6,Part.CORE,false);
        for(int y=-20;y<5;y++) for(int n=-5;n<6;n++) {
            a.put(-5,y,n,Part.SKIN,false,false); a.put(5,y,n,Part.SKIN,false,false);
            a.put(n,y,-5,Part.SKIN,false,false); a.put(n,y,5,Part.SKIN,false,false);
        }
        chamber(a,-9); chamber(a,0);
        for(int z=-4;z<5;z++) for(int x=-4;x<5;x++)
            if (!(z < -2 && x >= -2 && x < 2)) a.floor(x,z,x+1,z+1,5,Part.ROOF,"north");
        a.floor(-4,-5,-2,-2,-20,Part.FLOOR,"north");
        for(int j=0;j<5;j++) a.floor(-2+j,-4,-1+j,-2,-19+j,Part.STAIR,a.facing(1,0));
        a.floor(3,-4,5,-2,-15,Part.FLOOR,"north");
        for(int j=0;j<5;j++) a.floor(3,-2+j,5,-1+j,-14+j,Part.STAIR,a.facing(0,1));
        a.floor(3,3,5,5,-10,Part.FLOOR,"north");
        for(int j=0;j<5;j++) a.floor(2-j,3,3-j,5,-9+j,Part.STAIR,a.facing(-1,0));
        a.floor(-4,3,-2,5,-5,Part.FLOOR,"north");
        for(int j=0;j<5;j++) a.floor(-4,2-j,-2,3-j,-4+j,Part.STAIR,a.facing(0,-1));
        a.floor(-4,-4,-2,-2,0,Part.FLOOR,"north");
        for(int j=0;j<5;j++) a.floor(-2+j,-4,-1+j,-2,1+j,Part.STAIR,a.facing(1,0));
        a.floor(3,-4,5,-2,5,Part.FLOOR,"north");
        // The lower end of the west run cannot also carry a horizontal W floor.
        // Preserve the four-wide facade opening; its W connector uses the last stair.
        a.floor(-5,-2,-4,2,0,Part.FLOOR,"north");
        a.floor(-4,-2,5,-1,0,Part.FLOOR,"north");
        a.floor(5,-2,6,2,0,Part.FLOOR,"north");
        a.floor(3,-1,5,2,0,Part.FLOOR,"north");
        for(int n=-5;n<6;n++) {
            a.put(-5,5,n,Part.PARAPET,false,false); a.put(5,5,n,Part.PARAPET,false,false);
            a.put(n,5,-5,Part.PARAPET,false,false); a.put(n,5,5,Part.PARAPET,false,false);
        }
        for(int y:new int[]{-7,2}) for(int dy=0;dy<2;dy++) {
            a.put(-5,y+dy,0,Part.AIR,false,false); a.put(5,y+dy,0,Part.AIR,false,false);
            a.put(0,y+dy,-5,Part.AIR,false,false); a.put(0,y+dy,5,Part.AIR,false,false);
        }
        a.route(new Point3i(-3,-20,-5),new Point3i(2,-9,1),new Point3i(0,0,0),
                new Point3i(3,5,-3),new Point3i(-5,0,-2),new Point3i(5,0,-2));
        a.spawn(new Point3i(-1,-9,1),0); a.spawn(new Point3i(1,-9,1),1); a.spawn(new Point3i(0,0,0),2);
        a.put(-1,-9,0,Part.CHEST,false,false,Map.of("facing",a.facing(0,1),"type","single","waterlogged","false"));
        a.entity(new Point3i(-1,-9,0),"minecraft:chest",0,Map.of("loot","enemy_tower"));
        return a.finish();
    }
    private static void chamber(ComponentAssembly a,int feet) {
        a.box(-2,feet,-2,3,feet+4,3,Part.AIR,false);
        for(int z=-2;z<3;z++) for(int x=-2;x<3;x++)
            a.put(x,feet-1,z,Part.FLOOR,true,true,Map.of("type","top","waterlogged","false"));
    }
}
