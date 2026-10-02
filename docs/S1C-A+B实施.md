# S1C A+B 入口合同实施

日期：2026-10-02，Asia/Shanghai。接手基线 `a276e09`。
**PAUSED_FOR_AUDIT：用户2026-10-02要求停下交审计；生产 OFF，Tan's BLOCKED，S1C 未签收。**
最新结果与审计检查点见 `docs/S1C-A+B暂停审计交接.md`。没有继续实施owner修复。

## 1. 已实施

- `GroundEntranceContract.inspect` 是 RootSearch、Ramp 及四类入口消费者的单一判定。
- 根筛选新增独立的旋转 REFERENCE 入口采样阶段；未借用未旋转48足印的数据。
  保持四环带×36角度、原排序/每批前8、48足印高度差12、
  150000列硬预算、25ms目标/40ms检测、锁外结构筛选和无FEATURES反向依赖。
- 施工输入显式携带不可变原始列证据；`ComponentComposer` 和两种 Fort 的
  输入重建均保留该证据，不退回只凭support高度猜头空。
- 按自然外侧接入认证声明入口面、脚面差≤1、两格实际头空、
  `[W-32,W)`、SUPPORTED、drop≤12。水/保护/缺页/不连续均拒绝。
- `ComponentInput`原构造器仅保留解析fixture兼容，用显式
  `ANALYTIC_FIXTURE`证据；正式Realization用`DURABLE_T0`。
  根预检使用`NATURAL_REFERENCE`，不假称已获得永久T0。
- `groundEntrancePolicy`和实际源哈希绑定新recipe；不迁移旧永久世界数据。

## 2. 合同替换与几何边界

`docs/S1B-补充合同.md` 已显式注明：
“本条文替换2026-09-29版第65/110行的足印高差≤1”。
旧高台反例仍保留，并继续 GREEN。

没有改原入口平台、马道、庭院或梯级几何公式。因此已声明的平门道仍必须
在自然峰上方，脚面仍取其最大认证groundTopY；B改变的是外侧可走接入认证，
不是把低于岩峰的平门道用OWNED_VOID挖出来。
此前只读图提出`lowerFeetY=68`只证明高度图里的自然路径，不证明
原有平门道在70高岩峰处可写。这个边界在实施时明确修正，历史统计保留。

EnemyTower的既有二宽底门不改尺寸；Ramp/Gate/Fort保持至少三宽的声明入口。
不靠局部格编号连续性推断旋转宽度；检查实际世界列。
自然外侧列须一对一匹配，不能复用一个外侧体素伪造多个独立接入列。

## 3. 实现提交

| 提交 | 内容 |
|---|---|
| bad0bc6 | 共享纯入口合同与反例 |
| 0da7a2b | ComponentInput显式证据闭包、Realization施工列、重建保留 |
| deed883 | Ramp、FortPlaceholder、完整Fort消费证书 |
| 07f52c8 | Gate/EnemyTower显式消费同一证书 |
| 2085d7f | RootSearch独立旋转采样/预算/入口审计及资源指纹 |
| 8f49e24 | 原生碰撞入口测试及测试入口登记 |

实现按依赖闭包分小提交，测试针对完整组合源码状态；没有宣称每个中间提交
各自独立跑过全部测试。

## 4. 已跑验证与失败保留

