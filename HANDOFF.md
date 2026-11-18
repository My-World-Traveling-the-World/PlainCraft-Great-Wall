# 长城启动契约开发交接

## 2026-11-18：第38轮，长城规划调度拆分

### 一、改了什么

`dd777c46`（行为等价）将 `PlannerScheduler` 拆为三个协作类：`FineSampleStage` 负责截面切片、证据汇总和缓存；`BulkPrewarmStage` 负责批量结案、计划租约与编译通知；`PlannerPump` 处理执行器异常与启动门归属。沿用原推进器、地形通道、派发预算、预热范围和公开入口。四个生产文件统一为中文解释性注释。调度器由 1551 降至 953 有效行（含注释和空行 1072 行）；三新类分别 481、147、36 有效行。精采样调用日志使用真实固定切片数 48，诊断键保留。

### 二、验证了什么

开工 `f39e7a58` 干净，显式 pull 当前分支无更新。外置 JDK 21 / Gradle 9.4.1 执行 `test compileJava --offline --daemon --console=plain --dependency-verification=off`；终检 **802 项，零失败、错误或跳过**，`BUILD SUCCESSFUL`，日志 `build-round38-final.log`。现有精采样测试验证冻结边界较短时仍覆盖首尾；追加的调度断言通过真实调度器验证重复预热只通知一次、回调能拿到全部有序计划，编译后可释放租约。

| 生产变异 | 结果 |
|---|---|
| 成功预热时提前关闭段租约，不交给编译 | `prewarmedStartupStillServesNewPlansBeforeFirstTick` 断言红；正常编译、执行 JUnit 后失败，精确恢复，再全量回归。日志 `build-round38-mutant-unpin.log`。 |

道路共桥索引另在道路仓验证与交付，见道路第 57 轮。

### 三、我没能验证什么

未部署、未打开真实存档；未测开局耗时、游戏补建尾延迟、三模组实际注入顺序及大范围峰值。本轮中文化覆盖调度拆分的四个生产文件，未把全仓历史注释都重写。精采样原有提前结案政策仍在，本批未证明提前产物覆盖完整。

### 四、遗留半截的东西

本批拆分完整，保留通道、任务派发及租约活性在调度器；缓存 IO 与段界限检查仍在此类。构件校验和同输入邻接复用、有限全长及无限独立预热范围、正式模板、游戏跨 tick 施工均留待后续。未改配置键或存档格式。

### 五、下一轮先做什么

1. 先在编译窗口内共享不可变构件的校验；只有输入构件及覆盖一致时才复用邻接，按 owner 对拍计划、施工证明、哈希和跨区块连接。
2. 共用预热区间，统一结构扫描、精采样、批量计划和编译。在扩大范围前验证工作集和取消；继续保留进游戏补建。
3. 用存档副本记录单区块落地耗时，按尾延迟决定跨 tick 游标；同步整理下一批热点文件的中文注释。

### 六、踩到的坑

段计划结案信号与供道路使用的足迹就绪信号不是同一个。拆分保留这个区别，也保留回调前钉住租约的顺序。旧精采样日志按通道数估切片，和固定 48 的实现不同，已对齐。临时变异从暂存正确版本恢复，并核对工作区内容。

## 2026-11-18：第37轮，长城扫描与补建正确性、编译取证分桶

### 一、改了什么

- `603129b9`（行为变更）：结构扫描成功（含合法空列表）并安装索引后才打开批量规划门。扫描失败、取消、无效世界不再当成成功空列表；河流就绪和 `prepareLevels` 返回通知也走同一门。关闭使在跑和被移出队列的扫描 Future 结案，服务器线程丢弃已关闭会话的扫描回调。失败不发布本会话新计划，也不会把严格道路等待标记为就绪。
- `5637f553`（行为变更）：`DONE` 只提示核查，加载事件不直接结案。已有完成章与当前计划同哈希才保留玩家编辑；不同哈希交给施工。新发布携带实际哈希入队；已加载区块不会被旧 DONE 短路，未加载的旧 DONE 区块可以等自然加载。异步读盘从提交到服务器接收全过程去重，核对会话和存储；新发布使同区块在途读票据失效，即使新版本已被 LRU 淘汰。原计划和完成章持久格式未变。
- `ce472c52`（行为等价）：每个冻结单元的体素按区块一次分桶，替代每个 owner 全量扫描单元体素取施工前状态。保留原先遍历与先到证据优先的顺序，整个构件仍参与编译和邻接。新增 `indexedVoxels` 与 `previousVoxelChecks` 启动计数，可在真机比较工作量。

