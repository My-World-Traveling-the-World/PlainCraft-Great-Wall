# S1C：延迟水文判定与有限填平设计

日期：2026-10-01。源码检查点：`3440d2d`。
状态：**待用户批准的设计，不是实现或签收。生产代码未改，测试全部NOT_RUN。**
生产接线OFF；Tan's Huge Trees继续BLOCKED。只新存档；不部署真实mods、不读取真实存档。

证据分级：

- **【已验证·源码】**：本次读取本地源码确认，索引见§10；不表示运行测试通过。
- **【设计·推断】**：本次拟冻结的行为、接口及算法，批准后才能实现，正确性需测试。
- **【待运行验证】**：尚未实施或执行；不使用PASS。

## 1. 本次变更及不变项

**【设计·推断】**

把“宽窄证不出就整段失败”改为：

```text
REFERENCE规划：
    已认证NARROW/WIDE/SUPPORT_GAP -> 保留原路径
    分类UNRESOLVED -> 携带待施工判定的WaterEvidence，不产生已认证宽度

FEATURES-only PREPARE -> CAPTURE -> 原始T0耐久化
    -> T0测宽决议及施工前缀耐久化
    -> 跨cell出口决议
    -> 原构件编译 + 必要时新增WaterFill子构件
    -> owner计划/填平用途清单耐久化
    -> APPLY(owner) -> LIGHT
```

“现场探测”是读取实际FEATURES完成后的**原始、不可变T0**，不是现场同步getChunk，
也不是在APPLY里一边读实时地形一边决定。CAPTURE不等待其它APPLY。

不变项：

- 原40ms稳态门槛、25ms切片目标及一次性lane-native初始化协议。
- PrefixSummaryIndex的几何弧长、真实300格日程、ordinal、walkY、锚点、折线。
- 探测窗口`[walkY-32,walkY+6]`、drop≤12、三层天然支撑及原收头128/4日程。
- 原城墙/敌楼/烽火台/城门/水门/马道/收头模板文件及构件尺寸、材质、必需净空。
- ChunkPlanCompiler权限拒绝、OwnershipGuard及ChunkWallWriter的owner写入权限。
- 已认证NARROW仍走原WaterGate；已认证WIDE仍由原几何终点截断；SUPPORT_GAP不改成填平。
- 共享数据只发布不可变快照；协调容器使用ConcurrentHashMap；不碰possibleBiomes。

**新授权的精确边界：**只为原UNRESOLVED区域新增T0测宽和水下工程填料。
这不是对缺T0、保护冲突、预算耗尽、超时、过深河床、冻结高度不合法的通用兜底。

## 2. 第一步：前期继续，但不伪造宽度

### 2.1 改动范围

**【已验证·源码】**WallTypes已有`WaterClass.UNRESOLVED`；
WaterEvidence构造器允许宽度0，CanonicalCodec已能编码该枚举和证据。
无需新增DEFERRED枚举，无需改变旧枚举名称/次序或旧构件接口。

**【设计·推断】**仅改变HydrologyClassifier.classify中的分类UNRESOLVED分支：

```text
classify(cursor):
    执行原16方向/中心/两岸/水位判定
    if classification == UNRESOLVED:
        width = 0                       // 未知，不是零宽河，也不是50格
        piers = empty                   // 没有获得窄河桥墩授权
        保留原crossing/span、水位观察、可用banks、inputHash
        completeEvidence(cursor)
        return
    原NARROW -> pier阶段
    原WIDE/SUPPORT_GAP -> 原completeEvidence
```

UNRESOLVED的waterTopY是前期观察基准，不是可直接施工的统一水位。
其channelWidthQ8=0必须与classification一起消费；WaterGate不能收到这份未决证据。
原inputHash继续绑定原采样，不把后续T0哈希写回规划证据。

### 2.2 不能一起吞掉的错误

**【已验证·源码】**同一个HYDROLOGY_UNRESOLVED错误码还用于采样预算耗尽、
REFERENCE质量/河床无法认证，以及施工期的接缝矛盾。
因此不能写`catch(HYDROLOGY_UNRESOLVED) -> FILL`。

**【设计·推断】**

| 原错误 | 本次处理 |
|---|---|
| classify宽窄/水位一致性证不出 | 返回UNRESOLVED待施工 |
| 前期24000列预算耗尽 | 保留显式失败，不扩大预算、不补未知整段 |
| 前期REFERENCE质量/河床不可认证 | 保留显式失败；另行授权才能改变 |
| ROOT_NOT_FOUND、世界边界、取消、40ms超限 | 原错误 |
| 相同身份的证据内容矛盾 | 拒绝，不按谁先到选一份 |

这次解除的是已核实的**分类前置致命**，不承诺所有FAIL_BEFORE_APPLY都消失。
root覆盖缺口也不在本修复中悄悄重写。

### 2.3 缓存和世界配方

