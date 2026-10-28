package local.plaincraft.greatwall.generation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 门楼的**方块实体（BlockEntity）模板**：箱子/木桶挂战利品表、雕纹书架塞书、吊牌写文字。
 *
 * <p>为什么要单独一层：{@code gate.csv} 只记录「方块 + 方块状态」，箱子里装什么、书架有几本书、
 * 吊牌写什么字，全部存在区块的 {@code block_entities} 里，CSV 带不出来。于是把存档里
 * **门楼范围内**的方块实体抽成 {@code gate_be.jsonl}（一行一个，局部坐标），盖章结束时按坐标回填。
 *
 * <p>战利品表设计（见 {@code data/plaincraft_great_wall/loot_table/chests/*.json}）：
 * 必给层 + 主池 + 稀有池三层，<b>不吃幸运附魔</b>（所有池子 {@code bonus_rolls} 省略 = 0），
 * 主池/稀有池用标签引用（{@code #c:tools/swords}、{@code #c:foods/bread}、{@code artifacts:artifacts}…）
 * ，所以整合包里打对了标签的 mod 物品会自动进池，不需要逐个枚举。
 *
 * <p>种子：用「世界坐标派生」的固定值而不是 0。0 在部分版本里表示「每次打开都重掷」，
 * 这里要的是**同一个箱子在世界里永远同一份、不同箱子之间不同**，所以取 {@code pos.asLong()} 派生。
 */
public final class GateBlockEntities {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/gate_be.jsonl";
    private static final long SEED_SALT = 0x9E3779B97F4A7C15L;

    /** 一行模板：{@code loot} = 战利品表 id；{@code books} = 随机塞几本书；{@code nbt} = 原始 SNBT。 */
    private record Entry(int x, int y, int z, String loot, int books, String nbt) {
    }

    private static List<Entry> entries;

    private GateBlockEntities() {
    }

    public static void apply(ServerLevel level, int originX, int originY, int originZ) {
        List<Entry> list = entries();
        if (list.isEmpty()) return;
        int looted = 0;
        int stocked = 0;
        int restored = 0;
        int missing = 0;
        for (Entry entry : list) {
            BlockPos pos = new BlockPos(originX + entry.x(), originY + entry.y(), originZ + entry.z());
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                missing++;
                continue;
            }
            if (entry.loot() != null) {
                if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
                    missing++;
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(entry.loot());
                if (id == null) continue;
                container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, id),
                        pos.asLong() ^ SEED_SALT);
                container.setChanged();
                looted++;
            } else if (entry.books() > 0) {
                if (!(blockEntity instanceof Container container)) {
                    missing++;
                    continue;
                }
                Random random = new Random(pos.asLong());
                int size = container.getContainerSize();
                for (int placed = 0; placed < entry.books() && placed < size; placed++) {
                    int slot;
                    int guard = 0;
                    do {
                        slot = random.nextInt(size);
                    } while (!container.getItem(slot).isEmpty() && ++guard < 16);
                    if (container.getItem(slot).isEmpty()) {
                        container.setItem(slot, new ItemStack(Items.BOOK));
                    }
                }
                container.setChanged();
                stocked++;
            } else if (entry.nbt() != null) {
                try {
                    CompoundTag tag = TagParser.parseTag(entry.nbt());
                    blockEntity.loadWithComponents(tag, level.registryAccess());
                    blockEntity.setChanged();
                    restored++;
                } catch (Exception broken) {
                    GreatWallMod.LOGGER.warn("[GreatWall:wonder] GATE_BE_NBT_FAILED pos=({},{},{}) ({})",
                            pos.getX(), pos.getY(), pos.getZ(), broken.toString());
                }
            }
        }
        GreatWallMod.LOGGER.info(
                "[GreatWall:wonder] GATE_BE_APPLIED loot={} books={} nbt={} missing={} origin=({},{},{})",
                looted, stocked, restored, missing, originX, originY, originZ);
    }

    private static List<Entry> entries() {
        List<Entry> cached = entries;
        if (cached != null) return cached;
        List<Entry> loaded = new ArrayList<>();
        try (InputStream in = GateBlockEntities.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
                    try {
                        JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
                        loaded.add(new Entry(
                                json.get("x").getAsInt(),
                                json.get("y").getAsInt(),
                                json.get("z").getAsInt(),
                                json.has("loot") ? json.get("loot").getAsString() : null,
                                json.has("books") ? json.get("books").getAsInt() : 0,
                                json.has("nbt") ? json.get("nbt").getAsString() : null));
                    } catch (Exception broken) {
                        GreatWallMod.LOGGER.warn("[GreatWall:wonder] GATE_BE_PARSE_FAILED line={} ({})",
                                trimmed, broken.toString());
                    }
                }
            }
        } catch (Exception broken) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] GATE_BE_LOAD_FAILED resource={} ({})",
                    RESOURCE, broken.toString());
        }
        entries = loaded;
        return loaded;
    }
}
