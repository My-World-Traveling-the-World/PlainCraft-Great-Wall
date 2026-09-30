# S1B 收尾过程记录（非签收）

## 2026-09-30 单人接续检查点

- 依用户最新要求，已关闭两名现有子代理；后续不再分派。交接内容已读，未覆盖其未提交成果。
- `build/s1b-exit-drain-red.log` 实际执行 1 个 JUnit 并失败，证明 close 提前解除仍在执行的持久化决议数据预算。原始 XML 在 `docs/evidence/s1b-closeout/exit-drain-red/`；修复及物理公式变更说明见 `S1B-补充合同.md`。
- `build/s1b-drain-and-projection.log`：JDK21、严格离线，`test compileGametestJava --console=plain --no-daemon` 成功；52 个 suite，271 tests，0 failure/error/skip。此结果覆盖当时的 drain/nearest-edge/RealizationPlanner 修复，不覆盖后加的真实曲坡站点矩阵。
- 已提交 `2b84703`（出口异步排空及前置计量）、`870c5b1`（逐值等价最近边剪枝）。`WallContractEquivalenceTest` 独立旧算法比较曲坡、平距、负坐标和正负百万坐标；不是放宽精度或减少几何采样。
- `build/s1b-adjacency-third.log` 是正在运行的定向 10-method 子集：相邻 547 条、孤立 survival 960 条和 wall-only 曲坡 576 条。其运行期间新写 `CurvedSiteMatrixTests`，不会出现在本次已编译执行的子集中；新增测试必须另行编译并在最终不筛选构建中重跑。不能把磁盘新源码或旧 XML 冒充本次全部受测输入。
- 新增真实曲坡站点域：4×4 有序站点类 × 双向弯曲 × 两种变化 walkY，合计64条；使用实际 HeightContractPlanner 与 SiteScheduler.bindHeights 冻结平台，实际 resolver/owner/native replay 验证。当前新增域 **NOT_RUN**，不得签收 S1B。
- 目前 S1B 仍 IN_PROGRESS/BLOCKED；S1C 未开始生产接线，Tan's profile 仍 BLOCKED。没有联网、改锁版本、写真实 mods 或打开真实存档。

**状态：IN_PROGRESS。S1B 两项验收尚未全部闭合，S1C 生产接线仍关闭。**

本文件保留本轮中间结果，不能替代 `docs/S1B.md` 的最终签收。旧 BLOCKED 报告及反例不删除。

## 已完成的运行

所有下述构建均使用 `C:\Program Files\Java\jdk-21`、`--offline --dependency-verification=strict`，未部署真实 mods，未打开真实存档。

| 证据 | 实际结果 | 范围 |
| --- | --- | --- |
| `build/s1b-pressure-callback-fix.log` | BUILD SUCCESSFUL；8/8 GameTest | 压力项的完成回调已转回 GameTest tick，后继批次正常结束；不是全量验收 |
| `docs/evidence/s1b-closeout/admission-red/` | 16 JUnit 中 3 失败；6 GameTest 中 4 失败 | 独立审计反例修复前实际日志及 XML；不是只有源码推测 |
| `build/s1b-lifecycle-audit-green.log` | BUILD SUCCESSFUL；6/6 GameTest | 同配方/同 arena single-flight、取消隔离、关闭时保留已进入生产的 pin、串接 scratch 释放、调度拒绝交接 |
| `build/s1b-closeout-full.log` | BUILD FAILED | 曲坡 576 与 native 压力项结束后，异步 `helper.succeed()` 绕过原版测试 tick 的 listener 通知而停住；主代理终止该 GameTest 子进程，不能用旧 XML 冒充全量通过 |
| `build/s1b-pipeline-close-drain.log` | 编译失败 | 编译读到并行 worker 尚未补齐 helper 的相邻矩阵中间态；追加的 pipeline close-drain 测试尚未得到运行结果 |
| `build/s1b-arena-unit.log` | 15 JUnit，14 通过、1 失败 | codec 前置 charge 接入中间态暴露 FORT 的重复哈希字节数组压力；保持原 32 MiB，不修改失败断言；流式哈希修复待复测 |

## 本轮实现边界

