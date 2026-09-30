# S1B 验收与剩余工作报告

## 当前增量修复状态（2026-09-30，曲坡城门）

**仍未整体签收，生产接线关闭。** `253a1d8`的完整签收进程中断：547相邻、576曲坡墙、960孤立survival、192马道入口已输出完整通过汇总；曲坡站点有异常且无最终汇总，未取得全量JUnit/最终构建退出码。进程消失原因未查明，不归因于最后一条PASS或已捕获的WallFailure。

当前已按先封存证据、再影响分析、再定向红绿推进，生产修复`21c6177`只改墙体与刚性Gate的替换端面，不改编译器权限、完整Gate或用户尺寸。原曲坡index58已实测红转绿，38项相关JUnit已通过；受影响的547与64完整矩阵正在隔离回归，尚不写PASS。

详情、分片继承依据、原始日志与复现入口见`S1B-曲坡城门修复.md`。下方是历史报告，不用其旧全绿声明替代当前受测源码。本轮全量JUnit/最终build仍NOT_RUN；真实地形/真实生产墙/最终峰值验收NOT_RUN；S1C不启动，Tan's Huge Trees继续BLOCKED。

576/960/192三组本次未重跑；历史通过记录是原运行实测，修复后的继承依据明确为【推理：调用路径/差异隔离】，不是【已验证：修复后矩阵等价】。按用户要求退出持续监控，隔离回归继续运行，待后续询问时读取退出凭证。

## 最新交审状态（2026-09-30）

**本轮已整理提交，等待审计；不是最终源码全量测试 PASS 声明。**
用户明确要求不再额外耗时重测，保留既有结果，在 HYDRO 两个失败组合定向通过后提交审计。
本节优先于下方历史状态；历史失败、旧检查点与反例全部保留。

- 修复前完整相邻矩阵：CONFLICT 128/128、BOUNDARY 256/256、GAPS 6/6、REJECTION 8/8、TERMINAL 15/15、CAP 30/30、DECISION 32/32；HYDRO 70/72，两项失败。该轮总计 547 个参数，545 PASS、2 FAIL，退出码 1。
- HYDRO index=1/19 均由奇数河宽的半格中心近岸取整越出一列引起。WaterGate 下界 floor 改 ceil，保持八格预留与 owner 权限。旧公式反例和完整原检查保留。详见 `S1B-HYDRO边界修复.md`。
- 修复后仅运行 HydroBoundaryRegressionTests：两参数 2/2 PASS、1 个 GameTest PASS，严格离线、严格校验、JDK21，退出码 0，耗时 2分12秒。未把分次检查相加宣称最终全域 547/547 PASS。
- 收尾代码包括 codec 8 KiB 前置计量缓冲及单 scope 哈希、inspection/cap vote 保留计量；新增矩阵源码与审计材料一并交审。
- `s1b-final-unit-compile.log` 的既有 `test compileGametestJava` 为 BUILD SUCCESSFUL（1分15秒），发生在最终 HYDRO 修复之前，不当作最终源码全量 build。
- 最终源码全量 build、完整72项 HYDRO 复跑、960项 survival、64项曲坡站点、576项曲坡墙、最终峰值压力复跑：本次 NOT_RUN。既有压力采样及历史运行见 `S1B-收尾过程.md`，不外推到未测范围。
- S1C 未实施；生产接线关闭；Tan's Huge Trees profile 继续 BLOCKED。

### 交审入口和复现

