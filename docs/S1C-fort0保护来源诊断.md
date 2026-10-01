# S1C fort0保护来源诊断

日期：2026-10-01。接手基线：792f967，初始工作区干净。
状态：**根因来源已实测；修复未实施；BLOCKED_PENDING_AUTHORIZATION。**
正式production OFF，Tan's BLOCKED。墙仍未进入APPLY，不能签收S1C或推进后续生产接线。

## 1. 先读交接及commit复核结论

已完整读取S1C-交接-GPT.md，保留其188/188入口PROTECTED、五分支统计、
煤矿石反例和此前20余次运行。该文件内部基线f8fd057是历史记录；
本次实际接手HEAD为用户指定的792f967。

**不执行整提交git revert 74ccddb/f8fd057，理由可核：**

- 74ccddb的generation-step跳过确实不可靠，但f8fd057已经删除surfaceStep，
  恢复所有未解析引用加入保守读取盒。当前代码不存在该跳过分支。
- f8fd057的引用准备在ProductionWallPipeline.capture确实已接入，
  当前运行能拿到+4/-2chunk的真实start，不是“完全没走到改的调用点”。
- 整体回退f8fd057会重新丢失已正确解析的结构；回退74ccddb还会影响它包含的其它改动。
  当前保留锁外异步引用准备和严格保护，不跳过Jigsaw，不扩大owner权限。
- leaseRadiusChunks的解释不能拿blockStateWriteRadius当最大reference距离；
  本轮实际仍radius1，远引用由额外有限租约提供，81总上限不改。
  该辅助方法注释仍有旧描述，后续清理应公开，不据注释当运行事实。

## 2. 本轮唯一真实运行

只跑一次固定vanilla seed17，新隔离目录；
没有换seed、删水、调内存或Ramp容忍度，没有修改BlockCatalog/资源标签。
诊断开关只输出原算法的来源，不改变保护决定。

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runServerSmoke `
  -PsmokeProfile=vanilla -PsmokeRunId=s1c-protection-source-20261001-224755 `
  -Ps1cRealWall=true -Ps1cSeed=17 -Ps1cRepresentativeBlocks=100 `
  -Ps1cProtectionTrace=true --no-daemon --console=plain
```

起始输入记录UTC14:47:55，对应当地22:47:55；compileJava成功；
Gradle实际BUILD FAILED，8m5s，exit1，结束UTC14:56:02.8563516。
100只是诊断入口阈值，不是1200代表墙验收；实际仍申请600个遍历owner。

证据根：
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\s1c-protection-source-20261001-224755\

## 3. 保守盒路径问题：本轮确切答案

**【已验证·运行】本轮没有保守盒加入路径。不是旧T0，不是S0 snapshot，不是漏了引用准备。**

从日志独立重新统计（independent-audit.json）：

| 观测 | 数量 |
|---|---:|
| MISSING_PREPARED / MISSING_START / INVALID_START | 0 |
| NEW_CAPTURE元数据发布 | 87 |
| DURABLE_READ旧元数据读回 | 0 |
| RESOLVED_REFERENCE来源 | 108 |
| 有效OWNER_START来源 | 2 |
| 日志中APPLY进入/owner进度 | 0 / 0 |

session-STOPPING.json同时记录PRODUCTION_PREPARE_REFERENCES=65、
PRODUCTION_PREPARE_FEATURES=87，且没有PRODUCTION_APPLY_ENTER。
预测保护列表由ProductionSession构造传空，本次首次捕获不读旧coverage。

### 3.1 两个引用解析出的真实盒

源日志latest.log:134、:135，当地22:53:27：

| owner | 引用 | 类型 | 原生整体bbox（半开） | pieces |
|---|---|---|---|---:|
| [-14,72] | [-14,70] | MineshaftStructure | X[-286,-134)、Y[-44,-23)、Z[1071,1197) | 149 |
| [-14,72] | [-14,76] | JigsawStructure | X[-289,-157)、Y[51,101)、Z[1141,1271) | 103 |

latest.log:136随后输出NEW_CAPTURE及完整盒列表：

    read = [-224,-208) × [43,113) × [1152,1168)
    boxes = 实际矿井整体盒/各piece + 实际Jigsaw整体盒/各piece

read打印出来只是canonical读取窗口；不能把这行中的read字段当成boxes成员。
本次不存在MISSING_*日志或fallback=字段。
旧交接中的“structureBox命中坐标落在保守读取盒”不能证明本轮仍使用该保守盒，
因为同一坐标也位于更大的真实Jigsaw盒内。

**注意身份边界：日志记录class，没有记录结构registry key。
不能仅凭JigsawStructure就声称已确认minecraft:village_plains。**
103个piece和地表高度说明需要保护的真实生成结构范围；
确切类型ID待后续根点结构筛选入口补证，不使用名称推测作过滤。

### 3.2 根点确实在真实结构保护范围里

