package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class WallWorldStateTest {
    private static final UUID WORLD=new UUID(1,2);
    private static final String HASH="a".repeat(64);
    private static HistoryTypes.State state(long sequence,long revision) {
        return new HistoryTypes.State(WORLD,HASH,sequence,sequence/2,revision,HistoryTypes.Health.READY,HASH);
    }
    @Test void verifiedHeaderRoundTripsWithoutStoringPermanentIds() {
        var saved=new WallWorldState(state(12,3));
        var tag=saved.save(new CompoundTag(),null);
        assertEquals(saved.snapshot(),WallWorldState.load(tag,null).snapshot());
        assertEquals(8,tag.getAllKeys().size());
    }
    @Test void everyMissingFieldAndFutureSchemaFailClosed() {
        var saved=new WallWorldState(state(12,3)).save(new CompoundTag(),null);
        for(var key:saved.getAllKeys()) {
            var broken=saved.copy();broken.remove(key);
            assertThrows(RuntimeException.class,()->WallWorldState.load(broken,null),key);
        }
        saved.putInt("schema",2);
        assertThrows(IllegalArgumentException.class,()->WallWorldState.load(saved,null));
    }
    @Test void negativeAndMalformedHeaderCannotBecomeEmptyHistory() {
        assertThrows(NullPointerException.class,()->new WallWorldState(null));
        var tag=new WallWorldState(state(12,3)).save(new CompoundTag(),null);
        tag.putLong("count",-1);
        assertThrows(IllegalArgumentException.class,()->WallWorldState.load(tag,null));
        tag.putLong("count",0);tag.putString("checkpointSHA","bad");
        assertThrows(IllegalArgumentException.class,()->WallWorldState.load(tag,null));
    }
    @Test void applyRejectsRollbackAndForeignJournalIdentity() {
        var saved=new WallWorldState(state(12,3));
        assertThrows(IllegalArgumentException.class,()->saved.apply(state(11,4)));
        assertThrows(IllegalArgumentException.class,()->saved.apply(state(13,2)));
        assertThrows(IllegalArgumentException.class,()->saved.apply(new HistoryTypes.State(
                new UUID(3,4),HASH,13,6,4,HistoryTypes.Health.READY,HASH)));
        saved.apply(state(14,4));
        assertEquals(14,saved.snapshot().sequence());assertTrue(saved.isDirty());
    }
}
