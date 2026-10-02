# S1C owner coverage 诊断与方案

日期：2026-10-02（Asia/Shanghai）
基线：`e2959de`。本文件是诊断，不是 S1C 签收，也没有修改触发/捕获判据。

## 结论

已验证的 GameTest 反例：`ProductionPipelineTests.fringeOwnerMissingDurableCoverageIsRejectedBeforeApply`。
fixture owner `ChunkKey[minecraft:overworld, x=2, z=6]` 的投影横向偏移为
`26752 Q8 = 104 格`；`cellsForChunk` 返回 arc cell `0..3`，而四个 cell 的
`realizationOwners` 都不包含该 owner。流水线因此在
`ProductionWallPipeline.compileAndApply` 的 `collected.own()==null` 处以
`T0_MISSING / owner not in realization evidence` 拒绝，APPLY 次数为 0。

离线归档分析（`docs/evidence/.../entrance-ab17-final-20261002-134025`）重建出
505 个 traversal owner，日志中的实际捕获 owner 为 133 个；请求域与捕获域差集
有 219 个。这个差集证明设计不变式破坏，但因为旧日志未保存 LIGHT requester，
不能把任一差集成员冒充 30 分钟真实运行的唯一失败 owner。

## 方案对比

| 方案 | 内容 | 代价/风险 | 判断 |
|---|---|---|---|
| A | 把触发面收缩到与 `realizationOwners` 同一归属判据，或由同一纯函数生成两边的 owner 集 | 触发 chunk 减少；必须证明不会漏掉模板/出口/水门的潜在写集 | 推荐，先做纯函数与不变式测试 |
| B | 触发面不变；每个请求 owner 锁外独立 capture、绑定自己的 durable coverage | 读域、FEATURES lease、预算、pin、drain 显著扩大；可能重复 capture | 保留为备选，不先实施 |
| C | 增加 `cellsForChunk(c)!=empty -> c in realizationOwners(cell)` 守卫 | 只能早失败，不能修复覆盖缺口 | A/B 配套，不能单独交付 |

方案 A 还要求真实 traversal fixture 不再要求非 owner chunk 拥有墙 marker：
仍遍历它、断言未写墙并明示 `NON_OWNER`，不能把它删出样本或用假空 coverage 补证。
必须验证所有实际潜在写集 owner 仍触发，不能只证明“被触发者都有捕获”而漏写墙。

无论选哪项，保留 `collected.own()==null`、before-state、负空间、结构盒、
BE/tombstone 与 writer 权限；不合成空 coverage。S1C-DRAIN-01 继续独立未闭合。

**当前状态：等待用户确认 A/B；在确认前不改生产 coverage 判据。**

## 源码与本轮改动

| 绝对路径 | 已验证事实或改动 | 范围 |
|---|---|---|
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\CoveragePlanner.java:45` | `cellsForChunk` 使用投影横向 196 格及前后 192 格；`:60` 的 `realizationOwners` 使用 station 周围 16 格及模板包络 | 原判据未改；这两者不等价 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ProductionWallPipeline.java:150` | 增加缺失 owner/cell/投影/捕获集合诊断；`:186` 记录每个 cell 的 metadata 缺失 | 只增加诊断，原 `T0_MISSING` 拒绝保留 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\generation\ArchivedOwnerCoverageTest.java:19` | 用原缓存解码器重建旧请求面并输出差集 | 离线读取归档，不读真实存档 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\ProductionPipelineTests.java:24` | 104 格横向偏移反例真正走到原异常；APPLY 为 0 | 原生 ProtoChunk/T0，但调度端口使用 fixture-local executor 与 StageTrace 锁哨兵 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\FringeOwnerCoverageTests.java:9` | 独立注册上述反例，完成通知延迟一 tick，使 XML 完整记录 | 不改 timeout，不删红例，不加 Disabled |
| `D:\云游四海-Mod源码\plaincraft-great-wall\tools\Invoke-VerifiedRun.ps1` | 命令、源码 SHA、UTC 起止、退出码及日志 SHA 归档 | 命令退出 0 不自动等同阶段 PASS |

建议 A+C 的守卫应精确到**每个返回的 cell**：
`forall owner, cell in cellsForChunk(owner): realizationOwners(cell).contains(owner)`。
双方只引用一个归属函数；不能另写一套相似的距离判断。另加正向验证：
模板、水门、出口的所有潜在写集 owner 都仍被派发，防止通过减少派发偷偷丢墙。
这些是待批准方案，尚未实施。

## 定向测试与失败尝试

最终受测长城源码为 `e84c30b`，证据位于
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s2\initial-20261002\final-unit-native\`。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test runGameTest `
  '-PgreatwallGameTestClasses=FringeOwnerCoverageTests,OwnershipMutationTests' `
  --no-daemon --console=plain
```

