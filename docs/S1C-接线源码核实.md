# S1C 接线源码核实与实施准备

核实日期：2026-09-30（本机时间，Asia/Shanghai）。

## 0. 范围、状态与并行写入边界

**这是准备文档，不是 S1C 实施、测试或签收报告。S1B 未签收，S1C 生产接线保持关闭。**

- 本次唯一写入文件：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-接线源码核实.md`。
- 未改生产代码、构建配置、规格、真实 mods 或存档；未联网，未运行 Gradle、游戏或测试，未执行 git add/commit。
- 本次起始只读核对的 HEAD 为 `eba113a8b95b171f35e9b99b37573126dd08d7d7`，不是早先任务描述中的 `b46ab38`。工作区已有其他执行者的 S1B 修改，本次未覆盖、回滚或纳入自己的产出。
- `ProductionWallPipeline`、`TerrainSnapshotService`、`ChunkPlanCompiler` 等为并行修改中的文件；下面记录的是本次阅读时的工作区事实。S1B 签收后，主代理必须对照最终提交复核这些入口，不把本文件当作未提交实现的版本锁。
- Tan's Huge Trees profile 仍为 **BLOCKED**。原样整包不得因移除 Tan's 后部分测试通过而改写为通过。

证据等级：

| 标记 | 含义 |
|---|---|
| FACT-SOURCE | 本次实际读取本地 Java/Gradle/PowerShell/JSON 文件；只证明源码内容 |
| FACT-ABI | 本次使用 JDK21 的 javap 或只读 ZIP/SHA 核对了实际锁定 JAR；只证明符号/字节码 |
| DERIVED | 根据已读代码推导的接线条件或风险，不等于运行结果 |
| TODO | S1B 签收后实施的具体工作；本次未实施 |
| NOT_RUN | 本次未编译、未执行或尚无本次运行证据 |

已读取指定输入：

1. `D:\云游四海-Mod源码\plaincraft-great-wall\开工交接.md`。
2. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\S1-城墙本体.md`，S1C 全节（约 1033–1595 行）。
3. 补充核对 `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\06-本地ABI与证据.md`。其中过去的运行结论没有在本次重跑，不转述为本次 PASS。

## 1. 本地证据路径与身份

### 1.1 RoadWeaver 树钩子家族

以下三个文件均已逐个读取（FACT-SOURCE）：

```text
D:\云游四海-Mod源码\roadweaver\fabric\src\main\java\net\shiroha233\roadweaver\mixin\fabric\TreeFeatureMixin.java
D:\云游四海-Mod源码\roadweaver\fabric\src\main\java\net\shiroha233\roadweaver\mixin\fabric\tyg\TreeFromStructureNBTFeatureMixin.java
D:\云游四海-Mod源码\roadweaver\fabric\src\main\java\net\shiroha233\roadweaver\mixin\fabric\tyg\TreeFromStructureNBTFeatureV2Mixin.java
```

| 类 | 已读实际行为 | 长城接线限制 |
|---|---|---|
| 原版 TreeFeatureMixin | `place(FeaturePlaceContext)Z` HEAD、可取消；`RoadPositionQuery.isOnRoad(ctx.level(),ctx.origin())`；`catch(Throwable ignored)` | 只复用目标类/方法形状。长城改为完整树盒与完整墙足印外扩 32 的相交判断，不复用只判树根/吞异常逻辑 |
| TYG V1 Mixin | 目标 `TreeFromStructureNBTFeature`，HEAD，`require=0`，也只判 origin | 长城使用首写前精确三张位置表，`require=1` |
| TYG V2 Mixin | 目标 `TreeFromStructureNBTFeatureV2`，同上 | 独立 V2 注入和配置类型，不把 V2 强转为 V1 |

因此“复制 RoadWeaver 家族”是复用已核实的目标覆盖，不是逐字照抄其漏冠幅、可静默失效的实现。S1C 不依赖 S2 的 Road SPI，不向 Road 注册端点。

### 1.2 C2ME 使用 0.4.0-alpha.0.27 的实际本地证据

本次读取的反编译源码：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\build\inspection\c2me\decompiled\com\ishland\c2me\rewrites\chunksystem\common\statuses\VanillaWorldGenerationDelegate.java
```

ABI 核对所用 JAR：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\build\inspection\c2me\c2me-fabric-rewrites-chunk-system-mc1.21.1-0.4.0-alpha.0.27.jar
D:\云游四海-Mod源码\plaincraft-great-wall\build\inspection\c2me\c2me-fabric-base-mc1.21.1-0.4.0-alpha.0.27-all.jar
```

它们与当前只读输入主包内对应 `META-INF/jars/` 条目的 SHA-256 **本次已重新比对一致**：

| 对象 | SHA-256 |
|---|---|
| 当前 patched 主包 | `85de278c4acfddbaa748c1e72facaa7e13901f3980e5deb2c65ab7064fc8c460` |
| rewrites-chunk-system 内包 | `3d1224750abbca60d02e9c06720cb07e2f309a7376262e5d56c80b5bec11f4c7` |
| base 内包 | `e04c6cbc4f7bed2b514715f48bb725b894f69e1f890866b980aabc9dff0c6efd` |

主包完整路径：

```text
D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\mods\[C^2M 引擎] c2me-fabric-mc1.21.1-0.4.0-alpha.0.27-plaincraft-qos-direct.0.3.6-avx2-isa-clamp.jar
```

