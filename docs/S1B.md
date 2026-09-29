# S1B 实施进度与阻塞报告

日期：2026-09-29。审查通过的 S1A 基线：`80f2b24`。

**状态：BLOCKED，未完成、未签收，未进入 S1C。**

已按小批推进纯 Java 前置类和 WallSection/Ramp 候选实现。原八册规格、构建版本、依赖校验与 S0 worldgen 接线没有更改。遇到冻结马道坐标的必需地板/净空冲突后停止推进依赖它的城门、收头与生产写入；没有用不完整组件或跳过测试冒充 S1B 完成。

## 1. 子批与范围

| 提交 | 内容 | 当批实际验证 |
| --- | --- | --- |
| `f437fda` | Rasterizer、DecayField、PaletteBinding | 8 项定向 JUnit PASS，严格离线 |
| `920abdb` | ComponentTemplate、SupportGraph、SurfaceProbe | 13 项定向 JUnit PASS，严格离线 |
| `89ad77a` | ComponentAssembly、WALL 单元身份、纯 fixture | 13 项定向 JUnit PASS，含原 CanonicalCodec/ScheduleContract 回归 |
| `b9edd4a` | 墙段、马道候选、完整必需地板冲突检查、边界反例 | 编译通过；最终全量 JUnit 131/135，4 项失败；不作为阶段签收 |

`SupportGraph` 当前提供基础六邻接落地检查、重力支撑迭代清理和纯脚面可达性检查；它尚未完成梁/拱双支座专用验证、所有装饰 survival 和原版 collision shape 复核。`WallSection` 与 `Ramp` 是尚未交付完整 S1B 的候选，不应据类名存在就判定全部规格实现。

## 2. 阻塞证据

### B-S1B-01：H=1/2 必需底入口与顶平台冲突

原文位置：

`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\S1-城墙本体.md:674`

原文要求同时满足：

```text
H=upper-lower，H∈[1,32]
L=ceil(H/2)，R=H-L，e=-27+L，t=(R>0?e-R+1:e)
底入口：x=-29..-27，z=5..7，脚面 lower，至少两格净空
顶平台：x=t-3..t-1，z=4..10，脚面 upper
```

两个边界都有 `L=1,e=-26,t=-26`。在同一个局部列 `(x=-28,z=6)`：

| 高差 | 必需底入口空气 | 必需顶平台地板 | 结果 |
| --- | --- | --- | --- |
| H=1 | y=lower、lower+1 | y=upper-1=lower | 地板占底入口脚部空间 |
| H=2 | y=lower、lower+1 | y=upper-1=lower+1 | 地板占底入口头部空间 |

这是由原坐标直接推出的矛盾，不依赖我写的栅格化、支撑图或原版碰撞近似。上半砖同样侵入规定净空；不允许通过少放顶平台方块来假装满足两个平台。

可独立复现的测试：

`D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\RampSpecificationTest.java`

`frozenSmallRampPlatformsIntersectRequiredEntranceAir(1/2)` 为两个 **PASS 的反例验证**，意思是确认原坐标确实相交，不是确认马道通过验收。

### B-S1B-02：H=32 天然底平台支撑合同缺口

原 localBounds 的最低输出 y 为 `W-32`，却允许 `lowerFeetY=W-32`，其地板/支撑在 `W-33`。例如 W=84，底入口脚面52，支撑方块51，超出模板声明的最低输出52。

`height32LandingSupportFallsBelowFrozenLocalBounds` 验证了这一点。T0 读取窗口已有 W-35 guard，并不缺读取高度；缺的是纯模板/支撑图如何持有并使用只读天然脚面证据，或是否允许输出 AABB 向下多一格的明确合同。当前模板选择拒绝超出声明的候选，未擅自扩大。

这项是接口/输出包络缺口，不声称“现实中无法建32格高马道”。

### H=3 当前实现失败，不等同于 B-S1B-01

`rampHeightBoundary(3)` 也失败。当前平台预留三格净空，上平台地板发生冲突。入口原文仅要求至少两格，这不能直接证明 H=3 的冻结几何无解。它属于候选实现仍未解决的短坡碰撞/通行问题，明确保留为失败，不虚报成设计已被否证。

