# S1B 分配预留边界独立审计

## 1. 结论和取证范围

**结论：审计时的分配/生命周期闭包仍有阻塞缺口；本报告不是 S1B 签收。全部新增测试为 NOT_RUN。**

审计对象是工作区的实际代码，不是旧交接摘要。读取时 HEAD 为 `eba113a`，并包含主代理尚未提交的 `ProductionWallPipeline`、`TerrainSnapshotService`、`ChunkEvidenceStore`、`CanonicalCodec`、`ChunkPlanCompiler` 等修改。主代理随后修复时，本报告中的行号及判断须按下面的文件指纹定位到受审版本，不能把旧发现当作新代码仍然存在的问题。

本次只新增本报告、3 个 JUnit 类、2 个 GameTest 类。未修改任何既有生产类、测试、Gradle、注册资源；未启动 Gradle、游戏或 JVM；未联网、升级、部署、打开存档、提交 Git 或创建 worktree。用户告知主代理构建 `session3852` 在运行，本审计不干预该会话，不认领它的结果。

证据分级：

- **SOURCE_CONFIRMED**：已逐行核对的控制流、调用关系、缺失的预算参与和生命周期关系。
- **EXPECTED_FAIL / EXPECTED_PASS**：依据该版本控制流对新增测试的预期，不是执行结果。
- **NOT_RUN**：未编译、未运行的测试、真实堆测量、真实引擎行为。任何 EXPECTED_PASS 均不得写入阶段 PASS 统计。

## 2. 严重度排序的发现

### A01 / P1：成功 CellTerrain 交给异步消费者时，拒绝执行会遗失 terrain pin

**SOURCE_CONFIRMED。**

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ProductionWallPipeline.java`。

- `156–170`：`resolveCell` 中 `loadCell(...).thenApplyAsync(...)` 只在回调体的 `finally` 调用 `releaseTerrain`。
- `177–186`：`GroundExitResolver.Source.inspect/cap` 同样只在被调度的回调体内释放。
- `223–224`：`loadCell` 的错误清理只在 loadCell 自身失败时释放；已经成功返回 `CellTerrain` 后，消费者的调度失败不属于这个错误分支。

最小事件序列：terrain reservation 已准入 -> `loadCell` 成功 -> worker 拒绝 `thenApplyAsync` -> 回调体及其 finally 均不执行 -> owner future 失败，但 terrain reservation 仍在 `leases` 内。下一 owner 可因这份满额 64 MiB pin 无法准入而停住；最终关服才释放不算本次请求完成释放。

新增反例：`PipelineReservationHandoffAuditTests.rejectedInspectionContinuationReturnsTheSuccessfullyLoadedCellPin`。

测试从公开 `beforeLight` 进入；在 arc-plan 租约正常释放时注入“只拒绝下一次调度”；断言失败确实来自注入的同一个 `RejectedExecutionException`，不是 fixture 地形错误；在调用 `pipeline.close()` **之前**断言 `terrainReserved == 0` 和 `leases == 0`。当前源码预期失败。fixture 保持 64/32 MiB 生产限额，使用空 arc 数据隔离资源交接，不写方块、不宣称几何有效或真实引擎已验。

修复边界：成功 `CellTerrain` 的所有权交接必须覆盖消费者完成、异常、取消和执行器拒绝；释放挂在可能根本不执行的工作体之外，并保持幂等。禁止用同步等待、提前关闭仍在使用的数据、增大限额或最终关服清扫代替交接。

### A02 / P1：对外失败完成不等于生产链结束，close 提前归还活动数据预算

**SOURCE_CONFIRMED。**

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\TerrainSnapshotService.java`。

- `79–82`：清理 scratch/失败 retained pin 绑定的是对外 promise。
- `88–144`：内部生产链独立执行，`TerrainEngine.exclusive` 和 IO 可能仍在运行或持有已生成的页。
- `272–275`：close 直接异常完成上述 promise，并清除 flights/claims，未等待内部生产链结束。