**【设计·推断】**生产配方增加语义项`hydrologyPolicy=deferred-t0-fill-v1`，
绑定进GenerationResourceFingerprint/WorldRecipeGuard。
旧规划缓存不能跨新配方复用；新世界重新规划。
不改旧世界，不清空任何永久历史，不为原配方已生成owner回填。
既有NARROW/WIDE的纯算法及构件canonical字节不因新增枚举而改变。

## 3. 第二步：T0测宽与只读采集

### 3.1 决议单元、读域、写域

**【设计·推断】**

- 决议core固定为已有全球弧长cell：`[k*64,(k+1)*64)`格，使用Q8。
- 纵向只读上下文为core两端各160格，裁到合法路线范围；不使用当前加载边作边界。
- 测宽横向每侧最长58格，固定16方向，两侧各连续8格干岸才认证岸。
- 最多三个中心；完整湿run按原“入口附近4格/中点/出口附近4格”规则，重复中心去重。
- 没找到完整端点的长湿run只用有限上下文；不无限追到下一座岛。
- 能认证NARROW并调用原WaterGate的run必须完整、沿墙跨度≤128、有入/出岸及桥墩证据。
- **写域仅是本core中最终接受的墙体足印**，绝不是整个只读域或58格射线。

完整短run在每个相邻cell的160格上下文里均完整可见，中心选择不随请求cell改变。
其共享水门身份为原`SiteId(WATER_GATE,globalCrossing.fromQ8)`，中心cell发布整件，
其它owner重放整件后裁剪；不得每个cell各造一个水门。

长run/上下文截断不会伪装成合法NARROW；不能用“横向窄”绕过原128格跨度及支撑合同。

### 3.2 T0采集接口不能混同写权限

**【已验证·源码】**CoveragePlanner.realizationOwners主要按墙旁±16格列举owner，
不覆盖全部58格测宽射线。TerrainSnapshotService同时拒绝同owner的不同Coverage，
并禁止已完成APPLY后的缺页重捕获。

**【设计·推断】**新增HydrologyCapturePlanner，先生成确定的读列清单：

```text
readPlan(cell, frozenPlans):
    将core±160路线按原supercover格列化
    先规划LINE阶段的确定读列/owner，不根据噪声水位猜T0的run端点
    LINE按预算预留、FEATURES halo、原T0捕获/force的顺序执行
    将T0转为不持有TerrainTile/ColumnView的不可变ColumnObservation和哈希
    释放LINE阶段不再消费的页pin
    从这些实际纵向观察确定湿run和固定中心
    TRANSECTS阶段枚举其16方向两侧58格射线
    列清单按(x,z)排序去重，每列绑定同一站点链计算的W
    按相同预留/FEATURES/T0协议执行，已捕获owner只读原耐久页
    发布观测/哈希；后续填料编译需要完整状态时在自己的pin下重读耐久T0
```

两阶段不能让释放pin后的ColumnView继续引用原页；
也不能把原噪声run的三个中心当成实际T0端点变化后的完整读域。
LINE/TRANSECTS的稳态列观察生成本身也必须按25ms目标/40ms检测切片，
不能先在一个慢批次中算完所有观察，再仅给廉价的分类归约计时。
读域候选允许包含最终没有实际用到的射线；实际逻辑采样仍计入有界预算。
每次FEATURES租约仍≤81；原256 capture-owner上限、T0/compiled字节预算不增大。
预先计量不足则异步等待容量或显式CAPACITY，不边分配边扩大。
对同一owner的canonical Coverage仍使用原CoveragePlanner.forChunk，不因射线临时扩大。
若现有canonical窗口不能提供所需已授权高度，按缺证据拒绝；
不另造第二份T0，也不复用含已写墙体的实时页。

读取射线只要求FEATURES及其原生写半径halo，不要求LIGHT/FULL，也不要求墙APPLY。
生产beforeFeatures仍只依赖纯规划；不新增“FEATURES等T0、T0等FEATURES”的环。
新增水文依赖接在beforeLight的锁外异步链；不改C2ME已核实的锁前挂接点。

### 3.3 单列观察与宽度证据

**【设计·推断】**

SurfaceProbe原方法不改，负责实际墙基/桥墩的窗口、保护、天然支撑认证。
新增T0WaterClassifier负责横向证据，不把ProbeKind.WATER直接等价为NARROW。
它从SnapshotColumnView读取实际状态，在同一探测窗口内生成：

```text
ColumnObservation:
    W、实际可见水面、河床可见性、DRY/WET/UNKNOWN
    窗口截断原因、是否普通水/水生植物/薄冰/水淹固体
    T0列哈希、保护观察
```

侧向列超出探测能力可为UNKNOWN，不能当DRY岸；真正应存在的T0页缺失则T0_MISSING。
NO_SUPPORT的ProbeResult不能借其bedTopY占位值当作已认证河床。
保护范围内的侧向列可只读，但不能据其人工实体认证天然岸；
实际填料足印触及保护则禁止写入。

