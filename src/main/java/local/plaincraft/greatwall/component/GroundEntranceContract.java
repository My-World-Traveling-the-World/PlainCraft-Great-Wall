package local.plaincraft.greatwall.component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;

/** One pure entrance predicate for natural root previews and immutable construction evidence. */
public final class GroundEntranceContract {
    public enum Source { ANALYTIC_FIXTURE, NATURAL_REFERENCE, DURABLE_T0 }
    public enum Face { MIN_X, MAX_X, MIN_Z, MAX_Z }
    public record Corridor(int minX,int minZ,int maxX,int maxZ,int width,List<Face> exterior) {
        public Corridor {
            if(minX>=maxX||minZ>=maxZ||width<2)throw new IllegalArgumentException("finite declared entrance required");
            exterior=List.copyOf(exterior);
            if(exterior.isEmpty())throw new IllegalArgumentException("external entrance face required");
        }
        public boolean contains(Point3i p){return p.x()>=minX&&p.x()<maxX&&p.z()>=minZ&&p.z()<maxZ;}
        public static Corridor fort(){return new Corridor(-2,-24,2,24,3,List.of(Face.MIN_Z,Face.MAX_Z));}
        public static Corridor forSite(SiteKind kind,int x0,int z0,int x1,int z1){
            if(kind==SiteKind.RAMP||kind==SiteKind.END_CAP)
                return new Corridor(x0,z0,x1,z1,3,List.of(Face.MIN_X,Face.MAX_Z));
            if(kind==SiteKind.ENEMY_TOWER){
                int x=x0+x1,z=z0+z1;
                var face=Math.abs(x)>Math.abs(z)?(x<0?Face.MIN_X:Face.MAX_X):(z<0?Face.MIN_Z:Face.MAX_Z);
                return new Corridor(x0,z0,x1,z1,2,List.of(face));
            }
            return new Corridor(x0,z0,x1,z1,3,List.of(Face.MIN_Z,Face.MAX_Z));
        }
    }
    public record Evidence(Source source,DimensionBounds bounds,Map<Point3i,SurfaceProbe.ColumnView> columns) {
        public Evidence {Objects.requireNonNull(source);Objects.requireNonNull(bounds);columns=EntranceColumns.copyOf(columns);}
        /** Compatibility input for the analytic component fixtures, never a durable production proof. */
        public static Evidence analytic(Map<Point3i,ProbeResult> supports){
            var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();
            supports.forEach((p,v)->columns.put(new Point3i(p.x(),0,p.z()),new ClearSkyColumn(v)));
            int low=supports.keySet().stream().mapToInt(Point3i::y).min().orElse(84);
            int high=supports.keySet().stream().mapToInt(Point3i::y).max().orElse(84);
            return new Evidence(Source.ANALYTIC_FIXTURE,
                    new DimensionBounds(Math.min(-64,low-64),Math.max(320,high+64),30_000_000),columns);
        }
    }
    public record Certificate(Frame frame,int lowerFeetY,List<List<Point3i>> approaches,
            String evidenceHash,Source source) {
        public Certificate {
            approaches=approaches.stream().map(List::copyOf).toList();
            local.plaincraft.greatwall.model.WallTypes.requireHash(evidenceHash);Objects.requireNonNull(source);
        }
    }
    /** REFERENCE supplies the top solid; no invented states below that evidence boundary. */
    public record ClearSkyColumn(ProbeResult probe) implements SurfaceProbe.ColumnView {
        public ClearSkyColumn {Objects.requireNonNull(probe);}
        public String state(int y){
            if(y<probe.groundTopY())throw new IllegalArgumentException("no below-surface block proof");
            return "minecraft:air{}:fluid=minecraft:empty:be=false";
        }
        public boolean solidSupport(int y){return probe.kind()==ProbeKind.SUPPORTED
                &&y>=probe.groundTopY()-3&&y<probe.groundTopY();}
        public boolean protectedAt(int y){return probe.kind()!=ProbeKind.SUPPORTED;}
        public boolean water(int y){return probe.waterTopY().isPresent();}
        public boolean airOrReplaceablePlant(int y){return y>=probe.groundTopY();}
        public int highestNonPlantFeet(){return probe.groundTopY();}
        public String hash(){return probe.evidenceHash();}
    }
    private static final int[][] SIDES={{0,-1},{1,0},{0,1},{-1,0}};
    private static final Comparator<Point3i> ORDER=Comparator.comparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private GroundEntranceContract(){}

