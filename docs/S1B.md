# S1B 验收与阻塞报告

日期：2026-09-29。S1A 审查基线：`80f2b24`。本次实现末提交：`eae8993`；保留失败反例的源码提交：`1d4ff21`。

**阶段状态：BLOCKED。未完成、未签收，未进入 S1C。**

用户批准的 H=1/2/3/32 修复已完成，原四个红项现在均通过，原公式反例仍保留。后续已实现构件、owner writer、耐久 T0/proof 与生产屏障策略，并完成下面列明的验证。当前不能签收的主要阻塞是 **B-S1B-03：有限收头候选全部失败后，跨 cell 退回前一个已验证地面出口的决议尚未实现**；也仍有第5节列明的规格细节未完成。

此前等待马道批准的报告已原样归档到：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\pre-approval-report.md`

旧报告、旧131/135结果和旧四项红色日志都是历史，不是当前结果。原八册未被静默重写；授权修订及新增接口闭包见 `docs/S1B-补充合同.md`。

## 1. 本次严格离线结果

实际执行，退出码 **1**：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build runGameTest --continue --rerun-tasks --no-build-cache --console=plain --no-daemon
```

`--continue` 只让其它验收任务也跑完，不跳过失败、不关闭校验、不把失败改成成功。

关键输出：

```text
All 17 required tests passed :)
167 tests completed, 1 failed
BUILD FAILED in 2m 5s
18 actionable tasks: 18 executed
```

- Java/客户端/GameTest编译和 remap 执行完成；**整个 build 失败，不可发布**。
- JUnit：34个 suite，167项，166 PASS、1 FAIL、0 skipped。
- 唯一 FAIL：`GroundExitFallbackTest.exhaustedCapSearchMustFallBackToTheEarlierGroundExitWithoutFatalGeneration`。
- Fabric GameTest：17/17 PASS，正常保存、关服。
- 没有 `@Disabled`、排除红项、空组件冒充成功或永久关闭依赖校验。
- Java21、Mojmap、Gradle9.4.1、Loom1.16.3、Loader0.19.3、Fabric API0.116.14+1.21.1及JUnit5.11.0均保持原锁定版本。未下载依赖，未添加代理。