沿用原Q8方向、supercover、cellWidth、双侧extent/lower、水位差≤1、
两岸8格和50格分界公式，**不以一条“左右25格都没岸”射线替代原宽水域证明**。
本方案使用新增T0实现，不先重构原REFERENCE分类器；两者通过共同输入对照测试防分叉。

```text
classifyT0(run):
    固定中心和16方向逐原语推进；保持25ms目标/40ms硬检测
    每个侧向格：
        无T0/损坏 -> 失败
        观察UNKNOWN -> 本方向不能认证，不造假岸
        WET -> 更新extent、连续湿下界、水位一致性
        DRY -> 按原投影公式累积连续干岸长度
    用原NARROW / allWide逻辑归约
    完整、宽<50、跨度≤128、桥墩合法 -> NARROW证据
    满足原WIDE证明 -> WIDE证据
    证明窄但跨度/桥墩不合法 -> 原SUPPORT_GAP
    无水 -> DRY
    宽窄仍无法认证 -> FILL_REQUIRED
```

逻辑列预算沿用≤24000，不新增不受限射线/方向。
实现将“射线在固定58格内未认证岸”与“资源预算或时间门槛失败”分开：
前者可FILL_REQUIRED，后者保留显式失败，不能靠填平掩盖资源超限。

## 4. 第三步：分派及永久终点

### 4.1 分派表

**【设计·推断】**

| 原规划状态 | 施工行为 |
|---|---|
| 原NARROW | 原WaterGate，原T0支撑/水位/薄冰规则；失败仍原断口，不改填平 |
| 原WIDE | 原PrefixSummaryIndex几何终点及收头，不测到窄后继续 |
| 原SUPPORT_GAP | 原断口/收头，不借新策略消除支撑失败 |
| 原UNRESOLVED，T0为DRY | 原墙，填平清单为空 |
| 原UNRESOLVED，T0认证NARROW | 完整新T0 WaterEvidence交给原WaterGate |
| 原UNRESOLVED，T0认证WIDE | 耐久施工终点、原收头，后续禁止墙APPLY |
| 原UNRESOLVED，T0仍证不出宽窄 | FILL_REQUIRED；对合法墙基生成WaterFill |
| FILL_REQUIRED，但填料不合法 | 显式局部断口/原出口回退；fort0无合法根仍失败 |

**未认证宽度0不传给WaterGate，也不拿来判断“<50所以窄”。**
既有站点序号不重排；水域相交站点继续服从原水文避让。
不为消除碰撞放宽ChunkPlanCompiler，不能省略某个冲突坐标。

### 4.2 必需的第二层前缀

**【已验证·源码】**现有PrefixSummaryIndex只归约SegmentGeometry的terminal。
RealizationPlanner里的局部break本身不能永久约束所有后续段。

**【设计·推断】**保留纯几何PrefixSummaryIndex，新增
ConstructionHydrologyPrefixIndex作为施工前缀，不让纯规划反向依赖T0：

```text
requireThrough(targetCell):
    找最新有效耐久施工checkpoint
    从其下一cell按绝对S顺序推进到targetCell
    每cell：
        只读获取原纯规划；没有UNRESOLVED时不为测宽额外生成FEATURES/T0
        有UNRESOLVED才通过锁外链获取其原始FEATURES/T0
        原已认证水文沿原路径
        只对UNRESOLVED生成固定cell水文决议
        决议force成功后归约前缀、force前缀，再允许future完成
        若出现永久terminal：封存，后续不再请求施工
    冷远跳需前缀推进，不承诺O(1)
```

只有同一cell同一决议可发布；请求顺序、当前加载侧、缓存冷热不能决定FILL/WIDE。
施工checkpoint及水文决议是永久施工证据，不是可丢弃站点缓存。
缺失/CRC损坏/recipe不符不能静默清空成“尚未入海”。
各步释放T0工作集，前缀只携带不可变哈希/状态；不把全部历史T0 pin在内存。
调用者只依赖future；取消一个观察视图不结束内部producer，不提前释放pin。
冷远跳到未决水域之后可能必须额外生成此前未决片的FEATURES/T0；
这是本次延后判定的新增成本，尚未实测，不能沿用纯几何远跳的耗时作证明。

### 4.3 新增晚确认WIDE的截断语义（必须随本设计批准）

**【设计·推断】**

- 原规划已认证WIDE的终点位置一字不改。
- 对延后水文，永久终点取**第一个按S封存为WIDE的决议core内第一湿列**的S。
- 不能把晚确认的WIDE倒推到已经封存FILL的较早core，不能回滚已APPLY或玩家方块。
- 因而一个未决长水域可先出现有限填平片，再在后续首次认证WIDE处收头。
  这是本次“未知可填平”策略的明确新增行为，不冒称与旧前置拒绝完全等价。
- 如湿run≤128且完整可见，各相关cell使用同一完整run/中心/射线；
  第一相交core即可一致确认WIDE，不因邻cell加载先后改变入水终点。
