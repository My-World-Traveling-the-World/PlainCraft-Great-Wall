# S1B 曲坡城门权限冲突：影响分析与修复记录

## 当前结论（2026-09-30 17:07）

曲坡站点 **64/64** 与相邻矩阵 **547/547（含 HYDRO 72/72）** 的 v2 分片均完整结束，退出码均为 0，证据已提交 `89d193d`、`2ace778`。随后按用户授权、经未筛选 dry-run 确认任务图后，最终 `build -x runGameTest` 严格离线通过：全量 JUnit **279/279、54 suite、0 失败/错误/跳过**，build 退出 0。

**GameTest 采用已审查分片证据及【推理】继承，非单进程全绿。** 第 4 节 576/960/192 的继承等级及撤销条件不变；`src/` 与分片 `3dc84cb` 一致。完整签收申请、原命令中止记录、dry-run 和最终命令见 `S1B.md` 顶部。下面第 0 节的 NOT_RUN 属于修复前决策时点，不是当前全量 JUnit/build 结果；历史失败和根因 B 均保留。

## 0. 修复前决策（2026-09-30）

本节在生产代码修改前记录。源码基线 `253a1d8`；三份中断日志已先行提交 `23f3d83`，`8f3f8e1` 补充日志的 Git 字节保真规则。不是阶段签收。

### 进程消失

原始日志见 `evidence/s1b-curved-gate/interrupted-20260930/`，含原件 SHA-256。
三份日志与本地 Gradle `daemon-21940.out.log` 均止于 13:40:17 的 CURVED_SITES index=60 PASS；没有最终汇总/退出码。
检索这些日志未发现 OOM、JVM crash、正常 shutdown 或外部终止标记；隔离 gameTest 目录未发现 hs_err/hprof/crash 文件。
查询 Windows Application/System 13:35–13:55 的严重/错误/警告事件未返回可解释本次终止的事件。
结论：**未查明**。这不是“证明无 OOM/外部终止”，更不是将已捕获的 WallFailure 等同于杀进程原因。

### 两个生产者（以下行号为修改前）

- `src/main/java/local/plaincraft/greatwall/component/WallSection.java:60-97,142-181`：按世界列中心投影到最近 station edge，得到真实 S、横向格和 W；`compileIntervals` 对 S 做半开区间判定；横向 `[-3,3)`、局部 y>=0 的净空输出 required AIR。
- `src/main/java/local/plaincraft/greatwall/component/ComponentAssembly.java:123-133,180-182`：Gate 的 mapper 用刚性 frame 逆栅格；required AIR 优先级900，Gate 实体优先级700。不存在对 x=223 的特判。
- `src/main/java/local/plaincraft/greatwall/component/Gate.java:26-31`：主体 X[-30,30)，Y=0、Z=-4/3 输出 PARAPET；两条 Ramp 仍归 Gate SiteId。
- `src/main/java/local/plaincraft/greatwall/terrain/RealizationPlanner.java:120-135,216-225`：Gate 用 `[site.S-30Q8,site.S+30Q8)` 从墙弧长范围扣除；该范围不是刚性城门端面。
- `src/main/java/local/plaincraft/greatwall/geometry/Rasterizer.java:41-50`：Gate 使用世界格中心、Q30逆旋转、floor 得局部格；不是 WallSection 最近曲线投影函数。
- `src/main/java/local/plaincraft/greatwall/generation/ChunkPlanCompiler.java:109-121` 正确拒绝必需墙净空擦除 Gate，禁止放宽。

### x=223 与 HYDRO：B，独立问题、相同世界列

