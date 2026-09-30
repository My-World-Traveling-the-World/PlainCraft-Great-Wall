# S1C 阻塞：私有 RandomState 冷初始化

状态：**B-S1C-01 / BLOCKED，生产接线 OFF，等待初始化协议审查决策**。

日期：2026-09-30，Asia/Shanghai。输入 HEAD `e9edcf6` 加证据 `inputs.json` 所列工作区 SHA；本报告所属提交收录最终诊断源码。S1B 签收不撤销，但本报告不是 S1C 签收。

## 1. 冻结合同与真实反例

1. **旧合同**：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\S1-城墙本体.md:1082`，私有完整 NoiseChunk 点级切片必须与原生逐状态一致；“构造/单cell缓存初始化也单独计时，>40ms即阻塞性能签收”。当前 CpuTerrainOracle 公共构造器内包括私有 RandomState 初始化，不擅自把这一部分排除。
2. **具体反例**：三个真正普通 DedicatedServer、三个独立新世界目录、实际 Tectonic/C2ME JAR、全部相同四个 seed×128 列。各组完整 196608 状态及 ColumnSample 均与独立 getBaseColumn 对照相同；但 Tectonic 最大 RandomState 初始化 121.7527ms、4 次超限；C2ME 最大 1851.2769ms、4 次超限。NoiseChunk/slice/cell/block/biome 的本轮最大值均未越 40ms，超限集中在 RandomState 初始化。
3. **最小修订**：**未修改任何性能公式或生产实现**。仅添加测试诊断、原因证据和阻塞状态。保留 40ms 谓词及失败结果；没有删除 C2ME 模块，没有旧结果覆盖新红例。
4. **明确不变项**：真实 300 格日程、PrefixSummaryIndex、列预算、几何 blob、S1B 出口决议/owner 权限、贴地窗口、PREPARE/CAPTURE/APPLY、实际 fluidPicker、完整 noise 高度、三条红线、依赖版本、真实 mods/存档均未改变。未复制世界实例的 RandomState 给规划线程，未启用生产墙。
5. **修复前后及残余范围**：这里没有“修复后转绿”。正确性为限定输入域 PASS，性能为明确 BLOCKED。全量 JUnit 和编译/assemble 回归 294/294、exit 0 不能替代性能验收。实际 6000、生产生成/重进、植被、同 JVM 生命周期、每 chunk 生产成本均 NOT_RUN。

这不是已经发生 watchdog 崩服、锁内等待或丢墙的证明；原生诊断服务器都正常退出。它是已实测的性能合同冲突，且涉及生产授权的前置门槛。

## 2. 可核定位

下列源码路径均以 `D:\云游四海-Mod源码\plaincraft-great-wall\` 为根；行号基于本报告所在源码，后续修改需重新定位。

| 位置 | 证据等级 | 事实 |
|---|---|---|
| `src/main/java/local/plaincraft/greatwall/terrain/CpuTerrainOracle.java:48` | FACT-SOURCE | 冻结工厂每次创建一个新的 oracle |
| 同文件 `:80` / `:82` | FACT-SOURCE | 构造器调用完整 native RandomState.create，实际时间进入 over40 观测 |
| 同文件 `:239` | FACT-SOURCE | `nanos>40_000_000` 加计数，没有排除初始化 |
| `src/main/java/local/plaincraft/greatwall/planning/PlannerScheduler.java:359` / `:363` / `:374` | FACT-SOURCE | 当前调度器在 job 首个批次创建 oracle，完成后 close；不是已经实现“一次 lane 初始化、跨 job 借用” |
| `docs/evidence/s1c/native-oracle-profiles-20260930/abi/runtime-c2me-RandomState.txt:162` | FACT-ABI | 真实运行变换后的构造器返回前进入 `c2me-opts-dfc$postCreate` |
| 同证据 `:250` / `:430` | FACT-ABI | 该钩子同步生成各密度函数并调用 `BytecodeGen.finalizeCompilation`，返回后才结束 constructor |
| C2ME 原日志 `latest.log:270` 到 `:293` | FACT-RUN | 私有 worker 上多次原生 DFC 编译日志约 1.28..1.73s |
| C2ME `oracle-profile.json` | FACT-RUN | 独立 per-seed wall/CPU 初始化计时、完整状态 hash、超限计数 |

【推理】照当前工厂直接接进 production PlannerScheduler，会把这样的未切片冷构造带入 job 首批；外层 `advance(64)` 不能打断构造器内部的 DFC 编译。**没有启动生产 PlannerScheduler 来把此推理伪装成实际生产性能测量**。

【未查明】Tectonic 不含 C2ME DFC 的 121.753ms 如何在 native router wiring / sampler /其它构造子阶段分配；本轮未加其内部子阶段探针。不能因为 C2ME 钩子明确，就把 Tectonic 的全部开销也归因于 DFC。

## 3. 实际结果

| Profile / 新目录 runId | 正确性 | 私有 RandomState 最大 / 超限数 | 原生 Gradle / 外部 runner |
|---|---|---|---|
| `s1c-oracle-vanilla-20260930-a1485c7f` | 512 列 / 196608 态 PASS | 16.3931ms / 0 | 0 / 接受此诊断输入 |
| `s1c-oracle-tectonic-20260930-b7119571` | 512 列 / 196608 态 PASS | 121.7527ms / 4 | 0 / 明确抛阻塞异常 |
| `s1c-oracle-tectonic-c2me-20260930-60497bbb` | 512 列 / 196608 态 PASS | 1851.2769ms / 4 | 0 / 明确抛阻塞异常 |

三个维度均为 `[-64,320)`。每个私有采样矩阵的 seed 是 `0,1,9282026,Long.MIN_VALUE`；实际服务器世界 seed=9282026。没有把它们写成四个真实走墙世界。Tectonic/C2ME hash 同为 `9a874cbb44033887b5cba8456fbe57b7996ae72f4b63420254e68920297e1b10`，vanilla 为 `c86a7f534f6e886b65fbe8dd1a09f9f3dc5a1a28236a0283377e93e39113f525`。

C2ME oracle 的 per-seed native wall time / 构造全过程同线程 CPU time：

| seed | Native RandomState wall ms | Oracle constructor CPU ms |
|---|---|---|
| 0 | 1657.7559 | 1656.250 |
| 1 | 1851.2769 | 1828.125 |
| 9282026 | 1822.6777 | 1828.125 |
| Long.MIN_VALUE | 1667.2583 | 1640.625 |

CPU 计时是平台量化旁证，不能相减后宣称精确 GC 成本，也不是 DFC 每行耗时分析。它支持“该现象不是只有 worker 被挂起两秒”的判断；原始 GC/safepoint 日志一并保留。

服务器 smoke 正常退出标记 **只证明启动、tick、正常 shutdown**。外部 runner 必须同时检查 oracle JSON 的正确性、完整列/状态/seed 数、hash 和性能谓词；因此 `server-smoke-passed.txt` / Gradle exit 0 不代表后两组认证通过。

## 4. 变更、构建与证据

本批实际新增/修改的执行文件（绝对路径）：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\OracleProfileFixture.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\ServerSmoke.java
D:\云游四海-Mod源码\plaincraft-great-wall\gradle\smoke-tests.gradle
D:\云游四海-Mod源码\plaincraft-great-wall\tools\Stage-SmokeProfile.ps1
D:\云游四海-Mod源码\plaincraft-great-wall\tools\Test-S1OracleProfiles.ps1
```