### 二、验证了什么

从 `377fa593` 干净工作区显式 `git pull --rebase origin refactor/planning-frozen-feature-paint`，无更新。JDK 21 + 外置 Gradle 9.4.1，`test compileJava --offline --daemon --console=plain --dependency-verification=off`；扫描批 795、补建批 801、终检 **802 项，零失败、错误或跳过**（`build-round37-final.log`）。分桶夹具逐区块对拍原扫描与新索引的证据和完整序列化计划，覆盖负坐标、重复证据优先级和跨区块围栏连接。道路独立运行 1661 项零跳过/中断/失败且 Fabric 编译成功，见道路第 56 轮交接。

| 故意破坏 | 变异结果 |
|---|---|
| 有部分结果就忽略扫描故障 | 红：故障结果不安装 |
| 生命周期通知绕开扫描门 | 红：生产接线 |
| 关闭时不取消在途/排队扫描 | 红：线程和队列 Future 结案 |
| 内存完成只认区块坐标 | 红：同坐标不同哈希 |
| 加载时 DONE 直接记录完工 | 红：生产接线 |
| 异步读取回调不检查原世界状态 | 红：生产接线 |
| 新发布不作废在途旧读票据 | 红：新计划被 LRU 淘汰后仍拒收旧结果 |
| 分桶施工证据用覆盖替代先到优先 | 红：接缝证据对拍 |

8 组变异均实际编译并触发 JUnit 失败；每组用 `git checkout -- <file>` 精确恢复后核对，最终重新全量构建。日志 `build-round37-mutant-*.log`。三个源码提交与本交接同步到当前分支（origin 为 E 盘备份）。

### 三、我没能验证什么

未部署；未在真实存档跑扫描故障、强制落地、光照/实体、玩家编辑重开或停服中途返回。会话与 DONE 路径有生产源码接线和组件行为验证，未加载完整 ServerLevel 执行负控。扫描关闭保证结果作废并通知等待方，底层没有强制立刻中断每个 Minecraft 结构任务。未测真机启动耗时、分桶峰值内存、单块长尾或三模组实际注入顺序。

### 四、遗留半截的东西

已落实取证分桶；构件哈希校验、合并与邻接仍重复。缓存票据只保护同会话的在途读，持久写失败后新计划逐出缓存这一单独恢复问题未改。有限全长/无限独立预热范围、`PlannerScheduler` 拆分、模板正式接线和跨 tick 落块仍未实施。依旧开局规划并发布足迹、进游戏分批落地。

### 五、下一轮先做什么

用存档副本同时验证首次、同版重开和异版区块，记录分桶工作量和峰值；进一步共享不可变构件校验及相同输入集合的邻接，逐 owner 对拍 plan/proof/hash 后接入。扩大范围前统一结构扫描、精采样、编译的预热区间并校核峰值内存。施工切片按游戏内长尾实测定，保留同版完工保护和首次按已发布计划施工。

### 六、踩到的坑

扫描除了正常安装还有两个旧生命周期入口能释放批量规划；只改异常回调不够。旧计划读取不能仅查当前缓存是否有新值，LRU 逐出后会漏判，故持有在途读票据。Windows checkout 会换行；恢复时同时核对内容和原始行尾。分桶只给取证使用，不能提前裁掉相邻区块构件。

## 2026-11-18：第36轮，长城和道路剩余工作复核（仅文档）

### 一、改了什么

