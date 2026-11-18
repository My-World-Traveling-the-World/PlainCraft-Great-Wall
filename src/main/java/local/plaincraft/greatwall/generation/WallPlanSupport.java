package local.plaincraft.greatwall.generation;

import java.util.HashSet;
import java.util.Set;
import local.plaincraft.greatwall.model.WallTypes.BlockEntityIntent;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The non-block half of a {@link ChunkWritePlan}, shared by worldgen and loaded-chunk backfill.
 *
 * <p>The production landing path used to write only the voxels. That left a perfectly good-looking
 * wall with no campfire/chest identity and no persistent ownership or gameplay index. These helpers
 * 首次施工按已发布计划写实体；已完成同hash计划由入口提前返回，保留玩家后续编辑。
 */
public final class WallPlanSupport {
    private WallPlanSupport() {
    }

    /** Every planned wall column is protected, including columns already at their target state. */
    public static void markPlannedColumns(ChunkWritePlan plan) {
        if (plan == null) return;
        for (var voxel : plan.voxels()) {
            Point3i p = voxel.pos();
            local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(p.x(), p.z());
        }
    }

    /** Writes the custom block-entity payload for positions this pass actually placed. */
    public static int writeBlockEntities(ChunkAccess chunk, ChunkWritePlan plan, Set<Point3i> touched) {
        if (chunk == null || plan == null) return 0;
        int written = 0;
        for (BlockEntityIntent entity : plan.blockEntities()) {
            Point3i p = entity.pos();
            if (!owns(plan, p)) continue;
            // 部分施工只给已成功落块/已匹配目标的位置安装实体，不能给失败位置预写payload。
            if (touched != null && !touched.contains(p)) continue;
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            var existing = chunk.getBlockEntity(pos);
            // A block entity that was already present belongs to the world/player. Only fill one we
            // just created, or a missing one from an older wall, never overwrite custom data.
            if (existing != null && (touched == null || !touched.contains(p))) continue;
            String receipt = PlannedBlockEntityNbt.lootReceipt(plan.recipeHash(), entity);
            if (receipt != null) {
                // 部分施工重试时，已初始化的容器可能还在待加载 NBT 中，也可能已被玩家开空。
                if (existing != null && receipt.equals(existing.getAttached(TerrainMarkers.TEMPLATE_LOOT_RECEIPT))) continue;
                if (existing == null && !(chunk instanceof net.minecraft.world.level.chunk.LevelChunk)) {
                    var pending = chunk.getBlockEntityNbt(pos);
                    if (pending != null && receipt.equals(pending.getCompound(AttachmentTarget.NBT_ATTACHMENT_KEY)
                            .getString(TerrainMarkers.TEMPLATE_LOOT_RECEIPT_ID.toString()))) continue;
                }
            }
            var tag = PlannedBlockEntityNbt.encode(entity, receipt);
            if(chunk instanceof net.minecraft.world.level.chunk.LevelChunk loaded) {
                // 已加载区块不能只存待加载NBT：现有实体会继续生效，重开前看不到计划身份。
                var replacement=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                        pos,chunk.getBlockState(pos),tag,loaded.getLevel().registryAccess());
                if(replacement==null)throw new IllegalStateException("无法创建计划方块实体: "+entity.type()+" "+p);
                loaded.removeBlockEntity(pos);
                loaded.addAndRegisterBlockEntity(replacement);
                replacement.setChanged();
            } else {
                chunk.setBlockEntityNbt(tag);
            }
            written++;
        }
        return written;
    }

    /** Persistent production and ownership data used by gameplay, spawning and tombstone tracking. */
    public static void writeMarkers(ChunkAccess chunk, ChunkWritePlan plan) {
        writeMarkers(chunk, plan, true);
    }

    /** {@code complete=false} keeps a chunk retryable when writes were skipped or failed. */
    public static void writeMarkers(ChunkAccess chunk, ChunkWritePlan plan, boolean complete) {
        if (chunk == null || plan == null) return;
        Set<Point3i> owned = new HashSet<>();
        Set<Point3i> firepits = new HashSet<>();
        for (var voxel : plan.voxels()) {
            if (owns(plan, voxel.pos())
                    && voxel.kind() != local.plaincraft.greatwall.model.WallTypes.IntentKind.KEEP) {
                owned.add(voxel.pos());
            }
        }
        for (var entity : plan.blockEntities()) {
            if ("minecraft:campfire".equals(entity.type()) && owns(plan, entity.pos())) {
                firepits.add(entity.pos());
            }
        }
        // The plan is the durable authority for the wall footprint. Reading every voxel back here
        // would spend the drain's 150 ms budget; individual writes and conflicts are already
        // checked in settle(), and the mask deliberately protects the planned column even when a
        // player has replaced one block.
        chunk.setAttached(TerrainMarkers.OWNERSHIP,
                new TerrainMarkers.Ownership(plan.metadata().gateRevision(), owned, firepits));
        chunk.setAttached(TerrainMarkers.PRODUCTION, new TerrainMarkers.Marker(
                plan.recipeHash(), plan.metadata().coverageHash(), plan.hash(),
                plan.metadata().gateRevision(), complete, plan.walkwayFeet(), plan.blockEntities(),
                plan.metadata().spawnSlots(), plan.metadata().realizedStates()));
        chunk.setUnsaved(true);
    }

    private static boolean owns(ChunkWritePlan plan, Point3i p) {
        return Math.floorDiv(p.x(), 16) == plan.owner().x()
                && Math.floorDiv(p.z(), 16) == plan.owner().z();
    }
}
