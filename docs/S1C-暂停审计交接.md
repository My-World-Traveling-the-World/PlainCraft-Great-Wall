# S1C 暂停审计交接

日期：2026-10-01。已提交代码检查点：`715428f`。
状态：**PAUSED_FOR_AUDIT；S1C未签收；S2～S5尚未实施。**
用户要求先梳理再工作，本次暂停代码编写、编译和运行测试。
无本任务仍在运行的Java测试进程。正式production OFF，Tan's BLOCKED。

## 1. 最新需求，以此为准

1. 水文分类的水位不一致/证不出宽窄按WIDE收头，不实施延迟填平或深基础。
   sample的预算耗尽/REFERENCE质量或河床错误仍抛原异常。
2. 只避让地表上的已注册结构；地下和天空结构不在新增避让范围。
   普通地形不按结构处理，玩家方块仍按原before-state/tombstone协议保护。
3. 规划期补廉价水提示，借鉴RoadWeaver群系标签和高度比较经验；
   不复制道路/桥梁/隧道算法，不共享live世界RandomState，不放宽40ms。
4. 用户已要求暂停梳理，交由审计确认下面的技术分叉。

## 2. 真正做完的部分

| 项 | 状态/范围 | 原件 |
|---|---|---|
| 用户两处水文改动 | 已承接入库，未重做；commit092311b | HydrologyClassifier.java/HydrologyTest.java及原diff |
| 该改动上的全量JUnit | 343/343、exit0、1m17s | evidence/s1c/wide-closeout-20261001-151922/ |
| 新增反例和下游终点回归 | 四组41/41、exit0、34s；不是新增后全量 | evidence/s1c/wide-regression-20261001-152423/ |
| 隔离客户端入口和候选包 | prepareWallPreview exit0、14s；客户端未启动 | commit715428f；evidence/s1c/preview-package-20261001-153315/ |
| 完整build | 主动中止、exit1；未通过，不是新代码回归失败 | evidence/s1c/wide-full-build-20261001-153829/ |
| 当前结构草稿编译/测试 | NOT_RUN | §3 |
| 新策略真实墙/重进/成本 | NOT_RUN | 没有新证书，旧失败不改成通过 |
| RoadWeaver外部构建能力预查 | 只跑离线strict help、exit0、9s；不是S2编译/测试 | evidence/s2/road-build-preflight-20261001-154736/ |
| S2/S3/S4/S5 | NOT_STARTED | 无这些阶段的新实现或签收 |

### 2.1 全量build中断的准确说明

2026-10-01当地15:38:29开始，16:12:57结束，34m27s。
源码冻结715428f，调用`gradlew.bat --offline --dependency-verification=strict build --no-daemon --console=plain`。
追加结构避让后发现旧保护不能兑现“不能压在结构上方”；
我主动终止经身份检查的本次GameTest PID2076，未终止用户客户端或其它Java进程。
interruption.json记录INTERRUPTED_FOR_NEW_REQUIREMENT；execution.json实际exit1，不能改写成0。

完整游戏测试71个方法未结束，不能用部分方法/矩阵冒称全绿。
原日志确有以下汇总：曲坡墙576/576、马道入口192/192、
CONFLICT128/128、BOUNDARY256/256；这些只是各自子矩阵。
日志里的HYDRO2项、CURVED_SITES1项是定向回归，不是完整72/64。
没有完整相邻547项总汇总，也没有装饰960项完整汇总。

归档gametest-results/results.xml的LastWriteTimeUtc为
2026-09-30T16:38:05.9780025Z，早于本次运行，**是旧XML，不能作为本次通过凭证**。
本次build在runGameTest失败后没有执行末尾test任务；
新增两个方法后的最终全量JUnit不能引用当前build内复制的旧XML。
所有原件保留，审计时优先看inputs/execution/interruption和带时间戳的日志。
整份日志目录的拷贝还含过去运行的轮转.gz日志，不能把它们计作本轮测试；
原diff/log中的尾随空白保留，不为通过git whitespace检查而改写取证原件。

### 2.2 候选包不是完整成品