只更新交接。重新检查e323e7d8的生产路径，将规划扫描失败、补建完成证据排到性能工作之前。保留用户决定：规划绕行，首次施工按已发布计划落地，已完成的同版本计划保留玩家修改；仍开局规划发布、进游戏分批建造。

### 二、验证了什么

- `ProductionSession.java:507–524`：结构扫描Future失败时用空列表调用正常安装，`:536–566`会安装空障碍索引并开始预热。`CorridorStructureScanner.java:60–65`也会对关闭返回空结果。成功无结构、失败和关闭必须分开。强制施工使整次扫描失败后继续出计划的后果更大；已核调用链，未在存档注入故障。
- `WallBodyBackfill.java:492–500`：持久DONE提示命中时，`onChunkLoaded`只看`marker.complete()`就进`settled`，直接绕过`:780–790`按哈希判断的施工入口。`:435–443`的入队也先按区块`settled`/DONE短路。新计划覆盖同区块、旧完成章不匹配，源码可构造错误结案路径；未确认实际存档触发频率。
- `PlanningChunkCompiler.java:147–151,211–219`已有按cell复用结果，但仍按owner重扫构件；`ChunkPlanCompiler.java:42–50`还反复哈希、合并及解析连接，值得做产物逐项对拍。`GreatWallConfig.java:211–215`超10万格和无限仍只预热开头；`ProductionSession.java:424–440`结构扫描却用有限总长度。

### 三、我没能验证什么

本轮只读核对源码和历史日志，没改生产、没重跑构建、没注入变异、没部署或打开实际存档。789项和道路1659项来自上一轮。实体/光照、强制覆盖、同版重开、真实地形共享命中、启动耗时和MSPT仍未验。

### 四、遗留半截的东西

两处新发现均未修。保留原台账格式，把DONE当查找提示，实际结案按当前计划哈希校验；晚到的磁盘旧计划不能覆盖新发布计划。规划扫描异常不可当有效空快照，处理失败时要让等待方收敛，但不能宣告长城成功可用。窗口编译、范围统一、调度器拆分和模板接线仍待做。

### 五、下一轮先做什么

1. 给两条正确性路径补行为回归和负控：故障扫描不发布无障碍计划，成功空扫描照常；同哈希跳过、不同哈希进入施工、缺计划等异步读取、卸载/重开不遗留旧证据。
2. 优化窗口编译：先计数，再一次校验构件、按相同输入集合复用邻接解析、按owner分桶；逐区块核对计划哈希、证明、重叠和跨区块楼梯/围栏。保留失败只影响相关owner的语义。
3. 用共享预热区间统一扫描、精采样和编译，实施有限全长/无限独立范围；先证明大范围工作集受控，不把区块施工加回启动。随后拆`PlannerScheduler`的精采样、任务租约和关闭职责；实际模板等待素材。
4. 在存档副本验证覆盖/实体/光照及重开，再按真实单块尾延迟决定跨tick游标。Top64只限制选候选，`:575–576`的150ms只在区块之间检查。

### 六、踩到的坑

不能只检查`settle`的同哈希早退，CHUNK_LOAD还有独立快路径；也不能把前台看到的空扫描与扫描异常混为一谈。两仓开工pull成功（道路重试一次后成功）；本轮只提交交接，不重报旧回归为新结果。

## 2026-11-18：第35轮第二步，计划施工与会话共享缓存

### 一、改了什么

行为变更：世界生成WallFeature与游戏补建WallBodyBackfill接入PublishedPlanPainter。规划已负责结构绕行，首次按发布plan落块，不按旧before token、漏检建筑/方块实体/基岩跳过；合法高度、owner和KEEP仍守。相同目标也进入已满足集合，实体可补齐；同hash已完工仍保留玩家后续编辑，显式repair可越过。部分失败不SETTLED、不DONE；成功写入才计数，元数据失败可重试，重试耗尽明确WARN但不冒充完成。已加载实体按计划创建并即时注册，只有成功满足的位置可装payload；重开与强制覆盖需现场验收。

