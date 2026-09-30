# S1C 一次性 lane-native 初始化协议

状态：**本次初始化协议已实现并通过前置验证 / 不是 S1C 整阶段签收 / 生产接线 OFF**。输入基线 `fad944e`，最终验证源码 `6026605`，2026-09-30。

## 1. 授权与冻结合同差分

本次用户批准把一次性原生初始化与稳态采样拆成两个协议，不改变地形算法、采样预算、日程或画面质量。

1. 旧公式：`docs/S1-S5实施规格/S1-城墙本体.md:1082` 的构造/单 cell 超过 40ms 阻塞条款，此前把私有 RandomState 冷初始化也纳入采样超限。
2. 具体反例：旧真实证据中 Tectonic 初始化最大 121.7527ms、C2ME 最大 1851.2769ms。旧证书保持 BLOCKED，不回写为 PASS。
3. 获批最小修订：每 session 每 lane 的一次性 native 初始化显式锁外异步、独立容量/计时/取消/异常/drain，可超过 40ms；稳态 slices/cell/block/biome/advance 仍是 25ms 目标、40ms 检测。
4. 明确不变：私有同 seed/settings 的 RandomState；不使用 live 世界 RandomState；不跨 lane/session 借用；不在主线程或 worldgen 同步等初始化；startup 不等正常 tick，也不等 native ready；所有 P0/红线不变。
5. 验证与残余范围：按下列批次累计，不把协议单测写成真实墙生成验收。

这是协议拆分，不是静默放宽采样门槛：切片上限用于避免可重复采样工作卡顿；原生库一次性编译是另一个不可分割生产者，必须移出调用者/锁/tick，并用独立 future 与 drain 明示它的成本。实际 C2ME 变换字节码 `docs/evidence/s1c/native-oracle-profiles-20260930/abi/runtime-c2me-RandomState.txt:162` 在构造器返回前调用 `c2me-opts-dfc$postCreate`，`:430` 同步 `BytecodeGen.finalizeCompilation`。**Tectonic 单独约 122ms 的内部阶段分解仍未查明，不套用 C2ME 归因。**

## 2. 批次一：lane 生命周期与调度依赖

修改文件，根路径均为 `D:\云游四海-Mod源码\plaincraft-great-wall\`：

- `src/main/java/local/plaincraft/greatwall/terrain/OracleLane.java`，新增独立生产者。
- `src/main/java/local/plaincraft/greatwall/terrain/NaturalTerrainOracle.java`，同步诊断默认拒绝，只有解析 fixture 显式授权。
- `src/main/java/local/plaincraft/greatwall/planning/PlannerScheduler.java`，job 不持有 oracle；ready 前只接 future；lane 复用；增加真实 drain future。
- `src/test/java/local/plaincraft/greatwall/terrain/OracleLaneTest.java`，9 项生命周期反例。
- `src/test/java/local/plaincraft/greatwall/terrain/AnalyticTerrainOracle.java`，解析参考输入显式 opt-in。
- `src/test/java/local/plaincraft/greatwall/planning/PlannerSchedulerTest.java`，14 项（原 12 + 2）。

`OracleLane` executor 单线程独占 mutable oracle；初始化 future 仅公开 `Void` 副本，取消副本不取消生产者。初始化一次，最多 64 个批次先预留再入队，两个固定 lane 是 session 初始化容量。初始化错误不重试、不静默换输入；失败 job 可能遗留 cursor，调度器关闭整 session，不能把污染 lane 借给下一 job。关闭即拒绝新申请、让公开 promises 失败，但 close runnable 排在真正 constructor/动作之后；drain 仅在 owner 关闭 oracle 成功后完成，close 异常使 drain 异常。没有 shutdownNow 代替生产者结束。

初始化允许 CPU 原生构造的专用上下文下一批接入。本批不能单独证明 CpuTerrainOracle 的实际 native 初始化守卫或性能。

### 实际命令与结果

所有命令先设置 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`：