build/libs/plaincraft-great-wall-0.1.0-s1c-preview.1.jar
SHA256：6308ef8a7e1f0c6bc68c62c2b9aa0f3f520075ff89c16e05f227cd7725f0a03d。
这是715428f内容的隔离候选；没有包含后来的未提交结构草稿或新COARSE水提示。
只完成准备及限定ZIP检查，未启动客户端，未跑人工实走。
追加要求闭合前不能称“符合地表结构避让的新验收”或“S5完整模组”。
不部署真实mods，不导入真实存档，不因此开启正式production。

## 3. 未提交草稿，不能拿来验收

完整根目录：D:\云游四海-Mod源码\plaincraft-great-wall\

- src/main/java/local/plaincraft/greatwall/terrain/BlockCatalog.java：
  新增冻结registeredStructures及只读getter。
- src/main/java/local/plaincraft/greatwall/generation/RegisteredStructureGuard.java：
  新文件；从已准备FEATURES的start/reference派生全高度XZ排除盒。
- src/main/java/local/plaincraft/greatwall/terrain/TerrainSnapshotService.java：
  合入上述排除盒，forecast计入新增盒上界。

**明确问题：草稿针对所有已注册结构投影全维度高度，尚未做“只地表”的筛选。
这不符合用户最后收窄的范围，不准合并为完成版，也没有编译/测试PASS。**
仅捕获/禁建草稿，没有RidgePlanner绕行、RootSearch结构重选或新结构GameTest。
用户提出水检测要求后没有修改CpuTerrainOracle或RootSearch代码。

审计快照含三份完整源文件、tracked-source.diff及各SHA：
docs/evidence/s1c/structure-draft-paused-20261001/。
工作区原草稿保留；没有回滚用户代码或把草稿偷偷提交成生产修复。

## 4. 水检测：已核实的事实与错误假设

### 4.1 biomeSource不是阻塞

【已验证·源码】CpuTerrainOracle.FrozenInput已有BiomeSource，构造校验非空；
实例字段final biomeSource在:80从frozen赋值，freeze在:100传入实际generator.getBiomeSource。
其RandomState是私有、每session每lane初始化，不能复制RoadWeaver直接取live状态的做法。
本次没有运行标签查询测试；“可接入”是接口/字段事实，不是性能PASS。

### 4.2 RoadWeaver粗路径不是两幅真实heightmap

【已验证·源码】RoadWeaver：

| 文件:行 | 实际行为 |
|---|---|
| pathfinding/cache/TerrainSamplingCache.java:80 | quartXZ、quartY16查询noise biome |
| 同文件:81 | IS_RIVER/IS_OCEAN/IS_DEEP_OCEAN标签 |
| 同文件:64、:95 | height和oceanFloor都调用同一个fastSampler.sampleHeight |
| pathfinding/terrain/region/CoarseTerrainTileSampler.java:230、:231 | height来自批量粗采样；oceanFloor=height |
| pathfinding/terrain/SurfaceWaterClassifier.java:13 | 两个已有数值的比较公式本身是O(1) |
| pathfinding/cache/FastHeightSampler.java:53 | 冷粗高度逐cellHeight向下做密度查询，不是一次O(1)求真实高度 |
| pathfinding/cache/NoiseChunkHeightSampler.java:116、:119、:137 | 真正CPU双高度来自NoiseChunk逐高度/状态评估，再可缓存查询 |

粗路径把同一粗高度传入公式时，第一个`worldSurface>oceanFloor`项自然为false，
起作用的是waterBiome和seaLevel这一近似分支。
这不否定道路的快查经验，但不能把它说成“两个真实WG高度冷算都是O(1)”。
准确高度缓存查询可以很快；首次产生高度的成本不能隐藏。
Road原方法的waterSurfaceY还保留其桥梁seaLevel坐标约定；
长城waterTopY是流体上表面脚面，不能逐字照搬并造成一格偏差。

### 4.3 长城当前COARSE确实没有水

【已验证·源码】CpuTerrainOracle.coarse(:169)只算initialDensityWithoutJaggedness，
不填waterTop；complete(:239)只补地表biome key，没有标签水判定。
RidgePlanner在:118用COARSE，在:132读取waterTop作为wet评分。
因此补COARSE避水提示有实际落点；但提示必须保持COARSE质量，
不能变成窄/宽最终证书或T0before-state。
RidgePlanner边界高度在:72仍为REFERENCE，不能连同水评分一起随手换掉。