历史来源记录可复核：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s0-03\runtime\s003-tectonic-c2me-c1-65da1c7d-inputs.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s0-03\adapter\tectonic-c2me-VanillaWorldGenerationDelegate.txt
```

没有使用 `D:\辅助源码\c2me` 的旧源码证明新 ABI。当前 patched delegate 包含 `local.plaincraftc2meqos.FeatureOrderCycleFallback.wrap(...)`；不能用未打补丁的 upstream delegate 推导当前异常/跳过行为。

### 1.3 TYG、黄金树的名称与实际工程

本地实际名称已从当前 JAR 的 `fabric.mod.json` 读取：

- `ohthetreesyoullgrow` = **Oh The Trees You'll Grow**，5.3.2；不是黄金树圣域。
- `plaincraft_erdtree_sanctuary` = **PlainCraft Golden Erdtree Sanctuary**，0.7.15-lss-hold-api。
- `plaincraft_erdtree_sapling` = **PlainCraft Golden Erdtree Sapling**，1.5.0。
- 在本次列举的两个源码根目录及按 tree/golden/erdtree/yellow 名称筛选的 mods 中，没有识别出叫 “The Yellow ...” 的独立工程。因此不以这个不完整称呼假造类型或将其与 TYG 等同。黄金树兼容依据下面两个实际工程；TYG 另行核对。

本次实际读取：

```text
D:\辅助源码\ohthetreesyoullgrow\common\src\main\java\dev\corgitaco\ohthetreesyoullgrow\world\level\levelgen\feature\TreeFromStructureNBTFeature.java
D:\辅助源码\ohthetreesyoullgrow\common\src\main\java\dev\corgitaco\ohthetreesyoullgrow\world\level\levelgen\feature\TreeFromStructureNBTFeatureV2.java
D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\GoldenErdtreeSanctuaryMod.java
D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\SanctuaryRuntime.java
D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\mixin\NoiseBasedChunkGeneratorMixin.java
D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\mixin\ChunkGeneratorMixin.java
D:\云游四海-Mod源码\golden-erdtree-sanctuary\src\main\java\local\plaincraft\erdtreesanctuary\ErdtreeSnapshotInstaller.java
D:\云游四海-Mod源码\plaincraft-erdtree-sapling\src\main\java\local\plaincraft\erdtreesapling\ErdtreeGenerationManager.java
```

TYG 配置 record、Golden Runtime/Context 与 sapling PrebakeBounds 方法另用实际 JAR 的 javap 交叉核对（FACT-ABI），没有把辅助源码的版本默认视为当前版本。

| 当前输入 JAR（目录与 §1.2 主包同为只读 mods 输入） | 本次 SHA-256 |
|---|---|
| `Oh-The-Trees-Youll-Grow-fabric-1.21.1-5.3.2.jar` | `584277ba83f9178ee123725be3156b4f51320721627216a93772ae7008620d93` |
| `plaincraft-golden-erdtree-sanctuary-0.7.15-lss-hold-api.jar` | `d53d2a528ee71772545779cf72686161ac58df718ac5bcded971a6999afe8d7f` |
| `[黄金树苗]plaincraft-golden-erdtree-sapling-1.5.0.jar` | `633e4c775df432c6c0102219e603729fc13636b8ca8eb211058a30b00693c6ac` |
| `[巨树移植]tanshugetrees-fabric-rebuild-0.1.1-local.jar` | `fd4ddd38c13f792c09de0e849bce4274f9a3e34c93737af65d57b286f09ce04f` |

## 2. 现有长城入口与精确可复用签名

以下签名均来自本次读取的现有源码；类型短名沿用原文件 import，不新造同名类型。没有 checked throws，除非显式写出。

```java
// local.plaincraft.greatwall.planning.PlannerScheduler
public PlannerScheduler(WorldRecipe recipe,
        java.util.function.Supplier<NaturalTerrainOracle> oracleFactory,
        SegmentCacheStore cache, java.util.concurrent.Executor io);
public PlanLease<RouteShape> requireRoute(long segment, Priority priority);
public PlanLease<SegmentGeometry> requireGeometry(long segment, Priority priority);
public PlanLease<SegmentPlan> requirePlan(long segment, Priority priority);
public PlanLease<PlanWindow> requireWindow(ChunkKey chunk, int marginBlocks, Priority priority);
public void startupPump();
public void tick(SliceBudget budget);
public java.util.Map<java.util.UUID, JobProgress> progress();
public java.util.Map<String, Long> diagnostics();
public void close();

// local.plaincraft.greatwall.terrain.NaturalTerrainOracle
public interface NaturalTerrainOracle extends AutoCloseable {
    interface ColumnCursor { boolean done(); }
    ColumnCursor begin(int x, int z, SampleKind kind);
    WorkState advance(ColumnCursor cursor, int primitiveLimit);
    ColumnSample finish(ColumnCursor cursor);
    String fingerprint();
    void close();
}

// local.plaincraft.greatwall.generation.TerrainEngine
public interface TerrainEngine {
    <T> java.util.concurrent.CompletableFuture<T> exclusive(
            net.minecraft.world.level.ChunkPos owner, java.util.function.Supplier<T> action);
    String name();
}

// local.plaincraft.greatwall.generation.WallStageCoordinator
public WallStageCoordinator(ServerLevel level, WorldServices session, WorldRecipe recipe,
        PlannerScheduler planner, TerrainSnapshotService snapshots,
        RealizationPlanner realization, ChunkPlanCompiler compiler, ChunkWallWriter writer);
public CompletableFuture<Void> beforeFeatures(ChunkKey key);
public CompletableFuture<Void> beforeLight(ChunkKey key, ChunkAccess owner, TerrainEngine engine);
public CompletableFuture<Void> verifyLoaded(ChunkAccess chunk);
public void close();

// local.plaincraft.greatwall.generation.FeaturesLease
public FeaturesLease(ServerLevel level, java.util.List<ChunkPos> positions, StageTrace trace);
public CompletableFuture<java.util.Map<ChunkPos, ChunkAccess>> ready();
public void close();

// local.plaincraft.greatwall.lifecycle.WorldServices: 当前仍为 S0 API
public static void register();
public static WorldServices find(MinecraftServer server); // nullable，不是 Optional
public static void warming(MinecraftServer server);
public WallStageCoordinator barrier(ServerLevel level); // nullable
public void assertActiveSession();
public CompletableFuture<ProbePlanner.Plan> require(String dimension, int x, int z, String stage);
public void failBootstrap(Throwable cause);
public void failRequiredPlan(Throwable cause);

// local.plaincraft.greatwall.compat.c2me.C2meProbeAdapter
public static io.reactivex.rxjava3.core.Completable gate(
        Object context, Object delegate, io.reactivex.rxjava3.core.Completable original);
public static <T> java.util.function.Supplier<CompletableFuture<T>> instrument(
        java.util.function.Supplier<CompletableFuture<T>> action, String token);
public static Object invoke(Object receiver, String method);
```

补充保留：

- `VanillaTerrainEngine(ServerLevel)` 使用原 `worldgenMailbox`；不是“模拟 C2ME 区域锁”。
- `C2meTerrainEngine(Object manager)` 使用真实 delegate 的 `runTaskWithLockArea(owner.x,owner.z,1,1,manager,action)`。
- `TerrainChunkAccess.greatwall$request(int,int,ChunkStatus,boolean)` 返回 `CompletableFuture<ChunkResult<ChunkAccess>>`，Invoker 目标是 `ServerChunkCache.getChunkFutureMainThread`，由 native chunk event loop 提交，不是同步 `getChunk`。
- `ChunkMapAccess.greatwall$chunkMainExecutor()` 返回 `BlockableEventLoop<Runnable>`；`greatwall$worldgenMailbox()` 返回 `ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>>`。
- `FeaturesLease` 现有输入上限为 81 个位置，按 FEATURES ticket level 持有租约；`ready()` 返回 copy。不能给它塞入整条植被预取范围的大列表。
- `PlannerScheduler.requireWindow` 的 margin 是 **0..512**，单窗口最多 5 段。植被最大半径 2048 的规格不意味着可以直接传 2080。
- `WorldServices` 当前仅在 `greatwall.s0.probe=true` 且存在 `greatwall_test_world` 时建立会话。`GreatWallMod` 仍打印生成禁用；不能仅替换日志当接线。
- `GenerationResourceFingerprint.capture(ServerLevel,ResourceManager) throws IOException` 和 `WorldRecipeGuard.validate(Map<String,JsonElement>)` 保留实验语义；生产方法按规格另加，不破坏 S0 证据。
- `BlockCatalog`、`CoveragePlanner` 已由 S1B 建立，不重复新建。`ProtectionSnapshot`、`CpuTerrainOracle`、`WorldManifest`、`WorldBootstrap` 和树家族在本次检查时尚不存在。

下面是冻结规格要求的 **待新增** WorldServices API（TODO），不能以它们已存在为前提直接改调用端：

```java
public WorldRecipe recipe();
public static java.util.Optional<WorldServices> find(java.util.UUID session);
public java.util.concurrent.CompletableFuture<Void> requireProduction(
        String dimension, int chunkX, int chunkZ, String stage);
