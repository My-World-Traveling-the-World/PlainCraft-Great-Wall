# S1A 纯几何规划签收报告

日期：2026-09-29。基线：`5ad1a3789523d37852509267cec982e0c18bf9ad`。

**状态：S1A 离线实现与本阶段测试已通过，提交审查；未进入 S1B。**

本报告对应实现提交 `ef3fadac974fd71841056a102a1f872ada038042`。报告/证据另行提交，不把尚未审查的 S1A 自动视为 S1B 开工授权。

## 1. 范围与前置类型

实现 S1A 的纯 Java 几何、自然地形 oracle 接口、分段水文、可重建段缓存、有序前缀、日程、高度合同与异步调度器。按 S5 规格的明确前置说明提前实现 `BudgetCache`，但没有执行 S5 发布或压测阶段。

`ProbePlanner` 和 S0 的 `WorldServices`、Mixin、PREPARE/CAPTURE/APPLY 实验均保留。本次 **没有把 PlannerScheduler 接到真实 worldgen，也没有生成城墙方块**。真实 CPU 地形 oracle 与原引擎接线属于 S1C；S1A 使用解析 fixture，不缩短 2048 段长，不修改 300 格日程或 150000 逻辑列预算。

四个已有公共类型先原样纳入版本控制：`f2d4115`、`1a0d655`，没有重写或回滚。随后契约测试发现原有实现与已写规格不一致，进行了以下局部修复（`85a668e`）：

- `WallTypes`：Q30 单位方向的平方应比较 `2^60`，不是 `2^62`；RouteShape 两端非空；RouteNeighborhood 相邻编号/维度/配方一致；requiredRoutes 内层列表深复制。
- `PaletteTypes`：权重累加使用 long，避免 int 溢出后误通过。
- `WallFailure`、`PlanLease` 保持原实现。
- 原始 9 项测试有 7 项失败，修正后 9/9 通过；原始与修复后的 TXT/XML 均保存。该前置检查不声称已穷尽 S2～S4 全部公共类型的未来用途。

缺失输入与必要签名补充集中在：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1A-补充合同.md`

该文件依据用户“如果有缺失你可以自己补充信息”的授权，明确 recipe 注入、后置水门支撑判定、prefix 编码、缓存 IO executor、无定义 Gate socket 的处理、预算元数据与依赖准入规则。原来的八册实施规格没有被静默改写。

## 2. 文件清单（完整路径）

### 2.1 生产纯 Java 文件

以下 19 个文件包含原有四个公共类型与新建 15 个类/接口：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\model\WallTypes.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\model\WallFailure.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\model\PlanLease.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\decay\PaletteTypes.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\FixedMath.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\DeterministicHash.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\CanonicalCodec.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\ArcLength.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\Projection.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\AnchorField.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\NaturalTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\RidgePlanner.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\HydrologyClassifier.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\SegmentCacheStore.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\PrefixSummaryIndex.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\SiteScheduler.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\HeightContractPlanner.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\diagnostics\BudgetCache.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\PlannerScheduler.java
```

职责划分：

| 类组 | 实际职责 |
| --- | --- |
| FixedMath / DeterministicHash | Q8/Q16、负向取整、exact 溢出检查、splitmix64、域分离、SHA-256 |
| CanonicalCodec / ArcLength / Projection | 有界大端编码、嵌套哈希核验、真实弧长、轴向 u 和最近投影 |
| AnchorField / RidgePlanner | 索引锚点与 Hermite 载波、16 格贪心扇形、动量、端点收敛、可续跑游标 |
| NaturalTerrainOracle / HydrologyClassifier | 无 Minecraft 输入接口、绝对 u 网格与 supercover、16 向两岸认证、49/50 分界、后置支撑判定 |
| SegmentCacheStore / PrefixSummaryIndex | PGW1/CRC32C/SHA/原子缓存、有祖先证明的 checkpoint、顺序累加与最早终点 |
| SiteScheduler / HeightContractPlanner | 全局日程与保留 ordinal、冲突/侧翼合同、平台/边界硬约束、线性坡度约束与显式断口 |
| BudgetCache / PlannerScheduler | 先预留再加载、独立租约与 pin、依赖准入、两 oracle lane、startup pump、异步 IO、关闭隔离 |