同类管线入口：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ProductionWallPipeline.java:312–320` 直接关闭全部 lease 和 compilation arena；`AllocationBudget.close()` 将 used 清零并不能使被其他闭包引用的数据消失。

新增反例：`SnapshotAllocationBoundaryAuditTests.closeDoesNotReturnPinsBeforeAnEnteredProducerDrains`。

受控 engine 在第三次 exclusive 已执行 action、得到真实 `TerrainTile` 页之后暂缓完成 future；调用 service.close 时该 engine 仍强引用页。测试记录此时 budget.used，要求它不能为零；再释放 engine、排空实际生产链，要求失败资源最终归零。当前源码预期在“生产链仍持有页”时已归零。

修复边界：区分消费者视图终止、内部生产结束、结果消费者使用结束三种事件。close 可立即拒绝新任务/完成对外失败视图，但不得据此解除仍在运行的生产阶段或仍被消费的结果的预算所有权。清理必须在实际链路终止/结果所有权交还后发生；包括正在执行的 IO、已经排入 engine 的 action 和已经产生但尚未转交的 CellTerrain。

### A03 / P1：replay 与持久化校验的解码/复制开销没有接入预算

**SOURCE_CONFIRMED。**

直接入口：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\TerrainSnapshotService.java:159–182`：`persistPlan`、`persistExitDecision`、`readPlan`、`verifyExitProof`、`verifyLoaded` 只提交 IO，没有预算准入或结果 pin 协议。
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ProductionWallPipeline.java:104–105,257–261`：完整 marker 进入只读 replay，绕过 `runOwner` 后续的 compiled reservation 和所有 T0 capture reservation。

实际分配点：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\ChunkEvidenceStore.java:95–120`：compressed/raw 数组、palette、states、`TerrainTile` 防御性复制。
- 同文件 `192–203`：persist 编码后又完整 decode 自检；readPlan 完整 decode，均不参与 `AllocationBudget`。
- 同文件 `205–228,231–268`：出口证书写前 decode、自检读、owner proof 校验再次读证书。固定 8 MiB 文件上限不是这些对象的内存配额。
- 同文件 `281–313`：原记录副本、输出缓冲、输出复制、`readNBytes` 分配；没有统一工作集预留。
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\CanonicalCodec.java:134–158,388–403`：编解码未借用活动 compiled scope 的预算。

新增反例 `EvidenceAllocationBoundaryAuditTest` 中三项：

1. `persistPlanMustReserveScratchBeforePublishingIntoAFullWorkingSet`：在已经用满的活动 compiled scope 内调用持久化，要求 CAPACITY，且不得发布 build-proof。
2. `readPlanMustReserveScratchBeforeDecodingIntoAFullWorkingSet`：fixture 在 scope 外落盘，scope 内无余量时必须拒绝读取/解码。
3. `canonicalPlanEncodingMustReserveItsBufferInsideAProductionScope`：活动 scope 无余量时必须拒绝编码缓冲生产。

这三项当前源码预期失败：调用完成但没有 charge。它们证明“当前 scope 可被绕过”；单凭“方法没有返回”并不能证明未来修复在每个具体数组分配之前拒绝，修复仍须源码核对分配顺序。

修复边界：准入覆盖 read-only replay、直接 readPlan 的返回对象持有期、写前自检和所有 codec scratch。主代理可用显式传递的预算/结果租约完成原合同，无需改变文件协议。不得用串行 IO 当内存上限：多个 future 可同时保留已经返回的结果。readPlan 的数据必须在调用者用完前继续被计量。修复三个 replay 入口后仍要复核 persist 自检路径；普通 `CompiledAllocation` ThreadLocal 不会自动传播到 IO executor。

### A04 / P1：成功 promise 发布早于 scratch 清理，串接下一捕获可能误报容量不足

**SOURCE_CONFIRMED 的清理/发布次序；具体受控执行预期失败，NOT_RUN。**

位置：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\TerrainSnapshotService.java:79–85,141–145`。

清理是较早注册到 promise 的 dependent；随后返回的是 promise.copy。完成 promise 会触发消费者续作，不能假定此前注册的 sibling 清理回调已经完成。若消费者立刻开始另一个 owner 的 capture，仍在账上的旧 24 MiB scratch 会与新 scratch 重叠。多线程 worker 中这个窗口取决于调度，不应让容量接受结果由回调快慢决定。