public PlannerScheduler planning();
public WorldBootstrap bootstrap();
public VegetationGuard vegetation();
public java.util.concurrent.CompletableFuture<Void> closeProduction();
```

`requireProduction` 非主世界立即完成；bootstrap 只对已授权 earlyDomain 放行，其余必须等待 ready，再获取真实窗口和 beforeFeatures。返回调用者视图；失败保留原 required-plan fatal 通道。上述 UUID 重载不能改变原 `find(MinecraftServer)` 的 nullable 返回合同。

## 3. C2ME 锁外 future 入口

### 3.1 实际 ABI（FACT-ABI）

以下以 named Minecraft 类型表示；C2ME 自身类/方法名不重映射。已安装内包 javap 的 `ChunkPos` 为 `net.minecraft.class_1923`，`ChunkMap` 为 `net.minecraft.class_3898`，不是可在 Mojmap 代码中直接照抄的类名。

```java
// com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate
public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable);
public static <T> CompletableFuture<T> runTaskWithLockArea(
        int baseChunkX, int baseChunkZ, int sizeX, int sizeZ,
        com.ishland.c2me.base.common.scheduler.SchedulingManager manager,
        java.util.function.Supplier<CompletableFuture<T>> action);

// com.ishland.c2me.base.common.scheduler.SchedulingManager
public java.util.concurrent.Executor positionedExecutor(long chunkPos);

