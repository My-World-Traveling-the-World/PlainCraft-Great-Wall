package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import local.plaincraft.greatwall.geometry.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroundExitEvidenceTest {
    private static final String H="a".repeat(64);
    @TempDir Path directory;
    private static GroundExitDecision decision(long cut) {
        var root=new Exit(new SiteId("fixture",SiteKind.FORT,0),0,new ArcInterval(0,24*256L),H,H);
        return GroundExitCodec.hashed(new GroundExitDecision(new Key(H,"minecraft:overworld",new ArcInterval(0,Long.MAX_VALUE)),
                root,Optional.empty(),480*256L,List.of(new BreakInterval(new ArcInterval(cut,Long.MAX_VALUE),BreakReason.WORLD_LIMIT,BreakScope.PLANNED)),
                List.of(new ArcInterval(cut,480*256L)),List.of(),H,H));
    }
    private static ChunkWritePlan plan() {
        var p=new ChunkWritePlan(new ChunkKey("minecraft:overworld",4,0),H,H,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),H);
        return new ChunkWritePlan(p.owner(),H,H,p.voxels(),p.proofs(),p.blockEntities(),p.postprocess(),p.fluidTicks(),p.walkwayFeet(),
                DeterministicHash.sha256(CanonicalCodec.writePlan(p)));
    }
    @Test void durableCertificateAndOwnerBindingSurviveColdReopen() throws Exception {
        var store=ChunkEvidenceStore.production(directory);var d=decision(24*256L);var p=plan();
        assertThrows(IOException.class,()->store.persistExitProof(p,List.of(d)));
        store.persistExitDecision(d);store.persistExitProof(p,List.of(d));store.persistPlan(p);
        var cold=ChunkEvidenceStore.production(directory);
        assertEquals(d,cold.readExitDecision(d.key()).orElseThrow());cold.verifyExitProof(cold.readPlan(p.owner()).orElseThrow());
        assertThrows(IOException.class,()->cold.persistExitDecision(decision(32*256L)),"a published cut cannot be revised");
    }
    @Test void missingOrCorruptDecisionNeverSilentlyRebuildsOwnerProof() throws Exception {
        var store=ChunkEvidenceStore.production(directory);var d=decision(24*256L);var p=plan();
        store.persistExitDecision(d);store.persistExitProof(p,List.of(d));store.persistPlan(p);
        Path file;
        try(var files=Files.walk(directory)){file=files.filter(f->f.getFileName().toString().equals("0_"+Long.MAX_VALUE+".bin")).findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(file);Files.write(file,new byte[]{0});
        assertThrows(IOException.class,()->store.verifyExitProof(p));assertThrows(IOException.class,()->store.persistExitDecision(d));
        Files.write(file,bytes);store.verifyExitProof(p);Files.delete(file);
        assertThrows(IOException.class,()->store.verifyExitProof(p));
        assertThrows(IOException.class,()->store.persistExitDecision(d),"sealed missing evidence cannot be silently recreated");
    }
    @Test void ownerWithoutExitBindingCannotReplayProduction()throws Exception {
        var store=ChunkEvidenceStore.production(directory);store.persistPlan(plan());
        assertThrows(IOException.class,()->store.verifyExitProof(plan()));
        assertThrows(IOException.class,()->store.persistExitProof(plan(),List.of()));
    }
}
