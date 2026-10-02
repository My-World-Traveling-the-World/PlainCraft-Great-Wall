# S1B 补充合同

日期：2026-09-29。基线：80f2b24。补足实施规格没有编码值的细节及已获授权的物理坐标修订；不改已签收 S1A 的日程、预算、折线几何或依赖版本。构件包络的同步修正逐项公开，不能将旧包络继续用于新模板。

## 第一批：栅格与破败

- `Rasterizer.supercover` 按闭线段覆盖，包括线段端点恰在网格线时接触的两侧格、角点接触的四格；结果按 y,z,x 排序。单次遍历轴向格差之和不得超过 262144，否则拒绝，避免不受限分配。构件最大 256 owner 限制仍独立生效。
- 变换使用整数/BigInteger 中间积；逆变换只对世界方块中心执行一次 Z 镜像。输出高度只加一次 W。
- `DecayField` 的格点噪声 domain 固定为 `decay-noise`，ordered fields 为 `(scaleBlocks, knotIndex)`；部分破败垛口/灯的整组 domain 为 `decay-group`，fields 为 `(floorDiv(globalSQ8,1024), componentHash)`。微缺口仍严格使用规格的 `decay-detail`。
- 维护影响用最近站点距离，输入顺序不影响结果。超出 48 格影响严格为零。所有方法无 Minecraft 依赖，没有全局可变缓存。

## 构件输入闭包

- 公共 `SiteKind` 原来只有七类站点，无法为 `WallSection` 分配无冲突身份。追加 `WALL`，ordinal 是非负 64 格全球弧长 cell 编号；不复用 RAMP 身份。原枚举名称、站点编号及 S1A canonical 编码保持不变。
- `ComponentInput.supports` 键冻结为 `(世界x, 该列合同W, 世界z)`；不将 groundTopY 放进键，不允许构件从世界补查。构件任一需要的列缺失时返回显式空构件。
- `ComponentAssembly` 是包内、单次编译独占的缓冲器，集中实现模板共同算法；局部候选经世界列中心逆栅格化，完整支撑判定后再输出不可变数据。其局部 HashMap 不跨线程发布。
- S1B 的 `SurfaceProbe`、`SupportGraph` 提前到模板前实现，二者只依赖已冻结公共类型，没有倒置依赖。

## 原始规格冲突记录（批准前的历史）

以下保留最初停止时的事实和反例，不表示当前仍在等待批准。用户随后已授权采用本文件下一节的修订；H=1/2/3/32 原红项现已通过。当前 S1B 的跨 cell 出口回退阻塞另见 `S1B-跨单元出口阻塞.md`。

- 原 S1 册第 674～681 行：H=1/2 的底入口与顶平台覆盖同一个 `x=-28,z=6` 列，顶平台地板占据底入口必需的两格头部空间。详见 `RampSpecificationTest` 的纯坐标证明和 `docs/S1B.md`。
- H=32 底入口脚面可到 `W-32`，其支撑方块在 `W-33`，低于声明的 localBounds。已有 T0 读取 guard 覆盖该高度，但模板输出与 SupportGraph 之间没有表达“只读天然脚面支撑”的已冻结合同。候选实现未擅自扩张 localBounds。
- `ComponentAssembly` 现在明确拒绝两个必需路径对同一体素分别要求地板与净空的情况；不能靠删除必需地板、仅验证少量端点来假装整个平台存在。普通基础填充必须让位于已声明地板和净空。
- H=3 的候选实现也未通过：当前平台统一保留三格净空，与上层平台相交。入口规格只要求至少两格，因此 **这项不构成已证明的规格无解**；需要在获准修订短坡布局后继续完成碰撞验证，不将其混同于 H=1/2 的坐标矛盾。

## 2026-09-29 获准修订：马道低高差和最低地板

用户已批准修复 H=1/2/3/32，并授权以后物理矛盾采用公开记录的最小修订；以上反例保留为历史证据，不删测试。

