# 长城 mod 全量自动化验证报告

- 日期：2026-10-07
- 工程：`plaincraft-great-wall`，分支 `main`
- HEAD：`953ac85`（本次会话共 19 个提交）
- 部署 jar：`plaincraft-great-wall-0.1.0-s1c-preview.2.jar`
- jar SHA-256：`80506fe57a6379b151ef3dab4ce7b83ec62b5ad74e631d0049777f5c4d08f456`（`build/libs` 与客户端 mods 目录一致）
- 机器：AMD Ryzen 7 5700X（8C/16T）/ 31.9 GB / Windows 11 Pro / Oracle JDK 21.0.5

本报告只记录**实际执行过并观察到结果**的项目。凡未执行或证据不足者，明确标为
`NOT_RUN`，不以推断代替观测。

---

## 1. 六项可自动化验证

| # | 项目 | 结果 | 用时 | 证据 |
|---|---|---|---|---|
| ① | `verifyReleaseArtifacts` 发布门禁 | **通过除版本号外全部检查** | 12 s | 已重跑；输出见 `docs/release/acceptance.json` 的 artifact-contents／reproducible-build 条目 |
| ② | `runGameTest` 全量 | **All 119 required tests passed** | 3 h 22 m | 日志已被后续 `--rerun-tasks` 覆盖，见下方「证据存在性」 |
| ③ | `runServerSmoke` 真实地形 | **REAL_WALL_TRAVERSAL_PASS** | 5 h 43 m | `build/smoke-pass.log` + `docs/evidence/s1c/real-wall-traversal-pass-20261007.json` |
| ④ | S5 故障矩阵 exception | **180/180 通过** | — | `docs/evidence/s5/s5-faults-exception-20261006-c0c3f001/` |
| ④ | S5 故障矩阵 halt | **180/180 通过** | — | `docs/evidence/s5/s5-faults-halt-20261006-623a795f/` |
| ⑤ | S5 压力 profile（1 小时） | **passed=true** | 1 h | `build/s5-stress-1h-evidence/` |
| ⑤ | S5 压力 profile（24 小时） | **运行中** | — | `build/s5-stress-24h2.log` |
| ⑥ | 5 源集编译 | **全绿，783 个 class** | 12 s | — |
| — | JUnit 单测 | **585/585，强制重跑全绿** | 3 m 18 s | `build/test-results/test/` |

### 证据存在性（2026-10-07 自查）

`build/` 下的日志会被后续构建**覆盖**，因此早期几轮运行的部分原始日志**已不存在**：

| 曾被引用的路径 | 现状 |
|---|---|
| `build/gametest-v2.log`（全量 119 项） | **已缺失** —— 被后续 `--rerun-tasks` 覆盖 |
| `build/verify-release.log` | **已缺失** —— 同上 |

**补救**：JUnit 结果每次重跑都会重新落地在 `build/test-results/test/`；故障矩阵与遍历的证据
已提前复制到 `docs/evidence/`（不受 `build/` 清理影响）。**全量 GameTest 会在 24h 压测结束后
重跑并重新落盘**，届时上述条目将指向真实存在的文件。本报告不再引用已消失的路径。

### 尚未运行：`tools/Test-S5Release.ps1`（两轮干净构建比对）

`tools/Test-S5Release.ps1` 是**验收规格形态的可复现性验证**：两轮
`--offline --dependency-verification=strict clean build verifyReleaseArtifacts`，
比对 remapJar SHA，结果写入 `docs/evidence/s5/s5-release-<profile>-<date>-<id>/`。
**它从未运行过**（`docs/evidence/s5` 下无 `s5-release-*` 目录）。

**当前不能运行**：脚本第 30 行在 Gradle 参数里传了 `clean`，会删除整个 `build/` ——
其中含正在进行的 24h 压测的 7.32 GB 运行数据。**在压测期间运行它会直接杀掉压测。**

