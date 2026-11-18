package local.plaincraft.greatwall.generation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import local.plaincraft.greatwall.BuildContract;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.state.BlockState;
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
    /** 门楼室内上下层分界（模板局部 y）：≥ 此高度算「上层」，兜底给军械类表。 */

    /** 一行模板：{@code loot} = 战利品表 id；{@code books} = 随机塞几本书；{@code nbt} = 原始 SNBT。 */
    private record Entry(int x, int y, int z, String loot, int books, String nbt) {
    }

    private static List<Entry> entries;

    private GateBlockEntities() {
    }

    public static void apply(ServerLevel level, int originX, int originY, int originZ) {
        apply(level, originX, originY, originZ, null, null);
    }

    /**
     * 先按 {@code gate_be.jsonl} 显式回填，再对**模板里其余容器**套用兜底规则。
     *
     * <p>兜底规则的存在意义：玩家在存档里新放一个箱子，只要重采模板，它就应该自带战利品，
     * 而不是每次都要人工往 jsonl 里补一行坐标。反过来，玩家删掉容器时这里只会找不到方块实体
     * 而跳过（计入 {@code missing}），不会凭空造内容、也不会抛异常。
     *
     * @param coords 模板格子的局部坐标（可空：为空时只做显式回填）
     * @param states 与 {@code coords} 一一对应的方块状态
     */
    public static void apply(ServerLevel level, int originX, int originY, int originZ,
                             List<int[]> coords, List<BlockState> states) {
        // 门楼自己的坐标表 + 未列出容器的兜底。
        apply(level, originX, originY, originZ, coords, states, true, "gate.csv");
    }

    /**
     * 兜底专用入口：**不读** {@code gate_be.jsonl}，只按方块 id 给容器挂默认战利品。
     *
     * <p>非门楼模板（水门、堡垒、信标…）走这条。{@code gate_be.jsonl} 里是**门楼局部坐标**
     * （x 63..76，而水门模板只有 x 0..45），套到别的模板上必然全部落空 —— 0.7.101 的超平坦水门
     * 就是这样报出 {@code missing=17} 的：方块全对，但 17 个坐标一个都没有落在水门身上。
     */
    public static void applyDefaults(ServerLevel level, int originX, int originY, int originZ,
                                     List<int[]> coords, List<BlockState> states, String template) {
        apply(level, originX, originY, originZ, coords, states, false, template);
    }

    private static void apply(ServerLevel level, int originX, int originY, int originZ,
                              List<int[]> coords, List<BlockState> states,
                              boolean explicitTable, String template) {
        List<Entry> list = explicitTable ? entries() : List.of();
        Set<Long> explicit = new HashSet<>();
        int looted = 0;
        int stocked = 0;
        int restored = 0;
        int missing = 0;
        for (Entry entry : list) {
            explicit.add(localKey(entry.x(), entry.y(), entry.z()));
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
                fillBooks(container, entry.books(), pos);
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

        // ---- 兜底：模板里没被显式指定的容器按方块类型给默认战利品 ----
        int autoLoot = 0;
        int autoBooks = 0;
        if (coords != null && states != null && coords.size() == states.size()) {
            for (int i = 0; i < coords.size(); i++) {
                int[] p = coords.get(i);
                if (p == null || p.length < 3) continue;
                if (explicit.contains(localKey(p[0], p[1], p[2]))) continue;
                BlockState state = states.get(i);
                // 一个大模板能有四十多万格（kitset.csv），只有本身带方块实体的方块才值得查注册表。
                if (state == null || !state.hasBlockEntity()) continue;
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                BlockPos pos = new BlockPos(originX + p[0], originY + p[1], originZ + p[2]);
                String table = defaultTableFor(blockId, p[1]);
                if (table != null) {
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) continue;
                    container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                            ResourceLocation.fromNamespaceAndPath(BuildContract.MOD_ID, "chests/" + table)),
                            pos.asLong() ^ SEED_SALT);
                    container.setChanged();
                    autoLoot++;
                    continue;
                }
                int books = defaultBooksFor(blockId, pos.asLong());
                if (books > 0) {
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (!(blockEntity instanceof Container container)) continue;
                    fillBooks(container, books, pos);
                    autoBooks++;
                }
            }
        }

        GreatWallMod.LOGGER.info(
                "[GreatWall:wonder] GATE_BE_APPLIED loot={} books={} nbt={} missing={} autoLoot={} autoBooks={} "
                        + "origin=({},{},{}) template={}",
                looted, stocked, restored, missing, autoLoot, autoBooks, originX, originY, originZ, template);
    }

    /**
     * 兜底规则（纯函数，便于回归）：容器方块 id + 模板局部 y → 用哪张表。
     *
     * <p>「上层」判定用 y ≥ 20（门楼室内的楼层分界），上层是军械/值钱的，下层是补给/杂物。
     * 返回 {@code null} 表示不是我们要管的容器。
     */
    static String defaultTableFor(String blockId, int localY) {
        // 展台和正式模板必须使用同一楼层分界，调表时不需要同步两处。
        return local.plaincraft.greatwall.gameplay.TemplateLoot.tableFor(blockId, localY);
    }

    /** 兜底规则：雕纹书架按坐标派生 2~3 本书，其余方块返回 0。 */
    static int defaultBooksFor(String blockId, long posSeed) {
        if ("minecraft:chiseled_bookshelf".equals(blockId)) {
            return 2 + (int) Math.floorMod(posSeed, 2L);
        }
        return 0;
    }

    private static void fillBooks(Container container, int books, BlockPos pos) {
        Random random = new Random(pos.asLong());
        int size = container.getContainerSize();
        for (int placed = 0; placed < books && placed < size; placed++) {
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
    }

    private static long localKey(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y) << 21) ^ (z & 0x1FFFFFL);
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