HYDRO：`WaterGate.java:22-23` 的旧 lower floor 在 S=256.5 的49宽河流生成多余近岸列；独立反例位于 `AdjacentComponentMatrixTests.java:175-182`，近岸232、预留8，合法首列224，旧式产出223。
本次 fixture：城门中心 global S=256、局部长度60，沿弯曲路径取点与切线。墙第3 cell覆盖 S=[192,256)，标量门区从226开始；x不是S，刚性端部可落到世界x=223。精确Q8/局部列由定向运行输出，不凭整数世界坐标猜测。
两者共同使用基础定点/栅格工具，但**没有共用近岸8格常量或同一边界函数**。`RealizationPlanner.java:126-128,154` 的8格项只用于水文排斥/水门替换；`Gate.java` 的30来自60格主体。全项目搜索 literal223仅命中既有HYDRO反例，不是生产边界常量。

### 拟采用的最小修订与四类传播

生产修改目标是墙体生产前的 Gate 替换端面：用 Gate 同一刚性 frame 的沿向局部列 `[-30,30)` 决定替换，不把单个冲突体素列入白名单，不在编译后删体素。Gate 完整模板/马道与编译器不改。弧长日程、地形契约、出口搜索预算不变。具体实现须通过原完整连通/owner/反序检查才成立。

1. 几何/字节：Gate 邻接的 WALL端部可能增减完整列，墙 componentHash、cellHash、owner writePlanHash随实际内容改变。不能宣称修订是全局字节等价。
2. 身份/持久化：SiteId/ordinal/S日程/codec schema不改；模板/生成配方指纹须按既有规则冻结新世界版本，不将新字节灌入旧证书。生产接线仍关闭，不迁移旧存档。
3. 所有权/组合：WALL只输出属于其衔接端面的列；GATE的全部意图、归属与700优先级不改；墙必需净空900不改。ChunkPlanCompiler拒绝规则、writer owner权限、玩家保护不改。
4. 异步生命周期：锁外future、PREPARE/CAPTURE/APPLY、pin/lease/drain不改。若最终修复超出此范围，重新分析，不沿用此表。

| 旧测试组 | 影响判定（修复前） | 回归/继承条件 |
|---|---|---|
| 相邻主矩阵547 | 受影响 | DECISION等路径经过RealizationPlanner；完整547重跑，不以平地看似等价跳过 |
| 曲坡墙576 | 直接模板路径、无Gate输入 | 若只新增显式Gate替换参数且旧入口空参数等价，可继承；需最终diff核实，否则重跑 |
| 装饰survival960 | 各组件孤立直接compile，无RealizationPlanner | Gate/Ramp/通用模板输出不改且旧Wall入口等价才可继承；不认证合并后的装饰 |
| 马道入口192 | 直接Ramp.compileBetween | Ramp/ComponentAssembly/碰撞fixture及依赖不改可继承；不认证Gate+曲墙组合 |
| 曲坡站点64 | 受影响且旧run不完整 | 定向原case红绿后完整64重跑，保留所有输入及断言 |

授权：已完整补读 `S1B-补充合同.md`，第28节附近记录用户允许公开的最小物理公式修订；本次保持需求/尺寸/保护/预算，仅统一生产衔接端面，在该授权内。若必须扩大包络、改变walkY或恢复权限，停下重新询问。

修复前决策时点的全量JUnit/最终build：**NOT_RUN**（当时不能借旧08:44 XML）。后续实际结果见本报告顶部及第 5 节。S0百轮矩阵本轮仍NOT_RUN；真实生产墙/真实地形/长时堆峰值仍NOT_RUN；Tan's Huge Trees继续BLOCKED。

## 1. 旧公式

旧墙体替换式：`S_column ∉ [S_gate-30*256,S_gate+30*256)` 时允许生成墙。`S_column` 来自最近曲线边投影。
完整 Gate 则按刚性 frame 逆栅格，局部 X[-30,30) 建墙身/马道；两者在弯道不等价。这里不是 floor 应改 ceil：改一个取整方向不能把曲线投影变成刚性端面。

## 2. 具体反例（已实测）