- H=1..3 不再套重叠的 U 形两跑平台公式。底入口仍 X[-29,-26)、Z[5,8)、脚面 lower；第 j 阶 X=-26+j、Z[5,8)、脚面 lower+j+1，j=0..H-1；顶平台 X[-26+H,-23+H)、Z[4,11)、脚面 upper。上下平台水平不重叠，XZ 整体仍在原足印内。H>=4 保留原两跑坐标。
- Ramp 输出 localBounds 的 minY 从 -32 明确改为 -33，只为 lower=W-32 的底入口地板 W-33 提供输出包络。支撑 feet 搜索范围仍为 [W-32,W+6]；T0 guard 仍 W-35；合法高度仍1..32；不向下寻找更深地面。普通高度按 probe 的 h 截断，不凭包络额外填一层。
- 强化测试逐一覆盖 H=1..32，检查必需路线、两格净空和上下平台全部地板，不能只因某条端点路径可达就漏掉平台。

## 敌楼与烽火台的离散几何修订

- 敌楼最后一级脚面 W+5，三格净空占 y=W+5..W+7，原 maxYExclusive=W+7 少一格。输出包络显式改为 W+8；实体屋面高度不增加，仅包含已有楼梯所需空气。
- 敌楼西侧宽4外立面门口保持不变，W 高度的内部连廊从西跑最后一级所在 z=-2 接中央，不能用横向楼板封掉西跑尚低于 W 的梯井。屋顶对应第二圈尚未升到 W+5 的梯井先扣除。
- 离散旋转可能让指定局部格没有对应世界列。路线锚点只允许落到距其理论中心1.5格内、同脚面高度、实际有地板和两格净空的步道格；没有合格格则拒绝，不凭空生成路。
- 功能方块与实体意图做一对一匹配：按局部坐标稳定顺序挑选未占用的实际功能格，必要时在同高度、同1.5格界限的非必需空气格上绑定，须已有支撑；未选中的重复栅格功能块删除。篝火正下方同步绑定 hay_block，四个localFireIndex保留，不靠数量近似。所有修改仍在完整组件内、写世界前决议。

## 生产写入的数据闭包补齐

- 原 `ChunkWritePlan` 没有 coverageHash、gateRevision、spawnSlots、realizedStates，无法凭现有 `ChunkWallWriter.apply(owner,plan,mode)` 生成规格要求的完整 marker。新增不可变 `WriteMetadata` 并作为计划字段保留；旧构造器委托 EMPTY，只用于旧测试/接口兼容。生产 coordinator 必须在持久化前调用 `ChunkPlanCompiler.bindCoverage` 绑定 owner 的耐久 coverageHash。
- 原 `ComponentVoxels` 没有破败状态，不能从残块高度猜玩法状态。追加 `realizedState`；原构造器仍在，缺省 INTACT；真实模板传实际状态。状态进入 component hash。
- 非空生产 WriteMetadata 在原 writePlan payload 尾追加有魔数 PGWM 的明确扩展，读写同样有界；旧无扩展 payload 仍可读，旧构造器生成的字节不变。几何/段计划 canonical、T0-v1 和所有永久历史协议不改。
- 这是新世界生产接口的缺字段补充，不是对既有世界 marker 的回填升级。生产接线仍须冻结新模板指纹。

## 曲线收头与逐列破败

- 冻结要求“向已证支撑侧每4格回退”保留。旧候选实现把回退写成原 tangent 的直线平移且沿用原 W，弯道/坡道会离开合同；反向端点还错误地减 S。改为在站点链逐个采样 `S - direction * retreat` 的位置、W、切向，direction 由端点朝向与合同切向的点积确定。半径上限仍128，步长仍4，不扩大搜索预算。反例测试明确证明旧位置不在曲线上。
- WallSection 区分可渲染区间与可走区间；每世界列按其全球 S 获取破败样本。低矮废墟继续输出有根实体，但不保留空中的步道、功能或路线。最后一个64格 cell 允许裁到真实合同终点，不申请不存在的下一段；cell 身份不重编。

## 生产策略与原版序列化核验

