package local.plaincraft.greatwall.persistence;

import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** Derived header only. Journal recovery remains the permanent source of truth. */
public final class WallWorldState extends SavedData {
    private HistoryTypes.State current;

    public WallWorldState(HistoryTypes.State recovered) {
        current=validated(recovered);
    }

    public static WallWorldState load(CompoundTag tag,HolderLookup.Provider registries) {
        Objects.requireNonNull(tag,"tag");
        require(tag,"schema",Tag.TAG_INT);
        if(tag.getInt("schema")!=1)throw new IllegalArgumentException("unsupported wall history schema");
        if(!tag.hasUUID("world"))throw new IllegalArgumentException("missing wall world UUID");
        require(tag,"manifestSHA",Tag.TAG_STRING);
        require(tag,"sequence",Tag.TAG_LONG);require(tag,"count",Tag.TAG_LONG);
        require(tag,"revision",Tag.TAG_LONG);require(tag,"health",Tag.TAG_STRING);
        require(tag,"checkpointSHA",Tag.TAG_STRING);
        return new WallWorldState(new HistoryTypes.State(tag.getUUID("world"),tag.getString("manifestSHA"),
                tag.getLong("sequence"),tag.getLong("count"),tag.getLong("revision"),
                HistoryTypes.Health.valueOf(tag.getString("health")),tag.getString("checkpointSHA")));
    }

    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        Objects.requireNonNull(tag,"tag");
        tag.putInt("schema",1);tag.putUUID("world",current.world());
        tag.putString("manifestSHA",current.manifestHash());tag.putLong("sequence",current.sequence());
        tag.putLong("count",current.beaconCount());tag.putLong("revision",current.revision());
        tag.putString("health",current.health().name());tag.putString("checkpointSHA",current.checkpointHash());
        return tag;
    }

    public void apply(HistoryTypes.State committed) {
        committed=validated(committed);
        if(!current.world().equals(committed.world())||!current.manifestHash().equals(committed.manifestHash()))
            throw new IllegalArgumentException("foreign history identity");
        if(committed.sequence()<current.sequence()||committed.revision()<current.revision()
                ||committed.beaconCount()<current.beaconCount())
            throw new IllegalArgumentException("history header cannot move backwards");
        current=committed;setDirty();
    }

    public HistoryTypes.State snapshot() { return current; }

    private static HistoryTypes.State validated(HistoryTypes.State state) {
        Objects.requireNonNull(state,"verified journal state required");
        WallTypes.requireHash(state.manifestHash());WallTypes.requireHash(state.checkpointHash());
        if(state.beaconCount()>state.sequence())throw new IllegalArgumentException("count exceeds history sequence");
        return state;
    }

    private static void require(CompoundTag tag,String key,int type) {
        if(!tag.contains(key,type))throw new IllegalArgumentException("missing or mistyped wall history field: "+key);
    }
}