本轮FIXTURE_RECIPE_READY的冻结root：

    Point3i(-185,65,1199)
    Axis(exQ30=536913779, ezQ30=929862946)

该点落在上述Jigsaw整体盒内；不是仅在远处引用start的chunk内。
fort0旋转后的读列包络：
X[-220,-150)、Y[52,93)、Z[1164,1234)。
不只是整体bbox相交，多份真实piece盒也与fort0包络相交，
例如X[-198,-190)、Y[64,78)、Z[1200,1218)。
不能删除整体盒或只绕过某个冲突坐标让fort0硬写进去。

### 3.3 本轮support计数的范围

latest.log:331，完整包络4900个候选列：

    SUPPORTED=370
    NO_SUPPORT=21
    PROTECTED=4509

这是70×70旋转外包络的统计，**不是此前188个中央入口列的重新计数**；
外包络存在不被模板实际逆栅格使用的列，不能拿21个NO_SUPPORT否定交接的入口结论。
本次只是证明fort0看到真实保护范围；入口继续在严格Ramp.ground中失败。
必须移开/拒绝冲突的根候选，不能放宽入口坡度、支撑比率或保护条件。

矿井Y最高为-24实体格，与该fort0/probe窗口不相交。
本轮问题没有理由通过跳过地下step解决；Jigsaw是实际近地结构盒。

## 4. 完整进入链（源码）

以下行号对应本次diagnostic源码，绝对根：
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\

1. generation/ProductionWallPipeline.java:267：
   锁外withReferenceChunks，补实际reference chunk。
2. 同文件:268：
   原TerrainSnapshotService.capture，非S0 laboratory snapshot。
3. terrain/TerrainSnapshotService.java:123：
   旧metadata读回分支，trace=DURABLE_READ；本轮零命中。
4. 同文件:145：
   实际首次capture调用ProtectionSnapshot.structureBounds；
   ProtectionSnapshot.java:119打印每引用究竟resolved还是fallback。
5. 同文件:156：
   元数据force保存后NEW_CAPTURE打印；本轮87次。
6. ProductionWallPipeline.java:236：
   所有本cell捕获metadata.protectedStructures合为不可变protectedBounds输入。
7. terrain/RealizationPlanner.java:413：
   probe读取原列/合并盒；:419打印fort0全包络supportKinds和相交盒。
8. component/FortPlaceholder.java:19：
   原严格Ramp.ground，不可行时ROOT_NOT_FOUND，无APPLY。

WallStageCoordinator.snapshot是S0实验页路径，不返回CaptureMetadata；
生产构造持有ProductionWallPipeline，beforeLight转发给它。
本轮日志线程与函数来源也符合生产capture，不需要凭猜测修改S0。

## 5. 矿石分类：必须先取得的授权

**【已验证·源码 + 原生诊断】**

BlockCatalog.java:46仅按foundation_support判natural；
:47单独验证support；:51又以!natural推导protected。
因此“未列为支撑”的普通矿石/砾石会在probe整窗口预检时被PROTECTED拒绝。
此前真机日志已实测coal_ore、gravel；本次新增原生诊断也核实coal_ore、
deepslate_coal_ore、gravel的support=false、protected=true。

修改分类/资源是**S1C地表分类协议变更**，不是S1A纯几何或S1B模板尺寸/楼梯修改。
但它会改变BlockCatalog.hash → terrainHash → recipeHash，
所以不属于“无行为影响的小修”，也不自动继承原验收。
用户明确要求先批准，本次没有改BlockCatalog或标签。

拟请求的最小范围：

- 将自然地形可由自墙SOLID替换，与是否能做三层支撑，分成两个明确标签/判据。
- 先只允许显式原版矿石及本次实测砾石，不开放任意非空气/任意mod方块。
- 矿石是否可作为三层支撑不在本次自动增加；不修改foundation_support清单。
- 砾石的gravity/support=false保持，不把软床变成承重证明。
- 显式protected、BE、已注册结构盒、tombstone、before-state仍优先。
- 不授权OWNED_VOID挖矿石或砾石；ChunkPlanCompiler原负空间保护不改。
- 新配方仅新世界；旧测试世界不删永久T0/manifest后重试。

**这项分类修复不能单独让当前root通过，因为真实Jigsaw保护仍必须保留。**

## 6. 真正修复方向及需要确认的分叉

**【推断·设计，未实施】**

在recipe冻结前，给候选根点补**真实结构相交筛选**，
剔除与地表已注册结构相交的完整fort0+入口足印；
继续在同seed、同800～1500环带及既有确定性候选/预算内挑下一个合法根。
不能在T0/APPLY之后移动已冻结根、回写已生成owner，不能换seed凑绿。

结构检查依赖必须在锁外异步准备STRUCTURE_STARTS/STRUCTURE_REFERENCES，
不能先等FEATURES，因为FEATURES本身可能等未冻结recipe；
还需在源查询下确认不会涉及其它APPLY或同步chunk获取。
不能按generation step将村庄/Jigsaw分类为地下跳过；
地表/地下/天空依据和完整足印需另有证据，未知不能默默放行。
本轮并未完成上述新接口/依赖图/根候选恢复逻辑。