已验证：全量 JUnit 367/367、69 套件；本次 GameTest 子集 5/5，控制台/XML 一致，
两者 failure/error/skipped 为 0，命令退出 0。GameTest 不是全量，更不是真实墙证书。

保留失败证据目录：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\owner-coverage-20261002\`。
其中 `native-counterexample.log` 是使用原 Vanilla mailbox 时的超时；
`native-and-ownership.log` 保留 AttachmentType 方法名错误导致的编译失败；
`native-and-ownership-v2.log` 与 `results.xml` 保留控制台 8 项但 XML 仅 7 项的差异。
最终独立 5 项 XML 不回填或改写这些旧事实。

## 真实 seed17 诊断

本轮 `ownercov20261002-173902` 是**修复前的只读诊断**，源 `e84c30b`。
证据目录：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\owner-coverage-20261002\ownercov20261002-173902\`。
本轮实际失败 owner 只属于本轮，不能反向冒充旧运行的唯一 requester。

```powershell
.\tools\Invoke-VerifiedRun.ps1 `
  -Evidence 'docs/evidence/s1c/owner-coverage-20261002/ownercov20261002-173902-REPRO' `
  -Tasks @('runServerSmoke','-PsmokeProfile=vanilla',
    '-PsmokeRunId=ownercov20261002-173902-REPRO','-Ps1cRealWall=true',
    '-Ps1cSeed=17','-Ps1cRepresentativeBlocks=100')
