# S1B 曲坡城门权限冲突：影响分析与修复记录

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

本次全量JUnit/最终build：**NOT_RUN**（将单独列出实际定向与矩阵命令，不能借旧08:44 XML）。S0百轮矩阵NOT_RUN；真实生产墙/真实地形/长时堆峰值NOT_RUN；Tan's Huge Trees继续BLOCKED。

## 1. 旧公式、具体反例、最小修订、不变项、验证

待定向红绿与最终差异核验后填入；本节尚未签收。
