# 长城启动契约开发交接

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
