# S5 24h 压力 profile —— 运行时里程碑（2026-10-07）

本文件记录 24h 压测【运行中】的关键证据，防止 build/ 被清理或覆盖后丢失。

## 首次 refill（决定性验证点）

    [10:13:09] STRESS_REFILL ordinal=1 completed=65536 elapsedSeconds=5110 remainingSeconds=81290

上一版（commit 3f4402f 之前）正是在首次 refill 后 14 分钟被 watchdog 杀死。
死因是 inFlightFutures 内存泄漏：每个 future 完成后不从列表移除，累积约 100 万个
future 及其区块引用，堆顶到 8G 上限，GC 压力使一次区块保存 tick 卡 60.01 秒。
修复见 commit 3f4402f（future.whenComplete 自移除，只保留未完成的工作）。

## 修复后的堆行为（证明泄漏已消除）

- refill 后堆可达 7.37GB，但**会回落**：sample-000193 从 7.23GB 降到 2.19GB。
- 序列在 2.19..7.45GB 之间**震荡**，无单调上升趋势。
- 对比修复前：单调爬升且不回落，最终触顶。

## 采样时状态

- 进度：139533 个 chunk（已超过首个 65536，即第二轮"已生成区重进"在进行）
- refills：1
- watchdog：0     OutOfMemory：0
- 本目录同时保存了当时的 stress-reports 采样（header.json + sample-*.json）