# S3 探索内容、永久历史与 FTB

本册是 [设计]，外部方法存在性见06册 [本地事实]，所有测试仍为 [待执行]。S2先实现本册 HistoryTypes/HistoryJournal/WallWorldState 的通用基础与门事件；S3才启用实体、烽火和任务。R1/R2/R3/P1/P4/P5逐类生效。下述 Minecraft 短名均使用 Mojmap；FTB短名的完整包见各节。

## local.plaincraft.greatwall.component.Fort

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\Fort.java`。纯体素函数/JUnit，实际写入GameTest；R2/P3/P4。

```java
package local.plaincraft.greatwall.component;
public final class Fort implements ComponentTemplate {
    private final DecayField decay;
    private final PaletteBinding palette;
    private final BeaconTower beacon;
    private final EnemyTower enemy;
    public Fort(DecayField decay, PaletteBinding palette, BeaconTower beacon, EnemyTower enemy);
    public Bounds localBounds(ComponentInput input);
    public ComponentVoxels compile(ComponentInput input);
    public ComponentVoxels compileWithSockets(ComponentInput input, List<SiteRecord> absorbed);
}
```

```text
ctor：依赖非空；不访问世界。localBounds=(-24,-32,-24,24,8,24)，Y相对W，g=W-20；XZ严格48。
compile：调用compileWithSockets(input,空列表)。
compileWithSockets：
  按SiteId排序absorbed；必须是本fort吸收的site，否则INVALID_RESOURCE。
  扫描完整48×48足印的supports，全部承重条带h合格且drop<=12；任何保护/上溢则整体SUPPRESSED；
  不用当前owner的半足印投票。根0不可SUPPRESSED：失败ROOT_NOT_FOUND，禁发布半座0号关隘。
  按下面坐标表生成语义体素；坐标表后的负空间/必需路径优先于填芯。
  最多2个吸收socket；按最小siteId分配西北、东北；超出明确SUPPRESSED(CONFLICT)。
  BE、spawn slots、route是输出数据，不在compile创建实体或访问注册表。
  四态同DecayField：完好/半破保护主通道、登城坡道；废墟/掩埋删除笼/箱/房顶并生成有支撑出口。
  SupportGraph验证+两侧地面→庭院→W步道→四角屋顶可达，失败整件抑制而非留封闭室。
```

局部坐标下所有范围半开；高度为绝对 y。逐项按优先级合并，不重复写：

| 部分 | 唯一体素规则 |
|---|---|
| 地基/院坪 | 足印内y∈[h,g)填 FOUNDATION，y=g-1有支撑地坪；主通道脚面g。除建筑条带外，不向下挖自然地形形成室内地下室；地面高于g的地块只有自然可替换表层≤1可整平，否则site冲突。 |
| 外围 | x/z任一落在[-24,-22)或[22,24)是2厚皮墙；内侧再2格支撑条带填CORE至W-1，形成4宽环形步道。其余院内不填20高。沿墙X两侧Z∈[-3,3)在y∈[W,W+3)留6宽通口。 |
| 主过墙口 | X∈[-2,2)，Z∈[-24,24)，y∈[g,g+5)为必需4×5通道；门墙拱圈放在y=g+5..g+7，不能侵入净空。南北门楼X∈[-6,6)、Z∈[-24,-18)/[18,24)，外壳到W+5，门楼上层地板W-1；主通道始终贯穿。 |
| 瓮城 | X∈[-10,10)、Z∈[8,22)，边框厚2、高g+8；X[-2,2)贯穿其Z两边为第二道门；瓮城不得盖住中央4宽道路。 |
| 四角箭楼 | 各角6×6：[−24,−18)或[18,24)笛卡儿积。外壳厚1；下部实承重至W-1；室内W..W+4，顶板W+4；屋顶脚面W+5，栏W+5..W+7。朝内各2宽开口，环道接W；室内沿外侧用5级石阶到屋顶，2宽、3高净空。 |
| 登城坡道 | 西侧X[-20,-17)、东侧[17,20)，Z[-10,10)，第j格z=-10+j，脚面g+j+1，j0..19；下方实填，头顶3高clear。底部landing Z[-13,-10)脚g；顶部landing Z[10,13)、西X[-24,-17)/东[17,24)脚W。坡道及接外环道保留3宽。 |
| 营房 | X[-16,-6)、Z[-12,-4)，墙厚1、脚g、净空4、屋顶g+4；门X[-10,-8)、Z=-4边，内部所有家具避开门2宽通道。床固定两个，脚端(-14,g,-10)、(-14,g,-7)，朝E，头端x+1。 |
| 武库 | X[8,16)、Z[-12,-4)，同高；门X[10,12)、Z=-4边；箱在(13,g,-9)且上方2空；铁砧(10,g,-9)永远有地基。 |
| 马厩 | X[-16,-8)、Z[0,6)，4角柱、g+4顶、面东2宽入口；不自动生马。 |
| 井 | X[7,10)、Z[1,4)，3×3石圈，中心(8,g-1,2)源水；下方石底，封闭独立不连地下水。 |
| 两笼 | 营房(-12,g,-9) localId=0；北门楼(4,g+1,-21) localId=1，后者3×3独立有地板小室；恰好2个。完好/半破才输出SPAWNER，初次Delay=400。 |
| 固定箱 | 武库上述localId0；营房(-8,g,-10)localId1；北门楼(3,W,-21)localId2。不会在chunk load新增或补货。 |
| 吸收socket | 西北中心(-12,W,-17)、东北(12,W,-17)；各11×11预留区，局部X/Z[-5,6)，整体仍在48足印内，覆盖外围装饰而非中央道路。beacon调用原10×10 BeaconTower；enemy调用原11×11 EnemyTower完整双层模板；镜像按原SiteId保留。每个输入仅平移frame.center到socket、W保持fort合同，保留site ordinal及spawn slot身份。两模板localBounds最多到W+7，包含于Fort[-32,8)高度；新输入supports必须取socket完整足印。11宽预留区除beacon实际10宽外侧一格为W-1平台+W栏杆。外围到socket入口各留3宽连廊，socket voxels和requiredRoutes优先于箭楼以外装饰。最多1beacon+1enemy；超过同类1个的候选按SiteId保留首个，其余SUPPRESSED(CONFLICT)，不造尺寸未定义的小室。 |

Fort0 强制 `DecaySample(INTACT,0,20)`，其周边48格维护偏置平滑衰减；其他fort保留自然四态。它是 [设计细化]，确保起点功能建筑，不改变远方日程。

## local.plaincraft.greatwall.persistence.HistoryTypes

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\HistoryTypes.java`。纯模型/JUnit，P5。