文件清单：`docs/evidence/s1b-closeout/review-files.txt`（绝对路径，相对本轮起始 HEAD 3cea506）。
代码分批提交，最后以单独文档/证据提交封存；使用 `git log --oneline 3cea506..HEAD` 定位。
本次收尾整理没有启动任何额外测试，没有修改依赖版本或代理配置；本地 gradle.properties 无 proxy 配置。
关键日志封存在 `docs/evidence/s1b-closeout/review-logs.zip`，包含原相邻矩阵失败、单测编译记录、HYDRO 定向红绿日志。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runGameTest -PgreatwallGameTestClasses=HydroBoundaryRegressionTests -PgreatwallTestHeap=1024m --console=plain --no-daemon
```

上述命令是已经成功执行的定向复现命令，不是本次整理再次运行。其余验收范围交由审计明确处理，未验证项不静默标绿。

## 当前结果（2026-09-29，跨cell修复后）

**B-S1B-03已实现并通过验证。S1B整体仍为BLOCKED，未整体签收；生产接线关闭，S1C未启动。**

本轮接续 `0be2433`，不是等待用户再次批准。跨cell决议及后续马道/水门/楼体入口收尾均已编写代码。最终受测源码提交为 `7964b59`；构建运行时是同一份未提交源码，完成后原样提交，没有在测试后修改源码。当前报告以本节和 `evidence/s1b/cross-cell/final/checks.json` 为准；下方旧的167/1 FAIL是特意保留的历史，不能当作当前结果。

### 本轮实际验证

- 严格离线全量构建已通过：`build runGameTest --continue --rerun-tasks --no-build-cache`，保持 `--offline --dependency-verification=strict`、JDK21。
- 全量JUnit 195项、39个suite，0失败、0错误、0跳过；含S0/S1A回归，不把全部数量冒充S1B独有测试。
- Fabric GameTest 22/22通过。范围是解析规划+原版BlockState/ProtoChunk/Vanilla mailbox，不是已启用真实生产墙世界。最终构建退出码0，耗时7分10秒，18个任务全部实际执行。
- 真实山脊反例转绿：所有局部马道仍不可行，但共同决议将断口退到关隘出口；早/晚cell使用同一边界。原局部不可行反例继续保留。
- 原生两owner测试：APPLY前完成远端决议；早/晚owner都不保留被撤回步道；服务冷重开后的逆序重放保留玩家金块。
- 正序/逆序/随机/并发、冷热服务、延迟证据、取消隔离、拒绝executor、关闭、缺T0、缺/坏耐久证据、8192-cell稀疏摘要容量均有定向测试。
- 补齐马道四候選和有支撑外沿护栏；H=1..32逐一通过原版碰撞形状通路检查，不声称人工玩家实走。
- 收头四种马道与主体做跨子组件连通验证，证书保存实际占用S足印；cap接受前避开完整Gate/站点/水门。
- 在同一退让S上，首个可落地朝向被足印/碰撞决议否决后继续测试其余朝向；证书重放按componentHash恢复所选候选，不再默认重新选择第一个朝向。定向测试验证全部四候选、全拒绝及按hash重放。
- 水门次跨4..49宽度、显式双支座、单层自然薄冰证据及原版writer主航道化水/旁冰保留通过；冰面桥墩冲突时拒绝整件，不挖旁冰或留悬空墩。
- 敌楼/关隘占位体非零基础落差入口已修复：JUnit逐一检查drop0..12；原版碰撞检查两类构件的56组组合（0°的13个drop，另5个朝向的drop4/5/12），墙顶可走到组件外自然地面。没有把解析地形测试写成人工玩家实走。

### 五项修复边界

| 边界 | 落地方式 | 结果 |
|---|---|---|
| recipe+绝对S+SiteId+T0+最终截断 | GroundExitDecision与schema2二进制证书；cap实际足印入hash | PASS |
| 共同决议先于APPLY | GroundExitResolver single-flight；ProductionWallPipeline先require再resolve/compile | PASS（纯协议及原生fixture） |
| 不由加载侧/冷热决定 | 完整T0和自然出口证明；缺列T0_MISSING；冻结摘要有界复用 | PASS |
| 较早出口锁外异步依赖 | ArcPlanProvider按真实PrefixSummary取S；逐cell FEATURES/CAPTURE，无同步future等待 | PASS（S0适配器未变；新生产C2ME世界NOT_RUN） |
| 不撤销旧owner、不毁玩家改动 | intent→证书→seal；build-exits绑定write proof；完整marker只读重放 | PASS |

完整协议、接口与资源边界：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-跨cell决议实现.md`。

### 冻结公式和接口变更

