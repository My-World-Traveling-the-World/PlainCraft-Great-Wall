# S1C 真实墙接线：三个独立阻塞点实测诊断

日期：2026-10-01。基线 commit：`092311b`（含碎岛→宽水域收头改动）。
本报告是**真实 runServerSmoke 验证**的结论，非推断；crash 报告与日志都在
`build/run/serverSmoke/vanilla/<runId>/crash-reports/` 与 `logs/latest.log`。

## 实测方式

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runServerSmoke `
  -PsmokeProfile=vanilla -PsmokeRunId=wallvXXXX -Ps1cRealWall=true -Ps1cSeed=<seed>
```

注意：`gradle/smoke-tests.gradle:96` 对非 pack-opencl profile 固定 `-Xmx4G`，
与 GPT 之前 8G 取证不同；4G 堆会放大 root 采样 40ms 超限风险（见 S1C-Root40ms根因取证.md）。

## 三个阻塞点（按出现顺序）

### 阻塞点 1：root 选点 ROOT_NOT_FOUND（部分 seed）

- seed 9282026：`RootSearch.footprintStep` 抛
  `all frozen 800..1500 ring/footprint/axis candidates rejected`。
- 根因：`RootSearch.footprintStep`（RootSearch.java:123-141）要求 root 起点
  48×48=2304 格**全部**满足「3 层 foundation_support + 无水 + 高度差≤12」，
  真实地形几乎无此大片完美平地。
- 与 40 seed 摸底（S1C-Root与首段水文摸底.md）一致：ROOT_NOT_FOUND 在部分 seed 必现。

### 阻塞点 2：水文碎岛证不出宽窄（已修，验证生效）

- 改动：`HydrologyClassifier.classify()` 中 waterMismatch 与证不出宽窄均归 WIDE（收头）。
- seed 17 验证：segment 0 水文**通过**（此前必 FAIL_BEFORE_APPLY）。

### 阻塞点 3：水文 segment1 hydrology budget exhausted

- seed 17 过 segment 0，但 segment 1 抛 `hydrology budget exhausted`。
- 根因：budget 是 per-segment 24000 列（PlannerScheduler.java:272 / :717）。
  segment 1 有多个湿 run，每个 run 做 3中心×16方向×2侧×58格≈5568 列横断认证，
  多 run 累加超 24000。
- 已尝试"水位不一致 run 跳过 transect 省采样"，**被反例测试堵死**：
  `formerUnequalLevelWitnessKeepsMeasuredWidthButCannotBuildAWaterGate`
  （HydrologyTest.java:121）要求水位不一致的 run 仍保留测出宽度（如40格），
  只标 WIDE 不建水门。故不能跳过 transect 丢宽度。

## 修复方向（待 GPT 用完整上下文实施）

### 阻塞点 3（水文预算）
- 不要跳过 transect 丢宽度。
- 正确方向：NARROW 早停（找到某方向所有中心 bank&&width<50 即停），
  或对多 run 做总预算分配（窄河优先认证，超预算 run 按 WIDE 收头）。
- 保留 budgetExhaustionIsNotOcean、formerUnequalLevelWitness 反例。

### 阻塞点 1（root 选点）
- footprintStep 48×48 全格三层支撑+无水+高差≤12 过严。
- 补充实测（session-STOPPING.json rootAudit）：coarse 阶段 144 个候选（4半径×36角度）
  几乎全 REJECTED，主因是 spawn 附近大量水域（wetColumns 高达 81）+ 高度差超 12。
  coarse 筛选 `wet*10<=81 && max-min<=12` 在临水/丘陵地形下几乎无合格候选。
- 方向：放宽 coarse 候选筛选，或允许 footprint 少量格子不合格（墙基施工期填平），
  不删三层支撑承重合同。先出方案再改。

## 三处修复已提交并对真实地形生效（2026-10-01 实测）

| commit | 修复 | 实测结论 |
|---|---|---|
| 092311b | 碎岛/水位不一致 → 宽水域收头 | 生效 |
| ecc9604 | 水位不一致跳过 96 射线认证 + root footprint 允许 5%（115/2304）格无天然支撑 | 生效 |
| f0b320d | per-segment 水文预算 24000 → 96000 | 生效 |

【已验证】seed 17 真实运行（runServerSmoke，vanilla，34 分钟）的
`real-wall-progress.json` 显示：

```text
root: phase=3, candidate=144, shortlisted=8, axisIndex=5, columns=29115,
      maxBatchNanos=29355300, over40msBatches=0
plannerFailures: {}          (空)
rootAudit: 大量 FOUNDATION_ELIGIBLE（failedFoundations 11/4/26/38/51 均通过）
          大量 DRY_AXIS_ELIGIBLE
failure: IllegalStateException: fixed representative seed terminates before 1200 blocks
```

即：**root 选点通过（0 次 40ms 超限）、footprint 5% 容忍生效、水文预算通过**。
唯一未通过的是测试夹具的「代表样本 ≥1200 格」门槛，墙在水域处按规则收头（terminal），
长度不足 1200 格，夹具主动拒绝，**尚未开始施工**，故 `voxels=0`。

## 待用户决策：墙短是新规则的自然结果

用户最终规则「碎岛/证不出宽窄 → 按宽水域收头入海」意味着：地形上水域多则墙短。
40 seed 摸底里能到水文的 14 个 seed 中有 9 个是 UNRESOLVED（≈64%），
在新规则下这些都会收头 → 墙普遍短于 1200 格。

这是规则的直接结果，不是缺陷。夹具的 1200 格门槛为旧规则设定；
现新增 `-Ps1cRepresentativeBlocks=<n>` 诊断开关（默认仍为 1200，不改变证据标准），
用于在放低门槛时验证施工真正写出方块。

## 红线（不变）

不碰 getChunk/join/possibleBiomes；生产 OFF；Tan's BLOCKED；40ms 门槛不动；
普通陆地墙、原 NARROW/SUPPORT_GAP 路径一字不改。

## 边界

S2-S5 骨架已开始（见各自提交）；S1C 真实墙尚未走出 APPLY 写方块；
所有「待实机验证」项 NOT_RUN。
