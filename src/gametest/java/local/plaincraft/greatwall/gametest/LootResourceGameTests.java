package local.plaincraft.greatwall.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Native datapack loading and sampling of the themed chest tables, not first-writer authorization or
 * entity loot integration.
 *
 * <p>The old tests froze each table to a hand-written four-item reward set. Commit {@code 30fe29d6}
 * ("gate loot: three themed layers plus tag-driven mod item pools") deliberately replaced that with
 * three pools per table - a guaranteed theme item, a themed common pool and a rare pool - whose items
 * come from {@code plaincraft_great_wall:loot/*} tags so other mods' items (Artifacts, conventional
 * food/tool tags) can join a chest. The frozen literal set is therefore no longer a product contract;
 * what still has to hold is that the runtime table samples <em>only</em> what its own JSON declares,
 * inside the roll/count envelope that JSON declares, deterministically per seed. That envelope is read
 * from the datapack itself, so a reward change is a data change and not a test edit.
 */
public final class LootResourceGameTests implements FabricGameTest {
    private static final String MOD = "plaincraft_great_wall";
    private static final int SAMPLES = 256;

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void ordinaryNativeTableSamplesOnlyItsDeclaredRewards(GameTestHelper helper) throws IOException {
        sampleChest(helper, "ordinary", "minecraft:stick");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void weatheredNativeTableAlwaysYieldsItsGuaranteedThreadPool(GameTestHelper helper) throws IOException {
        // The retired contract said a weathered chest may be empty (zero rolls). The themed table always
        // rolls its fixed string pool, so the surviving contract is the opposite one: the guaranteed pool
        // is present in every sample.
        sampleChest(helper, "weathered", "minecraft:string");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void suppliesNativeTableSamplesOnlyItsDeclaredRewards(GameTestHelper helper) throws IOException {
        sampleChest(helper, "supplies", "minecraft:bread");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void armoryNativeTableSamplesOnlyItsDeclaredRewards(GameTestHelper helper) throws IOException {
        sampleChest(helper, "armory", "minecraft:arrow");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void garrisonIsAnActualEntityLootTableRatherThanTheMissingTableFallback(GameTestHelper helper) {
        table(helper, "entities/garrison", LootContextParamSets.ENTITY);
        helper.succeed();
    }

    private static void sampleChest(GameTestHelper helper, String name, String guaranteed) throws IOException {
        var declared = declaredTable(helper, name);
        var table = table(helper, "chests/" + name, LootContextParamSets.CHEST);
        var params = params(helper);
        var seen = new TreeSet<String>();
        for (long index = 1; index <= SAMPLES; index++) {
            long seed = seed(index);
            var result = table.getRandomItems(params, seed);
            helper.assertTrue(!result.isEmpty(), "the guaranteed first pool never samples an empty chest: " + name);
            int total = 0;
            for (var stack : result) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                helper.assertTrue(declared.items().contains(id),
                        "the native table sampled a reward its own JSON never declares: " + id + " from " + name);
                helper.assertTrue(!stack.isEmpty() && stack.getCount() > 0, "native reward stack must not be empty");
                var range = declared.counts().get(id);
                helper.assertTrue(stack.getCount() >= range[0] && stack.getCount() <= range[1],
                        "declared count range violated by " + id + ": " + stack.getCount()
                                + " not in [" + range[0] + "," + range[1] + "] from " + name);
                seen.add(id);
                total = Math.addExact(total, stack.getCount());
            }
            helper.assertTrue(total >= declared.minTotal() && total <= declared.maxTotal(),
                    "the table's own roll/count envelope is " + declared.minTotal() + ".." + declared.maxTotal()
                            + ", sampled " + total + " from " + name);
            var repeated = table.getRandomItems(params, seed);
            helper.assertTrue(result.size() == repeated.size(), "seed replay changes stack count");
            for (int i = 0; i < result.size(); i++) {
                helper.assertTrue(ItemStack.matches(result.get(i), repeated.get(i)),
                        "native seed replay changes reward content");
            }
        }
        helper.assertTrue(seen.contains(guaranteed),
                "the guaranteed first pool must fire within " + SAMPLES + " seeds: " + guaranteed + " from " + name);
        System.out.println("S1B_LOOT " + name + " samples=" + SAMPLES + " distinct=" + seen.size()
                + " envelope=" + declared.minTotal() + ".." + declared.maxTotal() + " seen=" + seen);
    }

    /** Items, per-item count range and total envelope that one table's own JSON declares. */
    private record Declared(Set<String> items, Map<String, int[]> counts, int minTotal, int maxTotal) {}

    private static Declared declaredTable(GameTestHelper helper, String name) throws IOException {
        var manager = helper.getLevel().getServer().getResourceManager();
        // ResourceManager keys are file paths relative to the namespace root, extension included.
        var resource = manager.getResource(ResourceLocation.fromNamespaceAndPath(MOD, "loot_table/chests/" + name + ".json"));
        if (resource.isEmpty()) {
            resource = manager.listResources("loot_table",
                            id -> id.getNamespace().equals(MOD) && id.getPath().endsWith("chests/" + name + ".json"))
                    .values().stream().findFirst();
        }
        var json = resource.orElseThrow(
                () -> new AssertionError("the datapack JSON for chests/" + name + " is missing"));
        JsonElement root;
        try (var reader = json.openAsReader()) {
            root = JsonParser.parseReader(reader);
        }
        var items = new HashSet<String>();
        var counts = new HashMap<String, int[]>();
        int minTotal = 0;
        int maxTotal = 0;
        for (var poolElement : root.getAsJsonObject().getAsJsonArray("pools")) {
            var pool = poolElement.getAsJsonObject();
            var rolls = rolls(pool.get("rolls"));
            int poolMin = Integer.MAX_VALUE;
            int poolMax = 0;
            for (var entryElement : pool.getAsJsonArray("entries")) {
                var entry = entryElement.getAsJsonObject();
                var entryItems = entryItems(entry);
                var count = countRange(entry);
                // A tag whose owner mod is absent (artifacts:artifacts) can be picked and yields nothing, so
                // it lowers the achievable floor; it cannot raise the ceiling.
                poolMin = Math.min(poolMin, entryItems.isEmpty() ? 0 : count[0]);
                if (entryItems.isEmpty()) continue;
                poolMax = Math.max(poolMax, count[1]);
                for (String item : entryItems) {
                    items.add(item);
                    var existing = counts.get(item);
                    counts.put(item, existing == null ? count
                            : new int[]{Math.min(existing[0], count[0]), Math.max(existing[1], count[1])});
                }
            }
            if (poolMin == Integer.MAX_VALUE) poolMin = 0;
            minTotal = Math.addExact(minTotal, Math.multiplyExact(rolls[0], poolMin));
            maxTotal = Math.addExact(maxTotal, Math.multiplyExact(rolls[1], poolMax));
        }
        return new Declared(Set.copyOf(items), Map.copyOf(counts), minTotal, maxTotal);
    }

    private static int[] rolls(JsonElement element) {
        if (element == null || element.isJsonNull()) return new int[]{1, 1};
        if (element.isJsonPrimitive()) {
            int fixed = element.getAsInt();
            return new int[]{fixed, fixed};
        }
        var object = element.getAsJsonObject();
        return switch (object.get("type").getAsString()) {
            case "minecraft:uniform" -> new int[]{object.get("min").getAsInt(), object.get("max").getAsInt()};
            case "minecraft:binomial" -> new int[]{0, object.get("n").getAsInt()};
            default -> throw new AssertionError("unknown roll provider " + object);
        };
    }

    private static int[] countRange(JsonObject entry) {
        int min = 1;
        int max = 1;
        if (!entry.has("functions")) return new int[]{min, max};
        for (var functionElement : entry.getAsJsonArray("functions")) {
            var function = functionElement.getAsJsonObject();
            if (!function.has("function") || !"minecraft:set_count".equals(function.get("function").getAsString())) continue;
            var count = function.getAsJsonObject("count");
            if (count == null) continue;
            if (count.has("type") && "minecraft:uniform".equals(count.get("type").getAsString())) {
                min = count.get("min").getAsInt();
                max = count.get("max").getAsInt();
            } else if (count.isJsonPrimitive()) {
                min = count.getAsInt();
                max = min;
            }
        }
        return new int[]{min, max};
    }

    private static Set<String> entryItems(JsonObject entry) {
        var type = entry.get("type").getAsString();
        var name = entry.get("name").getAsString();
        if ("minecraft:item".equals(type)) return Set.of(name);
        if (!"minecraft:tag".equals(type)) throw new AssertionError("unknown loot entry type " + type);
        var resolved = new HashSet<String>();
        BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, ResourceLocation.parse(name)))
                .ifPresent(holders -> holders.stream().forEach(holder -> resolved.add(
                        holder.unwrapKey().orElseThrow().location().toString())));
        return resolved;
    }

    private static LootTable table(GameTestHelper helper, String path, LootContextParamSet expected) {
        var id = ResourceLocation.fromNamespaceAndPath(MOD, path);
        var registries = helper.getLevel().getServer().reloadableRegistries();
        helper.assertTrue(registries.getKeys(Registries.LOOT_TABLE).contains(id), "actual datapack key is missing: " + id);
        var table = registries.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
        helper.assertTrue(table != LootTable.EMPTY, "missing table must not silently become EMPTY: " + id);
        helper.assertTrue(table.getParamSet() == expected, "native loot context differs: " + id);
        return table;
    }

    private static LootParams params(GameTestHelper helper) {
        return new LootParams.Builder(helper.getLevel()).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
    }

    private static long seed(long index) {
        return index * 0x9E3779B97F4A7C15L;
    }
}