- `WallStageCoordinator` 新生产构造委托 `ProductionWallPipeline`；原 S0 构造及实验路径保留。生产策略不实例化 `BarrierFixtureLayout`，单个 owner 异步排队、逐 cell 收集 T0，并在 proof force 完成后进入 native exclusive。FEATURES 租约按实际写半径分配，单份不超过81；结果回调在锁外执行，测试哨兵会拒绝锁内重新请求。
- Pipeline 的 `Ports` 仅隔离“规划租约 / FEATURES租约 / session有效性 / 失败通道 / 工作executor”，正式构造接既有真实端口；GameTest 用解析合同和原生 ProtoChunk 执行同一生产链路。S1C 才切换实际生命周期和 mod hooks，不冒充已启用真实全世界生成。
- `BlockCatalog.specification` 必须用原版 `Property.getName(value)` 而非枚举 `toString()`。箱子类型的 Java 枚举字符串与合法方块属性字符串不同，实际写入反例已触发 preflight 拒绝；采用原版序列化后，含箱子的敌楼整件跨 owner 写入通过。S0 `TerrainTile` canonical v1/hash 与 S0 实验 token 不变。
- GameTest “从磁盘重读T0”比较页 key、palette、完整状态数组和SHA，不比较 Java 对象引用。异步IO测试同时设置墙钟90秒上限，避免测试服务器高速 tick 提前超时；失败产物保留在 continuation 证据目录。

## 默认落地高度的最小修订

- 原冻结默认式 `Ramp.compile → compileBetween(W-20,W)` 在 drop=4/5/12 时把入口架在实际地面上方4/5/12格；填实其下方只能得到高台，不能证明可以从地面进入。保留调用原显式式得到高台的反例测试。
- 2026-10-02 用户批准替换：**本条文替换2026-09-29版第65行的“足印高差≤1”代理判据**。默认 `Ramp.compile` 读取完整声明入口走廊及外侧邻接列的不可变证据，由共享 `GroundEntranceContract.inspect` 认证至少三宽的整个实际入口面和两格头空。为保留现有平入口几何且不挖自然峰，入口脚面取走廊最大已证groundTopY；只有从模板声明的外部自然入口面能逐列以脚面差≤1接入该平面时才接受。完整足印高差>1不再单独否决，但 `[W-32,W)`、每列SUPPORTED、h>=g-12、drop<=12、真实头空、所有实际入口面列完整接入仍必须满足。无证据/保护/水/不连续/required corridor被侵入则fail-closed。`compileBetween(lower,upper)` 仍接受 H=1..32。EnemyTower声明的既有二宽底门保留二宽，不借此改其尺寸。
- 无道路冻结高度的 `Gate.compile` 同样取已证门洞地表高度；已有 `frozenGateY` 的调用仍严格使用 `compileAtGround`，不改协商值。walkY 不改变，T0 探测窗口不扩大，最大基础 drop12 不改变。

## 跨 cell 决议实现补充（2026-09-29）

- 用户批准的是实现缺口修复，不改变128格回退、4格步进、H=1..32、drop≤12、owner权限或用户尺寸要求。完整协议和方法入口见 `S1B-跨cell决议实现.md`。
- 新增不可变 GroundExitDecision（recipe、维度、绝对S区间、已接受SiteId、T0/hash、最终截断和cap实际足印）及锁外异步 GroundExitResolver。已封存决议有界复用，不缓存完整T0或构件；前后顺序/冷暖缓存不改变结果。
- Pipeline.Ports 增加 `Function<CellKey,PlanLease<PlanWindow>> arcPlans`，避免拿轴向u冒充全球S。ArcPlanProvider通过真实PrefixSummary遍历；不承诺冷远跳O(1)，单次只保留有限窗口。
- 生产决议层现在在进入模板前拒绝缺失的T0列（T0_MISSING）。这收紧的是生产证据契约，不把纯模板直接收到不完整supports时的空构件，解释成“可用缓存没加载所以这个出口失败”。
- 新 `build-exits.bin` 把owner write-plan hash绑定全部共同决议；完整marker重放验证这些证书但不改玩家方块。此证据不属于可重建站点缓存；封存后缺失/损坏不静默恢复为新边界。

## 四候选马道与收头包络

- Ramp固定遍历 `(+Z,-X)、(-Z,-X)、(+Z,+X)、(-Z,+X)`，选择第一个完整可行模板；每个方向保持原H公式和三格宽通路。外沿增加独立落地的一格护栏，避开必需地板、净空与进出口。
- 原实现 EndCap 只尝试一个子马道方向，且只声明到沿向 `+1` 的包络。完整四候选要求恢复冻结规格已有的沿向 `[-30,30)`；正向马道必须通过一个有支撑的 W 高度连接平台接回收头主体，不能只让两个不连通子组件各自通过BFS。
- 该连接平台只在正向候选需要时生成：沿向 `[-29,29)`、侧向 `[3,6)`，在原60×30包络内；镜像由固定候选顺序给出。主墙截面、马道通道宽度及回退日程不变。
- Cap证书现保存**完整体素实际投影占用的S区间**，不能仍假设所有方向仅占 `[actualS-30,actualS)`。实际足印越过允许区间、与已接受完整站点或水门冲突时，候选不获证。
- 纯测试验证四方向全部连通；原版碰撞测试验证后三方向的主体到实际地面入口连接，而不只检查两份子路线各自连通。

