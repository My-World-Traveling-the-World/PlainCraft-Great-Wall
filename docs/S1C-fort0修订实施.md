# fort0修订实施

日期2026-10-01。用户批准两项修订；基线89c9fdc。
状态BLOCKED_AFTER_APPROVED_REVISIONS，不是签收。production OFF，Tan's BLOCKED。

本批两项批准修订已完成并经固定 seed17 运行验证到新的阻塞点；详见
`docs/S1C-fort0修订结果.md`。结构筛选和分类分离没有放宽保护层，
但入口仍被 T0 中的苔石/浆果丛等未获授权的自然装饰保护，故没有 voxels>0
或 APPLY 证书。S2-S5 不在此状态下推进。

## 实现前冻结的依赖图

```text
creation授权 + 实际spawn + prepareLevels已进入 + 独立lane ready
  -> 原144候选自然粗采样/原确定性排序
  -> 锁外STRUCTURE_REFERENCES租约（完整潜在旋转fort足印）
  -> 读取不可变引用清单 -> 锁外补STRUCTURE_STARTS租约（实际被引用starts）
  -> 从已准备chunk提取注册ID及原生bbox/piece -> 不可变结构投票
  -> 剔除冲突候选/朝向 -> 每批最多8候选精查/五朝向/原根评分
  -> world.json force -> recipe.ready
  -> 原规划future -> FEATURES -> CAPTURE -> 耐久T0 -> APPLY -> LIGHT
```

前置结构检查不请求FEATURES/LIGHT/FULL，不订阅recipe.ready，不使用生产barrier；
原STRUCTURE_STARTS/REFERENCES阶段不受墙FEATURES门控，保持原vanilla/C2ME任务调度。
有向图无反向边；不授权worldgen同步getChunk/join/get。

仍是800/1000/1250/1500四半径×36角度、原coarse排序、每批最多8精查槽和150000列预算。
在截取8名之前过滤已批准的结构冲突，不能过滤后继续只困在原冲突的8名里。
每个候选五朝向使用覆盖完整48×48 fort、所有初始曲线朝向及入口guard的±36列包络，
因为实际首段正切不一定等于投影轴；只检查轴朝向的单个旋转盒不够。
该读域排除包络不改变fort写集/尺寸；360朝向的纯栅格反例测试验证覆盖。
不按中心点或reference start chunk地址代替实际相交。
所有候选/朝向被否决则ROOT_NOT_FOUND；结构引用未知否决候选，不假称无结构。

首轮真实运行06fc94b已拒绝旧村庄root，但首批8个清晰结构候选都没通过承重。
这是候选消费的实现缺口：不能还有本域已筛出的候选却提前称“全部候选失败”。
修正为当前批全部承重/轴失败时按原排序取下一批≤8，列预算硬限不增、
不新增环带点、不放宽三层/drop/坡度。若任一批产生合法轴，按原该批评分归约；
耗尽候选或达到原预算明确失败。无结构筛选的旧纯fixture入口仍保留原首批语义。
该变化公开记录，不将“8个驻留精查槽”解释成可以跳过剩余已授权候选。

另核实根点承重端口错位：旧supportEvidence是最高固体之后额外读取的三格，
实际为[h-2,h-3,h-4]，与SurfaceProbe的[h-1,h-2,h-3]不相同。
保留旧supportEvidence/REFERENCE的完整采样顺序和ColumnSample语义，新增
foundationEvidence返回最高固体及其下两层，只修改生产根点回调消费此精确视图。
仍三层天然support，每层矿石/砾石都不支持；不删第四格原生读取，也不靠矿石授权消红。
OracleGameTests独立nativeColumn同时检查旧额外读取和新承重三格，不能改旧对照断言。

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
