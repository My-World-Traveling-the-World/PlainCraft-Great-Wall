# Root 与第0段水文：覆盖核对及40 seed摸底

状态：**40/40取证完成；不是S1C签收；纯几何总体比例受26个root性能拒绝干扰。**
生产基线 `b440e33`；测试工具冻结源码 `cf96116`。生产接线OFF，Tan's BLOCKED。
日期2026-10-01。本任务只增加测试 sourceSet/启动取证工具，不修改生产源码或水文政策。

## A. 谁保证实际第0段干燥

【源码事实】没有前置环节保证第0段实际路线全程干燥，也没有首段认证失败后重选root的闭环。
但“后半段无人检查”不是准确描述：实际路线后来由HydrologyClassifier检查，
它可以拒绝，却不能使已经选定的首段必然可认证/生成。

| 环节 | 实际范围/行为 | 源码索引 |
|---|---|---|
| root轴初筛 | distance=256/512/1024，across=0/-48/+48，九个离散REFERENCE列 | RootSearch.java:25、:157 |
| 接受轴 | invalid=false且wet=0；不读取AnchorField或首段路线 | RootSearch.java:176 |
| 第0段carrier | u长度2048，boundary1 lateral由seed给出[-384,+384]，只检几何包络/朝向 | AnchorField.java:12、:27、:42、:81 |
| 山脊路线 | carrier叠加受限残差；COARSE湿度只是评分项，不是“所有列干燥”证明 | RidgePlanner.java:118、:132 |
| 实际水文 | 第0段全路线加下一RouteShape前128格；REFERENCE/supercover/两岸宽度认证 | HydrologyClassifier.java:24；PlannerScheduler.java:300 |
| 认证失败 | 原HYDROLOGY_UNRESOLVED等错误；无回调至RootSearch重选 | HydrologyClassifier.java:218；PlannerScheduler.java:435 |

