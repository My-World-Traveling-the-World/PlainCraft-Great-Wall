package local.plaincraft.greatwall.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Resource contracts only: these tests do not authorize a chest initialization or mob loot override. */
class LootResourceTest {
    @Test
    void ordinaryMatchesFrozenWeightsAndCounts() throws IOException {
        chest("ordinary", 1, 3, List.of(
                new Item("bread", 40, 1, 3), new Item("arrow", 30, 2, 8),
                new Item("iron_nugget", 20, 2, 6), new Item("torch", 10, 2, 5)));
    }

    @Test
    void weatheredMayBeEmptyWithoutAddingExtraRewards() throws IOException {
        chest("weathered", 0, 2, List.of(
                new Item("string", 35, 1, 3), new Item("stick", 35, 2, 5),
                new Item("arrow", 20, 1, 4), new Item("iron_nugget", 10, 1, 3)));
    }

    @Test
    void suppliesMatchesFrozenWeightsAndCounts() throws IOException {
        chest("supplies", 2, 4, List.of(
                new Item("bread", 35, 2, 5), new Item("arrow", 30, 4, 12),
                new Item("iron_ingot", 20, 1, 2), new Item("leather", 15, 1, 3)));
    }

    @Test
    void armoryDoesNotRandomizeTheSingleToolsOrAddDiamonds() throws IOException {
        chest("armory", 2, 4, List.of(
                new Item("arrow", 40, 8, 16), new Item("iron_ingot", 35, 1, 3),
                new Item("crossbow", 15, 1, 1), new Item("iron_axe", 10, 1, 1)));
    }

    @Test
    void garrisonOnlyHasNinetyEmptyAndTenArrowWeight() throws IOException {
        var table = resource("entities/garrison");
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
        item(entries.get(1).getAsJsonObject(), new Item("arrow", 10, 1, 2));
        assertFalse(table.toString().contains("experience"), "XP belongs to the owned-mob guard, not this table");
    }

    private record Item(String name, int weight, int min, int max) {}

    private static void chest(String name, int minRolls, int maxRolls, List<Item> expected) throws IOException {
        var pool = pool(resource("chests/" + name), "minecraft:chest");
        assertEquals(Set.of("rolls", "entries"), pool.keySet());
        uniform(pool.getAsJsonObject("rolls"), minRolls, maxRolls);
        JsonArray entries = pool.getAsJsonArray("entries");
        assertEquals(expected.size(), entries.size());
        int weight = 0;
        var names = new HashSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i).getAsJsonObject();
            item(entry, expected.get(i));
            assertTrue(names.add(entry.get("name").getAsString()), "duplicate reward");
            weight = Math.addExact(weight, entry.get("weight").getAsInt());
        }
        assertEquals(100, weight);
        assertFalse(names.contains("minecraft:diamond"));
        assertTrue(names.stream().noneMatch(value -> value.contains("netherite")));
    }

    private static JsonObject pool(JsonObject table, String type) {
        assertEquals(Set.of("type", "pools"), table.keySet(), "no surprise reward functions or references");
        assertEquals(type, table.get("type").getAsString());
        assertEquals(1, table.getAsJsonArray("pools").size());
        return table.getAsJsonArray("pools").get(0).getAsJsonObject();
    }

    private static void item(JsonObject entry, Item expected) {
        assertEquals(Set.of("type", "name", "weight", "functions"), entry.keySet());
        assertEquals("minecraft:item", entry.get("type").getAsString());
        assertEquals("minecraft:" + expected.name(), entry.get("name").getAsString());
        assertEquals(expected.weight(), entry.get("weight").getAsInt());
        assertEquals(1, entry.getAsJsonArray("functions").size());
        var function = entry.getAsJsonArray("functions").get(0).getAsJsonObject();
        assertEquals(Set.of("function", "count"), function.keySet(), "no additive count or hidden conditions");
        assertEquals("minecraft:set_count", function.get("function").getAsString());
        if (expected.min() == expected.max()) {
            assertTrue(function.get("count").isJsonPrimitive());
            assertEquals(expected.min(), function.get("count").getAsInt());
        } else {
            uniform(function.getAsJsonObject("count"), expected.min(), expected.max());
        }
    }

    private static void uniform(JsonObject provider, int min, int max) {
        assertEquals(Set.of("type", "min", "max"), provider.keySet());
        assertEquals("minecraft:uniform", provider.get("type").getAsString());
        assertEquals(min, provider.get("min").getAsInt());
        assertEquals(max, provider.get("max").getAsInt());
    }

    private static JsonObject resource(String path) throws IOException {
        String name = "data/plaincraft_great_wall/loot_table/" + path + ".json";
        var input = LootResourceTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, name);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
