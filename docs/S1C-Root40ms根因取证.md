# Root 40ms 超限：单JVM串行取证

状态：测试工具准备完成，实际四seed复测NOT_RUN。日期2026-10-01。
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