- 一旦施工前缀封存terminal，之后即使T0/缓存重新读取也不能撤销；
  派生内存缓存可重建，耐久terminal和已封存决议不能静默重算成另一种政策。

需要此语义是因为新信息产生在施工期；不能同时允许早期未知片APPLY，
又要求未来宽水域证明反向改写这些已经永久写出的片。

GroundExitResolver的inspect、cap、最终resolve全部消费相同ConstructionView，
禁止inspect判FILL、最终resolve却使用另一份未决水文。
出口足印/收头/水门触及的前后cell也必须已完成水文前缀，之后才封存出口决议。
inspect阶段必须执行填料可行性preflight，将保护/支撑/原净空碰撞形成的break
加入共享出口输入；不能在最终resolve才首次发现这些break再自行发布新边界。
填料形成的人工表面不作为“天然地面出口”的证据；原128/4退让和自然入口判定不改。

## 5. WaterFill的具体构造

### 5.1 身份、写集和追溯

**【设计·推断】**

新增`component.WaterFill`，作为原WALL的施工子构件，不注册新SiteKind、
不修改旧模板或ComponentAssembly的优先级switch。
它输出不可变FillPatch，合入对应cell的**同一份**WALL ComponentVoxels，
不是以同SiteId发布两个不同hash组件，也不使用特殊wallId冒充另一座墙。

```text
WaterFillInput:
    原cell WALL SiteId、已接受buildIntervals、站点链
    同一份T0列、真实SurfaceProbe支撑、ConstructionView
    原WallSection输出、原确定性材质解析输入

FillPatch:
    cell、waterDecisionHash
    各填料Point3i、原T0 token、目标BlockSpec、天然根证据
    每格purpose=DEFERRED_WATER_FILL
    maskHash、t0ReadSetHash、patchHash
```

坐标始终三个int；不存BlockPos.asLong。
新增填料是SOLID、FOUNDATION、原WALL身份/优先级、required=true、walkable=false；
重合的原SOLID保持原part/required/walkable/材质，整件验证后另查填料清单全部留存；
不生成FLUID/OWNED_VOID、BE、篝火、驻军点或独立马道。
使用对应材质族的现有确定性无重力实心基础材质，不使用砂/沙砾作落空填料。
外观不强制异色；用途标记存在耐久清单中，不给每块方块新增BE。

### 5.2 精确范围

**【设计·推断】**

每个世界列由原`WallSection.contract`得到S、lateralCell、W，不重新旋转成另一条墙：

```text
横向/纵向：
    S在本cell接受的buildInterval及FILL决议片内
    排除已接受Gate的刚性端面、其它构件替换区及所有收头exclusion
    只在原墙的下部梯形足印内：
        Y < W-8     -> lateralCell in [-5,5)
        W-8 <= Y < W-1 -> lateralCell in [-4,4)
    不超过10格最大横宽，不进入原水门主通航孔

竖向：
    h = 实际已认证河床groundTopY
    t = 实际已认证waterTopY
    支撑必须来自原T0，drop=max(0,(W-20)-h) <= 12
    要求h >= W-32，t <= W-1
    填料Y在[h,t)，最大竖深不超过原窗口
    h-1/h-2/h-3的天然支撑保持原认证，不挖掉河床
```

只填墙体经过的水下横截面，不填射线、岸外、整个河床或整条河。
**填料不止水面一层**：从已证河床连续填到水面下，避免“石板悬在水上”。
如果水面已侵入原必需步道/头部空间，或河床超出窗口/drop>12，
本方案不能用新增填料修改walkY/头部净空/探测窗口，走原安全断口。

实际目标格只允许：

- T0普通water及其流动等级；按原T0 token留before-state。
- 同水柱内部原已允许替换的空气/植物，用于避免留下无根空洞。
- 已有同墙SOLID目标，保持原意图和材质，仅增加用途追踪；不重复发布同位置。

不填水淹的天然/结构固体，不替换BE、受保护方块、厚冰/薄冰，
不新增“为了填平可以解冻”的授权。普通薄冰仍仅原WaterGate主通航四列可以恢复源水。

### 5.3 构造及合并伪代码

**【设计·推断】**

```text
compileFill(baseWall, input):
    if baseWall为空:
        返回NO_WALL，不生成孤立填料
    对所有候选列先计数、计量，取得预算pin后才分配数组/集合
    对每个候选列按(x,z)稳定顺序：
        原SurfaceProbe必须为WATER且有真实水面、河床、三层支撑
        检查drop、竖域、保护、薄冰和冻结W
        对Y=h..t-1：
            超出该高度的原梯形足印 -> 不属于墙线，不填
            不在accepted interval/处于已决议排除区 -> 不填
            必须有原始T0 token
            T0非普通水/允许空气植物且不是合法已有同墙SOLID -> 拒绝该填料片
            原base在该格为SOLID -> 保持原意图，记录水填料用途
            原base在该格为OWNED_VOID/FLUID -> 拒绝该填料片，不放宽优先级、不删净空
            原base没有该格 -> 生成FOUNDATION SOLID
    同一个cell先完整判定再发布；失败不能留下半份patch
    合并为一份WALL ComponentVoxels：
        原实体/路线/状态不改；新增填料格纳入bounds及canonical hash
        原bounds内施工，不扩大模板包络
        天然根只由原T0支撑认证，不由待写填料自证
        SupportGraph再次验证整件；不输出无根水下碎片
    返回整件 + FillPatch用途清单
```