```

复现必须使用新的 runId/证据目录；禁止覆盖已有 T0/manifest。
已验证启动日志：root `(206,67,953)`，17:43:31 开始遍历 505 个 owner，
正式 production 为 OFF；这不等于完成了 505 个 owner。

### 本轮最终结果【已验证】

运行起止：2026-10-02 17:39:03 至 18:16:45（Asia/Shanghai），
2261.775833 秒，即 37 分 41.776 秒；命令退出码 **1**。
受测 sourceCommit 为 `e84c30b`；归档时 HEAD 已是纯文档提交 `065790d`。
运行前后的 **290 项源码/构建输入 SHA 全部一致**，没有在运行中修改生产输入。

**确切失败 owner：`ChunkKey[dimension=minecraft:overworld, x=9, z=56]`。**
获取方式是事先加入的只读诊断，不是从差集猜选。
最终 `S1C_OWNER_COVERAGE_MISSING` 位于原始 `run.log:292`，
时间 18:15:43，列出请求 cell **0、1、2、3**；
`:293` 紧接 `FIXTURE_FAILED`，`:313` 的 cause 为原
`WallFailure: owner not in realization evidence`。

| 原始日志行 | cell | 捕获 owner 数【已验证】 | 含 `(9,56)`？ | 投影【已验证】 |
|---|---|---|---|---|
| 288 | 0 | 79 | 否 | lateralQ8=18486，globalSQ8=0 |
| 289 | 1 | 91 | 否 | lateralQ8=18486，globalSQ8=0 |
| 290 | 2 | 106 | 否 | lateralQ8=18486，globalSQ8=0 |
| 291 | 3 | 118 | 否 | lateralQ8=18486，globalSQ8=0 |

【推导】18486/256 = **72.2109375 格**，在触发的 196 格限额内；
chunk 的 X/Z 区域为 `[144,160) × [896,912)`。
四次 metadata 缺失与最终 own==null 对应，直接证明本轮触发面/捕获面缺口。
各 cell 数量不能相加当作独立 owner 数。

【已验证】归档中实际有 **133 个 `coverage.bin`**；这是耐久捕获文件数，
不是 505 个 owner 完成数，也不是 APPLY 完成数。
STOPPING 的 `barrier` 没有 `PRODUCTION_APPLY_ENTER/EXIT`，两种记录的计数均为 **0**。
**`real-wall-progress.json` 未产出，`voxels=null / NOT_AVAILABLE`**；
不能把缺失字段填成 voxels=0，更不能称 voxels>0。S1C 硬验收未达成。

本轮 root 最大稳态批次 **18.8661ms**、超 40ms 批次 **0**，
来自 `world/greatwall/diagnostics/session-STOPPING.json`，只代表本轮 vanilla。
failed-owner 墙钟等待为 **1931.8899474 秒**，是失败链耗时，
**不是成功每 chunk 生成成本**。不能把这个失败计时用于签收性能。

### 关服事实与独立缺陷【已验证】

18:16:44 watchdog 报单 tick 60 秒并强制退出，`run.log:394`；
原始 crash report 和 latest/debug 日志已完整归档。
STOPPING 的 `barrier` 为 **flights=8、admitted=8**，没有 STOPPED 导出。
**S1C-DRAIN-01 再次观察到，仍 OPEN**；不主张正常排空，也不扩大本轮修复授权。

线程取证 `threads-20261002-1758.txt` 的实际写入时间为 **17:52:33**，
以 `threads.capture.json.fileWrittenUtc` 为准，不以文件名当时间证据。
该瞬时栈显示 `greatwall-t0-io` 在结构保护扫描，不能据此证明全程无死锁。

### 证据保全

同目录保存 `execution.json`、`diagnosis.json`、逐输入 SHA、全部日志、
watchdog crash report 和原始 `world/greatwall`（manifest/cache/T0/diagnostics）。
只复制这个带 `greatwall_test_world` 和 `greatwall_s1c_fixture` 标记的新隔离世界；
原测试世界保留，不改写、不迁移、不回填永久 T0/manifest，不读真实存档。
`archive-files.sha256.json` 逐文件记录 SHA，Git 的 S1C `-text` 属性保留原字节。
`.gitattributes` 对本次 JVM 原始 `.txt` 证据关闭空白清理检查，
保留 dump/crash report 的尾空格及空行；核验 Git 长路径文件时同样使用
`git -c core.longpaths=true`，不把工具路径限制误报成证据丢失。

派生诊断的第一次导出把 PowerShell 属性集合计数误序列化成数组；
错误版本保存在 `diagnosis-export-first-invalid-count.json`，
修正版 `diagnosis.json.sourceInputsChecked=290`，明确记录修正原因。
这个导出修正不改原始日志或任何运行输入。

**结论：根因诊断完成；推荐 A+C，仍等用户确认后才改 coverage 判据。**
旧运行的唯一 requester 仍不可恢复，本轮 `(9,56)` 不反向替代旧事实。

## NOT_RUN 与边界

coverage 修复后的 seed17 APPLY、真实墙重进连续、vanilla/Tectonic/C2ME 代表 seed
完整验收及成功每 chunk 成本均 NOT_RUN。全 GameTest/最终 build、完整植被抑制、
同 JVM A→B→A、6000 高/非默认 minY/Golden、S0 百轮、
S5 长压/24h/人工实走/视觉签收仍 NOT_RUN。
Tan's Huge Trees BLOCKED；40ms/25ms 与 writer 权限不变。
S1C-DRAIN-01 独立未闭合，未主张正常排空。