见 `S1B-补充合同.md` 新增章节：原H=1/2/3/32反例不删；四候选包络恢复原规定范围；正向cap连接平台保持在原60×30包络内；末跨保留 `4+2+1>6` 反例并将尾部2列归入岸侧基础；ProbeResult追加可选thinIceY且保留旧构造器。原20×10墙、H范围、128/4日程、drop12、owner权限不变。

32高度原版测试首次失败是fixture仍将beforeStates地面写死64，与较低supports矛盾。只修fixture地面参数，没有放宽天然实体/负空间保护；失败日志另存 `cross-cell/native-fixture-mismatch.txt`。

楼体入口补充修订：敌楼在11×11内部环道向下补阶，原两层、上部梯段和屋顶不动；关隘在48×48内降低庭院/门道至已证地面，内部两条三宽坡道增加相应级数。两者最小Y改为-33以容纳drop12地板。SiteScheduler同步授权过的包络（含此前Gate/Ramp/敌楼净空修订），不改折线、walkY、300格序号、退让日程或探测深度。精确坐标式和反例见补充合同。

新GroundEntranceTest首次编写时错误地把含一格guard的worldBounds当实际墙边，导致drop0也报错。保留该次原始日志/XML在 `ground-entrance-before.*`；它们是测试修正记录，**不是冻结公式矛盾的证据**。现测试使用实际体素足印；独立保留 `nominalGroundFormulaIsNotAnExitWhenFoundationDropIsNonzero` 证明原W-20入口与真实地面相差4/5/12格、W-32脚面需要W-33地板；有效通地验收没有删减。

### S1B尚未整体签收的原因

这些不是授权问题，也不是B03仍未实现；不能把已通过子集外推为全部完成：

1. **T0/compiled完整分配前预留、实际峰值pin压力**尚未闭合。现有有限窗口、字节计量、失败/关闭释放及新的有界决议缓存，不等于已证明整个Java堆峰值；仍有分配后才检查的路径。
2. **全站点/水门/收头相邻碰撞矩阵、曲坡和装饰survival**未跑完。楼体入口在明确列出的drop/朝向组合已经通过，不能外推到混合斜坡/弯道/邻构件干涉的所有组合。本轮有定向冲突消解和动态WATER_GATE同步撤回，不将局部测试标成全组合PASS。

另列后续阶段而非冒充本阶段已验：**真实CPU/Tectonic/C2ME生产墙与生命周期**属于S1C后续接线，仍NOT_RUN。Tan's Huge Trees profile继续拒绝授权，S5整包不因本轮结果解除BLOCKED。没有因这些后续阶段未开始而声称B03未完成。

因此本轮不写“S1B完成，请审查”。

### 最终证据和复现

本轮新增/修改文件完整绝对路径、SHA-256及提交清单：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\cross-cell\final\files.json`

全量日志、原始XML、机器汇总同目录保存。修复前报告/阻塞报告完整原件保存在 `cross-cell/pre-fix-report.md` 与 `cross-cell/pre-fix-blocker.md`；原continuation证据及旧S0/S1B矩阵没有覆盖。

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
New-Item -ItemType Directory -Path '.\build' -Force | Out-Null
.\gradlew.bat --offline --dependency-verification=strict build runGameTest --continue --rerun-tasks --no-build-cache --console=plain --no-daemon *> '.\build\s1b-cross-cell-latest.txt'
$buildExitCode = $LASTEXITCODE
.\tools\Test-TerrainMatrix.ps1 -EvidenceStage s1b -EvidenceRun cross-cell -Rounds 100
.\tools\Export-S1BCrossCellEvidence.ps1 -BuildLog '.\build\s1b-cross-cell-latest.txt' -BuildExitCode $buildExitCode
```

本轮百轮矩阵另存 `docs/evidence/s1b/matrix/cross-cell/`；原版与Tectonic+C2ME各1/2/8/16线程、每项400owner，**8/8通过，共3200owner，脚本退出码0**。矩阵执行于 `dfa984e`；之后两笔提交仅改生产收头候选/模板/规划足印及测试，S0实验路径未变。矩阵验证S0实验屏障，不冒充S1C生产墙认证。最终 `7964b59` 源码又跑过上列完整构建与22项GameTest。

