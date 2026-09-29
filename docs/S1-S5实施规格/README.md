# S1–S5 可执行实施规格

日期：2026-09-29。基线：`5ad1a37`。本交付仅新增设计文档，不修改 Java、资源、依赖、第三方工程或世界。执行者按本目录顺序实施；不能将本文的设计合同写成已经编译或游戏验证的事实。

## 阅读及实施顺序

1. `00-公共类型与协议.md`：完整共享类型、坐标、错误、二进制协议和跨阶段决策。
2. `S1-城墙本体.md`：1A 纯函数、1B 构件/写入、1C 真实生成/生命周期。
3. `S2-道路联动.md`：RoadWeaver fork SPI、门事务、已加载分片补拱。
4. `S3-探索与FTB.md`：关隘、驻军/刷怪笼、战利品、点火历史、两种任务。
5. `S4-材质与视觉.md`：三族权重、8 格混料、完整图册和烟幕。
6. `S5-恢复压测与发布.md`：道路先到、故障恢复、压力测试、发布与全局拓扑。
7. `06-本地ABI与证据.md`：实际已读输入、外部签名、编译接线及兼容失败条件。

## 优先级与语义

本次用户要求 > 本目录明确冻结的细化 > 七轮终审方案 > v2.0 早期原文。原文中的 `[W-24,W+6]`、上半砖防刷、火把原版点火、历史可重建、超时空墙回填均不采用。起点严格在 800..1500 环带内，四个递进半径在环带内搜索；搜索耗尽明确拒绝此种子/配方，不扩到环带外。S4/S5 是必交付，不再沿用“可选”。

完整方案不能在未编译的情况下保证任意执行者“零思考必编译”。本文将**设计选择、类型闭包、方法签名、边界、错误和测试结果预期**固定；外部 Mixin 命中、运行时兼容与性能必须由列出的阻塞测试证明。失败时按指定错误停止，不让执行者发明新算法或跳过测试。本文没有将未来测试标成通过。

**已明确的整包边界**：本地Tan's Huge Trees现有保护只覆盖树干，不能证明完整树冠及后置写入安全。S1C将该未认证profile设为启动授权拒绝，S5原样整包项保持BLOCKED，不能移除该mod后冒充整包通过。该兼容缺口在S1C和06册单列；核心S1～S5规格不伪称解决任意第三方生成器。

## 本轮核实的 S0 边界

- S0 是 opt-in 实验：`ProbePlanner` 无真实地形；`WallStageCoordinator` 绑定 `BarrierFixtureLayout` 和金块测试带。S1 必须泛化业务输入，不能只删测试开关。
- `TerrainTile.Key` 的高度上限 128 层；保留它，用固定 128 层页覆盖多高度区域，不把高度 6000 塞进一个 tile。
- S0 的 `WorldRecipeGuard` manifest 明写 `s0-probe-only-not-a-wall-recipe`，不能当成生产世界授权。生产存储另设目录；S0 世界不是迁移对象。
- S0 `ProbePlanner` 的显式 startup pump 在专用 executor 上，不依赖正常 tick。真实 CPU oracle 仍只在它的私有 owner 上执行；主线程不得同步等它。
- S0-03 最大样本约 43.50ms，功能通过不等于 S5 的 40ms 门槛通过。
- S0 整包 OpenCL 只到客户端菜单，不能作为 S1C/S5 GPU 生成通过证据。

## 所有类的共同约束

下列编号在每个相关类中引用，含义固定，不需执行者补充设计。