// com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext
public net.minecraft.server.level.ChunkMap tacs();
public com.ishland.c2me.base.common.scheduler.SchedulingManager schedulingManager();
// holder() 返回 ItemHolder<ChunkPos,ChunkState,ChunkLoadingContext,NewChunkHolderVanillaInterface>
```

`C2meGateMixin` 的现有注入描述符必须原样保留：

```text
upgradeToThis(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;
```

### 3.2 已核实的锁时序

源码为 `FeatureOrderCycleFallback.wrap(Completable.defer(() -> fromCompletionStage(runTaskWithLockRadius(...))), this, context)`。`runTaskWithLockArea` 构造 WORLDGEN LockToken 列表与 ScheduledTask，随后 `schedulingManager.enqueue(task)`。

因此现有 **upgradeToThis RETURN 包装 + 规划完成后才订阅 original** 是锁前入口。将等待移入 action supplier、`runTaskWithLockArea` 内部或 FEATURES 已执行后的 hook 都不满足 P0-01。

后续接线顺序（TODO）：

```text
收到 FEATURES delegate：
  解析当前 server session / dimension / ChunkPos / positionedExecutor。
  实验模式保留原 require + delayFeatures。
  生产模式取得 requireProduction(...,"c2me:features") 的调用者 future。
  future 成功后 observeOn(原 positionedExecutor)，验证 session 仍 active。
  此时才订阅 original；保留 SUBSCRIBING 的 set/try/finally remove 与锁证据。
  原 FEATURES 完成或失败后释放 vegetation owner 租约。
  取消只取消调用者视图并释放自己的租约，不能取消共享规划真值。
  异常继续进入 failRequiredPlan/MinecraftServer.setFatalException。

收到 INITIALIZE_LIGHT：
  锁外调用生产 beforeLight(ChunkKey, 当前 holder 的 ChunkAccess, C2meTerrainEngine)。
  完成后恢复 positionedExecutor，再订阅原 initialize_light。
  不用 production.layout().owns(...)，因为 production.layout() 明确抛异常。
```

原 `AtomicReference.get()`、`Supplier.get()` 是第三方状态读取/函数调用，不是 `Future.get()`；红线禁止的是 worldgen 同步等待 future 和同步取 chunk，不能靠字符串搜索误删正常接口。本模组新增代码不得增加后者。

`C2meFeatureSkipMixin` 与 patched QoS 失败见证保留；跳过原 FEATURES 不能记录为真实 T0 完成。变换后的 Mixin 入口、订阅次数、取消和 `WAIT_WHILE_LOCKED=0` 仍需新运行证据（NOT_RUN）。

## 4. 真实 CPU oracle 与启动顺序

### 4.1 Mojmap 字节码依据（FACT-ABI）

本次 javap 使用：

```text
C:\Program Files\Java\jdk-21\bin\javap.exe
C:\Users\18364\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2\minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar
```

已核实：

```java
// net.minecraft.world.level.levelgen.RandomState
public static RandomState create(NoiseGeneratorSettings settings,
        net.minecraft.core.HolderGetter<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> noise,
        long seed);

// net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
public net.minecraft.core.Holder<NoiseGeneratorSettings> generatorSettings();
public net.minecraft.world.level.NoiseColumn getBaseColumn(
        int x, int z, net.minecraft.world.level.LevelHeightAccessor height, RandomState state);
// private final Supplier<Aquifer.FluidPicker> globalFluidPicker

// net.minecraft.world.level.levelgen.NoiseChunk
public NoiseChunk(int cellCountXZ, RandomState state, int firstBlockX, int firstBlockZ,
        NoiseSettings noise, DensityFunctions.BeardifierOrMarker beardifier,
        NoiseGeneratorSettings settings, Aquifer.FluidPicker fluid, Blender blender);
public void selectCellYZ(int cellY, int cellZ);
public void updateForY(int y, double fraction);
public void updateForX(int x, double fraction);
public void updateForZ(int z, double fraction);
public void stopInterpolation();
// protected BlockState getInterpolatedState()
```

`getBaseColumn` 实际执行完整 `settings.noiseSettings().clampToHeightAccessor(height)`，构造 `NoiseChunk(1,...)`，使用实际 `globalFluidPicker.get()`、`BeardifierMarker.INSTANCE` 和 `Blender.empty()`，再初始化两张 slice。

`NoiseChunk.fillSlice` 的 Z 循环是 `0..cellCountXZ`，每个 Z 开始递增 `arrayInterpolationCounter`，按原 interpolators 顺序填数组，每张 slice 结束再增一次。`advanceCellX(0)` 填第二张 slice 后恢复 `cellStartBlockX=firstCellX*cellWidth`。S1C 的游标必须保持这个顺序；不能在每次 yield 后重新初始化。

`NoiseSliceMixin` 规格所用字段全部在 named JAR 中存在，但不全为 private：例如 `cellCountXZ/cellCountY/cellWidth/interpolators/interpolating/interpolationCounter/arrayInterpolationCounter/inCellX/inCellZ` 为包级字段。实现时匹配实际字段 descriptor/可见性；`NoiseInterpolator` 的 `slice0/slice1/noiseFiller` 用 Accessor，`getInterpolatedState` 用 Invoker。

### 4.2 Tectonic 与 lane 隔离

实际只读 JAR：

```text
D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\mods\tectonic-3.0.26-fabric-21.1.jar
```

本次 javap 确认 `dev.worldgen.tectonic.mixin.NoiseBasedChunkGeneratorMixin.tectonic$fixLavaLevel` 在配置启用且 settings key 的 path 为 `overworld` 时替换 fluid-picker supplier，熔岩高度取 `CONFIG.globalTerrain.heightLimits.minY + 10`。**不是无条件用 NoiseSettings.minY+10，也不是固定 -54。**

生产 oracle 的动作（TODO）：

1. server owner 在全部 LOAD 回调结束后冻结实际 settings、noise registry 输入、biomeSource、维度边界、fluid picker 与语义指纹。
2. `PlannerScheduler` 当前在 oracle lane 内调用 `oracleFactory.get()`。工厂不得在 lane 中重新访问活的 ServerLevel/第三方注册表；传入冻结输入，每 lane 构建并独占 RandomState/NoiseChunk/游标。
3. 规格公开构造器 `CpuTerrainOracle(ServerLevel,String)` 的“server owner freeze”与现有 lane 工厂的调用位置必须分开实现。保留规格入口，增加内部冻结输入/专属工厂边界；不得共用一个可变 oracle。该内部桥接尚未存在，不能写成已验证 API。
4. COARSE 仅用于初筛；REFERENCE 执行完整 aquifer 高度与点级 slice 游标。每原语至多一个 slice 点、cell 选择或 block 状态评估；构造及 selectCell 缓存初始化单独计时。
5. REFERENCE 是自然噪声参考，不是装饰后最终地表；实际贴地仍走 T0 与 `[walkY-32,walkY+6]`。生物群系只调用 `getNoiseBiome`，不用 `possibleBiomes()`。
6. 生产规划不调用单列不可切片的 `getBaseColumn`；它只作为隔离一致性测试的基准。

NOT_RUN：128 列 × 4 seed × 默认/Tectonic/6000 的逐 BlockState 对照；第三方 DFC/native 优化下 `fillArray` 与逐点 `compute` 等价性；构造/单 cell 最大耗时；CPU 固定 route hash。没有这些证据不能授权该 oracle/profile。

### 4.3 初始化事件次序

已读 Fabric 实际锁版本 sources JAR：

```text
C:\Users\18364\.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-lifecycle-events-v1\2.6.0+0865547519\7962668e75d9771ebcb500737409a1cdb4bf6f14\fabric-lifecycle-events-v1-2.6.0+0865547519-sources.jar
```

其中 `net/fabricmc/fabric/mixin/event/lifecycle/MinecraftServerMixin.java` 在原版创建世界的 `Map.put` 返回后同步派发 LOAD。named Minecraft 字节码显示主世界入 levels 后才 `setInitialSpawn`；全部 `createLevels` 返回后才 `prepareLevels`。因此：

```text
common init：注册 markers / mixin 能力 / 服务工厂，不实例化可选类型。
SERVER_STARTING：创建独立 session，拒绝不受支持 profile，读取原 initialized；
  WorldManifest.authorize 在初始化标志改变前写 creation token。
主世界 LOAD：记录 level/registry；不以本 listener 的返回代表其他 LOAD 已结束。
SpawnProbeMixin HEAD：记录原生 spawn-search 的累计依赖域，仅对授权的新世界。
BootstrapSpawnMixin RETURN：记录实际 spawn 三个 int；此时不开始依赖 Golden 的 root 计算。
prepareLevels HEAD：标记 prepareLevelsSeen；冻结 CPU/Golden/资源/树配置快照。
  等 creation 授权和实际 spawn 两个前提，独立 root pump 选址并持久化 recipe。
  安装真实 PlannerScheduler、生产 barrier、vegetation；显式 startupPump 预热 0..3。
SERVER_STARTED：只记录原服已启动；recipe+prewarm 成功后才切 ACTIVE。
END_SERVER_TICK：提交 SliceBudget 和有界后台预取。
SERVER_STOPPING：拒新请求→取消 caller leases / generation leases→关闭 pump/oracle→有界 IO 收尾。
SERVER_STOPPED：兜底移除 session；旧 session callback 不得写入下一世界。
```

已核实原版方法为：

```java
private static void setInitialSpawn(ServerLevel level, ServerLevelData data,
        boolean bonusChest, boolean debug);
private void prepareLevels(net.minecraft.server.level.progress.ChunkProgressListener progress);
public static BlockPos getSpawnPosInChunk(ServerLevel level, ChunkPos pos);
```

现有 `MinecraftServerProbeMixin.greatwall$startupWarmup(CallbackInfo)` 是合法的不捕获原参数注入，继续保留。`SpawnProbeMixin` 的 native halo 从 `ChunkPyramid.GENERATION_PYRAMID.getStepTo(status).accumulatedDependencies()` 读取，不能写死 1。

earlyDomain 只放行已证明的出生探测依赖，不全局放行 bootstrap chunk。远方请求挂生产 ready；root 必须在 800..1500 环带并证明完整未来足印（含 0 号关隘、马道、margin32）与早期生成域不交。找不到则可见失败，不扩大环带。

**启动活性风险（DERIVED）**：当前 `startupPump()` 在 0..3 完成后取消 timer，只留下 `startup-ready` 诊断；额外规划工作靠 tick 供给 credits。若原版准备出生区域尚未结束、某请求需要后续段，正常 tick 可能尚未开始。后续必须保留 bootstrap 阶段额外请求的 pump 活性，直到 server-started/tick 驱动已接管；增加“预热完成但首次 tick 未到，远段请求仍能完成”的阻塞测试。不能把 0..3 成功当作所有启动依赖成功，也不能用主线程 join。

## 5. 植被与黄金树的具体连接

### 5.1 原版与 TYG

`VegetationGuard.publish(featuresOwner, plans)` 发布不可变走廊快照，足印包括主墙、楼、门、马道、收头，XZ 外扩 32。`featuresOwner` 使用当前 `WorldGenRegion` 的生成中心身份，不从树 origin 推断 owner，因为 origin 可以越 chunk。

锁前预取必须覆盖 feature origin 的允许范围及 `TreeBoundsCatalog.maximumRadius()+32`。半径最大 2048 是规格界限，不是本轮统计的已注册树最大值。超过就拒绝该 profile，未知 trunk/foliage/root/decorator 也拒绝，不默认套 vanilla。

接线补齐方式（TODO，不改 S1A 单窗上限）：将目标有限区域按 XZ 分片，以每片中心 chunk 调用 `requireWindow`，每次 margin 不超过 512；按 segment key/hash 合并去重，验证整个目标区域被覆盖，保留各 lease 至 FEATURES 结束。分片申请数量/活跃租约必须有界，不能一次创建数千等待对象。不在树钩子里申请 future、访问磁盘或计算新段。

当前 `ProductionWallPipeline.beforeFeatures` 仅申请 `plans(key,32)`，future 完成即释放，没有发布植被快照。S1C 需要把“plan ready”升级为“完整 vegetation lease 已安装”，并在原版/C2ME 原 FEATURES 的终止回调中释放。若同 owner 同时有多个合法消费者，不能由第一个完成者删除其他消费者仍在用的快照。

TYG 当前 5.3.2 的两版首写证据（FACT-ABI）：

```text
place 的 intermediary 名称：method_13151(class_5821)Z
三张 Map 活跃局部变量：slot28 leavePositions，slot29 logPositions，slot30 additionalPositions。
首个 placeKnownBlockPositions 调用 offset541；随后 offset548 写叶，offset555 写 additional。
private static void placeKnownBlockPositions(Map<BlockPos,BlockState>, WorldGenLevel)
intermediary descriptor：(Ljava/util/Map;Lnet/minecraft/class_5281;)V
```

采用规格 `@Local(ordinal=0/1/2)` 捕获三个 Map，注入到第一处 `INVOKE placeKnownBlockPositions` 之前，`ordinal=0, cancellable=true, require=1`。offset/slot 仅供核对，不能写成注入偏移常量。合并三表实际旋转后的 keys，外扩已认证 decorator 位移，与 guard 相交则返回 false；不重采 RNG，不改原表。

配置 ABI 已核实：两版都有 `baseLocation()/canopyLocation(): ResourceLocation`、`height(): IntProvider`、`maxLogDepth(): int`、`treeDecorators(): List<TreeDecorator>`、`randomRotation(): boolean`、各自的 `orientation()`。V1 `leavesProvider(): BlockStateProvider`；V2 为 `List<BlockStateProvider>`。

HEAD 原版 hook 仅处理自然 `WorldGenRegion`；玩家 FULL 世界骨粉种树不追溯删树、不读取已释放的 FEATURES 快照。NOT_RUN：实际 MixinExtras 局部捕获命中、取消零写块、每注册树配置 100 seed 写集包含证明。

### 5.2 黄金树圣域

已核实方法，Minecraft 参数名按 Mojmap 展示：

```java
// local.plaincraft.erdtreesanctuary.SanctuaryRuntime
public static SanctuaryRuntime.Context get(net.minecraft.world.level.biome.Climate.Sampler sampler);

// SanctuaryRuntime.Context
public boolean intersectsTerrainChunk(int minBlockX, int minBlockZ);
public boolean affectsTerrain(int blockX, int blockZ);
public boolean externalHydrologyAt(int blockX, int blockZ);
public double riverStrength(int blockX, int blockZ);
public int riverWaterLevel(int blockX, int blockZ, int seaLevel);
public int targetSurfaceTop(int blockX, int blockZ, int seaLevel, int originalTop);
public int solidFoundationY(int blockX, int blockZ, int seaLevel, int targetTop, int minimumY);
public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeAt(int blockX, int blockZ);

// local.plaincraft.erdtreesapling.ErdtreeGenerationManager
public static PrebakeBounds prebakeBounds(ServerLevel level); // 可能 null
public record PrebakeBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
        int treeChunks, long totalBlocks, int completedTreeChunks) {}
