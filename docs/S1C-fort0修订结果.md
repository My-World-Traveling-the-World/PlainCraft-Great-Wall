# S1C fort0 修订结果

日期：2026-10-02（运行环境本地时间；凭证保留 UTC）。
真实运行基线：`65bea36`；最后源码为 `ddd650d`（全朝向包络）+
`7461c4d`（只读审计工具）。后两项在 `65bea36` 未提交状态测试后原样提交，
凭证的 `dirty=true` 保留，不将历史凭证 sourceCommit 改写为新提交。

## 结论

本次批准的两项修订已实现，但 S1C 仍未签收，生产接线保持 OFF。

- 结构前置筛选：已实现。检查发生在 recipe 冻结前，使用锁外
  `STRUCTURE_REFERENCES`/`STRUCTURE_STARTS` 租约，不等待 FEATURES，不修改构件模板。
- 可替换与可承重分离：已实现。显式自然可替换表不改变
  `foundation_support`；矿石和砾石仍 `support=false`，OWNED_VOID 负空间保护不变。
- seed17 真实运行：仍 `FAIL_BEFORE_APPLY`，`progressVoxels=null`，没有墙体写入证书。
  此次真实运行源码为 `65bea36`；其后 heading-invariant 根筛包络仅完成
  JUnit 验证，最新包络的真实运行为 NOT_RUN，不声称根坐标在该修正后已经重测。

## 根点结果

固定 vanilla seed `17`，原 800/1000/1250/1500 环带、36 角度、确定性排序和原预算未改变。

| 项目 | 结果 |
|---|---|
| 原根候选 `(-185,65,1199)` | 拒绝；`minecraft:village_plains` 真实结构盒相交 |
| 其他结构冲突候选 | `(-107,96,-820)` village_plains；`(374,91,-972)` village_taiga；`(-228,90,-1509)` trail_ruins |
| 新根 | `(206,67,953)`，radiusIndex=1，angleIndex=8 |
| 新根合法性 | `FOUNDATION_ELIGIBLE`、`DRY_AXIS_ELIGIBLE`、5 个朝向 `STRUCTURE_CLEAR` |
| 结构检查范围 | 锁外租约；未知引用 fail-closed；不请求 FEATURES |
| 真实 fort0 包络 | X[176,236)，Y[54,95)，Z[923,983) |

候选和结构盒原始记录见
`docs/evidence/s1c/fort0-revised-foundation-17-20261002-002523/logs/latest.log`
及 `world/greatwall/diagnostics/session-STOPPING.json`。同目录 `root-audit.csv`
导出全部原始候选审计行（含 coarse/footprint/axis 拒绝，不只是结构拒绝）。
结构筛选的 80 条朝向投票为 60 CLEAR、20 冲突；对应 12 个清晰候选和
4 个结构冲突候选。粗筛 128 条有效采样记录中 112 REJECTED、16
SHORTLIST_ELIGIBLE；不据此把无审计行的其他环带位置写成“通过”。

## 新阻塞

真实施工到达 `REAL_WALL_TRAVERSAL_BEGIN`，fort0 的 3600 列中：

- `SUPPORTED=3344`，`PROTECTED=256`；
- 地面入口 190 列中 18 列受保护：`mossy_cobblestone` 16 列、
  `sweet_berry_bush(age=3)` 2 列；
- 只读 T0 审计的全包络首次命中还包括 brown mushroom 40、pumpkin 19、
  sweet berry bush 12、magma block 3、mossy cobblestone 182；
- 未发现结构盒首次命中。

因此失败仍为：
`fort0 ground road has no supported entrance`。

这些状态不属于本次批准的显式矿石/砾石可替换范围。没有把苔石、植物、
南瓜或岩浆块加入白名单，也没有放宽 `ChunkPlanCompiler`、负空间或
`OwnershipGuard`。需要另行决定自然装饰策略；建议优先研究
“自然装饰在 FEATURES 前的有限预留/抑制”，但该方案尚未设计、授权或实现。
T0 只能证明方块和保护元数据，不能证明苔石来自哪个 configured/placed feature；
实际 vanilla feature 来源仍待核实。直接将苔石标记“可替换”也不证明
OWNED_VOID 净空可以获准；不能把这些方块静默归类成矿石。
审计中的 highestNonPlantFeet 也不等于三层认证 groundTopY，当前没有入口坡度
合格证书，不保证解除材料阻塞后就一定进入 APPLY。