### 待批准的最小修订方向

1. H=1/2 使用原 XZ 足印内、上下入口不重叠的短坡规则，并明确哪些现有平台公式在该分支不适用；H=3 同时用原版碰撞验证确定净空。不能只删红色测试或将合法高度范围偷偷改为 H>=4。
2. 为 H=32 冻结只读天然底平台支撑证据合同，或明确批准 output localBounds 包含 W-33 的地板。两者不能由当前代码悄悄选择后声称“严格照原规格”。

以上尚未写入原八册或采用到生产实现。需要规格修订确认后继续。

## 3. 最终离线构建

实际执行：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build --rerun-tasks --no-build-cache --console=plain --no-daemon
```

实际输出：

```text
All 1 required tests passed :)
135 tests completed, 4 failed
> Task :test FAILED
BUILD FAILED in 1m 36s
18 actionable tasks: 18 executed
```

- Java 编译、资源处理和 remap 执行通过，但 **整个 build 失败**。生成过 jar 不等于可发布，不交付部署。
- JUnit 24 个 suite，135 项，131 PASS、4 FAIL、0 skipped。原有 S0/S1A 的97项全部 PASS，S1B 新增38项中34 PASS、4 FAIL。
- 4项失败全在 `WallRampTest.rampHeightBoundary`：H=1、2、3、32。H=4、20、31 通过纯模型检查。
- Fabric GameTest **1/1 PASS**，仅既有 `coreLoadsWithoutOptionalIntegrations`。不是 S1B 构件 GameTest，不是 C2ME 100轮矩阵。
- 测试服务器日志仍为 `world generation disabled`，正常保存、关服。没有启用墙生成。
- 所有 Gradle 调用均使用 JDK21、`--offline --dependency-verification=strict`。未升级依赖，未添加代理或绕过校验。
- 保留4项红色回归，没有 `@Disabled`、没有改断言让空组件冒充成功；因此当前分支预期仍是红色 build，等待规格修订与实现完成。

原始证据：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\strict-offline-build.txt
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\gametest-results.xml
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\junit\TEST-local.plaincraft.greatwall.component.WallRampTest.xml
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1b\junit\TEST-local.plaincraft.greatwall.component.RampSpecificationTest.xml
```

同目录 `junit` 保留其余22个 suite 的原始XML；`checks.json` 记录总数、失败项、源文件清单与构建日志 SHA-256。

原始 Gradle 日志第2行自带一个尾空格，保留原始证据，不为消除 `git diff --check` 对该日志的提示而改写输出。生产源码/测试源码的 whitespace 检查通过。

## 4. 验收核对