这个新前置筛选会改变原seed17选中的root；它是同seed在新算法版本下的确定性选择，
不是重跑至随机选到好seed。若不允许改变根选择协议，
当前既定root与“不得破坏结构”冲突，不能通过取消保护达到voxels>0。

须先确认两项：**允许新增冻结前结构筛选及拒绝候选；
允许§5的自然可替换分类与承重分离。**批准后再实施和定向回归。

## 7. 本轮验证及残余风险

| 项 | 结果 |
|---|---|
| 固定seed17真实来源取证 | 已完成，失败exit1，未修复 |
| strict offline JDK21 compileJava | PASS；不等于完整build |
| ProtectionSnapshotTest | 2/2 PASS |
| ProtectionGameTests + CatalogGameTests | 8/8 PASS，exit0，30秒 |
| 新原生对照测试 | actual registered jigsaw的resolved/fallback区分；当前矿石/砾石保护反例 |
| 修复后真实墙voxels>0 | NOT_RUN / 未达成 |
| 真机正常关服 | FAIL：初始fort0 fatal后watchdog |
| S1C重进、1200墙、Tectonic/C2ME、每chunk成本 | NOT_RUN |
| 全量JUnit/build/GameTest慢矩阵 | 本轮NOT_RUN |
| Tan's | BLOCKED |
| S2～S5新代码/生产接线/压测 | 本轮未进入；现有骨架不代签 |

原生GameTest中的Jigsaw是注册minecraft:village_plains，两个detached native piece仅用于
复现真实bbox保护分支，**不是seed17村庄的完整生成或结构保留验收**。
当前矿石诊断测试验证的是旧政策仍拒绝，需要授权改变后把功能预期更新为新政策，
同时保留本轮旧事实日志/反例；不能据这项PASS声称矿石bug已修。

真实运行在22:55:01先报fort0 fatal，22:55:02输出Stopping server；
22:56:02watchdog另生成crash。后者thread dump没有Server thread条目，
不能据此断言它正在等待某个保存future或具体IO；详细退出原因待查。
本轮不把它归因采样40ms或另一个JVM，尚未修理错误退出的排空/保存问题。
保存两份crash report、session-STOPPING/events及完整日志，没有关闭watchdog。
未生成real-wall-progress.json/真实墙证书，不伪造voxels字段；
零APPLY由日志及STOPPING barrier计数共同约束。
STOPPING时terrain/compiled reserved=0，但flights/admitted仍8；
这不是完整session排空认证，必须单独处理，不写“所有future已正常排空”。

## 8. 文件、证据及停止点

代码提交：`9cb2b22`（三个生产类的默认关闭来源trace及隔离启动参数）；
测试提交：`92c93de`（两个原生诊断见证）。
原运行凭证仍保留其真实输入基线792f967+dirty source SHA，不改成后来的提交号。

本批新增/修改（均在工程绝对根）：

- src/main/java/local/plaincraft/greatwall/generation/ProtectionSnapshot.java：来源trace，策略不改。
- src/main/java/local/plaincraft/greatwall/terrain/TerrainSnapshotService.java：NEW_CAPTURE/DURABLE_READ来源trace。
- src/main/java/local/plaincraft/greatwall/terrain/RealizationPlanner.java：fort0候选列统计，仅diagnostic开关。
- gradle/smoke-tests.gradle：仅隔离realWall允许s1cProtectionTrace。
- src/gametest/java/local/plaincraft/greatwall/gametest/ProtectionGameTests.java：resolved与fallback原生对照。
- src/gametest/java/local/plaincraft/greatwall/gametest/CatalogGameTests.java：当前天然矿石/砾石分类诊断。
- docs/S1C-fort0保护来源诊断.md及本批证据。

来源trace默认关闭，输出时间/坐标不进入几何/保护hash；
但是诊断源码变化会使实际source fingerprint改变，两个世界使用不同recipe是正常事实，
不能声称所有recipeHash相同。seed/root/原规则不变，本次也实际观察到相同root坐标。
计时含诊断输出，不作为正常生产性能证书。

原件入口：s1c-protection-source-20261001-224755/inputs.json、execution.json、
independent-audit.json、sha256.json、logs/latest.log、crash-reports/、
world/greatwall/diagnostics/session-STOPPING.json。
prior-isolated-runs保留交接两轮原日志，不覆盖它们，不把旧日志冒充新trace。
定向验证根：
docs/evidence/s1c/protection-source-native-20261001-230339/，
包含execution.json、JUnit XML、GameTest XML及完整日志。

此处停止等待最小合同修订授权，不改用户要求的S1A/S1B构件，
不通过放宽保护、入口容忍、预算或换seed来硬推APPLY。
