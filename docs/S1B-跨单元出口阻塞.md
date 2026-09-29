# S1B 跨单元出口回退阻塞

日期：2026-09-29。编号：B-S1B-03。当前状态：已实现并通过定向验证；S1B 整体状态另见 `S1B.md`。

## 当前处理结果

用户已批准继续实现。现已增加 GroundExitDecision / GroundExitResolver / GroundExitCodec、绝对S规划端口、T0完整性校验、耐久证书和 owner proof 绑定。所有生产消费者在 APPLY 前等待共同决议；四候选局部回退全失败后，统一撤回到经 T0 完整接受的更早地面出口。

原失败验收已通过，未改成期望异常。新增原生 ProtoChunk/Vanilla mailbox 测试证明早、晚 owner 在相同边界截断，并在冷重开后的反向只读重放中保留玩家金块。实现细节、范围和资源限制见 `S1B-跨cell决议实现.md`。

本文件以下第1～5节保留**修复前的反例与历史结果**，其中 FAIL/BLOCKED 不代表当前该测试仍失败。修复前原件另存 `evidence/s1b/cross-cell/pre-fix-blocker.md`，没有覆盖旧证据。

历史说明：本条不是此前 H=1/2/3/32 的马道坐标问题，曾阻止S1B签收。两项现均已修复；下面保留当时的分析，不代表当前仍因B03等待批准。

## 1. 冻结要求

`docs/S1-S5实施规格/S1-城墙本体.md` 的 EndCap 条目：

- 先向已证支撑侧每4格回退，最多128格，逐候选检查完整 cap + ramp。
- 全部失败时，连续可走区间边界退到前一个已有地面出口。
- 普通断口两端要有通地出口，永久终点不在后侧另建一份。

同册 RealizationPlanner 的输入按64格 cell 加有限邻域；writer 在完整决议、T0及proof落盘后只写 owner。已经完成且有相同 marker 的 owner 重放是只读，不因后续规划重做而抹掉玩家修改。

## 2. 已运行的反例

测试完整路径：

`D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\terrain\GroundExitFallbackTest.java`

这是纯 Java 解析 T0 fixture，不是冒充实世界验证，也不改 S1A 采样预算、日程或构件 ordinal。

固定条件：

1. `walkY=84`，普通地面脚面64，seed=8；本例范围内破败保持可走态。
2. S=0 的48×48 FortPlaceholder 可以完整编译，是可行的更早地面出口。
3. X<128 的地形平坦；其后只有 Z[-5,5) 的10格宽山脊保留地面64，两侧地面为0。
4. 合同在 S=480 终止；输入含完整站点链和解析 T0，不靠缺数据制造失败。
5. EndCap 在480、476……352的全部冻结回退点，两侧、前后向马道入口都无 [W-32,W+6] 内的合法地面，且所需高差大于32。放宽 H 或把支撑 drop 上限改大不是合法修复。

实际结果：

- `finiteRetreatCanFailAfterAnEarlierValidGroundExit`：PASS。确认早期关隘可行、四方向入口在所有冻结回退点均 NO_SUPPORT；较早 cell [128,192) 能发布 walkable 体素；末端 cell 抛出 `OWNERSHIP_CONFLICT: no proven ground exit`。
- `exhaustedCapSearchMustFallBackToTheEarlierGroundExitWithoutFatalGeneration`：FAIL。它要求正常返回符合冻结回退要求的断口决议，当前实现仍抛异常。没有禁用、改成“期望异常”或缩减验收条件。

证据范围必须分清：本测试证明的是纯决议层的缺口，以及早晚 cell 的独立输出。它**没有**声称已经在真实世界重现“旧 chunk 已写入后被强行回填”。生产 pipeline 还有邻域捕获，可能在更早阶段 fail closed；没有因此声称它已经实现合法回退。

## 3. 当前缺口

当前 `EndCap` 已能沿真实站点链更新回退位置、切向及 walkY；默认 Ramp 已取真实入口地面。局部几何问题不是本例失败的原因。

当前 `RealizationPlanner.addCap` 在所有128格候选失败后直接抛错，没有：

- 前一个**已完整验证可行**的地面出口证明；
- 向受影响的更早 cell 传播最终截断边界的协议；
- 在任一相关 owner APPLY 前冻结上述边界的依赖。

现有 `PrefixSummaryIndex` 证明几何长度和最早规划终点，不证明 T0 下的实际出口可行性；不能拿“计划有一个 Gate/Fort”冒充“地形实测接受并可通地”。

这是代码和接口尚未闭合，不宣称已证明整个既定架构无法实现。当前缺失的跨 cell 行为不能用增加一个局部回退常数、跳过失败或把缺失出口当成功来签收。

## 4. 必须满足的后续修复边界

在现有 PREPARE→CAPTURE→APPLY 中补齐实际出口/截断证明：

- 身份至少含 recipe、绝对S区间、接受的出口 SiteId、所用T0哈希、最终截断边界。
- 同一连续可走区间的所有消费者在 APPLY 前等待相同决议；不同请求顺序不得发布相反边界。
- 出口可行性不能由当前已加载侧、缓存冷热或“之前恰好生成过”决定。
- 需要更早出口时，通过锁外异步依赖取得证据，不在 worldgen 中同步 getChunk/join/get。
- 不把已经写入的普通 owner 当可随意回退的临时缓存；不得破坏玩家修改。
- 同时约束 T0/compiled pin 与失败/关闭释放；不得把有限窗口直接无限扩大。
- 原128步进搜索、H=1..32、drop≤12、owner 写权限和用户视觉/尺寸要求保持不变。

这些条件完成前，生产世界接线保持关闭，S1C 不启动。它不是“再批准一次短坡公式”的请求；需要的是跨 cell 决议实现及对应顺序/恢复验证。

## 5. 复现

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*GroundExitFallbackTest'
```

预期当前结果：2项，1 PASS、1 FAIL，exit code 1。

完整日志：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\ground-exit-counterexample.txt`

最终全量报告/原始XML：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\`
