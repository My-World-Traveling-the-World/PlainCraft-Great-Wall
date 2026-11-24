package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在结构实际落块前屏蔽整座结构；规划时扫描不能挡住后来才创建的起点。 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow public abstract BoundingBox getBoundingBox();

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void greatwall$protectFootprint(WorldGenLevel world, StructureManager manager,
            ChunkGenerator generator, RandomSource random, BoundingBox chunkBounds, ChunkPos chunk, CallbackInfo ci) {
        var level = world.getLevel();
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        var services = WorldServices.find(level.getServer());
        if (services == null || !services.productionFixture()) return;
        var box = getBoundingBox();
        // 检查整座建筑而不是本区块切片，避免只取消墙旁一块却留下另一半建筑。
        if (services.structures().intersects(new Bounds(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1))) ci.cancel();
    }
}
