# plaincraft-great-wall

Fabric 1.21.1 万里长城模组工程。当前是 **S0-01 基建原型，尚未验收通过**，不生成城墙，不修改既有存档。

开工上下文为 `开工交接.md` 和相邻的 `plaincraft-great-wall-终审与实施方案` 五册。当前进度与阻塞详见 [S0-01 实施记录](docs/S0-01.md)。

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
.\gradlew.bat --offline --no-configuration-cache build
```

**方案 A 后严格离线构建已通过，正式构建无需且不得关闭依赖校验。** 另以 `--dependency-verification=strict --rerun-tasks --no-build-cache` 强制离线执行，18 个任务全部执行、3 项 JUnit 与 1 项服务端 GameTest 通过。临时代理设置已移除，`gradle.properties` 与修复前逐字节一致。

不要再次调用初次开工的 `writeLocalVerification` 临时取证任务覆盖现有原生清单；该历史任务不属于正式构建流程。

`build` 经 Loom 测试配置包含 `runGameTest`。独立入口为 `test`、`runGameTest`、`runClientSmoke`。客户端 smoke 仅在隔离目录启动至标题界面、截图并退出，不打开用户存档；当前尚未运行到该界面。

## 目录

| 路径 | 职责 |
| --- | --- |
| `src/main` | 公共入口、构建常量、只读运行版本快照；没有生成挂钩 |
| `src/client` | 独立客户端入口；没有客户端类引用进入公共代码 |
| `src/test` | JVM/JUnit 构建合同测试 |
| `src/gametest` | 独立服务端测试模组，不进入发布 JAR |
| `src/clientSmoke` | 独立客户端 smoke 模组，不进入发布 JAR |
| `tools/Collect-Environment.ps1` | 只读实际实例的 JAR 元数据及 SHA-256，报告写回本工程 |
| `docs/evidence/s0-01` | 版本清单、真实执行日志、测试结果 |

Windows 中文工程路径的 JUnit 启动问题通过 ASCII manifest classpath 桥解决；桥接 JAR 是 Gradle 用户缓存内的构建临时产物，源码仍在本工程，不改变系统代码页。

公共资源的 `pack.mcmeta` 以资源格式 34 声明并覆盖格式范围 34..48，使同一模组容器也能作为数据格式 48 的数据包读取；两种格式常量及元数据有测试。当前没有世界生成资源、模板或旧世界回填逻辑。

## 下一步

S0-01 的严格离线校验缺口已关闭；客户端资源与运行矩阵仍待完成，然后依次做 S0-02、S0-03。没有任何一项可用“已经编译”替代真实运行验收。
