# S1A 缺失输入补充合同

日期：2026-09-29。依据用户“如果有缺失你可以自己补充信息”的授权。本文件仅补齐原实施规格无法从参数获得的信息，不修改2048段长、300日程、预算、起点、世界红线或生成屏障。与原册对应方法有冲突时，以本补充为准。

1. `HeightContractPlanner(WorldRecipe recipe)`替代尚未实现的无参构造；保留`solve/ walkY`参数。维度上下界来自不可变recipe，不能从地面样本最小/最大值猜测。
2. `SegmentCacheStore(Path root, WorldRecipe recipe)`替代尚未实现的单Path构造；固定seed/algorithm/dimension/recipe用于文件头、读校验与路径隔离，不读取全局世界对象。每卷的manifest采用独立geometry/plan段指针，原子更新各槽，避免不同段并发写导致整卷索引丢项。
3. `HydrologyClassifier.begin`只认证河道宽度、岸点和河床墩候选，不在尚无W合同时判支撑深度。新增`public WaterEvidence resolveSupport(WaterEvidence evidence, ToIntFunction<Point3i> walkY)`：对NARROW各墩检查`max(0,walkY(p)-20-p.y())<=12`，否则返回同证据的SUPPORT_GAP；其它分类原样返回。生产在1B取得完整跨段高度合同时调用；1A用解析合同验证此纯函数。未知水文仍失败，不改为海洋终止。此拆分不让几何反向等待高度/prefix。
4. RouteShape含SEARCH与boundary两个计数，SegmentGeometry只有总计数。route内存cache miss不从geometry总数猜两个分账；重新跑同一个Ridge纯函数。geometry磁盘cache仍可直接用于geometry，不增永久路线路径协议。
5. CanonicalCodec补充`stations(StationTable)`、`prefix(PrefixSummary)`、`readPrefix(byte[])`，用于原规格已有站表hash与前缀存储；前缀proofHash是必须保存的链字段，不是被省略的自身payload校验和。`readWritePlan(byte[])`执行8MiB/有界集合检查；带`DimensionBounds`重载额外检查体素数量及所有Y，生产调用后者，不猜维度高度。
6. PrefixSummaryIndex新增静态纯函数`reduce(PrefixSummary,SegmentGeometry)`，原`combine`委托它；缓存验证用同一归约公式，不复制另一套链哈希算法。
7. PrefixSummaryIndex增加四参构造（最后参数`Executor io`），三参构造保留给独立IO owner测试。生产必须传独立IO executor。SegmentCacheStore增加`recipeHash()`不可变访问器、`corruptCount()`诊断访问器；前缀读取用冻结recipe且在IO执行，不在pump同步读盘。缓存CRC32C覆盖压缩字节，SHA-256覆盖原始canonical字节。前缀链固定为`SHA256(CanonicalCodec.prefix(新的边界/累计长/terminal/旧proof) || nextGeometryHash的32字节)`。
8. HeightContractPlanner增加纯函数`samplePoints(SegmentGeometry,PrefixSummary,List<SiteRecord>)`，返回稳定去重的合同列请求；不含已在RouteShape采过的两端点，最多5998。平台九点沿模板X/Z各取min+0.5、0、max-0.5，平台Y取脚面中位数+20。原规格未定义Gate侧翼socket，S1A不臆造：Gate可吸收的socket数=0，冲突塔保留ID并SUPPRESSED(CONFLICT)。Fort按S3已有两侧翼定义，一类最多一个，最多两个；原日程S不改变。公共纯函数`PrefixSummaryIndex.origin()`统一零前缀，不各类复制seed proof常量。
9. PlannerScheduler增加只读`diagnostics()`（`Map<String,Long>`不可变快照），记录启动结果、缓存禁写、同步诊断次数/最大耗时、64原语批次最大耗时与超过40ms次数；时间不进入hash。水平安全包络保守取512+192+96+48=848格（载波、残差、构件与参考侧采样），以完整段而非单锚点判定WORLD_LIMIT。
10. 确定性哈希的长度混合固定为：`h=mix64(mix64(seed)^mix64(domainUtf8.length))`；逐字节后`h=mix64(h^mix64(values.length))`，再依序混值。原文“先混长度”在此展开为唯一编码。水文九点/扇形评分的侧点按投影轴坐标系旋转；前瞻点使用固定COARSE请求，边界/水文/高度使用REFERENCE。地下有顶盖水体必须由oracle输出干燥的顶盖脚面，参考列截断或有水无河床不能认证为干地。
11. BudgetCache先预留`4096*waiterLimit+256*entryLimit`字节请求/索引元数据，再从同一个byteLimit内分配值与进行中reservation；默认段缓存64MiB不变、4096调用者/256完成项不变。route/geometry/plan的上界分别1/2/4MiB。值的retained公式见PlannerScheduler，不以canonical字节数充当堆占用。该公式的堆/JFR实测仍属S5，不能写成已测得精确堆大小。
12. 在申请缓存reservation之前，PlannerScheduler的plan和geometry各只准入4个申请，其余进入有界owner队列；route不依赖父级准入。此门槛防止父级plan占满64MiB后自身依赖的route永远无法reserve。不得把准入门槛放到reservation之后；同一结果的后续调用者仍各有独立pin，不取消其他调用者。

本文件不宣称以上实现/测试已经完成；实际状态只记在`docs/S1A.md`。
