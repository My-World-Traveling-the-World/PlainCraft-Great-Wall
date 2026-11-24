package local.plaincraft.greatwall.client.config;

import local.plaincraft.greatwall.config.GreatWallConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 只展示会影响当前走线和调度的参数，避免玩家调到已停用的山脊权重或采样档位。 */
public final class GreatWallConfigScreen {
    private GreatWallConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("screen.plaincraft_great_wall.config"));
        builder.setSavingRunnable(GreatWallConfig::save);

        GreatWallConfig conf = GreatWallConfig.get();
        GreatWallConfig def = new GreatWallConfig();
        ConfigEntryBuilder eb = builder.entryBuilder();

        ConfigCategory length = builder.getOrCreateCategory(
                Component.translatable("config.plaincraft_great_wall.category.length"));
        ConfigCategory ridge = builder.getOrCreateCategory(
                Component.translatable("config.plaincraft_great_wall.category.ridge"));
        ConfigCategory perf = builder.getOrCreateCategory(
                Component.translatable("config.plaincraft_great_wall.category.performance"));

        // ── 长度 ──
        length.addEntry(eb
                .startLongField(Component.translatable("option.plaincraft_great_wall.wallLength"),
                        conf.wallLengthBlocks())
                .setDefaultValue(def.wallLengthBlocks())
                .setTooltip(Component.translatable("option.plaincraft_great_wall.wallLength.tooltip"))
                // 界面和服务端读取 JSON 限制相同；保留 0 的独立无限模式。
                .setMin(0L).setMax(GreatWallConfig.MAX_WALL_LENGTH_BLOCKS)
                .setSaveConsumer(v -> GreatWallConfig.get().setWallLengthBlocks(v))
                .build());
        // 无限墙只指定开局窗口，不改变墙的终点或已存档的配方。
        length.addEntry(eb
                .startIntField(Component.translatable("option.plaincraft_great_wall.unlimitedPrewarmBlocks"),
                        conf.unlimitedPrewarmBlocks())
                .setDefaultValue(def.unlimitedPrewarmBlocks()).setMin(1)
                .setMax(GreatWallConfig.MAX_UNLIMITED_PREWARM_BLOCKS)
                .setTooltip(Component.translatable("option.plaincraft_great_wall.unlimitedPrewarmBlocks.tooltip"))
                .setSaveConsumer(conf::setUnlimitedPrewarmBlocks)
                .build());

        // 新版先选陆地，再比较坡度和走向；旧的水域权重和精采抽稀不再出现在界面。
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_slope_weight"),
                        conf.ridgeSlopeWeight())
                .setDefaultValue(def.ridgeSlopeWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_slope_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeSlopeWeight)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_turn_weight"),
                        conf.ridgeTurnWeight())
                .setDefaultValue(def.ridgeTurnWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_turn_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeTurnWeight)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_departure_weight"),
                        conf.ridgeDepartureWeight())
                .setDefaultValue(def.ridgeDepartureWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_departure_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeDepartureWeight)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_accumulated_slope_weight"),
                        conf.ridgeAccumulatedSlopeWeight())
                .setDefaultValue(def.ridgeAccumulatedSlopeWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_accumulated_slope_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeAccumulatedSlopeWeight)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.obstacle_margin_blocks"),
                        conf.obstacleMarginBlocks())
                .setDefaultValue(def.obstacleMarginBlocks())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.obstacle_margin_blocks.tooltip"))
                .setMin(0).setMax(32)
                .setSaveConsumer(conf::setObstacleMarginBlocks)
                .build());
        // 性能调度：只改「什么时候算、一次算多少、复用多少」，不改变任何采样结果，
        // 因此不进配方语义哈希 —— 改这些不会让已有世界 RECIPE_MISMATCH。
        perf.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.fine_sample_driver_interval_ms"),
                        conf.fineSampleDriverIntervalMs())
                .setDefaultValue(def.fineSampleDriverIntervalMs())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.fine_sample_driver_interval_ms.tooltip"))
                .setMin(1).setMax(50)
                .setSaveConsumer(conf::setFineSampleDriverIntervalMs)
                .build());
        perf.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.lane_batch_operations"),
                        conf.laneBatchOperations())
                .setDefaultValue(def.laneBatchOperations())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.lane_batch_operations.tooltip"))
                .setMin(1).setMax(256)
                .setSaveConsumer(conf::setLaneBatchOperations)
                .build());
        perf.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.chunk_cache_limit"),
                        conf.chunkCacheLimit())
                .setDefaultValue(def.chunkCacheLimit())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.chunk_cache_limit.tooltip"))
                .setMin(4).setMax(256)
                .setSaveConsumer(conf::setChunkCacheLimit)
                .build());
        return builder.build();
    }
}
