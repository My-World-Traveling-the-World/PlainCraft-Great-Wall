package local.plaincraft.greatwall.gametest;

import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class CatalogGameTests implements FabricGameTest {
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