```java
package local.plaincraft.greatwall.persistence;
public final class HistoryTypes {
    private HistoryTypes();
    public enum Health { READY, PENDING_IO, DEGRADED, CORRUPT, CLOSED }
    public enum Kind { GATE_PROPOSED, GATE_FROZEN, GATE_BEGIN, GATE_ACK, GATE_CONFLICT,
        BEACON_CREDIT, SLOT_CONSUMED, MOB_SPAWN_RESULT, MOB_RETIRED, LOOT_ASSIGNED }
    public enum Tool { FLINT_AND_STEEL, FIRE_CHARGE, TORCH }
    public enum SpawnResult { ADDED, REFUSED, LOST_BEFORE_ADD, REMOVED_BY_CAP }
    public record ShardKey(String kind, long bucket, int page) {}
    public record BlobRef(String relativePath, long length, String sha256) {}
    public record ShardRef(ShardKey key, long throughSequence, long count, BlobRef blob) {}
    public record IndexBranch(String firstKey, String lastKey, BlobRef child) {}
    public record IndexPage(int level, List<IndexBranch> branches,
        List<DurableLog.Record> records, String hash) {}
    public record Head(UUID world, long generation, long sequence, long beaconCount,
        Map<String,BlobRef> roots, String hash) {}
    public record Credit(BeaconId id, UUID player, long gameTime, Tool tool, long sequence) {}
    public record SlotKey(SiteId site, int localSlot, long attempt) {}
    public record Consumption(SlotKey key, UUID entity, long sequence, Optional<SpawnResult> result) {}
    public record State(UUID world, String manifestHash, long sequence, long beaconCount,
        long revision, Health health, String checkpointHash) {}
    public record Recovery(Head head, List<DurableLog.Record> tail, boolean archivedPartialTail) {}
}
```

构造：按照00册深复制；bucket/page/sequence/count/generation/attempt非负；enemy slot localSlot0..2且attempt=0；fort localSlot0/1、attempt为已提交出生流水号，不能拿tick当唯一键。relativePath只能在history根下的规范相对路径，无`..`/盘符/符号链接逃逸。hash不通过则IOException，不能创建READY空记录。IndexPage.level=0只能含records；level>0只能含branches且子level恰减1；各区间严格不重叠。Head.roots空仅允许sequence=0，否则键集恰为by_key/by_sequence，固定2个根，不是全分片Map。

分片目录：BEACON按floorDiv(ordinal,4096)，GATE按namespace+floorDiv(ordinal,4096)，SLOT按site kind+floorDiv(site.ordinal,4096)分桶，page从0递增，仅用于文件组织，不以固定桶数限制世界长度。完整SiteId/BeaconId做等值，hash只用于命名/校验。

精确索引采用两个不可变B+树。by_key按`type + NUL + eventKey`的UTF-8字节排序；by_sequence按19位前导0十进制sequence排序。叶页装已提交Record，最多1024条且编码<=1MiB；单Entry实现上限64KiB（低于公共协议1MiB绝对拒绝上限），大对象必须先storeBlob。内部页最多128个IndexBranch且<=1MiB，区间[firstKey,lastKey]闭端；空树无文件。树高上限16，超出明确CAPACITY而不是截断记录。lookup每层二分唯一覆盖区间，缺区间即已证明不存在；子页缺/CRC/hash坏是CORRUPT。HEAD和当前路径最多持有16个页引用，缓存实际内容总<=8MiB；遍历使用IO owner栈和逐页释放，不保留所有目录/ID。

## local.plaincraft.greatwall.persistence.HistoryJournal

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\HistoryJournal.java`。IO/JUnit+进程故障；R2/P5。通用基础在S2前置实现。

```java
package local.plaincraft.greatwall.persistence;
public final class HistoryJournal implements DurableLog {
    private final Path root;
    private final UUID world;
    private final ExecutorService ioOwner;
    private final ConcurrentHashMap<String,CompletableFuture<Receipt>> inFlight;
    private final ConcurrentHashMap<HistoryTypes.BlobRef,HistoryTypes.IndexPage> pages;
    public HistoryJournal(Path root, UUID world, ExecutorService ioOwner);
    public CompletionStage<HistoryTypes.Recovery> open();
    public CompletionStage<Receipt> append(Entry entry);
    public CompletionStage<Optional<DurableLog.Record>> lookup(String type, String eventKey);
    public CompletionStage<HistoryTypes.BlobRef> storeBlob(String kind, byte[] bytes);
    public CompletionStage<byte[]> loadBlob(HistoryTypes.BlobRef ref);
    public List<DurableLog.Record> replay(String type, long afterSequence, int limit);
    public CompletionStage<HistoryTypes.Head> checkpoint();
    public HistoryTypes.State state();
    public CompletionStage<Void> flush();
    public void close();
}
```

`DurableLog.Record`及lookup/storeBlob/loadBlob已在S2接口完整声明。bytes入队即clone，loadBlob返回新副本；数组只在IO调用边界，不跨线程共享可变原件。

```text
ctor：不阻塞/不启动读盘；owner局部字段nextSequence、WAL channel、队列、LRU、opened/closed。
open：IO上验证manifest.instance与HEAD；首次只有creation且history目录空才建generation0。
  有历史却HEAD缺失：检查仍保留的checkpoint代际与WAL连续链，唯一一致链可恢复，否则CORRUPT。
  从两个根流式遍历页头/哈希和计数归约；每页释放/驱逐，不把全目录或历史常驻内存；尾WAL逐record replay。
  sequence严格单调，相同eventKey只能同payload；完整CRC坏/中部空洞硬失败。
  不完整尾保存只读副本再截断并force；初始化精确count，发布READY后complete(open)。
append：限制128个未处理事件及单Entry64KiB；满返回CAPACITY且调用者保留pending，不丢。
  复制Entry，按eventKey putIfAbsent promise；相同key不同payload拒绝；IO排队查磁盘精确索引。
  已提交返回原Receipt；无记录分配nextSequence，用 canonical Entry 编码：
    length:int | schema:int=1 | sequence:long | eventKey:string | type:string | fields:map | CRC32C:long。
  完整write循环至ByteBuffer无剩余，channel.force(true)，再发布只读page/state，最后完成Receipt。
  force失败不发布；将journal标DEGRADED并停止新append；恢复时重新open核对真实尾，不盲重写seq。
lookup：先查本owner已提交尾WAL精确小索引，再按by_key B+树查；热点pages总预算8MiB。
  尾索引达到1024条或8MiB（二者先到）立即安排checkpoint，对新append背压，不能无限驻留。
  不存在Optional.empty，是已查证不存在。
  map只含immutable page；未缓存不假装不存在。completed inFlight按identity remove；不常驻所有历史key。
storeBlob：kind白名单，<=16MiB；SHA命名临时文件→force→ATOMIC_MOVE，不同内容不覆盖；返回BlobRef。
loadBlob：验证路径/长度/SHA后返回副本；缺失硬错。blob必须先durable，事件后引用。
replay：仅IO owner且open完成；从by_sequence首个>afterSequence开始叶页迭代并合并提交尾，
  升序读limit1..1024，带sequence；禁止服务器线程调用。
checkpoint：先flush截止seqK，按两种排序各自流式归并旧叶记录+<=1024条tail至K；
  叶页按1024条/1MiB确定性切页，内部每128子页/1MiB分组，自底向上写新不可变页至唯一根；
  相同已提交key+payload仅保留原sequence；不同payload硬错。写两个root引用与count到checkpoint。
  逐个force与验证，ATOMIC_MOVE切HEAD，保留前一代checkpoint和它所需WAL；仅第三代后删除更旧完整代。
  切换完成不丢K后append；单writer期间新请求留有界队列；失败原HEAD不动。
