package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** A terminal-side taper connected to a complete ground exit; never creates a far-bank cap. */
public final class EndCap implements ComponentTemplate {
    private static final Bounds ENVELOPE=new Bounds(-30,-33,-15,30,3,15);
    private final DecayField decay;
    private final PaletteBinding palette;
    public EndCap(DecayField decay,PaletteBinding palette) {this.decay=Objects.requireNonNull(decay);this.palette=Objects.requireNonNull(palette);}
    @Override public Bounds localBounds(ComponentInput input) {Objects.requireNonNull(input);return ENVELOPE;}
    /** Every retry samples the immutable station ribbon, including reverse-side caps. */
    public static List<SiteRecord> candidates(SiteRecord site,List<Station> stations) {
        if(stations.size()<2)throw new IllegalArgumentException("cap needs station ribbon");
        Station[] original=edge(stations,site.globalSQ8());
        long dx=original[1].point().xQ8()-original[0].point().xQ8();
        long dz=original[1].point().zQ8()-original[0].point().zQ8();
        int direction=dx*site.frame().txQ30()+dz*site.frame().tzQ30()>=0?1:-1;
        var result=new java.util.ArrayList<SiteRecord>();
        for(int retreat=0;retreat<=128;retreat+=4) {
            long s=site.globalSQ8()-direction*retreat*256L;
            if(s<stations.getFirst().globalSQ8()||s>stations.getLast().globalSQ8())break;
            Station[] pair=edge(stations,s);var a=pair[0];var b=pair[1];
            long length=b.globalSQ8()-a.globalSQ8(),offset=s-a.globalSQ8();
            dx=b.point().xQ8()-a.point().xQ8();dz=b.point().zQ8()-a.point().zQ8();
            var point=new Point2q(a.point().xQ8()+FixedMath.mulDiv(dx,offset,length),a.point().zQ8()+FixedMath.mulDiv(dz,offset,length));
            int w=a.walkY()+(int)FixedMath.roundDiv((long)(b.walkY()-a.walkY())*offset,length);
            double norm=StrictMath.hypot(dx,dz);
            var frame=new Frame(point,direction*(int)StrictMath.round(dx*(1L<<30)/norm),
                    direction*(int)StrictMath.round(dz*(1L<<30)/norm),w,site.frame().mirror());
            Bounds bounds=Rasterizer.worldBounds(frame,ENVELOPE);
            result.add(new SiteRecord(site.id(),s,frame,bounds,site.state(),site.absorbedBy(),site.reason()));
        }
        return List.copyOf(result);
    }
    private static Station[] edge(List<Station> stations,long s) {
        for(int i=1;i<stations.size();i++)if(s>=stations.get(i-1).globalSQ8()&&s<=stations.get(i).globalSQ8())
            return new Station[]{stations.get(i-1),stations.get(i)};
        throw new IllegalArgumentException("cap outside station ribbon");
    }
    @Override public ComponentVoxels compile(ComponentInput input) {
        ComponentVoxels last=null;
        for(var candidate:candidates(input.site(),input.stations())) {
            var current=ComponentComposer.frame(input,candidate.frame(),candidate.globalSQ8());
            try {
                last=compileAt(current);
                if(!last.voxels().isEmpty()) return last;
            } catch(WallFailure failure) {
                if(failure.code()!=FailureCode.OWNERSHIP_CONFLICT) throw failure;
            }
        }
        return last==null?ComponentAssembly.empty(input.site().id(),input.site().bounds()):
                ComponentAssembly.empty(input.site().id(),last.bounds());
    }
    /** Compile one already-probed candidate; the realization planner owns retry publication. */
    public ComponentVoxels compileAt(ComponentInput current) {
        return compileAt(current,result->true);
    }
    /** Test every orientation at this retreat against the shared interval certificate. */
    public ComponentVoxels compileAt(ComponentInput current,Predicate<ComponentVoxels> admissible) {
        Objects.requireNonNull(admissible);
        for(int candidate=0;candidate<4;candidate++)try {
            var result=compileVariant(current,candidate);
            if(!result.voxels().isEmpty()&&admissible.test(result))return result;
        }catch(WallFailure e){if(e.code()!=FailureCode.OWNERSHIP_CONFLICT)throw e;}
        return ComponentAssembly.empty(current.site().id(),Rasterizer.worldBounds(current.site().frame(),ENVELOPE));
    }
    private ComponentVoxels compileVariant(ComponentInput input,int candidate) {
                var f=input.site().frame();boolean positiveX=candidate>=2;
                var bodyFrame=new Frame(f.center(),f.txQ30(),f.tzQ30(),f.walkY(),f.mirror()^(candidate%2==1));
                var current=ComponentComposer.frame(input,bodyFrame,input.site().globalSQ8());
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
                if(positiveX) {
                    a.foundation(-29,3,29,6,-1);
                    a.floor(-29,3,29,6,0,Part.FLOOR,"east");
                    a.route(new Point3i(-28,0,3),new Point3i(28,0,4));
                }
                var rampFrame=positiveX?new Frame(f.center(),-bodyFrame.txQ30(),-bodyFrame.tzQ30(),f.walkY(),!bodyFrame.mirror()):bodyFrame;
                var ramp=new Ramp(decay,palette).compileInFrame(ComponentComposer.frame(current,rampFrame,current.site().globalSQ8()));
                var body=a.finish();
                var result=ComponentComposer.merge(current,List.of(body,ramp));
                if(!result.voxels().isEmpty()&&!new local.plaincraft.greatwall.decay.SupportGraph().reachable(result,
                        body.requiredRoutes().getFirst().getFirst(),ramp.requiredRoutes().getFirst().getFirst()))
                    return ComponentAssembly.empty(current.site().id(),result.bounds());
                return result;
    }
}
