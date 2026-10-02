package local.plaincraft.greatwall.gametest;

import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.SnapshotColumnView;
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

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void mossyFoundationNeedsAllThreeLayersAndPreservesCapturedProtection(GameTestHelper helper) {
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var coverage=new Coverage(new ChunkKey("minecraft:overworld",0,0),java.util.List.of(0),
                new Bounds(0,49,0,16,92,16),"a".repeat(64));
        var palette=java.util.List.of(BlockCatalog.token(Blocks.AIR.defaultBlockState(),false),
                BlockCatalog.token(Blocks.MOSSY_COBBLESTONE.defaultBlockState(),false),
                BlockCatalog.token(Blocks.COAL_ORE.defaultBlockState(),false),
                BlockCatalog.token(Blocks.GRAVEL.defaultBlockState(),false));
        var key=new TerrainTile.Key("minecraft:overworld",0,0,0,127);
        int[] states=new int[key.volume()];
        for(int y=61;y<=63;y++)states[(y*16)*16]=1;
        var metadata=new CaptureMetadata(coverage,java.util.List.of(new ColumnEvidence(0,0,64,false)),
                java.util.List.of(),java.util.Set.of(),java.util.Set.of(),"a".repeat(64));
        var probe=new SurfaceProbe();var bounds=new DimensionBounds(-64,320,29_999_000);
        var tile=new TerrainTile(key,"a".repeat(64),palette,states);
        var result=probe.probe(new SnapshotColumnView(0,0,metadata,java.util.Map.of(key,tile),catalog),84,bounds);
        helper.assertTrue(result.kind()==ProbeKind.SUPPORTED&&result.groundTopY()==64,
                "three native mossy layers are support; two layers are insufficient");
        for(int lower:new int[]{0,2,3}){
            var changed=states.clone();changed[(61*16)*16]=lower;
            var broken=new TerrainTile(key,"a".repeat(64),palette,changed);
            helper.assertTrue(probe.probe(new SnapshotColumnView(0,0,metadata,java.util.Map.of(key,broken),catalog),
                    84,bounds).kind()==ProbeKind.NO_SUPPORT,"air/ore/gravel cannot be a third support layer");
        }
        for(var protectedMetadata:java.util.List.of(
                new CaptureMetadata(coverage,metadata.columns(),java.util.List.of(new Bounds(0,60,0,1,65,1)),
                        java.util.Set.of(),java.util.Set.of(),"a".repeat(64)),
                new CaptureMetadata(coverage,metadata.columns(),java.util.List.of(),
                        java.util.Set.of(new Point3i(0,63,0)),java.util.Set.of(),"a".repeat(64)),
                new CaptureMetadata(coverage,metadata.columns(),java.util.List.of(),
                        java.util.Set.of(),java.util.Set.of(new Point3i(0,63,0)),"a".repeat(64)))){
            helper.assertTrue(probe.probe(new SnapshotColumnView(0,0,protectedMetadata,java.util.Map.of(key,tile),catalog),
                    84,bounds).kind()==ProbeKind.PROTECTED,"structure/BE/old immutable evidence remains protected");
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

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void naturalThinIceAuthorizationStaysInTheCapturedColumn(GameTestHelper helper) {
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var coverage=new Coverage(new ChunkKey("minecraft:overworld",0,0),
                java.util.List.of(0),new Bounds(0,49,0,16,92,16),"a".repeat(64));
        var metadata=new CaptureMetadata(coverage,java.util.List.of(new ColumnEvidence(0,0,67,false)),
                java.util.List.of(),java.util.Set.of(),java.util.Set.of(),"a".repeat(64));
        var palette=java.util.List.of(BlockCatalog.token(Blocks.STONE.defaultBlockState(),false),
                BlockCatalog.token(Blocks.WATER.defaultBlockState(),false),
                BlockCatalog.token(Blocks.ICE.defaultBlockState(),false),
                BlockCatalog.token(Blocks.AIR.defaultBlockState(),false));
        var key=new TerrainTile.Key("minecraft:overworld",0,0,0,127);
        int[] states=new int[key.volume()];
        for(int y=0;y<128;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            states[(y*16+z)*16+x]=y<64?0:y<66?1:y==66?2:3;
        var tile=new TerrainTile(key,"a".repeat(64),palette,states);
        var column=new SnapshotColumnView(0,0,metadata,java.util.Map.of(key,tile),catalog);
        var result=new SurfaceProbe().probe(column,84,new DimensionBounds(-64,320,29_999_000));
        helper.assertTrue(result.kind()==ProbeKind.WATER,"catalog must not globally reject exposed single natural ice");
        helper.assertTrue(result.thinIceY().orElseThrow()==66,"only the exposed ice layer may be cleared");
        states[(65*16)*16]=2;
        var thick=new TerrainTile(key,"a".repeat(64),palette,states);
        var protectedColumn=new SnapshotColumnView(0,0,metadata,java.util.Map.of(key,thick),catalog);
        helper.assertTrue(new SurfaceProbe().probe(protectedColumn,84,new DimensionBounds(-64,320,29_999_000)).kind()==ProbeKind.PROTECTED,
                "two-layer ice must remain protected by the T0 context");
        helper.succeed();
    }
}
