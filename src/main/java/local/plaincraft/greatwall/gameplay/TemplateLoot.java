package local.plaincraft.greatwall.gameplay;

import java.util.Map;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.SiteId;

/** 为作者放置的容器冻结原版延迟抽取所需的数据，区块施工次序不参与随机。 */
public final class TemplateLoot {
    public static final String TABLE = "templateLootTable";
    public static final String SEED = "templateLootSeed";

    private TemplateLoot() {}

    public static Map<String, String> fields(String blockId, int localY, long worldSeed, SiteId site, int localId) {
        String table = tableFor(blockId, localY);
        if (table == null) return Map.of();
        // 用同一站点内稳定的容器编号区分箱桶，转向或跨区块重试不改变抽取结果。
        long seed = DeterministicHash.fields(worldSeed, "wall-template-loot/" + site.wallId() + "/" + site.kind().name(),
                site.ordinal(), localId);
        return Map.of(TABLE, "plaincraft_great_wall:chests/" + table, SEED, Long.toString(seed == 0 ? 1 : seed));
    }

    public static boolean isLootTable(String table) {
        return switch (table) {
            case "plaincraft_great_wall:chests/armory", "plaincraft_great_wall:chests/supplies",
                    "plaincraft_great_wall:chests/ordinary", "plaincraft_great_wall:chests/weathered" -> true;
            default -> false;
        };
    }

    public static String tableFor(String blockId, int localY) {
        if (blockId == null) return null;
        // 延续展台的楼层分界与表；抽取次数和数量由资源包配置，避免出现第二套掉落数值。
        return switch (blockId) {
            case "minecraft:chest", "minecraft:trapped_chest" -> localY >= 20 ? "armory" : "supplies";
            case "minecraft:barrel" -> localY >= 20 ? "armory" : "ordinary";
            default -> null;
        };
    }
}
