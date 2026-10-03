package local.plaincraft.greatwall.gametest;

import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** Native datapack loading and sampling, not first-writer authorization or entity loot integration. */
public final class LootResourceGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void ordinaryNativeTableContainsOnlyTheFourFrozenRewards(GameTestHelper helper) {
        sampleChest(helper, "ordinary", Set.of("bread", "arrow", "iron_nugget", "torch"), 1, 24);
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void weatheredNativeTableCanProduceAnEmptyResult(GameTestHelper helper) {
        sampleChest(helper, "weathered", Set.of("string", "stick", "arrow", "iron_nugget"), 0, 10);
        var params = params(helper);
        var table = table(helper, "chests/weathered", LootContextParamSets.CHEST);
        boolean empty = false;
        for (long index = 1; index <= 256; index++) empty |= table.getRandomItems(params, seed(index)).isEmpty();
        helper.assertTrue(empty, "zero rolls must remain possible with the native uniform number provider");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void suppliesNativeTableContainsNoRewardEscalation(GameTestHelper helper) {
        sampleChest(helper, "supplies", Set.of("bread", "arrow", "iron_ingot", "leather"), 2, 48);
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void armoryNativeTableIsDeterministicForEveryNonzeroSeed(GameTestHelper helper) {
        sampleChest(helper, "armory", Set.of("arrow", "iron_ingot", "crossbow", "iron_axe"), 2, 64);
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void garrisonIsAnActualEntityLootTableRatherThanTheMissingTableFallback(GameTestHelper helper) {
        table(helper, "entities/garrison", LootContextParamSets.ENTITY);
        helper.succeed();
    }

    private static void sampleChest(GameTestHelper helper, String name, Set<String> allowed, int min, int max) {
        var table = table(helper, "chests/" + name, LootContextParamSets.CHEST);
        var params = params(helper);
        var seen = new HashSet<String>();
        for (long index = 1; index <= 256; index++) {
            long seed = seed(index);
            var result = table.getRandomItems(params, seed);
            int count = 0;
            for (var stack : result) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                helper.assertTrue(id.startsWith("minecraft:") && allowed.contains(id.substring("minecraft:".length())),
                        "unexpected native reward " + id + " from " + name);
                helper.assertTrue(!stack.isEmpty() && stack.getCount() > 0, "native reward stack must not be empty");
                seen.add(id.substring("minecraft:".length()));
                count = Math.addExact(count, stack.getCount());
            }
            helper.assertTrue(count >= min && count <= max, "roll/count bounds violated by " + name);
            var repeated = table.getRandomItems(params, seed);
            helper.assertTrue(result.size() == repeated.size(), "seed replay changes stack count");
            for (int i = 0; i < result.size(); i++) {
                helper.assertTrue(net.minecraft.world.item.ItemStack.matches(result.get(i), repeated.get(i)),
                        "native seed replay changes reward content");
            }
        }
        helper.assertTrue(seen.equals(allowed), "a declared reward disappeared from native sampling: " + name);
    }

    private static LootTable table(GameTestHelper helper, String path, LootContextParamSet expected) {
        var id = ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", path);
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