最终原始证据：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\strict-offline-build.txt
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\gametest-results.xml
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\junit\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\checks.json
```

## 2. 冻结公式修订与反例

| 原条目 | 真实矛盾/缺口 | 已采用的最小修订 | 反例及验证 |
| --- | --- | --- | --- |
| H=1/2/3 全部套 U形两跑平台 | H=1/2上下平台地板侵入必需净空；H=3候选也碰撞 | 仅H=1..3使用原足印内不重叠短坡；H≥4保留原两跑公式 | `RampSpecificationTest`保留原H1/H2坐标反例；`WallRampTest`逐一验证1..32全部平台、支撑和净空 |
| Ramp localBounds.minY=-32 | lower=W-32 的地板在 W-33 | minY=-33，只多容纳这一层；不改探测窗口/支撑drop | 保留H32原包络反例；H32完整构件通过 |
| 敌楼 maxYExclusive=W+7 | 最后脚面W+5所需三格净空包括W+7 | maxYExclusive=W+8，只增加空气包络 | 11×11实体尺寸不变；6朝向、各层通地原版碰撞通过 |
| 收头退让被实现成沿旧切线平移、沿用旧W | 弯道/坡道离开站点合同；反向端点错误减S | 每4S采样真实位置、W、切向；方向取端点朝向与合同切向点积 | `capRetreatFollowsBendAndHeightInsteadOfOriginalTangent`保留原位置反例 |
| 默认 Ramp/Gate 入口固定 W-20 | drop=4/5/12时填实后仍为高台，不等于通地 | 默认入口取完整入口足印已探明的自然地面，足印高差≤1；明确协商高度仍用原显式接口 | `defaultRampGroundEntranceTracksActualSupportedGround`、`filledFoundationsDoNotPretendTheirTopIsNaturalGround`及旧式高台反例通过 |

H=1..32 合法范围未改。walkY合同未改。探测窗口仍 `[walkY-32,walkY+6]`，只读三层guard可到W-35；drop上限仍12。没有降分辨率、缩小构件或放宽支撑来换性能。

另外补足了冻结签名原先缺失的数据闭包：`SiteKind.WALL`、`ComponentVoxels.realizedState`、`WriteMetadata`、生产marker完整字段、耐久capture intent等；旧构造器保留，原几何和T0-v1字节协议不变。详细合同在补充文档中逐项列明。

## 3. 已完成的实现批次

每批生产类控制在1～3个；测试、合同说明与对应类一起提交。

| 提交 | 内容 |
| --- | --- |
| `2cf4090` | 短坡/最低地板修复，保留1..32验收 |
| `4ead623` | EnemyTower、BeaconTower、旋转功能格一对一绑定 |
| `54f9a15` | Gate、EndCap、完整组件合并 |
| `196ae43` | WaterGate、48×48 FortPlaceholder |
| `6ad0fd7`、`22793f1` | ChunkPlanCompiler、写入metadata、破败状态传递 |
| `b346946`、`4adf82f` | 冻结BlockCatalog、OwnershipGuard、原生writer/markers |
| `87b1588`、`bec35d7` | 生产存证、coverage、capture intent |
| `904a483` | TerrainSnapshotService、SnapshotColumnView |
| `0bb8ff1`、`49bad68`、`1dd1fb7` | RealizationPlanner、逐列破败、真实曲线回退、末段裁切与终点原因 |
| `cec91b6` | 生产版PREPARE→CAPTURE→APPLY与原版构件验证；S0实验策略保留 |
| `a1934a9` | 显式双支座支撑组、原版船体通航及源水测试 |
| `b7617df` | 拒绝IO后的future/claim计数清理、marker完整payload校验 |
| `eae8993` | 默认入口真正落到已证自然地面 |
| `1d4ff21` | 保留跨cell出口回退的失败验收 |

`BlockCatalog` 的箱子属性序列化已由 Java `toString()` 修为原版 `Property.getName(value)`，实际含箱子的整件写入反例发现该问题。S0 token 和 T0-v1 hash 不因这项生产序列化修复改变。

## 4. 验收核对与范围

| 判据 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- |
| 马道H=1..32 | 完整平台、两格净空、可达 | 原四红项及全部32高度的纯几何检查通过 | PASS；全部高度逐一原版实体运动尚NOT_RUN |
| 默认地面入口drop0/4/5/12 | 入口在地面，不是高台 | 默认马道/城门/收头局部测试通过 | PASS，解析T0 |
| 支撑drop0/4/5/12/13 | 前四支持、13拒绝 | SurfaceProbe边界/guard/遮蔽/保护测试通过 | PASS |
| 墙体20×10、收分、6格净道 | 尺寸不变 | 纯几何通过；六朝向原版碰撞形状连通通过 | PASS，平坦代表fixture |
| 三材质族同占位 | 几何一致 | 完好墙段比较通过 | PASS，局部；不是全构件全四态图册 |
| 敌楼11×11/1、2、4owner | 所有层可达、重放同结果 | 原版ProtoChunk写入、逆序重放和原版碰撞检查通过 | PASS |
| 烽火台四火塘同身份 | 不重复、不缺失、未点燃、有支撑 | 六朝向身份测试、破败功能删除、原版连接更新通过 | PASS，S3玩法未启用 |
| 门洞不被马道堵住 | 上下通路完整 | Gate纯测试和原版碰撞检查通过 | PASS，代表fixture |
| 收头通地、弯坡回退 | 正常候选真实可达 | 常规收头/三种落差/曲线重采样通过 | PASS，局部 |
| 所有128格候选失败后退至旧出口 | 跨cell一致、不能fatal代替 | 解析山脊反例可稳定复现缺口 | **FAIL / B-S1B-03** |
| 河流49/50/51 | 49跨、50/51不建水门 | 模板拒绝阈值、近侧cap、纯terminal决议通过 | PASS，各自测试范围 |
| 原版船体穿主拱 | 无方块碰撞阻塞 | 实际Boat.move在隔离测试世界横穿物理主拱切片 | PASS；非玩家驾驶/长期浮力与流体压测 |
| 源水不抬面不填净空 | 原水面以下source | 原版BlockState检查和4宽3高主通道检查通过 | PASS |
| 无孤岛、重力、拱双支座 | 必需支撑不能假落地 | 纯支撑反例通过，城门接入双支座；代表废墟及楼梯/铁栏原版邻接更新通过 | PASS，明确子集，见未完成项 |
| 整件决议→owner裁切→proof预检 | 不挖未知方块，不越界 | 顺序/hash/BE保护、全部写集预检、编辑后重放测试通过 | PASS |
| 原T0耐久复用 | APPLY后不能重采墙 | 多页/20并发请求、磁盘逐字节重读、晚到复用通过 | PASS，原生ProtoChunk及Vanilla mailbox |
| 历史/证据损坏 | fail closed | 缺页/缺proof/CRC/身份/marker payload测试通过 | PASS，对当前存证协议 |
| IO拒绝/释放 | 无永久挂起 | 300次拒绝IO、flight/claim/admission归零通过 | PASS |
| 生产完整屏障路径 | proof durable后exclusive写，回调锁外 | 解析合同+真实ProtoChunk/Vanilla mailbox完整链路及player edit重放通过 | PASS，FEATURES由fixture提供 |
| S0百轮矩阵 | 1/2/8/16同输入同输出 | 8项全部通过，共3200owner | PASS，S0实验屏障，不冒充生产墙C2ME接线 |
| S1B整体 | 所有规定行为和build通过 | 一项红色验收 + 未完成细节 | **BLOCKED** |

GameTest对角色通路使用原版碰撞形状、0.6×1.8身体、半格节点检查，不是让真实玩家或寻路AI逐条走完全路。17项原始方法名和结果在最终XML中，不据测试名字扩大覆盖范围。

## 5. 阻塞与未完成项

主阻塞详细报告：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-跨单元出口阻塞.md`