### 2.2 测试和工具

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\model\ModelContractTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\geometry\FixedMathTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\geometry\CanonicalCodecTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\geometry\ArcLengthTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\PlanningFixtures.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\AnchorProjectionTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\RidgePlannerTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\GeometryDeterminismTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\terrain\AnalyticTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\terrain\HydrologyTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\persistence\SegmentCacheTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\PrefixSummaryIndexTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\ScheduleContractTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\diagnostics\BudgetCacheTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\PlannerSchedulerTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\tools\Export-S1AEvidence.ps1
```

报告、补充合同、原有八册的纳入版本控制，以及所有 TXT/XML/JSON 证据的逐文件绝对路径见：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1a\final-checks.json` 的 `fullFileInventory`。

## 3. 最终构建与证据

最终强制构建命令：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build --rerun-tasks --no-build-cache --console=plain --no-daemon
```

最终结果：**BUILD SUCCESSFUL in 1m 30s；18 actionable tasks: 18 executed。**

- JUnit **97/97 PASS**，17 个 suite，失败/错误/跳过均为 0。其中 S0 原有 22 项，本阶段新增 75 项。
- Fabric GameTest **1/1 PASS**，是已有的 `coreLoadsWithoutOptionalIntegrations` 隔离回归，不是新增的城墙施工或 C2ME 真实地形测试。
- S1A 测试均为纯 JVM fixture；没有 S1A GameTest 被跳过后冒充 PASS。
- 原版测试服务器正常保存并关闭；日志仍显示 `world generation disabled`。
- 通用构建提示仍是 Gradle 弃用 API 警告；未升级依赖或关闭校验规避。

最终证据：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1a\final-offline-build.txt
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1a\final-checks.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1a\gametest.xml
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1a\junit\
```

其它日志保留为过程证据，不能代替最终结果：

- `model-before.*`：实际前置失败记录；`model-after.*`：局部修正后的记录。
- `partial-offline-build.txt`：早期只完成数学/公共类型的构建，不代表完整 S1A。
- `all-junit.txt`：95 项时点；`pre-admission-offline-build.txt`：96 项时点。
- `scheduler-admission.txt`：最后的 11 项调度回归，包括 20 个不同段倒序并发。
- 分批 TXT 保存每批测试结果，最终源代码状态以 97 项全量强制构建及其 XML 为准。