文档变更：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-1C-1.md
D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-阻塞-RandomState初始化.md
D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C.md
```

raw 日志、证书、inputs/execution/sha256 存在：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\s1c-oracle-vanilla-20260930-a1485c7f\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\s1c-oracle-tectonic-20260930-b7119571\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\s1c-oracle-tectonic-c2me-20260930-60497bbb\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\native-oracle-profiles-20260930\
```

锁定输入：Tectonic 3.0.26、Lithostitched 1.8.0+beta6、C2ME 0.4.0-alpha.0.27+1.21.1，精确 SHA 在 staged-inputs.json。C2ME SHA `85de278c4acfddbaa748c1e72facaa7e13901f3980e5deb2c65ab7064fc8c460`，配置 SHA `070398f4258ac299023e9db495af81c46d974f00d3fd5263d3d337c432cd6188`。没有改源实例文件，没有向源 mods 部署。

实际严格离线回归：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test assemble compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon
```

exitCode=0，`BUILD SUCCESSFUL in 1m 41s`，13 个任务全部执行；294 JUnit / 56 suites / 0 failure / 0 error / 0 skipped。已核对新鲜 XML 294 个 testcase 节点与属性计数。**本命令不等于 `build`**；GameTest、本阶段完整签收 build 均 NOT_RUN，保留该区别。PowerShell runner 的最终语法检查为 0 error。

## 5. 待审查决策，不是已实施方案

当前无法在冻结构造门槛下认证两个必要 profile，因此 **不继续生产 gate/bootstrap 接线，不进入 1C-2/1C-3**。两种解决方向必须先审查，不能从旧的“碰撞/支撑最小修订授权”外推获得性能合同变更授权。

**提请审查的最小方向：把一次性 lane-native 初始化与25ms采样批次拆成两个明确协议。**

1. 每个 session 的每条 oracle lane 只初始化一次自己的完整 native RandomState，仍保留实际 C2ME DFC、真实 fluidPicker、同 seed/settings、私有 NoiseChunk 与同一算法。
2. 初始化作为锁外显式 future 阶段，有独立耗时/容量/取消/异常/drain 证据；调用者只依赖 future，不 worldgen `join/get`，startup 不依赖正常 tick。
3. 初始化完成后，按同 lane 借用 oracle 到多个 job；只在 lane shutdown drain 时关闭，共享范围不越 lane，不复制或共享 live 世界 RandomState。
4. 稳态 slices/cell/block/biome 和真实 `advance(64)` 批次仍遵守原 25ms目标/40ms检测；冷初始化不能偷偷计为达标采样批次。
5. 冻结构造验收须明确新增“一次性 native 初始化独立分栏”的适用边界及超时值；当前没有擅自制定数值、实现例外或签收。真实 per-chunk 性能仍须后续 production 运行验证。

若审查要求所有初始化仍必须 <=40ms，则需要另审增量构造协议，并证明 native wiring/DFC 的暂停恢复和完整等价；现有 NoiseChunk slice bridge 不能解决 RandomState 构造内部的问题。**不通过禁用模块、换算法、提高墙预算、降低画面质量或只统计热态绕过。**

## 6. 未运行与停工状态

| 验收项 | 当前状态 |
|---|---|
| 原版 / Tectonic / C2ME oracle 逐状态 + sample 归约 | 已验证，仅 4私有seed×128列×384实际跨度 |
| 两个第三方 CPU profile 初始化性能 | BLOCKED，保留两个红例 |
| 当前全量 JUnit、compile、assemble | 已验证，294/294、exit 0，不是 S1C 完成 |
| 完整生产 recipe / manifest 初始化前 native 授权 | NOT_RUN，前批仅磁盘协议测试 |
| FEATURES 锁前生产计划 + vegetation lease / 前后 LIGHT | NOT_RUN，生产入口 OFF |
| 真实墙生成、退出/重进、无漏段、每 chunk 成本 | NOT_RUN |
| 静态状态跨同 JVM A→B→A / 生产 drain | NOT_RUN |
| margin32 完整树写集抑制 / TYG 首写取消 | NOT_RUN |
| 6000高世界 / 非默认minY lava阈值 / Golden | NOT_RUN |
| S0 百轮矩阵 | NOT_RUN，历史8/8不冒充本轮；本批未修改S0屏障/gate/矩阵用例，smoke新增opt-in分支不外推为S0运行通过 |
| S5/24小时/真实玩家行走 | NOT_RUN |
| Tan's Huge Trees | BLOCKED，未删除mod、未解锁profile |

所有本轮进程均已结束，不保留后台持续监控。永久历史/既有 T0 没有清空或重采。未创建 S1C 签收提交，不输出“S1C完成”。