```powershell
.\gradlew.bat --offline --dependency-verification=strict test --tests 'local.plaincraft.greatwall.planning.PlannerSchedulerTest.hundredCallersShareOneCompute' --rerun-tasks --no-build-cache --console=plain --no-daemon
.\gradlew.bat --offline --dependency-verification=strict test --tests 'local.plaincraft.greatwall.terrain.OracleLaneTest' --tests 'local.plaincraft.greatwall.planning.PlannerSchedulerTest' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

第一条在修生产代码之前退出 1：旧实现实际调用 3 次工厂，新协议要求 2 次。不是删除共享计算测试，保留 100 caller 同一对象断言，只按新授权调整生命周期计数。第二条退出 0，23s，**23/23，2 suites，0 failure/error/skipped**。

| 判据 | 预期 | 实际 |
|---|---|---|
| 每 lane 一次、跨 job 复用 | ready 后 5 个请求同身份，关服前零 close | PASS，owner 校验 fixture |
| 不跨 session/lane | 三 producer 身份不同 | PASS，生命周期 fixture；原生 seed/settings 下一批验证 |
| 初始化延迟不阻塞 startup | 两 factory latch 被扣住，pump 仍推进，无 tick | PASS，实际 scheduler 定向测试 |
| 取消与排空分离 | 取消 view 不取消保留请求；constructor 未返回不得 drain | PASS，反例 latch |
| 锁/线程守卫、容量/异常 | 非法入口被拒；64 预留，失败有显式错误 | PASS，fixture；不是实际 C2ME 生产锁证书 |
| 原生构造守卫、真实稳态 ≤40ms | 三 CPU profile 新证书 | NOT_RUN，本批未做 |

证据：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\lane-native-20260930\before\` 和 `lifecycle\`，包含原始日志、JUnit XML、退出凭证。SOURCE 为本批父提交加收录于同批的源码 diff；不声称父提交未修改代码直接通过。

## 3. 批次二：原生构造守卫与稳态分栏

输入 `8d55761` 加同批收录源码。修改文件根路径仍为 `D:\云游四海-Mod源码\plaincraft-great-wall\`：

- `src/main/java/local/plaincraft/greatwall/terrain/CpuTerrainOracle.java`。
- `src/gametest/java/local/plaincraft/greatwall/gametest/OracleGameTests.java`。
- `src/serverSmoke/java/local/plaincraft/greatwall/smoke/OracleProfileFixture.java`。
- `tools/Test-S1OracleProfiles.ps1`。
- `docs/S1-S5实施规格/S1-城墙本体.md`，只同步本次获批的初始化/稳态差分。

CPU 构造第一步检查 lane 初始化上下文；sampling action 内也不能再次构造。同步诊断的默认拒绝使 native oracle 无法通过原先 computeGeometryNow 旁路。原生计时 `randomStateNanos` 仍保留，移出稳态 `over40msPrimitives`；NoiseChunk/point/cell/block/coarse/biome 没有移出，新增实际 advance 批次最大值和超限计数。immutable UUID 仅用于诊断，绝不参与规划 hash。

原有 4 个 Oracle GameTest 名称、4 seeds×128 columns、128 列坐标域、三格 support、屋顶水/上溢水、coarse 对齐/阈值与 foreign/closed/未完成拒绝全部保留。执行迁移到 OracleLane，而不是让测试在 server thread 违反新协议。唯一恶意跨 lane cursor 传递是测试拒绝保护，生产 futures 不发布 mutable cursor/oracle。

实际先执行全量 `test assemble compileServerSmokeJava runGameTest`；诊断参数错误使用完整类名和不存在的 NoiseCursorGameTests，`processGametestResources` 拒绝，整体 exit 1，**不能写整体 PASS**。此前 `test` 已完成且 57 份新 XML 核对 **305/305，0 failure/error/skipped**；CPU/profile/GameTest Java 编译、remapJar 已执行。失败日志原样保留。

随后仅修启动参数，没有更改生产代码/测试域：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runGameTest '-PgreatwallGameTestClasses=OracleGameTests,NoiseBridgeGameTests' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

实际 exit **0**，36s，GameTest **6/6**（XML 六个实际 testcase、0 failure/error/skips）；独立 196608 状态 hash 与历史原生相同，512 列/248 wet/264 dry/三格 support 全同；当前 vanilla 原生 oracle native init 最大 9.937ms，稳态 advance 最大 7.0516ms，primitive+advance 超限 0。只针对本次 vanilla GameTest 输入域，不是 Tectonic/C2ME 性能签收。

证据：`docs/evidence/s1c/lane-native-20260930/native-guard/`。第一次参数失败与第二次成功分开；完整 JUnit XML 在 `junit/`、原生 XML 在 `gametest/results.xml`。

三 profile runner 已加严格证书校验：4 独立 session/8 独立 oracle 身份、每 lane factory/close=1、实际 drain、零初始化哨兵、同 lane 两个 job 复用、advance(64)≤40ms；旧无该协议字段的证书不能充当新协议认证。**本批真实三个 profile 尚 NOT_RUN，下一批执行。**

## 4. 批次三：实际 scheduler 批次与真实 profile 验证入口

输入 `cd5a354` 加本批源码；新增
`D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\NativeSchedulerFixture.java`，
修改 `PlannerScheduler.java`、`OracleProfileFixture.java` 和 `tools/Test-S1OracleProfiles.ps1`。

真实 profile 前置诊断现在包含实际 PlannerScheduler、实际 CpuTerrainOracle 两条 lane、实际 startupPump、至少 route 0/1 的接缝与 hash；固定 150000 列预算、2048 段长、原实际维度 bounds。测试 recipe 的 spawn/axis 是明确的独立诊断输入，不冒充实际新世界出生选址授权。使用隔离 cache，不写世界墙体/owner/T0；没有打开生产 worldgen 接线。

稳态批次仍最多 64 个逻辑原语，达到 25ms 目标后在原语边界 yield；时间只控制暂停，不影响候选顺序、预算降级或返回几何。完整批次包括 value/publish 的成本，单个慢原语不能被 deadline 检查隐藏，仍记录 >40ms。初始化永远在 ready future 阶段，不落入采样计时。实际 native fixture 将检查完整 scheduler 的最大批次和超限计数，不能仅用原语计时替代。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test assemble compileServerSmokeJava runGameTest '-PgreatwallGameTestClasses=OracleGameTests,NoiseBridgeGameTests' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

实际 exit **0**，2m03s，19 tasks 全执行。全量 JUnit **305/305，57 suites**；定向 GameTest **6/6**，XML 对应六个实际名字；无 failure/error/skipped。这是全量 JUnit + 定向 native GameTest + assemble，**不是全量 build / 全 S1B GameTest 矩阵**。证据 `docs/evidence/s1c/lane-native-20260930/scheduler-batches/`。

三个真正普通 DedicatedServer profile 验证在此批提交之后执行，结果另附；尚未运行时不得预写 PASS。

## 5. 收尾自审反例：ready continuation 不能借初始化权限

在 `104f548` 的第一轮三个真实 profile 均通过后，收尾自查发现一条未被良性采样覆盖的非法路径。第一轮证据保留，不用它证明该回调安全性。

1. 旧行为：`OracleLane.initialize()` 在 `INITIALIZING.remove()` 前 `ready.complete`。CompletableFuture 的同步 continuation 可 inline 执行，因此 public ready 回调短暂仍见私有构造上下文。
2. 具体反例：`OracleLaneTest.readinessCallbackCannotBorrowTheInitializationGrant` 用 latch 保证 callback 注册在构造返回前，且真实运行在 completing lane；原代码不能拒绝该 callback 的构造上下文查询。定向测试实际 exit 1，1 项失败，日志/XML 在 `ready-scope-before/`。
3. 最小修订：success/error 两路均先撤销 ThreadLocal 并结束初始化计时，再发布 ready；CPU 构造前消费每 lane 不可续期的唯一 `native-constructions` 许可。ready callback、sampling action、另一线程及第二次 constructor 均不能借用。
4. 不变项：native 算法/seed/settings/fluidPicker/NoiseChunk、25/40ms 采样合同、lane 数量/队列上限、异步提交、实际 drain、所有几何 hash 与用户需求不变。
5. 修后验证：全量 JUnit **309/309、57 suites**，定向 GameTest **6/6**，exit 0，2m06s，19 tasks 全执行，0 failure/error/skips。新增 4 项覆盖成功/失败 ready inline callback、单次许可及模拟 server/worldgen/C2ME 线程拒绝。这不是生产 worldgen 锁认证。证据在 `docs/evidence/s1c/lane-native-20260930/ready-scope-after/`。真实三 profile 必须在修后源码上重验，不继承第一轮安全结论。

新增/修改生产文件是 `OracleLane.java`、`CpuTerrainOracle.java`；测试为 `OracleLaneTest.java`。单测命令仅把 BEFORE 的 `--tests` 设为上述一个反例，AFTER 采用 §4 的全量 JUnit/定向 native 命令。

真实 profile fixture 与 runner 同步增加 `native-constructions=1` 的实测校验，另批提交；第一轮 `104f548` 没有此字段，必须标明是 guard 加固前的观测，不充当最终证书。

## 6. 最终冻结源码三 profile 复验

在 ready 权限修复后的 `6026605` 上重新执行一次三 profile，不沿用 `104f548` 的初轮认证：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\tools\Test-S1OracleProfiles.ps1 -Profiles vanilla,tectonic,tectonic-c2me -ExpectedMaxY 320
```

