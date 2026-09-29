# 本地 ABI、构建接线与证据边界

日期：2026-09-29。本册不是测试通过报告。标记含义固定：**[本地事实]**是实际读取的文件/字节码；**[推导]**是从这些输入推出的约束；**[设计]**是本次冻结的新合同；**[待执行]**是必须由未来编译/游戏证据关闭的验收。没有联网查新版本，没有运行Gradle、游戏或24小时测试。

## 1. 输入与基线

已读取的四份指定材料，SHA-256如下。实现前比较文件hash；发生变更时先做规格差异审查，不能把更晚内容默默混入本次配方。

| 输入完整路径 | SHA-256 |
|---|---|
| `D:\_文档\万里长城-mod设计文档.md` | `0c6ac80219238fff02043e72922c79eafde65fa441c62c0be3ce9117032d8e17` |
| `D:\云游四海-Mod源码\plaincraft-great-wall-终审与实施方案\02-完整实现方案.md` | `40a27957bf4dc79d23df7fd223ec5341baf2cdb9bc0f9b8a51855501004b1504` |
| `D:\云游四海-Mod源码\plaincraft-great-wall-终审与实施方案\03-接口算法与FTB.md` | `237b2533eb24607baf5004800d94cb3ddae4668df18962ad6e37a57d33fc480c` |
| `D:\云游四海-Mod源码\plaincraft-great-wall-终审与实施方案\04-阶段0与验收.md` | `b76e618be97080e0bf0bbdd069d69ed024a2a840384d93446e9dafdcb91b69db` |

另读了交接、前次README/终审/证据索引，以及S0实际代码和报告。工程基线完整commit：`5ad1a3789523d37852509267cec982e0c18bf9ad`。本次产出在`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\`，不是新生产代码。

### S0 已签收范围 [本地事实]

| 文件 | 能证明什么 / 不能外推什么 |
|---|---|
| `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S0-01-runtime.md` | 原版/Tectonic/C2ME CPU启动与整包客户端菜单；不能证明OpenCL真实生成。原样整包DedicatedServer因mobility失败已单列。 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S0-02.md` | 假规划器startup pump、锁外依赖及失败/取消；不能证明真实CPU oracle切片。 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S0-03.md` | 有限实验屏障、800轮/3200owner、八项恢复、原生QoS对照；不是城墙生产空间覆盖。 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s0-03\adapter\` | 运行变换类/原生锁入口证据；生产变更后需重导出验证。 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s0-01\environment.json` | 已锁本地依赖路径/SHA；其中早期`runtimeCompatibilityVerified=false`不是更新后的阶段签收结论。 |

S0已报告的BUILD SUCCESSFUL和22/22 JUnit是过去签收证据，**本轮没有重跑**。S0最大43.50ms样本不等于S5全部slice≤40ms。自有T0序列化到6006不等于整包BlockPos/BE/网络6000通过。

## 2. 版本、依赖和可选编译

核心版本冻结：Java21/Mojmap、Gradle9.4.1、Loom1.16.3、Loader0.19.3、FabricAPI0.116.14+1.21.1、JUnit5.11.0/Platform1.11.0。S1–S5不新增下载要求、不改为Yarn、不使用动态版本。

### 本地可选 JAR [本地事实]