产物仍沿用工程自身的 S0.3 文件名，未做发布版本升级：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\build\libs\plaincraft-great-wall-0.1.0-s0.3.jar
338781 bytes
SHA-256 7fd88c25213c223326e8611d15f83720cd6ea785482a6a4153a0cc0a3f807b33
```

本产物含 PlannerScheduler，不含 AnalyticTerrainOracle、PlanningFixtures、GameTest 或 serverSmoke 测试类；未部署到真实 mods。

## 4. 验收判据：预期与实际

| 判据 | 预期 | 实际/对应测试 |
| --- | --- | --- |
| 四 seed、多顺序路线 | seed 0/1/9282026/-1；0..63 顺序、逆序、随机字节相同 | PASS；RidgePlannerTest.forwardReverseRandomProduceSameBytes |
| 完整 geometry blob | 不只比较路线，包含水文与逻辑采样数的几何字节也一致 | PASS；GeometryDeterminismTest，4×64×3=768 份输出 |
| 切片大小 | 1/7/64/256 原语不改结果 | PASS；RidgePlannerTest.sliceSizesDoNotChangePlan；水文另覆盖 1/7/64/256 |
| 缓存冷热 | 缓存命中不能免逻辑列计费或改变输出 | PASS；cacheTemperatureDoesNotChangeBudget，完整几何矩阵另覆盖冷热 fixture |
| 接缝 | 锚点、边界 W/identity 一致；首尾 16 格无残差 | PASS；adjacentAnchorsAndTangentsMatch、carrierSeamsAndTangentsAreIndexOnly |
| 负坐标 | 使用 floorDiv，不产生负编号的第二条墙 | PASS；negativeCoordinatesUseFloorDivision、negativeProjectionDoesNotCreateSecondWall |
| 远坐标 | ±1000000、可用数学边界不溢出；越界明确拒绝 | PASS；millionBlockMathDoesNotOverflow、negativeProjectionAndMillionCoordinatesAreSafe |
| 真弧长日程 | 300n 跨 2048 段不重置 ordinal | PASS；towerOrdinalsCross2048Seam，同时保留被抑制的原编号 |
| 门/关隘间隔 | 门 600..1000，关隘 3000..5000 | PASS；gateAndFortGapsAreBounded，对连续 100000 个 ordinal 检查 |
| 合同预算 | SEARCH≤120000、HYDRO≤24000、两端+合同≤6000，总≤150000 | PASS；budgetNeverExceeds150000、完整几何矩阵、合同列上限检查；没有借用分账 |
| 海洋终止 | 顺序归约取最早终点，不谎称终点后精确前缀 | PASS；earliestOceanWinsAllOrders |
| 冷前缀 | 确实请求缺段，不承诺 O(1) | PASS；coldPrefixDoesNotClaimConstantTime |
| 跨段平台 | 两段使用同一个边界 W，不互相迭代调整 | PASS；platformAcrossSeamUsesOneHeight，左右不同地面 fixture 仍同 W |
| 不可达坡度 | 保持硬端点，生成 PLANNED/SLOPE 断口 | PASS；infeasibleSlopeCreatesExplicitBreak |
| 49/50/51 水宽 | 49 NARROW，50/51 WIDE | PASS；width49IsNarrow50And51Terminate，平行岸解析 fixture |
| 斜穿河流 | crossingSpan 不能冒充 channelWidth | PASS；diagonalSpanIsNotChannelWidth |
| 有顶盖含水层 | 顶盖脚面参考列不是河流 | PASS（oracle 合同 fixture）；roofedAquiferIsNotRiver。真实方块探测 NOT_RUN |
| 深窄河 | 支撑 drop=12 允许，13 为 SUPPORT_GAP，不变成海洋 | PASS；deepNarrowRiverMakesSupportGap |
| 未知水文 | 预算耗尽/截断/缺河床不能认证为海洋或干地 | PASS；budgetExhaustionIsNotOcean、missingBedOrCensoredReferenceIsNotCertifiedDry |
| 细河 | 4 格粗样本之间的 1 格河不漏 | PASS；oneBlockRiverBetweenCoarseSamplesIsFound |
| 跨段河 | 岸点、墩候选、证据 hash 和全球区间一致；仅 entry 所属半开段持有入岸 | PASS；seamRiverHasSameBanksAndOneGlobalCrossing。实际水门写块属于 S1B，NOT_RUN |
| 百调用者 | 共享一次 geometry 计算，各自独立 lease | PASS；hundredCallersShareOneCompute，实际 2 个 route + 1 个 hydro oracle |
| 取消/关闭 | 取消一个不影响其他；close 结束全部 future | PASS；cancelOneLeaseKeepsOthers、closeTerminatesAllFutures |
| 跨会话 | A 的迟到 IO 回调不能向 B 发布 | PASS；lateWorldACallbackCannotPublishWorldB |
| 邻段水文 | 只等待邻 RouteShape，不等待邻 geometry，避免环 | PASS；neighborHydrologyNeverWaitsOnNeighborGeometry，随机相邻并发与同步纯函数字节比对 |
| 世界边界 | 不请求完整包络界外段，最后安全段一个 WORLD_LIMIT | PASS；lastSafeSegmentEndsBeforeWorldLimit |
| startup pump | 无正常 tick 也能推进预热/计划 | PASS；startupPumpProgressesWithoutNormalTicks；纯 JVM，不冒充 C2ME 实跑 |
| pin 背压 | 无空间时不启动新 loader；释放 pin 后恢复 | PASS；BudgetCacheTest.pinnedCacheBackpressuresWithoutStartingLoader，组件级容量测试 |
| 父依赖预留 | 多个 plan 不能占满预算后饿死自身 route/geometry | PASS；manyDistinctPlansCannotReserveAwayTheirDependencies，20 个不同段倒序同时申请；生产 64MiB 未改 |
| IO 执行失败 | 执行器拒绝不得留下永久 pending | PASS；rejectedIoExecutorCannotLeaveAnUnfinishedFuture |
| 世界配方隔离 | 非主世界不能创建第二个 planner；改 seed 不可沿用旧语义 hash | PASS；nonOverworldAndTamperedRecipeCannotCreateSecondPlanner |
| 损坏缓存 | CRC 错返回 miss，历史哨兵不变，可纯算重写缓存 | PASS；crcFailureRecomputesCacheOnly |
| 伪造 checkpoint | 缺祖先/伪 proof 不可跳段 | PASS；invalidPrefixCannotSkipGap |
| 恶意长度 | 超长、截断、坏 UTF-8/布尔/嵌套 hash 拒绝 | PASS；CanonicalCodecTest 与 malformedLengthsRejected |
| 卷并发 | 同卷 32 段、4 writer，指针和 blob 不丢 | PASS；parallelVolumeWritersCannotLoseBlobs，临时文件为零 |
| 旧 S0 回归 | 不回滚已有屏障/加载合同 | PASS；22 项原有 JUnit + 1 项隔离 GameTest；未重跑完整 S0 真实矩阵 |

这里的 PASS 指具体测试和输入范围，不是对所有未知 mod 地形、所有硬件时延或无限并发的普遍证明。

## 5. 红线、P0 与永久历史边界

- 新阶段 19 个纯 Java 文件的 Minecraft/Fabric import、`possibleBiomes()`、`getChunk()`、阻塞 future `join/get` 模式扫描零命中。测试线程等待带超时的 future 不属于 worldgen。
- 共享完成值/Stats/进度为不可变模型，跨线程容器为 ConcurrentHashMap；规划游标、LRU、队列、pin 计数和临时地图由指定 owner 持有。`PlanLease` 独立视图不把取消传回共享计算。
- 不增加锁内调用；现有 C2ME/Vanilla gate 不变。未来接线必须先锁外取得 plan/window，再进入原有屏障；不能把 `computeGeometryNow` 用作正常 worldgen miss。
- PrefixSummaryIndex 负责真实弧长与最早终止；分段随机访问仍需缺段前缀工作，不承诺冷 O(1)。
- 本阶段冻结 W，不读取装饰后的方块、不调整构建期高度。SurfaceProbe 的 `[W-32,W+6]` 搜索和 `W-35` 承重 guard 属于 S1B，尚未实现，不能把高度合同测试当作支撑验收。
- S0 PREPARE→CAPTURE→APPLY 和不可重建 T0 存证未修改；没有将缓存命中当作 T0 或 APPLIED。
- SegmentCacheStore 只操作传入 cache root 下的配方目录；没有实现/清空 BeaconLedger、城门事务、驻军消耗等永久历史。
- 缓存计算最多 4 个 geometry、2 个 oracle lane；parent plan/geometry 在 reservation 前各限 4 个准入；调用者/待申请限 4096、IO 队列限 128。
- 64MiB 内先为 bounded 元数据留额，再保留值和进行中 reservation；超量等待/显式失败，不以“无墙成功”吞需求。

## 6. 版本、产物与未验证项

本次全部 Gradle 调用使用 JDK21、`--offline --dependency-verification=strict`。没有联网补依赖。

Java21、Mojmap、Gradle9.4.1、Loom1.16.3、Loader0.19.3、Fabric API0.116.14+1.21.1、JUnit5.11.0 未升级；Gradle 文件、wrapper、lockfile、verification metadata 与 `5ad1a37` 无差异。项目代理配置行数 0。

```text
gradle.lockfile SHA-256:
367ba3559edbec89d075630c6ce9a2b881620de0028ef2b43366fb58640651f4
gradle/verification-metadata.xml SHA-256:
c4e281095c96f26a421fc7d676a6d218f20f9739b40978b061863707f7edcee5
```

**S1A 本阶段无未关闭的已知测试阻塞项；以下不在本阶段 PASS 范围：**

| 项目 | 状态与边界 |
| --- | --- |
| S1B 构件、SurfaceProbe、写墙、跨 chunk 写入和物理入口 | NOT_RUN / 未开始，等本阶段审查 |
| Vanilla/Tectonic/C2ME 真实噪声规划实走 | NOT_RUN，S1C；本次没有第三方交互代码变更 |
| 新 PlannerScheduler 与真实 C2ME 区域写锁的运行接线 | NOT_RUN，S1C；已有 S0 路径不替换 |
| 真实 NoiseChunk 单原语≤40ms、性能尾延迟 | NOT_RUN；诊断计时字段已提供，不宣称硬时延保证 |
| 实际堆/JFR 对 retained 公式的校准 | NOT_RUN，S5；公式与容量不变量测试不是精确堆测量 |
| 客户端 smoke、真实服务器加载矩阵重跑 | NOT_RUN；此次仅现有隔离 GameTest 回归 |
| RoadWeaver/黄金树/FTB 交互、任务奖励、防刷 | NOT_RUN，S2/S3/S1C |
| 24小时、远跳真实生成、Chunky 压测、发布 | NOT_RUN，S5 |
| Tan's Huge Trees profile | 按用户边界继续 BLOCKED；树干保护不能授权整树写范围；未移除 mod 冒充通过 |
| S5 整包授权 | 继续 BLOCKED，不能因 S1A 通过而解除 |

没部署真实 mods，没打开真实存档，没写旧存档回填。构建/测试产物留在项目 build；docs 中的中间失败日志是审查证据，不是遗留待部署临时文件。

## 7. 小阶段提交

| 提交 | 内容 |
| --- | --- |
| f2d4115 / 1a0d655 | 原样保留公共类型和八册规格 |
| 85a668e | 公共类型局部契约修复与前后对照 |
| e104014 | FixedMath + DeterministicHash |
| d26c2e4 | CanonicalCodec + ArcLength |
| 700025e | AnchorField + Projection |
| 2400ca8 | NaturalTerrainOracle + RidgePlanner |
| 3858549 | HydrologyClassifier |
| 91db7b0 | SegmentCacheStore + PrefixSummaryIndex |
| d7ae6ec | SiteScheduler + HeightContractPlanner |
| 425400d | BudgetCache |
| a9711b4 | 首尾残差、未知水文、跨界吸收与完整几何矩阵 |
| 704ef8a | 元数据计入同一个缓存预算 |
| 285a78f | PlannerScheduler、prefix IO 续调、关闭/队列/配方回归 |
| ef3fada | 依赖预算准入、20 个不同段并发回归 |

每批新建生产类为 1～3 个，未把全部 S1A 堆成一个实现提交。签收报告与最终证据单独提交。

## 8. 可复现命令

全量签收：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build --rerun-tasks --no-build-cache --console=plain --no-daemon
.\tools\Export-S1AEvidence.ps1
```

聚焦确定性和调度：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*RidgePlannerTest' --tests '*GeometryDeterminismTest' --tests '*PlannerSchedulerTest' --tests '*BudgetCacheTest' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

聚焦高度、水文、缓存：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*ScheduleContractTest' --tests '*HydrologyTest' --tests '*SegmentCacheTest' --tests '*PrefixSummaryIndexTest' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

聚焦测试会覆盖 `build/test-results/test`；要重新导出全阶段证据，必须先重新执行全量构建，不能把聚焦运行的 XML 数量当成全阶段数量。

**下一步：等待 S1A 审查，不自动进入 S1B。**