## 水门末跨与薄冰

- 次跨采用显式Span，完整主通航宽4、重复净跨4/墩2、所有净跨≤6；每跨接入SupportGraph双支座组。
- 保留了旧文字公式的反例：若余数1直接与“净跨4+墩2”合并，得到7，违反最大净跨6。最小修订是尾部7/8列分别保留5/6净跨，把最后2列归入既有岸侧基础；不足3列不另开狭缝。原4宽主通道和外包络不变，没有增加最大跨径。测试覆盖4..49各宽度。
- ProbeResult追加 `OptionalInt thinIceY` 并保留原构造器。SnapshotColumnView仅认可水面上一层普通ice、下为水、上为空/可替植物且未被结构/BE/不可变保护占用的证据；厚冰、packed/blue ice以及被保护冰不作为该特例。
- 只有水门主通航四列可以把该薄冰恢复为原水位的源水；旁冰不写。若保留旁冰会切断必需桥墩，则整个水门拒绝，不偷偷挖掉旁冰或留下悬空墩。源水也不得填到T0水位之上或灌入原来的干列。
- Native薄冰测试先从实际原版BlockState T0页取得证据，再执行owner writer，验证主航道水、旁冰不变。不是单凭字符串模拟原版写入。

## 新增原版高度测试的fixture纠正

32高度测试首次运行时，supports已用实际降落地面，但通用fixture的beforeStates仍硬编码64，H>20被正确的天然实体保护拒绝。修复仅为给fixture补充一致的ground参数；没有关闭ChunkPlanCompiler负空间保护。失败日志保存在 `evidence/s1b/cross-cell/native-fixture-mismatch.txt`。生产检查保持原样，修复后32个高度通过原版碰撞形状验证。

## 同一退让点的朝向否决与重放

`EndCap.compileAt(ComponentInput, Predicate<ComponentVoxels>)` 是新增重载，原单参数方法保留。四方向顺序不变；predicate仅在完整模板非空后调用。`probeCap`以真实S足印和完整站点/水门避让作predicate，同S所有方向拒绝后才走下一个4格退让点。认证`resolve`以componentHash作predicate，不重新选择默认第一方向。每个候选仍受原H/drop/支撑/净空约束；没有增加退让点。

## 敌楼与关隘的实际地面入口（7964b59）

冻结式矛盾：原两类入口脚面固定`g=W-20`，基础落差d时真实地面`h=g-d`；d=4/5/12产生不可直接跨越的高台。填实基础不能替代通地入口。`GroundEntranceTest.nominalGroundFormulaIsNotAnExitWhenFoundationDropIsNonzero`保留该坐标反例，以及d=12时地板位于W-33的证明。

最小修订仅在原足印内部进行：