    public static List<Point3i> columns(Frame frame,Corridor corridor){
        var b=Rasterizer.worldBounds(frame,new Bounds(corridor.minX(),-33,corridor.minZ(),corridor.maxX(),3,corridor.maxZ()));
        var points=new TreeSet<Point3i>(ORDER);
        long visits=(long)(b.maxXExclusive()-b.minX())*(b.maxZExclusive()-b.minZ());
        if(visits>4096)throw new IllegalArgumentException("entrance exceeds finite template window");
        for(int z=b.minZ();z<b.maxZExclusive();z++)for(int x=b.minX();x<b.maxXExclusive();x++){
            var p=new Point3i(x,frame.walkY(),z);
            if(!corridor.contains(Rasterizer.localCell(frame,p)))continue;
            points.add(p);
            for(var d:SIDES)points.add(new Point3i(x+d[0],frame.walkY(),z+d[1]));
        }
        return List.copyOf(points);
    }
    public static Optional<Certificate> inspect(Frame frame,Corridor corridor,
            Map<Point3i,ProbeResult> supports,Evidence evidence){
        Objects.requireNonNull(frame);Objects.requireNonNull(corridor);
        Objects.requireNonNull(supports);Objects.requireNonNull(evidence);
        long side=Math.addExact(Math.addExact((long)corridor.maxX()-corridor.minX(),
                (long)corridor.maxZ()-corridor.minZ()),4L);
        long work=Math.addExact(4096L,Math.multiplyExact(384L,Math.multiplyExact(side,side)));
        local.plaincraft.greatwall.diagnostics.CompiledAllocation.charge(work);
        long keep=0;
        try{
            var result=inspectAdmitted(frame,corridor,supports,evidence);
            if(result.isPresent())keep=4096L+256L*result.get().approaches().size();
            return result;
        }finally{local.plaincraft.greatwall.diagnostics.CompiledAllocation.discard(work-keep);}
    }
    private static Optional<Certificate> inspectAdmitted(Frame frame,Corridor corridor,
            Map<Point3i,ProbeResult> supports,Evidence evidence){
        lastRejection.remove();
        var inside=new TreeMap<Point3i,Point3i>(ORDER);
        int lower=Integer.MIN_VALUE,w=frame.walkY(),g=Math.subtractExact(w,20);
        for(var p:columns(frame,corridor)){
            var local=Rasterizer.localCell(frame,p);
            if(!corridor.contains(local))continue;
            var probe=supports.get(p);
            if(!valid(probe,w,g))return reject("column-invalid at "+p+" local="+local+" walkY="+w+" g="+g
                    +" probe="+describe(probe));
            inside.put(p,local);lower=Math.max(lower,probe.groundTopY());
        }
        if(inside.isEmpty()||lower<Math.subtractExact(w,32)||lower>=w
                ||lower<evidence.bounds().minY()||lower+2>evidence.bounds().maxYExclusive()) {
            return reject("lower-out-of-range inside="+inside.size()+" lower="
                    +(inside.isEmpty()?null:lower)+" walkY="+w+" allowed=["
                    +Math.subtractExact(w,32)+","+w+") evidenceBounds="+evidence.bounds());
        }
        // The existing templates have a level entrance floor: natural peaks cannot be carved to fit it.
        for(var p:inside.keySet())if(!clear(evidence,p,lower,lower+2)) {
            return reject("floor-blocked at "+p+" range=["+lower+","+(lower+2)+")");
        }
        for(var face:corridor.exterior()){
            int declared=face==Face.MIN_Z||face==Face.MAX_Z?corridor.maxX()-corridor.minX():corridor.maxZ()-corridor.minZ();
            if(declared<corridor.width())continue;
            var expected=new TreeSet<Point3i>(ORDER);
            var lanes=new TreeMap<Point3i,List<Point3i>>(ORDER);
            for(var entry:inside.entrySet()){
                var p=entry.getKey();var local=entry.getValue();
                for(var d:SIDES){
                    var outside=new Point3i(p.x()+d[0],w,p.z()+d[1]);
                    var outsideLocal=Rasterizer.localCell(frame,outside);
                    if(!crosses(face,corridor,local,outsideLocal))continue;
                    expected.add(p);
                    var probe=supports.get(outside);
                    if(!valid(probe,w,g)||Math.abs((long)probe.groundTopY()-lower)>1)continue;
                    int head=Math.max(lower,probe.groundTopY())+2;
                    if(!clear(evidence,outside,probe.groundTopY(),head)||!clear(evidence,p,lower,head))continue;
                    lanes.computeIfAbsent(p,k->new ArrayList<>()).add(
                            new Point3i(outside.x(),probe.groundTopY(),outside.z()));
                }
            }
            // Local indices can be absent after rotation. Require the whole actual declared portal,
            // not a invented consecutive-index band or just a single convenient outside column.
            if(!expected.isEmpty()&&lanes.keySet().equals(expected)){
                var outsideOwners=new TreeMap<Point3i,Point3i>(ORDER);
                boolean complete=true;
                for(var p:expected)if(!match(p,lanes,outsideOwners,new HashSet<>())){complete=false;break;}
                if(!complete) {
                    return reject("lane-unmatched face="+face+" expected="+expected.size()
                           +" matched="+lanes.size()+" corridor="+corridor);
                }
                var insideOrigins=new TreeMap<Point3i,Point3i>(ORDER);
                outsideOwners.forEach((outside,insidePoint)->insideOrigins.put(insidePoint,outside));
                var approaches=new ArrayList<List<Point3i>>();
                for(var p:expected)approaches.add(List.of(insideOrigins.get(p),new Point3i(p.x(),lower,p.z())));
                var hashes=new TreeSet<String>();
                for(var p:inside.keySet()){
                    var column=evidence.columns().get(new Point3i(p.x(),0,p.z()));
                    if(column==null)return reject("evidence-column-missing (inside) at "+p
                            +" source="+evidence.source());
                    hashes.add(p+":"+column.hash());
                }
                for(var lane:approaches)for(var p:lane){
                    var column=evidence.columns().get(new Point3i(p.x(),0,p.z()));
                    if(column==null)return reject("evidence-column-missing (lane) at "+p
                            +" source="+evidence.source());
                    hashes.add(p+":"+column.hash());
                }
                String hash=DeterministicHash.sha256((evidence.source()+"\n"+frame+"\n"+corridor+"\n"
                        +lower+"\n"+hashes+"\n"+approaches).getBytes(StandardCharsets.UTF_8));
                return Optional.of(new Certificate(frame,lower,approaches,hash,evidence.source()));
            }
        }
        // Every declared face was either too narrow to carry the portal or had an empty
        // expected set. Report the corridor so it is clear which faces were even considered.
        return reject("no-exterior-face-qualified corridor="+corridor+" walkY="+w+" lower="
                +((inside.isEmpty())?null:lower)+" inside="+inside.size());
    }