各 run 的 inputs/execution/原始 stdout、stderr、latest/debug、GC/safepoint、证书及 SHA 清单均保留。三次内部 Gradle 都是 `--offline --dependency-verification=strict runServerSmoke ...`；三个 runner 结果 **PASS_NATIVE_ORACLE、exit 0、timedOut=false、laneProtocolPassed=true**。没有反复抽取幸运计时；初轮后重验的原因是明确的权限反例及源码修复。

| Profile / 证据末尾 ID | 全列/逐状态 | 一次初始化最大 ms | oracle advance(64) 最大 ms | 实际 scheduler 批次最大 ms / 计数 | 稳态超限 |
|---|---|---:|---:|---|---|
| vanilla / b797f27f | 512 / 196608，248 wet + 264 dry | 23.0376 | 6.0213 | 7.8147 / 12717 | 0 |
| tectonic / 93cb5fa6 | 512 / 196608，319 wet + 193 dry | 164.0400 | 15.0642 | 24.5981 / 13832 | 0 |
| tectonic-c2me / 7b2bdc7d | 512 / 196608，319 wet + 193 dry | 2285.9866 | 3.2931 | 6.7015 / 12232 | 0 |

初始化最大值取认证的全部 10 条 lane（4 个私有 seed session×2 + 1 个实际 scheduler session×2）完整 `initialization-nanos` 的最大值；不是采样批次耗时。证书字段 `actualNativeInitializations=8` 专指 512 列 parity 域的八条 lane，另两条由 `actualScheduler.diagnostics` 单列。本报告显式区分这一计数范围，不把 8 写成整个服务器只有八次 RandomState 构造；独立 native/reference baseline 和 Minecraft 自身构造也不属于这个计数。