新增反例：`SnapshotAllocationBoundaryAuditTests.completionReleasesScratchBeforeStartingTheNextSequentialCapture`。

测试保持 64 MiB，预先 pin 38 MiB 模拟已有页面；两个单页捕获顺序串接。每次只需要 24 MiB scratch，两个 retained 结果合计不到 2 MiB，正常复用完全装得下。控制 executor 在第一份 promise 完成时执行第二份续作，要求两份均完成；当前源码预期第二份提前碰到容量拒绝。这里的 38 MiB 是**逻辑占用 fixture**，不冒充真实堆对象压力。

修复边界：内部生产确实结束之后，先解除属于该生产阶段的 scratch，再发布可让消费者启动下一阶段的成功结果；保留返回 T0 的 retained 预算。不能仅交换 whenComplete 的注册顺序，必须显式建立清理先于成功发布的因果关系。该修复不得退化为 A02 的提前释放。

### A05 / P2：snapshot single-flight 有预算身份，但遗漏 recipe 身份

**SOURCE_CONFIRMED 的 API 行为。是否可穿透现有 WorldRecipeGuard 触发生产事故未验证。**

位置：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\TerrainSnapshotService.java:30–31,62,73–75,152–155`。

Flight 只存结果和 AllocationBudget；Coverage 内容/哈希不包含 recipe。相同 Coverage + 相同 AllocationBudget + 不同 recipeHash 的第二个请求直接复用第一个结果，在磁盘 `read(key, recipe)` 校验前就走了 fast path。

新增反例：`SnapshotAllocationBoundaryAuditTests.sameCoverageCannotJoinAFlightForAnotherRecipe`。先排队 recipe A，再请求 B，两个请求共用 arena；完成 A 后要求 B 异常结束，不能交付 recipe A 的页。当前源码预期 B 同样成功。

修复边界：在 flight 的身份或 join 守卫中包含 recipeHash；保留既有 arena 身份保护。不同 arena 不得偷借原 pin，不同 recipe 不得凭相同窗口偷借数据。这里不要求引入跨世界共享或改变新存档策略。

### A06 / 验收阻塞：逻辑 peak 不是 JVM 峰值，当前压力断言没有证明完整闭包

**SOURCE_CONFIRMED 的测试覆盖限制，不声称已经观测到 OOM。**

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\ReservationHeapPressureTest.java` 已区分 primitive 与 production scope，这一点正确。其 payload 没有经过 `TerrainSnapshotService` forecast/持久化/compiled arena，也没有验证生产分配闭包。
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\ProductionPipelineTests.java:24–75` 的新 native-pipeline heap 测试采样整个 GameTest JVM，使用 8 个 owner、128 格解析路线及准备好的 FEATURES。它输出真实采样值，但判定主要仍是逻辑 `terrainAllocationPeak/compiledAllocationPeak` 不超过配额；不是最大 palette、保护集、自检副本、执行器拒绝或 close-drain 的峰值证明。
- `MemoryPoolMXBean` 各池各自 peak 的和不是同一时刻的总堆峰值。采样得到的 max 是采样观测峰值，可能漏掉两次采样间的瞬时峰值；不能把这两种指标混称为精确 JVM 全程峰值。

本审计遵照限制未运行或读取冒充本次产出的堆证据。实际压力结果、最大支持输入下的 heap headroom、取消/关闭时对象存活均为 NOT_RUN。主代理完成修复后须串行补齐有明确输入规模、JDK/堆参数、baseline、采样值、配额值和退出状态的原始证据；不得把本报告新增 22 项控制流测试当作这项验收完成。

## 3. forecast 与真实分配的覆盖核对

| 路径 | 当前已落实的事实 | 仍需闭合/不得外推 |
|---|---|---|
| capture 入口 | 在 hints/forecast 前 reserve 24 MiB scratch；retained forecast 在 readSaved/capturePage 前 reserve | A02/A04 说明“在哪申请”之外，还要证明整个使用期被 pin；固定 24 MiB 的充分性尚无极端输入证据 |
| 新页 forecast | `1024 + 4*volume + paletteBytes`，每种状态计 `128 + 4*token.length`；protectedCount 等用于 metadata forecast | `capturePage` 的原始 states 与 TerrainTile.clone 同时存活；palette Map/List、UTF-8 hash 行属于 scratch；不能只用 4*volume 当全部峰值 |
| 已存页 hint | 流式扫描已存 palette 长度，不先分配完整 compressed/raw；实际 read 校验 recipe/CRC/hash | hint 与 load 之间的文件变化仍须由后续校验 fail closed；hint 本身不是证据授权，也不能替代 shared budget |
| metadata | 新捕获按 protected/entity/start 计数；已存 metadata 按 raw 长度给放大估值 | `EvidenceCodec.hashed` 编码、persist 再编码、readMetadata 自检、集合复制/排序同时存在；24 MiB scratch 是否覆盖上界需要证明和压力，不声称已知足够或已实测超额 |
| TerrainTile | states 防御性复制，按 palette 缓存 UTF-8 行并流式 SHA-256，未构建逐体素完整文本 | 仍有一次输入 states 与 clone 的重叠、lines 字节数组；流式哈希不等于没有临时分配 |
| SnapshotColumnView | 每列保存不可变 pages map、summary 引用、hash | forecast 用每列 512 固定估值；pages 数变化与临时 hash 字符串/字节开销未给独立最坏情况证明。合法 64-page 窗口需覆盖；这里不是宣称已发生溢出 |
| CompiledAllocation 嵌套 | 相同 budget 子 scope 把 keep 转给 parent；失败释放子 scope 的 charge；异预算嵌套拒绝 | retained 回调的 `keep <= charged` 是结果期不变量，不是前置准入；不能拿它代替遗漏的前置 charge |
| 构件/SupportGraph | 组件装配按 cell/voxel/entity charge；已销毁 builder 表通过 discard 退账；compiler 按候选/owner 数预 charge | SupportGraph 的多份 map、BFS 集合、hash 编码及动态属性文本没有独立计量。现有 256/128/512 常数是否覆盖同时存活对象，应给上界推导和针对性测量，而不是仅断言账面峰值 |
| persist/replay | 固定文件长度、CRC/SHA、拒绝篡改和旧证据覆盖 | A03：输出尺寸上限不等于整个缓冲/解码对象的分配预留 |

没有确认 `BudgetReservation` primitive 的新的重复释放或计数竞争缺陷。新增的 FIFO 取消、reentrant close、已交付 pin 后执行器拒绝测试用于防回归，不能标为已经执行通过。

## 4. 新增测试清单

全部运行状态 **NOT_RUN**；未编译。下表“静态预期”只描述本报告指纹对应源码，修复后应全部通过。没有 disabled/assumption/排除配置，也没有把当前错误行为写为应通过的断言。

### JUnit：BudgetReservationLifecycleAuditTest（6）

完整路径：`D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\diagnostics\BudgetReservationLifecycleAuditTest.java`。

| 方法 | 检查 | 静态预期 |
|---|---|---|
| cancellingBeforeOwnerDrainNeverCreatesAHeldPin | 准入队列取消后无 held pin，可再申请完整容量 | EXPECTED_PASS |
| cancellingFifoHeadAdmitsSmallerSuccessorWithoutReleasingExistingConsumer | FIFO 不插队；取消队首后尾部可用剩余容量 | EXPECTED_PASS |
| executorRejectionFailsWaitersButCannotUnpinDeliveredData | owner 拒绝只终止等待者；已交付 pin 留账到实际 close | EXPECTED_PASS |
| closeBeforeDrainFailsEveryWaiterAndQueuedDrainCannotResurrectThem | close 后旧 drain 不复活任务 | EXPECTED_PASS |
| reentrantCloseOnFirstGrantCannotLeakOtherAlreadyAdmittedJobs | 同批准入的第一回调重入 close 不泄漏其他 held job | EXPECTED_PASS |
| failingConsumerOwnsItsPinUntilFinallyClosesIt | 消费者失败后按拥有权释放 | EXPECTED_PASS |

### JUnit：CompiledAllocationScopeAuditTest（5）

完整路径：`D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\diagnostics\CompiledAllocationScopeAuditTest.java`。

| 方法 | 检查 | 静态预期 |
|---|---|---|
| successfulChildTransfersOnlyRetainedBytesToItsParent | 子 scope scratch 释放、keep 上移、parent 可 discard | EXPECTED_PASS |
| childCapacityFailureRestoresParentAndReleasesOnlyChildCharges | 子 scope 容量失败不释放父 scope 原占用 | EXPECTED_PASS |
| failedRetentionEstimatorRestoresThreadLocalScope | retained 估算器抛异常后无 ThreadLocal 泄漏 | EXPECTED_PASS |
| retainedBytesCannotExceedPrechargedBytesEvenAfterNestedSuccess | nested keep 超过 charge 时 fail closed 并释放 | EXPECTED_PASS |
| foreignNestedBudgetIsRejectedBeforeItsProducerAndParentSurvives | 不同 arena 的嵌套在进入子 producer 前拒绝 | EXPECTED_PASS |

### JUnit：EvidenceAllocationBoundaryAuditTest（5）

完整路径：`D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\persistence\EvidenceAllocationBoundaryAuditTest.java`。

| 方法 | 检查 | 静态预期 |
|---|---|---|
| persistPlanMustReserveScratchBeforePublishingIntoAFullWorkingSet | 无余量不能编码/自检/发布 proof | EXPECTED_FAIL，A03 |
| readPlanMustReserveScratchBeforeDecodingIntoAFullWorkingSet | 无余量不能读取/解码 proof | EXPECTED_FAIL，A03 |
| canonicalPlanEncodingMustReserveItsBufferInsideAProductionScope | canonical 输出不能绕过活动 compiled scope | EXPECTED_FAIL，A03 |
| allocationHintAccountsForPaletteTextAndPreservesOriginalT0Hash | 大 palette 文本、modified UTF 字符、T0 完整 round trip | EXPECTED_PASS；不是 heap 充分性证明 |
| allocationHintIsNotAuthorityToReadWithAnotherRecipe | 有 hint 也必须拒绝错误 recipe 的 read | EXPECTED_PASS |

### GameTest：SnapshotAllocationBoundaryAuditTests（5，尚未注册）

完整路径：`D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\SnapshotAllocationBoundaryAuditTests.java`。

| 方法 | 检查 | 静态预期 |
|---|---|---|
| sameFlightCannotBorrowAnotherAllocationOwner | 同 Coverage 不同 arena 不偷借 pin | EXPECTED_PASS |
| sameCoverageCannotJoinAFlightForAnotherRecipe | 同 arena 不同 recipe 不共享 T0 | EXPECTED_FAIL，A05 |
| cancellingOneViewDoesNotCancelItsPeerOrUnpinDeliveredT0 | 取消视图隔离；成功数据保留预算 | EXPECTED_PASS |
| completionReleasesScratchBeforeStartingTheNextSequentialCapture | 串行后继在固定 64 MiB 中复用 scratch | EXPECTED_FAIL，A04 |
| closeDoesNotReturnPinsBeforeAnEnteredProducerDrains | close 不提前释放尚被 engine future 持有的真实 T0 页 | EXPECTED_FAIL，A02 |

### GameTest：PipelineReservationHandoffAuditTests（1，尚未注册）

完整路径：`D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\PipelineReservationHandoffAuditTests.java`。

| 方法 | 检查 | 静态预期 |
|---|---|---|
| rejectedInspectionContinuationReturnsTheSuccessfullyLoadedCellPin | 实际 beforeLight/CellTerrain 链路的调度拒绝不泄漏 terrain pin | EXPECTED_FAIL，A01 |

合计 **16 JUnit + 6 GameTest = 22**；其中 7 项预期暴露当前缺陷。这不是 22 项运行记录，更不是 15 PASS / 7 FAIL 的实测结果。

## 5. 给主代理的集成顺序和复现命令

1. 等现有构建会话结束；本审计没有启动或停止任何构建。
2. 修复 A01/A02/A04 的真实生产生命周期，再接通 A03 的所有读写/自检预算；保留同 budget 嵌套和已有 single-flight 预算身份保护，补 A05 的 recipe 校验。
3. 主代理把两个新 GameTest 类加入 `src/gametest/resources/fabric.mod.json` 的 `fabric-gametest` 列表。本审计没有权限修改该资源，**在注册前不能期待发现这 6 个测试**。
4. 先跑下面的定向回归，归档原始失败及修复结果；再做严格离线全量构建、生产路径峰值验证。不能过滤掉预期失败项来签收。

以下仅是复现说明，本次 **未执行**：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*BudgetReservationLifecycleAuditTest' --tests '*CompiledAllocationScopeAuditTest' --tests '*EvidenceAllocationBoundaryAuditTest' --rerun-tasks --no-build-cache --console=plain
```