归档工具只核验和复制已完成结果，不运行构建、不联网；源码/锁配置有未提交差异、测试失败或跳过、矩阵不足8项均拒绝导出。`files.json`记录本轮源文件/测试/脚本/报告的完整路径和SHA，`matrix-files.json`记录矩阵原始证据。此前191/21的中间全量结果保存在 `cross-cell/milestones/dfa984e/`，当前最终目录为195/22。

本轮证据路径在`.gitattributes`中关闭文本换行转换，保持日志/XML/JSON的原始校验字节，并识别原生CRLF；仅原始命令日志跳过空白格式检查，保留Gradle输出的行末空格和末尾空行，不改日志取悦格式检查。源码/报告仍正常检查，清单同时记录工作区原始SHA-256与Git规范化blob身份，避免另一台机器的CRLF/LF检出策略被误报为源码内容改变。

未升级依赖、未联网、未设置代理；未部署真实mods、未打开真实存档、未写旧世界回填；未碰possibleBiomes，未加worldgen同步getChunk/join/get，未改变startup pump或C2ME锁授权。

---

## 修复前报告原文（历史，不是当前测试结果）

日期：2026-09-29。S1A 审查基线：`80f2b24`。本次实现末提交：`eae8993`；保留失败反例的源码提交：`1d4ff21`。

**阶段状态：BLOCKED。未完成、未签收，未进入 S1C。**

用户批准的 H=1/2/3/32 修复已完成，原四个红项现在均通过，原公式反例仍保留。后续已实现构件、owner writer、耐久 T0/proof 与生产屏障策略，并完成下面列明的验证。当前不能签收的主要阻塞是 **B-S1B-03：有限收头候选全部失败后，跨 cell 退回前一个已验证地面出口的决议尚未实现**；也仍有第5节列明的规格细节未完成。

此前等待马道批准的报告已原样归档到：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\pre-approval-report.md`

旧报告、旧131/135结果和旧四项红色日志都是历史，不是当前结果。原八册未被静默重写；授权修订及新增接口闭包见 `docs/S1B-补充合同.md`。

## 1. 本次严格离线结果

实际执行，退出码 **1**：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build runGameTest --continue --rerun-tasks --no-build-cache --console=plain --no-daemon
```

`--continue` 只让其它验收任务也跑完，不跳过失败、不关闭校验、不把失败改成成功。

关键输出：

```text
All 17 required tests passed :)
167 tests completed, 1 failed
BUILD FAILED in 2m 5s
18 actionable tasks: 18 executed
```

- Java/客户端/GameTest编译和 remap 执行完成；**整个 build 失败，不可发布**。
- JUnit：34个 suite，167项，166 PASS、1 FAIL、0 skipped。
- 唯一 FAIL：`GroundExitFallbackTest.exhaustedCapSearchMustFallBackToTheEarlierGroundExitWithoutFatalGeneration`。
- Fabric GameTest：17/17 PASS，正常保存、关服。
- 没有 `@Disabled`、排除红项、空组件冒充成功或永久关闭依赖校验。
- Java21、Mojmap、Gradle9.4.1、Loom1.16.3、Loader0.19.3、Fabric API0.116.14+1.21.1及JUnit5.11.0均保持原锁定版本。未下载依赖，未添加代理。

