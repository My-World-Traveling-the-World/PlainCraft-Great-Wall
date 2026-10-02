package local.plaincraft.greatwall.component;

import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** The frozen 48x48 foundation, ring, passage and internal ramps. No S3 gameplay. */
public final class FortPlaceholder implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;
    public FortPlaceholder(DecayField decay,PaletteBinding palette){this.decay=Objects.requireNonNull(decay);this.palette=Objects.requireNonNull(palette);}
    @Override public Bounds localBounds(ComponentInput input){Objects.requireNonNull(input);return new Bounds(-24,-33,-24,24,8,24);}
    @Override public ComponentVoxels compile(ComponentInput input) {
        boolean root=input.site().id().ordinal()==0;
        if(root) input=new ComponentInput(input.site(),input.stations(),input.supports(),new DecaySample(DecayState.INTACT,0,20),
                input.family(),input.redSandstone(),input.waters(),input.materials(),input.entranceEvidence());
        var entrance=Ramp.entrance(input,-2,-24,2,24);
        int groundFeet=entrance.map(GroundEntranceContract.Certificate::lowerFeetY).orElse(Integer.MIN_VALUE);
        if(groundFeet==Integer.MIN_VALUE) {
            if(root)throw new WallFailure(FailureCode.ROOT_NOT_FOUND,"fort0 ground road has no supported entrance");
            return ComponentAssembly.empty(input.site().id(),input.site().bounds());
        }
        int lower=groundFeet-input.site().frame().walkY(),height=-lower;
        int stairStart=Math.max(-19,10-height),stairEnd=stairStart+height;
        var a=new ComponentAssembly(input,localBounds(input),decay,palette);
        a.foundation(-24,-24,24,24,lower-1);
        a.floor(-24,-24,24,24,lower,Part.FLOOR,"north");
        // Ground courtyard is a floor, not a required void throughout the future wall strips.
        // Perimeter and stairs are built in a second, same-site assembly.
        var shell=new ComponentAssembly(input,localBounds(input),decay,palette);
        for(int z=-24;z<24;z++) for(int x=-24;x<24;x++) {
            if(x< -20 || x>=20 || z< -20 || z>=20) {
                shell.foundation(x,z,x+1,z+1,-1);
                shell.floor(x,z,x+1,z+1,0,Part.FLOOR,"north");
            }
        }
        // Only the central ground road reserves courtyard headroom below the ring.
        shell.floor(-2,-24,2,24,lower,Part.FLOOR,"north");
        shell.box(-2,lower,-24,2,lower+5,24,Part.AIR,true);
        for(int side:new int[]{-20,17}) {
            shell.foundation(side,stairStart-3,side+3,stairStart,lower-1);
            shell.floor(side,stairStart-3,side+3,stairStart,lower,Part.FLOOR,"north");
            for(int j=0;j<height;j++) {
                int feet=lower+j+1;
                shell.foundation(side,stairStart+j,side+3,stairStart+j+1,feet-1);
                shell.floor(side,stairStart+j,side+3,stairStart+j+1,feet,Part.STAIR,shell.facing(0,1));
            }
            int lo=side<0?-24:17,hi=side<0?-17:24;
            shell.foundation(lo,stairEnd,hi,stairEnd+3,-1);shell.floor(lo,stairEnd,hi,stairEnd+3,0,Part.FLOOR,"north");
            shell.route(new Point3i(side+1,lower,stairStart-2),new Point3i(side+1,0,stairEnd+1),new Point3i(side<0?-22:22,0,stairEnd+1));
        }
        shell.route(new Point3i(0,lower,-24),new Point3i(0,lower,23));
        var ground=a.finish();
        // Strip the courtyard's blanket voids; only shell's explicit routes must cut masonry.
        ground=ComponentAssembly.hashed(ground.id(),ground.bounds(),ground.voxels().stream()
                .filter(v->v.kind()!=IntentKind.OWNED_VOID).toList(),ground.blockEntities(),ground.spawnSlots(),ground.requiredRoutes());
        var result=ComponentComposer.merge(input,java.util.List.of(ground,shell.finish()));
        if(root && result.voxels().isEmpty()) throw new WallFailure(FailureCode.ROOT_NOT_FOUND,"fort0 footprint is not fully supported");
        return result;
    }
}
