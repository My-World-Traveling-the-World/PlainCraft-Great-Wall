package local.plaincraft.greatwall.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 战利品资源契约（0.7.99 三层设计）。
 *
 * <p>这层测试只钉**资源文件**，不授权任何箱子初始化或生物掉落覆写。0.7.99 把四张宝箱表从
 * 「单池加权」改成「必给层 + 主池 + 稀有池」三层，主池/稀有池用标签引用（`#c:tools/swords`、
 * `#c:foods/bread`、`artifacts:artifacts`…）以便整合包 mod 物品自动进池。因此这里钉住：
 * <ul>
 *   <li>每张表恰好三层，且三层都不吃幸运附魔（任何池子都不得出现 `bonus_rolls`）；</li>
 *   <li>必给层固定 1 抽（保证不空手），主池抽数、稀有触发概率按设计值冻结；</li>
 *   <li>标签引用只允许出现在白名单里，且引用的标签文件必须真实存在；</li>
 *   <li>不出现钻石/下界合金/经验（平衡红线，与旧版一致）；</li>
 *   <li>`gate_be.jsonl` 里引用的战利品表 id 必须都有对应资源文件。</li>
 * </ul>
 */
class LootResourceTest {

    /** 每张表：主池抽数 + 稀有触发概率。 */
    private static final Map<String, double[]> TIERS = Map.of(
            "armory", new double[] {3, 5, 0.10},
            "supplies", new double[] {3, 5, 0.08},
            "ordinary", new double[] {2, 4, 0.06},
            "weathered", new double[] {2, 4, 0.05});

    /** 允许被战利品表引用的外部标签（其余一律必须是我们自己的 `plaincraft_great_wall:loot/*`）。 */
    private static final Set<String> ALLOWED_EXTERNAL_TAGS = Set.of(
            "artifacts:artifacts",
            "minecraft:decorated_pot_sherds");

    @Test
    void everyChestHasThreeTiersAndNeverEatsLuck() throws IOException {
        for (var entry : TIERS.entrySet()) {
            String name = entry.getKey();
            double[] tier = entry.getValue();
            JsonObject table = resource("loot_table/chests/" + name);
            assertEquals(Set.of("type", "pools"), table.keySet(), name + ": no surprise fields");
            assertEquals("minecraft:chest", table.get("type").getAsString());
            JsonArray pools = table.getAsJsonArray("pools");
            assertEquals(3, pools.size(), name + ": 必给 / 主池 / 稀有 三层");

            // 必给层：固定 1 抽，不吃幸运
            JsonObject guaranteed = pools.get(0).getAsJsonObject();
            assertEquals(Set.of("rolls", "entries"), guaranteed.keySet(), name + ": 必给层不得带 bonus_rolls");
            assertEquals(1, guaranteed.get("rolls").getAsInt(), name + ": 必给层固定 1 抽");
            assertEquals(1, guaranteed.getAsJsonArray("entries").size(), name + ": 必给层只有一条");

            // 主池：uniform 抽数
            JsonObject common = pools.get(1).getAsJsonObject();
            assertEquals(Set.of("rolls", "entries"), common.keySet(), name + ": 主池不得带 bonus_rolls");
            uniform(common.getAsJsonObject("rolls"), (int) tier[0], (int) tier[1]);
            assertTrue(common.getAsJsonArray("entries").size() >= 4, name + ": 主池至少 4 条");

            // 稀有池：binomial 触发概率
            JsonObject rare = pools.get(2).getAsJsonObject();
            assertEquals(Set.of("rolls", "entries"), rare.keySet(), name + ": 稀有池不得带 bonus_rolls");
            JsonObject rolls = rare.getAsJsonObject("rolls");
            assertEquals("minecraft:binomial", rolls.get("type").getAsString());
            assertEquals(1, rolls.get("n").getAsInt());
            assertEquals(tier[2], rolls.get("p").getAsDouble(), 1.0e-9);
            assertTrue(rare.getAsJsonArray("entries").size() >= 3, name + ": 稀有池至少 3 条");
        }
    }

    @Test
    void tagReferencesStayInsideTheWhitelist() throws IOException {
        for (String name : TIERS.keySet()) {
            JsonArray pools = resource("loot_table/chests/" + name).getAsJsonArray("pools");
            for (JsonElement poolElement : pools) {
                for (JsonElement entryElement : poolElement.getAsJsonObject().getAsJsonArray("entries")) {
                    JsonObject entry = entryElement.getAsJsonObject();
                    if (!"minecraft:tag".equals(entry.get("type").getAsString())) continue;
                    String tag = entry.get("name").getAsString();
                    boolean ours = tag.startsWith("plaincraft_great_wall:loot/");
                    assertTrue(ours || ALLOWED_EXTERNAL_TAGS.contains(tag), name + ": 未登记的标签引用 " + tag);
                    assertTrue(entry.get("expand").getAsBoolean(), name + ": 标签必须 expand");
                }
            }
        }
    }