下列文件都在只读输入目录`D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\mods\`。相对文件名只是本表展示，实际Gradle输入先解析绝对路径并核hash。

| 文件名 | SHA-256 |
|---|---|
| `[FTB任务] ftb-quests-fabric-2101.1.24.jar` | `dcdc4ac4fbf441647d9dda16444ef81bcb029c7ead760904737e7715ee03aabe` |
| `ftb-library-fabric-2101.1.31.jar` | `34f11f4a1a09dd5f58e19ea3da92e8b17d8f36d2790c9712042f16d201fb1b3e` |
| `[FTB 团队] ftb-teams-fabric-2101.1.10.jar` | `bc398b9f7837447c701ed3cae2dffadb35270340d82abdb72e97265c3583386f` |
| `Oh-The-Trees-Youll-Grow-fabric-1.21.1-5.3.2.jar` | `584277ba83f9178ee123725be3156b4f51320721627216a93772ae7008620d93` |
| `plaincraft-golden-erdtree-sanctuary-0.7.15-lss-hold-api.jar` | `d53d2a528ee71772545779cf72686161ac58df718ac5bcded971a6999afe8d7f` |
| `tectonic-3.0.26-fabric-21.1.jar` | `6653be7cb1574e5973cde0a8cacb9d79d9e55a99bf7378937e0c47979b718b67` |
| `bettersmoke-0.1.2+1.21-1.21.8.jar` | `88261a89b352da671d4d7a7eaabbd8123496ef6f76f5c184ccced48bad0b8b7b` |

Architectury运行输入按fabric.mod.json的id=`architectury`、version=`13.0.8`定位唯一JAR，已读SHA=`10cbbb6f5f96a2f1853b0cc68428424cec8903409517b299ff02350756b6399d`。不得猜本机带中文前缀的文件名。C2ME实际patched主包SHA=`85de278c4acfddbaa748c1e72facaa7e13901f3980e5deb2c65ab7064fc8c460`，OpenCL模块SHA=`2148e5d022b64cbd37096e7b4da8e10e236771281c98cbc202d5d03774e211e1`，完整路径用现有environment.json解析。

### Gradle 接线合同 [设计]

实施时修改`D:\云游四海-Mod源码\plaincraft-great-wall\gradle\production-tests.gradle`并由根build.gradle apply。先读现有`gradle/smoke-tests.gradle`：**serverSmoke源集已经存在**，只能扩展，不能重复创建同名源集。现有Fabric测试源集沿用`gametest`，client-only仍是`client`。

```text
初始化：
  local dependency manifest字段={id,version,path,sha256,role}，路径是机器本地输入，不写进发布Jar。
  逐文件先Files.isRegularFile，再SHA256全文件，匹配才加入file collection。
  FTB quests/library/teams/architectury、TYG、Golden及其sapling接口都用Loom modCompileOnly(files(...))。
  不能用普通compileOnly直接吃intermediary的Minecraft方法名；不能用Maven坐标隐式拉传递依赖。
  缺输入时说明id/期望SHA并阻塞对应实现编译，不网络下载/不自动换版本。
  运行optional profile显式modRuntimeOnly同一集合；core-absent profile不加这些runtime文件。
  common常量/字段/公开签名不得引用可选类型；只在isModLoaded成功后的专属入口加载兼容类。
  optional Mixin均由ProbeMixinPlugin按存在性与协议启用；启用后require=1。
  MixinExtras沿用Loader已有依赖，不另升版本；本地缺@Local/WrapOperation则编译失败不得换伪注入。
校验：
  文件依赖的SHA检查是Gradle依赖校验之外的补充，不以文件依赖逃避验证。
  dependencyLocking/verification-metadata原有严格策略保留。
  本地新增已声明的文件输入先记录来源SHA；不执行旧writeLocalVerification任务覆盖metadata。
  任何缺的Maven模块元数据都阻塞；当前任务没有再次联网补缓存授权。
运行：
  仅build/run/<stage>/<runId>新目录，marker与路径双检查。
  assets只用已签收本地复用脚本；不调用下载asset修复路径。
  原样整包intermediary运行沿用S0生产命名空间启动器，不强行用Loom开发运行器绕已知remapper失败。
```

RoadWeaver必须先完成S2 SPI fork后生成**新**Fabric remapJar，再作为GreatWall `modCompileOnly(files(...))`本地输入。当前安装的0.8.405 JAR没有新SPI，不能对它编译新接口。Road自己的`gradle.properties`是Loader0.16.14/API0.104.0/Architectury13.0.6，**保持它自己的锁**，不拿GreatWall版本去全局覆盖邻工程。其现有Fabric Shadow/remap流程保持，先按其离线构建锁成功出包，再记录新源码commit/JAR SHA和protocol1。若邻工程缺严格校验元数据，只可据已有本地文件生成待审清单并复测，仍不得联网、关闭verification或假称编译通过。

执行命令的唯一基础形式：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build
```

整包实际OpenCL/native相关模块声明Java>=25，已在S0验证。构建一直Java21；额外整包游戏进程沿用本地JDK25.0.3，报告单列，不宣称Java21能加载原样OpenCL整包。没有代理配置新增。