混合曲坡导致局部填料不可行时，preflight按原列断口规则建立break，
**先做共享出口/截断决议后**编整个cell；
最终resolve只按同输入/决议重放，意外不一致则失败，不重新选择已封存边界。
不能发布patch后再按owner随手去掉坏列。
合法普通墙在WATER支撑上的基础本已由ComponentAssembly输出SOLID；
这些重合格复用原写集即可，新增类主要补充“明确填料用途/受限合并/证书”。
不能为“必须有新方块数量”而给原墙外侧多加一层。

### 5.4 为什么不需要放宽保护层

**【已验证·源码】**

- ComponentAssembly接受合法WATER支撑，并为非AIR/WATER部件输出SOLID。
- ChunkPlanCompiler会拒绝同SiteId的两种realization及无权限净空擦除。
- OwnershipGuard和writer按T0 before-state、owner、proof/marker检查，完整预检后才写。

**【设计·推断】**WaterFill沿用这一写集/预检机制；
不向世界直接setBlock、不修改compiler.winner、不扩大owner写权限。
新用途清单只绑定证明，不能授予额外写入权限。

## 6. 接口及持久化设计

**【设计·推断】**以下是实施接口草案，均尚未写入src；类型为不可变record/enum。

```java
// terrain/DeferredHydrologyTypes.java 中的公共嵌套类型
enum Outcome { DRY, NARROW, WIDE, FILL_REQUIRED, SUPPORT_GAP }
record DecisionKey(CellKey cell, String policyHash) {}
record WaterCellDecision(
    DecisionKey key, ArcInterval core, List<ResolvedRun> runs,
    OptionalLong terminalSQ8, String readSetHash, String hash) {}
record ConstructionPrefix(
    long beforeArcCell, long certifiedUntilSQ8, OptionalLong terminalSQ8,
    String previousHash, String proofHash) {}
record ConstructionView(
    ArcInterval covered, List<WaterCellDecision> decisions,
    OptionalLong terminalSQ8, String hash) {}
record ResolvedRun(
    ArcInterval range, Outcome outcome, Optional<WaterEvidence> narrow,
    String reason, String proofHash) {}
```

构造规则：宽窄未知不提供narrow；WIDE必须有真实宽证明；
每run与core相交，完整NARROW允许跨core引用共享crossing但不改变所属水门身份；
decision输入包括recipe/dimension、绝对S、站点W哈希、原UNRESOLVED证据、
读列坐标及T0/保护哈希、固定策略版本。拒绝null、越界及重复矛盾。
跨线程不得保存mutable cursor、live ChunkAccess或未关闭的oracle在这些record里。

```java
// public接口；Cursor只在生产者线程内，不跨线程发布
HydrologyCapturePlanner.ReadPlan plan(CellKey cell, PlanWindow plans);
HydrologyCapturePlanner.ReadPlan planTransects(
    HydrologyCapturePlanner.ReadPlan linePlan, List<ColumnObservation> lineObservations);

T0WaterClassifier.Cursor begin(
    HydrologyCapturePlanner.ReadPlan plan,
    List<ColumnObservation> columns,
    DimensionBounds bounds, int columnBudget);
WorkState advance(T0WaterClassifier.Cursor cursor, int primitiveLimit);
WaterCellDecision finish(T0WaterClassifier.Cursor cursor);

PlanLease<ConstructionView> ConstructionHydrologyPrefixIndex.requireThrough(long arcCell);
CompletableFuture<Void> ConstructionHydrologyPrefixIndex.drained();
void ConstructionHydrologyPrefixIndex.close();

WaterFill.Result WaterFill.compile(WaterFill.Input input);
WaterFill.Check WaterFill.preflight(WaterFill.Input input);

// 原方法保留；以下新增重载在生产链消费相同施工水文快照
GroundExitDecision.Slice RealizationPlanner.inspect(Input input, ConstructionView water);
GroundExitDecision.CapVote RealizationPlanner.probeCap(
    Input input, GroundExitDecision.Edge edge, ConstructionView water);
RealizationCell RealizationPlanner.resolve(
    Input input, List<GroundExitDecision> exits, ConstructionView water);
```

原无UNRESOLVED测试及旧签收构件继续走原方法；
新生产调用必须显式提供覆盖其读域/构件/收头足印的ConstructionView，不能缺省跳过新门槛。
旧签收NARROW/WIDE的生产几何也必须对照验证无输出变化；不能因“旧入口还在”宣称生产等价。

