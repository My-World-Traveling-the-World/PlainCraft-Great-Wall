# S1B 相邻构件有限矩阵

## 状态与所有权

**本次 token/T0 修正检查点：SOURCE_READY / COMPILE_NOT_RUN / GAME_TEST_NOT_RUN。旧首轮已中止并保留部分失败证据，不能报告 547 全域 PASS。S1B 未签收；本文件不批准启动 S1C。**

前任约 600 行版本曾由主代理编译，但未注册、未运行。主代理在本 worker 分批补 helper 时读到中间态，报告了 19 个缺失 helper 编译错误；该次属于中间态构建失败。随后完整旧检查点 `907e14eabc0dae501ade8a4def7b9ebf537e5e7c145f116a6f43526e54244805` 已由主代理编译并开始首轮 547 矩阵，后中止会话 33163；部分结果保留于 `build/s1b-adjacency-first.log`。未完成条目保持 NOT_RUN，已执行失败不得改写为 PASS。本次源码不能继承旧检查点的编译或运行结果。

本次修正只改动以下两个文件，没有改动现有生产类、ComponentFixtures、注册 JSON 或 build.gradle：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\AdjacentComponentMatrixTests.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-相邻矩阵.md`

**当前 Java 冻结 SHA-256：`afd0bbcecdb639df53fa07156253f8c9ec5145b07feaab804c05c21aa6d4c766`。** helper 已全部写完并读回，交由主代理串行编译；文档写入不改变 Java。worker 仅做本地源码/API 对照、括号词法检查和枚举计数，未运行 Java 编译器、Gradle、游戏或 JVM，未联网、未提交 Git，未访问真实 mods/存档。词法检查不等于 Java 编译成功。

此前另获授权新增的 `src/gametest/java/local/plaincraft/greatwall/gametest/AdjacentFixtureDiagnosticTests.java` 已交主代理；主代理随后补入旧 portal 闭合反例，本次不修改该类，也不修改新增的 `RampGroundPortalTests`。

## token/T0 诊断与修正证据

- `build/s1b-closeout-diagnostic-v2.log` 第 29379 行保留 Gate 原生诊断：四条独立路线均可达，墙顶到双马道均可达，但补齐真实 T0 后 door→routes 仍为 `[true,false,false,false]`，墙顶到包络外地面为 false。入口边界 `(32,64,6)`、`(34,64,4)`、`(34,64,8)` 为 stone_bricks。第 29401 行保留失败，第 29410 行起汇总 18 项结束、仅 Gate 失败，进程退出 1。**这是实际入口封闭，不是假红；不允许通过删除跨路线或 exterior 断言消红。**
- 同一日志第 29406–29409 行保留 ChestType 的 `SINGLE/single`、`LEFT/left`、`RIGHT/right` 差异，实际敌楼箱子 `(-1,75,0)` 的 BE 位置存在，规范 token 与 proof 完全相等，旧 S0 token 仅 `type=SINGLE` 不同。矩阵据此改用 `BlockCatalog.token(chunk.getBlockState(p), actualEntities.contains(p))`；actualEntities 在真实 writer 完成后取得，不用 `hasBlockEntity()` 或计划意图冒充实际 BE。
- 主代理后续报告：在原 LOCAL 内补充 `x[-30,-29), z[5,8)` 地面 landing 与三格净空后，Gate 天然地面跨路线/exterior 已转绿，旧 foundation 封口反例仍保留，新增 RampGroundPortalTests 的 `32H×6角=192` 已通过。这是主代理提供的独立结果，本 worker 未运行，不能替代当前 547 新检查点的执行证据；本次不修改 Ramp/Gate 生产类。
- 矩阵背景使用本条目的真实解析捕获 columns，读到缺列或未知 token 立即失败，不猜 AIR。完整组件 bounds 周围额外 XZ 两格、下方一格/上方两格只用于原版形状和邻居读取；**原 route 起点、逐锚点断言及 BFS 的组件 bounds 没有放大**。原发布写入优先，包括显式空气，并逐坐标检查背景叠加没有改变任何写入。
- BOUNDARY 在左完整体素的 `maxXExclusive` 处分区：其左侧使用左件捕获列，右侧使用右件平移后的捕获列，gap=1 的空白列明确属于右侧背景。每一件全部实际写入列的完整 T0 hash 必须与其原捕获列一致，所有最终写入位置还逐一核对 before-state。模板为编译预采样但未使用的另一侧远处列，不作为相邻世界的第二套矛盾地形。此分区是固定 fixture 定义，不增加参数轴，不改变模板、resolver 或证书。
- CONFLICT 中合法的两条 WALL/WALL 共用既有平坦捕获列；126 条拒绝仍只验证既定所有权冲突。协议组使用同一 Fixture 的全带 columns。天然出口校验现在补足捕获背景而非仅补地板，并保留附近其他组件的真实写入；原目标集合和 exterior 断言不变。
- **DECISION 站点间的墙顶连续性仍只用发布写集验证**，不允许借天然地面绕行掩盖缺失墙顶。正逆 resolver、正逆 owner canonical 字节、两轮原生写入、真实 BE、spawn slot、玩家改动保护及所有原连通断言均保留；547 条枚举未裁剪。

## 枚举总数

有限矩阵包含 **8 个 GameTest 方法、547 个参数条目**。本表只穷举明确列出的离散轴，不声称覆盖全部可能参数、全部地形或所有调度交错。当前新源码检查点的每项运行状态均为 **NOT_RUN**；旧首轮部分执行记录另存，不被此标签抹除。

| 日志标签 | 方法 | 参数轴与条目数 | 实际检查层次 |
| --- | --- | --- | --- |
| BOUNDARY | `orderedTouchAndOneBlockBoundaryMatrix` | 8 有序左类 × 8 有序右类 × 2 耦合几何/地形 profile × 2 边界 = **256** | 两个真实完整模板的占用 X 边界相切/隔 1 格，owner 合并、原版形状与原生写入 |
| CONFLICT | `orderedRequiredClearanceConflictMatrix` | 8 × 8 × 2 相对朝向 = **128** | 126 条必需净空对非 WALL 实体的拒绝；另 2 条 WALL/WALL 合法重叠 |
| DECISION | `orderedSitesUseActualSharedExitDecisions` | 4 有序站点类 × 4 有序站点类 × 2 地面 profile = **32** | 实际 survey → resolver → certified resolve → writer |
| HYDRO | `water49_50_51AgainstEverySiteAndBoundary` | 4 站点类 × 3 河宽 × 2 河岸侧 × 3 水文足印边界 = **72** | 49 窄河；50/51 近岸终止；站点接受/水文抑制/实际撤回 |
| CAP | `capAvoidsActualSiteAndWaterFootprints` | 5 障碍类 × 2 端点方向 × 3 接触边界 = **30** | 实际 `probeCap` 局部投票及完整组件 hash 重放；不是独立完整 resolver 决议 |
| TERMINAL | `terminalRetreatAndExhaustionUseActualResolver` | 5 障碍类 × 3 支撑路径 = **15** | 终点直接接受、有限退让、所有候选耗尽后的真实共同撤回 |
| REJECTION | `unsupportedOrProtectedSitesAreRejectedWhole` | 4 站点类 × 2 不可行原因 = **8** | 完整站点无支撑/受保护，保留根出口和远端出口原 ordinal |
| GAPS | `ordinaryBreaksAndShortIslandsUseActualVotes` | 2 左支撑 × 2 右支撑 + 2 孤岛长度 = **6** | 普通断口两侧接受/拒绝，两个可行 cap 足印相撞时撤回短岛 |
| 合计 | 8 个方法 | **256 + 128 + 32 + 72 + 30 + 15 + 8 + 6 = 547** | **NOT_RUN** |

8 类为 WALL、ENEMY_TOWER、BEACON、GATE、FORT、WATER_GATE、END_CAP、RAMP。4 类站点为 ENEMY_TOWER、BEACON、GATE、FORT；FORT 是 S1B 的 FortPlaceholder，不是 S3 正式关隘。5 类障碍为上述 4 类加 WATER_GATE。

DECISION/HYDRO/TERMINAL/REJECTION/GAPS 共 **133 条**，每条分别创建一个正序与一个逆序 resolver，各请求 8 个 cell。因此全部成功执行到末尾时是 266 个 resolver 生命周期、2128 次 require 请求。它们不是新增的 2128 个独立参数条目；条目中途失败时也不能宣称后续 require 已执行。内存中的不可变输入可复用，未模拟磁盘冷读。

## 封闭离散域与状态边界

令 `K` 为上述 8 类构件，`E` 为上述 4 类站点，`I=INTACT`。**8×8 对覆盖只指 BOUNDARY 与 CONFLICT 各自定义的域，不指后面所有协议组，也不指任意方向、坡形、破败状态的邻接全笛卡尔积。**

BOUNDARY 的封闭域严格为：

```text
D_boundary = K × K × {P0, P1} × {gap=0, gap=1} × {(I,I)}
|D_boundary| = 8 × 8 × 2 × 2 × 1 = 256
```

profile 同时绑定方向和地形，不能拆开后当作独立轴再相乘：

| 固定或绑定轴 | P0 | P1 |
| --- | --- | --- |
| 左/右输入朝向 | (0°,0°) | (0°,45°)，没有 (45°,0°) 或 (45°,45°) |
| 输入 frame.mirror | 左右均 false；模板内部候选选择不计独立镜像轴 | 同 P0 |
| 墙线平面形状 | 直线 | 只有 WALL 为正弦曲线；非 WALL 仍为各自完整局部模板 |
| W 纵向形状 | 固定 84 | 非 WALL 固定 84；WALL 用下面固定公式，未枚举上坡/下坡/峰/谷的独立组合 |
| 干列 ground/drop | ground=64，drop=0 | ground=W(column)-20-drop，drop=4+floorMod(x+z,2)；棋盘按刚性平移前的世界列定义 |
| WATER_GATE 湿列 | 局部 X[-24,25)，ground=56、水面=65、drop=8 | 同 P0；干岸列按本 profile 的干列规则 |
| 左/右输入破败状态 | **(INTACT,INTACT)**，两件均显式 `DecaySample(INTACT,0,20)` | **(INTACT,INTACT)**，相同固定值 |
| 其它固定量 | seed=0、STONE、站点中心 S=1024、完整构件 ordinal=100/200；按实际 X 占用边界平移右件 | 同 P0 |

P1 的 WALL 采样参数 `u=-96,-92,...,96`；局部横向 Q8 为 `round(2*sin(u*pi/32)*256)`，采样 W 为 `84+round(2*sin(u*pi/32))`，之后沿真实累计 Q8 弧长插值。64 格周期是局部参数 u 的周期，不等于全球 S 方向上严格 64 格周期。方向、曲线、W 和棋盘地形在 P1 中耦合，不能据此宣布各自取值的任意交叉都覆盖。

CONFLICT 的封闭域为 `K × K × {(0°,0°),(0°,45°)} × {平坦支撑及固定水门湿列} × {(I,I)}`，共 128 条；对齐碰撞见证的刚性平移是确定性构造，不是另一个被穷举的位置/高度轴。

其它 6 组的方向/坡形/状态范围如下，不能并入上面的 8×8 域补齐缺项：

| 组 | 方向与坡形 | 状态取值方式 |
| --- | --- | --- |
| DECISION | 直线 +X，W=84；仅 ground=64 或 ground=59/60 棋盘这两个 profile | 正式采样 `DecayField(seed=8)` 及实际接受站点维护影响；不是显式枚举状态对 |
| HYDRO | 同一直线与 W；固定平岸/河床，河前/河后是位置轴，不是旋转轴 | 同样由实际采样决定；即使出现 PARTIAL，也只是该固定输入的结果，不证明 PARTIAL 邻接整域 |
| CAP | 直线、W=84、固定平岸/河床；edge.reverse=false/true 两种端点方向 | 独立编译用于合并的 cap/障碍体固定 INTACT；probe 内障碍接受仍用生产采样，未穷举状态组合 |
| TERMINAL | 直线、W=84；仅全宽平地、X>=456 窄脊、X>=320 窄脊三种地形 | 实际 seed=8 采样；cap 用生产固定 INTACT 候选 |
| REJECTION | 直线、W=84；局部 ground=51 或固定保护盒 | 实际 seed=8 采样；不可行原因不是额外破败态 |
| GAPS | 直线、W=84；固定计划断口及侧坡支撑掩码，孤岛仅长 48/64 | 实际 seed=8 采样；两端 cap 用生产固定 INTACT 候选 |

**未提供、也未验证其它破败状态邻接的单调性归约证明。** 不能假定“INTACT 最密，所以删除方块只会更安全”：净空碰撞、必需通路、承重/装饰 survival、功能意图和真实出口/撤回决议都需要各自的保持性证明；本报告没有这些证明，也不据此改写或放宽任何断言。

因此，以同一 BOUNDARY 几何域为例，`{INTACT,PARTIAL,RUIN,BURIED}²` 的 16 种有序状态对中，仅 `(INTACT,INTACT)` 有当前枚举；其余 **15 种状态对为 NOT_COVERED / NOT_RUN**。这不意味着已为它们实现 3840 个测试，更不能将未实现条目加进 547。当前已枚举的 INTACT 条目在本交接点本身也仍 NOT_RUN。

用户所指的单构件 960 条、wall-only 曲坡 576 条属于其它域，**不计入 547，不相乘、不合并成相邻全参数覆盖证据**；即使它们各自通过，也不能补齐本文件缺少的状态对、方向对或站点曲坡组合。本报告未核验那两组的实际执行结果。后续如新增轴，需新增明确参数域、计数、源码检查点和运行证据；本轮优先由主代理实跑当前检查点，不在运行期间扩轴。

## 各轴的精确定义

### BOUNDARY 与 CONFLICT

BOUNDARY 的 profile 不是相互独立的角度、坡度和曲率笛卡尔积：

- profile 0：左右均 0 度，通常地面脚面 64、W=84；水门湿列固定河床 56、水面 65。
- profile 1：左 0 度、右 45 度；干地使用 drop=4/5 的棋盘列，湿列仍 drop=8。仅 WALL 使用由真实累计 Q8 段长构造的正弦曲线，横向偏移及 W 的振幅各 2 格、局部采样参数周期 64 格；其他构件不因此变成曲线模板。两件破败状态在两个 profile 下都固定 INTACT。
- 右完整构件在编译后刚性平移，实际体素占用包围盒的 X 轴半开边界差严格为 0 或 1；不是凭带 guard 的 template bounds 认定相切。全部 voxel、实体、spawn slot、route 一起平移并重算组件 hash。
- 整个 XZ 列的 T0 hash 必须相容，不仅比较两件恰好重合的写入体素。支持值经过真实 SurfaceProbe，不手填 SUPPORTED 来压过地形事实。
- 此组检查空间相邻、完整合并与各完整组件内部通路，不声称两个任意类型刚性摆放后互相都有一条可通行连接，也不声称是 SiteScheduler 产出的合法站点日程。

CONFLICT 的 profile 是右构件相对左构件的 0/45 度，地面为平坦模板输入。按稳定 y,z,x 顺序选完整组件中已有的 required OWNED_VOID 与另一完整组件的 required SOLID 对齐，保留完整体素，不用两个伪造小方块冒充整件冲突。

126 条拒绝分支必须在两个 cell 顺序下得到 `OWNERSHIP_CONFLICT`，且错误必须是必需 void 无权擦除别的非 WALL 组件。为隔离这个先于 T0 写入检查的合并规则，此分支的 before-map 明确是合成全空气；**它不是自然支撑或原生 writer 测试，不证明这种强行叠放在地形中可合法生成**。不执行失败计划的 APPLY。

WALL/WALL 的 2 条不套非 WALL 拒绝规则：保留 0 度重复完整墙带，并将原先重复输入的第二条改成真实 45 度交叉墙带。两条都要求完整合并、原版碰撞和 owner 重放成功，不把重复输入计成新的独立几何。

### DECISION

根为 S=0 的完整 FORT，待测两个站点为 S=128/256，终点为 S=480，合同站点链到 S=512、W=84、每 4 格一个站。两个地面 profile 是平坦 ground=64 和棋盘 ground=59/60；这里的 W 仍固定，不是纵坡。真实 DecayField seed=8、STONE 族参与生产模板采样，不手工填 GroundExitDecision。

先按实际 decay 和 T0 独立编译合法站点，要求它非空、有必需路径；再检查实际决议是否排除，并核对发布体的 hash。计划站点不自动等于地面出口。证书 left/right 必须逐一对应实际 inspect 的 Exit。

两个相邻站点之间没有已证断口时，检查原版碰撞形状的墙顶可达性；断口/撤回的跳过分别计数，且每条 DECISION 至少真正检查一对连接，禁止全跳过仍过关。所有真正获证的自然出口另用捕获 T0 的天然脚面和原版碰撞形状验证到组件边界外的通路。

### HYDRO

河宽固定 49/50/51，河流绝对 S 区间为 `[256-floor(width/2), from+width)`，水面脚面 65、河床脚面 56，完整岸点及墩证据来自解析输入。50/51 的 terminal 是 near-bank `from`，49 的远端 terminal 为 480。

站点安排在河前或河后；gap=-1/0/1 是**规划站点 S 足印**相对 crossing 外扩 8 格水文保护区的重叠 1 格/相切/分离 1 格。站点 extent 沿用当前合同的 ENEMY_TOWER=6、BEACON=5、GATE=30、FORT=24；这不是所有类型实际世界体素的包围盒距离。

- gap=-1 必须由水文优先规则抑制站点；0/1 先验证完整模板可行，再遵守实际共享排除区，不能把计划可用当成最终必发布。
- 49 先要求完整水门物理可编译，再要求发布恰 1 件，或被实际共同排除明确撤回；不因“最终没水门”就跳过前置可行性。
- 50/51 不得出现 WATER_GATE，最多一个近侧 END_CAP，发布体素投影必须全部在近岸之前，不能在远岸形成第二份墙/收头。
- 发布的水门检查全部 FLUID 仍为原水位以下 source，原 T0 该坐标本来有水；主船道 4×10×3 的 120 个坐标逐一检查 required OWNED_VOID 和最终 air，而非用其它次拱净空数量抵账。

### CAP 与 TERMINAL

CAP 的障碍站点位于 S=256；水门障碍 crossing 为 [232,281)。障碍保护 S 区间沿用生产占用规则。先完整编译无碰撞收头，投影所有体素得到真正足印，再反求要测试的接触 edge，要求未消冲突的完整体 measured gap 确为 -1/0/1。禁止凭固定“30 格”或 padding 判断接触。

`probeCap` 必须给出真实完整可重放的 cap，足印不得碰障碍；相切/分离在这些平坦 fixture 必须零退让，重叠必须发生正退让。退让按**带方向**的差值核验 0,4,...,128，不能用绝对值让反向错误蒙混过关。同 S 内四朝向仍由生产 EndCap 自行决定，测试不手选证书、也不指定某个朝向来替换真实投票。CAP 为局部 probe 输入构造 Edge；真正证书只在其它五组由 resolver 生成。

TERMINAL 在 S=256 放一个已完整编译障碍，终点 480，三条解析地形路径为：

- DIRECT：全宽平坦，要求终点实际 cap 零退让。
- RETREAT：X>=456 后只保留 Z[-5,5) 的 10 格宽高地，其外自然地面降至 0，要求真实终点 cap 发生 4..128 格内退让。
- EXHAUSTED：同一窄脊从 X>=320 开始，整个 0,4,...,128 搜索中的四朝向坡道均无合法侧向支持；要求终点投票为空、共同决议撤回整个失败 run、不发布假 cap、不残留被撤回区间内的 walkable 体。BEACON 和 WATER_GATE 不具备该 fixture 的自然地面出口，因此要求连完整非出口构件一起撤回。

这 15 条都用实际 resolver 完成正逆 cell 请求并运行 certified resolve。耗尽场景可能合法地只剩根 FORT：专用 `verifyOwners` 仍检查该完整根和全部 proof/native replay，不伪造第二件来满足相邻条数；其它普通相邻组原有“至少两个完整组件”断言保留。

### REJECTION 与 GAPS

REJECTION 将 S=256 站点足印周围 X[216,297) 分别设为 ground=51（低于本 W 的合法探测脚面）或保护盒 X[216,297)、Y[48,96)、Z[-40,41)。根 FORT ordinal=0 和 S=440 的远端 FORT ordinal=200 不变。要求目标整件缺席、不能被列为已接受出口、共同证书有包含中心的断口、没有别的构件写进保护盒。不能把缺页当成无支撑，此组 T0 列仍完整。

GAPS 的普通断口为计划 S[200,224)，分别让左右 128 格候选范围的侧坡有/无支撑，枚举 4 种组合。要求来自实际投票的两端 cap 与支撑预期一致，失败的一侧有对应完整 run 的实际撤回。

另外两个 fixture 的断口为 [96,192) 和 [192+length,352)，中间孤岛长度分别 48/64。先强制检查两个端点都能投出完整 cap；48 格时两 cap 实际足印必须相交并撤回整岛，64 格时足印必须不相交并发布两件。不用“本来某端投票失败”冒充覆盖了双 cap 相撞分支。

## 共用断言与日志

完整合并的共用验收保留并加强：组件 hash/同 SiteId 一致、正逆 cell 输入的 owner write-plan canonical 字节相等、所有非 KEEP 坐标的 owner 并集精确一致、每写一条 proof、禁止越 owner、必需净空、无重力方块、原版 `canSurvive`、原版碰撞路径、所有功能块实体和 spawn slot 不丢不重。

非 WALL 组件的所有 requiredRoutes 必须处于同一个可达集合，防止收头主体和坡道各自可走却彼此不连通。WALL 允许其多个被合法断口隔开的独立路线分别验证。

原生写入使用隔离 ProtoChunk，不读取或写入真实世界。两个 owner 顺序均用 BlockCatalog 规范属性值及原生 `getBlockEntitiesPos()` 的实际成员关系核对最终 block/entity token 与 proof；不再调用旧 S0 token。完整 marker 重放写入数为零。随后在同一隔离 owner 改一块 gold_block，再次按 INITIAL 重放，要求仍为零写入且保留 gold_block。它不等同磁盘重启后的完整生命周期恢复测试。

真实协议组的 Source 只委托 `RealizationPlanner.inspect/probeCap`，persist 保存实际 resolver 产物。每个消费证书核对已 persist 的同 Key 值和 codec hash；left/right 都应来自本次真实 survey，cap 应来自本次真实 vote；certified resolve 后完整体 hash 和占用 S 足印必须一致。没有手工创建 GroundExitDecision/Exit/Cap 来替代实际决议。

每组输出 `S1B_ADJACENCY_<LABEL> planned=N`，成功条目输出 `S1B_ADJACENCY_CASE ... status=PASS`，失败打印 index/完整参数和异常堆栈，末尾输出 attempted/passed/failed。一个失败不会阻止本组后续普通条目被尝试；进程中断、超时、内存错误等导致未到结尾时，剩余条目必须记录 **NOT_RUN**。没有末尾汇总或缺少任一方法 XML，不能宣布 547 条完成。

## 注册与主代理执行

主代理已将矩阵和独立诊断注册到 `D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\resources\fabric.mod.json` 的 `entrypoints.fabric-gametest`。矩阵类名如下；不得重复追加：

```text
local.plaincraft.greatwall.gametest.AdjacentComponentMatrixTests
```

worker 不修改该文件。当前已读取到的 build.gradle 提供 `greatwallGameTestClasses` 定向选择，且会拒绝未注册类；注册前不能直接使用该选择参数。按主代理统一调度，确认 Java 检查点后串行执行，禁止与其它 Gradle 同时运行。下面只是交接命令，**worker 未执行**：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
.\gradlew.bat --offline --dependency-verification=strict compileGametestJava --console=plain --no-daemon
.\gradlew.bat --offline --dependency-verification=strict runGameTest -PgreatwallGameTestClasses=AdjacentComponentMatrixTests --rerun-tasks --no-build-cache --console=plain --no-daemon
```

