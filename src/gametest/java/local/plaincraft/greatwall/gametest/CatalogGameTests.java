package local.plaincraft.greatwall.gametest;

import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.TerrainTile;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class CatalogGameTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void approvedMossyAndPlantsDoNotGrantForeignVoidOrDangerousSupport(GameTestHelper helper) {
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var mossy=Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        helper.assertTrue(mossy.isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP)
                &&mossy.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO),"mossy support must have native full collision and a sturdy top");
        helper.assertTrue(flags(catalog,mossy).support()&&!flags(catalog,mossy).replaceablePlant()
                &&!flags(catalog,mossy).protectedBlock(),"approved mossy foundation classification");
        for(var block:new net.minecraft.world.level.block.Block[]{
                Blocks.SWEET_BERRY_BUSH,Blocks.BROWN_MUSHROOM,Blocks.PUMPKIN}){
            for(var state:block.getStateDefinition().getPossibleStates()){
                var value=flags(catalog,state);
                helper.assertTrue(value.replaceablePlant()&&!value.support()&&!value.protectedBlock(),
                        "explicit natural vegetation, never foundation: "+state);
                helper.assertTrue(catalog.flags(BlockCatalog.token(state,true)).protectedBlock(),
                        "captured BE flag remains dominant over vegetation");
            }
        }
        var magma=flags(catalog,Blocks.MAGMA_BLOCK.defaultBlockState());
        helper.assertTrue(magma.protectedBlock()&&!magma.support()&&!magma.replaceablePlant(),
                "magma stays protected even though it is not lava fluid");
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.MOSSY_COBBLESTONE,
                Blocks.SWEET_BERRY_BUSH,Blocks.BROWN_MUSHROOM,Blocks.PUMPKIN,Blocks.MAGMA_BLOCK}){
            var site=new SiteId("fixture",SiteKind.WALL,0);var p=new Point3i(0,64,0);
            var intent=new VoxelIntent(p,IntentKind.OWNED_VOID,Part.AIR,
                    new BlockSpec("minecraft:air",java.util.Map.of()),site,900,true,false);
            var component=new ComponentVoxels(site,new Bounds(0,64,0,1,65,1),java.util.List.of(intent),
                    java.util.List.of(),java.util.List.of(),java.util.List.of(),"0".repeat(64));
            component=new ComponentVoxels(component.id(),component.bounds(),component.voxels(),component.blockEntities(),
                    component.spawnSlots(),component.requiredRoutes(),local.plaincraft.greatwall.geometry.CanonicalCodec.componentHash(component));
            var cell=new RealizationCell(new CellKey("a".repeat(64),"minecraft:overworld",0),java.util.List.of(),
                    java.util.List.of(component),"a".repeat(64),0,"a".repeat(64),"a".repeat(64));
            boolean rejected=false;
            try{new local.plaincraft.greatwall.generation.ChunkPlanCompiler().compile(
                    new ChunkKey("minecraft:overworld",0,0),"a".repeat(64),java.util.List.of(cell),
                    java.util.Map.of(p,BlockCatalog.token(block.defaultBlockState(),false)));}
            catch(WallFailure expected){rejected=expected.code()==FailureCode.OWNERSHIP_CONFLICT;}
            helper.assertTrue(rejected,"new tags never authorize OWNED_VOID carving "+block);
        }
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=40)
    public void approvedOreAndGravelReplacementDoesNotGrantFoundationOrVoidPermissions(GameTestHelper helper) {
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.COAL_ORE,Blocks.DEEPSLATE_COAL_ORE,Blocks.GRAVEL}) {
            var captured=flags(catalog,block.defaultBlockState());
            helper.assertTrue(!captured.support()&&!captured.protectedBlock()&&!captured.replaceablePlant(),
                    "approved natural replacement must not become foundation or air/vegetation");
            boolean legacyProtected=!captured.support()&&!captured.replaceablePlant()&&!captured.water();
            helper.assertTrue(legacyProtected,"the old support-only formula's false protection witness is preserved");
            helper.assertTrue(catalog.flags(BlockCatalog.token(block.defaultBlockState(),true)).protectedBlock(),
                    "captured block entity authorization remains dominant");
        }
        helper.assertTrue(flags(catalog,Blocks.GRAVEL.defaultBlockState()).gravity(),"gravel remains gravity-bound");
        helper.assertTrue(flags(catalog,Blocks.DIAMOND_BLOCK.defaultBlockState()).protectedBlock(),"ore blocks are not ore");
        helper.assertTrue(flags(catalog,Blocks.SAND.defaultBlockState()).protectedBlock(),"the approved table does not silently open sand");
        var site=new SiteId("fixture",SiteKind.WALL,0);
        var p=new Point3i(0,64,0);
        var intent=new VoxelIntent(p,IntentKind.OWNED_VOID,Part.AIR,new BlockSpec("minecraft:air",java.util.Map.of()),site,900,true,false);
        var component=new ComponentVoxels(site,new Bounds(0,64,0,1,65,1),java.util.List.of(intent),
                java.util.List.of(),java.util.List.of(),java.util.List.of(),"0".repeat(64));
        component=new ComponentVoxels(component.id(),component.bounds(),component.voxels(),component.blockEntities(),
                component.spawnSlots(),component.requiredRoutes(),local.plaincraft.greatwall.geometry.CanonicalCodec.componentHash(component));
        var cell=new RealizationCell(new CellKey("a".repeat(64),"minecraft:overworld",0),java.util.List.of(),
                java.util.List.of(component),"a".repeat(64),0,"a".repeat(64),"a".repeat(64));
        boolean rejected=false;
        try {new local.plaincraft.greatwall.generation.ChunkPlanCompiler().compile(
                new ChunkKey("minecraft:overworld",0,0),"a".repeat(64),java.util.List.of(cell),
                java.util.Map.of(p,BlockCatalog.token(Blocks.COAL_ORE.defaultBlockState(),false)));}
        catch(WallFailure expected){rejected=expected.code()==FailureCode.OWNERSHIP_CONFLICT;}
        helper.assertTrue(rejected,"OWNED_VOID cannot carve ore under the new natural replacement policy");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=40)
    public void supportTagsRejectGravitySnowAndMud(GameTestHelper helper) {
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.STONE,Blocks.DEEPSLATE,Blocks.DIRT,
                Blocks.SANDSTONE,Blocks.RED_SANDSTONE,Blocks.TERRACOTTA,Blocks.BLACK_TERRACOTTA}) {
            helper.assertTrue(flags(catalog,block.defaultBlockState()).support(),"approved foundation missing: "+block);
        }
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.MUD,Blocks.PACKED_MUD,Blocks.SAND,
                Blocks.RED_SAND,Blocks.GRAVEL,Blocks.SNOW_BLOCK,Blocks.SNOW,Blocks.POWDER_SNOW,
                Blocks.ICE,Blocks.OAK_LOG,Blocks.OAK_LEAVES}) {
            helper.assertTrue(!flags(catalog,block.defaultBlockState()).support(),"unapproved foundation: "+block);
        }
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=40)
    public void vegetationAndProtectionAreDisjointAndUnknownFailsClosed(GameTestHelper helper) {
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.AIR,Blocks.SHORT_GRASS,
                Blocks.POPPY,Blocks.OAK_SAPLING,Blocks.VINE,Blocks.SNOW}) {
            var f=flags(catalog,block.defaultBlockState());
            helper.assertTrue(f.replaceablePlant()&&!f.protectedBlock(),"approved vegetation: "+block);
        }
        helper.assertTrue(!flags(catalog,Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,8)).replaceablePlant(),
                "a full snow layer is not thin vegetation");
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.BEDROCK,Blocks.END_PORTAL_FRAME,
                Blocks.END_PORTAL,Blocks.NETHER_PORTAL,Blocks.LAVA,Blocks.CHEST,Blocks.DIAMOND_BLOCK,
                Blocks.FIRE,Blocks.LIGHT,Blocks.OAK_LEAVES}) {
            var f=flags(catalog,block.defaultBlockState());
            helper.assertTrue(f.protectedBlock()&&!f.replaceablePlant(),"protected or unknown block must not be erasable: "+block);
        }
        var water=flags(catalog,Blocks.WATER.defaultBlockState());
        helper.assertTrue(water.water()&&!water.support()&&!water.replaceablePlant(),"water is not ground or vegetation");
        boolean rejected=false;
        try { catalog.flags("unknown:state"); } catch(WallFailure expected) { rejected=true; }
        helper.assertTrue(rejected,"unregistered tokens must fail closed");
        helper.assertTrue(catalog.hash().equals(new BlockCatalog(helper.getLevel().registryAccess()).hash()),
                "frozen classification hash must be repeatable");
        helper.succeed();
    }

    private static BlockCatalog.Flags flags(BlockCatalog catalog,BlockState state) {
        return catalog.flags(BlockCatalog.token(state,false));
    }
}
