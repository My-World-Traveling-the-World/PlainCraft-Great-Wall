package local.plaincraft.greatwall.client.config;

import local.plaincraft.greatwall.config.GreatWallConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The wall's player-facing options, built on Cloth Config so the fine-grained
 * ridge-scoring parameters are editable with the same control set RoadWeaver uses.
 *
 * <p>This replaces the hand-written {@code Screen} that exposed only the wall
 * length. Every ridge-scoring weight is now a labelled entry with a default and a
 * tooltip, grouped into one category so the player can tune how the wall avoids
 * knife-edge ridges without reading the source.</p>
 */
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
        // Deliberately its own category: unlike the performance knobs above, these change what the
        // sampler does, so they must not sit under a heading that promises "no effect on results".
        ConfigCategory sampling = builder.getOrCreateCategory(
                Component.translatable("config.plaincraft_great_wall.category.sampling"));

        // ── 长度 ──
        length.addEntry(eb
                .startLongField(Component.translatable("option.plaincraft_great_wall.wallLength"),
                        conf.wallLengthBlocks())
                .setDefaultValue(def.wallLengthBlocks())
                .setTooltip(Component.translatable("option.plaincraft_great_wall.wallLength.tooltip"))
                .setMin(0L)
                .setSaveConsumer(v -> GreatWallConfig.get().setWallLengthBlocks(v))
                .build());

        // ── 山脊打分（最细粒度奖励惩罚体系）──
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_weight"),
                        conf.ridgeWeight())
                .setDefaultValue(def.ridgeWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeWeight)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_prominence_weight"),
                        conf.ridgeProminenceWeight())
                .setDefaultValue(def.ridgeProminenceWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_prominence_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeProminenceWeight)
                .build());
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
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_wet_weight"),
                        conf.ridgeWetWeight())
                .setDefaultValue(def.ridgeWetWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_wet_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeWetWeight)
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
                .startIntField(Component.translatable("config.plaincraft_great_wall.summit_flat_threshold"),
                        conf.summitFlatThreshold())
                .setDefaultValue(def.summitFlatThreshold())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.summit_flat_threshold.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(conf::setSummitFlatThreshold)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.summit_flat_reward"),
                        conf.summitFlatReward())
                .setDefaultValue(def.summitFlatReward())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.summit_flat_reward.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setSummitFlatReward)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.knife_ridge_penalty"),
                        conf.knifeRidgePenalty())
                .setDefaultValue(def.knifeRidgePenalty())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.knife_ridge_penalty.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setKnifeRidgePenalty)
                .build());
        ridge.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.ridge_asymmetry_weight"),
                        conf.ridgeAsymmetryWeight())
                .setDefaultValue(def.ridgeAsymmetryWeight())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.ridge_asymmetry_weight.tooltip"))
                .setMin(-20).setMax(20)
                .setSaveConsumer(conf::setRidgeAsymmetryWeight)
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
                .setMin(4).setMax(64)
                .setSaveConsumer(conf::setChunkCacheLimit)
                .build());
        sampling.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.fine_sample_grid_step"),
                        conf.fineSampleGridStep())
                .setDefaultValue(def.fineSampleGridStep())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.fine_sample_grid_step.tooltip"))
                .setMin(1).setMax(GreatWallConfig.MAX_FINE_SAMPLE_GRID_STEP)
                .setSaveConsumer(conf::setFineSampleGridStep)
                .build());
        sampling.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.fine_step"),
                        conf.fineStep())
                .setDefaultValue(def.fineStep())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.fine_step.tooltip"))
                .setMin(1).setMax(4)
                .setSaveConsumer(conf::setFineStep)
                .build());
        sampling.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.steep_threshold"),
                        conf.steepThreshold())
                .setDefaultValue(def.steepThreshold())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.steep_threshold.tooltip"))
                .setMin(1).setMax(16)
                .setSaveConsumer(conf::setSteepThreshold)
                .build());
        sampling.addEntry(eb
                .startIntField(Component.translatable("config.plaincraft_great_wall.core_half_width"),
                        conf.coreHalfWidth())
                .setDefaultValue(def.coreHalfWidth())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.core_half_width.tooltip"))
                .setMin(0).setMax(52)
                .setSaveConsumer(conf::setCoreHalfWidth)
                .build());
        sampling.addEntry(eb
                .startBooleanToggle(Component.translatable("config.plaincraft_great_wall.refine_water"),
                        conf.refineWater())
                .setDefaultValue(def.refineWater())
                .setTooltip(Component.translatable("config.plaincraft_great_wall.refine_water.tooltip"))
                .setSaveConsumer(conf::setRefineWater)
                .build());

        return builder.build();
    }
}
