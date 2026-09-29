# S1A 缺失输入补充合同

日期：2026-09-29。依据用户“如果有缺失你可以自己补充信息”的授权。本文件仅补齐原实施规格无法从参数获得的信息，不修改2048段长、300日程、预算、起点、世界红线或生成屏障。与原册对应方法有冲突时，以本补充为准。

1. `HeightContractPlanner(WorldRecipe recipe)`替代尚未实现的无参构造；保留`solve/ walkY`参数。维度上下界来自不可变recipe，不能从地面样本最小/最大值猜测。
2. `SegmentCacheStore(Path root, WorldRecipe recipe)`替代尚未实现的单Path构造；固定seed/algorithm/dimension/recipe用于文件头、读校验与路径隔离，不读取全局世界对象。每卷的manifest采用独立geometry/plan段指针，原子更新各槽，避免不同段并发写导致整卷索引丢项。
3. `HydrologyClassifier.begin`只认证河道宽度、岸点和河床墩候选，不在尚无W合同时判支撑深度。新增`public WaterEvidence resolveSupport(WaterEvidence evidence, ToIntFunction<Point3i> walkY)`：对NARROW各墩检查`max(0,walkY(p)-20-p.y())<=12`，否则返回同证据的SUPPORT_GAP；其它分类原样返回。生产在1B取得完整跨段高度合同时调用；1A用解析合同验证此纯函数。未知水文仍失败，不改为海洋终止。此拆分不让几何反向等待高度/prefix。
4. RouteShape含SEARCH与boundary两个计数，SegmentGeometry只有总计数。route内存cache miss不从geometry总数猜两个分账；重新跑同一个Ridge纯函数。geometry磁盘cache仍可直接用于geometry，不增永久路线路径协议。
5. CanonicalCodec补充`stations(StationTable)`、`prefix(PrefixSummary)`、`readPrefix(byte[])`，用于原规格已有站表hash与前缀存储；前缀proofHash是必须保存的链字段，不是被省略的自身payload校验和。`readWritePlan(byte[])`执行8MiB/有界集合检查；带`DimensionBounds`重载额外检查体素数量及所有Y，生产调用后者，不猜维度高度。
6. PrefixSummaryIndex新增静态纯函数`reduce(PrefixSummary,SegmentGeometry)`，原`combine`委托它；缓存验证用同一归约公式，不复制另一套链哈希算法。
7. PrefixSummaryIndex增加四参构造（最后参数`Executor io`），三参构造保留给独立IO owner测试。生产必须传独立IO executor。SegmentCacheStore增加`recipeHash()`不可变访问器、`corruptCount()`诊断访问器；前缀读取用冻结recipe且在IO执行，不在pump同步读盘。缓存CRC32C覆盖压缩字节，SHA-256覆盖原始canonical字节。前缀链固定为`SHA256(CanonicalCodec.prefix(新的边界/累计长/terminal/旧proof) || nextGeometryHash的32字节)`。

本文件不宣称以上实现/测试已经完成；实际状态只记在`docs/S1A.md`。
