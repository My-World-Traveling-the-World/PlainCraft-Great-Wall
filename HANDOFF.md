# 长城启动契约开发交接

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