最终原始证据：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\strict-offline-build.txt
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\gametest-results.xml
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\junit\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\checks.json
```

## 2. 冻结公式修订与反例

| 原条目 | 真实矛盾/缺口 | 已采用的最小修订 | 反例及验证 |
| --- | --- | --- | --- |
| H=1/2/3 全部套 U形两跑平台 | H=1/2上下平台地板侵入必需净空；H=3候选也碰撞 | 仅H=1..3使用原足印内不重叠短坡；H≥4保留原两跑公式 | `RampSpecificationTest`保留原H1/H2坐标反例；`WallRampTest`逐一验证1..32全部平台、支撑和净空 |
| Ramp localBounds.minY=-32 | lower=W-32 的地板在 W-33 | minY=-33，只多容纳这一层；不改探测窗口/支撑drop | 保留H32原包络反例；H32完整构件通过 |
| 敌楼 maxYExclusive=W+7 | 最后脚面W+5所需三格净空包括W+7 | maxYExclusive=W+8，只增加空气包络 | 11×11实体尺寸不变；6朝向、各层通地原版碰撞通过 |
| 收头退让被实现成沿旧切线平移、沿用旧W | 弯道/坡道离开站点合同；反向端点错误减S | 每4S采样真实位置、W、切向；方向取端点朝向与合同切向点积 | `capRetreatFollowsBendAndHeightInsteadOfOriginalTangent`保留原位置反例 |
| 默认 Ramp/Gate 入口固定 W-20 | drop=4/5/12时填实后仍为高台，不等于通地 | 默认入口取完整入口足印已探明的自然地面，足印高差≤1；明确协商高度仍用原显式接口 | `defaultRampGroundEntranceTracksActualSupportedGround`、`filledFoundationsDoNotPretendTheirTopIsNaturalGround`及旧式高台反例通过 |

H=1..32 合法范围未改。walkY合同未改。探测窗口仍 `[walkY-32,walkY+6]`，只读三层guard可到W-35；drop上限仍12。没有降分辨率、缩小构件或放宽支撑来换性能。

另外补足了冻结签名原先缺失的数据闭包：`SiteKind.WALL`、`ComponentVoxels.realizedState`、`WriteMetadata`、生产marker完整字段、耐久capture intent等；旧构造器保留，原几何和T0-v1字节协议不变。详细合同在补充文档中逐项列明。

## 3. 已完成的实现批次

每批生产类控制在1～3个；测试、合同说明与对应类一起提交。

| 提交 | 内容 |
| --- | --- |
| `2cf4090` | 短坡/最低地板修复，保留1..32验收 |
| `4ead623` | EnemyTower、BeaconTower、旋转功能格一对一绑定 |
| `54f9a15` | Gate、EndCap、完整组件合并 |
| `196ae43` | WaterGate、48×48 FortPlaceholder |
| `6ad0fd7`、`22793f1` | ChunkPlanCompiler、写入metadata、破败状态传递 |
| `b346946`、`4adf82f` | 冻结BlockCatalog、OwnershipGuard、原生writer/markers |
| `87b1588`、`bec35d7` | 生产存证、coverage、capture intent |
| `904a483` | TerrainSnapshotService、SnapshotColumnView |
| `0bb8ff1`、`49bad68`、`1dd1fb7` | RealizationPlanner、逐列破败、真实曲线回退、末段裁切与终点原因 |
| `cec91b6` | 生产版PREPARE→CAPTURE→APPLY与原版构件验证；S0实验策略保留 |
| `a1934a9` | 显式双支座支撑组、原版船体通航及源水测试 |
| `b7617df` | 拒绝IO后的future/claim计数清理、marker完整payload校验 |
| `eae8993` | 默认入口真正落到已证自然地面 |
| `1d4ff21` | 保留跨cell出口回退的失败验收 |

`BlockCatalog` 的箱子属性序列化已由 Java `toString()` 修为原版 `Property.getName(value)`，实际含箱子的整件写入反例发现该问题。S0 token 和 T0-v1 hash 不因这项生产序列化修复改变。

## 4. 验收核对与范围

| 判据 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- |
| 马道H=1..32 | 完整平台、两格净空、可达 | 原四红项及全部32高度的纯几何检查通过 | PASS；全部高度逐一原版实体运动尚NOT_RUN |
| 默认地面入口drop0/4/5/12 | 入口在地面，不是高台 | 默认马道/城门/收头局部测试通过 | PASS，解析T0 |
| 支撑drop0/4/5/12/13 | 前四支持、13拒绝 | SurfaceProbe边界/guard/遮蔽/保护测试通过 | PASS |
| 墙体20×10、收分、6格净道 | 尺寸不变 | 纯几何通过；六朝向原版碰撞形状连通通过 | PASS，平坦代表fixture |
| 三材质族同占位 | 几何一致 | 完好墙段比较通过 | PASS，局部；不是全构件全四态图册 |
| 敌楼11×11/1、2、4owner | 所有层可达、重放同结果 | 原版ProtoChunk写入、逆序重放和原版碰撞检查通过 | PASS |
| 烽火台四火塘同身份 | 不重复、不缺失、未点燃、有支撑 | 六朝向身份测试、破败功能删除、原版连接更新通过 | PASS，S3玩法未启用 |
| 门洞不被马道堵住 | 上下通路完整 | Gate纯测试和原版碰撞检查通过 | PASS，代表fixture |
| 收头通地、弯坡回退 | 正常候选真实可达 | 常规收头/三种落差/曲线重采样通过 | PASS，局部 |
| 所有128格候选失败后退至旧出口 | 跨cell一致、不能fatal代替 | 解析山脊反例可稳定复现缺口 | **FAIL / B-S1B-03** |
| 河流49/50/51 | 49跨、50/51不建水门 | 模板拒绝阈值、近侧cap、纯terminal决议通过 | PASS，各自测试范围 |
| 原版船体穿主拱 | 无方块碰撞阻塞 | 实际Boat.move在隔离测试世界横穿物理主拱切片 | PASS；非玩家驾驶/长期浮力与流体压测 |
| 源水不抬面不填净空 | 原水面以下source | 原版BlockState检查和4宽3高主通道检查通过 | PASS |
| 无孤岛、重力、拱双支座 | 必需支撑不能假落地 | 纯支撑反例通过，城门接入双支座；代表废墟及楼梯/铁栏原版邻接更新通过 | PASS，明确子集，见未完成项 |
| 整件决议→owner裁切→proof预检 | 不挖未知方块，不越界 | 顺序/hash/BE保护、全部写集预检、编辑后重放测试通过 | PASS |
| 原T0耐久复用 | APPLY后不能重采墙 | 多页/20并发请求、磁盘逐字节重读、晚到复用通过 | PASS，原生ProtoChunk及Vanilla mailbox |
| 历史/证据损坏 | fail closed | 缺页/缺proof/CRC/身份/marker payload测试通过 | PASS，对当前存证协议 |
| IO拒绝/释放 | 无永久挂起 | 300次拒绝IO、flight/claim/admission归零通过 | PASS |
| 生产完整屏障路径 | proof durable后exclusive写，回调锁外 | 解析合同+真实ProtoChunk/Vanilla mailbox完整链路及player edit重放通过 | PASS，FEATURES由fixture提供 |
| S0百轮矩阵 | 1/2/8/16同输入同输出 | 8项全部通过，共3200owner | PASS，S0实验屏障，不冒充生产墙C2ME接线 |
| S1B整体 | 所有规定行为和build通过 | 一项红色验收 + 未完成细节 | **BLOCKED** |

GameTest对角色通路使用原版碰撞形状、0.6×1.8身体、半格节点检查，不是让真实玩家或寻路AI逐条走完全路。17项原始方法名和结果在最终XML中，不据测试名字扩大覆盖范围。

## 5. 阻塞与未完成项

主阻塞详细报告：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-跨单元出口阻塞.md`