## 3. Mojmap ABI 对照 [本地事实]

核对工具：`C:\Program Files\Java\jdk-21\bin\javap.exe`。核心named JAR：

`C:\Users\18364\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2\minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar`

| 短名/调用 | 完整类型或约束 |
|---|---|
| ServerLevel / ServerPlayer | `net.minecraft.server.level.ServerLevel` / `net.minecraft.server.level.ServerPlayer` |
| WorldGenLevel / WorldGenRegion | `net.minecraft.world.level.WorldGenLevel` / `net.minecraft.server.level.WorldGenRegion` |
| ChunkAccess / ProtoChunk / LevelChunk | `net.minecraft.world.level.chunk`包；不可将getChunkNow当作原始T0 |
| SavedData | `net.minecraft.world.level.saveddata.SavedData`；override `public CompoundTag save(CompoundTag,HolderLookup.Provider)` |
| getDataStorage | `net.minecraft.world.level.storage.DimensionDataStorage`；坏.dat不可由computeIfAbsent返回新空账本掩盖 |
| RegistryFriendlyByteBuf | `net.minecraft.network.RegistryFriendlyByteBuf`；自有pos用writeInt×3 |
| CustomPacketPayload / StreamCodec | `net.minecraft.network.protocol.common.custom.CustomPacketPayload` / `net.minecraft.network.codec.StreamCodec` |
| GameTestHelper / GameTest | `net.minecraft.gametest.framework.GameTestHelper` / `net.minecraft.gametest.framework.GameTest` |
| NoiseChunk / NoiseSettings / RandomState | `net.minecraft.world.level.levelgen`包；getBaseColumn使用完整clamp噪声高度与真实fluid picker |
| DensityFunction | `net.minecraft.world.level.levelgen.DensityFunction`；scalar compute与fillArray在所有已装适配器下是否一致仍待测 |
| StructureTemplate | `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate`，getSize()/save(CompoundTag)存在；StructureTemplateManager.get(ResourceLocation)返回Optional |
| BaseSpawner | `net.minecraft.world.level.BaseSpawner`；serverTick(ServerLevel,BlockPos)，spawnDelay为private；原生每次出生最终调用tryAddFreshEntityWithPassengers |
| Mob | `net.minecraft.world.entity.Mob`；getLootTable是final，不能override；没有setLootTable；用定向Mixin及持久标识 |
| RandomizableContainerBlockEntity | `net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity`，setLootTable(ResourceKey\<LootTable\>)/setLootTableSeed(long)存在 |
| NaturalSpawner | `net.minecraft.world.level.NaturalSpawner`；方法实际拼作isValidSpawnPostitionForType，S3不得擅改英文拼写 |
| ItemStack.useOn | 调Item.useOn之前执行冒险CanPlaceOn检查；火把hook放在这个内部调用处，不在早期事件绕保护 |
| CampfireBlock | 原版火把不会点燃；打火石/火焰弹可点燃；雨不自动熄灭；本任务自定义火把入口见S3 |
| LevelRenderer | `net.minecraft.client.renderer.LevelRenderer.renderLineBox`；1.21.1不使用后来版本的ShapeRenderer名称 |
| WorldRenderEvents / WorldRenderContext | `net.fabricmc.fabric.api.client.rendering.v1`；AFTER_ENTITIES时matrixStack/consumers有效；顶点减camera位置 |
| VertexConsumer / Font | `com.mojang.blaze3d.vertex.VertexConsumer`；addVertex(Pose,...)、setNormal(Pose,...)；`net.minecraft.client.gui.Font.drawInBatch` |

S0 `TerrainEngine`的实际完整接口：

```java
package local.plaincraft.greatwall.generation;
public interface TerrainEngine {
    <T> java.util.concurrent.CompletableFuture<T> exclusive(
        net.minecraft.world.level.ChunkPos owner, java.util.function.Supplier<T> action);
    String name();
}
```

S1保留此授权入口，不让CPU executor绕过它直接setBlock。S0 TerrainTile.Key是`(String dimension,int chunkX,int chunkZ,int minY,int maxY)`，maxY闭端、跨度差<=127；其state索引`((y-minY)*16+localZ)*16+localX`。S1页基对齐是调度策略，不能改Key字段含义。Baseline公开API未在分册列为替换者完整保留，以基线源码为精确签名；新增重载不删除实验测试调用。