本轮所有实际维度是 **minY=-64、maxYExclusive=320**，真实新服务器世界 seed 是 9282026；私有采样 seeds 是 `0,1,9282026,Long.MIN_VALUE`。不是四个真实世界实走。Tectonic 使用隔离默认配置，不复制整包 max_y=2048。锁定 C2ME JAR 与**启动前复制的真实 c2me.toml 输入**的 SHA 与旧证据一致，DFC 未关闭；不声称运行后配置文件字节保持相同。

收尾核验发现：C2ME 隔离配置从输入 SHA `070398f4258ac299023e9db495af81c46d974f00d3fd5263d3d337c432cd6188` 变为运行后 SHA `3d2611f14764da2667ca39c2f6c0a63ae33ba351756f4061ac998b72f94e6114`，真实实例原件仍是前者，未修改。`s1c-oracle-tectonic-c2me-20260930-7b2bdc7d/latest.log:116` 起明示清除未用客户端/OpenCL/nativeAcceleration 配置；精确运行 JAR 内 base 模块 SHA 为 `e04c6cbc4f7bed2b514715f48bb725b894f69e1f890866b980aabc9dff0c6efd`，核对相同内包的 `C2ME-ConfigSystem-runtime.txt` 可见 `flushConfig→purgeUnusedRecursively→save`。输入/运行后 TOML、差分及证据核验器误把输入当运行后恒等式的失败均保存在 `lane-native-20260930/audit/`。DFC 项仍是 `useDensityFunctionCompiler="default"`，实际变换 constructor 仍调用编译 hook；这是 CPU profile 的配置自保存，不是关闭 DFC，也不是整包/OpenCL 授权。不主张所有配置语义与整包运行等价。