本例中前一个实际可行出口可能在128格回退/当前cell有限邻域之外。当前输入/缓存没有跨cell接受出口证明，也没有在所有相关owner APPLY前传播截断边界；现有代码只能 fail closed。不是旧马道批准仍未处理，更不是悄悄把异常当“成功断口”。

另外以下范围尚未完成，**不列为PASS**：

- Ramp固定四候选择优和完整外沿护栏尚未闭合；当前正常模板路径已验证，不等于四候选合同齐备。
- WaterGate全部次跨的余数合并、自然薄冰主通航清除/旁冰保留策略、水门的显式双支座分组尚待完成；本次双支座已接城门。主通航和基本墩身规则已实现。
- Realization维护影响目前来自计划中未抑制的站点，尚未收敛为仅“经完整T0接受的站点”；跨站点/水门/收头相邻冲突的完整矩阵未跑完。
- 敌楼/关隘在非零基础落差下的每个外部地面入口、全部转弯坡度、所有装饰的原版survival尚未全覆盖。
- T0/compiled字节限额已有检查，仍需压力验证峰值对象占用、在分配前的完整预留和长期pin行为；不以逻辑估算冒充实测内存上限。
- 生产FEATURES真请求端口与真实CPU地形/C2ME生产墙的全链路验证为NOT_RUN；当前完整pipeline测试使用解析规划和预置FEATURES ProtoChunk。
- S1C植被抑制/实际生命周期切换未实施。Tan's Huge Trees保护不足的profile仍拒绝授权；S5整包仍BLOCKED，没有删mod冒充兼容。