| 判据 | 预期 | 实际 | 状态 |
| --- | --- | --- | --- |
| 栅格逆变换/负坐标/百万坐标 | 稳定覆盖、floorDiv、单次镜像 | RasterizerTest 5项通过 | PASS，纯JUnit |
| 连贯破败、维护影响、全局S | 顺序无关、required保护 | DecayFieldTest 3项通过 | PASS，局部JUnit |
| 完整组件支撑再裁owner | 缺任何必需列拒绝；必需净空不冲突 | ComponentAssemblyTest 3项通过 | PASS，纯JUnit；无writer接线 |
| 基础连通/重力/两格净空 | 去孤块，必需断支撑拒绝 | SupportGraphTest 3项通过 | PASS，基础子集 |
| 三层guard/支撑0、4、5、12、13 | 前四可支撑，13断；最低读W-35 | SurfaceProbeTest 10项通过 | PASS，冻结分类fixture |
| 墙体20高10宽/顶部收8宽/6净道 | 名义墙段满足 | 单一平坦直段检查通过 | PASS，局部JUnit |
| STONE/DEEPSLATE/SANDSTONE同几何 | 三族占位一致 | 完好墙段三族比较通过 | PASS，局部JUnit |
| 0/15/30/45/60/90°墙顶连通 | 旋转后完整路径可达 | 旋转站点链fixture纯BFS通过 | PASS，纯模型；原版碰撞NOT_RUN |
| 马道1..32完整可行 | 入口/顶平台齐全可达 | H=1/2/3/32未通过 | BLOCKED/FAIL |
| 弯曲墙、真实坡道阶梯形状、全部破败四态 | 全组件、真实碰撞稳定 | 未完成全覆盖 | NOT_RUN |
| 敌楼11×11、1/2/4chunk、各层可达 | 完整构件与owner重放 | EnemyTower尚未实现 | NOT_RUN |
| 烽火台四火塘同ID | 四块未点燃且有支撑 | BeaconTower尚未实现 | NOT_RUN |
| Gate/WaterGate/EndCap/FortPlaceholder | 通路、49/50/51、通地出口 | 尚未实现 | NOT_RUN |
| 梁拱双支座、全部装饰survival、无孤立残片 | 全模板复核 | SupportGraph尚未完成对应扩展 | NOT_RUN |
| Realization/ChunkPlanCompiler/ChunkWallWriter | 整件决议、owner限制、proof预检 | 尚未实现 | NOT_RUN |
| 生产T0/metadata/markers/coordinator | 冻结coverage、耐久proof、复用原T0 | 未改造，仍为已签收S0实验实现 | NOT_RUN |
| S1B ComponentGameTests/ProductionBarrierTests | 全表通过 | 尚未编写/运行 | NOT_RUN |
| S0 100轮、worker1/2/8/16重跑 | 接入后无回归 | 未接入，本轮未重跑 | NOT_RUN |
| 原有97项JUnit+已有1项GameTest | 通过 | 全部通过 | PASS，仅既有覆盖 |
| S1B全部签收 | 严格离线build及全部门槛通过 | build失败且大量集成项未实施 | BLOCKED |

## 5. 新建与修改文件

生产源文件（均纯 Java；尚无 S1B worldgen 写入）：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\geometry\Rasterizer.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\decay\DecayField.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\decay\PaletteBinding.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\decay\SupportGraph.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\SurfaceProbe.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\ComponentTemplate.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\ComponentAssembly.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\WallSection.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\Ramp.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\model\WallTypes.java
```

`WallTypes` 仅追加 `SiteKind.WALL`，为普通64格墙单元提供不与站点冲突的身份；原站点编号不动。其余原有 S1A 类型未重写或回滚。细节在补充合同中公开记录。

测试源文件：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\geometry\RasterizerTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\decay\DecayFieldTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\decay\SupportGraphTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\generation\SurfaceProbeTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\TemplateFixtures.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\ComponentAssemblyTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\WallRampTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\component\RampSpecificationTest.java
```

报告与补充合同：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B.md
D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1B-补充合同.md
```

## 6. 复现命令

只验证冻结坐标矛盾（预期成功，表示反例成立）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*RampSpecificationTest'
```

复现未完成的马道边界验收（预期4项失败）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test --tests '*WallRampTest'
```

完整构建按第3节命令。单独定向测试会覆盖 build 目录中的当次JUnit报告，因此最终全量原始XML已复制到 docs/evidence，不用后续少量测试的成功覆盖本次失败结论。

## 7. 边界与后续恢复

- 没有同步加载世界 chunk，没有 future join/get，没有调用 possibleBiomes。新代码不持有 Minecraft 世界对象。
- 工作 HashMap/队列仅存在于单次纯函数调用/独占构件缓冲器，输出使用不可变 record 集合；没有新共享可变容器。
- S0 的锁外 future、startup pump、PREPARE→CAPTURE→APPLY 与永久 T0 文件未改。也没有新增/清空永久历史。
- 未触碰真实 mods、真实存档、旧世界回填；未使用网络补依赖；项目代理设置扫描无匹配。
- 未改 RoadWeaver/C2ME/黄金树交互。S1C Tan's Huge Trees 与 S5 整包 BLOCKED 边界继续保留，未删除 mod。
- 后续先确认/冻结马道修订规则并让红色测试真实转绿，再完成 WallSection/Ramp 的所有约束、其余构件及生产屏障，最后执行完整 GameTest 和 S0矩阵；不从当前候选直接跳到 S1C。
