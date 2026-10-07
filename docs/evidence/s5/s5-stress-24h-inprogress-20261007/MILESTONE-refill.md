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
## refill 频率与磁盘收敛（2026-10-07 补充实测）

三次 refill 的日志：

    [10:13:09] STRESS_REFILL ordinal=1 completed=65536    elapsedSeconds=5110
    [10:31:52] STRESS_REFILL ordinal=2 completed=131072   elapsedSeconds=6232
    [10:54:39] STRESS_REFILL ordinal=3 completed=196608   elapsedSeconds=7600

首轮含预热约 85 分钟；此后每轮 65536 个 chunk 约 19–23 分钟（≈48 chunk/s），
故 24h 内约 60 次 refill。每次 refill 重跑相同坐标（MIXED_24H 是确定性
i%3 轮转），即规格要求的"已生成区重进"。

**磁盘会收敛，不会线性增长**：

| 时刻 | 占用 |
|---|---|
| 1.79 h | 8.78 GB |
| 2.05 h | 8.83 GB |
| 5 分钟实时采样 | +0.008 GB（0.09 GB/h），region 文件 +1 |

剩余 22h 约需 2.1 GB，可用 78.9 GB，充足。

⚠️ **不要用平均速率外推**：平均 4.31 GB/h × 24h = 103 GB 会得出"必然爆盘"的
错误结论 —— 首轮冷启动填充不是稳态速率。
---

## 4.4 小时复核（2026-10-07 13:12，运行中）

上面各节记录的是 1.6–2.1 小时时的观测。此处补上后段状态，让轨迹完整：

| 项 | 1.6–2.1h 时 | 4.4h 时 |
|---|---|---|
| refills | 1–3 | **10** |
| chunks | 139533 | **693159** |
| watchdog | 0 | **0** |
| OutOfMemory | 0 | **0** |
| 线程 | — | **33**（与 0.86h 时的 33 持平） |
| 堆 | 2.19–7.45GB 震荡 | 6.92GB（仍在震荡区间内） |
| 磁盘 | 8.83 GB | **8.94 GB** |

**磁盘预测已实测验证**：我在 2.05h 时预测稳态约 0.09 GB/小时；实测 2.05h→3.86h
增长 0.11 GB，即 **0.06 GB/小时**，略低于预测。剩余约 19.6 小时需约 1.2 GB。

**线程无泄漏**：9 次 refill 之后线程数仍为 33，未随 refill 累积。

**refill 间隔稳定**：25.2 / 18.7 / 22.8 / 18.2 / 21.4 / 18.6 / 17.1 / 17.5 / 18.1 分钟，
向 ~18 分钟收敛，无上升漂移。

**日志零错误**：[ERROR] 0 条（共享读核实；曾误报 3 条，实为 PowerShell 自身错误文本
混入管道）。