已核对锁文件中的`fabric-lifecycle-events-v1:2.6.0+0865547519`源码：MinecraftServerMixin在createWorlds的Map.put返回后同步派发ServerWorldEvents.LOAD。因此选prepareLevels HEAD而非“本mod自己的LOAD listener先后”作为全部LOAD回调结束的冻结点；真正第三方Mixin顺序仍由ABI-05启动trace验证。WorldBootstrap.spawnChosen先记录，prepareLevelsEntered再允许独立root pump开算。

## 4. FTB ABI [本地事实]

详细字节码档案：

- `D:\云游四海-Mod源码\plaincraft-great-wall-终审与实施方案\evidence\ftb-custom-api.txt`
- `D:\云游四海-Mod源码\plaincraft-great-wall-终审与实施方案\evidence\ftb-load-and-task.txt`

本轮又直接读取已安装JAR确认以下接口，下面以Mojmap列出已重映射后的Minecraft参数名：

```java
// dev.ftb.mods.ftbquests.quest.task.TaskType.Provider
Task create(long id, dev.ftb.mods.ftbquests.quest.Quest quest);
// dev.ftb.mods.ftbquests.quest.task.TaskTypes
static TaskType register(net.minecraft.resources.ResourceLocation id,
    TaskType.Provider provider, java.util.function.Supplier<dev.ftb.mods.ftblibrary.icon.Icon> icon);
// dev.ftb.mods.ftbquests.quest.task.CustomTask.Check
void check(CustomTask.Data data, net.minecraft.server.level.ServerPlayer player);
// dev.ftb.mods.ftbquests.events.CustomTaskEvent
CustomTask getTask();
// EVENT: dev.architectury.event.Event<dev.architectury.event.EventActor<CustomTaskEvent>>
// dev.ftb.mods.ftbquests.events.ClearFileCacheEvent.EVENT
// dev.architectury.event.Event<java.util.function.Consumer<dev.ftb.mods.ftbquests.quest.BaseQuestFile>>
```

`Task.onCreated()`是final；只注册CustomTaskEvent，不重写这个方法。GUI服务端编辑回调是`editedFromGUIOnServer()`。CustomTask三个同步setter会加入TaskSync.toSync：客户端readNetData禁止调用它们。Check是void，不是Predicate。World级计数由自家账本投影，不把FTB团队progress当去重数据库。任务类型与资源在init注册；账本到WorldServices就绪后才读取，两者不能倒置。

## 5. RoadWeaver / C2ME / 黄金树 [本地事实 + 设计界面]

| 工程与实际文件 | 已核实事实 | 本次设计接法 |
|---|---|---|
| `D:\云游四海-Mod源码\roadweaver\common\src\main\java\net\shiroha233\roadweaver\api\RoadNetworkApi.java` | registerStructureEndpoint三种重载，四参数autoConnect不被读取；StructureInfo使用Y0；ensureConnection有三/四参数重载 | S2新增持久幂等registerOnce，真实Y由GateLedger；ensureConnection(...,false)显式触发 |
| `D:\云游四海-Mod源码\roadweaver\common\src\main\java\net\shiroha233\roadweaver\features\path\LoadedChunkRoadBackfill.java` | 路面回填不是墙洞施工 | WallArchBackfill按自有before/after proof补洞，Road只消费ready |
| `D:\云游四海-Mod源码\roadweaver\common\src\main\java\net\shiroha233\roadweaver\planning\terrain\RoadTerrainPlanningPipeline.java` | 原入口同步；A*内部不能等待长城规划 | fork的planAsync锁外prepare快照，原plan在快照scope内同步纯算 |
| `D:\云游四海-Mod源码\roadweaver\fabric\src\main\java\net\shiroha233\roadweaver\planning\fabric\ServerPlanningHooks.java` | SERVER_STARTED先做生命周期/cache/threadpool，再发现结构、recover道路 | 新ready promise在这些操作之后完成，端点副作用等它；wall planner不等它 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\c2me\C2meProbeAdapter.java` | S0已包住实际delegate的锁外依赖入口并恢复positioned executor | S1只换生产future来源，不把beforeFeatures搬进runTaskWithLockArea内部 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\c2me\C2meTerrainEngine.java` | 运行已核实WORLDGEN owner区域锁 | T0页复制/owner写仍走它；不自行换锁实现 |
| `D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\GoldenErdtreeSanctuaryMod.java` | LOAD时register sampler Context，后面可激活lazy树；UNLOAD时unregister | prepareLevels HEAD之后捕获实际sampler对应Context，不虚构ready事件 |
| `D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\SanctuaryRuntime.java` | Context查询依赖sampler身份；affectsTerrain/riverStrength/riverWaterLevel/targetSurfaceTop/solidFoundationY/biomeAt实际存在 | 私有CPU oracle不能换sampler后假定自动命中；冻结adapter显式调用原Context |
| `D:\云游四海-Mod源码\plaincraft-erdtree-sapling\src\main\java\local\plaincraft\erdtreesapling\ErdtreeGenerationManager.java` | prebakeBounds(ServerLevel)可返回null；lazy完整边界为闭chunk范围 | 非null转完整保护盒；null不猜树径；snapshot从已安装manifest区域声明保护 |
| `D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\ErdtreeSnapshotInstaller.java` | 可在新世界安装预烘焙成品区域，并写snapshot marker | 这些FULL成品不是可重新CAPTURE的自然新chunk；范围保护并记录断口，不挖穿黄金树 |

