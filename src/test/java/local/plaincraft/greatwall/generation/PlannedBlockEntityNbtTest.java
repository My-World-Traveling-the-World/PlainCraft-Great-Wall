package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.Map;
import local.plaincraft.greatwall.gameplay.TemplateLoot;
import local.plaincraft.greatwall.model.WallTypes.BlockEntityIntent;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannedBlockEntityNbtTest {
    private static final SiteId SITE = new SiteId("test-wall", SiteKind.GATE, 12);
    private static final Point3i POS = new Point3i(-18, 75, 274);

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void vanillaLoadsPendingTableWithoutSampleInventory() {
        // 用真实原版箱桶读计划 NBT，守住自定义标签无法触发首次开箱的边界。
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL}) {
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            String type = block == Blocks.TRAPPED_CHEST ? "minecraft:trapped_chest" : blockId;
            var fields = new HashMap<>(TemplateLoot.fields(blockId, block == Blocks.BARREL ? 20 : 19, 42, SITE, 7));
            fields.put(PlannedBlockEntityNbt.DECORATION,
                    "{x:0,y:0,z:0,id:\"minecraft:furnace\",Items:[{Slot:0b,id:\"minecraft:diamond\",count:64}],LootTable:\"minecraft:chests/simple_dungeon\",LootTableSeed:23L}");
            var tag = PlannedBlockEntityNbt.encode(new BlockEntityIntent(POS, type, SITE, 7, fields));
            assertFalse(tag.contains("Items"));
            assertEquals(POS.x(), tag.getInt("x"));
            var entity = assertInstanceOf(RandomizableContainerBlockEntity.class, BlockEntity.loadStatic(
                    new BlockPos(POS.x(), POS.y(), POS.z()), block.defaultBlockState(), tag, RegistryAccess.EMPTY));
            assertNotNull(entity.getLootTable());
            assertEquals(block == Blocks.BARREL ? "plaincraft_great_wall:chests/armory"
                    : "plaincraft_great_wall:chests/supplies", entity.getLootTable().location().toString());
            assertEquals(Long.parseLong(fields.get(TemplateLoot.SEED)), entity.getLootTableSeed());
            assertNotEquals(0L, entity.getLootTableSeed());
        }
    }

    @Test void containerSeedUsesWorldAndLocalIdentity() {
        var first = TemplateLoot.fields("minecraft:barrel", 0, 42, SITE, 7);
        assertEquals(first, TemplateLoot.fields("minecraft:barrel", 0, 42, SITE, 7));
        assertEquals("plaincraft_great_wall:chests/ordinary", first.get(TemplateLoot.TABLE));
        for (var other : java.util.List.of(TemplateLoot.fields("minecraft:barrel", 0, 42, SITE, 8),
                TemplateLoot.fields("minecraft:barrel", 0, 43, SITE, 7),
                TemplateLoot.fields("minecraft:barrel", 0, 42, new SiteId("other-wall", SiteKind.GATE, 12), 7))) {
            assertNotEquals(first.get(TemplateLoot.SEED), other.get(TemplateLoot.SEED));
        }
    }

    @Test void decorationsAndLegacyIntentKeepTheirOwnSemantics() {
        // 非箱桶的数据不应被清空；既有存档的 loot 元数据也不能变成新的抽取命令。
        var legacy = PlannedBlockEntityNbt.encode(new BlockEntityIntent(POS, "minecraft:chest", SITE, 7,
                Map.of("loot", "supplies")));
        assertFalse(legacy.contains("LootTable"));
        assertEquals("supplies", legacy.getCompound("plaincraft_great_wall").getString("loot"));
        var decorated = PlannedBlockEntityNbt.encode(new BlockEntityIntent(POS, "minecraft:banner", SITE, 7,
                Map.of(PlannedBlockEntityNbt.DECORATION, "{patterns:[{pattern:\"minecraft:stripe_top\",color:\"red\"}]}")));
        assertEquals(1, decorated.getList("patterns", 10).size());
        assertEquals("minecraft:banner", decorated.getString("id"));
    }
}