原 CURVED_SITES **index=58**：`FORT -> GATE, bend=+1, slope=-1`，保持原 fixture 和所有原断言。

| 量 | 原始红态运行值 |
|---|---|
| Gate SiteId | adjacency / GATE / 200 |
| Gate global S | 65536 Q8，即256格 |
| Gate frame | center=(64923,-120) Q8，tangent=(1053829736,205826120) Q30，W=84，mirror=false |
| 冲突坐标 | (223,84,-3) |
| Gate局部格 | (-30,0,3)，主体沿向第1列的上护栏 |
| 墙合同 | globalSQ8=57754（225.6015625格），lateralCell=1，walkY=84 |
| 旧排除起点 | 57856 Q8（226格），所以57754仍在旧墙生成区 |
| 墙身份/局部沿向 | WALL ordinal=3，cell S=[192,256)，中心224；floor((57754-57344)/256)=1，即该cell局部第34列（-32起算） |

Gate以`Gate.java:31`的x=-30、z=3输出PARAPET；墙以`WallSection.java:101`的横向1、局部y=0输出必需AIR。`ComponentAssembly.java:180-182,291-295`分别赋900与700，`ChunkPlanCompiler.java:116`拒绝正确。

红态：`evidence/s1b-curved-gate/target-red.log`及`target-red.xml`，测试入口提交`4d5b608`，生产代码仍等于253a1d8；退出1、1方法/1组合失败，47秒。不是根据旧日志猜测复现。
永久反例：`CurvedGateBoundaryTest.legacyScalarCutReallyPublishesClearanceInsideTheFirstGateParapetColumn`（第26行）主动调用旧标量路径，验证同一坐标确实同时属于墙必需净空和Gate实体；修复后该反例仍成立。该JUnit使用原空间曲线与恒定W84，只证明该空间边界矛盾；完整混合坡面由原GameTest验证。

## 3. 最小修订

生产提交：`21c6177`。只修改两个生产文件：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\RealizationPlanner.java:121-147,229-231`：对成功编译的完整Gate，传不可变frame集合给墙生成；旧标量区间仍作为路线锚点区间，不再冒充墙体替换端面。失败Gate维持原切断处理；水门、敌楼、烽火台、关隘的原标量规则不变。
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\WallSection.java:46-75,111-123`：新增重载，在体素生成前按Gate同一逆栅格算法，局部沿向列落在[-30,30)则由完整Gate替换该墙列。不是取planning guard/AABB，也不是列出冲突坐标后过滤。原标量锚点落进刚性端面时，从当前有限区间两端逐格向内找到实际墙端点；不改变W或搜索预算。

恢复的列也同样重要：从buildable中移除错误标量排除，保留Gate端面外的原墙列；不能只多删几列遮掩冲突。`replacementUsesCompleteEndPlanesNotAConflictCoordinateWhitelist`（第44行）检查结果等于完整原墙在端面外的全部体素，涵盖地板、实体与净空，不止检查“没抛异常”。

### 同类计算盘点（当前行号）

| 位置 | 语义 | 本次处理 |
|---|---|---|
| Gate.java:15,26-31 | 60格刚性主体端面 | 原样保留 |
| SiteScheduler.java:114 | Gate规划足印/高度采样范围 | 原样保留，不拿guard当擦写权 |
| HeightContractPlanner.java:55-75 | 平台高度硬契约 | 原样保留，不改变W |
| RealizationPlanner.java:126,131-132 | 站点水文避让 | 原样保留 |
| RealizationPlanner.java:136,146,229 | Gate墙替换与路线 | 刚性端面替换；标量范围仍约束路线 |
| RealizationPlanner.java:285 | cap对完整站点的预检 | 原样保留；完整相邻CAP/TERMINAL矩阵重跑 |
| WaterGate.java:22-23、RealizationPlanner.java:160 | 水门8格岸侧范围 | 原HYDRO修复原样保留，72组合随547全跑 |
| RealizationPlanner.java:253-254,380 | 收头候选/旧局部收头边界 | 另一用途的30格，不机械批量替换 |

