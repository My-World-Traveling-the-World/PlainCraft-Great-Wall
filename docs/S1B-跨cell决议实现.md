# S1B 跨 cell 地面出口决议

日期：2026-09-29。对应用户已批准的 B-S1B-03，实现基线 `0be2433`。

本文记录已实现的协议，不是另起设计。实际测试数量、最终提交和签收边界以 `S1B.md` 及 `evidence/s1b/cross-cell/` 为准。生产生命周期仍关闭，未进入 S1C。

## 1. 身份与不可变边界

实现文件：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\GroundExitDecision.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\GroundExitResolver.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\persistence\GroundExitCodec.java`

`GroundExitDecision.Key` 是 recipeHash、维度、绝对 Q8 弧长区间。区间以相邻的、**已经完整 T0 验证并与自然地面连通**的出口为边界；永久终止的一侧用 `Long.MAX_VALUE` 表示，另存精确 `terminalSQ8`。这不是按当前请求 cell 或已加载区块选边界。

`Exit` 含 SiteId、绝对 S、完整足印的 S 区间、组件 hash、T0 证据 hash。判定不能只检查计划有 Gate/Fort：模板先完整编译，再检查从 W 步道经过合法组件脚面到自然地面的连接。没有通地的高台不会进入出口索引。

决议还包含原始/实现断口、最终排除区间、接受的 cap SiteId、原断口 Edge、实际退让 S、完整组件 hash 和实际占用 S 范围。T0 归约按绝对 cell 升序进行；原始页、列证据、保护摘要、gateRevision、规划 hash 参与身份。最终 SHA-256 覆盖整个二进制 payload，而不是只 hash 一个 cell 编号。

当前出口证据 payload schema 为 2；第二版增加 cap 的实际占用区间，避免正向马道仍错误套用“只向后30格”的包络。该新协议此前没有部署到真实世界；未知版本明确拒绝，不静默升级历史。

## 2. 查找和共享

`GroundExitResolver.require(CellKey)` 返回独立可取消的 future 视图。

1. 向前面的 cell 取得最近的已接受出口；没有根出口返回 ROOT_NOT_FOUND。
2. 沿 S 向后找到下一个已接受出口或永久终点。
3. 对封闭区间按 S 顺序逐 cell 归约断口，分别验证每段可走区间的端部 cap。
4. 每个端点仍只尝试原来的 `0,4,...,128`；候选需要完整模板、完整 T0、合法 H 和支撑，不放宽为更深基础。
5. cap 均失败时，在相同决议中撤回到已接受出口，或撤回一个没有合法两端出口的子区间；不是抛出原来的“no proven ground exit”来代替回退。
6. 一个 Key 的并发消费者共享同一 single-flight；封存后的不可变决议可以在容量内复用。不同请求顺序不得更新已有边界。

一个已接受且可通地的站点是安全的区间分界：它后方将来发生的断口，无须撤回它前方已经闭合的区间。跨过多个分界的 cell 等待有序决议列表，不能只等列表中第一个。

`ArcPlanProvider` 使用 SegmentPlan 的 PrefixSummary 查找绝对 S。不会把轴向 u 当实际 S，也不承诺冷启动 O(1)。它按段异步前进并关闭已经使用完的租约；遇到最早永久终点停止。查询海后远处时不会生成一条第二长城。

## 3. PREPARE / CAPTURE / APPLY 接线

`ProductionWallPipeline` 新增绝对 S 的 `arcPlans` 端口，生产构造由 `ArcPlanProvider` 接 `PlannerScheduler.requirePlan`。

```text
beforeLight(owner)
  锁外排队
  只读检查已有 marker
  等所有相交 cell 的 GroundExitResolver.require
    按单 cell 取得有限 FEATURES halo
    CAPTURE 并耐久化原 T0
    inspect / probeCap：纯不可变输入
    封存共同的 GroundExitDecision
  resolve(input, decisions)
  整件决议后裁 owner
  核验并落盘 build-exits + build-proof
  native exclusive 中预检完整写集并 APPLY
