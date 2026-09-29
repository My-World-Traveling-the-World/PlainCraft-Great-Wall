package local.plaincraft.greatwall.terrain;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Registry-owner frozen classification. Unknown states fail closed; no neighbor/chunk lookup. */
public final class BlockCatalog {
    public record Flags(boolean support,boolean replaceablePlant,boolean water,boolean gravity,boolean blockEntity,boolean protectedBlock) {}
    private final Map<String,Flags> flags;
    private final String hash;
    public BlockCatalog(RegistryAccess registries) {
        Objects.requireNonNull(registries);
        var values=new TreeMap<String,Flags>();
        for(var block:BuiltInRegistries.BLOCK) for(var state:block.getStateDefinition().getPossibleStates()) {
            String id=BuiltInRegistries.BLOCK.getKey(block).toString();
            boolean entity=state.hasBlockEntity(),gravity=block instanceof FallingBlock;
            boolean plant=state.isAir() || state.canBeReplaced()&&!entity&&state.getFluidState().isEmpty()
                    &&!state.is(Blocks.POWDER_SNOW)&&!state.is(BlockTags.LEAVES);
            boolean water=state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            boolean natural=natural(id);
            boolean support=natural&&!gravity&&!state.is(BlockTags.LOGS)&&!state.is(BlockTags.LEAVES)
                    &&state.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP);
            boolean protectedBlock=entity||!id.startsWith("minecraft:")||state.is(Blocks.BEDROCK)
                    ||state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)||!natural&&!plant&&!water;
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
        state.getValues().forEach((p,v)->values.put(p.getName(),v.toString()));
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
    private static boolean natural(String id) {
        return switch(id) {
            case "minecraft:stone","minecraft:granite","minecraft:diorite","minecraft:andesite","minecraft:deepslate",
                    "minecraft:tuff","minecraft:calcite","minecraft:dripstone_block","minecraft:dirt","minecraft:grass_block",
                    "minecraft:coarse_dirt","minecraft:rooted_dirt","minecraft:podzol","minecraft:mycelium","minecraft:moss_block",
                    "minecraft:sandstone","minecraft:red_sandstone","minecraft:terracotta","minecraft:clay","minecraft:mud",
                    "minecraft:packed_mud","minecraft:snow_block","minecraft:sand","minecraft:red_sand","minecraft:gravel"->true;
            default->id.startsWith("minecraft:")&&id.endsWith("_terracotta")&&!id.endsWith("_glazed_terracotta");
        };
    }
}
