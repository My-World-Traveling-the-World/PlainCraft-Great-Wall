# S1C-B 入口可走性判据与 A 根筛统计

日期：2026-10-02，Asia/Shanghai。基线：`5a482e9`。
**本报告只读统计与设计，不修改生产代码、不重跑 seed17、不写世界、不选点。**
生产 OFF，Tan's BLOCKED，S1C 未签收。

## 1. 结论

**B 对当前已选根可行，A 可以按批准方向实施，但本轮还不改代码。**

已选根 `(206,67,953)` 的入口 T0 证据显示：

- 外侧 local `z=-24` 的 3 个实际列均为 `groundTopY=68`；
- 3 宽入口走廊内存在相邻列高度差不超过1的连通路径；
- 路径可到达内部 local `z=23` 的 3 个目标列，目标自然地面为 `69`；
- 选择自然起点 `lowerFeetY=68`，再用已有 `Ramp.compileBetween(lower,W)` 逐级上升到
  `W=87`，不需要把入口地板固定在最大自然地面 `h=70`；
- 所有已预检入口列是 `SUPPORTED`，旧保护/水/上界分类没有被吞掉。

这证明“全入口足印 `max-min<=1`”不是必要的可走性判据；它会拒绝本例
`67..70`，而逐步可走路径可以从 `68` 开始。这个结论只对已归档 T0 的
选定根是**已验证**；其他候选入口数据缺失，不能外推。

## 2. B：替换合同判据

### 建议替换的两句冻结文字

将 `docs/S1B-补充合同.md:65` 中：

> 足印高差不得超过1；以该高度接 W。

替换为：

> 入口不以完整足印的绝对 `max-min<=1` 作为拒绝条件。入口合同在完整
> 三宽走廊内选择一个 `lowerFeetY`，要求 `W-32<=lowerFeetY<W`，每个
> 被路径或 required corridor 使用的列均为 `SUPPORTED`、`drop<=12`、
> `groundTopY<W`；从外部自然边界到入口脚面存在确定性相邻路径，每次
> 相邻步的脚面高度差不超过1，且路径保留两格头部净空。入口无法形成
> 该路径时 fail-closed。

将 `docs/S1B-补充合同.md:110` 中：

> 从门道……完整已证地面取 h，足印高差<=1；庭院/门道脚面为 h。

替换为：

> FortPlaceholder 从门道完整三宽走廊的已证支撑列中，按固定顺序选择
> 首个能从外部自然边界逐步连通的 `lowerFeetY`；其值必须在
> `[W-32,W)`，庭院/门道按该起点接入，内部坡道逐级到 `W`。不要求门道
> 所有自然列共面；若无外部连通路径、列缺失、保护/水/无支撑、drop>12、
> 两格头部净空被侵入，则整件 fail-closed。

### 单一事实来源

新增的共享纯判据应由 `Ramp.ground` 与 `RootSearch` 同时调用，建议形态：

```text
GroundEntranceContract.inspect(
    local 3-wide corridor,
    immutable ProbeResult per world column,
    walkY,
    DimensionBounds
) -> GroundEntranceCertificate(lowerFeetY, path, t0Hash)
```

它只做几何/证据判定，不读世界、不补列、不修改方块。`Ramp.ground` 消费
证书并调用已有 `compileBetween(lowerFeetY, walkY)`；`RootSearch` 在根候选
施工前的入口预检阶段消费同一方法。不能在 RootSearch 与 Ramp 各写一套
“近似可走”逻辑。

### 判定细节

1. 入口走廊只使用模板声明的三宽区域和两格头部净空。
2. 起点是外部自然边界上的已证 `SUPPORTED` 列，不能把墙自己的方块当自然起点。
3. 相邻路径只能经过已证列；相邻脚面差值 `abs(h1-h2)<=1`。
4. 路径末端必须能接到入口脚面；自然地面不足时只能由
   `compileBetween` 生成有支撑的逐级台阶，不能用整面高台伪造入口。
5. `lowerFeetY` 仍受 `[W-32,W)` 和 `drop<=12` 约束；`W-33` 只允许作为支撑
   方块，不作为脚面。
6. 水、PROTECTED、T0缺失、upper-censored、BE、结构盒和不连续路径都 fail-closed。

### 仍然会拒绝的反例

- 外部三宽边界没有任何 `SUPPORTED` 列；
- 外部列到入口之间被保护/水/缺失列切断；
- 唯一连通路径需要相邻脚面差至少2，且没有合法的逐级坡道起点；
- 所有候选起点低于 `W-32` 或达到/超过 `W`；
- 任一 required corridor 的头部净空被真实 T0 方块侵入；
- 只剩一条非三宽旁路，或路径依赖把 `OWNED_VOID` 当自然地面。

旧反例 `GroundEntranceTest.nominalGroundFormulaIsNotAnExitWhenFoundationDropIsNonzero`
必须保留：固定 `W-20` 在 drop=4/5/12 时仍不是入口。B 不是恢复旧公式，
而是从真实外部地面选合法起点后生成可走坡道。

## 3. 只读候选统计

来源：
`docs/evidence/s1c/fort0-revised-foundation-17-20261002-002523/world/greatwall/diagnostics/session-STOPPING.json`
中的 RootSearch 审计，以及已归档
`docs/evidence/s1c/fort0-policy-comparison-20261002/support-preflight.json`。