S2所有`api.interop`类型、registerOnce/planAsync/whenReady均为**将新增**的fork API，当前未存在。没有“已装Road即自动具有SPI”的假设。

## 6. 植被适配的精确边界

TYG5.3.2两个TreeFromStructureNBTFeature类已读。place流程为：取base/canopy模板→选palette与旋转→定位白羊毛中心→采height→填3个map→校验→首次写log→叶→其它→decorators。V1/V2首次写块调用前的三个Map局部变量在实际JAR分别是slot28/29/30，顺序leaves/logs/other；S1使用按类型ordinal的@Local，不把slot号当长期稳定API。

配置record的baseLocation()/canopyLocation()/height()/maxLogDepth()/treeDecorators()/randomRotation()/orientation()真实存在；V2 leavesProvider是List，不能强转V1配置。本规格不再使用不存在的“模板池最大冠幅API”。

Tan's已读输入：

- `D:\云游四海-Mod源码\plaincraft-roadweaver-tans-compat\src\main\java\local\plaincraft\roadweavertanscompat\mixin\TansHugeTreeReservationMixin.java`
- `D:\云游四海-Mod源码\roadweaver\common\src\main\java\net\shiroha233\roadweaver\features\path\pathlogic\core\HugeTreeReservation.java`
- `D:\云游四海-Mod源码\tanshugetrees\upstream-reference\tannyjung\tanshugetrees_handcode\systems\world_gen\TreePlacer.java`
- 当前安装`[巨树移植]tanshugetrees-fabric-rebuild-0.1.1-local.jar`的TreePlacer/Caches字节码。

实际新版private place尾部有`boolean worldGeneration`参数，旧源码的无boolean签名不能直接抄；形状short[]四元组含树干/枝/叶，现有Road reservation只取类型1100..1199。FULL后写和Function.run不能由FEATURES屏障自动约束。[推导]当前证据不足以把原样Tan's纳入认证。[设计]S1明确拒绝此profile生产授权；S5原样整包项保持BLOCKED，不删mod后冒充通过。这是本交付明确暴露的额外兼容缺口，**不是声称S1–S5代码已经全量完成或整包可以发行**。核心五阶段实现规格不包含重写Tan's生成器。

## 7. 必须阻塞签收的运行门槛 [待执行]