可核调用链：`BlockCatalog.java:53` 生成保护分类 →
`ProtectionSnapshot.java:58` 保存 immutable 位置 →
`SnapshotColumnView.java:54` 读取持久 T0 的保护状态 →
`SurfaceProbe.java:35` 拒绝受保护列 →
`Fort.java:66` 对 fort0 无完整入口 fail-closed。均在
`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\`
下；BlockCatalog/SnapshotColumnView 位于 terrain，ProtectionSnapshot/SurfaceProbe
位于 generation，Fort 位于 component。

## 验证

| 验收项 | 结果 |
|---|---|
| 结构冲突根点不冻结 | 已验证；结构盒和候选状态见原始日志 |
| 根点三层 foundation 视图 | 已验证；native parity 与 350 项 JUnit |
| 结构包络 360 heading | 已验证；`RootFootprintGuardTest` |
| 全量 JUnit | 已验证：350/350，65 suites，exit 0 |
| 最新审计工具编译/包络定向测试 | 已验证：`compileServerSmokeJava` + `RootFootprintGuardTest` 1/1，strict offline，exit 0；见 fort0-final-audit-20261002 |
| 真实 seed17 进入 APPLY | 未通过：`FAIL_BEFORE_APPLY` |
| `real-wall-progress.json` voxels > 0 | FAIL/未生成；本轮 `progressVoxels=null`，不得伪造 0 或 PASS |
| 生命周期 drain | 未闭合；STOPPING 仍记录 `flights=8, admitted=8`，作为独立缺陷 `S1C-DRAIN-01`；完整生命周期验收 NOT_RUN |
| 生产接线 | OFF |
| Tan's Huge Trees | BLOCKED |

本轮真实运行凭证：
`docs/evidence/s1c/fort0-revised-foundation-17-20261002-002523/execution.json`

原始 T0 的 `manifest/cache/evidence` 已归档，哈希清单：
`docs/evidence/s1c/fort0-revised-foundation-17-20261002-002523/world-greatwall-sha256.txt`
239 个原始文件全部逐 SHA-256 比对一致，共 2,533,000 字节；仅项目标记的
隔离 fixture 数据，不包含真实存档或 mods。复制工具第一次使用 LiteralPath
通配符未复制文件，随后改为逐子项原生 Copy-Item 并完成上述比对；不将第一次
无复制结果认作归档成功。
`archive-verification.json` 另核对 Git 索引：239 个入库 blob 与归档原始字节
全部一致。Git 遍历深目录时出现 Windows 长路径警告，已以索引清单逐文件
验证无漏件；未修改机器或仓库 longpaths 设置。原始 crash report 的尾部空白
保留，不清洗取证原件；代码/新报告的 whitespace 检查通过。

### 独立批次与失败保留

| 证据目录（均在 docs/evidence/s1c/） | 结果与范围 |
|---|---|
| fort0-revision-unit-20261001-233804 | 18/18 JUnit + 8/8 定向 native；exit0 |
| fort0-candidate-continuation-20261001-235549 | 19/19 root/bootstrap JUnit；exit0 |
| fort0-revised-17-20261001-234748 | seed17 首轮拒绝旧村庄根，但 ROOT_NOT_FOUND；exit1 |
| fort0-foundation-parity-20261002-000422 | native 12/13；512 列测试 tick timeout，exit1；失败保留 |
| fort0-foundation-parity-after-20261002-001004 | OracleGameTests 5/5，exit0；512 列/4 seed，steadyOver40ms=0，maxSteadyAdvance=6.0733ms；不等于 worldgen 验收 |
| fort0-revised-foundation-17-20261002-002523 | 新根 `(206,67,953)`；入口保护失败，exit1；真实墙未通过 |
| fort0-final-junit-20261002-011548 | 350/350 JUnit，65 suites，failure/error/skipped=0，exit0；源码为 65bea36 + 当时未提交包络/审计工具 |
| fort0-final-audit-20261002 | 最新工具编译 + 1/1 包络测试，exit0；最新工具离线审计 exit0，与原 256/18 列结果一致 |

native tick timeout 修复只将该测试的 tickAllowance 提高至 20M：
512 列、4 seed、逐状态对照、原 240 秒 wall-clock deadline 和 40ms
稳态检测不变；没有删断言或少跑输入。原失败保留在 before 目录。
不同批次不能相加写成“当前源码全部 native/GameTest 一次全绿”。

## 改动与提交索引

以下路径均以绝对工程根
`D:\云游四海-Mod源码\plaincraft-great-wall\` 为前缀：

- `dcb82dd`：`src/main/java/local/plaincraft/greatwall/lifecycle/RootStructureScreen.java`，
  `generation/StructureLease.java`，`generation/NativeRootStructureScreen.java`；
  先写无 FEATURES 环依赖图，再实现租约/不可变投票。
- `7b7fd07`：`lifecycle/RootSearch.java`、`WorldBootstrap.java`、`ProductionInputs.java`；
  生产入口采用投票，旧纯 fixture 入口不改变。
- `06fc94b`：`terrain/BlockCatalog.java`、`lifecycle/GenerationResourceFingerprint.java`，
  `src/main/resources/data/plaincraft_great_wall/tags/block/natural_replaceable.json`；
  分类及新世界指纹绑定，native 分类反例。
- `b085e9b`：`lifecycle/RootSearch.java`；原域内继续下一批，不增加预算。
- `3c9a3fe`、`65bea36`：`terrain/CpuTerrainOracle.java`、
  `lifecycle/ProductionInputs.java`、`GenerationResourceFingerprint.java`；
  生产根承重端口改用 `[h-1,h-2,h-3]`，保留旧额外状态视图。
- `ddd650d`：`generation/NativeRootStructureScreen.java` 与
  `src/test/java/local/plaincraft/greatwall/generation/RootFootprintGuardTest.java`，
  覆盖实际首段切线不等于投影轴的全 heading 足印。
- `7461c4d`：`src/serverSmoke/java/local/plaincraft/greatwall/smoke/ProtectionEvidenceAudit.java`；
  有界、标记隔离目录、路径越界拒绝、原 codec 只读审计，无游戏/registry/live chunk 访问。

生产源文件绝对路径清单：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\RootStructureScreen.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\StructureLease.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\NativeRootStructureScreen.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\RootSearch.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\WorldBootstrap.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\ProductionInputs.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\GenerationResourceFingerprint.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\BlockCatalog.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\CpuTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\tags\block\natural_replaceable.json
```

