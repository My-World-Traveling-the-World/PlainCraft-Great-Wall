# S1C 标签与 Fort 坐标修订

日期：2026-10-02，Asia/Shanghai。接手基线 `c16fa47`。
**两项已实施；入口合同替换待确认；S1C 未签收，生产 OFF，Tan's BLOCKED。**

## 1. 必须先纠正的规格核实

用户要求先确认 `Ramp.ground` 的“高差<=1”是否有规格依据，若别处存在依据应指出。
S1 主册674..681确实没有这个数值，但补充合同明确有：

- `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-补充合同.md:65`：
  “取最大自然 groundTopY，足印高差不得超过1”。
- 同文件`:110`：
  “FortPlaceholder从门道……完整已证地面取h，足印高差<=1”。

因此“主册没有”成立，“所有冻结合同都没有”不成立。
本轮不把它静默当成无依据代码删除：A与Fort坐标修正仍明确获批，
先独立完成；**Ramp、入口坐标/几何及补充合同原条款不改**。
需用户明确是否以本次“三宽/两格头空/逐列支撑及不侵入corridor”
替换上述两条补充合同，才实施第三项并跑固定seed17。
根足印<=12仍是S1主册1307..1308的选址判据，不与入口通道判据混同。

## 2. 已实施改动

### A：显式分类补齐（995bc8b）

绝对文件：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\tags\block\foundation_support.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\tags\block\replaceable_vegetation.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\CatalogGameTests.java
```

仅苔石新增到support；浆果丛/棕蘑菇/南瓜新增到vegetation。
不改变BlockCatalog分类算法、protected/natural_replaceable资源、
OWNED_VOID编译器、OwnershipGuard或before-state规则。
新增原生测试逐可能状态检查植物分类；三层苔石有原生碰撞/顶面和
T0-backed SurfaceProbe证据，第三层空气/矿石/砾石仍拒绝。
原结构盒、BE、immutable标记仍使探针返回PROTECTED。
五种本轮涉及方块的OWNED_VOID写集均由原编译器拒绝，不因新标签获权。
岩浆块继续protected/support=false，不用lava流体错误归因解释其保护。

### Fort坐标修正（ed36e5b）

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\Fort.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\FortFootprintCoordinatesTest.java
```

旧式局部[-24,24)坐标直接查世界supports键，在平移关隘处全miss。
新式用原frame的Rasterizer.worldBounds枚举世界列，再用localCell过滤
实际48×48局部足印；每列仍查世界(x,W,z)。缺列、PROTECTED/NO_SUPPORT/
UPPER_CENSORED、drop>12或groundTopY>=W均拒绝；非空足印才可通过。
不跳过全足印、不检查无关旋转bbox角部guard；所有构件几何生成代码保持原样。

## 3. 红绿验证

严格离线/JDK21，命令退出码及源码SHA快照均归档。

| 验收项 | 修复前 | 修复后 |
|---|---|---|
| Fort足印查表 | 新增3项JUnit：2失败、1通过 | 3/3通过 |
| 平移/旋转正例 | 第一个非原点case即错拒 | `(206,953)`×0/15/30/45/60/90°通过全足印检查 |
| 强制所有实际列 | 反例测试保留 | 缺列、保护、无支撑、高度删失、drop13、ground>=W均拒绝 |
| 旋转包络角部 | 旧局部查表错误拒绝 | 实际足印以外guard不成为必需列 |
| 新分类/三层苔石 | 新增2项原生测试失败 | 两项通过；植物全状态、第三层反例和保护优先均验证 |
| 原生定向组合 | Catalog/Protection合计10项，8通过2失败 | 10/10通过 |
| 全量JUnit | before仅跑新增3项，不冒充全量 | 353/353，66 suites，failure/error/skipped=0 |
| Gradle命令 | exit1，原红证据保留 | exit0，1m35s |

本轮是全量JUnit＋10个**定向**GameTest，不是全部慢矩阵、完整build或S1C签收。
具体测试断言未在生产修复后改变，没有Disabled/跳case/删断言。

证据绝对目录：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\fort0-tags-coordinates-20261002\`
内 `before/after` 各含execution、source-inputs、JUnit XML、原生XML、run.log。
sourceCommit保留实际测试基线c16fa47、dirty=true，不回写成提交后的虚假干净运行。
995bc8b/ed36e5b是该测试源码分批原样提交。

## 4. 真实入口/全足印/voxels 的边界

**本轮没有启动新的seed17真实运行**，理由是入口合同尚待上述明确确认。

上一轮counterfactual入口190列67:57/68:96/69:31/70:6、max-min=3
保留为“原T0/frame上的改前预检”，不是本轮真实入口统计。
新标签只证明原生加载的分类正确，不证明新recipe/new root已通过入口。
真实新根坐标、新入口统计、voxels数值均NOT_RUN，不能复制旧根作为新实测结果。

另已核实实际调用链，修正“第四层必定在当前seed17发生”的表述：

- `ProductionSession.java:82` 用 `S1TemplateSet.create` 创建施工模板。
- `S1TemplateSet.java:17` 的FORT绑定的是 **FortPlaceholder**，不是完整Fort。
- 旧真实seed17日志`:390`、`:446`、`:506` 均为
  `FortPlaceholder.compile(FortPlaceholder.java:21)`。

完整Fort的坐标bug是真的，已获批准并修复；但目前仅完成其全足印方法的JUnit
实际调用验证，**完整Fort在真实生产管线的全足印结果为NOT_RUN/尚未接入**。
不为证明修复临时切换模板，更不把S3骨架当S1C已完成。
FortPlaceholder自身仍经ComponentAssembly逐实际世界列支撑检查；
下一轮真实运行须按它的真实失败调用层报告。

## 5. recipe 与永久证据

两标签改变BlockCatalog.hash。ProductionInputs.java:23把分类hash纳入terrain
输入；GenerationResourceFingerprint捕获资源及展开标签，完整recipe由这些
输入冻结。Fort源码改变也进入实际mod源哈希；“几何公式不变”不等于
templatesHash/recipeHash数值不变。新hash数值尚未真实冻结，NOT_RUN。

只用于新隔离世界；旧manifest、durable T0、immutable元数据不重写/
迁移/清空。新标签不得让旧永久捕获的immutable标记失效。
正式production保持OFF、Tan's BLOCKED、25/40ms目标与检测不变。

## 6. 可复现命令

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict `
  test runGameTest '-PgreatwallGameTestClasses=CatalogGameTests,ProtectionGameTests' `
  --continue --no-daemon --console=plain
```

before命令仅将test限定为
`--tests local.plaincraft.greatwall.component.FortFootprintCoordinatesTest`，
其余相同；before源码为旧生产＋新增反例测试，不把当前重跑当before。
以后真实运行必须用新runId、固定seed17、不删旧fixture；
本轮不因已知入口门槛仍在而重复浪费真实生成运行。

## 7. 未闭合与 NOT_RUN

- 入口补充合同替换：等待明确确认；Ramp原<=1代码不变。
- S1C-DRAIN-01：独立未闭合，旧STOPPING flights=8/admitted=8仍保留；
  本次普通原生测试退出成功不证明生产排空缺陷已修。
- 新seed17 APPLY/voxels、重进不漏段、成功每chunk成本：NOT_RUN。
- 完整build、全GameTest慢矩阵、Tectonic/C2ME真实墙、完整植被抑制、
  同JVM生命周期验收、S5/24h、真实整包/人工/视觉：NOT_RUN。
- 不部署真实mods、不打开真实存档；不改变红线和保护层。
