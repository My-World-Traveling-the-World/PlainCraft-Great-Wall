package local.plaincraft.greatwall.generation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;
import local.plaincraft.greatwall.component.ImportedWallLayout;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/** 新建超平坦中的实物接墙验收区；原始展台作为独立参考保留。 */
public final class ImportedShowcase {
    private static final int BLOCKS_PER_TICK = 4_000;
    private static final int WALL_RUN = 24;
    private static final String RECEIPT = "flat-imported-wall-v2";
    // 用临时区块票，展台退出不能撤销玩家的 /forceload，也不把票写进存档。
    private static final TicketType<ChunkPos> TICKET = TicketType.create("greatwall_imported_showcase",
            java.util.Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<ServerLevel, ImportedShowcase> SESSIONS = new HashMap<>();

    private final Path root;
    private ImportedShowcaseReceipt receipt;
    private final Map<Asset, Work> workByAsset = new EnumMap<>(Asset.class);

    private ImportedShowcase(Path root, ImportedShowcaseReceipt receipt) {
        this.root = root;
        this.receipt = receipt;
    }

    public static void start(ServerLevel level, BlockPos spawn, boolean newWorld) {
        if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat) || SESSIONS.containsKey(level)) return;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        try {
            var saved = ImportedShowcaseReceipt.open(root, newWorld, level.getSeed(), spawn.getX(), spawn.getZ());
            if (saved.isEmpty()) return;
            var receipt = saved.orElseThrow();
            var session = new ImportedShowcase(root, receipt);
            for (var asset : Asset.values()) {
                var rect = plot(asset, receipt.x(), receipt.z());
                WallRoadGuard.publishRect(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
                int x = receipt.x() - 400, z = receipt.z() + 160 * asset.ordinal();
                // 用生成器的地表，不把重开时已有的楼顶当作地基。
                int base = flat.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                        level.getChunkSource().randomState());
                var work = new Work(level, asset, new Point3i(x, base + 15, z), rect);
                work.completed = receipt.contains(asset.ordinal());
                work.finished = work.completed;
                session.workByAsset.put(asset, work);
            }
            SESSIONS.put(level, session);
            session.workByAsset.values().stream().filter(work -> !work.completed)
                    .forEach(work -> ShowcaseGate.expect(work.rect.name()));
        } catch (IOException | RuntimeException failure) {
            GreatWallMod.LOGGER.error("[GreatWall:展台] 无法授权实物展区，保留存档现状: {}", root, failure);
        }
    }

    static ShowcaseLayout.Rect plot(Asset asset, int spawnX, int spawnZ) {
        // 占地和编译用同一锚点；这里只发布水平投影，实际高度等平地生成器决定。
        var layout = new ImportedWallLayout(asset, new Point3i(spawnX - 400, 0, spawnZ + 160 * asset.ordinal()), Rotation.NONE);
        var box = layout.boundsWithStraightWalls(WALL_RUN);
        return new ShowcaseLayout.Rect("实物接墙:" + asset.name(), box.minX(), box.minZ(),
                box.maxXExclusive() - 1, box.maxZExclusive() - 1);
    }

    static ComponentVoxels compile(Asset asset, Point3i feet, long seed) {
        var site = new SiteId(RECEIPT, switch (asset) {
            case GATE -> SiteKind.GATE;
            case ENEMY_TOWER -> SiteKind.ENEMY_TOWER;
            case BEACON -> SiteKind.BEACON;
            case CORNER_TOWER, CHENGHAI_TOWER -> SiteKind.FORT;
        }, asset.ordinal());
        return new ImportedWallLayout(asset, feet, Rotation.NONE).compileStraight(site, seed, WALL_RUN);
    }

    static int writeSlice(ComponentVoxels component, int cursor, Consumer<VoxelIntent> write) {
        // 只覆写模板实物；稀疏模板的空气不应刨掉地形或已存在的方块。
        int end = Math.min(cursor + BLOCKS_PER_TICK, component.voxels().size());
        while (cursor < end) {
            var voxel = component.voxels().get(cursor++);
            if (voxel.kind() == IntentKind.SOLID) write.accept(voxel);
        }
        return cursor;
    }

    public static void tick(ServerLevel level) {
        var session = SESSIONS.get(level);
        if (session == null) return;
        // 五件共用一个落块额度，避免大型模板同一 tick 争抢服务端时间。
        for (var work : session.workByAsset.values()) if (!work.finished) {
            work.advance();
            break;
        }
    }

    public static void afterSave(ServerLevel level, boolean flush, boolean force) {
        var session = SESSIONS.get(level);
        // 区块落盘后才记完成；普通自动保存可能只写部分区块，不能据此跳过施工。
        if (session == null || !flush || (level.noSave && !force)) return;
        int completed = 0;
        for (var work : session.workByAsset.values()) if (work.completed) completed |= 1 << work.asset.ordinal();
        try {
            session.receipt = session.receipt.save(session.root, completed);
        } catch (IOException | RuntimeException failure) {
            GreatWallMod.LOGGER.error("[GreatWall:展台] 无法保存实物展区完工凭证: {}", session.root, failure);
        }
    }

    public static void reset(ServerLevel level) {
        var session = SESSIONS.get(level);
        if (session != null) session.workByAsset.values().forEach(Work::finish);
    }

    public static void forget(ServerLevel level) { SESSIONS.remove(level); }

    public static void restart(ServerLevel level) {
        var session = SESSIONS.get(level);
        if (session == null) return;
        for (var work : session.workByAsset.values()) {
            // 沿用首次冻结的位置与高度，不在旧楼顶上再垒一栋。
            work.finish();
            work.cursor = 0;
            work.entityCursor = 0;
            work.completed = false;
            work.finished = false;
            ShowcaseGate.expect(work.rect.name());
        }
    }

    private static final class Work {
        final ServerLevel level;
        final Asset asset;
        final Point3i feet;
        final ShowcaseLayout.Rect rect;
        final Map<BlockSpec, BlockState> palette = new HashMap<>();
        ComponentVoxels component;
        CompletableFuture<ComponentVoxels> compiling;
        boolean held;
        int cursor;
        int entityCursor;
        boolean finished;
        boolean completed;

        Work(ServerLevel level, Asset asset, Point3i feet, ShowcaseLayout.Rect rect) {
            this.level = level; this.asset = asset; this.feet = feet; this.rect = rect;
        }

        void advance() {
            try {
                if (!held) {
                    held = true;
                    changeTickets(true);
                }
                if (component == null) {
                    for (int cx = rect.minX() >> 4; cx <= rect.maxX() >> 4; cx++) {
                        for (int cz = rect.minZ() >> 4; cz <= rect.maxZ() >> 4; cz++) {
                            if (level.getChunkSource().getChunkNow(cx, cz) == null) return;
                        }
                    }
                    if (compiling == null) {
                        long seed = level.getSeed();
                        // 大模板的摘要和体素编译不接触世界，放到后台；主线程只写限额内方块。
                        compiling = CompletableFuture.supplyAsync(() -> compile(asset, feet, seed),
                                net.minecraft.Util.backgroundExecutor());
                    }
                    if (!compiling.isDone()) return;
                    component = compiling.join();
                    compiling = null;
                    var bounds = component.bounds();
                    if (level.isOutsideBuildHeight(bounds.minY()) || level.isOutsideBuildHeight(bounds.maxYExclusive() - 1)) {
                        throw new IllegalStateException("实物展位超出建造高度: " + bounds);
                    }
                }
                if (cursor < component.voxels().size()) {
                    cursor = writeSlice(component, cursor, this::writeBlock);
                    return;
                }
                // 实体要在方块之后初始化；分批加载 NBT，避免整座大楼的容器集中在一个 tick。
                int end = Math.min(entityCursor + 64, component.blockEntities().size());
                while (entityCursor < end) writeEntity(component.blockEntities().get(entityCursor++));
                if (entityCursor < component.blockEntities().size()) return;
                completed = true;
                GreatWallMod.LOGGER.info("[GreatWall:展台] 实物模板接墙完成，素材={}，方块意图={}", asset, cursor);
                finish();
                ShowcaseGate.done(rect.name());
            } catch (RuntimeException failure) {
                // 保留未完成的记账，不能让保存标记误报五件展位全部完成。
                finish();
                GreatWallMod.LOGGER.error("[GreatWall:展台] 实物模板施工失败，素材={}，位点={}", asset, feet, failure);
            }
        }

        private void writeBlock(VoxelIntent voxel) {
            var pos = pos(voxel.pos());
            var state = palette.computeIfAbsent(voxel.state(), BlockCatalog::resolveState);
            if (level.getBlockState(pos) != state) {
                // 批量施工时邻格尚未齐全；素材已经给出围墙和栏杆的连接状态，不能让原版用半成品重算。
                level.setBlock(pos, state, StampWrite.flags(state) | Block.UPDATE_KNOWN_SHAPE);
                var actual = level.getBlockState(pos);
                if (actual != state) {
                    throw new IllegalStateException("实物展位落块被拒绝: " + pos + "，目标=" + state + "，实际=" + actual);
                }
            }
        }

        private void writeEntity(BlockEntityIntent entity) {
            var pos = pos(entity.pos());
            var receipt = PlannedBlockEntityNbt.lootReceipt(RECEIPT, entity);
            var old = level.getBlockEntity(pos);
            if (old != null && receipt != null && receipt.equals(old.getAttached(TerrainMarkers.TEMPLATE_LOOT_RECEIPT))) return;
            var replacement = BlockEntity.loadStatic(pos, level.getBlockState(pos),
                    PlannedBlockEntityNbt.encode(entity, receipt), level.registryAccess());
            if (replacement == null) throw new IllegalStateException("实物展位无法加载方块实体: " + pos);
            level.setBlockEntity(replacement);
            replacement.setChanged();
        }

        private void finish() {
            if (held) changeTickets(false);
            held = false;
            component = null;
            if (compiling != null) compiling.cancel(false);
            compiling = null;
            palette.clear();
            finished = true;
        }

        private void changeTickets(boolean add) {
            // 同一时刻只持有一件的票，不在世界启动时同步拉取五座楼的区块。
            for (int cx = rect.minX() >> 4; cx <= rect.maxX() >> 4; cx++) {
                for (int cz = rect.minZ() >> 4; cz <= rect.maxZ() >> 4; cz++) {
                    var chunk = new ChunkPos(cx, cz);
                    if (add) level.getChunkSource().addRegionTicket(TICKET, chunk, 1, chunk);
                    else level.getChunkSource().removeRegionTicket(TICKET, chunk, 1, chunk);
                }
            }
        }

        private static BlockPos pos(Point3i p) { return new BlockPos(p.x(), p.y(), p.z()); }
    }
}