因此关系判定为 **B**。共享的是基础栅格工具与权限保护，不共享导致错误的近岸公式；没有漏改WaterGate的另一个调用点。

## 4. 明确不变项及继承判定

- Gate、Ramp、WaterGate、ComponentAssembly、ChunkPlanCompiler、ChunkWallWriter源码未改；所有部件尺寸、20×10墙截面、H=1..32、drop≤12、128/4退让、[W-32,W+6]探测保持。
- 不改SiteId/ordinal、优先级、schema、owner裁剪、玩家保护、已有永久证书；不做旧存档回填。改变的是WALL端部内容以及由内容派生的hash，不保证新旧墙字节相同。
- 不改锁前依赖、任何future、pin/lease/drain协议或S0路径；新增列表为请求内数据，传入墙时复制成不可变集合，没有新共享可变状态。
- 无代码/资源/依赖升级。`git diff --name-only 253a1d8 -- src/main src/client gradle build.gradle gradle.properties settings.gradle`只返回以上两个Java文件。源码snapshot改变后不能直接加载旧构建证书；S1C接线时仍须冻结生产配方指纹，本次没有提前实现这项。

| 测试组 | 本次处理 | 可核依据与限制 |
|---|---|---|
| 相邻547 | **受影响，完整重跑** | `AdjacentComponentMatrixTests`调用RealizationPlanner并合并owner；不以部分平地样本字节等价外推全组 |
| 曲坡站点64 | **受影响，完整重跑** | 原64项枚举/fixture/断言未删；定向入口只是从同一列表取index58 |
| 曲坡墙576 | **NOT_RUN / 继承历史576/576；依据【推理】** | 【推理，非本组修复后实测】`CurvedWallMatrixTests.java:34`直接WallSection.compile；旧入口最终传空gates（WallSection:28,40-48），新增mask必返回false、锚点while不进入，原列/材料/路径运算不变；不继承Gate混合曲线结论 |
| 装饰survival960 | **NOT_RUN / 继承历史960/960；依据【推理】** | 【推理，非本组修复后实测】`ComponentSurvivalMatrixTests.java:41-42,64-72`直接孤立模板；Gate/Ramp等模板及依赖未改；WALL走上述空gates等价路径。仅继承原孤立输入域，不代替合并装饰检查 |
| 马道地面入口192 | **NOT_RUN / 继承历史192/192；依据【推理】** | 【推理，非本组修复后实测】`RampGroundPortalTests.java:29-33`直接Ramp.compileBetween，原Ramp/Assembly/碰撞fixture无diff；不认证Gate与邻墙结合后的入口 |

继承风险明确：这些是253a1d8中断运行内**已经完整输出成功汇总的分片**，不是一次完整进程退出0，也不是修复后新运行。继承依赖本次差异隔离；后续若改通用模板、palette、Rasterizer、fixture或依赖，即撤销该继承。旧证据对应原日志第178/24516/25204行；原文件哈希已封存。

证据等级分开：历史三组通过属于【已验证，仅原运行输入域】；将历史结论继承到修复后源码属于【推理】，来自调用路径与差异隔离，不是三组的后修复运行结果。新增JUnit中的空gates/直线门字节检查虽已运行，只覆盖其有限fixture，不能升级为这三组的实测等价证明。本次不追加三组代表case，其状态仍为NOT_RUN。

### 2026-09-30 14:55 监控交接

用户已确认本轮修复与影响分析质量合格，并要求退出持续监控。两份隔离测试继续运行，不发送停止信号、不重复启动；后续按用户询问读取各分片`execution.json`、`results.xml`及原日志。退出监控不是测试结束或通过声明；若进程异常消失且无退出凭证，仍明确写未完成/原因待查。

## 5. 修复前后验证与残余范围