**已排除的风险**：曾担心 60 分钟超时不够，因为 `build` 可能拖入 3h22m 的 `runGameTest`。
已核实**不会**：`runGameTest` 只被显式注册的 `runS2RoadTests` / `runS3FtbTests` / `runS4Gallery`
依赖，`check`/`build` 未绑定它。加上本轮已单独验证过「同一源码可产出同一 SHA」
（`remapJar --rerun-tasks` 与「删 `build/libs` 后重建」三次一致），该脚本是**同一断言更严格的形式**。

**排入队列**：24h 压测结束后运行，预期产出 `docs/evidence/s5/s5-release-*` 证据。

### ① 发布门禁

门禁**已能完整运行**（此前因 `File.digest('SHA-256')` 不是 JDK API 而从未跑成功过）。
它唯一的失败项是版本号：

```
mod_version must be exactly 0.1.0 for release, found '0.1.0-s1c-preview.2'
```

这是门禁**正确拒绝给 preview 版本签发布**，不是缺陷。其余检查独立复核通过：

- jar 内无 `FaultInjector` / `StressController` / `StressFixture` / `/stress/` / `/smoke/` / `/gametest/` 条目
- jar 内 json/txt 无 `D:\` / `C:\` / 用户名等机器路径
- `pack.mcmeta` = `pack_format 34`，`supported_formats 34..48`
- 依赖版本全部未变（Gradle 9.4.1 / Loom 1.16.3 / Loader 0.19.3 / FabricAPI 0.116.14+1.21.1）

**构建可复现性**：`remapJar --rerun-tasks` 与「删除 `build/libs` 后重建」三次产出
**同一 SHA-256**（`80506fe5…`）。

**本轮（2026-10-07）复验并得到一个额外结论**：在会话新增 27 个测试/工具文件之后，
用 `remapJar --rerun-tasks --offline --dependency-verification=strict` 重建，SHA **仍然不变**。
这**独立印证了「测试代码不进 jar」**这条设计约束：

```
src/main 最后一次改动 = 6e67cc2
6e67cc2..HEAD 改的是：16 个 src/test、4 个 src/gametest、7 个 src/serverSmoke、
                     2 个 gradle、2 个 tools、4 个 docs   ← 没有一个 src/main