state：CHM中唯一key的不可变快照；不可读写owner局部可变字段。
flush：把屏障事件排至之前写请求之后，force完返回；无主线程等待。
close：非阻塞拒绝新事件，已接受事件flush完关闭channel/executor；待请求成功或exceptional都要结束。
```

`P5`保证的是force成功后不重复计；不声称Minecraft区块、FTB队文件和WAL组成跨文件原子事务。故障注入矩阵在S5。

## local.plaincraft.greatwall.persistence.WallWorldState

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\WallWorldState.java`。SavedData/GameTest；R2/P5。

```java
package local.plaincraft.greatwall.persistence;
public final class WallWorldState extends net.minecraft.world.level.saveddata.SavedData {
    private HistoryTypes.State current;
    public WallWorldState(HistoryTypes.State recovered);
    public static WallWorldState load(net.minecraft.nbt.CompoundTag tag,
        net.minecraft.core.HolderLookup.Provider registries);
    public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag,
        net.minecraft.core.HolderLookup.Provider registries);
    public void apply(HistoryTypes.State committed);
    public HistoryTypes.State snapshot();
}
```

```text
ctor：recovered非空，是已验证journal派生state；不得无参创建空历史替代损坏读盘。
load：读取schema1、world UUID、manifest SHA、sequence、count、revision、health、checkpointSHA；
  必填缺失/负值/新schema立即异常；不吞异常返回零。加载结果还必须与journalRecovery核对。
save：只写上述header+schema，不写数万个ID；返回tag；自有坐标字段若增加必须3int。
apply：服务器线程，world/hash相同，sequence/revision不倒退；整体current=committed，setDirty。
snapshot：服务器线程返回current；跨线程调用者由WorldServices发布不可变副本，不并发读写current。
```

接线：生产bootstrap的IO恢复先手动验证已有`data/plaincraft_great_wall.dat`，因为原版DimensionDataStorage的读失败可能走supplier空实例。之后服务器线程 `level.getDataStorage().set("plaincraft_great_wall", recoveredState)`；没有旧dat但journal完整可重建派生header并记录恢复。dat坏不能静默丢，先隔离并显式报错；操作员按S5恢复步骤确认后才恢复。没有旧生产manifest的旧世界仍直接拒绝。

## local.plaincraft.greatwall.gameplay.GameplayTypes

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\GameplayTypes.java`。纯模型/JUnit，R2/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class GameplayTypes {
    private GameplayTypes();
    public enum CreditResult { COMMITTED, ALREADY_COMMITTED, ALREADY_PENDING }
    public enum SlotState { AVAILABLE, RESERVED_IO, CONSUMED, ADDED, REFUSED }
    public record Firepit(BeaconId id, Point3i pos, SiteId site, String proofHash) {}
    public record BeforeUse(UUID session, UUID player, String dimension, Point3i pos,
        BeaconId id, String hand, HistoryTypes.Tool tool, boolean beforeLit,
        boolean beforeWaterlogged, String blockId, long gameTime, String proofHash) {}
    public record VerifiedIgnition(BeforeUse before, boolean accepted, boolean afterLit,
        boolean afterWaterlogged, String afterBlockId) {}
    public record BeaconProgress(long count, long revision, HistoryTypes.Health health) {}
    public record OwnedMob(UUID world, SiteId site, int localSlot, long attempt,
        UUID entityId, long consumedSequence, String source) {}
    public record SpawnRequest(HistoryTypes.SlotKey key, Point3i feet, String entityType,
        DecayState decay, String inputHash, String source) {}
    public record SpawnPermit(UUID token, SpawnRequest request, UUID entityId, long sequence) {}
    public record SpawnPolicy(long dayTime, boolean peaceful, boolean mobSpawning,
        boolean playerNear, long spawnDistanceSquared, DecayState decay) {}
    public record LootAssignment(SiteId site, int container, Point3i pos,
        String table, long seed, String inputHash) {}
}
```

compact constructors：deep copy、UUID/ID非空、hand只MAIN_HAND/OFF_HAND；source只TOWER/FORT；SpawnRequest不含Mob对象；输入hash是已生成构件proof hash，不能用日程单独授权未生成场景。

## local.plaincraft.greatwall.gameplay.SiteRuntimeIndex

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\SiteRuntimeIndex.java`。已加载快照/GameTest；R1/R2/P4/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class SiteRuntimeIndex implements AutoCloseable {
    private final ConcurrentHashMap<ChunkKey,List<BlockEntityIntent>> loaded;
    public SiteRuntimeIndex(WorldRecipe recipe);
    public void loaded(net.minecraft.world.level.chunk.LevelChunk chunk, TerrainMarkers.Marker marker);
    public void unloaded(ChunkKey chunk);
    public Optional<GameplayTypes.Firepit> firepit(Point3i pos);
    public Optional<BlockEntityIntent> spawner(Point3i pos);
    public List<SpawnSlot> nearbySlots(Point3i pos, int radius);
    public boolean generatedAndUntouched(Point3i pos, String proofHash);
    public void close();
}
```

```text
ctor冻结recipe。loaded：主线程，marker.recipe匹配且complete；复制gameplay/slots到chunk桶；同hash幂等。
unloaded：删桶和悬空的主线程索引引用；不删永久历史。
firepit：只查对应已加载桶，BE intent type=FIREPIT，解析原ordinal；不存在empty，不临时规划或load chunk。
spawner：同上，类型SPAWNER且当前块仍spawner，已tombstone移除归属。
nearbySlots：radius0..64，只读覆盖chunk桶，距离平方与site/slot排序，返回immutable。
generatedAndUntouched：proof匹配且非tombstone；campfire只切LIT不撤销FIREPIT身份，拆块/替换/挪动则撤销。
  这是OwnershipMutations的特例：同campfire方块仅LIT变化不作“被替换”，WATERLOGGED变化保留ID但不能点火授权；
  方块种类变过哪怕重放campfire永久tombstone。已credited ID当然仍保留。
close清loaded，旧session查询抛STALE_SESSION。
```

S1 `TerrainMarkers.Marker` 已含 `List<SpawnSlot> spawnSlots` 和 `Map<SiteId,DecayState> realizedStates`，不在首次chunk-load又从已改变世界推四态。FIREPIT不是原版BE类型，语义标记放gameplay list；ChunkWallWriter按type白名单只对minecraft:chest/minecraft:mob_spawner创建真实BE，FIREPIT/SLOT等只写attachment。

## local.plaincraft.greatwall.gameplay.BeaconLedger

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\BeaconLedger.java`。纯持久业务/JUnit+GameTest；R2/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class BeaconLedger implements AutoCloseable {
    private final DurableLog journal;
    private final Executor serverOwner;
    private final ConcurrentHashMap<BeaconId,CompletableFuture<GameplayTypes.CreditResult>> pending;
    private final ConcurrentHashMap<BeaconId,Boolean> hotMembership;
    public BeaconLedger(UUID session, String wallId, DurableLog journal, Executor serverOwner,
        GameplayTypes.BeaconProgress recovered);
    public GameplayTypes.BeaconProgress progress();
    public Optional<Boolean> known(BeaconId id);
    public CompletionStage<Boolean> requireCredited(BeaconId id);
    public CompletionStage<GameplayTypes.CreditResult> credit(GameplayTypes.VerifiedIgnition ignition);
    public void retryPending();
    public void close();
}
```

