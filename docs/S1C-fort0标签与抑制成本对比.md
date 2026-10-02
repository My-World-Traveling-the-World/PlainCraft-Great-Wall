# fort0：标签补齐与 FEATURES 写前抑制成本对比

日期：2026-10-02，Asia/Shanghai。检查点：`ffc7c76`。
**仅分析，等待方案确认。生产源码、资源标签、构件几何、原 T0/manifest 均未修改。**
正式生产 OFF，Tan's BLOCKED，25ms/40ms 与原保护层不变。

## 1. 结论与建议

优先选择 A 作为**分类遗漏的最小修正**，不先建设 B 的 FEATURES 抑制系统。
但不能把 A 当成“补两份 JSON 就能完成 seed17 写墙验收”：
本轮只读预检已确认，在旧 fort0 frame/T0 上按 A 模拟新捕获，
入口 190/190 列 SUPPORTED，但认证地面为 67..70，差值 3；
冻结的 `Ramp.ground` 要求最大差值 <=1，所以仍会拒绝。

苔石仅可替换、不作承重的廉价变体也已模拟：认证地面为 67..69，
差值 2，仍拒绝。下一步应先对齐**根候选认证与实际入口认证**的契约，
而不是先放宽坡度或花更大成本建设 B。此处不实施新的根筛、模板修改或整平。

上轮直接优先建议 B 过早：没有先把标签遗漏、负空间权限和认证坡度分开；
本轮补齐该成本分析，不再以“材料放行”推导“墙必定写出”。

## 2. 分类是否成立

证据分级：“已验证/诊断”指本地原生方法或归档 T0 的只读计算；
“源码核实”指指定代码/原生资源；“推理”指尚未实施方案的影响判断。
这些都不等于新标签已在真实 worldgen 验收通过。

| 方块 | 判断与证据 |
|---|---|
| mossy_cobblestone | **作为承重材质成立**。本地 Mojmap 1.21.1 原生 default state 的碰撞体为完整方块，UP face sturdy=true，无流体/BE，非 FallingBlock。拟议 flags 为 support=true、replaceablePlant=false；见 support-preflight.json 的 nativeProperties。 |
| sweet_berry_bush / brown_mushroom | **作为显式可替换植物成立**。原生类分别为 SweetBerryBushBlock/MushroomBlock，无 BE/流体、非重力块；当前缺标签，拟议 flags 为 replaceablePlant=true、support=false。仍需真实标签加载后测试。 |
| pumpkin | **可作为显式自然可替换植物类，但不是空气状植物**。原生 PumpkinBlock 是完整碰撞方块，UP sturdy=true；拟议政策仍 support=false，不因其实体形状顺带授予承重或负空间权限。原版 patch_pumpkin 确有自然生成配置。 |
| magma_block | **保持保护成立**。当前及两种拟议模型均 protectedBlock=true、support=false；捕获 token 为 fluid=minecraft:empty。它不是 lava 流体，此处不能把“岩浆块”误说成实际 lava；保护不得依赖这种错误归因。 |

“苔石是石类”与“苔石用于装饰 feature”**不是互斥分类**：
原生 `configured_feature/forest_rock.json` 的 state 正是 mossy_cobblestone，
`placed_feature/forest_rock.json` 使用 MOTION_BLOCKING heightmap 放置。
这是原生资源事实，不证明旧 T0 中每一块苔石必定来自这个 feature，也不证明
玩家建筑或注册结构中的苔石可以放行。结构盒、显式保护和玩家修改规则必须另行优先。

### 三层支撑的具体证据

未以“石类看起来坚固”代替验证：直接调用原生
`isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)` /
`isCollisionShapeFullBlock`，两者均 true。
在拟议 support 标签模型下，旧 T0 入口有 6 列被现有 `SurfaceProbe`
选中为 groundTopY=70，三层真实 token 均为苔石。例如 `(194,957)` 的
y=69/68/67 均为 mossy_cobblestone，结果 SUPPORTED、drop=0。
该认证仍要求**每一层**通过 support；矿石/砾石没有进入支撑集合。
苔石下夹空气、软床或矿石时，不能根据一个苔石顶面就宣称三层成立。

## 3. 成本与风险对比