1. `TerrainSnapshotService` 的 single-flight 同时校验 Coverage、recipe 与 arena。内部生产完成后先清 scratch，再发布成功；close 仅拒绝后继生产，不以提前失败的消费者视图为依据释放仍被 engine 持有的页。
2. `ProductionWallPipeline` 的 cell 交接把释放挂在异步结果外层，调度拒绝也归还 pin。预算 pin 与可取消计划租约分离，close 后等待实际依赖链排空，再归还活动工作集。
3. replay 采用与生产相同顺序取得 compiled/T0 预留。存证服务的写入、读取和验证显式传入 arena；返回的 readPlan 由调用者持有 arena，直到消费结束。
4. codec/store 的缓冲增长、字符串/集合解码、写前自检参与活动 scope。仅计算摘要的调用改用流式 canonical SHA，保持协议字节及哈希不变。该批改动仍在集成，不能据以上 6 项绿色测试宣称全部 I/O 路径已验证。
5. 高维窗口的 256 列共享一个不可变页索引，避免每列复制最多 64 页的 map；新增高坐标/hash 一致性 GameTest，当前 NOT_RUN。

固定限额仍为 terrain 64 MiB、compiled 32 MiB。两者是工作集准入额度，不是整个 Minecraft JVM 的堆上限；堆报告区分 baseline、采样峰值、最大堆与不同时刻的内存池峰值。

## 相邻矩阵

`AdjacentComponentMatrixTests` 首轮已注册并开始定向运行，日志为 `build/s1b-adjacency-first.log`。当前出现原版连通及状态序列化比对失败，尚未核清所有根因，不能签收。

冻结的首轮有限域为 8 个方法、547 条参数枚举，详细边界见 `docs/S1B-相邻矩阵.md`。单构件 survival 960、wall-only 曲坡 576 与这 547 条相邻矩阵是三个不同覆盖域，不能相加后声称所有参数的完整笛卡尔积已经通过。

## 待闭合

- codec/store 完整前置预留、只读结果存活期及真实 native 堆压力最终复测。
- 相邻矩阵全部反例分类、修复、重跑；保留真实缺陷与错误 fixture 的独立对照测试。
- 当前补充测试、默认不筛选的全量 JUnit/GameTest、严格离线 build。
- 按小批提交源码，生成新鲜原始证据和逐项签收报告，再单独提交 S1B 签收。
- 仅在上述签收之后开始 S1C。Tan's Huge Trees profile 始终 BLOCKED，未删除 mod 获取通过结果。

`tools/Export-S1BCloseoutEvidence.ps1` 用于最终取证，检查未筛选成功日志、测试时间、确切数量、每个矩阵的完成行、堆报告、依赖锁及源码提交；未满足条件时拒绝导出。它的存在不是验收通过证明。

## 接续诊断检查点

- `build/s1b-adjacency-first.log` 首轮已中止，退出码 1，原因是继续运行会重复已知待分类的 fixture/proof 失败；保留完整部分日志，不将其当全域结果。CONFLICT 128 条已到完成行；其余组按日志中实际完成行与个案记录核对，未完成条目标 NOT_RUN。
- 新增 `AdjacentFixtureDiagnosticTests`，分别隔离 Gate 的天然地面背景与 ChestType 的规范序列化问题。两项都保留原失败见证；Gate 若补完整 T0 仍不通必须继续红，不允许弱化跨路线连通断言。
- 对首轮 GameTest 进程进行只读 `jcmd Thread.print`，Server thread 位于 `WallSection.height -> column -> contract -> RealizationPlanner.resolve`。最近边搜索原先每次改进候选都重扫高度链；现在保持距离比较、首个平距候选、定点计算与区间筛选不变，只对最终胜出的边计算高度/横向距离。未改冻结几何公式。
- `WallContractEquivalenceTest` 保留独立旧算法，逐值比较曲坡、折返平距、端点和正负百万坐标；当前等待本轮执行结果。
- 正在执行 `build/s1b-closeout-diagnostic-v2.log` 对应全 JUnit + 指定 GameTest 诊断子集。该次不是默认完整 GameTest 签收。

### 该检查点实际结果

- `s1b-closeout-diagnostic-v2.log`：254 JUnit，0 failure/error/skip；18 个 GameTest 中 17 通过、Gate 诊断 1 失败，整体 BUILD FAILED。Gate 在完整 T0 背景下仍封闭，所以没有当成假红。
- native pressure 是真实隔离 ProtoChunk 管线的 8 owner + 8 逆序只读重放；该轮 whole-JVM baseline=453914072、sampled peak=855650264、max=1073741824 bytes；逻辑 terrain allocation peak=39254144、compiled=27678752 bytes。不是整个 JVM 只有 96 MiB，也不是 24 小时测试。
- `s1b-ground-portal-v1.log`：最小入口修订后的定向构建成功，3 GameTest 全通过，其中入口矩阵为 192/192。旧入口封闭和错误 S0 token 反例都保留。精确修订见 `S1B-补充合同.md`，尚不代替全量签收。