```text
ctor加载recovered count，不遍历FTB队；state发布为CHM单键不可变快照。
known：热点精确membership，空=尚未读取而不是false；4096项8MiB双限制，当前FTB检查可pin。
requireCredited：wallId校验→hot命中立即view；否则journal.lookup("BEACON_CREDIT",结构化id)异步；
  serverOwner回调发布true/false；取消view不取消磁盘shared lookup。
credit：必须合法session/false→true/非waterlogged/accepted/工具允许，且由IgnitionService权威创建。
  pending.putIfAbsent先占ID（上限128）；existing返回ALREADY_PENDING；未读membership先requireCredited。
  已提交→ALREADY_COMMITTED；否则append事件key="beacon/"+完整wallId+"/"+ordinal，payload存玩家/工具/游戏tick/三int。
  receipt durable返回serverOwner，再核对session；计数由journal的BEACON_CREDIT唯一归约增加一次，
    replace progress不可变快照、发布WallWorldState.apply、hot=true、完成COMMITTED。
  append失败保留pendingError，不加count、不强行熄火；错误显示操作员，FTB读到DEGRADED不发新进度。
retryPending：仅journal恢复READY后每tick最多1条，指数20/100/400tick最多3次；之后人工解除磁盘问题再retry。
  重试仍用同eventKey/payload，不能生成新UUID绕过幂等。
close：结束caller views，不删已提交集合；不能关闭后callback向另一个世界加数。
```

崩溃：点燃成功但append尚未force时，允许未计数，重启不从LIT伪造来源；玩家熄灭后再合法手动点燃完成。force后崩溃不再+1。不支持删除账本骗奖励。

## local.plaincraft.greatwall.gameplay.IgnitionService

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\IgnitionService.java`。服务器交互/GameTest；R1/R2/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class IgnitionService {
    private final UUID session;
    private final SiteRuntimeIndex sites;
    private final BeaconLedger beacons;
    public IgnitionService(UUID session, SiteRuntimeIndex sites, BeaconLedger beacons);
    public Optional<GameplayTypes.BeforeUse> beforeUse(net.minecraft.server.level.ServerPlayer player,
        net.minecraft.world.level.Level level, net.minecraft.world.item.ItemStack stack,
        net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit);
    public void afterUse(GameplayTypes.BeforeUse token, net.minecraft.server.level.ServerPlayer player,
        net.minecraft.world.InteractionResult result);
    public Optional<net.minecraft.world.InteractionResult> useTorchAfterPermission(
        net.minecraft.world.item.context.UseOnContext context);
}
```

```text
beforeUse：服务器主线程，level为当前overworld、非旁观者，点击chunk已加载；取得Firepit+proof。
  当前必须原版campfire、!LIT、!WATERLOGGED，生成四火塘之一且未被替换；工具只原版打火石、火焰弹、火把。
  复制playerUUID/hand/tool/pos/blockState相关值/ID/time/proof，不能保存可变ItemStack引用。
  不消耗、不点火、不计数；无资格empty。
afterUse：同session/player，result.consumesAction且当前相同已加载pos从unlit变lit、非水浸、同proof；
  构造VerifiedIgnition→beacons.credit，异步结果只回同session主线程，不调用FTB.addProgress。
  异常退出没有afterUse；火把/打火石最后一件由token保留工具种类。
useTorchAfterPermission：只在ItemStack.useOn内部已过CanPlaceOn冒险检查的Item.useOn调用处触发。
  非torch/非服务器玩家/非合法火塘→Optional.empty让original继续。
  额外检查level.mayInteract(player,pos)、player非旁观者和当前已加载pos；同一用物交互没被外部取消才可能到此。
  false→true：level.setBlock(pos,state.setValue(CampfireBlock.LIT,true),3)成功，发原版点火声音和BLOCK_CHANGE，返回SUCCESS。
  不消耗火把，不放置旁边火把；waterlogged/已lit不自定义成功，走original。
```

火把是保留v2.0用户交互的**自定义行为**，不是声称原版火把可点火；火焰弹同时保留原版入口。保护mod若在更内层有未公开异步许可，必须进入保护矩阵；不能宣称对任意保护mod自动兼容。标准Fabric取消路径和本机领地mod的组合签收需真实运行。

## local.plaincraft.greatwall.mixin.IgnitionUseMixin

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\IgnitionUseMixin.java`。Mixin/GameTest；R1/R2/P5。

```java
package local.plaincraft.greatwall.mixin;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.server.level.ServerPlayerGameMode.class)
public abstract class IgnitionUseMixin {
    private net.minecraft.world.InteractionResult greatwall$observe(
        net.minecraft.server.level.ServerPlayer player, net.minecraft.world.level.Level level,
        net.minecraft.world.item.ItemStack stack, net.minecraft.world.InteractionHand hand,
        net.minecraft.world.phys.BlockHitResult hit,
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.InteractionResult> original);
}
```

`@WrapMethod(method="useItemOn")`：token局部变量=beforeUse，result=original.call五原参数；正常返回才afterUse；return原result。不用静态lastPlayer/ThreadLocal单槽。不要在HEAD见手持就+1。Mixin wrapper只观察，外部保护取消且无边沿始终0。

## local.plaincraft.greatwall.mixin.TorchIgnitionMixin

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\TorchIgnitionMixin.java`。Mixin/GameTest；R1/P5。

```java
package local.plaincraft.greatwall.mixin;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.item.ItemStack.class)
public abstract class TorchIgnitionMixin {
    private net.minecraft.world.InteractionResult greatwall$torch(net.minecraft.world.item.Item item,
        net.minecraft.world.item.context.UseOnContext context,
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.InteractionResult> original);
}
```

`@WrapOperation(method="useOn",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"),require=1)`。调用service.useTorchAfterPermission；有结果返回，否则original.call(item,context)。客户端不造服务、不改LIT，等服务端同步；专用服务器不加载任何客户端类。

## local.plaincraft.greatwall.gameplay.GarrisonController

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\GarrisonController.java`。服务器/GameTest，纯policy JUnit；R1/R2/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class GarrisonController implements AutoCloseable {
    private final net.minecraft.server.level.ServerLevel level;
    private final WorldRecipe recipe;
    private final DurableLog journal;
    private final SiteRuntimeIndex sites;
    private final ConcurrentHashMap<UUID,GameplayTypes.OwnedMob> alive;
    private final ConcurrentHashMap<UUID,GameplayTypes.SpawnPermit> reserved;
    public GarrisonController(net.minecraft.server.level.ServerLevel level, WorldRecipe recipe,
        DurableLog journal, SiteRuntimeIndex sites);
    public static boolean allowed(GameplayTypes.SpawnPolicy policy);
    public void tick();
    public CompletionStage<Optional<GameplayTypes.SpawnPermit>> reserve(GameplayTypes.SpawnRequest request);
    public boolean spawn(GameplayTypes.SpawnPermit permit);
    public void loaded(net.minecraft.world.entity.Entity entity);
    public void unloaded(net.minecraft.world.entity.Entity entity);
    public void release(GameplayTypes.SpawnPermit permit, HistoryTypes.SpawnResult result);
    public int loadedAndReserved();
    public void close();
}
```

