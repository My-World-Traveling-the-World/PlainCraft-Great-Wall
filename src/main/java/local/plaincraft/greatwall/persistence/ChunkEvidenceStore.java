package local.plaincraft.greatwall.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Optional;
import java.util.zip.CRC32C;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import local.plaincraft.greatwall.planning.ProbePlanner;
import local.plaincraft.greatwall.terrain.TerrainTile;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision;
import local.plaincraft.greatwall.diagnostics.BoundedByteArrayOutputStream;

/** Authoritative generation evidence, NOT a regenerable station cache. Called on the IO owner. */
public final class ChunkEvidenceStore {
    private static final int MAGIC = 0x50475430;
    private static final int LIMIT = 2 * 1024 * 1024;
    private final Path root;
    private final boolean partitioned;

    public ChunkEvidenceStore(Path root) { this(root,false); }
    private ChunkEvidenceStore(Path root,boolean partitioned){this.root=root.toAbsolutePath().normalize();this.partitioned=partitioned;}
    public static ChunkEvidenceStore production(Path root){return new ChunkEvidenceStore(root,true);}
    public Path path(TerrainTile.Key key) {
        return partitioned?directory(new ChunkKey(key.dimension(),key.chunkX(),key.chunkZ())).resolve(Math.floorDiv(key.minY(),128)*128+".t0"):
                root.resolve(ProbePlanner.hash(key.identity()) + ".t0");
    }

