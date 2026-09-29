# S1B 补充合同

日期：2026-09-29。基线：80f2b24。仅补足实施规格没有编码值的细节，不改已签收 S1A 的日程、预算、几何或依赖版本。

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

## 已发现的规格冲突：尚未批准修改

这部分不是已经采用的新设计。原八册保持原样，S1B 状态为 BLOCKED。

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
- 默认 `Ramp.compile` 改读完整底入口足印的已捕获 support，取最大自然 groundTopY，足印高差不得超过1；以该高度接 W。超过 [W-32,W) 或缺支撑整件拒绝。`compileBetween(lower,upper)` 仍接受 H=1..32，供指定高度平台/道路合同使用。
- 无道路冻结高度的 `Gate.compile` 同样取已证门洞地表高度；已有 `frozenGateY` 的调用仍严格使用 `compileAtGround`，不改协商值。walkY 不改变，T0 探测窗口不扩大，最大基础 drop12 不改变。