新增`persistence/ConstructionWaterStore`及`ConstructionWaterCodec`：

- `water-decisions/<arcCell>.bin`：完整决议、版本、recipe、T0读集/哈希、CRC/SHA。
- `water-prefix/<checkpoint>.bin`：顺序前缀、前驱哈希、永久terminal。
- `water-owner/<owner>.bin`：owner writePlan hash、决议/前缀证书引用、FillPatch及其具体坐标/用途。
- 原子写+force；相同身份内容不同拒绝，不采用last-writer-wins。
- owner绑定件先force，原exit proof及write-plan再force，最后才进入APPLY。
- 重进时marker.planHash必须与绑定件及原write-plan吻合；
  完整marker只读验证，不把玩家方块重捕获作新T0、不再次填平。
- 已有耐久prefix但决议丢失/CRC坏、已有绑定件但引用证据坏，显式失败，不静默清零。
- 未force成功前future不宣称ready；公开观察视图取消不代表producer/drain完成。
- 填料坐标可通过用途索引查询`DEFERRED_WATER_FILL`及其决议、before/after token；
  不将自然地形或普通墙基笼统算成填平，也不把读域所有水算成已填。

永久水文证据与可重建SegmentCacheStore分开目录/恢复政策。
只改新世界生成协议，不追加任何旧世界补墙/回填迁移。

## 7. 生产异步链与关闭

**【设计·推断】**

```text
beforeFeatures:
    原纯规划future（可带UNRESOLVED） -> FEATURES
    不等ConstructionView；startup pump不等T0/初始化完成

beforeLight / outside-lock:
    原计划 -> 有限cell清单 -> prior compiled reservation
    -> require施工水文前缀（覆盖全部潜在出口/水门/收头读域）
       其producer只申请纯计划、FEATURES、原T0，绝不申请APPLY
    -> GroundExitResolver（inspect/cap都借相同ConstructionView）
    -> realization.resolve（同决议，不在resolve重判宽度）
    -> compile、耐久用途绑定+水文/出口证明+write-plan
    -> engine.exclusive(owner)内仅预检/执行既定写集
    -> LIGHT
```

施工前缀producer不进入等待中的owner APPLY队列、不再次申请调用者已占满的compiled reservation。
使用同一已预留arena或其计量子pin；逐步释放T0，禁止持有整份terrain预算再递归申请同预算。
CAPTURE的exclusive只为原生快照操作；等待FEATURES/T0/IO发生在锁外future链。
未来任何线程同步getChunk、future.join/get均不被此协议授权。

SERVER_STOPPING关闭前缀/决议新请求，保留永久文件；
内部capture/IO/consumer排空后释放pin，session变更丢弃旧内存协调状态。
producer错误向原失败通道传播，不用catch吞掉变成空水文列表。

## 8. 预计文件变更范围