性能/生命周期：每plan缓存BlockSpec→BlockState；冻结ProductionInputs显式持有SharedOracleFactory，每lane仍新建RandomState/NoiseChunk，共用有界SessionCellCache。不同单元锁外并行烘焙，同单元合并认领；失败释放，关闭清结果/唤醒等待，迟到owner不能发布。单独oracle仍有独立缓存并在关闭释放；删除静态SHARED及错误共享/容量注释。更新审计工具默认取c57d9c69历史实现，修复后仍可复现。

### 二、我验证了什么

前一批完整776项通过后提交150b5d6b。本批目标测试及完整test compileJava通过，XML789项，failures=0/errors=0/skipped=0（build-round35-combined-full.log）。新增13项：施工6项、缓存5项、实际生产接线2项。随后最终负控全部恢复，再次完整终检记录见收口条目。审计工具历史提取再次完整运行成功，未拿旧反例冒充现生产缺陷。

| 故意破坏 | 结果 |
|---|---|
| 施工失败强报complete=true | 行为断言红，最终版本复测亦红 |
| 跳过实际write | 行为断言红 |
| 必要落块失败也给finish传true | 行为断言红 |
| 补建无条件SETTLED | 静态生产接线红，最终版本复测亦红 |
| 去掉单计划材料缓存 | 调色板调用次数断言红 |
| 去掉完工玩家编辑保护 | 行为断言红 |
| 缓存不复用 | 行为断言红 |
| 取消容量淘汰 | 行为断言红 |
| close不清结果 | 行为断言红 |
| close不唤醒等待者 | 超时边界断言红，任务编译成功，非编译失败 |
| 生产退回独立oracle工厂 | 静态生产接线红 |
| 停服不关共享工厂 | 静态生产接线红 |

记录在道路.analysis_tmp/round54-mutations-{paint,cache,final}.json及对应日志；逐项git checkout --精确文件恢复并核对，含末次3组复测共14次负控，12种破坏。新增测试均在长城，未混入道路。第一次地基负控误绿及修正见前一节，不隐去该发现。

### 三、我没能验证什么

未部署、未测当前新世界/重开启动秒数、MSPT、实际峰值堆或缓存命中收益。真实NoiseChunk各lane共享结果与原独立计算逐列对拍尚未运行；CPUOracle生产构造/退出只有实际接线守卫，缓存并发行为有组件测试。未执行真实LevelChunk方块实体、照明、玩家修改和停服存档验收，不能把Fake/源码守卫叫真机。无整机倍数承诺。

### 四、遗留半截的东西

本批施工与缓存均已接生产，不是只有接口。窗口编译重复构件处理、有限全长/无限独立预热范围、PlannerScheduler拆分、单块按体素暂停仍未做。此前失败计划若已被旧DONE错误记录，本轮未批量清除/重写历史台账；需存档副本区分不完整marker与真实完工，不自动抹掉玩家修改。规划期编译保护政策未改，强制覆盖只作用于已发布体素。

### 五、下一轮先做什么

先在副本测首次/重开、漏检建筑覆盖、实体/光照与部分失败重试，记录真实烘焙数/缓存命中和单块尾延迟。离线可继续窗口编译一次校验/一次邻接解析再分owner桶，逐区块plan/proof/hash对拍后再替换；范围统一先评估扩大工作量。不重新强拉全部区块到启动期。

### 六、踩到的坑

同包generation.BlockSpec会遮蔽WallTypes.BlockSpec，已显式导入正确模型；不把编译错当负控红。工厂构造不能在全局锁内建RandomState，否则把lane初始化串行化。不能只setBlockEntityNbt给已加载块，否则运行中的实体可能继续用旧状态；新路径即时注册。并发夹具先确认waiter真的WAITING，再放owner，避免普通缓存命中假装并发认领。

终检收口：所有变异恢复后的test compileJava再次BUILD SUCCESSFUL（2m10s），XML合计789项、failures=0/errors=0/skipped=0，build-round35-restored-final.log。道路最终1659/0/0/0及Fabric成功已随d259cdf推送GitHub。长城改动生产文件有效代码均≤1000；本轮新增4个生产类，最大SessionCellCache约60行；旧PlannerScheduler有效1552行仍待拆。最终推送当前分支到既有E盘备份远端，停止前检查两仓status空及远端差异0/0；未部署游戏jar，无后台写入进程。

