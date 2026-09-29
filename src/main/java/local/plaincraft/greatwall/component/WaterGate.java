package local.plaincraft.greatwall.component;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteBinding;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Source-water-preserving narrow-river crossing; width 50 is never admitted here. */
public final class WaterGate implements ComponentTemplate {
    private record Span(int from,int to) {}
    private final DecayField decay;
    private final PaletteBinding palette;
    public WaterGate(DecayField decay,PaletteBinding palette){this.decay=Objects.requireNonNull(decay);this.palette=Objects.requireNonNull(palette);}
    @Override public Bounds localBounds(ComponentInput input) {
        WaterEvidence w=evidence(input);
        int from=FixedMath.floorBlock(w.crossing().fromQ8()-input.site().globalSQ8())-8;
        int to=FixedMath.floorBlock(w.crossing().toQ8()-input.site().globalSQ8()+255)+8;
        return new Bounds(from,-32,-6,to,3,6);
    }
    @Override public ComponentVoxels compile(ComponentInput input) {
        var evidence=evidence(input); Bounds b=localBounds(input); int w=input.site().frame().walkY();
        if(evidence.classification()!=WaterClass.NARROW || evidence.channelWidthQ8()>=50L*256
                || evidence.crossingSpanQ8()>128L*256 || evidence.waterTopY()+4>w-1)
            return ComponentAssembly.empty(input.site().id(),Rasterizer.worldBounds(input.site().frame(),b));
        for(var reference:evidence.piers()) {
            var p=input.supports().get(new Point3i(reference.x(),w,reference.z()));
            if(p==null || p.kind()!=ProbeKind.WATER || p.waterTopY().isEmpty() || p.bedTopY().isEmpty()
                    || Math.abs(p.waterTopY().getAsInt()-evidence.waterTopY())>1
                    || Math.abs(p.bedTopY().getAsInt()-reference.y())>4 || w-20-p.bedTopY().getAsInt()>12)
                return ComponentAssembly.empty(input.site().id(),Rasterizer.worldBounds(input.site().frame(),b));
        }
        int from=FixedMath.floorBlock(evidence.crossing().fromQ8()-input.site().globalSQ8());
        int to=FixedMath.floorBlock(evidence.crossing().toQ8()-input.site().globalSQ8()+255);
        int water=evidence.waterTopY()-w;
        var a=new ComponentAssembly(input,b,decay,palette);
        a.foundation(b.minX(),-5,b.maxXExclusive(),5,-16);
        a.box(b.minX(),-16,-5,b.maxXExclusive(),-8,5,Part.CORE,false);
        a.box(b.minX(),-8,-4,b.maxXExclusive(),0,4,Part.CORE,false);
        a.floor(b.minX(),-3,b.maxXExclusive(),3,0,Part.FLOOR,"east");
        var spans=new ArrayList<Span>();spans.add(new Span(-2,2));
        spans.addAll(sideSpans(to));
        for(var s:sideSpans(-from))spans.add(new Span(-s.to(),-s.from()));
        for(int x=Math.min(from,-4);x<Math.max(to,4);x++) {
            Span span=null;for(var s:spans)if(x>=s.from()&&x<s.to()){span=s;break;}
            if(span==null) {a.box(x,-32,-5,x+1,0,5,Part.ARCH,true);continue;}
            double offset=(x+0.5-(span.from()+span.to())/2.0)/((span.to()-span.from())/2.0);
            int arc=(int)StrictMath.floor(2*StrictMath.sqrt(Math.max(0,1-offset*offset)));
            int ceiling=Math.min(-2,water+3+arc);
            for(int z=-6;z<6;z++) for(int y=b.minY();y<water;y++)
                a.put(x,y,z,Part.WATER,false,false,Map.of("level","0"));
            a.box(x,water,-5,x+1,ceiling,5,Part.AIR,true);
            a.box(x,ceiling,-5,x+1,ceiling+1,5,Part.ARCH,true);
        }
        for(var span:spans)a.span(new Bounds(span.from(),water+3,-5,span.to(),3,5),
                new Bounds(span.from()-2,-32,-5,span.from(),3,5),new Bounds(span.to(),-32,-5,span.to()+2,3,5));
        a.route(new Point3i(b.minX()+1,0,0),new Point3i(b.maxXExclusive()-2,0,0));
        return a.finish();
    }
    private static List<Span> sideSpans(int bank) {
        var result=new ArrayList<Span>();int cursor=4;
        while(bank-cursor>6) {
            int remaining=bank-cursor;
            // A seven/eight-column tail cannot be two >=3 spans plus a two-column pier.
            // Absorb its final two columns into the existing bank apron, not a >6 arch.
            if(remaining<=8){result.add(new Span(cursor,bank-2));return List.copyOf(result);}
            result.add(new Span(cursor,cursor+4));cursor+=6;
        }
        if(bank-cursor>=3)result.add(new Span(cursor,bank));
        return List.copyOf(result);
    }
    private static WaterEvidence evidence(ComponentInput input) {
        Objects.requireNonNull(input);
        if(input.waters().size()!=1) throw new IllegalArgumentException("water gate needs one complete, deduplicated crossing");
        return input.waters().getFirst();
    }
}