两个 GameTest 类由主代理注册后才能执行：

```powershell
.\gradlew.bat --offline --dependency-verification=strict runGameTest '-PgreatwallGameTestClasses=SnapshotAllocationBoundaryAuditTests,PipelineReservationHandoffAuditTests' --rerun-tasks --no-build-cache --console=plain
```

上述 GameTest 使用 detached ProtoChunk 和单线程受控 executor 来确定性重放调度边界，不证明 VanillaTerrainEngine/C2ME 原生锁授权；没有把它们外推为 S1C 的真实世界验证。

## 6. 审计时源码指纹

根目录统一为 `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\`；SHA-256 是读取时工作区文件字节，不是 Git 规范化 blob。主代理修复后预期改变，不能据此要求回滚。

| 相对根目录的文件 | SHA-256 |
|---|---|
| diagnostics/BudgetReservation.java | 4C63A26266F80BD72C5E716246B66F4D309DF394785A68D151F8FA0D18F3C5E3 |
| diagnostics/AllocationBudget.java | A95954507D0B7C360984D99AEA421F32D374B3B57945254ED2469F142E765DF2 |
| diagnostics/CompiledAllocation.java | 23A96CBC555EB0F05ABF978BDC0FC2C8C7FA86E1A0C078A3D5566439DDADAFF2 |
| generation/ProductionWallPipeline.java | 403463B1E5DF5E300B56A40ACA15B83668D292F8B09A73826021AD0D6CEA39DB |
| terrain/TerrainSnapshotService.java | 436639F6A5D06F4A74E061FB64F643DDFCC9EA38F95C87CD8F739120D8D7E07B |
| persistence/ChunkEvidenceStore.java | 8BDE4ED4753D63E249E2A4CBA8949A9852D969EA611C625820B22C9B9AB94BF1 |
| geometry/CanonicalCodec.java | 3F3BFEFD8F3715E10FADAE0934FFE0D1FDE7E27BC095F5C4E7CB569C89ACAE3C |

报告文件：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-分配预留边界审计.md`。