上述索引的完整源码根为：
`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\`；
RootSearch在lifecycle/，AnchorField/RidgePlanner/PlannerScheduler在planning/，
HydrologyClassifier在terrain/。行号以本基线为准，生产代码将用完整SHA检查冻结。

九列干燥不等于0..1024连续干燥；1024之后和carrier偏移均不在九列证明中。
2048是轴向u跨度，不保证真实弧长S恰等于2048。
vanilla/1已有失败S=[26172,43232)/256，约102..169格；
该例在前1024格内也失败，所以不能单独归因“后半段没验”或“boundary1甩偏”。

【设计判断】若把root成功理解为“首段可认证且可施工”，当前实现不兑现此承诺。
这是已确认的覆盖/阶段衔接缺口；四十个样本只量化影响，不能替此缺口开脱或改变规则。

## B. 固定输入与观测边界

固定vanilla seed=0..39，每个独立新DedicatedServer、独立目录、真实原版出生点，
从同一原生HEAD/RETURN读取出生探测域和选定坐标；不伪造spawn或早期域。
原ProductionInputs冻结、RootSearch、AnchorField、RidgePlanner、HydrologyClassifier原样调用。
只计算route0及为原128格halo所需的route1，**只分类hydrology0**；
不调用PlannerScheduler.startupPump的0..3预热、不运行height contract/施工/T0/APPLY。
这些是统计运行器端口，不是生产接线或生命周期认证。

分类：

1. ROOT_NOT_FOUND：原root阶段找不到候选/足印/干燥轴。
2. root成功后hydrology0抛HYDROLOGY_UNRESOLVED或ROOT_NOT_FOUND。
3. root成功且hydrology0完整结束。包括原WIDE/SUPPORT_GAP；不保证有1200格陆墙或能施工。
4. OTHER：性能硬门槛、初始化/运行器/route依赖/超时等；原样计数，不能塞入前三类。

总分母固定40；额外列root成功后的条件比例。若OTHER影响阈值判断，给成功率上下界，
不删case、不换seed、不反复重跑到绿。

失败区间记录原始signed S、当前段裁剪S、对应实际轴向u与横向b：

- S独占桶：FIRST_0_1024 / SECOND_1024_2048 / STRADDLES_BOUNDARY / AFTER_2048 / EMPTY。
- u另行分桶，不能把u与S混为同一种“覆盖”。
- “终点附近”仅操作化为u与[1664,2048]相交的重叠标签；384本来是横向偏移幅度，
  该标签不能证明boundary1造成失败，不能加到两个半段计数中冒充互斥第三类。
- 横向超出±48另标；这也不是九个稀疏采样点覆盖全带的证明。
- 没能定位的budget/样本质量错误单列UNLOCATED，不猜crossing。

## 取证工具准备

新增测试专用RootHydrologyFixture与SeedSurveyLocations，原生产目录不变；
仅测试类路径可读取serverSmoke output，以实测坐标统计函数。
JDK21/offline/strict `test --tests local.plaincraft.greatwall.smoke.SeedSurveyLocationsTest
compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon`：
exit0、26秒、6/6 JUnit。证据`docs/evidence/s1c/seed-survey-20261001/setup-unit/`。
覆盖曲线S/u不同、跨桶、signed halo裁剪、段内S>2048、终点标签重叠。
这不是六个seed结果；四十个实际输入仍NOT_RUN。

测试专用出生钩子已编译/remap通过：`compileServerSmokeJava remapServerSmokeJar`
（同JDK21/offline/strict、rerun、no-build-cache），exit0、21秒；
证据`seed-survey-20261001/hook-compile/`。钩子require=1，生产目录没有修改，
原RootProbe与InitialSpawn签名只在serverSmoke副本观察，普通模式只读开关后返回。
运行器固定0..39、最多2个独立JVM；每个独立world/runDir/stdout/退出凭证。
Gradle只准备一次严格离线缓存运行时，后续直接启动该已锁JDK21/原版server/API/模组Jar，
避免40次构建共用输出发生并行冲突。脚本AST syntaxErrors=0。
`OBSERVATION_COMPLETE`只表示该seed取证完成，任何失败分类都不写成墙通过。

## B. 40 seed结果（已验证）

实际批次 `root-hydro-40-20261001-998c68ec`，开始UTC
`2026-10-01T01:49:37.7308067Z`，结束`2026-10-01T02:41:25.8684842Z`；
当地时间09:49:37～10:41:25，总约51.8023分钟。原始时间见inputs/execution.json，不改写时间戳。
每个seed0..39各一次；无挑选、替换、重跑、跳过或修改水文规则。
JDK21，两个独立进程，单进程-Xmx4G；本机8核16线程、约32GB物理内存。
这是本次取证环境，不外推成所有玩家机器的运行环境。

| 原始分类 | 数量/40 | seed清单 | 证据级别 |
|---|---|---|---|
| 1：root ROOT_NOT_FOUND | 3/40（7.5%） | 12,14,16 | 已验证 |
| 2：root完成，segment0 HYDROLOGY_UNRESOLVED | 9/40（22.5%） | 0,1,2,3,5,6,7,15,18 | 已验证 |
| 3：root完成，segment0水文完整结束 | 2/40（5%） | 17,20 | 已验证；不是施工PASS |
| OTHER：root稳态40ms门槛拒绝 | 26/40（65%） | 4,8,9,10,11,13,19,21..39 | 已验证；不是干燥/水文拒绝 |

三个ROOT_NOT_FOUND输入都完成相应候选扫描，各有两个足印通过、十条轴审计，
但DRY_AXIS_ELIGIBLE为0。seed12同时记录了一次44.3999ms超限，实际首错误仍是
找不到候选，而非finish性能门槛；不改它的原始错误优先级。

26个OTHER全是 `RootSearch.finish → UNSUPPORTED_ADAPTER: root steady sampling exceeded 40ms`，
cursor已到phase3，最大批次50.5338～508.1877ms。没有把它们记成ROOT_NOT_FOUND，
也没有忽略finish检查取其内部候选继续跑水文。它们的水文结果是**未验证**。
GC/safepoint日志原样保留；没有逐超限批次的起止时间与CPU归因，
**不能断言每个超限由GC、并行或某个NoiseChunk原语造成**。

观察凭证验证：40个实际seed均等于输入；40个exit0、正常关服、oracle实际关闭/drain、
observationValid=true、sourceInputsUnchanged=true。**exit0是诊断观察完成，不是该seed通过。**

### B.1 失败crossing分布（已验证）

9个第二类样本均成功定位，UNLOCATED=0；源signed arc保留，
分桶只裁剪到当前段，不删掉跨段halo证据。

| 统计方式 | 前0..1024 | 后1024..2048 | 2048之后/当前段仍在 | 跨桶 |
|---|---:|---:|---:|---:|
| 真实弧长S | 3 | 5 | 1 | 0 |
| 轴向u | 3 | 6 | 0 | 0 |

“前0..1024”指纵向位置，**不是前1024格已被root连续认证**。
终点沿向384带相交2/9（seed2、15），与后半段计数重叠，不能相加。
超出root九列的±48横向带5/9（seed0、3、7、15、18）；仍不证明偏移是独立充分原因。

| seed | 原始S区间（格） | 当前段u区间（格） | S桶 | 终点带 |
|---|---|---|---|---|
| 0 | [604.296875,677.43359375) | [598.1015625,670.02734375] | 前半 | 否 |
| 1 | [102.234375,168.875) | [101.984375,168.48046875] | 前半 | 否 |
| 2 | [1712.359375,1772.359375) | [1711.51171875,1771.51171875] | 后半 | 是 |
| 3 | [394.4921875,486.515625) | [387.16796875,477.00390625] | 前半 | 否 |
| 5 | [1135.578125,1180) | [1134.5859375,1178.85546875] | 后半 | 否 |
| 6 | [1067.5546875,1569.41796875) | [1067.00390625,1567.99609375] | 后半 | 否 |
| 7 | [1599.25390625,1656.51953125) | [1587.47265625,1644.51171875] | 后半 | 否 |
| 15 | [2054.46484375,2092.19921875) | [2045.78125,2048]（裁剪） | S>2048 | 是 |
| 18 | [1443.71484375,1557.64453125) | [1437.6328125,1551.51171875] | 后半 | 否 |

seed15的原crossing包含halo，当前段部分的S也可>2048，因为曲线长度不等于u跨度。
这不是“错误创建了第1段”；原route0和route1 canonical二进制一并保存。
双向u/S映射的独立反例单测已见前节，不按S阈值猜u。

### B.2 两个完成样本的确切含义

- seed17：水文完整结束，六个NARROW、一处WIDE；最早海洋终点S=483552/256=1888.875。
- seed20：水文完整结束，三个NARROW，无WIDE；首段真实弧长2086.1640625格。
- 两者均未跑站点/高度/真实T0/支撑落地/施工/APPLY；不能称“陆墙已生成1200格”。
  NARROW墩的后续walkY/drop校验与构件冲突仍可能失败。
- 更没有运行重进、C2ME实际生产锁、真实玩家实走、每chunk成本或S5压测。

## C. 比例与工程结论

| 数字 | 计算及含义 | 分级 |
|---|---|---|
| 本轮严格root+hydrology通过率 | 2/40=5%；所有40输入作为本轮尝试总分母，OTHER并非成功 | 已验证的运行结果 |
| 严格完成root后的水文完成比例 | 2/11≈18.1818%，剩余9个为水文拒绝 | 已验证；受筛选，非总体率 |
| 完整集合的纯几何成功率区间 | [2/40,(2+26)/40]=[5%,70%]；26个水文未知均计入上界 | 推断的确定性界，不是概率置信区间 |
| 默认玩家能看到墙的概率 | 本轮没有施工，也没有随机总体样本 | 未验证 |

按用户分档，**本次实际协议运行的5%落在<20%档，不能据此批准补代表seed签收**。
但将65%的性能拒绝都解释成水文/覆盖失败，或声称“纯水文总体率必然<20%”，
都是错误归因。纯几何区间跨越三档，当前统计不足以给它唯一分档；
18.18%条件比例也不是无偏总体成功率。

【工程判断】回S1处理已确认的root九列/实际首段可认证性衔接缺口，
并先定位root性能拒绝与取证环境干扰；不是找17/20两个seed掩盖其余38个结果。
本轮**不修改任何生产实现、40ms门槛、水文规则、降级预算或seed**。
上述是待用户决策的方向，不是已获得修复授权。
不能把“全部首段必须无水”当新需求：既定窄河水门/宽水域终止仍保持；
需要的是首段可认证性闭环，不是私自删河或禁止合法水文段。

### C.1 附加诊断：山脊结果退回carrier

【已验证】进入route0的11个样本全部degradation=3，2049个u格点均与纯
AnchorField.carrier逐点相同（9个水文拒绝+2个水文完成）。
【源码事实】RidgePlanner的SEARCH预算耗尽分支需要>=120000列，
本批route0的SEARCH为13626～15588列，远未到该预算；低于该阈值仍出现3的生产分支在
prepareCurve/emitPoint的几何检查。源码索引RidgePlanner.java:84、:216、:264、:270。
【推断】这些样本的整段residual被几何fallback清空，而非预算用尽。
**未记录首次触发cell/具体检查，因此不确定是解析导数、包络还是栅格弦检查首触发**。
这项弱化山脊路线的观察需要接手者核验，不能笼统把全部失败归于boundary1偏移。

另有源码事实：CpuTerrainOracle.coarse只评估initialDensityWithoutJaggedness，
不执行reference读取露天水层；COARSE cursor的waterTop保持空。
因此RidgePlanner的wet评分在这些COARSE采样上不是逐列露天水认证。
这是当前合同使用的近似信息，不在本任务私自切换采样算法。

## D. 证据、文件与复现

完整证据根：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\root-hydro-40-20261001-998c68ec\`。
`summary.json`为运行器汇总，`independent-audit.json`从40份原始row/execution重新计数，
`cases.csv`供逐seed审查；所有清单SHA匹配、输入源码未变、无缺失或无效row。
每个seed有输入/原结果/退出码、VM GC/safepoint、stdout/stderr、latest.log，
完成route时另保存route0/route1二进制。没有debug.log就不编造一个文件。
`server-smoke-passed.txt`只证明诊断服务器启动/正常停止。

生产Jar SHA始终
`dfe146262610ccb7bb4974c500f3cb5e37b43428fe6302e4b5d63d7caabe6a42`，
与`b440e33`此前的原件相同；src/main、src/client无任何diff。
正式生产OFF，Tan's BLOCKED，S0百轮/S1B慢矩阵本轮NOT_RUN。
运行器源码cf96116；中间提交2ef81aa只归档已封存观察，不改变冻结源码或旧输入凭证。

新增/修改绝对路径（均为测试/工具，不在生产sourceSet）：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\RootHydrologyFixture.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\SeedSurveyLocations.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\mixin\SeedSurveySpawnProbeMixin.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\mixin\SeedSurveyInitialSpawnMixin.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\ServerSmoke.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\resources\greatwall-server-smoke.mixins.json`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\smoke\SeedSurveyLocationsTest.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\gradle\smoke-tests.gradle`
- `D:\云游四海-Mod源码\plaincraft-great-wall\tools\Test-RootHydrologySurvey.ps1`

复现命令（再次执行将生成一套新40世界与新证据，不覆盖本轮）：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\tools\Test-RootHydrologySurvey.ps1 -MaxParallel 2
```

脚本严格离线/校验准备一次已锁运行时，exit0；其后40次直接启动锁定JDK21与本地缓存Jar，
不是40次绕开Gradle下载依赖。所有世界都新建于build/run/serverSmoke/vanilla，未读真实存档，
未写真实mods。测试工具编译/独立6项单测已通过；本批完整build/全JUnit/全GameTest未重跑，
不得继承前一阶段332项为当前新增测试代码的全量结果。源码核对与摸底交付完成，**停止等待决策**。