    public Optional<TerrainTile> read(TerrainTile.Key key, String recipe) throws IOException {
        Path file = path(key);
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > LIMIT) throw new IOException("T0_CORRUPT: oversized evidence");
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("T0_CORRUPT: header");
            int size = input.readInt();
            long checksum = input.readLong();
            if (size < 0 || size > LIMIT) throw new IOException("T0_CORRUPT: length");
            int compressedSize = Math.toIntExact(Files.size(file) - 20);
            if (compressedSize < 0 || compressedSize > LIMIT - 20) throw new IOException("T0_CORRUPT: compressed length");
            byte[] compressed = new byte[compressedSize];
            input.readFully(compressed);
            if (input.read() != -1) throw new IOException("T0_CORRUPT: file grew while reading");
            byte[] raw;
            try (var inflater = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
                raw = new byte[size];
                inflater.readFully(raw);
                if (inflater.read() != -1) throw new IOException("T0_CORRUPT: inflated length");
            }
            CRC32C crc = new CRC32C();
            crc.update(raw);
            if (raw.length != size || crc.getValue() != checksum) throw new IOException("T0_CORRUPT: CRC/length");
            try (var payload = new DataInputStream(new ByteArrayInputStream(raw))) {
                String savedRecipe = payload.readUTF();
                var savedKey = new TerrainTile.Key(payload.readUTF(), payload.readInt(), payload.readInt(),
                        payload.readInt(), payload.readInt());
                if (!savedKey.equals(key) || !savedRecipe.equals(recipe)) throw new IOException("T0_RECIPE_MISMATCH");
                String hash = payload.readUTF();
                int count = payload.readInt();
                if (count <= 0 || count > key.volume()) throw new IOException("T0_CORRUPT: palette");
                var palette = new ArrayList<String>(count);
                for (int i = 0; i < count; i++) palette.add(payload.readUTF());
                int[] states = new int[key.volume()];
                for (int i = 0; i < states.length; i++) states[i] = payload.readInt();
                if (payload.read() != -1) throw new IOException("T0_CORRUPT: trailing payload");
                var tile = new TerrainTile(key, recipe, palette, states);
                if (!tile.hash().equals(hash)) throw new IOException("T0_CORRUPT: hash");
                return Optional.of(tile);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("T0_CORRUPT: invalid payload", invalid);
        }
    }

    public TerrainTile persistBeforeApply(TerrainTile tile) throws IOException {
        Optional<TerrainTile> prior = read(tile.key(), tile.recipe());
        if (prior.isPresent()) {
            if (!prior.get().hash().equals(tile.hash())) throw new IOException("T0_CONFLICT: attempted overwrite");
            return prior.get();
        }
        var raw = new BoundedByteArrayOutputStream(LIMIT);
        try (var out = new DataOutputStream(raw)) {
            out.writeUTF(tile.recipe());
            out.writeUTF(tile.key().dimension());
            out.writeInt(tile.key().chunkX()); out.writeInt(tile.key().chunkZ());
            out.writeInt(tile.key().minY()); out.writeInt(tile.key().maxY());
            out.writeUTF(tile.hash());
            out.writeInt(tile.palette().size());
            for (String state : tile.palette()) out.writeUTF(state);
            for (int state : tile.copyStates()) out.writeInt(state);
        }
        byte[] bytes = raw.toByteArray();
        CRC32C crc = new CRC32C(); crc.update(bytes);
        var encoded = new BoundedByteArrayOutputStream(LIMIT);
        try (var out = new DataOutputStream(encoded)) {
            out.writeInt(MAGIC); out.writeInt(1); out.writeInt(bytes.length); out.writeLong(crc.getValue());
            try (var deflater = new DeflaterOutputStream(out)) { deflater.write(bytes); }
        }
        Files.createDirectories(path(tile.key()).getParent());
        Path temporary = Files.createTempFile(path(tile.key()).getParent(), "t0-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(encoded.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: an unsupported filesystem fails before any APPLY.
            Files.move(temporary, path(tile.key()), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return tile;
    }

    public void persistCoverage(CaptureMetadata value)throws IOException {
        byte[] bytes=EvidenceCodec.metadata(value);
        if(!value.hash().equals(DeterministicHash.sha256(bytes)))throw new IOException("capture metadata hash mismatch");
        EvidenceCodec.readMetadata(bytes);
        persistRecord(directory(value.coverage().chunk()).resolve("coverage.bin"),0x50474331,bytes);
    }
    public void persistCoverageIntent(Coverage value)throws IOException {
        byte[] bytes=EvidenceCodec.coverage(value);
        if(!value.hash().equals(DeterministicHash.sha256(bytes)))throw new IOException("coverage intent hash mismatch");
        persistRecord(directory(value.chunk()).resolve("capture-intent.bin"),0x50474931,bytes);
    }
    public Optional<Coverage> readCoverageIntent(ChunkKey key)throws IOException {
        var bytes=readRecord(directory(key).resolve("capture-intent.bin"),0x50474931);
        if(bytes.isEmpty())return Optional.empty();
        var c=EvidenceCodec.readCoverage(bytes.get());
        if(!c.chunk().equals(key))throw new IOException("capture intent owner mismatch");
        return Optional.of(c);
    }
    public Optional<CaptureMetadata> readCoverage(ChunkKey key)throws IOException {
        var data=readRecord(directory(key).resolve("coverage.bin"),0x50474331);
        if(data.isEmpty())return Optional.empty();
        var value=EvidenceCodec.readMetadata(data.get());
        if(!value.coverage().chunk().equals(key))throw new IOException("coverage owner mismatch");
        return Optional.of(value);
    }
    public void persistPlan(ChunkWritePlan plan)throws IOException {
        byte[] bytes=CanonicalCodec.writePlan(plan);
        if(!plan.hash().equals(DeterministicHash.sha256(bytes)))throw new IOException("write proof hash mismatch");
        CanonicalCodec.readWritePlan(bytes);
        persistRecord(directory(plan.owner()).resolve("build-proof.bin"),0x50475031,bytes);
    }
    public Optional<ChunkWritePlan> readPlan(ChunkKey key)throws IOException {
        var data=readRecord(directory(key).resolve("build-proof.bin"),0x50475031);
        if(data.isEmpty())return Optional.empty();
        var value=CanonicalCodec.readWritePlan(data.get());
        if(!value.owner().equals(key))throw new IOException("proof owner mismatch");
        return Optional.of(value);
    }
    public void persistExitDecision(GroundExitDecision decision)throws IOException {
        byte[] bytes=GroundExitCodec.encode(decision);
        if(!decision.hash().equals(DeterministicHash.sha256(bytes)))throw new IOException("exit decision hash mismatch");
        GroundExitCodec.decode(bytes);
        // A sealed certificate is authority, not a regenerable cache. Intent alone is a pre-APPLY crash.
        readExitDecision(decision.key());
        byte[] identity=java.util.HexFormat.of().parseHex(decision.hash());
        persistRecord(exitAuxPath(decision.key(),"intent"),0x50474534,identity);
        persistRecord(exitPath(decision.key()),0x50474532,bytes);
        persistRecord(exitAuxPath(decision.key(),"sealed"),0x50474535,identity);
    }
    public Optional<GroundExitDecision> readExitDecision(GroundExitDecision.Key key)throws IOException {
        var intent=readRecord(exitAuxPath(key,"intent"),0x50474534);
        var sealed=readRecord(exitAuxPath(key,"sealed"),0x50474535);
        var bytes=readRecord(exitPath(key),0x50474532);
        if(sealed.isPresent()&&(bytes.isEmpty()||intent.isEmpty()))throw new IOException("sealed exit decision lost evidence");
        if(bytes.isEmpty())return Optional.empty();
        if(intent.isEmpty())throw new IOException("exit decision without intent");
        var decision=GroundExitCodec.decode(bytes.get());
        if(!decision.key().equals(key))throw new IOException("exit decision identity mismatch");
        byte[] hash=java.util.HexFormat.of().parseHex(decision.hash());
        if(!java.util.Arrays.equals(intent.get(),hash)||sealed.isPresent()&&!java.util.Arrays.equals(sealed.get(),hash))
            throw new IOException("exit decision seal/hash mismatch");
        return sealed.isPresent()?Optional.of(decision):Optional.empty();
    }
    /** A write proof cannot be published until every shared exit certificate is durable. */
    public void persistExitProof(ChunkWritePlan plan,java.util.List<GroundExitDecision> decisions)throws IOException {
        if(decisions.isEmpty())throw new IOException("production proof lacks ground-exit decisions");
        var unique=new java.util.TreeMap<String,GroundExitDecision>();
        for(var d:decisions) {
            if(!d.key().recipeHash().equals(plan.recipeHash())||!d.key().dimension().equals(plan.owner().dimension()))
                throw new IOException("foreign exit decision in owner proof");
            var saved=readExitDecision(d.key()).orElseThrow(()->new IOException("exit decision missing before owner proof"));
            if(!saved.equals(d))throw new IOException("exit decision differs from durable evidence");
            unique.put(d.hash(),d);
        }
        var bytes=new BoundedByteArrayOutputStream(8*1024*1024);
        try(var out=new DataOutputStream(bytes)) {
            out.writeUTF(plan.hash());out.writeInt(unique.size());
            for(var d:unique.values()) {
                out.writeUTF(d.key().recipeHash());out.writeUTF(d.key().dimension());
                out.writeLong(d.key().range().fromQ8());out.writeLong(d.key().range().toQ8());out.writeUTF(d.hash());
            }
        }
        persistRecord(directory(plan.owner()).resolve("build-exits.bin"),0x50474533,bytes.toByteArray());
    }
    public void verifyExitProof(ChunkWritePlan plan)throws IOException {
        var bytes=readRecord(directory(plan.owner()).resolve("build-exits.bin"),0x50474533)
                .orElseThrow(()->new IOException("production owner lost exit proof"));
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(!in.readUTF().equals(plan.hash()))throw new IOException("exit proof/write proof mismatch");
            int count=in.readInt();if(count<1||count>4096)throw new IOException("exit proof count");
            String previous="";
            for(int n=0;n<count;n++) {
                var key=new GroundExitDecision.Key(in.readUTF(),in.readUTF(),new ArcInterval(in.readLong(),in.readLong()));
                String hash=in.readUTF();
                if(!key.recipeHash().equals(plan.recipeHash())||!key.dimension().equals(plan.owner().dimension())||hash.compareTo(previous)<=0)
                    throw new IOException("exit proof identity/order");
                previous=hash;
                var decision=readExitDecision(key).orElseThrow(()->new IOException("referenced exit decision missing"));
                if(!decision.hash().equals(hash))throw new IOException("referenced exit decision changed");
            }
            if(in.read()!=-1)throw new IOException("exit proof trailing bytes");
        } catch(IllegalArgumentException e){throw new IOException("invalid exit proof",e);}
    }
    private Path exitPath(GroundExitDecision.Key key) {
        return root.resolve(DeterministicHash.sha256(key.dimension().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .resolve("ground-exits").resolve(key.recipeHash()).resolve(key.range().fromQ8()+"_"+key.range().toQ8()+".bin");
    }
    private Path exitAuxPath(GroundExitDecision.Key key,String suffix) {
        Path path=exitPath(key);return path.resolveSibling(path.getFileName()+"."+suffix);
    }
    private Path directory(ChunkKey key) {
        return root.resolve(DeterministicHash.sha256(key.dimension().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .resolve(key.x()+"_"+key.z());
    }
    private void persistRecord(Path path,int magic,byte[] bytes)throws IOException {
        var prior=readRecord(path,magic);
        if(prior.isPresent()) {
            if(!java.util.Arrays.equals(bytes,prior.get()))throw new IOException("authoritative evidence overwrite refused");
            return;
        }
        if(bytes.length>8*1024*1024)throw new IOException("record exceeds 8MiB");
        CRC32C crc=new CRC32C();crc.update(bytes);
        var encoded=new BoundedByteArrayOutputStream(8*1024*1024+52);
        try(var out=new DataOutputStream(encoded)){
            out.writeInt(magic);out.writeInt(1);out.writeInt(bytes.length);out.writeLong(crc.getValue());
            out.write(java.util.HexFormat.of().parseHex(DeterministicHash.sha256(bytes)));out.write(bytes);
        }
        Files.createDirectories(path.getParent());Path tmp=Files.createTempFile(path.getParent(),"proof-",".tmp");
        try{
            try(var channel=FileChannel.open(tmp,StandardOpenOption.WRITE)){
                var buffer=ByteBuffer.wrap(encoded.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(tmp);}
    }
    private Optional<byte[]> readRecord(Path path,int magic)throws IOException {
        if(!Files.exists(path))return Optional.empty();
        if(Files.size(path)>8*1024*1024+52)throw new IOException("authoritative evidence oversized");
        try(var in=new DataInputStream(Files.newInputStream(path))){
            if(in.readInt()!=magic||in.readInt()!=1)throw new IOException("authoritative evidence header");
            int size=in.readInt();long checksum=in.readLong();
            if(size<0||size>8*1024*1024)throw new IOException("authoritative evidence length");
            byte[] sha=in.readNBytes(32),bytes=in.readNBytes(size);
            CRC32C crc=new CRC32C();crc.update(bytes);
            if(sha.length!=32||bytes.length!=size||in.read()!=-1||crc.getValue()!=checksum
                    ||!java.util.HexFormat.of().formatHex(sha).equals(DeterministicHash.sha256(bytes)))
                throw new IOException("authoritative evidence CRC/SHA/length mismatch");
            return Optional.of(bytes);
        }
    }
}