本例中前一个实际可行出口可能在128格回退/当前cell有限邻域之外。当前输入/缓存没有跨cell接受出口证明，也没有在所有相关owner APPLY前传播截断边界；现有代码只能 fail closed。不是旧马道批准仍未处理，更不是悄悄把异常当“成功断口”。

另外以下范围尚未完成，**不列为PASS**：

- Ramp固定四候选择优和完整外沿护栏尚未闭合；当前正常模板路径已验证，不等于四候选合同齐备。
- WaterGate全部次跨的余数合并、自然薄冰主通航清除/旁冰保留策略、水门的显式双支座分组尚待完成；本次双支座已接城门。主通航和基本墩身规则已实现。
- Realization维护影响目前来自计划中未抑制的站点，尚未收敛为仅“经完整T0接受的站点”；跨站点/水门/收头相邻冲突的完整矩阵未跑完。
- 敌楼/关隘在非零基础落差下的每个外部地面入口、全部转弯坡度、所有装饰的原版survival尚未全覆盖。
- T0/compiled字节限额已有检查，仍需压力验证峰值对象占用、在分配前的完整预留和长期pin行为；不以逻辑估算冒充实测内存上限。
- 生产FEATURES真请求端口与真实CPU地形/C2ME生产墙的全链路验证为NOT_RUN；当前完整pipeline测试使用解析规划和预置FEATURES ProtoChunk。
- S1C植被抑制/实际生命周期切换未实施。Tan's Huge Trees保护不足的profile仍拒绝授权；S5整包仍BLOCKED，没有删mod冒充兼容。

## 6. S0矩阵证据

实际命令：

```powershell
.\tools\Test-TerrainMatrix.ps1 -EvidenceStage s1b -Rounds 100
```

脚本内部每次均使用 JDK21、`--offline --dependency-verification=strict`，新建隔离世界；第三方jar只从本地实例复制并核对锁定SHA，不修改源实例。

| profile | workers | rounds | owners | 结果 |
| --- | --- | --- | --- | --- |
| vanilla | 1 / 2 / 8 / 16 | 各100 | 各400 | 全PASS |
| tectonic-c2me | 1 / 2 / 8 / 16 | 各100 | 各400 | 全PASS |

比较的是每个profile内跨并发的逐owner T0/realization/final hash；不宣称 vanilla 与 Tectonic 应具有相同地形。每个owner都验证捕获→落盘→写入时间顺序、native线程、FEATURES而非FULL请求、晚到T0复用、关服lease清零。

矩阵执行于 `cec91b6` 实现基础；之后变化在生产构件/生产存证/生产策略，S0实验执行路径未改。最终源码再执行完整离线构建和17项GameTest。没有将本矩阵包装成新生产墙C2ME运行证明。

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\matrix\results.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\matrix\<run-id>\execution.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\matrix\<run-id>\terrain\
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\runtime\
```

## 7. 文件清单与复现

**全部新增/修改的源文件、测试、脚本及报告均以绝对路径列在机器可读清单中**，避免手写清单漏项：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\files.json`

该清单对比 `80f2b24`，覆盖本阶段所有已提交代码及本次脚本/报告变动；每项含类别、完整路径、SHA256。大量矩阵原始文件另有：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\continuation\final\matrix-files.json`

修复后的马道与原公式反例：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*RampSpecificationTest' --tests '*WallRampTest' --tests '*GateCapTest'
```

预期PASS。新的跨cell失败：

```powershell
.\gradlew.bat --offline --dependency-verification=strict test --tests '*GroundExitFallbackTest'
```

预期1 PASS、1 FAIL。完整构建见第1节；单独跑GameTest：

```powershell
.\gradlew.bat --offline --dependency-verification=strict runGameTest
```

预期17项PASS。定向运行会覆盖build内报告，因此完整验收的日志/XML已复制到docs独立保存。

## 8. 操作边界

- 未部署到真实mods，未打开真实存档，未增加旧世界回填。
- 正式生产世界生成仍关闭；仅显式构造生产策略及隔离fixture执行实际方块写入。
- 未同步worldgen getChunk / future join/get，未调用possibleBiomes。扫描中的 `String.join` 是字符串拼接，不是future等待。
- 高Y坐标存三个int；没有把6000高度压入BlockPos.asLong。
- T0/proof是耐久证据，不因缓存缺失静默重建；永久历史不被清空。
- C2ME适配器的锁外订阅、native WORLDGEN锁调用和positionedExecutor保持既有实现。启动pump仍保留，不改用普通tick兜底。
- 本次没有改RoadWeaver或黄金树代码；检查了本地C2ME适配源码与原版命名jar的相关方法，不凭名称猜ABI。
- 未升级任何依赖、未设置代理、未联网补包。

由于整体build仍红且第5节尚未闭合，本报告是明确的阻塞验收记录，不是交付签收。不能输出“S1B完成，请审查”。