```

真实 LOAD 顺序是：安装/选择 snapshot → 构造 externalHydrology → `SanctuaryRuntime.register(actualSampler,...)` → 如需要则 `activateLazyTree`。UNLOAD 才 unregister。`get` 按实际 sampler 身份查 Context；新建 RandomState 的 sampler 不会自动得到该 Context。

adapter 在 prepareLevels 捕获实际 Context，向 lane 提供冻结快照/可认证的只读接口。无 Context 不等普通 tick；拒绝该 profile。原工程 `buildSurface` HEAD 修改地形，`applyBiomeDecoration` HEAD/TAIL 修整水文/装饰，不能在 surface 后就认定 T0 最终。

保护范围：

- lazy 模式：`prebakeBounds` 有值才接受。范围由全部 index entries 计算，闭 chunk 上界转 `(maxChunk+1)*16`，Y 全维度。
- snapshot 模式：世界 marker 名为 `plaincraft_erdtree_snapshot.json`；来源 manifest 位于隔离 config 的 `plaincraft_erdtree_snapshot/active/manifest.json`。`FORMAT="erdtree_snapshot_v1"`，marker 字段是 `centerX/centerZ`，manifest 字段是 `template_center_x/template_center_z/region_files`。
- 本地 installer 校验源/目标中心按 512 对齐；region 名 `r.rx.rz.mca` 按 `(center-template_center)/512` 平移，覆盖完整 512×512 区域。这些 FULL 成品区域必须提前判为保护/断口，不让普通首次 CAPTURE 走进去再把 FULL 错当自然输入。
- 本地 installer **允许 region_files 为空并退回扫描 region 目录**，而冻结的长城规格只认证有 region_files 的 manifest。故缺列表时本适配按 `UNSUPPORTED_ADAPTER` 拒绝，不猜黄金树半径，也不把 installer 自身能加载当作长城已能保护。
- 普通 tick 延迟建树模式存在：`tickWorld` 最终可调用 `ErdtreeGenerationManager.start`。既无 snapshot 完整范围也无 prebakeBounds 时仍拒绝。

不复制黄金树自身的 `BiomeSourceMixin/possibleBiomes()` 实现。Golden finalizer 对结构敏感区有条件跳过；oracle 的地表预测不能替代最终 T0 比对。Golden 开/关、lazy/snapshot、保护边界内外及晚写验证全部 NOT_RUN。

### 5.3 Tan's 不解锁

本次读取了：

```text
D:\云游四海-Mod源码\roadweaver\common\src\main\java\net\shiroha233\roadweaver\features\path\pathlogic\core\HugeTreeReservation.java
D:\云游四海-Mod源码\plaincraft-roadweaver-tans-compat\src\main\java\local\plaincraft\roadweavertanscompat\mixin\TansHugeTreeReservationMixin.java
D:\云游四海-Mod源码\tanshugetrees\upstream-reference\tannyjung\tanshugetrees_handcode\systems\world_gen\TreePlacer.java
```

Road 的 reservation 只取 `1100 <= abs(type) < 1200`，每 level 4096 chunk 上限会淘汰，反射失败返回空数组。实际当前 Tan's JAR 同时有无 boolean 的 private place 和带末尾 `boolean worldGeneration` 的重载；现有兼容 Mixin 针对后者。完整冠幅、后置 Function 写入没有由这个 API 认证。

保持授权前拒绝 `tanshugetrees`，输出 id/version/SHA 和 `post-generation tree writer not certified`。本任务不增加 Tan's hook，不删除或绕过它。

## 6. PREPARE / CAPTURE / APPLY 与生命周期

后续必须复用 S1B 最终签收提交中的跨 cell 决议、预分配和 owner 保护，不用 S1C 替换实现；这里不提前认证当前未签收代码：

```text
FEATURES 前：
  等 recipe / 真实 plan / vegetation 快照；不等待自己的 FEATURES 或邻 chunk APPLY。
  原 FEATURES 在原引擎上下文执行；全 feature 终止后释放 vegetation lease。