## 2026-11-18：第35轮第一步，二分和两个性能内核落地

### 一、改了什么

行为变更：负高度中点改为lo+((hi-lo)>>>1)，包内生产内核可注入密度直接验证。行为等价性能优化：地基表FoundationFloorSnapshot防御性复制一次、各Input复用，避免MapN聚集；回填稳定Top64替代全量排序，小队列仍稳定排序、无玩家保留原前缀。距离、预算、几何、配置和存档不变。

### 二、验证了什么

开工pull成功。初始构建命中缓存；修改后恢复所有变异，再完整test compileJava成功，XML合计776项、failures=0/errors=0/skipped=0，build-round35-final.log。道路同类修复独立1659全绿且Fabric通过。

| 故意破坏 | 结果 |
|---|---|
| 中点恢复无符号和 | 编译成功、断言红 |
| 堆选择返回空 | 编译成功、断言红 |
| 同距倒序 | 编译成功、断言红 |
| Input恢复Map.copyOf | 初次绿，补实际表类型断言后重跑红 |
| 暴露地基可变表 | 编译成功、断言红 |

记录在道路.analysis_tmp/round54-mutations-wall.json及wall-*.log。逐项checkout精确恢复，最终完整回归在恢复之后执行。新增7项覆盖386个二分阈值、坐标量化/严格阈值、240组随机选择及并列、地基16384键/所有权/null/真实Input复用。

### 三、我没能验证什么

未部署、未测当前启动秒数/MSPT/堆，未做真实NoiseChunk高度/支撑对照。纯内核结果不承诺游戏提速。首次plan强制施工/可靠结案、会话缓存共享尚未在本提交实施。

### 四、遗留半截的东西

本批三项及守卫完整。后续施工端口/策略另提交，不混入本批；原静态采样缓存和窗口重复编译仍待改。

### 五、下一轮先做什么

继续首次施工plan优先与失败不DONE，双方入口共用可测内核；再处理会话共享采样。真实地基分布及队列规模仍需现场数据。

### 六、踩到的坑

独立密度测试须bootstrap注册表；并列夹具维度必须为namespace:path；Map.copyOf二次返回原对象会掩盖实现退化，已负控补强，不用宽松断言。禁止把这些夹具失败算作生产变异红。

## 2026-11-18：预热/补建深审与算法复现（分析交付，生产未改）

### 一、改了什么

新增[源码审查和实施路线](docs/STARTUP-OPTIMIZATION-20261118.md)及tools/audit-startup-algorithms.py。核实源码而非沿用旧文档：缓存按RandomState身份分lane且静态持有不释放；负数二分错误；地基地图MapN聚集；每owner重复编译；补建before格式不同、失败仍SETTLED。生产代码仍5ade8e9b。

用户最新要求明确为规划阶段绕结构，首次落块按已发布计划强制施工，漏检建筑也不跳过；已完成同hash的marker继续保护玩家后续修改。该策略尚未实现，不把“分析完”写成“已修”。保留启动规划发布、游戏内加载施工的分工。

### 二、验证了什么

开工显式pull当前分支成功、树干净。从11月5/7日原始压缩日志提取阶段证据，新建863.811秒、同配方重开159.042秒仅为历史数据；重叠fine阶段与多lane累计时间分别说明，未当当前提速。

指定JDK21运行独立工具成功：提取原二分384阈值95错，修正探针0错；提取缓存键16lane得到16表、不能共享、丢lane引用仍静态持有；Top64在80组视点及并列场景同序；JDK MapN合成建表测试见报告。工具不启动Minecraft，不是JUnit回归或生产守卫。本轮无生产变异，不能声称交付了变异守卫。

### 三、我没能验证什么