JDK 与离线锁定策略沿用工程配置。主代理保留实际 stdout/stderr、退出码、8 个方法 XML 和当前输入源码 SHA；按上述 8 个标签汇总 547 条，不把“8 个方法通过”孤立当作条目数证据。定向选择是诊断子集，不取代默认全套回归或 S1B 签收。运行期间 worker 不修改测试源码；修复必须产生新检查点与新证据。

## 明确盲区

下列未被本有限矩阵证明；本交接点统一标 **NOT_RUN / OUT_OF_SCOPE**，不能因 547 条将来通过而改为通过：

- 全部旋转、镜像、每一种 chunk 对齐、负全球 S、所有绝对世界位置；这里只有明确的 0/45 度和两种 cap 方向。正负世界 XZ 会被一些完整构件自然跨过，但没有穷举 16×16 owner 偏移。
- 曲率、纵坡、W、drop、H、破败四态、三个材质族的全笛卡尔积。BOUNDARY 的曲坡只施加于 WALL 且与角度/棋盘地形耦合；真实 resolver 的站点链仍为直线 W=84。不声称验证所有混合斜坡邻接。
- 所有站点日程重叠分量、S1A 的优先级/socket 吸收/抑制组合；完整模板强行碰撞不是重新测试 SiteScheduler。初始 SUPPRESSED/ABSORBED helper 防护存在，但没有为其新增矩阵条目。
- 任意水位/河床深度、斜交的河道宽度与 crossingSpan、跨段水文去重、薄冰/旁冰、多个河道、WATER_GATE 对 WATER_GATE 的完整 hydrology 决议；49/50/51 只在本文件列出的解析河床、水位和站点侧向位置覆盖。八类直接体素矩阵含 WATER_GATE/WATER_GATE，但不能替代双河协议。
- 强制逐一选中 EndCap 全部四朝向、逐一跑完所有 33 个最终退让距离。这里只让真实算法选朝向，并用固定地形验证直接/正退让/全部耗尽及相撞路径；不拿这点替代其它现有专门四候选/32 高度测试。
- ROOT_NOT_FOUND、T0 缺列/损坏、frozenGateY 变化、证书损坏/缺失/错 recipe、executor 拒绝、取消/关闭竞态、32 等待者超限、磁盘冷恢复及故障注入。本测试来源正确性断言不是这些故障矩阵。
- 随机/并发 cell/owner 到达、不同线程数、实际 worldgen 调度、C2ME 区域锁或 startup pump；当前 `Runnable::run` 只用于已完成的解析端口，future 先断言 isDone 再 getNow，不在生产 worldgen 中阻塞等待。
- 全部复杂 voxel 三件以上交叠优先级；原版服务器长期邻居更新、流体 tick、实体移动/玩家或船实际驾驶、视觉图册、真实 CPU/Tectonic/C2ME 生产墙、S1C 生命周期、真实存档和第三方模组。
- 内存分配前预留和 Java 堆峰值。测试配方仍是 terrain=64MiB、compiled=32MiB，未抬高预算；解析 fixture 为一次有限 8-cell 带提供 40×8=320 页、每页 Y[48,95] 的共享主数据（仅状态数组净大小 15MiB），不是一次生产 CAPTURE，也不等于已验证单次 FEATURES<=81、realization owner<=256 或完整对象峰值。Snapshot/pipeline 的专门内存审计由主代理负责，原红态不能被本矩阵遮盖。

## 不变边界

R1/R2/R3、P1..P5 全部沿用 `docs/S1-S5实施规格/README.md`；不添加 worldgen 同步 getChunk/join/get，不改 possibleBiomes，不更换跨线程可变状态规则，不改锁前依赖/启动 pump、绝对 S 前缀、合法支撑窗口、durable T0/owner 写入或永久历史策略。

本次冻结源码尚未编译/执行，尤其不能将 SOURCE_READY、诊断通过或旧首轮部分结果包装成 P0 已关闭。收头 128/4、坡道 H=1..32、drop<=12、32+64MiB 预算均未变。本工作只交付相邻矩阵测试与准确范围说明；S1C 生产代码没有授权给本 worker，也没有被改动。