INITIALIZE_LIGHT 前：
  ProductionWallPipeline.beforeLight → 请求 owner 的所有 cell/出口共同决议。
PREPARE：
  用 ChunkPyramid 的 FEATURES blockStateWriteRadius 求每个采样 owner 的真实写入 halo。
  每次 FeaturesLease <=81；仅异步请求 FEATURES，不能请求 FULL 来等邻 APPLY。
CAPTURE：
  S1B 预留预算获准后才分配页/元数据。
  等全部会写该 owner 的 halo FEATURES 结束，再由 engine.exclusive 复制 owner T0/保护证据。
  不在 engine.exclusive 内等待 IO、future、另一个 chunk。
  T0 页、coverage、保护/出口证据落盘完成；不能事后重新采已写墙/玩家修改。
RESOLVE / COMPILE：
  纯 T0 + recipe + plan + 决议，跨 owner 重放相同组件。
  保留决议身份 recipe + 绝对 S 区间 + 出口 SiteId + T0 hash + 最终边界。
APPLY：
  durable proof ready → engine.exclusive(owner) → preflight → 仅写 owner。
  成功才放行原 INITIALIZE_LIGHT；非 owner 部分由其自身流水线重放。
重进：
  先验证 recipe / marker / durable proof；完成 marker 无副作用；
  有标记无证据或损坏则显式失败，不能重采现地作为 T0。
```

`ProtectionSnapshot` 接入点在 owner CAPTURE，不能由异步纯规划线程读取 ChunkAccess。跨 chunk 结构引用必须来自已 PREPARE 的有限内容；无法解析的引用保持保守保护/失败，不同步补 chunk。Golden 保护盒在 recipe 冻结阶段发布，FULL 区域先过滤。

现有差距（FACT-SOURCE）：

1. `TerrainSnapshotService` 当前对 `getAllStarts()` 取结构整体盒，有 references 时把当前 `coverage.readBounds()` 整体保护；没有规格的精确引用解析及 Golden 预测盒输入。
2. `BlockCatalog` 当前是硬编码 `natural(id)`，不是规格的三个 tag 合同；它的候选包含 mud/snow_block 等，最终还依赖 sturdy/gravity 判定。S1C 必须按支持 tag 与注册表冻结重建分类并进 recipe hash，不把当前 hardcode 当规格已实现。
3. `WorldServices` 的生产状态、session 身份与 close 路径尚不存在；S0 的 `assertActiveSession`/失败通道目前依赖 ProbePlanner。生产不得为了复用这些方法暗中启动假规划器。
4. 当前生产 coordinator 的 `layout()` 会抛错，但两种 gate 仍用 S0 `layout().owns(...)` 判别 beforeLight。必须先按固定 mode 分支，再调用生产 `ChunkKey` 重载。

生命周期复位（TODO）：

- SERVER_STOPPING 首先发布拒新状态；释放树快照/计划/FEATURES ticket/跨 cell 等待，并取消本 session 的 pump。只处理自家会话，不清邻 mod 的静态缓存。
- C2ME `SUBSCRIBING` 是 ThreadLocal，当前 set/remove 已在订阅 finally 内。不能从 server 线程调用一次 remove 就声称清掉所有 worker 的 ThreadLocal；闭环测试必须覆盖异常/取消，且旧 callback 经 session 检查失效。
- flush 在专属 IO future 上有界 5 秒；worldgen 不等待；到期报告未 durable 边界，不清空永久历史。
- SERVER_STOPPED 再做幂等移除；同进程世界 A→B→A 不能残留 A 的 recipe、earlyDomain、树走廊或 closed 标志。
- 确定性重放只针对有原 recipe/原 T0/原 proof 的本模组世界，不能成为旧世界回填功能。

## 7. 后续新增/修改文件清单（TODO，未写入）

### 7.1 新增生产文件

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\CpuTerrainOracle.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\NoiseCursorBridge.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\NoiseSliceMixin.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\NoiseInterpolatorAccess.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\NoiseGeneratorAccess.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ProtectionSnapshot.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\WorldManifest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\WorldBootstrap.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\BootstrapSpawnMixin.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\SpawnProbeMixin.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\TreeBoundsCatalog.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\VegetationGuard.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\TreeFeatureMixin.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\TygTreeFeatureMixin.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\TygTreeFeatureV2Mixin.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\golden\GoldenTerrainAdapter.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\tags\block\foundation_support.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\tags\block\replaceable_vegetation.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\tags\block\protected.json
```

### 7.2 修改现有生产/构建文件