- EnemyTower原两层、五段上部梯和屋顶不动。d=0保留原南侧入口；d=1..5在西环下行至北侧门；d=6..10继续沿北环下行至东侧门；d=11..12再沿东环下行至南侧门。各门从其完整2宽足印的已捕获support求地面，使用首个与该d范围相符的确定性分支；无合法分支整件拒绝。
- 西下行第j阶：X[-4,-2)、Z[-2+j,-1+j)、脚面-21-j，j从0到min(d,5)-1。d<=5的低平台延至Z=6，门路线锚点(-3,-20-d,5)。d>5时西北平台X[-4,-2)、Z[3,5)、脚面-25。
- 北下行第j阶：X[-2+j,-1+j)、Z[3,5)、脚面-26-j，j从0到min(d-5,5)-1。d<=10的低平台延至X=6，门路线锚点(5,-20-d,4)。d>10时东北平台X[3,5)、Z[3,5)、脚面-30。
- 东下行第j阶：X[3,5)、Z[2-j,3-j)、脚面-31-j，j从0到d-11；底平台X[3,5)、Z[-5,13-d)，脚面-20-d，门路线锚点(4,-20-d,-5)。所有下行梯朝向上升方向放楼梯，保留三格净空。
- 2026-10-02 用户批准替换：**本条文替换2026-09-29版第110行的“足印高差≤1”**。FortPlaceholder门道X[-2,2)、Z[-24,24)由与RootSearch/Ramp相同的 `GroundEntranceContract.inspect` 认证：完整实际入口面从外部自然地面以每步≤1接入平门道，至少三宽、两格头空，所有required列SUPPORTED且drop<=12；无需全部自然列共面。为不改变原平庭院/门道几何且不授权挖峰，脚面h仍取走廊最大已证groundTopY，在两个既有外部端面中按固定顺序取首个完整可行接入。不能把自然高度图内的一条路线当作低于岩峰的平门道可写证据。庭院/门道脚面为h，walkY不变。设lower=h-W，H=-lower，a=max(-19,10-H)，b=a+H。两个3宽内坡道仍位于X[-20,-17)和[17,20)；底平台Z[a-3,a)，第j阶Z[a+j,a+j+1)、脚面lower+j+1，顶平台Z[b,b+3)。H=20严格还原原坐标，H=32仍在48×48内。
- 两模板minY由-32改为-33，只容纳最低入口地板；探测脚面窗口仍[W-32,W+6]，drop上限12。SiteScheduler同步敌楼`[-33,8)`、关隘`[-33,8)`和此前授权的Gate/Ramp`minY=-33`、Ramp四候选60×30包络。改变的是计划内模板包络，不是墙路折线、S日程、ordinal或walkY合同。

验证范围：JUnit两个模板逐一d=0..12；原版碰撞GameTest共56组（两模板×[0°十三个drop + 15/30/45/60/90°的drop4/5/12]），从W路线到包络外自然地面。测试人工添加的是与support一致的天然地板fixture，不改生产地形保护。混合坡面/曲线邻构件干涉仍单独标记未验，不扩大本测试含义。

测试修正记录：新GroundEntranceTest初版把带一格guard的worldBounds当实际墙边，连d=0也错误失败；现以真实体素XZ足印判定出口。保留首次日志/XML但不拿它作为冻结公式矛盾证据。独立坐标反例和全部有效通地断言均保留；没有删除红项或降低通路要求。

## 马道底入口与上层护栏基础的封闭矛盾

原坐标式保留两跑顶部平台末端护栏 `X=t-4, Z[4,11)`，护栏基础自 `Y=-33` 填到上层脚面。H=20 时 `t=-26`，所以这道完整基础正好位于 X=-30，封住底平台 `X[-29,-26), Z[5,8)` 的前端；两侧底护栏与上层基础同时挡住其它出口。内部路线各自可达，不代表能走到天然地面。

实际反例：`AdjacentFixtureDiagnosticTests.flatGateMustConnectWithCapturedGroundNotAnAirBackground` 在原实现中补入同一 T0 的全部天然地面后仍不能从门洞到墙顶，墙顶也不能走出完整 Gate bounds。原始失败日志和 XML 保存在 `docs/evidence/s1b-closeout/gate-red/`，不是推测或空气视图假红。

最小修订：在现有 LOCAL 包络内增加一列入口平台 `X[-30,-29), Z[5,8)`；脚面仍为 lower，地板在 lower-1，必需净空为 `[lower,lower+3)`。它在护栏生成后声明，清除入口处的下部基础但保留上方护栏和承重结构。默认入口地面预检相应覆盖 `X[-30,-26), Z[5,8)`，不以缺失的新入口列作为可用地面。短坡 H=1..3 使用相同的入口规则。

不变项：原两跑阶梯/转台坐标、LOCAL 外包络、三格通路宽、H=1..32、drop<=12、探测窗口、四朝向顺序、128/4 收头退让与 owner 权限均不变。没有扩大用户需求。

反例保留：`RampSpecificationTest.frozenUpperEndRailSealsTheGroundLandingAtHeightTwenty` 独立保留旧式坐标见证；Gate 原版诊断把修复的入口按旧式填回后必须再次断开，当前实现则必须跨全部 requiredRoutes 并达到外部天然地面。`WallRampTest` 逐 H 检查完整三宽地板/净空；`RampGroundPortalTests` 枚举 32 高度 × 六朝向 = 192 条原版碰撞路径，逐条验证墙顶路线到完整包络外天然地面。