### 新合同逐项核对

| 判据 | 预期 | 本轮实际与范围 |
|---|---|---|
| 初始化在锁外专用 lane future | 不在 main/server/worldgen 同步构造/等待 | PASS，原生专用 lane；各实际 lane 初始化/锁哨兵零命中；非法锁/线程有反例测试 |
| 每 lane 一次、跨 job 复用 | factory/native-constructions/close 各 1，同 lane 身份不变 | PASS，每 profile 的 10 条 lane 均各 1；parity lane0 两个 64 列 job 同身份，lane1 不同身份 |
| session/input 隔离 | 新 seed/settings 不误用旧状态 | PASS，4 私有 seed session 的 8 UUID 全异、独立 native 对照相同；GameTest 同 seed 的 flooded/roof settings 差异有反例；实际换存档生命周期仍 NOT_RUN |
| 稳态 25ms 目标/40ms 检测 | 原语、advance、实际组合批次均不超 40ms | PASS，仅上述观测输入域，全部 primitive/advance/scheduler 超限 0，没有移出 NoiseChunk/cell/biome |
| startup 不等初始化或正常 tick | held init 时 pump 持续；调用快速返回 | PASS，latch 反例 + 真 native scheduler 从未调用 tick；startupPump 调用分别 0.3562/0.2906/0.5309ms，冷构造最大约 2.286s |
| 取消/异常/真实 drain | 公共取消不是生产者停止，不重试或静默换输入 | PASS，13 项 lane JUnit + 实际 10 条 lane 的 owner close/drain |
| 没有 Future.get/join、同步 getChunk、possibleBiomes | 生产新路径方法引用零命中 | PASS，三个核心类实际编译字节码扫描 0；诊断 get 仅在明确 test worker；不是未接线生产 worldgen 的运行认证 |

actual scheduler 只要求 route 0/1 完成、接缝一致，随后主动 close 以检验 producer drain。它的 prewarm plan 0..3 尚未要求全部 ready，关闭造成 `startup-failed=1` 是源码所述的取消路径，**不认证完整 prewarm ready 或真实 prepareLevels**；不能忽略这个字段再写“生产启动全部成功”。

本轮真实 C2ME 变换后原始 class 及 `javap` 新归档：
`docs/evidence/s1c/lane-native-20260930/audit/RandomState-runtime-6026605.class`、
`RandomState-runtime-6026605.txt:162,250,430`。仍是构造器返回前同步 `c2me-opts-dfc$postCreate` 和 `BytecodeGen.finalizeCompilation`。**Tectonic 历史约 122ms、本轮最大 164.040ms 的内部阶段分解仍未查明**，没有因 C2ME 证据齐全就套用相同原因。

### 最终证据索引