```

规划、取较早出口、取 FEATURES 和后续计算均通过异步依赖；不在 native action 中等待 future。Vanilla mailbox 的 future 会在 action 内完成，因此后续计算仍通过 worker executor 转移到锁外。原有 C2ME gate、positionedExecutor、区域锁适配器和 startup pump 没有改成普通 tick 等待。

已重新读取本机实际 0.4.0-alpha.0.27 解包反编译的 `VanillaWorldGenerationDelegate`：它在 deferred subscription 中进入 `runTaskWithLockRadius`，后者通过 `runTaskWithLockArea` 排入 WORLDGEN 锁任务。本次没有用 `D:\辅助源码\c2me` 的旧版本冒充 ABI，也没有声称新生产世界的 C2ME 接线已运行验证。

`RealizationPlanner.resolve(Input)` 保留为未认证的局部几何诊断入口，供原反例和既有纯测试使用；生产 pipeline **只调用** `resolve(Input,List<GroundExitDecision>)`。后者拒绝 recipe、维度、hash 或覆盖区间不匹配的证书，不会发布未认证可走区间。

## 4. T0 完整性和碰撞

- 完整构件需要的 T0 列缺失时报 T0_MISSING，不能把缓存缺页当“这个出口物理不可行”。纯模板直接消费不完整 supports 时仍可返回空；生产决议层在进入模板前先验证 T0。
- 维护影响来自本轮完整 T0 接受的站点，不再来自全部未被计划抑制的站点。
- cap 接受前避开已接受的整件站点与水门足印。不能让某个 owner 后来才发现必需门洞与 cap 冲突。
- 收头的四种马道朝向逐一编译，并检查主体到马道的连接；正向马道的连接平台仍在原约定的 60×30 包络内。最终按完整体素对站点链投影的占用区间裁切，不套固定30格的错误假设。
- `EndCap.compileAt(ComponentInput, Predicate<ComponentVoxels>)` 在同一退让S内逐一筛选四个朝向；完整模板通过且区间/障碍判定通过，才接受。不能因第一朝向越界就漏掉本S的其余朝向。认证重放的predicate匹配证书componentHash，确保恢复同一完整组件。
- 敌楼与FortPlaceholder的地面入口已接自然地面；维持11×11/48×48足印和原W。该修订增加了非零drop时可证明的实际出口，不把原高台假认作自然出口。
- 最终撤回同步移除所涉普通站点和动态 WATER_GATE，不留下被截断区间里的独立可走水门。构件 ordinal 不重编。

## 5. 耐久性和玩家保护

证据在原 `ChunkEvidenceStore` 分区下单独保存，不是可重建站点缓存：

```text
<dimension hash>/ground-exits/<recipe hash>/<from>_<to>.bin.intent
<dimension hash>/ground-exits/<recipe hash>/<from>_<to>.bin
<dimension hash>/ground-exits/<recipe hash>/<from>_<to>.bin.sealed
<dimension hash>/<owner x>_<owner z>/build-exits.bin
```

每个 record 有限长、CRC32C、SHA-256、force 和原子写。顺序为意向、完整证书、封存标记。只有未封存的中断可以按完全相同的 intent 恢复；已封存后缺证书或缺 intent，明确失败，不重新猜边界。

`build-exits.bin` 把 owner 的 write-plan hash 绑定到全部证书的 Key 和 hash。每次持久化 owner proof 都重读封存证据，因此 RAM 命中不能绕过缺失、损坏或身份冲突。相同 Key 的不同 payload 禁止覆盖。

完整 marker 的重放只验证 owner T0/proof/exit proof 和 marker，不重新扫描当前方块为自然地形，也不重新 APPLY。原生测试在写后放置金块，关闭服务后按相反顺序重放，金块保留；没有撤销既有 owner 或回填旧存档。

## 6. 有界资源

- 查找与归约按单 cell 前进，不把从起点到远端的全部 T0 拼成一个超大窗口。
- 复用原有限单 cell coverage、每份 FEATURES 租约≤81、每次 realization 捕获 owner≤256 的限制。
- 查找状态机不为走过的每个 cell 留下一条未完成 future 递归链。
- 并发决议等待者上限32；纯摘要/封存决议缓存共用字节计数，上限 `min(summaryBytes/2,4MiB)`，每个表最多256条。它们不持有 TerrainTile、ChunkAccess 或组件体素。
- 关闭、取消视图、拒绝 executor、缺页失败都有定向测试；pipeline 继续使用原 terrainBytes/compiledBytes 计量与 fail-closed 策略。

这证明了跨 cell 查询不会无限 pin T0，不等于已经证明 Java 全堆的峰值硬上限。此前报告的全链路“分配前预留”和峰值对象占用压力验收仍须独立完成，不能把上述计数测试包装成该项 PASS。

## 7. 证据范围

已运行的定向测试包括：真实山脊反例、正/逆/随机和并发请求、冷热服务、远端证据延迟、取消隔离、8192-cell 稀疏查找、T0 hash 身份变化、完整 gate 足印冲突、拒绝 executor、关闭释放、磁盘冷读、损坏与缺失拒绝、原生两 owner 截断后冷重放。

原反例保留的是所有局部马道候选确实不可行，以及没有共同决议时的旧局部诊断失败；要求“回退成功”的验收现在经过真正的异步决议链转绿，并继续断言相同的最终截断范围。没有改成期望异常、禁用测试或允许悬空。

真实 CPU/Tectonic/C2ME 世界生成、人工实走、全部构件视觉图册、24小时堆压力不是本协议测试的覆盖范围。S0 百轮矩阵也只证明保留的实验屏障回归，不冒充 S1C 的真实墙生产验证。