    /** Renders a probe for a rejection message without inventing values for a missing one. */
    private static String describe(ProbeResult probe) {
        if (probe == null) return "absent";
        return probe.kind()+" groundTopY="+probe.groundTopY()+" drop="+probe.drop()
                +" water="+(probe.waterTopY().isPresent()?probe.waterTopY().getAsInt():"none");
    }
    private static boolean match(Point3i inside,Map<Point3i,List<Point3i>> choices,
            Map<Point3i,Point3i> outsideOwners,Set<Point3i> seen){
        for(var outside:choices.getOrDefault(inside,List.of())){
            if(!seen.add(outside))continue;
            var previous=outsideOwners.get(outside);
            if(previous==null||match(previous,choices,outsideOwners,seen)){
                outsideOwners.put(outside,inside);return true;
            }
        }
        return false;
    }

    /**
     * Why the last admission test on this thread returned empty.
     *
     * <h2>Why this exists</h2>
     * {@code inspect} answers a single boolean-ish question and returns empty for six
     * distinct reasons: a column with no support, a lower bound out of range, a blocked
     * level floor, an incomplete portal, an unmatched lane, or missing evidence. When the
     * root fort then reports "no supported entrance", the log says which sites failed but
     * never which test rejected them - so every attempt to fix it has been guesswork, and
     * a real session showed the pre-screen accepting an entrance the build then rejected
     * with nothing recorded about the difference.
     *
     * <p>Thread-local rather than a field: admission runs on lane threads and on the pump,
     * and a shared field would interleave two sites' reasons into one another. Callers read
     * it immediately after the call that produced it, on the same thread.
     */
    private static final ThreadLocal<String> lastRejection = new ThreadLocal<>();

    /** The reason the most recent admission test on this thread failed, or null if it did not. */
    public static String lastRejectionReason() { return lastRejection.get(); }

    /** Clears the recorded reason; call before a test whose outcome will be reported. */
    public static void clearRejection() { lastRejection.remove(); }

    private static Optional<Certificate> reject(String reason) {
        lastRejection.set(reason);
        return Optional.empty();
    }

    private static boolean valid(ProbeResult p,int w,int g){
        return p!=null&&p.kind()==ProbeKind.SUPPORTED&&p.drop()>=0&&p.drop()<=12
                &&p.groundTopY()>=g-12&&p.groundTopY()<w&&p.waterTopY().isEmpty();
    }
    private static boolean clear(Evidence evidence,Point3i p,int from,int to){
        var column=evidence.columns().get(new Point3i(p.x(),0,p.z()));
        if(column==null)return false;
        for(int y=from;y<to;y++){
            if(column.protectedAt(y)||column.water(y)||!column.airOrReplaceablePlant(y))return false;
            String state=column.state(y);int brace=state.indexOf('{');
            String id=brace<0?state:state.substring(0,brace);
            // The compiler's existing negative-space whitelist is not expanded by this certificate.
            if(!switch(id){case "minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:short_grass",
                    "minecraft:tall_grass","minecraft:fern","minecraft:large_fern","minecraft:dead_bush","minecraft:snow"->true;
                    default->false;})return false;
        }
        return true;
    }
    private static boolean crosses(Face face,Corridor c,Point3i inside,Point3i outside){
        return switch(face){
            case MIN_X->inside.x()==c.minX()&&outside.x()<c.minX();
            case MAX_X->inside.x()==c.maxX()-1&&outside.x()>=c.maxX();
            case MIN_Z->inside.z()==c.minZ()&&outside.z()<c.minZ();
            case MAX_Z->inside.z()==c.maxZ()-1&&outside.z()>=c.maxZ();
        };
    }
}
