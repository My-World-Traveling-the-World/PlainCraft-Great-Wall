# Root 40ms 超限：单JVM串行取证

状态：**四个固定seed串行8G取证完成，结论A（限定本轮四例）；不是S1C签收。** 日期2026-10-01。
生产基线b440e33，生产源码/40ms门槛/水文规则不改，接线OFF，Tan's BLOCKED。

## 冻结输入与环境

固定seed=4,8,9,21，均为上轮超限样本；每个独立新世界、单个测试JVM串行、-Xmx8G。
测试只运行原RootSearch，不跑水文、站点、T0或APPLY，不修改实际spawn或出生域。
用户关闭了真实客户端；确认后只剩一个约110MB Gradle守护进程，不代用户关闭它，
逐秒记录其PID/CPU计数/工作集及所有Java进程角色，**不保存外部进程命令行或登录参数**。
出现额外客户端/服务端/其他Java活动时标污染，不能把污染运行称为单JVM证据。
Idle Gradle JVM仍存在的事实保留；“单JVM”只指不并行运行另一个测试或游戏负载。

## 取证方式

- 原RootSearch.advance(...,64)不插入Mixin、不替换算法。测试调用者记录包围每次原调用的
  起止System.nanoTime、线程CPU差值，原over40msBatches计数为判定真值。
- 外层区间是原内部区间的包围窗口，不假称可见私有started局部变量；
  外层>40但原计数没增加的批次单列，不算原采样超限。
- 预分配primitive ledger最多200万批次、64MB；原advance内无逐批文件IO/对象日志。
  记录容量满明确失败、不丢批次。二进制及逐批CSVgzip在root完成后写入/派生。
- native RandomState初始化发生在ledger前，仍每session每lane一次私有初始化，
  初始native构造计时独立保留，不纳入稳态40ms；不共享live RandomState。
- 两个JFR自定义时钟锚点把单调时钟映射至JFR事件时间，记录映射不确定度；
  GCPhasePause与配对safepoint作为暂停，区间合并后求交，不累加嵌套暂停。
  GarbageCollection事件全周期只供上下文，**不作为G1并发周期全停顿**。
- profile JFR栈采样、原CpuTerrainOracle primitive timing与线程CPU用于区分真实计算；
  “栈在噪声里”但CPU低/GC重叠不能直接判C。
- A/B/C不能由原始4G+双JVM数字预判。混合/调度无法归因时保持未查明，不强迫判C。

## 工具验证

JDK21/offline/strict执行：

```powershell
.\gradlew.bat --offline --dependency-verification=strict test --tests local.plaincraft.greatwall.smoke.RootBatchLedgerTest --tests local.plaincraft.greatwall.smoke.RootTimingAnalyzerTest --tests local.plaincraft.greatwall.smoke.SeedSurveyLocationsTest compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon
```

exit0、22秒，11/11 JUnit（账本2、关联器3、坐标6）；证据
`docs/evidence/s1c/root-timing-20261001/analyzer-unit/`。此前8项账本/坐标测试亦有原凭证。
含真实JFR clock anchor读回、暂停区间去重、计数边界与不覆盖原件；
只是工具验证，不是原生root性能验证。

新增/修改均为测试sourceSet及tools，不在src/main/src/client：
RootBatchLedger.java、RootTimingAnalyzer.java、RootHydrologyFixture.java、
RootBatchLedgerTest.java、RootTimingAnalyzerTest.java、Test-RootTimingSerial.ps1。
完整路径根为`D:\云游四海-Mod源码\plaincraft-great-wall\`；
前二者/fixture位于src/serverSmoke/java/local/plaincraft/greatwall/smoke，
两个测试位于src/test/java/local/plaincraft/greatwall/smoke，脚本位于tools。
脚本AST syntaxErrors=0；所有依赖版本未改。

## 执行入口

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\tools\Test-RootTimingSerial.ps1
```

仅严格离线/校验准备本地runtime，此后四次独立JDK21进程按固定顺序串行；
每个测试退出后才启动离线关联器，再进入下一个seed。
不重摸40seed，不调堆/节奏、不更改40ms、不修生产。

## 启动层失败原件

测试源码9645053的第一套`root-timing-serial-20261001-ccf04afd`，
四个固定seed均exit1、MISSING_ANALYSIS；未启动游戏、未进行root采样。
JFR启动原错为中文绝对settings路径被原生参数解析乱码，
`Could not find file 'D:\...root-timing.jfc'`，配置文件真实存在。
原输入/启动参数/退出码/stdout/stderr/SHA全部保留，不将这一轮计入A/B/C或改成seed失败。

最小工具修复仅将同一JFC逐字节复制至每个新runDir，参数改ASCII相对
`settings=root-timing.jfc`；不改JFC事件、seed、Xmx8G、规则、生产代码或仓库路径。
随后按同一四seed再跑一套独立新目录；原件不覆盖。

## 实际四例结果