    @Test
    void ourOwnLootTagsOnlyReferenceOptionalExternalTags() throws IOException {
        for (String file : List.of("armory_common", "armory_rare", "supplies_common",
                "supplies_rare", "ordinary_common", "ordinary_rare", "weathered_common")) {
            JsonObject tag = resource("tags/item/loot/" + file);
            assertEquals(Set.of("replace", "values"), tag.keySet(), file);
            assertFalse(tag.get("replace").getAsBoolean());
            JsonArray values = tag.getAsJsonArray("values");
            assertFalse(values.isEmpty(), file + ": 不能是空标签");
            for (JsonElement value : values) {
                if (value.isJsonObject()) {
                    JsonObject ref = value.getAsJsonObject();
                    String id = ref.get("id").getAsString();
                    assertTrue(id.startsWith("#"), file + ": 对象形式必须是标签引用");
                    assertFalse(ref.get("required").getAsBoolean(),
                            file + ": 外部标签必须 required=false，否则缺 mod 时整包加载失败");
                } else {
                    String id = value.getAsString();
                    assertFalse(id.startsWith("#"), file + ": 裸字符串必须是具体物品 id");
                    assertTrue(id.contains(":"), file + ": 物品 id 必须带命名空间");
                }
            }
        }
    }

    @Test
    void noDiamondNoNetheriteNoExperienceAnywhere() throws IOException {
        for (String name : TIERS.keySet()) {
            String table = resource("loot_table/chests/" + name).toString();
            assertFalse(table.contains("minecraft:diamond"), name + ": 红线 —— 不出钻石");
            assertFalse(table.contains("netherite"), name + ": 红线 —— 不出下界合金");
            assertFalse(table.contains("experience"), name + ": 经验不属于宝箱表");
        }
    }

    @Test
    void gateBlockEntityTemplatePointsAtRealTables() throws IOException {
        String name = "assets/plaincraft-great-wall/templates/gate_be.jsonl";
        InputStream input = LootResourceTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, name);
        int lines = 0;
        int lootLines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
                lines++;
                JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
                Set<String> keys = new HashSet<>();
                json.keySet().forEach(keys::add);
                assertTrue(Set.of("x", "y", "z", "loot", "books", "nbt").containsAll(keys),
                        name + ": 未知字段 " + keys);
                if (json.has("loot")) {
                    lootLines++;
                    String id = json.get("loot").getAsString();
                    assertTrue(id.startsWith("plaincraft_great_wall:chests/"), name + ": 只允许自己的四张表");
                    String tableName = id.substring("plaincraft_great_wall:".length());
                    assertNotNull(LootResourceTest.class.getClassLoader()
                                    .getResourceAsStream("data/plaincraft_great_wall/loot_table/"
                                            + tableName.replace(':', '/') + ".json"),
                            name + ": 引用了不存在的表 " + id);
                }
                if (json.has("books")) {
                    int books = json.get("books").getAsInt();
                    assertTrue(books >= 1 && books <= 6, name + ": 雕纹书架只有 6 格");
                }
            }
        }
        assertTrue(lines >= 15, name + ": 门楼至少 15 个方块实体（13 个容器 + 4 个书架，去重后 17）");
        assertEquals(13, lootLines, name + ": 13 个容器挂战利品表");
    }

    @Test
    void garrisonOnlyHasNinetyEmptyAndTenArrowWeight() throws IOException {
        var table = resource("loot_table/entities/garrison");
        var pool = pool(table, "minecraft:entity");
        assertEquals(Set.of("rolls", "entries"), pool.keySet());
        assertTrue(pool.get("rolls").isJsonPrimitive());
        assertEquals(1, pool.get("rolls").getAsInt());
        var entries = pool.getAsJsonArray("entries");
        assertEquals(2, entries.size());
        var empty = entries.get(0).getAsJsonObject();
        assertEquals(Set.of("type", "weight"), empty.keySet());
        assertEquals("minecraft:empty", empty.get("type").getAsString());
        assertEquals(90, empty.get("weight").getAsInt());
        var arrow = entries.get(1).getAsJsonObject();
        assertEquals("minecraft:arrow", arrow.get("name").getAsString());
        assertEquals(10, arrow.get("weight").getAsInt());
        assertFalse(table.toString().contains("experience"), "XP belongs to the owned-mob guard, not this table");
    }

    private static JsonObject pool(JsonObject table, String type) {
        assertEquals(type, table.get("type").getAsString());
        assertEquals(1, table.getAsJsonArray("pools").size());
        return table.getAsJsonArray("pools").get(0).getAsJsonObject();
    }

    private static void uniform(JsonObject provider, int min, int max) {
        assertEquals(Set.of("type", "min", "max"), provider.keySet());
        assertEquals("minecraft:uniform", provider.get("type").getAsString());
        assertEquals(min, provider.get("min").getAsInt());
        assertEquals(max, provider.get("max").getAsInt());
    }

    private static JsonObject resource(String path) throws IOException {
        String name = "data/plaincraft_great_wall/" + path + ".json";
        var input = LootResourceTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, name);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