`git diff --stat 89c9fdc HEAD` 对 component/SurfaceProbe/ChunkPlanCompiler/
OwnershipGuard/foundation_support 无差异。根筛、租约、RootSearch/WorldBootstrap/
分类代码定向静态扫描没有 getChunk/join/possibleBiomes 调用；这是指定文件
范围的静态证据，非所有生产路径或 C2ME 的运行哨兵通过证书。

承重层修正的反例是最高三层可承重、第四层为空：旧额外状态视图
`[h-2,h-3,h-4]` 错拒，新增视图认证第一固体及其下两层。旧
`supportEvidence` 和 REFERENCE 读取顺序未改，native 参考仍独立对照两种视图。

## 可复现命令

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict `
  compileServerSmokeJava --no-daemon --console=plain
.\gradlew.bat --offline --dependency-verification=strict `
  test compileServerSmokeJava --no-daemon --console=plain
.\gradlew.bat --offline --dependency-verification=strict `
  runGameTest '-PgreatwallGameTestClasses=OracleGameTests' --no-daemon --console=plain
```

```powershell
# 使用新的隔离 runId；绝不删除或复用旧永久 T0/manifest。
.\gradlew.bat --offline --dependency-verification=strict runServerSmoke `
  '-PsmokeProfile=vanilla' "-PsmokeRunId=fort0review$(Get-Date -Format yyyyMMdd-HHmmss)" `
  '-Ps1cRealWall=true' '-Ps1cSeed=17' '-Ps1cRepresentativeBlocks=100' `
  --no-daemon --console=plain

# 最新只读审计命令；原始 build fixture 尚保留。
$sha=[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((Get-Location).Path))
$key=[Convert]::ToHexString($sha).ToLowerInvariant()
$bridge=Join-Path $env:USERPROFILE ".gradle/caches/plaincraft-great-wall/test-classpath/$key/classpath.jar"
& "$env:JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
  -cp $bridge local.plaincraft.greatwall.smoke.ProtectionEvidenceAudit `
  'build/run/serverSmoke/vanilla/fort0-revised-foundation-17-20261002-002523' 176 923 236 983 87
```

最后整理阶段没有重复启动真实运行；原因是新材料阻塞已有 T0 证据，而现有授权
不允许扩大自然方块白名单。上述命令是复现方法，不代表又运行过一次。

## 后续边界

S1C 未签收，不进入 S2-S5 生产接线。S2-S5 当前骨架的编译状态不等于
规格完成或真实集成完成。

完整 build/全 GameTest、最新包络真实 seed17、vanilla/Tectonic/C2ME
真实墙重进不漏段、成功每 chunk 成本、完整植被抑制、同 JVM 生命周期、
6000 高/非默认 minY/Golden、S0 百轮本次、S5 长压/24h/人工实走/
视觉验收均 NOT_RUN。失败 owner 的约 96.4 秒成本不冒充成功 chunk 成本。
本次没有跑 40 seed、升级依赖、开启正式生产或部署真实 mods。