**【设计·推断】**根目录均为
`D:\云游四海-Mod源码\plaincraft-great-wall\`。

| 路径（相对上述绝对根） | 变更 |
|---|---|
| src/main/java/local/plaincraft/greatwall/terrain/HydrologyClassifier.java | 只改变分类UNRESOLVED的前置异常；其它错误不吞 |
| src/main/java/local/plaincraft/greatwall/lifecycle/GenerationResourceFingerprint.java | 新策略指纹 |
| src/main/java/local/plaincraft/greatwall/terrain/DeferredHydrologyTypes.java | 新增不可变协议类型 |
| src/main/java/local/plaincraft/greatwall/terrain/HydrologyCapturePlanner.java | 新增固定T0读列规划 |
| src/main/java/local/plaincraft/greatwall/terrain/T0WaterClassifier.java | 新增施工测宽 |
| src/main/java/local/plaincraft/greatwall/terrain/ConstructionHydrologyPrefixIndex.java | 新增施工顺序前缀/终点 |
| src/main/java/local/plaincraft/greatwall/component/WaterFill.java | 新增受限填料子构件及同墙合并 |
| src/main/java/local/plaincraft/greatwall/persistence/ConstructionWaterCodec.java | 新增有界/可校验编码 |
| src/main/java/local/plaincraft/greatwall/persistence/ConstructionWaterStore.java | 新增永久证据与owner绑定 |
| src/main/java/local/plaincraft/greatwall/terrain/RealizationPlanner.java | 新增消费水文决议的重载及填料接入 |
| src/main/java/local/plaincraft/greatwall/generation/ProductionWallPipeline.java | 新增锁外前缀/读域准备/耐久证明门槛 |
| src/main/java/local/plaincraft/greatwall/terrain/TerrainSnapshotService.java | 新增owner水文绑定验证调用，不改T0捕获真值 |
| src/test/java/local/plaincraft/greatwall/terrain/HydrologyTest.java | 新授权行为及保留旧策略反例 |
| src/test/java/local/plaincraft/greatwall/terrain/DeferredHydrologyTest.java | 新增 |
| src/test/java/local/plaincraft/greatwall/terrain/T0WaterClassifierTest.java | 新增 |
| src/test/java/local/plaincraft/greatwall/terrain/ConstructionHydrologyPrefixIndexTest.java | 新增 |
| src/test/java/local/plaincraft/greatwall/component/WaterFillTest.java | 新增 |
| src/test/java/local/plaincraft/greatwall/persistence/ConstructionWaterStoreTest.java | 新增 |
| src/gametest/java/local/plaincraft/greatwall/gametest/DeferredHydrologyGameTests.java | 新增原生集成测试 |
| docs/S1C-延迟水文与有限填平验收.md | 实施后新增，当前不存在，不预写PASS |

原WallSection、WaterGate、Gate、Ramp、EndCap、EnemyTower、BeaconTower、
ComponentAssembly、SurfaceProbe、ChunkPlanCompiler、OwnershipGuard、ChunkWallWriter不修改。
如执行中发现必须改变它们才能成立，停止说明原因并申请新授权，不偷偷扩大本设计。

实现拓扑：协议/codec -> 前期返回/指纹 -> T0读域/测宽 -> store/施工前缀
-> WaterFill -> RealizationPlanner重载 -> pipeline/重进验证 -> 集成测试/签收。
仍按每1～3个类提交，失败、反例、before/after日志保留。

## 9. 验证计划及完成标准

**【待运行验证】全部NOT_RUN。批准之后才写代码、编译和执行。**

### 9.1 定向红绿与JUnit

| 测试方法 | 期望 |
|---|---|
| returnsDeferredEvidenceForUnequalWaterLevels | 原水位3格差反例在新策略返回UNRESOLVED，不假报NARROW |
| legacyClassifierRejectsSameUnequalWaterLevelWitness | 保存旧公式/原失败证据，对照同一输入，不能删除矛盾历史 |
| budgetExhaustionIsNotFillAuthorization | 原24000耗尽仍失败 |
| missingReferenceBedIsNotDeferredWidth | 原REFERENCE质量/河床错误仍失败 |
| narrowAndWideGoldenEvidenceRemainIdentical | 旧49/50/51及斜向认证结果/序列化不变 |
| frozenT0TransectsMatchReferenceForCommonInputs | 同字段的解析输入，16方向公式一致 |
| unknownSideColumnCannotBecomeDryBank | UNKNOWN不冒充岸，宽度结果保守 |
| width49CrossesAnd50Or51Terminates | 合格输入按Q8阈值分派 |
| fracturedIslandProducesFillDecisionNotFakeNarrow | 碎岛/水位不一致进入FILL_REQUIRED |
| lateWideStopsAllFollowingCellsBeforeApply | 远侧先请求也先归约前缀，不写终点后墙 |
| fixedCoreWideNeverRewritesEarlierSealedFill | 明确验证§4.3新增截断语义 |
| reverseRandomConcurrentRequestsPublishSameDecisions | 同T0决议、终点、填料blob逐字节相同 |
| crossingAcrossCellsHasOneWaterGateIdentity | 不重复水门、不重置编号 |
| fillHasNaturalRootsAndNoSurfaceOnlyPlate | 连续填至河床，天然根来自T0 |
| fillNeverEscapesAcceptedTaperedFootprint | 十宽/八宽截面、S区间及Gate端面外不填 |
| drop12CanFillAndDrop13StillBreaks | 不以填料重算drop消红 |
| protectedIceBlockEntityAndWaterloggedSolidAreNotFillable | 原保护不放宽 |
| baseVoidIsNeverRemovedToMakeFillPass | 不绕过净空拒绝 |
| fillIsOneWallComponentWithTraceableMask | 同SiteId只有一种hash，用途可反查 |
| cancelKeepsPinsUntilEnteredProducerDrains | 取消不提前释放/关闭arena |
| reservationPrecedesT0AndFillAllocation | 预留在分配前，容量不足不超配 |
| terminalOrFillJournalDamageFailsClosed | 永久历史损坏不静默清空 |
| completeMarkerVerificationDoesNotOverwritePlayerEdits | 重进只读证明，不改玩家方块 |

原HydrologyTest中的“旧分类必须抛异常”断言按用户新授权改为新语义断言，
同时保留独立旧策略反例和首次原日志；这不是关闭测试或把保护层改绿。

### 9.2 原生GameTest

| 测试方法 | 实际必须验证 |
|---|---|
| deferredNarrowUsesOriginalWaterGateAndSourceWater | 原ProtoChunk/T0水、原水门、源水位置及通航口 |
| deferredWideSealsPrefixAndEndCap | 两owner反序请求；终点后零墙写集 |
| deferredIslandsFillOnlyWallFootprint | 碎岛实际BlockState；十宽以内、读域射线水不变 |
| curvedSlopeFillRespectsContractAndClearance | 弯坡三W/drop组合、完整必需路线/头部净空 |
| fillAcrossOneTwoFourOwnersReplaysSameWholeComponent | owner切片一致，不随加载侧换决议 |
| fillCannotEraseNeighborGateOrWaterGate | 原compiler拒绝保持，碰撞生产者不偷删 |
| protectionConflictPreflightWritesZeroBlocks | before-state被改或BE占位，全预检不半写 |
| provenanceSurvivesSaveReloadWithoutRefilling | 用途证据/marker保存重读一致 |
| delayedFeaturesNeverWaitForNeighborApply | 慢FEATURES、反序LIGHT、锁哨兵；无循环依赖 |
| stopDuringWaterCaptureOrPersistDrainsAndResets | 原session结束不留静态终点或错误pin |

旧完整HYDRO 72项及相关RealizationPlanner/生产pipeline/跨cell出口回归必须跑。
其它慢矩阵是否继承，需实施后的实际diff、路径隔离与等价实测决定并报告；
**本设计不预先批准跳过任何受影响矩阵，也不继承旧测试为新策略PASS。**

### 9.3 构建与现实边界

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build runGameTest `
    --continue --rerun-tasks --no-build-cache --console=plain --no-daemon