**最后状态：审计及反例源码已交付；编译、JUnit、GameTest、JVM 峰值、S1B 签收全部未由本审计执行或确认。S1C 不因本报告解禁。**

## 7. A03 codec/store 修复交接（后续检查点）

本节追加后续修复状态，不改写上文的审计时事实、静态预期和反例。主代理报告原红态为 JUnit 3 FAIL、GameTest 4 FAIL，原日志/XML 已由主代理留证。本代理没有运行 Gradle、JVM、GameTest，没有提交 Git；本节新增测试均为 **NOT_RUN**。本节不是 S1B 签收，更不是 JVM 堆峰值证明。

### 7.1 本次独占写集

修改的生产文件：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\CanonicalCodec.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\ChunkEvidenceStore.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\EvidenceCodec.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\GroundExitCodec.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\diagnostics\BoundedByteArrayOutputStream.java`

新增生产辅助类：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\diagnostics\CodecMemory.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\diagnostics\CodecIO.java`

新增纯 Java 测试：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\diagnostics\CodecScratchBoundaryTest.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\persistence\StreamingEvidenceBudgetTest.java`

本报告同步追加交接。没有改 AllocationBudget、CompiledAllocation、TerrainSnapshotService、ProductionWallPipeline、其他现有生产类、既有测试、Gradle 或注册资源。

### 7.2 服务层接法及对象归属

1. `CanonicalCodec.componentHash(ComponentVoxels)` / `writePlanHash(ChunkWritePlan)` 对原 canonical 字节流计算 SHA-256，不分配完整 payload。旧 `component()` / `writePlan()` 保留为返回真实 `byte[]` 的接口。`hashComponent` / `hashWritePlan` 是相同实现的别名。
2. `EvidenceCodec.coverageHash` / `metadataHash`、`GroundExitCodec.hash` 同样流式。`hashed` 只保留新外层 record 和新 hash 字符串，共享输入集合仍由输入所有者持有。
3. 编码数组用第一遍精确计数，第二遍分配精确容量；返回数组不再经过 payload 等大的 `toByteArray` 复制。必须复制/扩容的兼容接口先预留新旧数组同时存活的额度，再分配，最后释放旧额度。`ByteArrayOutputStream.close()` 保持原来的无操作语义；显式释放用 `releaseBuffer()`。
4. 每个 store 读入口、写入口和验证入口自有同步 `CodecMemory` scope。写入与验证在方法退出时释放所有内部临时 charge；读取在退出时仅转交实际返回值的 allowance，raw payload、解码中间集合、路径 hash、排序缓冲均释放。失败路径不转交结果。scope 不得跨线程或跨 `CompiledAllocation` frame。
5. 服务层持久化/验证 IO lambda 用 `CompiledAllocation.run(arena, producer, ignored -> 0)`。读取使用 `CompiledAllocation.retainAll(arena, producer)`，或者 `run(arena, producer, ChunkEvidenceStore::retainedBytes)`。结果消费者结束后由 arena 所有者释放。不要在同一预算中再次 consume 该返回对象 allowance。
6. `ChunkEvidenceStore.retainedBytes(Object)` 支持各 store 返回模型及 Optional；empty Optional 为 0。`CanonicalCodec.retainedBytes(ChunkWritePlan)` 返回同一套模型 allowance。共享引用不去重，结果属于保守 allowance，不是 HotSpot 精确大小，也不是 GC 后的 Java 堆峰值。
7. capture 的已有 forecast pin 负责最终 T0 长期占用时，codec IO 在主代理创建的固定 scratch 子 arena 内执行 `run(..., keep=0)`。子 arena 对临时对象计量，不给主 arena 再加一份相同 retained pin。保存 T0 的读取预测至少覆盖 palette 文本、状态数组和解码返回对象 allowance；metadata 的预测覆盖保护点集合。
8. `persistBeforeApply` 的返回值现在明确为**借用的输入 tile**；存在同 hash 的原始证据时不再把临时解码校验副本传给调用者。磁盘原始证据不重写，hash 不一致仍拒绝；调用者不能把返回值当新增 owned 副本重复 retain。生产 capture 当前忽略该返回值；S0 coordinator 的返回值仍与原始证据具有同一 T0 hash。
9. 32/64 MiB 上限没有修改。UTF-8 的既有 4096 字符前置检查保留，后续仍检查编码字节上限；孤立 surrogate 的编码替换行为与旧 JDK `getBytes(UTF_8)` 一致，解码继续严格拒绝非法 UTF-8。modified UTF 与 `writeUTF/readUTF` 对齐。

### 7.3 新增未运行测试清单

以下 **25 项全部 NOT_RUN**。协议对照使用测试内独立的旧格式 writer，不只对比两个共同调用同一新 helper 的新接口。

| 类 | 方法 | 验证边界 |
|---|---|---|
| CodecScratchBoundaryTest | bufferGrowthDenialKeepsOriginalCapacityAndContents | 增长前拒绝，原数组不变 |
| CodecScratchBoundaryTest | copyDenialDoesNotReleaseOrModifyOriginalBuffer | 复制前拒绝，原 owner charge 不释放 |
| CodecScratchBoundaryTest | exactTakeTransfersOnlyTheArrayWithoutCopyHeadroom | 精确容量零复制转交 |
| CodecScratchBoundaryTest | byteArrayStreamCloseRemainsCompatibleWithReadingAfterClose | close 后读取的既有语义 |
| CodecScratchBoundaryTest | failedNestedScopeReleasesScratchWithoutReleasingParentCharge | 嵌套失败仅释放本次 scratch |
| CodecScratchBoundaryTest | nestedResultsAndScratchTransferOnlyRetainedBytes | 子 scope 仅转交返回 allowance |
| CodecScratchBoundaryTest | modifiedUtfBytesMatchDataOutputIncludingNullAndUnpairedSurrogates | NUL、中文、代理对、孤立 surrogate 的原 modified UTF 协议 |
| CodecScratchBoundaryTest | utf8EncoderRetainsLegacyReplacementBehaviorForUnpairedSurrogates | 原 UTF-8 替换行为反例 |
| CodecScratchBoundaryTest | validUtf8MatchesJdkAndRetainsOnlyDecodedString | UTF-8 串解码的结果保留 |
| CodecScratchBoundaryTest | invalidUtf8AndTruncationReleaseAllDecodeScratch | 错误 UTF-8 与截断无残留 |
| StreamingEvidenceBudgetTest | componentAndPlanHashesMatchIndependentLegacyBytes | canonical 协议与流式 SHA 一致 |
| StreamingEvidenceBudgetTest | largeComponentHashDoesNotRequirePayloadSizedCapacity | 大于 1 MiB 的构件用 16 KiB scratch 哈希 |
| StreamingEvidenceBudgetTest | planDecodeRetainsExactlyTheReturnedObjectGraph | 解码后没有 payload charge 残留 |
| StreamingEvidenceBudgetTest | dimensionValidationFailureDoesNotRetainDecodedResult | 已解码但维度校验失败仍释放 |
| StreamingEvidenceBudgetTest | malformedDecodeCanRetryInsideTheSameArenaWithoutAccumulation | 同 frame 多次拒绝后可正常解码 |
| StreamingEvidenceBudgetTest | repeatedLargePlanPersistenceUsesBoundedScratchAndNeverOverwrites | 大 proof 用 64 KiB scratch 持久化/重复验证，不覆盖冲突证据 |
| StreamingEvidenceBudgetTest | storeReadRetainsOnlyOptionalAndDecodedPlanNotPayloadBytes | Optional 和结果所有权准确转交 |
| StreamingEvidenceBudgetTest | absentReadsLeaveNoPathHashOrWrapperCharges | 缺失证据读取不积累临时 hash |
| StreamingEvidenceBudgetTest | metadataBytesAndStreamingHashMatchIndependentLegacyProtocol | metadata 协议、哈希、返回计量 |
| StreamingEvidenceBudgetTest | exitCertificateBytesIncludeLegacyModifiedUtfAndCapFootprints | schema 2 出口证书及 cap footprint 字节保持 |
| StreamingEvidenceBudgetTest | repeatedExitProofVerificationReleasesEachCertificateScratch | 多次验证不积累证书解码图 |
| StreamingEvidenceBudgetTest | t0StreamingPersistenceAndReadKeepStableHashAndNoScratch | T0 流式往返、palette、状态和 hash |
| StreamingEvidenceBudgetTest | tileForecastCoversDecodedRetainedAllowanceEvenForTinyPalettes | 小 palette 也不漏固定开销 |
| StreamingEvidenceBudgetTest | metadataForecastCoversProtectedPointRetainedAllowance | 保护点密集时预测覆盖返回集合 |
| StreamingEvidenceBudgetTest | persistenceStillRejectsMapKeysThatCollideAfterLegacyUtf8Replacement | 移除整份 decode 自检后仍拒绝不可读 map |

供主代理串行运行，本代理没有执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*CodecScratchBoundaryTest' --tests '*StreamingEvidenceBudgetTest' --tests '*EvidenceAllocationBoundaryAuditTest' --tests '*CanonicalCodecTest' --tests '*BoundedByteArrayOutputStreamTest' --console=plain
```

### 7.4 未闭合的运行证据

- 本次最终源码的编译、新增 25 JUnit、原反例修复回归：**NOT_RUN，本代理未确认**。
- 原相邻矩阵运行使用更早的已编译检查点，本代理只读到其进入 GameTest 的日志；没有把运行中矩阵判为 PASS。
- 固定 32/64 MiB 下原生 Fort、重放、销毁时序和 Java 堆峰值：由主代理串行运行，**不能由逻辑 allowance 检查代替**。
- A01/A02/A04/A05 的主代理生产修复不属于本节写集；本节不重新签收它们。