→ jar SHA 不变是【正确】的，不是碰巧
```

并且逐个确认新增的 `BiomeWriterGameTests` / `GarrisonControllerPolicyTest` /
`BeaconLedgerTest` / `SanctuaryAvoidanceTest` **均不在 jar 内**。

**产物内容复核（本轮）**：

| 检查 | `remapJar` | `sourcesJar` |
|---|---|---|
| 条目数 | 545 | 238 |
| 禁用类（fault/stress/smoke/gametest/clientSmoke） | **0** | **0** |
| 文本条目全量扫描机器路径（`C:\Users`、`D:\云游四海`、用户名） | 24 个扫描 / **0 命中** | 190 个扫描 / **0 命中** |

（注意 `sourcesJar` 含 `.java` 源码，其注释同样无机器路径 —— 上面是**全量扫描**，不是采样。）

### ③ 真实地形遍历

```
REAL_WALL_TRAVERSAL_PASS owners=505 voxels=28046 feet=2537 reopen=false production=OFF
SERVER_SMOKE_PASS: stopped normally
```

红线指标（决定性）：

| 计数器 | 值 | 含义 |
|---|---|---|
| `WAIT_WHILE_LOCKED` | **0** | 从未在 C2ME 写锁内请求规划 |
| `STAGE_ENTER` / `STAGE_EXIT` | **787727 / 787727** | 阶段完全配平 |
| `TRACE_TRUNCATED` | 2321898 | 仅诊断缓冲上限，非失败 |

延迟：median 52.6 s、p95 102.1 s、max 314.3 s（505 个 owner 串行，符合架构）。

### ④ 故障矩阵

| 模式 | 通过 | 退出码 |
|---|---|---|
| exception | **180/180** | `1` × 180 |
| halt | **180/180** | `137` × 180 |

18 个故障点 × 10 轮，两模式共 **360 次注入，零失败**。

**这一项验证的边界（重要，避免误读）**：按规格原文（`docs/S5.md:24`），故障矩阵要求的是
「18 个 `FaultInjector.Point` × {exception, halt} × 10 轮；halt → `Runtime.halt(137)`」，
即**每个点在两种模式下各 10 次的注入与退出码**。`FaultProcessMain` 本身不启动服务器、
不生成世界（它只反复调用注入器直到第 N 次命中，然后按模式抛异常或 `halt(137)`）。

**恢复语义由另一组测试覆盖，不是由故障矩阵覆盖**：

| 测试 | 验证内容 |
|---|---|
| `HistoryJournalTest.completeBadCrcIsNotTruncated` | **CRC 损坏的字节不被截断**（`assertArrayEquals` 逐字节比对）、判定为 `CORRUPT`、后续写入被拒 |
| `HistoryJournalTest.partialTailIsArchivedReadOnlyBeforeForcedTruncate` | 残缺尾部先归档为只读证据再截断 |
| `HistoryJournalTest.failedFsyncNeverPublishesAndVerifiedRetryReusesActualTail` | fsync 失败绝不发布 |
| `HistoryJournalTest.checkpointFailureLeavesOriginalHeadAndRecoveryCanContinue` | 检查点失败后仍可继续恢复 |
| `HistoryJournalTest.missingHeadRecoversOnlyAConsistentRetainedChain` | 缺失 head 只恢复一致的保留链 |
| `HistoryJournalTest.damagedIndexMissingWalAndWrongWorldNeverBecomeEmpty` | 损坏后**不静默变成空世界** |
| `RecoveryCoordinatorTest`（4 例） | 恢复序列包含 checkpoint 之外的 WAL、外部 manifest 不能开不同 journal root |

因此验收项 `fault-matrix` 的 `expected`（「per-matrix exact results; corrupt CRC never
truncated」）两侧都有实测覆盖：矩阵侧是 360 次运行，CRC 侧是 `completeBadCrcIsNotTruncated`。

**注意**：`RecoveryTest.eachFaultPointReopensWithoutSilentReset` **名字比实现更宽** ——
它实际验证的是枚举名往返、mock 注入器抛 `IOException`、`disabled()` 不抛异常、以及
`fault-hit.json` 路径不逃逸；它**没有**真的在 18 个点上逐个崩溃并重开世界。真正做端到端
恢复的是上表的 `HistoryJournalTest` / `RecoveryCoordinatorTest`。

### ⑤ 压力 profile（1 小时，已通过）

```json
{
  "profile": "MIXED_24H", "requestedMinutes": 60, "observedSeconds": 3616,
  "completedChunks": 52320, "peakHeapBytes": 7995922760,
  "checkpoints": 121, "ticks": 72000, "failure": null, "passed": true
}
```

`ticks = 72000` 正是 3600 s × 20 tick，说明窗口跑满；堆峰值 7.99/8 GB 未 OOM。

---

## 2. 本次验证挖出并修复的真实缺陷（20 个）

**关键背景**：全部 20 个缺陷都**逃过了 562 个 JUnit 单测**。四个属于「真机一启动就崩」级别。

### A. 产品热路径缺陷（真机必崩）

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| 1 | `WallSpawnerMixin` 调用**抛异常**的服务访问器 | 方块实体一 tick 即崩服务器 | 新增 `spawnersIfReady()` 降级原版行为 |
| 2 | `IgnitionUseMixin` / `TorchIgnitionMixin` 同问题 | 玩家点火即崩 | 同上，`ignitionIfReady()` |
| 3 | `ProductionSession` 给 `HistoryJournal` 传 `root/history`，而 journal 自行拼 `history/` 与兄弟 `manifest/` | **真机每次启动都失败**（`creation token required`） | 传 root |
| 4 | `WallArchBackfill` 的 `thenComposeAsync` 漏传 executor | 跳到 `ForkJoinPool.commonPool`，违反线程合同 | 传 `io` |

### B. 测试/门禁基础设施缺陷（两个方向都会骗人）

| # | 缺陷 | 方向 | 修复 |
|---|---|---|---|
| 5 | `verifyReleaseArtifacts` 调 `File.digest()`（非 JDK API） | **门禁从未跑成功过** | 自写 `sha256(File)` |
| 6 | 门禁把「诊断缓冲满」判为失败（`TRACE_TRUNCATED`） | **完整成功的遍历被报失败** | 只守 `WAIT_WHILE_LOCKED` + `ENTER==EXIT` |
| 7 | `ignoreExitValue=true` 使子进程退出码对驱动不可见 | **每个故障点都报失败** | 任务写 `exit-code.txt`，驱动读它 |
| 8 | `prepareS5Stress` 不校验真正生效的开关 | **会静默跑成普通 smoke（假验证）** | 强制 `-Ps5Stress=true` 且分钟数一致 |
| 9 | 故障见证文件写到共享 root | 第 2 次运行起必崩 | 写 per-run 目录 |
| 10 | 见证目录未按 mode 区分 | exception/halt 互相污染 | 目录名含 mode |
| 11 | **intermediary 缓存名假设过时**（Loom 现为 `-v2.jar`） | **所有 production 运行起不来** | 前缀匹配 |
| 12 | `StressFixture` 预建报告目录，而 `StressController` 要求全新目录 | 压力 profile 启动即失败 | 只建父目录 |
| 13 | `runServerSmokeProduction` 5 分钟硬超时 | 压力 profile 被中途杀掉 | 按请求时长推导 |
| 14 | `passed` **不校验时长** | **4.5 h 运行能「通过」24 h 规格** | 加 `durationMet` |
| 15 | `pending` 队列耗尽即 `stop()`、不补充 | **24 h 数据根本无法产生** | 窗口内 refill 重进 |
| 16 | `inFlightFutures` **内存泄漏**（完成后不移除） | 堆顶满 → GC 抖动 → 保存 tick 卡 60 s → **watchdog 杀服务器** | `whenComplete` 自移除 |

### C. 测试脆弱性

| # | 缺陷 | 修复 |
|---|---|---|
| 17 | `RootSearchTest` 硬断言 `over40msBatches==0` / `maxBatchNanos<40ms`，与 2026-10-05「40 ms 不再作拒绝条件」的授权冲突，满载下 flaky（FAILED/FAILED/PASSED） | 改为「计数存在且非负」，与 `WorldBootstrapTest` 同形 |
| 18 | `WorldBootstrapTest` 同类硬断言 | 同上 |

### D. 我自己引入、并被测试立刻抓到的（3 个）

| # | 我做了什么 | 测试如何抓到 |
|---|---|---|
| 19 | 重写 `WallArchBackfill`（停止伪造空写集）后，GameTest 契约失配 | GameTest 红；改为「由真实失败路径驱动」并新增 PENDING 用例 |
| 20 | 放宽排空窗口后，在 **server 线程**同步等待 drain | watchdog：`A single server tick took 60.00 seconds` |

**第 19、20 项说明测试链路本身是有效的** —— 我引入的问题没有被放过。

---

## 3. 反复出现的模式

### 「门禁阈值过紧」出现 4 次，每次都把**成功**报成**失败**

trace 缓冲上限、退出码不可见、30 秒排空窗口、压力 stop 超时。

这类 bug 比产品 bug 更危险：**它让人不敢相信任何测试结果**。

### 「短跑通过 ≠ 长跑稳定」

1 小时压力 profile 连跑 3 次全部通过；24 小时版本在首次队列 refill 后 14 分钟
因内存泄漏被 watchdog 杀死。**泄漏只在 refill 后显现**，短跑永远测不到。

---

## 4. 尚未验证、需真机/人工确认的项目

以下项目**没有**通过自动化取得证据，不能据此签字：

| 项目 | 状态 | 说明 |
|---|---|---|
| **人工步行穿越长城** | `NOT_RUN` | 只能程序化遍历 505 个 owner，不能代替真人走一遍 |
| **客户端视觉/画质检查** | `NOT_RUN` | `runClientSmoke` 会启动真实客户端，`SmokeVisualChecklist` 自我声明「Neither case creation nor state observations certify visuals」，`observer` 必须是 `human` |
| **专属群系实际生效** | `NOT_RUN` | 数据包为 **NORMAL（需手动启用）**；`ForcePlacement` 仅做过静态证明，**真实 RegistryOps 解码从未跑过**。见下方「群系着色的证据边界」 |
| **群系写入器 `GreatWallBiomeWriter`** | **已补测并通过** | 新增 `BiomeWriterGameTests`（3 例），见下 |

### 群系数据包内容核对（2026-10-07，静态）

对打进 jar 的 `resourcepacks/great_wall_biome/` 做了逐项核对：

| 项 | 实测 | 结论 |
|---|---|---|
| 目录约定 | `data/<ns>/lithostitched/biome_injector/`、`worldgen/biome/`（**单数**） | ✅ 符合规格 |
| 资源包 `pack_format` | 48 | ✅ |
| `density_function/registration_zero.json` | 裸 `0.0`（注入器条件的不可满足形式） | ✅ 按设计 |
| 注入器 | `lithostitched:force_placement`，`priority 1000`，条件 `registration_zero: 1.0` | ✅ 注册身份、永不自然放置 |
| 群系 `features` **组数** | **11**（与原版 plains 相同） | ✅ |
| 树类 feature | `tree`/`trunk`/`leaves`/`sapling`/`forest` **命中 0** | ✅ 无树 |
| `features[9]`（VEGETAL_DECORATION）逐条对照 | 与原版 plains **仅差 `minecraft:trees_plains` 一项被移除** | ✅ 即「plains 减树」 |

**注**：早前对话中我曾把组数说成 9，实际是 **11**；`features[9]` 是**下标 9**（0 起）的植被组，
两者不是同一个数字。上面的逐条对照才是准确表述。

（jar 内 `data/plaincraft_great_wall/` 的 `loot_table`、`tags`、`worldgen/biome/` 亦均为单数命名，
无复数目录警告隐患。）
| **height6000 / 非默认 minY** | `NOT_RUN` | 未测的配置组合 |
| **Golden 对照种子** | `NOT_RUN` | 未跑 |
| **integration-matrix 其余 profile** | `NOT_RUN` | 只跑过 `vanilla`。**并且这三个 profile 无法用当前 harness 补齐**：smoke 运行只装载 3 个 jar（fabric-api + 本 mod + smoke harness），`smokeProfile` 只改运行目录名、`expectedAdapter`、堆大小与 `-Dgreatwall.smoke.profile`，**不会**把 Tectonic / C2ME / OpenCL pack 加进 classpath —— 传 `-PsmokeProfile=tectonic` 只会得到一个「标签是 tectonic、实际装载相同 3 个 jar」的运行。Tectonic 与 full-pack OpenCL 两行**必须靠真机实例** |
| **S5 24 小时** | `NOT_RUN` | 运行中，未出结果 |
| **严格离线构建** | **已通过** | `remapJar remapSourcesJar --offline --dependency-verification=strict --rerun-tasks` 成功，`compileJava` 真的重新执行，SHA 与联网构建一致 |
| **Tan's Huge Trees / TYG write-set** | `BLOCKED` | 依赖外部 mod |

### 其余「测试零覆盖」类（系统扫描结果）

对 `src/main` 全部 163 个类与 `src/test` + `src/gametest` 做全文比对，**41 个类零引用**。
其中 mixin（`WallSpawnerMixin` / `IgnitionUseMixin` / `TorchIgnitionMixin` / `C2meGateMixin` 等）
天然只能在真机走到，但下列**非 mixin 逻辑类**是真实的自动化缺口：

| 类 | 行数 | main 内引用 | 备注 |
|---|---|---|---|
| `GarrisonController` | 309 | 12 | 缺口最大，最值得补 GameTest |
| `IgnitionService` | 117 | 9 | 点火状态机 |
| `WallSpawnerGuard` | 43 | 10 | 被 1 个 mixin 调用 → 只能真机验证 |
| `SanctuaryAvoidance` | 49 | 20 | ErdTree 圣地避让 |
| `C2meTerrainEngine` | — | 4 | C2ME 适配，需 `.405` 环境 |

另有 `BeaconLedger`、`Beacon{Count,Specific}Task`、`ComponentComposer`、`FeaturesLease`、
`StructureLease`、`SiteRuntimeIndex`、`WallObstacleGeometry`、`LootInitializer`、
`FtbIntegration`、`ProductionInputs`、`DebugService`、`GreatWallCommands`、
`OptionalTreePacks`、`GameplayTypes`、`BarrierFixtureLayout`、`C2meProbeAdapter`、
`TerrainChunkAccess` 等同样零直接引用。

**结论**：`GreatWallBiomeWriter` 不是孤例。补测优先级建议为
`GarrisonController` → `IgnitionService` → `SanctuaryAvoidance`；
`WallSpawnerGuard` 与其 mixin 只能靠真机，`C2meTerrainEngine` 需要 C2ME 环境。

### 激活专属群系会改变世界指纹

数据包默认 **NORMAL（opt-in）**。手动启用后，世界指纹变化，**旧存档会按设计拒绝加载**。
这是有意行为，但需在真机上确认一次。

### 群系着色的证据边界（重要）

已完成的两次长时间运行**都没有执行到群系着色代码**，原因是两道各自独立、且**同时**为真的门禁：

1. `ChunkGeneratorBiomeMixin:58` 要求 `services.productionFixture()`，而遍历以
   `production=OFF` 运行 → 直接 `return`
2. `ChunkGeneratorBiomeMixin:60` 要求 `runtime.identityBound()`，而 smoke 运行目录只装载
   **3 个 jar**（fabric-api + 本 mod + smoke harness），**没有 Lithostitched** → holder
   从未绑定 → 即使门禁 1 通过也会 `return`

HUD 日志的印证：`Applied 0 biome modifications to 0 of 64 new biomes`。

**因此**：

- 505-owner 遍历验证的是**墙体几何与写入**（28046 个方块、2537 walkway），**不是**群系着色
- 这两次运行**顺带证明了 fail-soft 设计正确**：holder 缺失时 mixin 静默降级为原版行为，
  服务器不崩、遍历照常通过；而真正需要 holder 的调用方走 `requireIdentity()`，会以
  `UNSUPPORTED_ADAPTER` fail closed
- **已补上写入器自身的执行证据**（2026-10-07，commit `a338ce9`）：新增
  `BiomeWriterGameTests`（3 例，`All 3 required tests passed`，连跑 3 次稳定）真正调用
  `GreatWallBiomeWriter.paint`，覆盖 ①`[H-8, H+32)` 半开带内画/带下保留原群系、
  ②无可用地表高度时拒绝绘制、③遮罩排除的列保持原群系
- 群系着色的其余可验证覆盖仍是**间接**的：`RouteBiomeMaskTest`（8 例，遮罩几何/半开边界/
  保守 quart 上色）与 `GreatWallBiomeRuntimeTest`（8 例，fail-closed/维度隔离/重复发布）

**仍未验证的部分**：写入器测试证明的是**「给定 holder 与遮罩时，写入器行为正确」**，
**不证明** Lithostitched 能注册该身份、也不证明 mixin 在真实客户端里会走到写入器。
后者**必须由用户在启用数据包的实例里确认**。这是本次验证**剩余的缺口**。

---

## 5. 验收账本

机器可读账本在 `docs/release/acceptance.json`，当前为：

| 状态 | 数量 | 项目 |
|---|---|---|
| **PASS** | 6 | artifact-contents、reproducible-build、pack-format、lifecycle-evidence、fault-matrix、negotiated-gate |
| **FAIL** | 2 | version-lock（版本号仍是 preview）、full-package（同一版本号阻塞；严格离线构建已通过） |
| **NOT_RUN** | 2 | integration-matrix（仅 vanilla）、stress-24h（运行中） |

顶层 `status` 保持 `NOT_READY`。按账本自身的规则，**只有全部 PASS 才能签「全 S5 通过」**，
因此当前**不可发布**。