| 方案 | 改动面/成本 | 风险与限制 | 是否改变冻结输入 |
|---|---|---|---|
| A：苔石加入 foundation_support；三植物加入 replaceable_vegetation | 生产改动可限于两份 tag JSON；另需分类、支撑、保护优先、负空间反例及定向运行证据。冻结 catalog 查表路径不新增 worldgen 阶段等待。 | **代码成本低，语义影响不能称零风险**：新增支撑改变探针选中的地面、入口高度、各构件支撑/净空；标签是按方块种类，不证明来源。新 hash 仅新世界。当前预检已知入口高度差失败。 | 改变 BlockCatalog.hash → terrain 指纹 → recipe hash。模板几何/编译器不变。实际 jar 源哈希还参与 templatesHash；不将“几何未变”写成“所有哈希未变”。 |
| A'：苔石仅加入 natural_replaceable；三植物加入 vegetation | 也是两份 JSON，但不触及 support 初表。 | 苔石 support=false，不能把凸起计作承重；入口依然差值 2。苔石在 OWNED_VOID 处仍拒绝。不能保证更可建。 | 同样改变 catalog/recipe；仅可替换政策不同。 |
| B：有限足印 FEATURES 写前抑制 | 需来源/ABI核实、覆盖所有目标 feature 写集的 guard 或 hook、已有快照生命周期接线、异常/取消/无循环依赖测试、真实重复生成验证；代码面和验证成本明显大于 A。**具体类数/工时未估造。** | 不能把现有 TreeFeature hook 当成 forest_rock/random_patch 覆盖。必须在首写前判定完整有限写集，不按方块类型事后删世界；不能抑制注册结构/岩浆。改变 native 随机消费或其他 feature 依赖的影响须实测。即使去掉凸起，也不自动解决天然入口坡度。 | 若保持 catalog，可不改支撑初表；但新增生成政策/实际 mod 源哈希仍必须绑定新 recipe。对 S1C worldgen/植被协议的改动更大，不修改已签收构件几何也不能当成“冻结输入未变”。 |

以上是**代码/验证成本推理**，不是 A/B 性能 benchmark。A 的查表复杂度不变，
不能因此宣布每 chunk 成本 PASS；B 的实现/40ms稳态数据均 NOT_RUN。

## 4. 改前入口预检：已经看到下一层

方法：独立诊断程序使用现有 `WorldManifest`、`SegmentCacheStore`、
`ChunkEvidenceStore` 解码旧隔离 seed17 的真实 frame/T0；
用本地原生碰撞/流体属性模拟标签，**调用未改动的 SurfaceProbe**。
不使用 highestNonPlantFeet 代替 groundTopY，不重跑 seed17，不写世界。

| 范围 | 当前分类复现 | A 模型 | A' 模型 |
|---|---|---|---|
| 全读取包络 3600 列 | SUPPORTED3344 / PROTECTED256，与真实日志一致 | SUPPORTED3597 / PROTECTED3 | SUPPORTED3597 / PROTECTED3 |
| 实际入口 190 列 | 旧材料阻塞保留 | SUPPORTED190 | SUPPORTED190 |
| 认证 groundTopY 分布 | 不将 protected 默认 ground 当真实高度 | 67:57、68:96、69:31、70:6 | 67:65、68:101、69:24 |
| max-min | 不用旧高度摘要判签收 | **3，超过1，入口仍失败** | **2，超过1，入口仍失败** |
| 模型编译/运行 | 基线分类断言必须吻合；不吻合即失败 | exit0 | exit0 |

反例两端：`(199,952)` 认证脚面67，三层 grass_block/dirt/dirt；
`(194,957)` 认证脚面70，三层 mossy/mossy/mossy。190 行逐列结果保留，
不是只比较两个手选点。A' 仍有24列脚面69，说明问题不全来自苔石承重。

源码依据：

- `src/main/java/local/plaincraft/greatwall/generation/SurfaceProbe.java:35`：
  全可能写域先查保护；`:46` 开始逐层认证三层支撑。
- `src/main/java/local/plaincraft/greatwall/component/Ramp.java:39`：
  按实际旋转入口逐世界列读 support；`:52` 要求 `max-min<=1`。
- `src/main/java/local/plaincraft/greatwall/component/Fort.java:64`：
  fort0 主入口使用这个判据。
- `src/main/java/local/plaincraft/greatwall/lifecycle/RootSearch.java:208`：
  根点自然48×48足印只要求全承重/无水且 `max-min<=12`；
  这不等于装饰后的实际旋转入口差值<=1。