未构建、未部署、未重跑长城769项。未测当前世界预热时长/FPS/MSPT/堆、实际NoiseChunk修正产物、缓存释放、强制覆盖实体/光照、单块施工长尾；合成实验无整机速度承诺。同石材proof失败由格式契约推导，原始冲突日志是石头/泥土不同材，未混称同材真机复现。

### 四、遗留半截的东西

本轮只有完整工具和文档，无半写生产改动、无临时变异。已知问题均尚未修。有限全长/无限独立范围未实现，正式模板等待素材。道路另发现NoiseChunkHeightSampler:207同类二分错误，记录到道路交接，不将其测试放本仓。

### 五、下一轮先做什么

报告列出7项分步方案：首次施工/结案端口与策略、两仓二分、地基表、会话共享缓存、稳定Top64、窗口编译、范围与调度职责。先行为等价端口化，再单独行为变更提交；各仓负控与真实产物对照。保持生成能力、存档格式、依赖顺序和道路足迹发布。

### 六、踩到的坑

Map.copyOf输入已不可变通常直接返回，不能说每cell复制；已有cell缓存、斜向走廊过滤不能重报。nearestFirst实际距玩家平均中心，等价堆需保留该政策及同距次序。150ms不是单块硬限；setBlock失败仍加written与假SETTLED需一起修。扩大线程/静态缓存不能修共享与生命周期问题。

## 2026-11-18：道路第52轮关联初查与最新用户决定（只改文档）

### 一、改了什么

只更新交接，源码保持5ade8e9b。用户确认当前长城能正常预热和生成建筑，建筑模板做好后再正式接线。本轮没有改建筑几何、模板挂接、配置或存档。用户先提出进世界前全建好，随后回忆启动拉区块更慢并决定保留现有启动规划/发布、进游戏分批施工；后者为最新指示，不能继续按前一决定强拉全区块。

### 二、验证了什么

现有分支refactor/planning-frozen-feature-paint工作区干净，显式pull同名分支成功。亲读WorldServices启动managedBlock及END_SERVER_TICK、ProductionSession、WallBodyBackfill、GreatWallConfig和河流就绪桥。启动发布规划足迹后finish(true)，游戏内drain读取计划、请求区块并settle。间隔10tick、每轮最多4块/64检查/8并发请求，检查预算150ms；预算并非单块硬上限，nearestFirst全量排序在预算检查之前。启动段数目前有限≤10万全量、超长或无限4段。没有把旧README/S0注释当实际功能结论。

### 三、没能验证什么

本轮未跑长城构建或回归，769项只是上轮记录；未部署、未测新世界MSPT/FPS、区块加载或总耗时。源码注释记录历史启动8分钟只约25%且剩343块、游戏内456块约3分钟，未取得原始本轮日志，不当作当前实测或普遍最优证明。用户现场确认正常生成不等于本轮端到端验收。

### 四、遗留半截的东西

无半写源码。用户范围意向：有限长城按总长、无限另设预热范围；尚未实施，先评估大范围规划/采样/编译成本，不能据此恢复启动全量落块。正式模板待素材。回填已有完成记录和缓存，不能再把“补去重”泛称缺失功能。

### 五、下一轮先做什么

保持启动依赖顺序和已发布避让足迹，优先给最近候选选择建立稳定等序对拍，再比较有界选择与全排序；记录单块settle尾延迟后才调整游玩预算或拆工作单元。PlannerScheduler有效1552行应按采样驱动、租约准入、调度关闭分步等价拆分；旧异步写全局进度需先用A关闭/B开始/A迟到夹具复现并守卫。测试留本仓，不放道路。模板接线等待用户素材。

### 六、踩到的坑

旧注释“启动立即画”和实际延后分支相互矛盾，实际代码优先。150ms是每10tick一轮的检查预算，不是每tick固定成本，更不是绝对上限。河流就绪桥的API由道路API包承载，但提供方仍是河流mod；请求Minecraft区块不等于重启整轮河流预热。此次仅文档，不构建或部署。

## 2026-11-16：第34轮，订阅、严格就绪及重开隔离

### 一、改了什么

