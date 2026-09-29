package local.plaincraft.greatwall.persistence;

import java.io.*;
import java.util.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;

/** Versioned, bounded binary evidence; never Java serialization or locale-dependent text. */
public final class GroundExitCodec {
    public static final int LIMIT = 8 * 1024 * 1024;
    private GroundExitCodec() {}
    public static byte[] encode(GroundExitDecision d) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(0x50474531); out.writeInt(1);
            out.writeUTF(d.key().recipeHash()); out.writeUTF(d.key().dimension()); range(out,d.key().range());
            exit(out,d.left()); out.writeBoolean(d.right().isPresent());
            if(d.right().isPresent()) exit(out,d.right().get());
            out.writeLong(d.terminalSQ8()); out.writeUTF(d.t0Hash());
            out.writeInt(d.breaks().size());
            for(var b:d.breaks()) { range(out,b.range());out.writeUTF(b.reason().name());out.writeUTF(b.scope().name()); }
            out.writeInt(d.exclusions().size());for(var r:d.exclusions())range(out,r);
            out.writeInt(d.caps().size());for(var c:d.caps()) {
                site(out,c.site());out.writeLong(c.edge().sQ8());out.writeBoolean(c.edge().reverse());range(out,c.edge().allowed());
                out.writeLong(c.actualSQ8());out.writeUTF(c.componentHash());
            }
            out.flush(); if(bytes.size()>LIMIT)throw new IllegalArgumentException("exit certificate exceeds 8MiB");
            return bytes.toByteArray();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public static GroundExitDecision hashed(GroundExitDecision d) {
        return new GroundExitDecision(d.key(),d.left(),d.right(),d.terminalSQ8(),d.breaks(),d.exclusions(),d.caps(),d.t0Hash(),
                DeterministicHash.sha256(encode(d)));
    }
    public static GroundExitDecision decode(byte[] bytes) throws IOException {
        if(bytes.length>LIMIT)throw new IOException("exit certificate exceeds 8MiB");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x50474531||in.readInt()!=1)throw new IOException("exit certificate header");
            var key=new Key(in.readUTF(),in.readUTF(),range(in));var left=exit(in);
            Optional<Exit> right=bool(in)?Optional.of(exit(in)):Optional.empty();long terminal=in.readLong();String t0=in.readUTF();
            var breaks=new ArrayList<BreakInterval>();int n=count(in);
            for(int i=0;i<n;i++)breaks.add(new BreakInterval(range(in),BreakReason.valueOf(in.readUTF()),BreakScope.valueOf(in.readUTF())));
            var exclusions=new ArrayList<ArcInterval>();n=count(in);for(int i=0;i<n;i++)exclusions.add(range(in));
            var caps=new ArrayList<Cap>();n=count(in);for(int i=0;i<n;i++)caps.add(new Cap(site(in),
                    new Edge(in.readLong(),bool(in),range(in)),in.readLong(),in.readUTF()));
            if(in.read()!=-1)throw new IOException("exit certificate trailing bytes");
            return new GroundExitDecision(key,left,right,terminal,breaks,exclusions,caps,t0,DeterministicHash.sha256(bytes));
        } catch(IllegalArgumentException e) { throw new IOException("invalid exit certificate",e); }
    }
    private static boolean bool(DataInputStream in)throws IOException {int b=in.readUnsignedByte();if(b>1)throw new IOException("boolean");return b==1;}
    private static int count(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>16384)throw new IOException("exit list bound");return n;}
    private static void range(DataOutputStream o,ArcInterval r)throws IOException{o.writeLong(r.fromQ8());o.writeLong(r.toQ8());}
    private static ArcInterval range(DataInputStream i)throws IOException{return new ArcInterval(i.readLong(),i.readLong());}
    private static void site(DataOutputStream o,SiteId s)throws IOException{o.writeUTF(s.wallId());o.writeUTF(s.kind().name());o.writeLong(s.ordinal());}
    private static SiteId site(DataInputStream i)throws IOException{return new SiteId(i.readUTF(),SiteKind.valueOf(i.readUTF()),i.readLong());}
    private static void exit(DataOutputStream o,Exit e)throws IOException{site(o,e.site());o.writeLong(e.sQ8());range(o,e.footprint());o.writeUTF(e.componentHash());o.writeUTF(e.t0Hash());}
    private static Exit exit(DataInputStream i)throws IOException{return new Exit(site(i),i.readLong(),range(i),i.readUTF(),i.readUTF());}
}