| 本次运行 | 结果 | 证据 |
|---|---|---|
| 原index58修复前 | FAIL，退出1，1方法/1组合 | target-red.log/xml |
| 原index58修复后 | PASS，退出0，1方法/1组合，1分41秒；2邻接对连通，0撤回 | target-green-v1.log/xml |
| 相关JUnit六suite | PASS，38测试，0失败/错误/跳过，退出0，24秒 | target-unit-v1.log、target-unit-v1/六份XML |
| 独立副本首次回归启动v1 | BLOCKED于编译前依赖验证，两个退出1，矩阵NOT_RUN | regression-20260930-gate-v1/；39个新生成的remapped jar不匹配既有锁值，未关闭校验 |
| 隔离矩阵v2 | **PASS：64/64、547/547（HYDRO72/72），两个退出码均0** | regression-20260930-gate-v2/；snapshot=3dc84cb，各有 execution.json、结果XML与哈希；15:56/16:35结束，原件已入库 |
| 原命令最终build尝试 | **人为中止，退出1；不是PASS** | final-build-20260930/；原命令实际启动全部GameTest，用户批准分片验收后中止；旧JUnit副本fresh=false，不计数 |
| 未筛选dry-run | **退出0，确认build包含runGameTest及test** | final-build-20260930/dry-run/；SKIPPED仅为dry-run任务图标记 |
| 获批最终build与全量JUnit | **PASS：退出0，279测试/54suite，0失败/错误/跳过** | final-build-20260930/scoped-build/；JDK21、offline/strict、build -x runGameTest、rerun-tasks/no-build-cache；XML全部新鲜且哈希一致 |

v2仅复制主工程现有`.gradle/loom-cache/remapped_mods`，逐文件SHA相等，清单在各分片`copied-cache.json`；Gradle仍严格校验同一verification-metadata.xml。没有信任首次新生成的39个jar，没有增加checksum或联网。各分片游戏堆1536m，Gradle JVM2G，不据此声称完成长期堆峰值验收。

### 可复现命令

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
# 单个原反例；不包含全矩阵。
.\gradlew.bat --offline --dependency-verification=strict runGameTest -PgreatwallGameTestClasses=CurvedGateRegressionTests -PgreatwallTestHeap=1024m --console=plain --no-daemon
# 仅相关JUnit；不称全量JUnit。
.\gradlew.bat --offline --dependency-verification=strict test --tests '*CurvedGateBoundaryTest' --tests '*RealizationPlannerTest' --tests '*WallContractEquivalenceTest' --tests '*WallRampTest' --tests '*WallDecayTest' --tests '*ChunkPlanCompilerTest' --console=plain --no-daemon
# 冻结干净HEAD后，两份独立源码快照，已有RunId拒绝覆盖。
.\tools\Test-CurvedGateRegression.ps1 -RunId regression-<unique-id>
```

脚本每分片命令为 `--offline --dependency-verification=strict runGameTest -PgreatwallGameTestClasses=<CurvedSiteMatrixTests或AdjacentComponentMatrixTests> -PgreatwallTestHeap=1536m --rerun-tasks --no-build-cache --console=plain --no-daemon`。`run.json`绑定源码/归档身份，`execution.json`在退出后记录命令、起止时间、退出码、日志/XML哈希；没有退出凭证就不签收。

**本轮全量JUnit279/279 / 获批最终build（显式排除runGameTest）= PASS，退出0。** GameTest仍采用已审查的分片证据与【推理】继承，非单进程全绿；不能把原中止命令或dry-run算作最终成功构建。S0百轮地形矩阵NOT_RUN（历史8/8、S0路径未改）；真实CPU/Tectonic/C2ME生产墙、真实地形、24小时/最终堆峰值验收NOT_RUN；Tan's Huge Trees profile仍BLOCKED。S1C不启动，不部署真实mods，不打开真实存档。