### 两个不能混淆的剩余风险

1. **3列岩浆都在读取包络、但不在实际 fort 局部足印**：
   `(195,57,923)` local(-32,-30,3)；
   `(196,57,923)` local(-31,-30,2)；
   `(197,59,923)` local(-31,-28,1)。
   均不在 X/Z[-24,24)。不能把读取包络 PROTECTED3 直接说成
   “fort 必定撞岩浆”；仍保持其保护，后续其他构件是否相交 NOT_RUN。
2. **额外源码风险【推理，未运行验收】**：
   `Fort.java:186` 用 local x/z 查 `supports`；
   `RealizationPlanner.java:411` / `:413` 构建的是 world x/z 键。
   当前远离原点的 fort 可能在入口之后被这个全足印检查拒绝。
   此处记录给审计，不改模板、不把它归因成已发生的本轮失败。

## 5. 负空间与其他护栏

`ChunkPlanCompiler.java:63` / `:162` 的负空间允许集是固定 switch，
不是 replaceable_vegetation tag。新加的苔石/浆果丛/蘑菇/南瓜都不在其中。
所以 A 只使探针承重/植物分类更准确，**不授权 OWNED_VOID 挖它们**；
若拟议写集的 VOID/FLUID 命中这些真实 before-state，仍 OWNERSHIP_CONFLICT。
不能为消红把它们加入编译器白名单。当前入口失败在编译器之前，
新方案整构件的负空间逐 voxel 验收仍 NOT_RUN。

所有方案保留显式 protected、BE、注册结构盒、tombstone、before-state。
玩家同种方块不得因新增标签获得覆盖许可；材料分类不是玩家来源证明。
旧永久 T0 中的 immutable 标记也不能因标签变化删除。模型只在内存模拟
**未来新 recipe/new capture**，旧存档/旧隔离世界不迁移、不回填。

## 6. 证据、命令与边界

工程绝对根：`D:\云游四海-Mod源码\plaincraft-great-wall\`。
本轮新增仅：

- `docs\S1C-fort0标签与抑制成本对比.md`（本报告）。
- `docs\evidence\s1c\fort0-policy-comparison-20261002\PolicyPreflight.java`：
  独立分析源码，不属于任何 Gradle sourceSet，不进 mod jar。
- 同目录 `Run-Preflight.ps1`、模型结果、命令/失败日志、
  本地原生 feature JSON 和 SHA-256 来源清单。

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
.\docs\evidence\s1c\fort0-policy-comparison-20261002\Run-Preflight.ps1
```

该脚本用已有严格离线构建的 classpath bridge、本地 JDK21，独立 javac 后
两个 JVM **串行**分析；无下载/依赖升级/Gradle worldgen 测试任务。
前提是原有隔离 fixture 和 classpath bridge 尚保留。证据
`final-execution.json` 记录编译exit0、两模式exit0及诊断源码SHA。
`support-preflight.json` / `replace-only-preflight.json` 保存完整190列，
`native-resource-evidence.json` 保存本地 Mojmap jar SHA及抽取资源SHA。

模型限制：从本地 tag 资源递归展开集合，不是新世界已加载 tag 的实测；
Bootstrap 未绑定 fluid tags，vanilla-only 模型用原生 WATER/FLOWING_WATER/
LAVA/FLOWING_LAVA 身份判定；保留旧上限摘要（当前范围未被上限删失）。
只对这份原始 T0/frame 与列域有效；最新全 heading 根筛或新 hash 的新世界
可能选择不同根，不能把这些数字当成新世界预测保证。

失败保留：首次诊断未绑定 fluid tags，基线误判为 PROTECTED343，
校验拒绝exit1；随后一次报告写出因 OptionalInt 反射失败exit1，
partial JSON 不作结论。修正只涉及分析工具：显式原生流体身份、显式摘要字段；
最新基线精确复现3344/256后才记录模型结果。before/serialization-failure
日志均保留，不修改生产流体或保护规则。

本轮全量JUnit/build/GameTest、标签实际实施、新seed17 APPLY/voxels、
退出重进不漏段、成功每chunk成本均 **NOT_RUN**。历史350/350不改写成
本轮新跑。`S1C-DRAIN-01` 仍未闭合，旧 STOPPING flights=8/admitted=8；
没有“已正常排空”结论。S1C未签收，正式production OFF，Tan's BLOCKED。