### 4.4 RootSearch不仅是水检测

【已验证·源码】RootSearch.read(:185)全部请求REFERENCE；
acceptHeight(:196)拒绝非REFERENCE；ProductionInputs(:42)从原cursor取三层supportEvidence。
直接把read改COARSE：

1. 不改质量检查时，候选会被全部拒绝；
2. 顺手放宽质量检查但保留foundation时，没有三层证据，认证仍失败；
3. 把foundation一起删掉，则改变已冻结承重合同。

【待审计】可以把候选/轴初筛改快查，并保留“最终根点承重认证”；
但用户“REFERENCE只用于Hydrology/T0”的表述与这项原认证需要明确协调。
不能用改名称“最终”来偷换授权，也不能拿COARSE高度充当三层承重证据。
若完全移除RootSearch的REFERENCE，需明确新的承重证据来源与bootstrap依赖图，
证明没有引入FEATURES等待recipe、recipe又等FEATURES的环。

## 5. 地表结构：尚需钉死的判据

【已验证·源码】ProtectionSnapshot原结构证据只含bounding boxes/starts/references，
没有“地表/地下/天空”的语义分类字段。
T0的WORLD_SURFACE_WG在FEATURES之后可能包含结构屋顶，不能直接把屋顶当自然地表。
站点W-20也不能无条件当实际自然地表，因为W是平滑/平台冻结后的合同。

【待审计】需确认地表结构的判断依据，以及证据生成时机：
采用结构生成前的原生地表证据、冻结自然oracle地表证据，还是数据声明的分类。
不能用任意固定海拔阈值区分天空/山地，也不能靠mod名称或结构ID黑名单代替判断。
地下/天空排除后，原registered结构盒如何与现有保护证据协调也要明确：
不擅自删玩家/BE/不可变块保护，不因新XZ规则把普通地形列成障碍。

还需查根点特例：fort0若与地表结构重叠，现RealizationPlanner的空fort0会ROOT_NOT_FOUND；
如何在冻recipe之前重选根、或在授权范围内安全留缺口继续，尚未实现/验证。
不把其它站点的SUPPRESSED行为假称能自动覆盖这个特例。

## 6. 审计建议顺序

1. 先确认COARSE允许是“群系/海平面粗水提示”还是必须提供两幅真实WG高度。
   若必须真实双高度，冷生成成本如何切片/预采样/计量，不能用缓存命中时间替代。
2. 确认Root初筛与最终承重认证边界，REFERENCE到底在哪些认证阶段允许保留。
3. 明确“只地表”的结构分类及证据来源，处理fort0与结构冲突的生命周期。
4. 审查三文件草稿，明确不能沿用全高度投影；先写地面/地下/天空反例再完善接线。
5. 协议确定后先定向红绿；再全量JUnit及相关GameTest、真实生成/重进/成本。
6. S1C真正闭合后再按全局拓扑8～13继续，不预写S2～S5完成或发布。

## 7. 我的执行偏差

此前在“字段存在”和“算法完整可接入”之间没有先把契约说透，
结构草稿先做了全高度投影，随后用户明确只地表，这份草稿就需要收窄。
水检测也不能仅凭比较公式就承诺高度生成O(1)/不会超40ms。
本次暂停就是为了避免继续在未冻结的语义上写代码；
不把34分钟中止的全量构建包装成签收，也不把预览Jar包装成完整可玩成品。

## 8. 复核入口

工程：D:\云游四海-Mod源码\plaincraft-great-wall\
RoadWeaver：D:\云游四海-Mod源码\roadweaver\common\src\main\java\net\shiroha233\roadweaver\

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
git log -3 --oneline
git status --short
git diff -- src/main/java/local/plaincraft/greatwall/terrain/BlockCatalog.java `
  src/main/java/local/plaincraft/greatwall/terrain/TerrainSnapshotService.java
```

未提交新RegisteredStructureGuard需另外直接读文件；git diff默认不显示untracked内容。
测试开工命令仍JDK21、--offline --dependency-verification=strict，
但**当前按用户指示暂停，不自动继续构建、启动fixture或改代码**。