本次定向验证：`build/s1b-ground-portal-v1.log` 严格离线 BUILD SUCCESSFUL；3 个 GameTest 方法通过，包含 192/192 条入口矩阵，以及保留旧封闭式的 Gate 反例对照。它不代替尚待重跑的 547 条相邻矩阵和默认完整构建。

## 相切关隘封入口与敌楼西侧接缝的最小修订

1. 原前端入口只开放 `X[-30,-29), Z[5,8)`；完整关隘在相切位置占据前方天然地面后，Gate 仍可在不重叠体素的情况下失去地面出口。修订在原马道 LOCAL 内增加 `X[-29,-26), Z[8,12)` 的侧向平台和三格净空，脚面仍为 lower；自然入口预检覆盖 `X[-30,-26), Z[5,12)`。不改两跑梯、H=1..32、通路宽度、外包络、drop<=12 或 owner 权限。`AdjacentFixtureDiagnosticTests.tangentFortCannotSealGateGroundEntrance` 保留填回旧侧面后封闭的真实原版碰撞对照。
2. 敌楼规划避让 guard 原为 `[S-6,S+6)`，实体宽 11 列为 `[S-5,S+6)`。把 guard 直接当墙体替换区会删除西侧一列墙地板。修订只令 ENEMY_TOWER 的墙体替换区从 `S-5` 开始；水文避让、地面出口足印和站点日程仍使用原 guard。`RealizationPlannerTest.elevenWideTowerKeepsTheWallColumnInsideItsTwelveWidePlanningGuard` 保留原差列反例；该单测只证明列恢复，完整邻接连通另由实际 resolver/native 矩阵验证。

## 出口工作集关闭与异步排空

`GroundExitResolver.drained(): CompletableFuture<Void>` 新增独立、可取消的观察视图。`close()` 立即拒新并终止公开请求视图，但不因此释放仍由 inspect/cap/persist 或 queued continuation 持有的数据。内部 require/section 执行链分别计数；仅 close 后计数归零才释放缓存、Work pin 和临时 arena。Work 替换期间新旧 pin 同时计量，计数覆盖同步 producer 调用体；不采用公开 promise 的结束作为 producer 排空证明。

`ProductionWallPipeline` 的 owner 完成路径先异步等待所有 resolver.drained，再关闭 compiled arena 和外层预算 pin。没有增加 worldgen 同步 getChunk/join/get；关闭不清永久证书，不把玩家方块作为可回退缓存。`GroundExitReservationTest.closeKeepsPinsWhileEnteredPersistenceStillOwnsTheDecision` 已保存修复前真实失败 XML；仅已结束 require 的 512-byte 控制 pin 可提前回收，持久化生产者仍持有的整个 section 工作集必须保留。补充 pending-vote、drain-view cancellation 和异常排空测试。

## 曲坡城门端面的最小修订（2026-09-30）

原墙体替换公式把Gate局部X[-30,30)直接等同于曲线弧长区间[S-30,S+30)。原生CURVED_SITES index58证明二者不等价：世界(223,84,-3)在Gate局部(-30,0,3)为护栏，但墙的投影S=57754/256小于旧起点226，仍输出必需净空。该问题与HYDRO半格中心近岸floor的反例独立；不靠改保护层解决。

最小修订只影响墙体生产：已完整接受的Gate按其同一frame逆栅格后的局部沿向[-30,30)替换墙列；完整Gate保持原样。体素生成前作端面判定，端面外的原墙列保留，不事后按冲突坐标删块。原标量区间继续约束路线锚点，落在刚性端面内的端点只在该有限路线区间内收缩至实际墙上。不改变站点日程、W、尺寸、马道、128/4退让、drop12、owner权限、优先级或永久证书协议。

`CurvedGateBoundaryTest.legacyScalarCutReallyPublishesClearanceInsideTheFirstGateParapetColumn`保留旧公式真反例；`CurvedGateRegressionTests`复用完整原生矩阵index58，不改fixture/断言，已实测红1→绿0。完整64和547回归状态、合法继承边界见`S1B-曲坡城门修复.md`，不能把定向绿宣称阶段全绿。生产接线仍关闭。