```text
ctor：world server-owner；entity引用只owner局部索引，CHM值只是OwnedMob；不得保存ServerLevel到跨世界静态对象。
allowed：!peaceful && mobSpawning && playerNear && distance²>250000 &&
  floorMod(dayTime,24000)在[13000,23000) && decay为INTACT/PARTIAL；rain不替代夜间。
tick：每20tick，最多处理8个nearbySites/最多2个tower slots，主线程本模组玩法总预算2ms；队列轮转。
  非旁观玩家64格内且至少距候选24格，site已complete，slot3个固定类型PILLAGER,PILLAGER,VINDICATOR。
  每座固定3slots实现精确2:1，slot空间全部预留在S1敌楼模板，不加入2人随机变体。
  先异步查永久SLOT_CONSUMED，未消耗才reserve，已消耗不补；昼夜系数0.5实现为固定奇偶夜tick轮流允许尝试，不影响类型比例。
reserve：主线程，重新allowed、实际chunk getChunkNow、无碰撞2高、脚下支撑；alive+reserved+待IO占额>=64拒绝。
  先同步在owner局部reservePending记录UUID占额，UUID由site/slot/attempt确定；异步append SLOT_CONSUMED force。
  回主线程校验session；转为SpawnPermit再完成future，IO失败释放占额并明确错误，不生成。
  已存在消费事件且不是本pending receipt→Optional.empty，不能重启后重新生成未落盘实体。
spawn：同session且reserved含token；再检查夜/和平/玩家/空间/owner已加载；失败release(REFUSED)。
  EntityType.PILLAGER/VINDICATOR.create(level)，null失败；设UUID/位置，finalizeSpawn(level,difficulty,STRUCTURE,null)。
  配PILLAGER弩，VINDICATOR铁斧，setPersistenceRequired，setCanPickUpLoot(false)，各装备dropChance=0。
  Raider.setCanJoinRaid(false)，PatrollingMonster.setPatrolLeader(false)，无旗帜；附持久OwnedMob attachment。
  level.addFreshEntity(entity)仅主线程。成功先把reserved转alive同一owner步骤，后异步MOB_SPAWN_RESULT=ADDED；
    add失败记录REFUSED。slot消费不回滚，因此kill/unload/和平移除/restart都不无限补怪。
loaded：只处理OwnedMob attachment的world匹配实体；先加入alive保守占配额，并异步lookup其SLOT_CONSUMED证据。
  不假定Fabric ENTITY_LOAD之前已有预取屏障；证据校验前不作为新slot补生依据；无记录/不匹配回主线程discard并告警。
  无attachment的玩家召唤或普通巡逻完全不认领；重复UUID由原版拦截。
  若重载旧chunk导致超64，采用下面明确的overflow规则，不伪称仅spawn检查足够。
unloaded：从alive移除，保留SLOT_CONSUMED；死亡不回收一次性slot。区块卸载不是死亡日志。
release：移除reserved/待IO占额一次，异步result日志；重复无操作，slot仍consumed。
loadedAndReserved：alive数量+permit数量+待IO占额，事务完成边界<=64；close取消未durable reservations并结束future。
```

**加载超额的冻结策略 [设计]**：卸载敌人不占64，玩家可再加载旧敌人。仅限制出生不能保证加载上限，必须显式处理。每次实体load回调结束前，按`consumedSequence升序、UUID字典序`保留前`64-reserved`个，其余长城自有实体 `discard()`，不触发死亡掉落/经验，永久slot不退回，并记`MOB_RETIRED/REMOVED_BY_CAP`诊断。原版袭击、巡逻、玩家怪物完全不动。此策略不是偷偷复活或无限挂起；S3报告必须单列“过额重新加载会撤去后出生的长城敌人”。若产品不接受此固定行为，64必须改为“新生配额”而不是硬加载上限；执行者不得私自改口径。

一次性出生的崩溃策略固定 **at-most-once**：consume force后、add前断电允许少一名，不自动补；记录/统计LOST_BEFORE_ADD，不假称exactly-once。可持续的关隘笼下一轮使用新attempt，但不得复用失败attempt。

## local.plaincraft.greatwall.gameplay.WallSpawnerGuard

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\WallSpawnerGuard.java`。原版笼适配/GameTest；R1/R2/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class WallSpawnerGuard {
    private final GarrisonController garrison;
    private final SiteRuntimeIndex sites;
    public WallSpawnerGuard(GarrisonController garrison, SiteRuntimeIndex sites);
    public boolean tickOwned(net.minecraft.world.level.BaseSpawner spawner,
        net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos);
}
```

```text
ctor保存依赖；tickOwned返回true表示本mod已处理且不能再调原生serverTick。
  sites.spawner不存在→false，完全保留原生行为。存在但已毁/废墟→true禁止新生。
  固定参数：RequiredPlayerRange16、SpawnCount2、MaxNearby3、SpawnRange4、Delay400..800。
  不满足allowed或16内无玩家→true，冻结倒计时，不用白天快速累积补偿。
  Delay>0：BaseSpawnerAccess.setDelay(delay-1)，true。
  Delay==0：同site已pending async birth则true；周围8×8×8已加载自有同site敌人>=3则delay20重查。
  每次最多2名，顺序j=0,1，每名独立reserve，占同一全局64；不在tick头只查一次额度。
  候选XYZ由site/attempt散列固定，XZ偏移[-4,4]、Y偏移[-1,1]；16次试位，无已加载支撑空间则本次不生。
  attempt先通过journal消费流水分配，比例attempt%3=0/1掠夺、2卫道；每次force完成后主线程garrison.spawn。
  不依赖怪物天然亮度规则，关隘可点灯；仍受碰撞、夜、gamerule、难度、出生圈、Nearby和全局帽约束。
  结束一轮delay=400+bounded(hash(site,attempt),401)，写原笼delay并setChanged；单spawner最多1轮在途。
```

为避免在原版同步每次`tryAddFreshEntityWithPassengers`里等待日志，本模组**仅对这两个已认证笼接管出生循环**；外观仍原版SpawnerBlockEntity，使用原版BaseSpawner显示及存盘。其它笼一律original。实际每次add必须经过garrison已提交permit；这比在原版内联tryAdd处临时异步截断更明确，不持有临时Mob跨IO线程。

## local.plaincraft.greatwall.mixin.WallSpawnerMixin

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\WallSpawnerMixin.java`。Mixin/GameTest；R1/P5。

```java
package local.plaincraft.greatwall.mixin;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.level.BaseSpawner.class)
public abstract class WallSpawnerMixin {
    private void greatwall$tick(net.minecraft.server.level.ServerLevel level,
        net.minecraft.core.BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci);
}
```

HEAD serverTick，cancellable=true、require=1：存在本world active service且tickOwned=true才ci.cancel；其它情况保留原方法。

## local.plaincraft.greatwall.mixin.BaseSpawnerAccess

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\BaseSpawnerAccess.java`。Mixin/GameTest，R1。

```java
package local.plaincraft.greatwall.mixin;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.level.BaseSpawner.class)
public interface BaseSpawnerAccess {
    @org.spongepowered.asm.mixin.gen.Accessor("spawnDelay") int greatwall$delay();
    @org.spongepowered.asm.mixin.gen.Accessor("spawnDelay") void greatwall$delay(int value);
}
```