| 编号 | 具体检查 | 失败行为 |
|---|---|---|
| ABI-01 | 独立core客户端/专用服无FTB/Road/TYG/Golden时能加载 | 软依赖泄漏即修正入口，不能改成硬depends掩盖 |
| ABI-02 | 实际C2ME变换类中规划future位于区域写锁外，原调度器保持 | 拒绝生产生成；不改为join或同步getChunk |
| ABI-03 | 完整NoiseChunk点级slice与原getBaseColumn逐状态一致，含Tectonic/6000/GPU旁路 | 不启用该oracle；不偷偷换海平面/粗采样当参考 |
| ABI-04 | TYG两注入各命中一次且首写前取消为0写块；所有注册树bounds包含真实写集 | 对该profile阻塞，不把require改0 |
| ABI-05 | spawn/LOAD/prepareLevels时序与Golden Context/完整保护边界闭合 | 未知late writer禁止认证，无“等正常tick”救启动 |
| ABI-06 | Road fork所有A*、平滑/重规划/修复路径都使用prepared snapshot | 任一路漏扫均S2失败，不能仅证明SPI被注册 |
| ABI-07 | FTB读取、编辑、新队、离线追平、前置/奖励逻辑及dedicated无client linkage | 不跨过FTB原有完成链强制发奖 |
| ABI-08 | 6000实际BlockPos打包、BE/网络/marker往返 | 原版或别的mod不支持就记录能力失败，不只测本mod三个int |
| ABI-09 | 真实OpenCL生成+冷远跳+完整24h+图册/人工实走 | 菜单/短测试不能代签 |

## 8. 原 §5/§6 的覆盖索引

这是测试定位表，不是PASS表。每个方法的参数/预期见对应阶段末尾；GameTest要有真实运行XML及日志。

| 原清单 | 实施位置及测试组 |
|---|---|
| §5.1 确定性/接缝/负坐标/溢出/真实日程/terminal | S1A FixedMathTest、RidgePlannerTest、ScheduleContractTest、PlannerSchedulerTest、HydrologyTest；含route-halo依赖无环和跨段同河证据测试 |
| §5.2 截面/支撑/哨兵/楼梯/水门/破败/owner | S1B TemplateTest、SurfaceProbeTest、ComponentGameTests、ProductionBarrierTests |
| §5.2 群系与第三方后写 | S4 PaletteTest/PaletteGameTests；S1C VegetationGameTests/OracleGameTests/BootstrapGameTests |
| §5.2 玩家改块/cache rebuild | S2 OwnershipMutations与RoadGateGameTests；S5 RecoveryTest |
| §5.3 6000全部坐标路径 | S1C OracleGameTests；S3 IgnitionGameTests.highYUsesThreeInts；S5 DiagnosticsTest.payloadUsesThreeIntsAndBounds |
| §5.4 道路全部 | S2 RoadGateGameTests/RoadContractTest、Road fork的WallInteropTest；S5 NegotiatedGateGameTests |
| §5.5 驻军/配额/自然刷怪 | S3 GarrisonPolicyTest/ExplorationGameTests，含499/500/501、全部四态、临界64与reload overflow |
| §5.5 点火/物理入口/反刷/FTB全矩阵 | S3 BeaconLedgerTest/IgnitionGameTests/FtbGameTests；S4 BetterSmoke正交视觉 |
| §5.6 崩溃/损坏/世界串线/磁盘满 | S5全部fault points两mode各10轮+RecoveryTest/LifecycleGameTests |
| §6 切片/零阻塞/缓存背压/24h/画质 | S5性能规程与StressController；不得按测试未执行返回默认PASS |

本次冻结但非用户原话的设计值包括：gate=800k±100、fort=4000m±500；四态阈值/材质概率；具体房室和loot表；W脚面约定与3层基础guard；4个firepit共享BeaconId；WAL先消费后生成的at-most-once；超额重载实体按明文退场；新资源指纹拒绝继续新生成。它们均在各类中有确定行为，执行者不自行另选。用户尺寸、间距范围、夜间/出生保护、世界计数、三红线和五P0优先级更高，不能以这些细化改变它们。

## 9. 本交付的验收口径

文档层可以核对：文件存在、章节/签名、类依赖、原测试覆盖、无占位决策、引用的本地API证据、只新增docs。文档层不能核对：未编写代码的javac成功、Mixin命中、玩法手感、图册画质、服务器性能与完整整包兼容。

执行者首次PR先提交S1A公共类型/纯函数及测试，随后按全局拓扑推进。任何设计合同和已锁实际ABI冲突必须保留最小编译/字节码反证，回到方案审查修正规格；不得编造现存API、悄改依赖版本，或在报告中把未运行项目勾通过。
