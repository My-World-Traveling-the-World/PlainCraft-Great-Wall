package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionEvidenceTest {
    @TempDir Path directory;
    private static final String H="a".repeat(64);
    private static final ChunkKey KEY=new ChunkKey("minecraft:overworld",-1,0);
    private CaptureMetadata metadata() {
        var cols=new ArrayList<ColumnEvidence>();
        for(int z=0;z<16;z++)for(int x=-16;x<0;x++)cols.add(new ColumnEvidence(x,z,6000,false));
        return EvidenceCodec.hashed(new CaptureMetadata(new Coverage(KEY,List.of(5888,6016),
                new Bounds(-16,5965,0,0,6008,16),H),cols,List.of(),Set.of(new Point3i(-1,6001,0)),Set.of(),H));
    }
    @Test void coverageIsDurableIdempotentAndCorruptionDoesNotReset()throws Exception {
        var store=ChunkEvidenceStore.production(directory);var m=metadata();store.persistCoverage(m);store.persistCoverage(m);
        assertEquals(m,store.readCoverage(KEY).orElseThrow());
        Path file;try(var files=Files.walk(directory)){file=files.filter(p->p.getFileName().toString().equals("coverage.bin")).findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->store.readCoverage(KEY));
        assertThrows(java.io.IOException.class,()->store.persistCoverage(m));
    }
    @Test void proofKeepsRevisionAndCannotBeReplaced()throws Exception {
        var p=new ChunkWritePlan(KEY,H,H,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
                new WriteMetadata(H,4,List.of(),Map.of()),H);
        p=new ChunkWritePlan(KEY,H,H,p.voxels(),p.proofs(),p.blockEntities(),p.postprocess(),p.fluidTicks(),p.walkwayFeet(),p.metadata(),
                DeterministicHash.sha256(CanonicalCodec.writePlan(p)));
        var store=ChunkEvidenceStore.production(directory);store.persistPlan(p);
        assertEquals(p,store.readPlan(KEY).orElseThrow());
        var other=new ChunkWritePlan(KEY,H,"b".repeat(64),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),H);
        var changed=new ChunkWritePlan(KEY,H,other.inputHash(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
                DeterministicHash.sha256(CanonicalCodec.writePlan(other)));
        assertThrows(java.io.IOException.class,()->store.persistPlan(changed));
    }
}