getter/setter仅原字段，value0..800；无自造未核实方法名。客户端仅观察原版同步动画不做出生决策。

## local.plaincraft.greatwall.gameplay.WalkwaySpawnGuard

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\WalkwaySpawnGuard.java`。已加载查询/GameTest；R1/R2。

```java
package local.plaincraft.greatwall.gameplay;
public final class WalkwaySpawnGuard {
    private WalkwaySpawnGuard();
    public static boolean blocksNaturalSpawn(net.minecraft.server.level.ServerLevel level,
        net.minecraft.core.BlockPos feet);
}
```

```text
getChunkNow为null→false，不拉区块；取已持久marker.walkwayFeet，按三int精确查询脚位。
只在原版NATURAL候选检查调用本类；位于步道mask且脚下仍自有地板、无tombstone→true。
塔内/地面/矿洞没有该mask不禁；上半砖不是许可或防刷依据。
```

## local.plaincraft.greatwall.mixin.NaturalSpawnMixin

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\NaturalSpawnMixin.java`。Mixin/GameTest；R1。

```java
package local.plaincraft.greatwall.mixin;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.level.NaturalSpawner.class)
public abstract class NaturalSpawnMixin {
    private static void greatwall$walkway(net.minecraft.server.level.ServerLevel level,
        net.minecraft.world.entity.MobCategory category, net.minecraft.world.level.StructureManager structures,
        net.minecraft.world.level.chunk.ChunkGenerator generator,
        net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData data,
        net.minecraft.core.BlockPos.MutableBlockPos pos, double distance,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir);
}
```

HEAD `isValidSpawnPostitionForType`（原版确实拼成Postition），require1；guard=true返回false，其他不动。不改EntityType/SpawnPlacements通用predicate，玩家召唤/SPAWNER/STRUCTURE/袭击不经过此否决。

## local.plaincraft.greatwall.gameplay.LootInitializer

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\gameplay\LootInitializer.java`。纯分配+BE/GameTest，R1/R2/P5。

```java
package local.plaincraft.greatwall.gameplay;
public final class LootInitializer {
    public LootInitializer();
    public Optional<GameplayTypes.LootAssignment> assignment(WorldRecipe recipe,
        SiteRecord site, DecayState decay, int containerId, Point3i pos, long nearestFortDistanceQ8);
    public net.minecraft.nbt.CompoundTag initialNbt(GameplayTypes.LootAssignment assignment);
    public void initializeNew(net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity chest,
        GameplayTypes.LootAssignment assignment);
}
```

```text
assignment：RUIN/BURIED无箱；敌楼仅hash("loot-present",site)%100<35输出，其余不放箱（多数空置）。
  距fort<=600块且INTACT用supplies；PARTIAL用weathered；其它用ordinary。
  fort容器0用armory、1supplies、2ordinary；seed=ordered hash(seed,site,container)，非随机UUID。
initialNbt：id=minecraft:chest,x/y/z三个int,LootTable=namespace:path,LootTableSeed=long；不提前展开Items。
initializeNew：仅ChunkWallWriter首次合法BE初始化；确认原目标无BE、同生成proof未初始化；
  setLootTable(ResourceKey.create(Registries.LOOT_TABLE,id))；setLootTableSeed(seed)；setChanged。
  不在CHUNK_LOAD调用，不因lootTable已被打开清空就再设置；回填不得触碰箱。
```

发布资源固定4个chest类型loot_table；每个1pool，`rolls`整数均匀范围如下，entry权重合100，`set_count`指定闭区间：

| table | rolls | entries：权重/数量 |
|---|---|---|
| chests/ordinary | 1..3 | bread40/1..3，arrow30/2..8，iron_nugget20/2..6，torch10/2..5 |
| chests/weathered | 0..2 | string35/1..3，stick35/2..5，arrow20/1..4，iron_nugget10/1..3 |
| chests/supplies | 2..4 | bread35/2..5，arrow30/4..12，iron_ingot20/1..2，leather15/1..3 |
| chests/armory | 2..4 | arrow40/8..16，iron_ingot35/1..3，crossbow15/1，iron_axe10/1 |

不放钻石/下界合金、不自动加任务奖励。自有敌人掉落另用 `entities/garrison` 表：1pool，empty权90，arrow权10数量1..2；经验固定0。用持久entity attachment与下面Mixin限定，只降本模组怪物，不修改原版表。

## local.plaincraft.greatwall.mixin.OwnedMobLootMixin

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\mixin\OwnedMobLootMixin.java`。Mixin/GameTest，R2/P5。

```java
package local.plaincraft.greatwall.mixin;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.entity.Mob.class)
public abstract class OwnedMobLootMixin {
    private void greatwall$loot(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable>> cir);
    private void greatwall$xp(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir);
}
```

getLootTable RETURN require1与getBaseExperienceReward RETURN require1；仅合法OwnedMob attachment存在时分别返回自有loot key/0。DropChance在出生初始化已设0。不能写不存在的Mob.setLootTable方法。Mixin能注入final实例方法，但不能Java override final getLootTable。

## local.plaincraft.greatwall.compat.ftb.FtbIntegration

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\ftb\FtbIntegration.java`。FTB软依赖/真实集成；R2/P5。

```java
package local.plaincraft.greatwall.compat.ftb;
public final class FtbIntegration {
    private static final ConcurrentHashMap<String,dev.ftb.mods.ftbquests.quest.task.TaskType> TYPES;
    private FtbIntegration();
    public static void initialize();
    public static dev.ftb.mods.ftbquests.quest.task.TaskType countType();
    public static dev.ftb.mods.ftbquests.quest.task.TaskType specificType();
    public static void configure(dev.ftb.mods.ftbquests.quest.task.CustomTask task);
    public static Optional<BeaconLedger> ledger(net.minecraft.server.level.ServerPlayer player);
    public static void lint(dev.ftb.mods.ftbquests.quest.BaseQuestFile file);
}
```

```text
initialize：common启动主线程一次，Loader确认ftbquests+ftblibrary+ftbteams+architectury实际版本/SHA白名单。
  TaskTypes.register(ResourceLocation.fromNamespaceAndPath(modid,"beacon_count"),BeaconCountTask.Provider.INSTANCE,
    () -> Icon.getIcon("minecraft:block/campfire"))；specific同理用minecraft:item/flint_and_steel。
  TaskType存TYPES；supplier延迟，不主动解析客户端纹理；dedicated server smoke必须证明可加载。
  CustomTaskEvent.EVENT.register(event->{configure(event.getTask()); return EventResult.pass();})。
  ClearFileCacheEvent.EVENT.register(file->{仅清本次诊断/验证订阅，不保留旧Task引用；待读完事件再configure新对象;})。
countType/specificType：get或throw未初始化，不返回null让FTB默默当CUSTOM。
configure：仅ServerQuestFile且task instanceof两种自家子类，调用configureServerCheck；其他任务不修改。
ledger：player!=null/server!=null且当前WorldServices ACTIVE→其overworld BeaconLedger；
  玩家人在下界也返回overworld视图；无服务返回empty且任务不自动完成。