证据根：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\entrance-ab-20261002\`

| 批次 | 结果/边界 |
|---|---|
| first-targeted | 93项，2红；旋转格编号错误；原断言未降低 |
| portal-raster-repair | 22/22，exit0；旧塔朝向和原根排序恢复 |
| full-unit-native | 全量JUnit360/360；命令exit1，因为新GameTest漏登记，native NOT_RUN |
| native-after-registration | 24/24原生，Gradle exit0；包括原32高度、56个塔/关隘drop/朝向组合、原cap/水门/保护与新入口 |
| apron-before | 新实际模板guard+apron反例红，旧纵向覆盖测试绿 |
| apron-after | 13项JUnit绿；native4项中3红，扩大读域触发原32MiB预算；未改预算或断言 |
| distinct-apron-before | 外部2列复用为3接入的反例红；记录原算法缺口 |
| apron-matching-after | 全量JUnit362/362；native4项中1红，扩大cap预读触发原64MiB地形预算 |
| apron-final | 12/12定向JUnit、4/4原生，exit0；只保留固定构件实际guard/apron修正及原station读窗口；原64/32MiB预算/pin/replay断言未改 |
| fringe-owner-before | 新边缘owner反例tick超时，原3项管线测试通过；不是同一个missing-owner异常的复现，测试未收尾 |

原生取证脚本另有时间验证错误：UTC文件时间与自动转换为本地的JSON时间比较，
wrapper在Gradle exit0后exit1。已用`ConvertFrom-Json -DateKind String`和
`DateTimeOffset.UtcDateTime`核对实际新XML24/24。脚本错误记录保留，不当native失败，
也没有用旧XML冒充新结果；旧XML单独标为`NOT_CURRENT`。

## 5. 第一轮真实seed17

源码 `8f49e24`，新隔离目录
`entrance-ab17-20261002-122705`，命令退出码1，耗时7m21s。

| 项目 | 实际 |
|---|---|
| 根 | `(206,67,953)`，原seed17未换 |
| recipe | `343b565dae30b3f8c6e96504b72427f6f6afc1ef57dc73c5efed7a66eb0bf61c` |
| root列数 | 39272，≤150000 |
| root maxBatch | 25.9201ms，over40ms=0；不是所有批次≤25ms的声明 |
| 施工frame | centerQ8(52736,243968)、tx247565951、tz1044812234、W87 |
| 当时入口结果 | 证书lowerFeetY70，从自然外部69接入；不是旧建议的68 |
| 当时证书局限 | 3路径复用2外列，后来新增红例并修一对一匹配；该旧证书不作最终三宽验收 |
| 后续失败层 | `T0_MISSING: ground-exit apron not captured` |
| APPLY/voxels | FAIL_BEFORE_APPLY；voxels=null，没有生成progress凭证 |
| 关闭 | 后续watchdog；STOPPING flights=8/admitted=8仍为独立S1C-DRAIN-01 |

原始日志、crash、manifest/cache/evidence/diagnostics已复制到本批证据目录，
旧world未删除或回填。完整归档在git入库时再次核验。

## 6. apron 实现缺口修订

计划站点盒来自角点投影，不含Rasterizer guard；实际组件盒含guard，
出口证明还检查该实际盒外一列。捕获只借规划盒，会在chunk边界缺页。

`CoveragePlanner.realizationOwners` 改为用已有模板 `localBounds` 与同一
`Rasterizer.worldBounds`，再预声明一列天然apron；所有依赖仍锁外准备。
不改SiteScheduler（S1A）几何/日程、128/4退让、owner写权限或
256 capture-owner限。缺页仍明确失败，不补空气或同步拉chunk。

早期把所有潜在cap预读域一并加宽触发原native预算，已撤销这项非必要拓展，
保留原station±16窗口，仅修已实测的固定站点读盒不足。
入口证书工作集分配前预留；判定结束释放临时表，仅保留返回证书所需预算。

## 7. 可复现命令

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --no-daemon --console=plain
.\gradlew.bat --offline --dependency-verification=strict runGameTest `
  '-PgreatwallGameTestClasses=ComponentGameTests,GroundEntranceContractGameTests,CatalogGameTests,ProtectionGameTests' `
  --no-daemon --console=plain
.\gradlew.bat --offline --dependency-verification=strict runServerSmoke `
  '-PsmokeProfile=vanilla' "-PsmokeRunId=entrance-ab17-$(Get-Date -Format yyyyMMdd-HHmmss)" `
  '-Ps1cRealWall=true' '-Ps1cSeed=17' '-Ps1cRepresentativeBlocks=100' '-Ps1cProtectionTrace=true' `
  --no-daemon --console=plain
```

真实命令必须新runId；100是隔离诊断门槛，不能冒充默认1200或完整S1C签收。

## 8. 仍未完成

源码3a8d891第二轮seed17已运行29m46s、exit1：入口lower70的一对一接入
通过后，在PRODUCTION_EXIT_READY=4之后失败`owner not in realization evidence`。
voxels=null，没有progress写墙凭证；这是FAIL_BEFORE_APPLY，不是NOT_RUN。
完整新入口ground分布尚NOT_RUN，不将匹配路径/旧统计代替它。
完整build/全GameTest慢矩阵、重进不漏段、成功每chunk成本、
vanilla/Tectonic/C2ME全代表运行、植被全部家族、同JVM生命周期、
6000高/非默认minY/Golden、S0百轮本次、S5/24h/人工/视觉均NOT_RUN。
S1C-DRAIN-01未闭合，正式生产OFF、Tan's BLOCKED，S2-S5未推进。