| 完整路径 | 接线工作 |
|---|---|
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\WorldServices.java` | 固定实验/生产 mode；生产 session、bootstrap、planner、barrier 与 tree leases；生命周期/失败通道 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\PlannerScheduler.java` | 核实并补齐启动到 tick 的连续活性，不改算法/日程/预算；纯函数接口保留 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ProductionWallPipeline.java` | 锁前完整植被预取/发布；Golden 保护前置；保留 S1B 预留与跨 cell 协议 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\WallStageCoordinator.java` | 注入 production vegetation/protection 服务与释放回调，实验路径保留 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\TerrainSnapshotService.java` | ProtectionSnapshot 与已冻结第三方范围合并；不削弱 S1B allocation/证据保护 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\BlockCatalog.java` | tags 驱动、canonical 分类与 hash，保留既有 token 协议回归 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\VanillaGateMixin.java` | 原 applyStep WrapMethod 保留；锁前生产依赖，FEATURES 终止释放，beforeLight 不碰实验 layout |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\c2me\C2meProbeAdapter.java` | 同上，保留 positionedExecutor、惰性订阅、取消/失败见证 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\MinecraftServerProbeMixin.java` | 通过 warming 记录 prepareLevelsEntered，安装真实 frozen adapter，不直接做长时选址 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\c2me\ProbeMixinPlugin.java` | 原 C2ME 锁版本检查保留；按 TYG 存在性/ABI 加载；不提前加载可选 Java 类型 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\GenerationResourceFingerprint.java` | captureProduction，包含模板、展开 tag、实际 JAR SHA、树边界协议与算法版本 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\WorldRecipeGuard.java` | validateProduction；资源漂移拒绝新生成，不静默清 manifest |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\BootstrapState.java` | 加 ABSENT/NEW_WORLD_AUTHORIZED，合法状态边显式化 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\GreatWallMod.java` | 资源先于服务；软依赖入口延迟加载；保留测试/生产区分 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\plaincraft_great_wall.mixins.json` | 新增必需 mixin，defaultRequire=1；可选目标走 plugin |
| `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\fabric.mod.json` | 开发阶段描述；锁版本不变，可选依赖不变硬依赖 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\gradle.properties` | 只按签收纪律递增 mod_version，不改依赖、不加代理 |
| `D:\云游四海-Mod源码\plaincraft-great-wall\build.gradle` | apply 独立 production-tests.gradle；不要重复创建 serverSmoke |

`C2meGateMixin.java`、`TerrainEngine.java`、`VanillaTerrainEngine.java`、`C2meTerrainEngine.java`、`FeaturesLease.java` 本次核对的基础 ABI 可复用；默认不重写其锁机制。仅当后续测试给出明确反证才定向修改并记录。

### 7.3 新增/修改验证文件

```text
新增 D:\云游四海-Mod源码\plaincraft-great-wall\gradle\production-tests.gradle
新增 D:\云游四海-Mod源码\plaincraft-great-wall\tools\Test-S1Terrain.ps1
新增 D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\RealTerrainFixture.java
新增 D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\OracleGameTests.java
新增 D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\BootstrapGameTests.java
新增 D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\VegetationGameTests.java
修改 D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\PlannerSchedulerTest.java
修改 D:\云游四海-Mod源码\plaincraft-great-wall\src\serverSmoke\java\local\plaincraft\greatwall\smoke\ServerSmoke.java
修改 D:\云游四海-Mod源码\plaincraft-great-wall\gradle\smoke-tests.gradle
修改 D:\云游四海-Mod源码\plaincraft-great-wall\tools\Stage-SmokeProfile.ps1
最终新增 D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C.md
```

所有新增文件均指未来实施，不表示本次已创建。可选依赖用本地锁 SHA 的 `modCompileOnly(files(...))`，不能普通 compileOnly 吃 intermediary JAR。核心无 TYG/Golden 时类加载必须成功。

实施拓扑：S1B 正式签收 → BlockCatalog/ProtectionSnapshot → Noise bridge/oracle 与一致性阻塞测试 → WorldManifest/WorldBootstrap/spawn 两 hook → TreeBoundsCatalog/VegetationGuard/TYG/Golden → WorldServices → 两种 gate → 真实地形矩阵与同进程生命周期 → 报告交审。

## 8. 隔离新世界矩阵脚本接口（TODO，不是现成命令）

现有脚本/任务的事实：

- `tools\Stage-SmokeProfile.ps1` 现支持 vanilla/tectonic/tectonic-c2me/pack-opencl，EvidenceStage 尚不含 s1c；只读源 mods，目标必须为全新的 `build\run\serverSmoke\<profile>\<runId>`。
- `tools\Test-TerrainMatrix.ps1` 是 S0/S1B 实验测试；它传 `-Ps0Terrain=true`。
- `gradle\smoke-tests.gradle` 在该参数为 true 时将 level-type 改为 flat，固定 seed=9282026；不能把这条命令换个报告标题作为 S1C 真实地形。
- `prepareServerSmoke` 目前重开仅允许识别出的 S0 terrain recovery；不能把生产 re-open 硬塞进 `-Ps0TerrainResume=true`。
- `ServerSmoke` 当前有自己的短时结束与 S0 PASS 标记逻辑；必须在 S1 真实测试模式下等 RealTerrainFixture 完成，否则会提前关服并产生无效通过标记。

未来脚本公开入口保留规格：

```powershell
# 尚未实现；本次没有执行以下命令。
param(
    [ValidateSet('vanilla','tectonic','tectonic-c2me')]
    [string]$Profile,
    [long[]]$Seeds = @(0,1,9282026),
    [ValidateSet(384,6000)][int]$Height = 384,
    [switch]$Reopen
)
```

由新 production-tests.gradle 增加并校验以下参数，复用既有 sourceSets.serverSmoke：

```text
-Ps1Terrain=true
-Ps1TerrainSeed=<long>
-Ps1TerrainHeight=384|6000
-Ps1TerrainReopen=false|true
-PsmokeProfile=<profile>
-PsmokeRunId=<新runId>
```

脚本行为：

1. 每个 `(profile,seed,height)` 产生独立新 runId 和输入 manifest；复制只读本地依赖到项目隔离目录，验证 id/version/SHA。设置 JDK21；Gradle 每次带 `--offline --dependency-verification=strict`，无代理、无下载修复。
2. S1 模式禁止 `s0Terrain=true`，普通地形不注入 flat。`-Height 384` 表示正常高度配置的测试入口，必须记录实际 dimension minY/maxY 与 noise 高度；若 Tectonic 默认高度不同，报告实际值，不冒充 384。
3. `-Height 6000` 表示 max_y=6000 能力 profile，不是“高度跨度=6000”的伪 label。需在隔离配置加载已锁本地 `plaincraft_height_6000_patch` 与配套有效高度输入；该 patch 只改打包/界面上限，不自动创建 6000 维度。未找到/未核定数据包或有效 noise 高度就报告 BLOCKED，不用默认高度运行冒充。记录 maxYExclusive，测试最高合法 y 为 maxYExclusive-1。
4. server-ip=127.0.0.1、随机端口、watchdog=60000 保持；脚本总超时 40 分钟。双向异步收集输出，超时只终止本 runId 创建的进程树，保存失败证据，不关系统其他 Java。
5. 自动 fixture 等生产 ACTIVE，生成有限真实计划走廊，至少覆盖 S=0 到第一敌楼/烽火台及 >=1200 格陆墙代表样本；记录各 chunk/cell 计划与 T0/proof/最终状态。早入海 seed 保留海洋终止结果，不偷换 seed。
6. `-Reopen` 仅重开脚本刚创建、token/recipe/输入 SHA 对得上的测试世界；不接收任意外部 SavePath。首次正常 shutdown 后另启进程，保留原世界目录不重新 staging，比较相同 owner 与加载顺序的证据。
7. 另用同一 JVM 依次启停多个会话验证 SERVER_STOPPING 复位；“退出 JVM 再启动”不能证明静态标志清理。
8. 自动生成/reopen、GameTest 和人工客户端实走三类证据分栏。无人实际操作就写 `MANUAL_WALK=NOT_RUN`；截图不存在不填 PASS；绝不把机器人路径巡检改名为人工实走。