lint：只读扫描自家任务，repeatable任务/target非法/指定台terminal后或SUPPRESSED打印具任务ID错误；
  指定台验证使用有界异步plan，不在FTB文件加载线程规划百万格；未结束状态显示VALIDATING，不假定可达。
```

类型只在FTB的任务文件读取前注册，world服务在服务器启动后存在。两者时序分开，constructor不能读取尚未创建的世界账本。Core的字段、签名、静态初始化均不引用FTB类型；`if (FabricLoader.getInstance().isModLoaded("ftbquests")) FtbIntegration.initialize()`是唯一入口，缺席时绝不触达该类。

## local.plaincraft.greatwall.compat.ftb.BeaconCountTask

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\ftb\BeaconCountTask.java`。FTB软依赖/真实集成；R2/P5。

```java
package local.plaincraft.greatwall.compat.ftb;
public final class BeaconCountTask extends dev.ftb.mods.ftbquests.quest.task.CustomTask {
    private long targetCount;
    public enum Provider implements dev.ftb.mods.ftbquests.quest.task.TaskType.Provider {
        INSTANCE;
        public dev.ftb.mods.ftbquests.quest.task.Task create(long id, dev.ftb.mods.ftbquests.quest.Quest quest);
    }
    public BeaconCountTask(long id, dev.ftb.mods.ftbquests.quest.Quest quest);
    public dev.ftb.mods.ftbquests.quest.task.TaskType getType();
    public void writeData(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup);
    public void readData(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup);
    public void writeNetData(net.minecraft.network.RegistryFriendlyByteBuf buffer);
    public void readNetData(net.minecraft.network.RegistryFriendlyByteBuf buffer);
    public void fillConfigGroup(dev.ftb.mods.ftblibrary.config.ConfigGroup config);
    public void editedFromGUIOnServer();
    public boolean checkOnLogin();
    public long targetCount();
    public void configureServerCheck();
}
```

```text
Provider.create：new BeaconCountTask(id,quest)，返回Task。
ctor：super(id,quest)，targetCount=10；仅服务器file时configure，不触发任务完成。
getType：FtbIntegration.countType；targetCount getter；checkOnLogin=true。
writeData：super后putLong("target_count",targetCount)。
readData：super；无字段默认10；否则getLong，必须1..1_000_000；非法拒绝并带task id；服务器configure。
writeNetData：super后buffer.writeLong(targetCount)。
readNetData：super后readLong校验；只赋targetCount，不调用任何setCheckTimer/setMaxProgress/setEnableButton；
  比较super.getMaxProgress==targetCount，否则协议错误，不用客户端setter污染TaskSync.toSync。
fillConfigGroup：super；config.addLong("target_count",targetCount,v->targetCount=v,10,1,1_000_000)。
  编辑callback仅更新字段；服务端认可提交后editedFromGUIOnServer才configure。
editedFromGUIOnServer：super调用后校验→configure；不调用客户端Task.editedFromGUI，不override final onCreated。
configureServerCheck：若非ServerQuestFile return；setEnableButton(false)，setCheckTimer(20)，setMaxProgress(N)。
  setCheck((data,player)->{
    ledger=FtbIntegration.ledger(player)；无/health!=READY不更新；
    next=min(ledger.progress.count,N)；data.getProgress!=next才data.setProgress(next)；
  })，lambda返回void，不是boolean。
```

只通过FTB原有自动tick/login→submitTask→checkTaskSequence→completed判断调用Check。不监听每次点火给所有队`addProgress(1)`；不直接`TeamData.forceProgress`。已完成任务不因改N被本mod撤回奖励或重发；编辑器调整已完成任务的重置由FTB管理，不绕过它。

## local.plaincraft.greatwall.compat.ftb.BeaconSpecificTask

文件：`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\compat\ftb\BeaconSpecificTask.java`。FTB软依赖/真实集成；R2/P5。

```java
package local.plaincraft.greatwall.compat.ftb;
public final class BeaconSpecificTask extends dev.ftb.mods.ftbquests.quest.task.CustomTask {
    private long targetOrdinal;
    private String wallSelector;
    public enum Provider implements dev.ftb.mods.ftbquests.quest.task.TaskType.Provider {
        INSTANCE;
        public dev.ftb.mods.ftbquests.quest.task.Task create(long id, dev.ftb.mods.ftbquests.quest.Quest quest);
    }
    public BeaconSpecificTask(long id, dev.ftb.mods.ftbquests.quest.Quest quest);
    public dev.ftb.mods.ftbquests.quest.task.TaskType getType();
    public void writeData(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup);
    public void readData(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup);
    public void writeNetData(net.minecraft.network.RegistryFriendlyByteBuf buffer);
    public void readNetData(net.minecraft.network.RegistryFriendlyByteBuf buffer);
    public void fillConfigGroup(dev.ftb.mods.ftblibrary.config.ConfigGroup config);
    public void editedFromGUIOnServer();
    public boolean checkOnLogin();
    public long targetOrdinal();
    public String wallSelector();
    public void configureServerCheck();
}
```

```text
Provider.create new实例；ctor super，targetOrdinal=1，wallSelector="current_overworld"；服务器configure。
getType=FtbIntegration.specificType；getter按字段；checkOnLogin=true。
writeData super后写target_beacon_ordinal(long)、wall_selector(string)。
readData super后读/default；ordinal1..1_000_000且selector仅current_overworld，非法拒绝；服务器configure。
writeNetData super后writeLong/writeUtf(max64)；readNetData super后readLong/readUtf(64)，校验并只赋字段；
  super.maxProgress必须1；不调同步setter。
fillConfigGroup super后addLong(...1,1,1_000_000)，addString(..."current_overworld")；setter只更新字段。
editedFromGUIOnServer super→validate→configure→异步lint定位，不能主线程等待prefix。
configureServerCheck：server only，enableButton=false/checkTimer20/max1；
  Check读取overworld.wallId+targetOrdinal形成BeaconId；known empty→启动合并requireCredited，不立刻写0；
  下次自动检查known=true/false，next1/0，变化才setProgress；history不健康不更新；不长期持有任务对象到异步callback。
```

世界已有10次历史时，新队/从单人转局域网/玩家换队均读同一个count；离线队登录由FTB自有链追平。前置锁着时不提前领奖，解锁后下一20tick追平。每队奖励仍按FTB配置，不声称世界唯一奖励。示例SNBT只提供任务体，不伪造外层章节ID、不自动安装/改写玩家的任务集：

```snbt
{ type: "plaincraft_great_wall:beacon_count", target_count: 10L }
{ type: "plaincraft_great_wall:beacon_specific", target_beacon_ordinal: 5L, wall_selector: "current_overworld" }
```

## S3 接线与完整文件清单

所有以上标题类每类一个完整路径，外加以下新增/修改文件；不新增未描述的生产类：

