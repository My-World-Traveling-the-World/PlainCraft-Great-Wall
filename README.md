# plaincraft-great-wall

2026-11-20第85轮：批内取消已修，964项全绿、客户端编译/remapJar通过；4组变异红且恢复。中断不再认领剩余任务，已运行子任务退出后才释放共享缓存。持久发布见第84轮；窗口缓存归属仍推进。

2026-11-20第84轮：持久发布故障已修，958项全绿、客户端编译与remapJar通过；5组变异红且恢复。先落盘确认再缓存/补建，IO最多3次，非法载荷不重试。第83轮“写盘失败仍发布”是历史基线；批内取消及窗口状态继续推进。

Fabric 1.21.1 万里长城模组工程。新建正常世界使用配套城墙剖面与门楼、敌楼、烽火台、角楼、澄海楼实物模板；建筑固定正交朝向，城墙从真实接口接出再回到规划路线。超平坦提供实物套件展区。已有世界按其冻结配方继续，不自动替换旧墙或覆盖已完成区块的玩家修改。

当前进度、验证和未完成项以 [HANDOFF.md](HANDOFF.md) 为准；正式模板规则见 [正式模板生成](docs/正式模板生成.md)。`开工交接.md`、S0 至 S5 文档及相邻终审五册保留为阶段设计与历史证据，不能据其中早期“未实现”描述推断当前源码状态。

接线后独立深审的五项已进入正式生产：连续墙相位、高地整站拒绝、精确BVH投影、完整楼体有界复用、按区块批量发布道路保护，另修复异步排空竞态。第80—82轮完成复杂斜墙/折返与双建筑接口守卫、冻结v2计划写盘重开续建、准入租约/启动发布职责拆分、会话累计缓存观测与取消清理。当前 **953项全量零失败/零跳过**，本批长城14组新生产变异会红且恢复，test/compileClientJava/remapJar通过；[最终证据](docs/evidence/five-followups-complete-20261119.json)。历史[修复证据](docs/evidence/deep-review-fixes-20261119.json)与[原始审查](docs/REVIEW-20261119.md)分开保存，真机边界见最新HANDOFF。

第83轮只读复核新增两项**已复现、未修复**：计划写盘失败未计入发布失败，编译批次拥有者中断后仍运行后续工作。独立正式类探针见[新增证据](docs/evidence/code-followups-probes-20261119.json)，先修结果传播与批内取消，步骤见最新HANDOFF；不把第82轮全部通过写成这两个契约已经有守卫。

## 已锁版本

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Mappings | Mojang official mappings |
| Java toolchain / release | 21 |
| Gradle wrapper | 9.4.1 |
| Fabric Loom | 1.16.3 |
| Fabric Loader | 0.19.3，与实际整包一致 |
| Fabric API | 0.116.14+1.21.1，与实际整包一致 |
| JUnit Jupiter / Platform | 5.11.0 / 1.11.0 |

`gradle.lockfile` 固定解析结果；`gradle/verification-metadata.xml` 经方案 A 补齐，含 266 个 component，校验 JAR、POM 和模块元数据的 SHA-256（`verify-metadata=true`）。这不是上游 PGP 签名认证。严格离线构建已通过，详细过程见 [元数据修复报告](docs/S0-01-metadata-repair.md)。

## 构建与测试

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$gradle='C:/Users/18364/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle.bat'
& $gradle test compileJava compileClientJava remapJar --offline --daemon --console=plain --dependency-verification=off '-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=GBK'
```

本机开发回归使用已有外置 Gradle，避免包装器握手问题；必须保留 `--daemon`。当前测试数量见交接最新记录，不能用早期 S0 的 22 项代替。历史严格校验与依赖修复证据仍见对应阶段报告。

不要再次调用初次开工的 `writeLocalVerification` 临时取证任务覆盖现有原生清单；该历史任务不属于正式构建流程。

`build` 经 Loom 测试配置包含 `runGameTest`。独立入口为 `test`、`runGameTest`、`runClientSmoke`、`runServerSmoke`。原版客户端与整包+OpenCL 客户端均已在隔离目录启动到标题界面、截图并正常退出，没有打开用户存档。整包游戏运行使用本机已存在且其 metadata 要求的 Java25；Gradle/Javac 仍为 Java21。菜单加载不等于 GPU 世界生成验证。

## 目录

| 路径 | 职责 |
| --- | --- |
| `src/main` | 正式规划、地形采样、实物模板、预热与分批施工、玩法、存档及跨模组接口 |
| `src/client` | 独立客户端入口；没有客户端类引用进入公共代码 |
| `src/test` | JVM/JUnit 构建合同测试 |
| `src/gametest` | 独立服务端测试模组，不进入发布 JAR |
| `src/clientSmoke` | 独立客户端 smoke 模组，不进入发布 JAR |
| `src/serverSmoke` | DedicatedServer、reload、Chunky、远跳、邻 feature 竞态及测试专用 worker 校准，不进入发布 JAR |
| `tools/Collect-Environment.ps1` | 只读实际实例的 JAR 元数据及 SHA-256，报告写回本工程 |
| `docs/evidence/s0-01` | 版本清单、真实执行日志、测试结果 |
| `docs/evidence/s0-02` | 启动/失败/取消/重载矩阵、阶段事件、线程栈与实际 Mixin 类导出 |
| `docs/evidence/s0-03` | 八组百轮屏障矩阵、原始 QoS 对照、八项进程故障/恢复、跳过装饰拒绝、存证与类导出 |

Windows 中文工程路径的 JUnit 启动问题通过 ASCII manifest classpath 桥解决；桥接 JAR 是 Gradle 用户缓存内的构建临时产物，源码仍在本工程，不改变系统代码页。

公共资源的 `pack.mcmeta` 以资源格式 34 声明并覆盖格式范围 34..48。五件实物位于 `src/main/resources/data/plaincraft_great_wall/wall_templates/v2/`，配套墙位于 `assets/plaincraft-great-wall/templates/wall_module.csv`。已发布施工计划在区块生成或进入游戏后分批落块，持久完成标记保护后续玩家修改。

## 下一步

正常世界模板接线已进入生产默认；后续主要是整合包中长距离、复杂地形和跨模组的现场验收。范围、间距与兼容规则以正式模板文档为准；不能因离线通过就宣称整合包帧率、总预热时间或全部建筑现场效果已验收。

道路、河流和调度模组的联调、超高维度及长期性能结论需对应运行证据，历史阶段测试不能替代当前版本的现场检查。