根目录 `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\`：

- `s1c-oracle-vanilla-20260930-b797f27f/`。
- `s1c-oracle-tectonic-20260930-93cb5fa6/`。
- `s1c-oracle-tectonic-c2me-20260930-7b2bdc7d/`。
- `lane-native-20260930/ready-scope-after/`：309 JUnit、6 GameTest、编译/assemble 的最后回归。
- `lane-native-20260930/before/`、`ready-scope-before/`：两种真实红例，未删除或 Disabled。
- `lane-native-20260930/audit/`：字节码红线扫描、实际 C2ME 构造证据及汇总核验。

### 归档独立核验

收尾再次读归档文件而非缓存测试摘要：每 profile 的 10 个原始证据 SHA、221 个源码/构建/工具输入 SHA、实际 staged JAR、启动前/运行后配置分别核对；57 份 JUnit XML 的 309 个实际节点及全部时间戳落在原命令运行窗口，GameTest 的 6 个实际名字与运行窗口一致，failure/error/skipped 全为 0。核验脚本实际 **exit 0**，输出 `audit/final-audit-6026605.json` 与 `audit/final-sha256-6026605.json`。这是**旧运行凭证的独立核验，不是再次运行 Gradle / 三 profile**。

入库前另核对清单内 **113/113** 个文件：磁盘 SHA 与清单相同，`git hash-object --no-filters` 与 `git rev-parse :<path>` 的暂存 blob 相同，原始日志/二进制证据已实际收录，不只是存在于磁盘。`git diff --cached --check` 退出 0；暂存区源码/工具/构建路径变更为 0。

最后全量 JUnit/定向 GameTest 的 `execution.json` 记录父 HEAD `104f548`，不能理解成未修改的 `104f548` 已通过新保护测试；`ready-scope-after/source-inputs.json` 收录六个实际工作区输入 SHA，现与最终源码逐字节相同。生产/main/client/test/gametest/构建文件在保护修复提交 `71fbc57` 到 `6026605` 无变更；三个最终 profile 则有干净 `6026605` 和完整 221 文件输入清单。本次归档提交只改报告/证据，不改生产源码。

核验器自身的两类反例也保留：`config-recheck-before-correction.json` 证明不能把复制前的 config SHA 当运行后恒等式；`timestamp-recheck-before-correction.json` 证明 PowerShell 把 JSON ISO 日期解成 UTC `DateTime` 后，再隐式转字符串解析会丢失 UTC kind/小数秒，产生错误的 +08:00 比较。修的是取证工具，未修改测试 XML/运行凭证/生产源码或性能阈值。

仅重复证据核验的命令：

```powershell
.\docs\evidence\s1c\lane-native-20260930\audit\Verify-FinalEvidence.ps1 -WorkspaceRoot 'D:\云游四海-Mod源码\plaincraft-great-wall'
```

生产变更文件完整路径：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\OracleLane.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\NaturalTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\CpuTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\PlannerScheduler.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\terrain\OracleLaneTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\terrain\AnalyticTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\PlannerSchedulerTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\OracleGameTests.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\OracleProfileFixture.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\NativeSchedulerFixture.java
D:\云游四海-Mod源码\plaincraft-great-wall\tools\Test-S1OracleProfiles.ps1
D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\S1-城墙本体.md
```

报告文件为本文件、`docs/S1C.md`、`docs/S1C-1C-1.md` 和历史阻塞报告的最新状态说明。新增归档核验工具完整路径是 `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\lane-native-20260930\audit\Verify-FinalEvidence.ps1`，不接入生产或构建任务。核心提交依次 `8d55761`、`cd5a354`、`104f548`、`71fbc57`、`6026605`，各批已附命令、源输入、退出码和失败/成功证据。

## 7. 未验证与保持边界

生产墙生成/重进/不漏段/每 chunk 成本、6000 高度、非默认 minY、Golden、真实玩家实走、S5/24h：**NOT_RUN**。

Tan's Huge Trees profile：**BLOCKED**，树干保护不等于完整树写集授权。

本次前置协议通过，不是 S1C 完成或生产接线授权。后续仍需 WorldBootstrap/新世界生命周期、生产 WorldServices 与锁前 gates、PREPARE/CAPTURE/耐久 T0/APPLY 的真实施工、完整树写集抑制及真实世界重进不漏段验收。

S0 百轮矩阵本批 **NOT_RUN**，S0 实验路径未修改，历史 8/8 不当成本轮新测。全阶段 `build`、全 S1B GameTest 矩阵本批 **NOT_RUN**，只执行全量 JUnit + 指定 6 个原生 GameTest + assemble；不使用“全量单进程全绿”措辞。依赖锁/校验清单/构建版本未修改，gradle.properties 无代理设置；未部署真实 mods 或打开真实存档。
