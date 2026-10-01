package local.plaincraft.greatwall.terrain;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Registry-owner frozen classification. Unknown states fail closed; no neighbor/chunk lookup. */
public final class BlockCatalog {
    private static final TagKey<Block> SUPPORT=tag("foundation_support");
    private static final TagKey<Block> VEGETATION=tag("replaceable_vegetation");
    private static final TagKey<Block> PROTECTED=tag("protected");
    private static final TagKey<Block> NATURAL_REPLACEABLE=tag("natural_replaceable");
    public record Flags(boolean support,boolean replaceablePlant,boolean water,boolean gravity,boolean blockEntity,boolean protectedBlock) {}
    private final Map<String,Flags> flags;
    private final String hash;
    public BlockCatalog(RegistryAccess registries) {
        Objects.requireNonNull(registries);
        var values=new TreeMap<String,Flags>();
        for(var block:BuiltInRegistries.BLOCK) for(var state:block.getStateDefinition().getPossibleStates()) {
            String id=BuiltInRegistries.BLOCK.getKey(block).toString();
            boolean entity=state.hasBlockEntity(),gravity=block instanceof FallingBlock;
            boolean explicitProtection=state.is(PROTECTED)||entity||!id.startsWith("minecraft:")
                    ||state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
            boolean plant=!explicitProtection&&(state.isAir() || state.is(VEGETATION)&&state.getFluidState().isEmpty()
                    &&!gravity&&!state.is(Blocks.POWDER_SNOW)&&!state.is(BlockTags.LEAVES)&&!state.is(BlockTags.LOGS)
                    &&(!state.is(Blocks.SNOW)||state.getValue(SnowLayerBlock.LAYERS)<8));
            boolean water=state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            boolean natural=state.is(SUPPORT);
            boolean naturalReplaceable=state.is(NATURAL_REPLACEABLE);
            boolean support=natural&&!explicitProtection&&!gravity&&state.getFluidState().isEmpty()
                    &&!state.is(Blocks.POWDER_SNOW)&&!state.is(BlockTags.LOGS)&&!state.is(BlockTags.LEAVES)
                    &&state.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP);
            // SnapshotColumnView alone authorizes exposed single natural ice; every other ice stays protected there.
            boolean protectedBlock=explicitProtection||!natural&&!naturalReplaceable&&!plant&&!water&&!state.is(Blocks.ICE);
            Flags f=new Flags(support,plant,water,gravity,entity,protectedBlock);
            values.put(token(state,false),f);
            values.put(token(state,true),new Flags(false,false,water,gravity,true,true));
        }
        flags=Map.copyOf(values);
        hash=DeterministicHash.sha256(values.toString().getBytes(StandardCharsets.UTF_8));
    }
    public Flags flags(String canonicalState) {
        var result=flags.get(canonicalState);
        if(result==null)throw new WallFailure(FailureCode.INVALID_RESOURCE,"unknown captured block state "+canonicalState);
        return result;
    }
    public BlockState resolve(BlockSpec spec) {return resolveState(spec);}
    public String hash(){return hash;}
    public static BlockState resolveState(BlockSpec spec) {
        var id=ResourceLocation.parse(spec.id());
        if(!BuiltInRegistries.BLOCK.containsKey(id))throw new WallFailure(FailureCode.INVALID_RESOURCE,"unknown block "+id);
        BlockState state=BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        for(var e:spec.properties().entrySet()) {
            Property<?> property=state.getBlock().getStateDefinition().getProperty(e.getKey());
            if(property==null)throw new WallFailure(FailureCode.INVALID_RESOURCE,"unknown property "+e.getKey()+" for "+id);
            state=with(state,property,e.getValue());
        }
        return state;
    }
    public static BlockSpec specification(BlockState state) {
        var values=new TreeMap<String,String>();
        state.getProperties().forEach(p->values.put(p.getName(),serialized(state,p)));
        return new BlockSpec(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),values);
    }
    public static String token(BlockState state,boolean entity) {
        var spec=specification(state);
        return spec.id()+new TreeMap<>(spec.properties()).toString()+":fluid="+BuiltInRegistries.FLUID.getKey(state.getFluidState().getType())+":be="+entity;
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> property,String value) {
        T parsed=property.getValue(value).orElseThrow(()->new WallFailure(FailureCode.INVALID_RESOURCE,"invalid block property value "+value));
        return state.setValue(property,parsed);
    }
    private static <T extends Comparable<T>> String serialized(BlockState state,Property<T> property) {
        return property.getName(state.getValue(property));
    }
    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK,ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall",path));
    }
}