未来运行命令形式：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict build runGameTest
.\tools\Test-S1Terrain.ps1 -Profile vanilla -Seeds 0,1,9282026 -Height 384 -Reopen
.\tools\Test-S1Terrain.ps1 -Profile tectonic -Seeds 0,1,9282026 -Height 384 -Reopen
.\tools\Test-S1Terrain.ps1 -Profile tectonic-c2me -Seeds 0,1,9282026 -Height 384 -Reopen
```

这些是实施后的复现接口，不是本次已经存在或执行成功的脚本。TYG/Golden 的加载与注入认证是独立附加 profile，记录精确输入，不把三组 core CPU 矩阵外推为完整整包。OpenCL 和 Tan's 不因本准备任务获授权。

输出目录合同：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\<runId>\`。至少包含 inputs.json（源码 commit/dirty 状态、mods/config/data SHA、Java、命令、实际高度）、first/reopen 日志与退出码、boot-events.json、oracle-parity.json、tree-bounds.json、owner-proof.json、gap-report.json、result.json；截图只列实际存在的文件与采集方式。未来写入也不能覆盖旧证据目录。

## 9. 阻塞测试映射与精确未闭合事实

| 检查 | 必须证明 | 本次状态 |
|---|---|---|
| S1B 两个收尾项与正式签收 | 分配前预留/真实峰值、完整碰撞曲坡 survival，严格构建和报告完成 | 未代签；由主代理负责 |
| `slicedColumnMatchesVanillaReference` | 所有高度逐状态等价，默认/Tectonic/6000 及负坐标 | NOT_RUN |
| `tectonicUsesActualFluidPicker` | 实际 picker 条件与配置 minY+10，未硬编码熔岩 | ABI 已核；运行 NOT_RUN |
| `height6000PauseResumeIsStable` | 实际高维度、暂停恢复/打包边界，不是只序列化三个 int | NOT_RUN；本次未构造高度输入 |
| `goldenFinalSurfaceMatchesCapturedInput` | Golden 自然修正与最终 T0、结构敏感分支一致 | NOT_RUN |
| `newWorldFreezesActualSpawn` / `rootIsIn800To1500Ring` | 原 spawn、实际 ring、recipe durable 后再生成 | NOT_RUN |
| `allRingCandidatesFailVisibly` | 无候选可见失败且不改变用户环带 | NOT_RUN |
| `earlySpawnDomainIsDisjoint` / `remoteBootstrapRequestDoesNotPassWithoutPlan` | native halo 域证明、不放行远请求、不死锁 | NOT_RUN |
| `creationCrashResumesOwnToken` / `existingUnmarkedWorldIsRejected` | 只恢复自己的初始化，旧世界不回填，损坏不清空 | NOT_RUN |
| 新增 scheduler 启动活性回归 | 0..3 已完成且未 tick 时，后续请求仍可切片推进 | 源码风险已定位；NOT_RUN |
| `canopyRootOutsideStillSuppressed` / `treeOutsideMarginUnchanged` | 完整冠幅/根系跨墙，界外树 RNG/写入不改变 | NOT_RUN |
| `everyRegisteredTreeWriteFitsBounds` | 每配置 100 固定 seed，冠/根/decorator 写集全部包含 | NOT_RUN；未声称已统计注册配置 |
| `tygRotatedTemplateBoundsCovered` / `tygCancelledBeforeFirstWrite` | V1/V2 所有旋转方向，首写前取消写块数=0 | 字节码已核；运行 NOT_RUN |
| `unknownPostFullWriterCannotSilentlyPass` | Tan's 原样 profile 授权前拒绝，含 id/SHA 诊断 | 策略 BLOCKED；运行 NOT_RUN |
| PREPARE/CAPTURE/APPLY + reopen | 真 FEATURES halo 完成、决议一致、owner 只写自己、玩家修改不重采 | 复用入口已核；真实接线 NOT_RUN |
| 同 JVM 生命周期 | STOPPING 后 leases/session/ThreadLocal 清理，第二世界无串线 | NOT_RUN |
| vanilla/Tectonic/C2ME 实走 | 各代表 seed 自动生成/重进无漏段，人工行走另验 | NOT_RUN |

精确风险清单：

1. 单窗 margin 512 与树边界预取最高 2080 的接口跨度，需要有限分片聚合；不能直接提升上限逃过背压测试。
2. FEATURES 快照生命周期目前无接线，不能在 plan future 完成时释放后才运行树。
3. startup pump 预热后到原版启动完成之间存在需验证的供给空窗。
4. CpuTerrainOracle 的 server-freeze 与 lane 构造必须拆开；混用实际 world 的可变 NoiseChunk 违反隔离。
5. scalar/fillArray 等价性、构造耗时、第三方 Mixin 合成结果不能用“字段存在”认证。
6. ProtectionSnapshot 规格尚未落地；现有引用保守整盒保护会影响断口分布，不能宣称精确避让已经完成。
7. 当前 native FEATURES 写半径只证明原生调度授权范围。第三方 FEATURES 以外后写不能由此自动纳入屏障，必须独立认证或拒绝。
8. 当前树边界算法是冻结设计，不是已运行证明的事实；未知类型、实际半径超界必须 fail closed。
9. Golden 的 snapshot/lazy/晚 tick 三模式有不同保护证据；没有统一“黄金树 ready”事件或通用完整写集 API。
10. S0 平地 fixture、S0 菜单/启动和 S1B 组件测试都不能抵扣 S1C 真实墙验收。
11. 生产 recipe/manifest 与永久历史分开存；资源漂移、证据损坏、旧 FULL chunk 不走清空重算。
12. 辅助称呼 “The Yellow ...” 未解析到独立本地实体，不能据此新增未知兼容类；实际工程和模组身份见 §1.3。

## 10. 本次结论

已完成：本地源码阅读、锁定 C2ME 内包身份/SHA 与 ABI 核对、TYG V1/V2 首写点和配置 ABI 核对、Golden/sapling 方法与生命周期核对、可复用入口/后续文件/测试接口与风险记录。

**未完成也未声称完成：S1B 签收、S1C 生产接线、编译、JUnit、GameTest、真实地形生成、退出重进、人工实走、运行性能。**

本文件交主代理在 S1B 正式签收后使用；使用前重读最终 S1B 提交的管线接口并保留现有实现。S1C 状态保持 **未开始实施 / NOT_RUN**，Tan's profile 保持 **BLOCKED**。