- **行为等价独立提交92720388**：RoadWeaverLifecycleBridge的公开register仍通知WorldServices，包内注入会话回调；GreatWallReadinessProvider默认仍读取WorldServices，包内注入等待/快照端口。依赖锁只补本地API开发件的testRuntimeClasspath坐标一条，其余锁记录保留。
- **行为变更**：严格世界就绪等待被clear唤醒后，必须核对还是同一只闩锁；旧世界退出不再冒充编译发布。clear同时清理阶段、子进度和阶段计时，不把上一档COMPILE数据送给新档。
- **行为变更**：RetainedRiverSignal把会话注册与早到信号重放放在同锁内；原构造器检查河流状态之后、SESSIONS入表之前存在漏通知窗口。SERVER_STARTING入表后attach，SERVER_STOPPING/STOPPED先detach，STOPPED清保留状态。保留重复通知，幂等仍由ProductionSession负责；不新增总调度器。
- 订阅回调异常保留隔离，并记录明确WARN。未改API签名、长城几何、配置键、存档schema、Mixin优先级和原生产会话的完成/失败判据。
- 新增24项测试，全部在长城仓库：10项契约订阅/查询、6项严格门、6项保留信号、2项静态接线。改动生产文件物理行数均小于1000，最大WorldServices858、PrewarmProgress783，新协作类32行；新增注释说明原因。

### 二、我验证了什么

开工01408e65干净，分支refactor/planning-frozen-feature-paint，无upstream；显式git pull --rebase origin refactor/planning-frozen-feature-paint确认无需更新。初始testRuntimeClasspath因本地重映射API坐标未锁而失败；补一条锁记录后基线745项，零失败/错误/跳过。第一步端口迁移后既有Prewarm系列和compileJava通过。

新测试先在旧逻辑上跑16项，两个目标明确失败：clearingOldWorldReleasesWaiterAsNotReady、reopenedWorldClearsStageAndSubProgress。修复后目标通过；等待线程测试先确认进入TIMED_WAITING再clear，避免靠时序碰运气。保留信号还有128轮共同起跑线的并发交接，两个确定性先后顺序分别另测。测试使用真实RiverReadinessApi注册表和实际注册的GreatWallReadinessProvider，不构造ServerLevel，也不宣称已运行Mixin。

| 生产变异 | 目标测试 | 结果 |
|---|---|---|
| 订阅收到广播不回调长城 | riverBroadcastActuallyReachesWallSubscription | 红 |
| 回调异常逃逸到河流发布者 | wallCallbackFailureCannotEscapeIntoRiverPublisher | 红 |
| 非阻塞就绪恒true | failedProbeNeverReportsReadyOrThrows | 红 |
| 毫秒等待错用纳秒 | timeoutAndPollUseTheirOriginalUnitsAndResults | 红 |
| 进度签名带elapsedMillis | signatureTracksNineProgressFieldsButNeverWallClock | 红 |
| 旧闩锁clear唤醒当成功 | clearingOldWorldReleasesWaiterAsNotReady | 红 |
| clear保留旧阶段 | reopenedWorldClearsStageAndSubProgress | 红 |
| clear保留旧分阶段子进度 | reopenedWorldClearsStageAndSubProgress | 红 |
| 失败也释放worldgen门 | segmentCompletionAndFailureBothKeepStrictGateClosed | 红 |
| 晚会话不重放早到信号 | riversBeforeSessionAreReplayed | 红 |
| 发布不保留ready | riversBeforeSessionAreReplayed | 红 |
| detach失效 | stoppedSessionIsNotCalledAndReopenDoesNotInheritReadiness | 红 |
| clear不清会话表 | clearingWithoutPriorDetachDropsOldCallbacks | 红 |
| 生产启动不attach | worldLifecycleAttachesAfterRegistrationAndDetachesBeforeClose | 红，静态接线 |
| 生产出口改用非严格门 | registeredProviderUsesStrictWallGateAndIncludesShowcase | 红 |
| 生产出口忽略盖章等待 | registeredProviderUsesStrictWallGateAndIncludesShowcase | 红 |

