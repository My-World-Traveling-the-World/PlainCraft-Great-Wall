package local.plaincraft.greatwall.persistence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.diagnostics.BoundedByteArrayOutputStream;

/** Bounded canonical production capture metadata, separate from disposable segment caches. */
public final class EvidenceCodec {
    private static final Comparator<Point3i> POS=Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private EvidenceCodec() {}
    public static byte[] coverage(Coverage c) {
        try {
            var bytes=new BoundedByteArrayOutputStream(8192);var out=new DataOutputStream(bytes);
            string(out,c.chunk().dimension());out.writeInt(c.chunk().x());out.writeInt(c.chunk().z());
            out.writeInt(c.tileBases().size());for(int base:c.tileBases())out.writeInt(base);
            bounds(out,c.readBounds());out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new AssertionError(impossible);}
    }
    public static Coverage readCoverage(byte[] bytes)throws IOException {
        if(bytes.length>8192)throw new IOException("coverage exceeds limit");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            var key=new ChunkKey(string(in),in.readInt(),in.readInt());
            int count=count(in,64);var bases=new ArrayList<Integer>();for(int i=0;i<count;i++)bases.add(in.readInt());
            var b=bounds(in);if(in.read()!=-1)throw new IOException("coverage trailing bytes");
            return new Coverage(key,bases,b,DeterministicHash.sha256(bytes));
        }catch(IllegalArgumentException invalid){throw new IOException("invalid coverage",invalid);}
    }
    public static byte[] metadata(CaptureMetadata value) {
        try {
            var bytes=new BoundedByteArrayOutputStream(8*1024*1024);var out=new DataOutputStream(bytes);var c=value.coverage();
            string(out,c.chunk().dimension());out.writeInt(c.chunk().x());out.writeInt(c.chunk().z());
            out.writeInt(c.tileBases().size());for(int base:c.tileBases())out.writeInt(base);
            bounds(out,c.readBounds());string(out,c.hash());
            var columns=value.columns().stream().sorted(Comparator.comparingInt(ColumnEvidence::z).thenComparingInt(ColumnEvidence::x)).toList();
            out.writeInt(columns.size());for(var col:columns){out.writeInt(col.x());out.writeInt(col.z());out.writeInt(col.highestNonPlantFeet());out.writeBoolean(col.upperCensored());}
            var boxes=value.protectedStructures().stream().sorted(Comparator.comparingInt(Bounds::minY).thenComparingInt(Bounds::minZ)
                    .thenComparingInt(Bounds::minX).thenComparingInt(Bounds::maxYExclusive).thenComparingInt(Bounds::maxZExclusive).thenComparingInt(Bounds::maxXExclusive)).toList();
            out.writeInt(boxes.size());for(var box:boxes)bounds(out,box);
            points(out,value.blockEntities());points(out,value.immutableBlocks());out.flush();
            return bytes.toByteArray();
        } catch(IOException impossible){throw new AssertionError(impossible);}
    }
    public static CaptureMetadata readMetadata(byte[] bytes) throws IOException {
        if(bytes.length>8*1024*1024)throw new IOException("metadata length");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            var key=new ChunkKey(string(in),in.readInt(),in.readInt());int size=count(in,64);
            var bases=new ArrayList<Integer>();for(int i=0;i<size;i++)bases.add(in.readInt());
            var coverage=new Coverage(key,bases,bounds(in),string(in));
            int n=count(in,256);if(n!=256)throw new IOException("capture needs all 256 column summaries");
            var columns=new ArrayList<ColumnEvidence>();var seen=new HashSet<Long>();
            for(int i=0;i<n;i++){
                var c=new ColumnEvidence(in.readInt(),in.readInt(),in.readInt(),in.readBoolean());
                if(Math.floorDiv(c.x(),16)!=key.x()||Math.floorDiv(c.z(),16)!=key.z()||!seen.add((long)c.x()<<32|(c.z()&0xffffffffL)))
                    throw new IOException("foreign or duplicate column summary");
                columns.add(c);
            }
            n=count(in,4096);var boxes=new ArrayList<Bounds>();for(int i=0;i<n;i++)boxes.add(bounds(in));
            var entities=points(in,key);var immutable=points(in,key);
            if(in.read()!=-1)throw new IOException("metadata trailing bytes");
            return new CaptureMetadata(coverage,columns,boxes,entities,immutable,DeterministicHash.sha256(bytes));
        }catch(IllegalArgumentException invalid){throw new IOException("invalid capture metadata",invalid);}
    }
    public static CaptureMetadata hashed(CaptureMetadata m) {
        return new CaptureMetadata(m.coverage(),m.columns(),m.protectedStructures(),m.blockEntities(),m.immutableBlocks(),
                DeterministicHash.sha256(metadata(m)));
    }
    private static void points(DataOutputStream out,Set<Point3i> points)throws IOException {
        out.writeInt(points.size());for(var p:points.stream().sorted(POS).toList()){out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}
    }
    private static Set<Point3i> points(DataInputStream in,ChunkKey key)throws IOException {
        int n=count(in,655360);var set=new HashSet<Point3i>();
        for(int i=0;i<n;i++){
            var p=new Point3i(in.readInt(),in.readInt(),in.readInt());
            if(Math.floorDiv(p.x(),16)!=key.x()||Math.floorDiv(p.z(),16)!=key.z()||!set.add(p))throw new IOException("foreign or duplicate protected position");
        }return Set.copyOf(set);
    }
    private static void bounds(DataOutputStream out,Bounds b)throws IOException {
        out.writeInt(b.minX());out.writeInt(b.minY());out.writeInt(b.minZ());out.writeInt(b.maxXExclusive());out.writeInt(b.maxYExclusive());out.writeInt(b.maxZExclusive());
    }
    private static Bounds bounds(DataInputStream in)throws IOException{return new Bounds(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());}
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("count out of bounds");return n;}
    private static void string(DataOutputStream out,String s)throws IOException{
        if(s.length()>4096)throw new IOException("string too long");
        byte[] b=s.getBytes(StandardCharsets.UTF_8);if(b.length>4096)throw new IOException("string too long");out.writeInt(b.length);out.write(b);
    }
    private static String string(DataInputStream in)throws IOException{
        int n=count(in,4096);byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();
    }
}