```text
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\DurableLog.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\WorldServices.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\TerrainMarkers.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\OwnershipMutations.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ChunkWallWriter.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\GreatWallMod.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\plaincraft_great_wall.mixins.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\fabric.mod.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\loot_table\chests\ordinary.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\loot_table\chests\weathered.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\loot_table\chests\supplies.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\loot_table\chests\armory.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\data\plaincraft_great_wall\loot_table\entities\garrison.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\assets\plaincraft_great_wall\lang\zh_cn.json
D:\云游四海-Mod源码\plaincraft-great-wall\src\main\resources\assets\plaincraft_great_wall\lang\en_us.json
D:\云游四海-Mod源码\plaincraft-great-wall\docs\ftb\task-examples.snbt
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\persistence\HistoryJournalTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\gameplay\BeaconLedgerTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\gameplay\GarrisonPolicyTest.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\ExplorationGameTests.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\IgnitionGameTests.java
D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\FtbGameTests.java
D:\云游四海-Mod源码\plaincraft-great-wall\tools\Test-S3Gameplay.ps1
```

WorldServices增加完整导出签名：`public BeaconLedger beacons()`、`public GateLedger gates()`、`public SiteRuntimeIndex sites()`、`public GarrisonController garrison()`、`public IgnitionService ignition()`、`public WallSpawnerGuard spawners()`、`public HistoryJournal history()`、`public UUID session()`、`public WallWorldState worldState()`。这些getter返回本session已初始化服务，未ACTIVE抛状态错误，不临时新建。

`GreatWallMod.onInitialize()`在注册FTB前初始化自家资源/attachment，在core内做Loader软检测。`ServerChunkEvents.CHUNK_LOAD/UNLOAD`接SiteRuntimeIndex；`ServerEntityEvents.ENTITY_LOAD/UNLOAD`接garrison；`END_SERVER_TICK`接玩法，`SERVER_STOPPING`先停新credit/spawn再flush history；FTB注册是进程一次、账本是世界一次，重进不能多次注册事件。

TerrainMarkers新增`AttachmentType<GameplayTypes.OwnedMob> OWNED_MOB`，codec字段显式名称+UUID+3int，Fabric持久entity attachment不设置复制到死亡克隆；`Marker`完整schema更新只在新测试/发布世界使用，不能把S1测试旧区块当生产旧存档回填。

## S3 验收清单（对应原 §5.5 全部）

每一行独立测试方法，Junit实例签名`void name() throws Exception`；GameTest实例`public void name(GameTestHelper helper)`。参数化只写表中的笛卡儿积，不由执行者随意缩减。

| 测试类/方法 | 输入与唯一预期 |
|---|---|
| HistoryJournalTest.appendIsDurableAndIdempotent | 同key100次/重启/两owner请求，唯一seq/count；不同payload拒绝 |
| HistoryJournalTest.completeBadCrcIsNotTruncated | 完整尾CRC坏→CORRUPT；半条尾→存副本后恢复 |
| HistoryJournalTest.checkpointDoesNotDropConcurrentTail | K切HEAD时新append仍连续；恢复exact ID集合一致 |
| HistoryJournalTest.pagedHistoryKeepsHeadAndResidentMemoryBounded | 8193个不同ID、跨页/桶/两次checkpoint；HEAD只含2根、热点页<=8MiB、首/中/末ID及不存在ID精确可查；seq分页无漏无重复 |
| HistoryJournalTest.missingSavedDataNeverZerosHistory | journal有10、dat缺失→明确恢复10；dat坏→拒绝未确认恢复 |
| BeaconLedgerTest.fourPitsTwoPlayersOneCredit | 四火塘8token乱序，count最终1，pending与committed不重叠 |
| BeaconLedgerTest.failedFsyncNeverPublishes | force失败count0；同key恢复重试仅1；旧session晚完成无跨世界写 |
| GarrisonPolicyTest.policyCartesianMatrix | day12999/13000/22999/23000×和平/困难×gamerule×499/500/501×四态；仅合同组合true |
| ExplorationGameTests.fortIsExactly48AndHasTwoSpawners | XZ半开48，笼恰2，主路净4×5，所有房间与W/屋顶可达 |
| ExplorationGameTests.fortAbsorptionPreservesBeaconId | 同原ordinal，四火塘、无第五ID；吸收敌楼保留序号 |
| ExplorationGameTests.allConcurrentBirthsRespect64 | 初63+同tick两笼/tower，最多1成功，reserved含IO中占额 |
| ExplorationGameTests.reloadOverflowUsesDocumentedRetirement | 旧实体重新加载超64，稳定排序保留64，撤退0掉落/0经验，slot不退 |
| ExplorationGameTests.consumedTowerSlotsNeverRespawn | 杀死/卸载/重启/和平清除/缓存重建各流程，无补生 |
| ExplorationGameTests.spawnerOnlyControlsOwnedTwo | 白天/出生圈owned笼0；普通笼/袭击/巡逻保持原逻辑 |
| ExplorationGameTests.topSlabCounterexampleAndMask | 控制上半砖可刷；正式mask阻natural，邻地面与指令spawn不误禁 |
| ExplorationGameTests.chestNeverRefillsAfterOpen | 初次正确表/seed；打开拿空→rebuild/reload→仍空，无new chest |
| IgnitionGameTests.mainOffhandAndLastUse | 正副手、最后耐久打火石、最后火焰弹合法边沿各独立台+1 |
| IgnitionGameTests.torchUsesPostPermissionPath | 原火把不消耗、合法台点燃+1；被拒保护/冒险无许可/水浸+0 |
| IgnitionGameTests.nonPlayerSourcesNeverCredit | 火矢/发射器/命令/别处自建火塘/已有lit右键均0 |
| IgnitionGameTests.extinguishReplaceReloadDoesNotFarm | 熄重燃/拆重放/重进/cache损坏每已提交台最多1 |
| IgnitionGameTests.rainAndNoBetterSmokeKeepCredit | 雨不灭；服务端无BetterSmoke合法动作正常+1 |
| FtbGameTests.worldProgressMirrorsTwoTeams | 同世界2队主从客户端进度同count，各自奖励只按FTB一次 |
| FtbGameTests.newAndOfflineTeamsCatchUpAfterUnlock | 已有10→新队/离线登录；锁前不越过顺序，解锁20tick内追平 |
| FtbGameTests.editReloadCreateTaskPreservesTypes | 编辑N/reload/新建specific，getType正确、网络max一致、无旧引用、无重奖 |
| FtbGameTests.missingOptionalModsDoNotLoadClasses | 无FTB/road/BetterSmoke dedicated与client均启动，无NoClassDefFoundError |
| IgnitionGameTests.highYUsesThreeInts | 实际合法2047/2048/4095/4096/5999逐点BE/ledger/network定位一致；整包打包不支持则能力测试明确失败 |

服务器单测不能代替双客户端主从/两队/客户端编辑器界面。工具脚本创建全新隔离测试世界，保存服务端日志、FTB任务SNBT、两客户端截图与计数前后；没跑到的写“待运行验证”，不打勾。

拓扑：HistoryTypes→DurableLog补充→HistoryJournal基础(S2前置)→WallWorldState→GameplayTypes→Fort/LootInitializer→TerrainMarkers/SiteRuntimeIndex→BeaconLedger/GarrisonController→IgnitionService/WallSpawnerGuard/WalkwaySpawnGuard→六个Mixin及accessor→FTB两个Task.Provider/Task→FtbIntegration→WorldServices事件接线→真实主从/双队矩阵。两个Task与FtbIntegration有类型互引但无静态构造读取world；同一编译批次处理即可，不形成运行future依赖环。