16组全部编译成功且指定JUnit目标实际失败才计红；最后一轮JSON为build/round34-mutations.json，日志build/round34-mutant-*。每组断言唯一锚点，finally git checkout -- 精确文件从本轮暂存索引恢复，核对内容并恢复原行尾字节。没有把编译失败算红。道路本轮完整双模块回归PASS tests=1222 skipped=0 aborted=0 failed=0，Fabric编译成功，日志在道路.analysis_tmp/round34-road-final.log。长城完整终检见本节末尾记录。

构建使用JDK21、外置Gradle9.4.1，test compileJava --offline --daemon --console=plain --dependency-verification=off，本机GRADLE_OPTS=-Xmx2G -Dfile.encoding=GBK，命令参数-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=GBK。长城已有ASCII manifest classpath桥接，源码编译仍UTF-8。

### 三、我没能验证什么（最重要）

- **需要真机**：新建、重开、无长城/无河流组合，三模组最终Mixin注入时序、真实停服及跨档晚回调；未部署，未声称减少FPS/MSPT/加载时长。
- **真实服务端生命周期未跑**：注册/注销与保留信号的生产接线有静态守卫；组件有行为及并发测试，但没有构造完整ProductionSession，也未执行真实SERVER_STARTING/STOPPING事件。不能把组件重开测试叫真实存档验收。
- 保留信号的交接窗口以原代码先检查后入表的确定顺序确认；修复后的组件交接已测，未声称真机日志曾复现该竞态。
- 旧生产会话的异步任务仍可写全局PrewarmProgress；本轮注销河流会话通知和拒绝旧严格等待成功，不等于所有异步写入都带世界代次。若需要彻底解决，须给所有进度发布回调携带会话令牌，不能只在一个出口加判断。
- 没有改变失败后的恢复政策：长城失败保持门关闭；道路的超时结案后再次复核，不会当ready。河流API只有布尔就绪，没有失败传播或重试UI。
- 道路CPU精确采样三处parallelStream、粗路径两轮INITIAL子任务预算、大范围4096采样/FMM内存、READY收敛仍待做；crossing、双平滑及其他需真机裁决项保持原状。

### 四、遗留半截的东西

本轮订阅和就绪守卫、已复现缺陷修复完整；临时变异均恢复。对应Minecraft生命周期完整验收仍未做。源码小步提交，保持当前分支，不擅自改main；最终push及干净状态在结束记录中写实。

### 五、下一轮先做什么

三模组顺序需要真实运行条件；可继续的代码任务为道路CPU采样与粗路径内部子任务预算。先给执行边界注入端口，验总并发上限、前台暂停、取消、开局豁免及父子等待，再替换并行流和INITIAL旁路，避免再建竞争的总调度器。4096分配估算和按需工作集随后推进。

### 六、我踩到但已解决的坑

- 原分支无upstream，显式拉取同名远端分支，不切main。定向锁更新会移除未解析配置的旧归属，本轮从原锁记录只合入新增坐标。
- 负控发现一条假守卫：先detach再clear会掩盖“clear不清会话表”；已新增不先detach的独立测试，同一变异由全绿变红。旧测试未删除，仍守卫detach自身。
- Windows checkout可能把新文件LF转CRLF；恢复时先核对正规化内容，再还原原字节；第一次严格字节断言暴露该差异，修正脚本后完整重跑16组。
- 公开默认订阅注册测试只注册不广播，避免给WorldServices全局保留信号留下测试污染；实际通知用生产回调端口和真实API广播测试，默认接线另外静态守卫。

终检：长城完整test compileJava为769项（745+24），failures=0 errors=0 skipped=0，BUILD SUCCESSFUL；日志build-round34-full-final.log。16组变异全部还原。道路完整1222与Fabric编译通过，河流82cc818本轮只读且工作区干净。

同步记录：端口92720388、修复及守卫928d3302已push到现有origin的refactor/planning-frozen-feature-paint分支（E盘本地备份远端），未切换或推送main。源码提交后git status --porcelain为空；本条文档另提交后再push。