【已验证】测试源码`28ff579`，批次`root-timing-serial-20261001-233c8614`。
严格离线/严格校验准备运行时exit0、8秒；实际测试单JVM按4、8、9、21串行，
每例-Xmx8G、独立新世界；每个服务端及离线分析器均exit0，无超时。
以下root最大值取原`RootSearch.Progress.maxBatchNanos`，
不以外层计时替换生产计数，也不将初始化混入稳态。

| seed | 旧双JVM/4G root最大ms | 旧超限次数 | 本轮单测试JVM/8G root最大ms | 外层最大ms | 本轮超限次数 | 原root调用批次数 |
|---|---:|---:|---:|---:|---:|---:|
| 4 | 90.1618 | 1 | 13.6467 | 13.6474 | 0 | 839691 |
| 8 | 75.7576 | 2 | 7.1349 | 7.1351 | 0 | 840377 |
| 9 | 155.9408 | 1 | 11.5885 | 11.5888 | 0 | 709025 |
| 21 | 50.5338 | 4 | 12.4711 | 12.5300 | 0 | 841325 |

两轮数值均为【已验证】观察值，旧数据取原40seed归档的phaseSnapshots.rootProgress。
本轮共3230418批；四例原root计数、二进制账本归约、离线分析均为0。
CpuTerrainOracle的over40msPrimitives和over40msAdvances亦四例全0。
四例均ROOT_TIMING_COMPLETE、phase3；没有忽略finish性能检查继续执行。
仅排除了本轮这四例的root性能拒绝，**没有分类它们的segment0水文**。

### 批次起止与GC记录

时间使用原execution.json的UTC；当地时区为Asia/Shanghai（+08:00）。
起止含游戏启动、root观察、正常关服及离线分析，不等同root纯耗时。

| seed | 开始UTC | 结束UTC | 原root扫描列数 | lane初始化ms（独立计时） | 原生诊断耗时秒 |
|---|---|---|---:|---:|---:|
| 4 | 04:02:13.8626873 | 04:03:45.5669619 | 28935 | 15.2926 | 69.1635715 |
| 8 | 04:03:45.8474685 | 04:05:17.2969276 | 28980 | 18.0603 | 69.4109034 |
| 9 | 04:05:17.5658508 | 04:06:37.7116895 | 24372 | 16.5392 | 58.6559440 |
| 21 | 04:06:37.9727398 | 04:08:11.9271527 | 28890 | 15.4264 | 71.3730314 |

四例执行窗口不重叠；首例开始至末例结束约5.97分钟。
每例root-batches.csv.gz逐批记录start/endEpochNanos、wall/cpuNanos、
ownOver40Delta、gcPauseOverlapNanos、safepointOverlapNanos；
原始GC/safepoint文本为root-vm.log，原始事件/栈为root.jfr。
不是只保存四个最大值，更没有用G1并发周期总时长替代暂停时长。

| seed | 全录制GCPhasePause数 | 已配对safepoint数 | 未配对begin数 | root线程栈采样数 |
|---|---:|---:|---:|---:|
| 4 | 186 | 238 | 1 | 3484 |
| 8 | 195 | 245 | 1 | 3520 |
| 9 | 168 | 217 | 1 | 2977 |
| 21 | 195 | 249 | 1 | 3667 |

上述事件数是**全录制窗口**，不能全部算成root期间的暂停。
各有一个未配对SafepointBegin，原因未查明，未将其补成任意时长；
GC/safepoint关联完整性存在该限制。A判定直接依赖原root超限计数为0，
不依赖“未配对事件不存在暂停”这一假设，也不据此声称GC从未发生。
Windows线程CPU计时存在量化误差，某个极短批次的cpuNanos可略大于wallNanos；
本轮不利用该现象作原生CPU因果归因。

### 独立复核及输入变化边界

从四份原结果/退出凭证、environment.jsonl、sha256.json重新读取，
保存`independent-audit.json`，不只照抄summary：

- 251个冻结源码/构建/工具输入文件SHA匹配，sourceMismatches=0；
  每例15个归档文件共60个SHA匹配（含原JFR、账本、CSV、日志、退出凭证）。
- 四例seed、spawn、DimensionBounds、earlyDomain、rootAudit与旧轮逐项相同，
  原root扫描列数分别仍为28935/28980/24372/28890，不改变候选/轴/预算凑绿。
- 四例factory-calls=1、oracle-closes=1、oracleDrained=true，
  正常SERVER_SMOKE_PASS；该标记只表示诊断服务器正常停止，不表示真实墙通过。
- 79/80/69/82次环境采样均未发现其他游戏/测试Java负载。
  唯一闲置Gradle daemon PID35264仍在；各例观察窗口CPU增量约
  0.125/0.09375/0.140625/0.21875秒，原件保留，不宣称完全没有其他JVM。
  逐秒采样不证明所有亚秒进程绝不存在，但与串行启动/退出凭证相符。

