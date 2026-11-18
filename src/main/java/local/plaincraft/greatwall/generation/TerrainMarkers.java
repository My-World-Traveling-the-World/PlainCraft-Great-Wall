package local.plaincraft.greatwall.generation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import local.plaincraft.greatwall.gameplay.GameplayTypes;
import local.plaincraft.greatwall.model.WallTypes;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;

/** Native chunk attachments must survive ProtoChunk promotion and serialization. */
public final class TerrainMarkers {
    // 只为新模板容器保存初始化身份，已拿走物资的箱子与尚未初始化的空箱不能混为一谈。
    static final ResourceLocation TEMPLATE_LOOT_RECEIPT_ID =
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "template_loot_receipt");
    static final AttachmentType<String> TEMPLATE_LOOT_RECEIPT =
            AttachmentRegistry.createPersistent(TEMPLATE_LOOT_RECEIPT_ID, Codec.STRING);
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
    public record Ownership(long revision,Set<Point3i> mask,Set<Point3i> firepits) {
        public Ownership {
            if(revision<0)throw new IllegalArgumentException("negative ownership revision");
            mask=Set.copyOf(mask);firepits=Set.copyOf(firepits);
            if(!mask.containsAll(firepits))throw new IllegalArgumentException("firepit outside ownership mask");
        }
    }
    private static final Codec<Set<Point3i>> POINT_SET=POINT.listOf().comapFlatMap(points->{
        var unique=Set.copyOf(points);
        return unique.size()==points.size()?DataResult.success(unique):DataResult.error(()->"duplicate owned coordinate");
    },points->points.stream().sorted(java.util.Comparator.comparingInt(Point3i::x)
            .thenComparingInt(Point3i::y).thenComparingInt(Point3i::z)).toList());
    public static final Codec<Ownership> OWNERSHIP_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.LONG.fieldOf("revision").forGetter(Ownership::revision),
            POINT_SET.fieldOf("mask").forGetter(Ownership::mask),
            POINT_SET.fieldOf("firepits").forGetter(Ownership::firepits)).apply(i,Ownership::new));
    public static final AttachmentType<Ownership> OWNERSHIP=AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall","ownership_v1"),OWNERSHIP_CODEC);
    public static final AttachmentType<Set<Point3i>> TOMBSTONES=AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall","tombstones_v1"),POINT_SET);
    private static final Codec<UUID> UUID_CODEC=Codec.STRING.comapFlatMap(s->{
        try{return DataResult.success(UUID.fromString(s));}
        catch(IllegalArgumentException invalid){return DataResult.error(()->"invalid UUID: "+s);}
    },UUID::toString);
    private static final Codec<GameplayTypes.OwnedMob> OWNED_MOB_CODEC=RecordCodecBuilder.create(i->i.group(
            SITE.fieldOf("site").forGetter(GameplayTypes.OwnedMob::site),
            Codec.INT.fieldOf("ordinal").forGetter(GameplayTypes.OwnedMob::ordinal),
            UUID_CODEC.fieldOf("entity").forGetter(GameplayTypes.OwnedMob::entity),
            Codec.LONG.fieldOf("sequence").forGetter(GameplayTypes.OwnedMob::sequence)).apply(i,GameplayTypes.OwnedMob::new));
    public static final AttachmentType<GameplayTypes.OwnedMob> OWNED_MOB=AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall","owned_mob_v1"),OWNED_MOB_CODEC);
    private static <E extends Enum<E>> Codec<E> enumeration(Class<E> type) {
        return Codec.STRING.comapFlatMap(s->{try{return DataResult.success(Enum.valueOf(type,s));}
            catch(IllegalArgumentException invalid){return DataResult.error(()->"unknown "+type.getSimpleName()+": "+s);}},Enum::name);
    }
    private TerrainMarkers() {}
    public static void register() { /* Initializes the types during mod registration. */ }
}