### 冻结环带采样覆盖

| 项目 | 数量 | 证据级别 |
|---|---:|---|
| 理论环带 | 4×36=144 | 已由冻结公式确定 |
| 实际完成9×9 coarse采样 | 128 | 已验证 |
| 采样前被原 `inRing/inside` 门槛拒绝 | 16 | 已验证 |
| coarse REJECTED | 112 | 已验证 |
| SHORTLIST_ELIGIBLE | 16 | 已验证 |
| 结构清晰 shortlist | 12 | 已验证 |
| 结构冲突 shortlist | 4 | 已验证 |

16 个未进入9×9采样的 `(radiusIndex,angleIndex)` 已逐项写入：
`docs/evidence/s1c/fort0-entry-preflight-20261002/unsampled-ring-points.csv`。
这不是跳过候选，而是冻结 RootSearch 的原始边界门槛结果。

### 16 个 shortlist 候选

完整表：
`docs/evidence/s1c/fort0-entry-preflight-20261002/shortlist-candidates.csv`

| 根点 | radius/angle | wet | coarse高度差 | 结构 |
|---|---|---:|---:|---|
| `(-107,-820)` | 0/26 | 0 | 6 | 冲突 village_plains |
| `(675,734)` | 1/5 | 2 | 10 | CLEAR |
| `(206,953)` | 1/8 | 0 | 12 | CLEAR，已选根 |
| `(32,968)` | 1/9 | 0 | 11 | CLEAR |
| `(-468,834)` | 1/12 | 0 | 10 | CLEAR |
| `(374,-972)` | 1/29 | 0 | 7 | 冲突 village_taiga |
| `(835,926)` | 2/5 | 0 | 10 | CLEAR |
| `(249,1199)` | 2/8 | 0 | 6 | CLEAR |
| `(32,1218)` | 2/9 | 5 | 11 | CLEAR |
| `(-185,1199)` | 2/10 | 0 | 9 | 冲突 village_plains |
| `(-1051,-657)` | 2/21 | 1 | 8 | CLEAR |
| `(-926,-835)` | 2/22 | 0 | 7 | CLEAR |
| `(835,-990)` | 2/31 | 0 | 7 | CLEAR |
| `(990,-835)` | 2/32 | 0 | 6 | CLEAR |
| `(32,1468)` | 3/9 | 0 | 7 | CLEAR |
| `(-228,-1509)` | 3/26 | 0 | 12 | 冲突 trail_ruins |

### 12 个结构清晰候选的入口高度

这里必须区分证据：

- **已验证入口数据：1/12**，仅 `(206,953)`，因为旧运行只把已选根的
  durable T0 写入该隔离证据。
- **NOT_AVAILABLE：11/12**，不是失败，也不是通过。
- 因此“12个各自高差”“36角度入口合格率”无法从现有 T0 合法计算；
 不能用 coarse 9×9 高度差代替入口三宽走廊。

已验证的 `(206,953)`：

| 项目 | 结果 |
|---|---:|
| 入口列 | 190 |
| 分类 | 190 SUPPORTED |
| groundTopY | 67:57、68:96、69:31、70:6 |
| 旧 `max-min<=1` | FAIL，spread=3 |
| 外侧 local z=-24 | 3列，全为68 |
| 逐步连通到 local z=23 | 3/3 外侧起点可达3/4目标列 |
| 连通规则 | 相邻高度差≤1，三宽网格，无跳跃 |
| B 结论 | **可行，已验证于该根** |

上述连通统计只证明自然地形提供了一个可选起点和连续路径；
最终生成仍需由共享证书和 `Ramp.compileBetween` 实测，不能把它写成 APPLY。

## 4. A 的实施点（等待确认后）

1. 新增一个无 Minecraft 世界读取的 `GroundEntranceContract` 纯判定类型；
2. 将 `Ramp.ground` 改为消费该共享证书，删除仅作为拒绝条件的全局
   `max-min<=1`；
3. 将 `RootSearch` 的候选入口预检接到同一判定方法，保持：
   原环带、9×9、48足印、湿度、排序、前8/结构筛选、预算和ROOT_NOT_FOUND；
4. 入口失败的候选在 recipe 冻结前继续按原确定性顺序筛选；
5. 增加反例：无外部路径、差值2断开、protected/T0 missing/drop13、
   旧 `W-20` 高台公式；
6. 运行定向 JUnit/GameTest，之后才启动新的隔离 seed17 真实运行。

### 仍不改的部分

`getChunk/join/get/possibleBiomes`、`ChunkPlanCompiler`负空间权限、
结构保护、BE/tombstone/before-state、S1A日程预算几何、
40ms/25ms、生产接线和 Tan's BLOCKED 均不变。

## 5. 证据与边界

本轮只读证据目录：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\fort0-entry-preflight-20261002\`

- `statistics.json`
- `shortlist-candidates.csv`
- `unsampled-ring-points.csv`
- `execution.json`

本轮没有修改生产源码，也没有修改旧 T0/manifest。S1C-DRAIN-01仍未闭合。
完整入口统计、全候选B实测、真实 seed17 APPLY、voxels、重进不漏段、
Tectonic/C2ME、完整build和S2-S5均 NOT_RUN。