【已验证】`git diff --exit-code b440e33 -- src/main src/client` exit0，
生产Jar SHA仍为
`dfe146262610ccb7bb4974c500f3cb5e37b43428fe6302e4b5d63d7caabe6a42`；
Fabric API Jar SHA两轮相同。测试server-smoke Jar因新增取证类而改变。
**两轮terrainHash并不相同，不能写“全部综合指纹未变”。**
captureProduction会对全部加载mod记录actual-source-sha256：
`GenerationResourceFingerprint.java:84`、`:87`；
ProductionInputs.java:25将这些值归入terrainHash。
CpuTerrainOracle.java:85仍以原settings/noises/seed创建RandomState；
fingerprint只保存为身份/一致性检查，不替代噪声seed（同文件:81、:104、:160；
OracleLane.java:127）。四例实际候选审计/坐标输入相同提供输出旁证，
但不能声称本任务已逐条独立重导出全部worldgen注册表。

核对命令初次误用了PowerShell只读变量`$PID`，产生只读赋值错误，
那次foreignJvmObservations统计无效，未用于结论；随后改为局部`$testPid`
并启用ErrorActionPreference=Stop，重新完整读取四份原件后才保存独立audit。
这只是核对命令缺陷，不是游戏失败，也不重写已结束运行的execution.json。

## A/B/C结论与下一步边界

**A【已验证观察】：四个固定旧超限seed在单测试JVM/-Xmx8G下超限全部消失。**
**【推断】前轮双JVM/4G环境的竞争、GC或调度干扰是优先解释；未证明唯一成因。**
本轮同时改变并行度与最大堆，还增加了JFR/账本观测，且两轮系统负载未完全控制，
因此不能分别量化“CPU竞争贡献多少/4G堆GC贡献多少”，不能称已根治原生oracle。
不判B或C：本轮无原root超限批次可供这两类归因；也不能替旧轮凭空补出暂停重叠。

旧26/40=65%仍是**旧环境的真实观察**，原件及历史计数保留；
作为“默认生产root有65%原生采样慢”的外推**撤销**，不能拿它作为修改oracle依据。
该结论不反向证明其余22个旧超限seed在串行环境必通过，
不把四例0/4转写成总体0/40；重摸固定0..39仍NOT_RUN，等待用户决定。

| 验收/边界 | 本轮状态 | 证据或原因 |
|---|---|---|
| 四个固定seed、单测试JVM、8G | 已验证 | 输入/时间窗口/逐秒环境/退出凭证 |
| 原root40ms不放宽、原预算/水文不改 | 已验证 | 生产diff为空、原Jar相同、原finish计数仍执行 |
| 每批起止/CPU及GC/safepoint记录 | 已验证；关联有一未配对begin/例 | 原账本+逐批CSV+JFR+VM日志 |
| 正常关服及oracle关闭/drain | 四例已验证 | 原row及execution、latest.log |
| GC还是并行竞争的单独因果贡献 | 未查明 | 本轮同时改变两项环境条件 |
| 重新40seed水文/覆盖比例 | NOT_RUN | 本任务只观察四例root，未经后续授权 |
| root九列与首段可认证性闭环 | 未修复 | 先前覆盖缺口结论不因A消失 |
| Tectonic独立122ms内部分解 | 未查明 | 本轮仅vanilla，不能借A代其归因 |
| Tectonic/C2ME生产墙、重进不漏段、每chunk成本 | NOT_RUN | 本轮无墙管线/T0/APPLY |
| 6000高度、非默认minY、Golden、人工实走、S5/24小时 | NOT_RUN | 不属于本轮取证 |
| Tan's Huge Trees | BLOCKED | 不删除模组、不授权树干之外的整树写集 |
| 全量build/JUnit/GameTest、S0百轮/S1B矩阵 | 本轮NOT_RUN | 仅严格离线runtime准备；工具11项历史单测已在前节分栏 |

## 证据目录与停止点

失败启动原件已由`28ff579`收录，实际运行sourceCommit亦为`28ff579`。
成功取证（不是墙签收）完整目录：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\root-timing-serial-20261001-233c8614\`。
入口是inputs.json、summary.json、independent-audit.json；
逐seed为execution.json、seed-survey-result.json、root-timing.json、
root-timing-analysis.json、root-batches.bin.gz、root-batches.csv.gz、
root.jfr、root-vm.log、environment.jsonl、stdout/stderr、logs/latest.log、sha256.json。
运行时输入另归档至
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\runtime\root-timing-serial-20261001-233c8614-runtime-inputs.json`。
没有debug.log的实际运行不伪造debug.log。

复现原四例命令见“执行入口”；每次生成新隔离目录，不覆盖本轮。
离线分析可对**原件副本**重跑（分析器会写派生文件，不直接覆盖已归档原件）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$runtime = Get-Content -LiteralPath '<本批证据根>\runtime.json' -Raw -Encoding UTF8 | ConvertFrom-Json
& "$env:JAVA_HOME\bin\java.exe" -Xmx1G -cp (($runtime.classpath + $runtime.mods) -join ';') `
    local.plaincraft.greatwall.smoke.RootTimingAnalyzer '<单seed原件副本目录>'
```

全部测试/分析进程已结束。此处停止，不启动40seed重摸、不修生产，
不改变40ms门槛、水文规则、生产OFF或Tan's BLOCKED；等待用户决定下一步。
