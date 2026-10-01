# fort0修订实施

日期2026-10-01。用户批准两项修订；基线89c9fdc。
状态IMPLEMENTING，不是签收。production OFF，Tan's BLOCKED。

## 实现前冻结的依赖图

```text
creation授权 + 实际spawn + prepareLevels已进入 + 独立lane ready
  -> 原144候选自然粗采样/原确定性排序
  -> 锁外STRUCTURE_REFERENCES租约（完整潜在旋转fort足印）
  -> 读取不可变引用清单 -> 锁外补STRUCTURE_STARTS租约（实际被引用starts）
  -> 从已准备chunk提取注册ID及原生bbox/piece -> 不可变结构投票
  -> 剔除冲突候选/朝向 -> 原最多8候选精查/五朝向/原根评分
  -> world.json force -> recipe.ready
  -> 原规划future -> FEATURES -> CAPTURE -> 耐久T0 -> APPLY -> LIGHT
```

前置结构检查不请求FEATURES/LIGHT/FULL，不订阅recipe.ready，不使用生产barrier；
原STRUCTURE_STARTS/REFERENCES阶段不受墙FEATURES门控，保持原vanilla/C2ME任务调度。
有向图无反向边；不授权worldgen同步getChunk/join/get。

仍是800/1000/1250/1500四半径×36角度、原coarse排序和最多8精查预算。
在截取8名之前过滤已批准的结构冲突，不能过滤后继续只困在原冲突的8名里。
每个候选五朝向逐一使用完整48×48 fort及一格外接入口guard的旋转包络，
不按中心点或reference start chunk地址代替实际相交。
所有候选/朝向被否决则ROOT_NOT_FOUND；结构引用未知否决候选，不假称无结构。

地表判据在本前筛中使用自然REFERENCE候选地面脚面及原生结构bbox：
只把与该候选自然地表层[h-1,h+1)相交的注册结构作为地表排除，
不按generation step/类名/固定绝对海拔猜分类。
前筛是保守检查，不替代最终FEATURES/T0保护；最终真实盒/BE/before-state仍可拒绝。
自然候选地高与装饰后结构/地形可能不同，不能声称此预筛认证了任意第三方后写行为。
所有投票记录结构registry ID/实际盒、候选及朝向、原因，未知引用不放行。

## 分类分离

新增显式自然可替换资源表：原版16种主世界矿石、下界金矿石/石英矿石、
本次实测gravel；不是通配或任意mod块。
此表仅影响protectedBlock：显式protected/BE/mod未知块/lava仍优先。
foundation_support文件和support判定不变，矿石support=false，gravel gravity=true/support=false。
OWNED_VOID/FLUID原负空间检查不改，不能据此凿矿石或砾石。
新分类和根筛选策略进入新世界指纹，旧manifest/T0不删除、不迁移。

## 验证范围

实施后先跑纯候选/投票/异步held-screen回归及native结构/分类反例，
再新隔离vanilla seed17（诊断100，不偷称1200签收）。
目标真实progress.voxels>0，必须原APPLY/owner marker/durable proof共同证明；
未出现前不记PASS，失败完整归档。

## 独立缺陷

S1C-DRAIN-01：fort fatal后watchdog、STOPPING flights/admitted=8。
来源证据见S1C-fort0保护来源诊断.md及两份crash report。
本工作单不以它阻塞两项获批修订，也不称“正常关闭/完全排空已通过”。
