package local.plaincraft.greatwall.gametest;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import local.plaincraft.greatwall.component.ImportedWallTemplate;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.ChunkWallWriter;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.generation.WallPlanSupport;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class WriterGameTests implements FabricGameTest {
    private static final String H="a".repeat(64);

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void partialBackfillDoesNotRestockAnOpenedContainerAfterNbtReload(GameTestHelper helper) {
        var level = helper.getLevel();
        var nativePos = helper.absolutePos(new BlockPos(1, 2, 1));
        var chunk = level.getChunkAt(nativePos);
        var pos = new Point3i(nativePos.getX(), nativePos.getY(), nativePos.getZ());
        var owner = new ChunkKey(level.dimension().location().toString(), chunk.getPos().x, chunk.getPos().z);
        var site = new SiteId("fixture", SiteKind.GATE, 3);
        var template = ImportedWallTemplate.Asset.GATE.template();
        var barrel = template.cells().stream().filter(c -> c.entity() != null && c.entity().type().equals("minecraft:barrel"))
                .findFirst().orElseThrow();
        var intent = template.entityIntent(barrel, pos, 42, site);
        var plan = new ChunkWritePlan(owner, H, H, List.of(), List.of(), List.of(intent), List.of(), List.of(), List.of(), H);
        level.setBlock(nativePos, Blocks.BARREL.defaultBlockState(), 2);
        var touched = java.util.Set.of(pos);
        helper.assertTrue(WallPlanSupport.writeBlockEntities(chunk, plan, touched) == 1, "已有原版空桶仍须首次初始化");
        WallPlanSupport.writeMarkers(chunk, plan, false);
        var opened = (BarrelBlockEntity) chunk.getBlockEntity(nativePos);
        helper.assertTrue(opened != null && opened.getLootTable() != null, "首次补建必须安装待抽取表");
        opened.unpackLootTable(null);
        helper.assertTrue(!opened.isEmpty(), "容器必须实际抽到物品，避免清空测试空转");
        opened.clearContent();
        // 让原版及 Fabric 真实往返实体 NBT；内存中的对象或战利品表都不能充当重试凭据。
        var stored = opened.saveWithFullMetadata(level.registryAccess());
        var reloaded = (BarrelBlockEntity) BlockEntity.loadStatic(nativePos, Blocks.BARREL.defaultBlockState(), stored,
                level.registryAccess());
        helper.assertTrue(reloaded != null && reloaded.getLootTable() == null, "重载必须得到已开过的容器");
        chunk.removeBlockEntity(nativePos);
        chunk.addAndRegisterBlockEntity(reloaded);
        helper.assertTrue(WallPlanSupport.writeBlockEntities(chunk, plan, touched) == 0, "部分补建重试不能再次初始化");
        helper.assertTrue(chunk.getBlockEntity(nativePos) == reloaded && reloaded.isEmpty(), "重试后原空桶原样保留");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void templateBarrelRollsOnFirstAccessAndCompletedOwnerDoesNotRefill(GameTestHelper helper) {
        var level = helper.getLevel();
        var template = ImportedWallTemplate.Asset.GATE.template();
        var barrel = template.cells().stream().filter(c -> c.entity() != null
                && c.entity().type().equals("minecraft:barrel") && c.local().y() < 20).findFirst().orElseThrow();
        var pos = new Point3i(4, 70, 4);
        var nativePos = new BlockPos(pos.x(), pos.y(), pos.z());
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        var writer = new ChunkWallWriter();
        var totals = new HashSet<Integer>();
        for (int i = 0; i < 24; i++) {
            var id = new SiteId("fixture", SiteKind.GATE, i);
            var entity = template.entityIntent(barrel, pos, 42, id);
            var voxel = new VoxelIntent(pos, IntentKind.SOLID, Part.CHEST,
                    BlockCatalog.specification(Blocks.BARREL.defaultBlockState()), id, 400, true, false);
            var bounds = new Bounds(4, 70, 4, 5, 71, 5);
            var raw = new ComponentVoxels(id, bounds, List.of(voxel), List.of(entity), List.of(), List.of(), H);
            var body = new ComponentVoxels(id, bounds, raw.voxels(), raw.blockEntities(), List.of(), List.of(),
                    CanonicalCodec.componentHash(raw));
            var cell = new RealizationCell(new CellKey(H, "minecraft:overworld", 0), List.of(), List.of(body), H, 0, H, H);
            var compiler = new ChunkPlanCompiler();
            var plan = compiler.bindCoverage(compiler.compile(new ChunkKey("minecraft:overworld", 0, 0), H,
                    List.of(cell), Map.of(pos, BlockCatalog.token(Blocks.AIR.defaultBlockState(), false))), H);
            if (i == 0) {
                StageTrace.enterLockAction();
                try {
                    helper.assertTrue(writer.apply(chunk, plan, WriteMode.INITIAL).complete(), "计划必须由真实区块写入器完成");
                } finally { StageTrace.leaveLockAction(); }
                // 世代写入器已准备的原版待加载 NBT，应能被另一条入口识别且不重复写入。
                helper.assertTrue(WallPlanSupport.writeBlockEntities(chunk, plan, java.util.Set.of(pos)) == 0,
                        "重复施工不能覆盖已有收据的待加载木桶");
            } else {
                // 与世界生成和加载后补建共用的实体入口也必须安装同样的原版战利品。
                helper.assertTrue(WallPlanSupport.writeBlockEntities(chunk, plan, java.util.Set.of(pos)) == 1,
                        "补建实体入口没有写入模板木桶");
            }
            var pending = chunk.getBlockEntityNbtForSaving(nativePos, level.registryAccess());
            helper.assertTrue(pending != null && pending.getString("LootTable").equals("plaincraft_great_wall:chests/ordinary")
                    && !pending.contains("Items"), "木桶应只保存待抽取战利品");
            var opened = (BarrelBlockEntity) BlockEntity.loadStatic(nativePos, Blocks.BARREL.defaultBlockState(), pending,
                    level.registryAccess());
            helper.assertTrue(opened != null, "原版木桶应能读取施工写入的数据");
            opened.setLevel(level);
            opened.unpackLootTable(null);
            int total = 0;
            for (int slot = 0; slot < opened.getContainerSize(); slot++) total += opened.getItem(slot).getCount();
            helper.assertTrue(total > 0 && opened.getLootTable() == null, "首次抽取应获得物资且消耗掉落表");
            totals.add(total);
            if (i == 0) {
                opened.clearContent();
                chunk.setBlockEntityNbt(opened.saveWithFullMetadata(level.registryAccess()));
                StageTrace.enterLockAction();
                try {
                    helper.assertTrue(writer.apply(chunk, plan, WriteMode.INITIAL).written() == 0, "同计划重放不得重复施工");
                } finally { StageTrace.leaveLockAction(); }
                var after = chunk.getBlockEntityNbtForSaving(nativePos, level.registryAccess());
                helper.assertTrue(after != null && !after.contains("LootTable") && after.getList("Items", 10).isEmpty(),
                        "玩家清空后的木桶不得补回战利品");
            }
        }
        helper.assertTrue(totals.size() > 1, "24 次真实抽取的总数量不应全部相同");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=40)
    public void nativeOwnerWritePreflightsEverythingAndReplayProtectsEdits(GameTestHelper helper) {
        var chunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        var id=new SiteId("fixture",SiteKind.WALL,0);
        var voxels=List.of(new VoxelIntent(new Point3i(0,70,0),IntentKind.SOLID,Part.CORE,
                new BlockSpec("minecraft:stone_bricks",Map.of()),id,400,true,true),
                new VoxelIntent(new Point3i(1,70,0),IntentKind.SOLID,Part.CORE,new BlockSpec("minecraft:stone_bricks",Map.of()),id,400,true,true));
        var raw=new ComponentVoxels(id,new Bounds(0,70,0,2,74,1),voxels,List.of(),List.of(),List.of(),H);
        var c=new ComponentVoxels(id,raw.bounds(),voxels,List.of(),List.of(),List.of(),DeterministicHash.sha256(CanonicalCodec.component(raw)));
        var cell=new RealizationCell(new CellKey(H,"minecraft:overworld",0),List.of(),List.of(c),H,0,H,H);
        var compiler=new ChunkPlanCompiler();
        var plan=compiler.bindCoverage(compiler.compile(new ChunkKey("minecraft:overworld",0,0),H,List.of(cell),
                Map.of(new Point3i(0,70,0),BlockCatalog.token(Blocks.AIR.defaultBlockState(),false),
                        new Point3i(1,70,0),BlockCatalog.token(Blocks.AIR.defaultBlockState(),false))),H);
        var writer=new ChunkWallWriter();
        chunk.setBlockState(new BlockPos(1,70,0),Blocks.DIAMOND_BLOCK.defaultBlockState(),false);
        StageTrace.enterLockAction();
        try {
            var conflict=writer.apply(chunk,plan,WriteMode.INITIAL);
            helper.assertTrue(!conflict.complete()&&chunk.getBlockState(new BlockPos(0,70,0)).isAir(),"whole write set is checked before mutation");
            helper.assertTrue(chunk.getAttached(TerrainMarkers.PRODUCTION)==null,"conflict must not publish marker");
            chunk.setBlockState(new BlockPos(1,70,0),Blocks.AIR.defaultBlockState(),false);
            helper.assertTrue(writer.apply(chunk,plan,WriteMode.INITIAL).complete(),"native owner write completes");
            chunk.setBlockState(new BlockPos(0,70,0),Blocks.GOLD_BLOCK.defaultBlockState(),false);
            helper.assertTrue(writer.apply(chunk,plan,WriteMode.INITIAL).written()==0,"replay is read-only");
            helper.assertTrue(chunk.getBlockState(new BlockPos(0,70,0)).is(Blocks.GOLD_BLOCK),"player edit survives replay");
        } finally {StageTrace.leaveLockAction();}
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=40)
    public void markerCodecKeepsThreeIntCoordinatesAndGameplayIdentity(GameTestHelper helper) {
        var site=new SiteId("fixture",SiteKind.BEACON,1);
        var marker=new TerrainMarkers.Marker(H,H,H,7,true,List.of(new Point3i(-1,6000,-1)),
                List.of(new BlockEntityIntent(new Point3i(-1,6002,-1),"minecraft:campfire",site,3,Map.of("beaconOrdinal","1"))),
                List.of(new SpawnSlot(site,0,new Point3i(-2,6000,-1),"minecraft:pillager")),Map.of(site,DecayState.PARTIAL));
        var nbt=TerrainMarkers.CODEC.encodeStart(NbtOps.INSTANCE,marker).getOrThrow();
        var decoded=TerrainMarkers.CODEC.parse(NbtOps.INSTANCE,nbt).getOrThrow();
        helper.assertTrue(marker.equals(decoded),"marker round-trip must preserve y=6000 without packed BlockPos");
        helper.succeed();
    }
}