| 编号 | 强制规则 |
|---|---|
| R1 | worldgen 不同步 `getChunk`、`join/get` 等 future；只在锁外挂依赖。`resultNow` 仅在 `allOf` 成功后的完成续调中使用。 |
| R2 | 跨线程公开值仅不可变对象、`ConcurrentHashMap` 和它承载的 promise；可变 cursor/队列/计数只属于单个明确 owner，绝不被其他线程直接访问。 |
| R3 | 不调用或修改 `possibleBiomes()`；群系取冻结注册表 key/tag 与坐标采样。 |
| P1 | C2ME 等依赖在 native 区域写锁之前；原调度器恢复；启动独立 pump。 |
| P2 | 全球 S 依赖按段号归约的 `PrefixSummaryIndex`；冷远跳可能 O(距最近 checkpoint 的段数)，可取消、有进度。 |
| P3 | 探测候选地面脚面 h∈[W-32,W+6]；下方承重 guard 另见公共协议，不偷偷漏掉 drop=12。 |
| P4 | PREPARE(FEATURES only)→CAPTURE→durable T0→APPLY(owner)→LIGHT；CAPTURE 不等其他 APPLY。 |
| P5 | manifest/T0/门/烽火/驻军历史不可当缓存清空；损坏 fail-closed。 |

没有网络操作；所有未来 Gradle 命令必须先设 `JAVA_HOME=C:\Program Files\Java\jdk-21`，再带 `--offline --dependency-verification=strict`。所有运行验证只用标记的新隔离世界；读取真实 mods 仅作为依赖输入，不部署。

## 文档记法

- 每个类标题是完整 Java 类型名；标题下的文件是完整绝对路径。Java 块给出类/字段/构造器/导出方法声明，方法体只在随后伪代码块中定义，不把这些块当已经存在的源码。
- 未写 `throws` 表示无 checked exception；参数 null/越界抛 `IllegalArgumentException`，错误时不返回“空成功”。IO API 明列 `throws IOException`；异步 API 把原因保留在 exceptional completion。
- `java.lang` 自动导入；`java.util.*`、`java.util.concurrent.*`、`java.nio.file.Path`、`java.io.*`、`local.plaincraft.greatwall.model.WallTypes.*` 是设计签名的统一 imports。其他类型由本文完整路径标题或 ABI 表解析，不能新造同名占位类。
- record 的 canonical constructor、字段 accessor、equals/hashCode/toString 按 Java21 自动生成；共同构造检查在公共类型册固定。未另外声明的方法不新增公开 API；纯 helper 均 private。枚举按名字存盘，不把 Java ordinal 写进永续文件。
- JUnit 方法统一 `void 方法名()`，参数化测试另列参数，checked IO 测试 `throws Exception`；GameTest 统一 `public void 方法名(GameTestHelper helper)`。每阶段测试类和路径在阶段末列出。

## 阶段签收

每阶段执行者新增 `docs/S1A.md`、`S1B.md`、`S1C.md`、`S2.md`、`S3.md`、`S4.md`、`S5.md`，包含修改文件、离线命令、完整输出路径、输入 SHA、逐项预期/实际、阻塞状态。通过后单独提交；任何必需集成挂钩未命中均阻塞对应阶段。不以普通 build 通过替代矩阵。

S2 使用 S3 定义的 `HistoryJournal` 基础实现是**拓扑前置**，不是提前实现 S3 玩法：S1B 先声明 `DurableLog` 接口，S2 完成日志与门事件，S3 增加烽火/驻军事件。这样不存在“先写易丢 GateLedger，以后再补日志”的窗口。

## 本次文档检查

- 8份文档，112个完整类型路径章节；另有嵌套record/enum、S0与RoadWeaver现有类的增量合同、资源及测试文件清单。
- 四份指定输入SHA-256已重新核对；S0基线、实际Road/Golden源码及MC/FTB/TYG本地JAR接口已对照。
- 各册代码围栏闭合；阶段和跨阶段拓扑、原§5/§6测试覆盖索引已提供。
- 本次只新增本目录Markdown；没有改Java/Gradle/第三方工程，没有运行构建、GameTest或游戏，没有网络/部署/真实存档访问。
- 文档检查不是编译证明。执行者从S1A开始按分册签收；任何NOT_RUN/BLOCKED不能写成PASS。