```

该命令可能包含旧慢矩阵；实施后先核对任务图，不在这里承诺10分钟或擅自排除。
新增/定向测试先跑红绿，最终全量JUnit和相关原生GameTest结果分别列出；
不把分片/继承写成单进程全绿。

完成本修复仍不等于S1C签收：

- 未重新摸底固定0..39就不更新总体水文通过率。
- 未跑真实vanilla/Tectonic/C2ME生成、保存/重进、连续性及每chunk成本，不签真实墙。
- 不因解析/GameTest绿启用生产；生产保持OFF直至另行验收授权。
- Tan's保持BLOCKED；6000高度、非默认minY、Golden、人工实走及S5/24小时继续NOT_RUN。

## 10. 本次源码核对索引

**【已验证·源码】**以下以检查点`3440d2d`为准；修改后重新定位。
每行相对路径的绝对根为`D:\云游四海-Mod源码\plaincraft-great-wall\`。

| 事实 | 文件:行 |
|---|---|
| 已有UNRESOLVED，WaterEvidence宽度非负及不可变字段 | src/main/java/local/plaincraft/greatwall/model/WallTypes.java:46、:202 |
| 原16方向两岸宽度归约及分类异常 | src/main/java/local/plaincraft/greatwall/terrain/HydrologyClassifier.java:186、:218 |
| 同码预算/REFERENCE错误，不能catch全部 | src/main/java/local/plaincraft/greatwall/terrain/HydrologyClassifier.java:280、:290 |
| 原单列窗口、drop上限 | src/main/java/local/plaincraft/greatwall/generation/SurfaceProbe.java:22、:25、:49 |
| 原T0缺页不回退到实时世界 | src/main/java/local/plaincraft/greatwall/terrain/SnapshotColumnView.java:46 |
| 原构件读owner枚举不足以默认覆盖测宽射线 | src/main/java/local/plaincraft/greatwall/generation/CoveragePlanner.java:56 |
| 原同owner只认同一capture窗口 | src/main/java/local/plaincraft/greatwall/terrain/TerrainSnapshotService.java:81 |
| 原FEATURES only采集及persist后APPLY | src/main/java/local/plaincraft/greatwall/generation/ProductionWallPipeline.java:155、:251 |
| 原施工水文只对NARROW建水门 | src/main/java/local/plaincraft/greatwall/terrain/RealizationPlanner.java:157 |
| 原几何前缀只归约geometry terminal | src/main/java/local/plaincraft/greatwall/planning/PrefixSummaryIndex.java:81 |
| 原WATER支撑、SOLID意图、天然根 | src/main/java/local/plaincraft/greatwall/component/ComponentAssembly.java:155、:180、:183 |
| 原墙下部梯形横截面 | src/main/java/local/plaincraft/greatwall/component/WallSection.java:81 |
| 原同SiteId两种realization及净空冲突拒绝 | src/main/java/local/plaincraft/greatwall/generation/ChunkPlanCompiler.java:41、:108 |
| 原水门<50、span≤128、水位/桥墩条件 | src/main/java/local/plaincraft/greatwall/component/WaterGate.java:26 |
| 原owner/完整proof预检后写入，完整marker只读 | src/main/java/local/plaincraft/greatwall/generation/ChunkWallWriter.java:24 |
| 原授权范围内before/after与玩家保护 | src/main/java/local/plaincraft/greatwall/generation/OwnershipGuard.java:32 |
| 既有水文canonical包含class/width/span/hash | src/main/java/local/plaincraft/greatwall/geometry/CanonicalCodec.java:349 |

**停止点：本次只新增本文档。未改生产/测试源码、构建文件、依赖版本或运行开关；
未执行编译、JUnit、GameTest或真实世界生成。等待用户确认本设计，尤其§4.3晚确认终点语义，
以及“填平不授予更深支撑/挖冰/改净空权限”的边界，然后才能实施。**
