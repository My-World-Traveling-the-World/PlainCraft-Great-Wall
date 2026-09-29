package local.plaincraft.greatwall.generation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;

/** Native chunk attachments must survive ProtoChunk promotion and serialization. */
public final class TerrainMarkers {
    public static final AttachmentType<String> T0 = AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "s0_t0"), Codec.STRING);
    public static final AttachmentType<String> APPLIED = AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "s0_applied"), Codec.STRING);
    public record Marker(String recipe,String coverageHash,String planHash,long gateRevision,boolean complete,
            List<Point3i> walkwayFeet,List<BlockEntityIntent> gameplay,List<SpawnSlot> spawnSlots,Map<SiteId,DecayState> realizedStates) {
        public Marker {
            WallTypes.requireHash(recipe);WallTypes.requireHash(coverageHash);WallTypes.requireHash(planHash);
            if(gateRevision<0)throw new IllegalArgumentException("revision must be nonnegative");
            walkwayFeet=List.copyOf(walkwayFeet);gameplay=List.copyOf(gameplay);spawnSlots=List.copyOf(spawnSlots);realizedStates=Map.copyOf(realizedStates);
        }
    }
    private static final Codec<Point3i> POINT=RecordCodecBuilder.create(i->i.group(
            Codec.INT.fieldOf("x").forGetter(Point3i::x),Codec.INT.fieldOf("y").forGetter(Point3i::y),
            Codec.INT.fieldOf("z").forGetter(Point3i::z)).apply(i,Point3i::new));
    private static final Codec<SiteId> SITE=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("wall").forGetter(SiteId::wallId),enumeration(SiteKind.class).fieldOf("kind").forGetter(SiteId::kind),
            Codec.LONG.fieldOf("ordinal").forGetter(SiteId::ordinal)).apply(i,SiteId::new));
    private static final Codec<BlockEntityIntent> ENTITY=RecordCodecBuilder.create(i->i.group(
            POINT.fieldOf("pos").forGetter(BlockEntityIntent::pos),Codec.STRING.fieldOf("type").forGetter(BlockEntityIntent::type),
            SITE.fieldOf("site").forGetter(BlockEntityIntent::site),Codec.INT.fieldOf("index").forGetter(BlockEntityIntent::localId),
            Codec.unboundedMap(Codec.STRING,Codec.STRING).fieldOf("fields").forGetter(BlockEntityIntent::fields)).apply(i,BlockEntityIntent::new));
    private static final Codec<SpawnSlot> SPAWN=RecordCodecBuilder.create(i->i.group(
            SITE.fieldOf("site").forGetter(SpawnSlot::site),Codec.INT.fieldOf("slot").forGetter(SpawnSlot::slot),
            POINT.fieldOf("feet").forGetter(SpawnSlot::feet),Codec.STRING.fieldOf("type").forGetter(SpawnSlot::entityType)).apply(i,SpawnSlot::new));
    private record StateEntry(SiteId site,DecayState state) {}
    private static final Codec<StateEntry> STATE=RecordCodecBuilder.create(i->i.group(
            SITE.fieldOf("site").forGetter(StateEntry::site),enumeration(DecayState.class).fieldOf("state").forGetter(StateEntry::state)).apply(i,StateEntry::new));
    private static final Codec<Map<SiteId,DecayState>> STATES=STATE.listOf().comapFlatMap(values->{
        var map=new java.util.HashMap<SiteId,DecayState>();
        for(var value:values)if(map.put(value.site(),value.state())!=null)return DataResult.error(()->"duplicate realized state");
        return DataResult.success(Map.copyOf(map));
    },map->map.entrySet().stream().sorted(java.util.Comparator.comparing((Map.Entry<SiteId,DecayState> e)->e.getKey().wallId())
            .thenComparing(e->e.getKey().kind().name()).thenComparingLong(e->e.getKey().ordinal()))
            .map(e->new StateEntry(e.getKey(),e.getValue())).toList());
    public static final Codec<Marker> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("recipe").forGetter(Marker::recipe),Codec.STRING.fieldOf("coverage").forGetter(Marker::coverageHash),
            Codec.STRING.fieldOf("plan").forGetter(Marker::planHash),Codec.LONG.fieldOf("revision").forGetter(Marker::gateRevision),
            Codec.BOOL.fieldOf("complete").forGetter(Marker::complete),POINT.listOf().fieldOf("feet").forGetter(Marker::walkwayFeet),
            ENTITY.listOf().fieldOf("gameplay").forGetter(Marker::gameplay),SPAWN.listOf().fieldOf("spawns").forGetter(Marker::spawnSlots),
            STATES.fieldOf("states").forGetter(Marker::realizedStates)).apply(i,Marker::new));
    public static final AttachmentType<Marker> PRODUCTION=AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall","production_v1"),CODEC);
    private static <E extends Enum<E>> Codec<E> enumeration(Class<E> type) {
        return Codec.STRING.comapFlatMap(s->{try{return DataResult.success(Enum.valueOf(type,s));}
            catch(IllegalArgumentException invalid){return DataResult.error(()->"unknown "+type.getSimpleName()+": "+s);}},Enum::name);
    }
    private TerrainMarkers() {}
    public static void register() { /* Initializes the types during mod registration. */ }
}