## 6. S0矩阵证据

实际命令：

```powershell
.\tools\Test-TerrainMatrix.ps1 -EvidenceStage s1b -Rounds 100
```

脚本内部每次均使用 JDK21、`--offline --dependency-verification=strict`，新建隔离世界；第三方jar只从本地实例复制并核对锁定SHA，不修改源实例。

| profile | workers | rounds | owners | 结果 |
| --- | --- | --- | --- | --- |
| vanilla | 1 / 2 / 8 / 16 | 各100 | 各400 | 全PASS |
| tectonic-c2me | 1 / 2 / 8 / 16 | 各100 | 各400 | 全PASS |

比较的是每个profile内跨并发的逐owner T0/realization/final hash；不宣称 vanilla 与 Tectonic 应具有相同地形。每个owner都验证捕获→落盘→写入时间顺序、native线程、FEATURES而非FULL请求、晚到T0复用、关服lease清零。

矩阵执行于 `cec91b6` 实现基础；之后变化在生产构件/生产存证/生产策略，S0实验执行路径未改。最终源码再执行完整离线构建和17项GameTest。没有将本矩阵包装成新生产墙C2ME运行证明。

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\matrix\results.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\matrix\<run-id>\execution.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\matrix\<run-id>\terrain\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\runtime\
```

## 7. 文件清单与复现

**全部新增/修改的源文件、测试、脚本及报告均以绝对路径列在机器可读清单中**，避免手写清单漏项：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\files.json`

该清单对比 `80f2b24`，覆盖本阶段所有已提交代码及本次脚本/报告变动；每项含类别、完整路径、SHA256。大量矩阵原始文件另有：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\matrix-files.json`

修复后的马道与原公式反例：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*RampSpecificationTest' --tests '*WallRampTest' --tests '*GateCapTest'
```

预期PASS。新的跨cell失败：

```powershell
.\gradlew.bat --offline --dependency-verification=strict test --tests '*GroundExitFallbackTest'
```

预期1 PASS、1 FAIL。完整构建见第1节；单独跑GameTest：

```powershell
.\gradlew.bat --offline --dependency-verification=strict runGameTest
```

预期17项PASS。定向运行会覆盖build内报告，因此完整验收的日志/XML已复制到docs独立保存。

## 8. 操作边界

- 未部署到真实mods，未打开真实存档，未增加旧世界回填。
- 正式生产世界生成仍关闭；仅显式构造生产策略及隔离fixture执行实际方块写入。
- 未同步worldgen getChunk / future join/get，未调用possibleBiomes。扫描中的 `String.join` 是字符串拼接，不是future等待。
- 高Y坐标存三个int；没有把6000高度压入BlockPos.asLong。
- T0/proof是耐久证据，不因缓存缺失静默重建；永久历史不被清空。
- C2ME适配器的锁外订阅、native WORLDGEN锁调用和positionedExecutor保持既有实现。启动pump仍保留，不改用普通tick兜底。
- 本次没有改RoadWeaver或黄金树代码；检查了本地C2ME适配源码与原版命名jar的相关方法，不凭名称猜ABI。
- 未升级任何依赖、未设置代理、未联网补包。

由于整体build仍红且第5节尚未闭合，本报告是明确的阻塞验收记录，不是交付签收。不能输出“S1B完成，请审查”。
