package local.plaincraft.greatwall.generation;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import local.plaincraft.greatwall.gameplay.TemplateLoot;
import local.plaincraft.greatwall.model.WallTypes.BlockEntityIntent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

/** 将冻结的计划实体翻译为原版实体 NBT，避免不同施工入口各写一套格式。 */
final class PlannedBlockEntityNbt {
    static final String DECORATION = "templateDecoration";

    private PlannedBlockEntityNbt() {}

    static CompoundTag encode(BlockEntityIntent entity) {
        var fields = entity.fields();
        var tag = new CompoundTag();
        String decoration = fields.get(DECORATION);
        if (decoration != null) {
            try {
                tag = TagParser.parseTag(decoration);
            } catch (CommandSyntaxException failure) {
                throw new IllegalStateException("长城模板装饰数据无效: " + entity.pos(), failure);
            }
            // 战利品、归属和世界位置只能来自本次计划，样板中的同名字段没有优先权。
            tag.remove("LootTable");
            tag.remove("LootTableSeed");
        }
        if (fields.containsKey(TemplateLoot.TABLE) || fields.containsKey(TemplateLoot.SEED)) {
            String table = fields.get(TemplateLoot.TABLE);
            String seedText = fields.get(TemplateLoot.SEED);
            if (table == null || seedText == null || !TemplateLoot.isLootTable(table)
                    || TemplateLoot.tableFor(entity.type(), 0) == null) {
                throw new IllegalStateException("长城模板容器战利品计划无效: " + entity.pos());
            }
            long seed = Long.parseLong(seedText);
            if (seed == 0) throw new IllegalStateException("长城模板战利品种子不能为零: " + entity.pos());
            // 原版读顶层的表；带样板库存会污染实际结果，首次开启时由原版生成数量。
            tag.remove("Items");
            tag.remove("components");
            tag.putString("LootTable", table);
            tag.putLong("LootTableSeed", seed);
        }
        var p = entity.pos();
        tag.putString("id", entity.type());
        tag.putInt("x", p.x());
        tag.putInt("y", p.y());
        tag.putInt("z", p.z());
        var payload = new CompoundTag();
        payload.putString("wall", entity.site().wallId());
        payload.putString("kind", entity.site().kind().name());
        payload.putLong("ordinal", entity.site().ordinal());
        payload.putInt("localId", entity.localId());
        // 旧计划的 loot 等字段只保留在原有的自定义标签里，保持冻结计划的重放语义。
        fields.forEach(payload::putString);
        tag.put("plaincraft_great_wall", payload);
        return tag;
    }
}
