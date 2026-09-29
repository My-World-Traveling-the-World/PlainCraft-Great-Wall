# plaincraft-great-wall

Fabric 1.21.1 万里长城模组工程。**S0-01、S0-02、S0-03 三个阻塞工作单已通过。** 不生成正式城墙，不修改用户既有存档。实验放块仅在显式授权的隔离 S0 世界启用。

开工上下文为 `开工交接.md` 和相邻的 `plaincraft-great-wall-终审与实施方案` 五册。验收依据见 [S0-01 运行签收](docs/S0-01-runtime.md)、[S0-02 实施记录](docs/S0-02.md)、[S0-03 屏障签收](docs/S0-03.md)。早期失败和修复日志保留，不用旧记录覆盖新签收。

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
.\gradlew.bat --offline --dependency-verification=strict --no-configuration-cache build
```

**方案 A 后严格离线构建已通过，正式构建无需且不得关闭依赖校验。** S0-03 最终强制构建为 26 个任务全部执行、22 项 JUnit 与 1 项服务端 GameTest 通过，见该阶段报告。没有临时代理设置；各阶段 mod 自身版本可递增，依赖版本不变。

不要再次调用初次开工的 `writeLocalVerification` 临时取证任务覆盖现有原生清单；该历史任务不属于正式构建流程。

`build` 经 Loom 测试配置包含 `runGameTest`。独立入口为 `test`、`runGameTest`、`runClientSmoke`、`runServerSmoke`。原版客户端与整包+OpenCL 客户端均已在隔离目录启动到标题界面、截图并正常退出，没有打开用户存档。整包游戏运行使用本机已存在且其 metadata 要求的 Java25；Gradle/Javac 仍为 Java21。菜单加载不等于 GPU 世界生成验证。

## 目录

| 路径 | 职责 |
| --- | --- |
| `src/main` | 公共入口、版本快照、opt-in 的 S0 假规划器、启动门、配方守卫、PREPARE/CAPTURE/APPLY 和 T0 存证原型；正常游戏不施工 |
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

公共资源的 `pack.mcmeta` 以资源格式 34 声明并覆盖格式范围 34..48，使同一模组容器也能作为数据格式 48 的数据包读取；两种格式常量及元数据有测试。当前没有世界生成资源、模板或旧世界回填逻辑。

## 下一步

本次停在 S0：S0-02 的 28 项运行矩阵、S0-03 的 800 轮固定并发和 100 轮原始 QoS 对照、真实进程恢复/故障均有独立证据。正式规划器、PrefixSummaryIndex、城墙本体、道路/FTB 集成尚未实现，不把原型误称可玩发布。

后续可按方案开始 S1。S0 的超平坦屏障实验不等于 OpenCL 实际世界生成、高度6000、第三方后置施工或长期性能已认证；本轮阶段耗时有超过40ms的尾部样本，详见 S0-03 报告，不签收 S5 性能门槛。
