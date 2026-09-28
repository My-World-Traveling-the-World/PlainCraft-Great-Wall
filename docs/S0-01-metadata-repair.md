# 方案 A：构建元数据补齐与严格离线复测

结论：**元数据已补齐；严格离线构建已通过。** 仅关闭 S0-01 的 B01，客户端 smoke、普通 DedicatedServer 和整包加载矩阵不在本次通过范围内。

## 1. 版本与授权范围

Loom 1.16.3、Loader 0.19.3、Fabric API 0.116.14+1.21.1、JUnit Jupiter 5.11.0 / Platform 1.11.0 均未变。

`build.gradle`、`settings.gradle`、`gradle.lockfile` 与修复前 SHA-256 一致；临时代理撤销后，`gradle.properties` 也与修复前逐字节一致。没有修改或部署实际游戏实例的 mods，没有打开实际存档；构建中的 GameTest 仅使用本工程隔离测试目录。

## 2. 代理与联网阶段

- 确认 `verge-mihomo` 监听 `127.0.0.1:7897`。
- 经代理对 Fabric Maven 中 Loom 1.16.3 POM 发出 HEAD，返回 HTTP 200。
- 临时加入四项 HTTP/HTTPS 代理属性，Gradle init 探针确认 **Gradle JVM 实际读取了这些属性**。
- 备份原校验清单和版本锁，使用原生 SHA-256 校验清单生成命令，未使用 `--refresh-dependencies` 或 `--write-locks`。

联网命令的核心参数：

```text
gradlew.bat --dependency-verification=off --write-verification-metadata sha256 help
```

该阶段退出码 0，`BUILD SUCCESSFUL in 48s`。联网仅执行了这一个 Gradle 元数据修复调用；之后的正式验证均离线。

### 必须披露的额外下载

Loom 1.16.3 在原生校验清单生成模式下输出 `Resolving all libraries for dependency verification metadata generation`，自动解析了所有平台本机库和未使用的反编译工具。**因此本次实际下载过程不是纯元数据：额外下载了 23 个 JAR，超出了用户“只补元数据”的边界。**

观察到该输出后立即尝试停止本次 single-use Gradle daemon，但它已正常退出。没有再发起联网命令。依据联网前完整缓存文件基线及新增文件 SHA-256，只删除了本次新增的 21 个 LWJGL Linux/macOS 本机库 JAR、CFR 0.2.2 JAR、Vineflower 1.11.1 JAR；未删除旧文件或任何目录。

删除前逐个校验目标位于 Gradle `modules-2/files-2.1` 缓存、未出现在基线中、内容仍等于本次记录的 SHA-256。之后核实：**最终缓存新增文件只剩 POM / module，新增 JAR 为 0**。这是对额外下载副作用的撤销，不是声称其从未发生。

完整记录见 `evidence/s0-01/metadata-repair/cache-additions.csv` 和 `rolled-back-binary-additions.csv`。不要把该原生生成命令描述为保证“只下载 POM”；后续同类维护应先限制这一路径。

## 3. 最终保留的元数据

缓存实际新增 **87 个 `.pom`、22 个 `.module`**，每个文件的坐标和 SHA-256 见 `retained-metadata.csv`。代表内容：

| 类别 | 内容 |
| --- | --- |
| Loom | 1.16.3 插件 marker POM、实现 POM / module |
| Loader | 0.19.3 POM |
| Fabric API | 0.116.14+1.21.1 汇总 POM、固定版本子模块 POM |
| JUnit | Jupiter 5.11.0、BOM 5.11.0、Platform 1.11.0 的 POM / module |
| 构建传递依赖 | ASM 9.9、Stitch、Tiny Remapper、Mapping IO、Kotlin metadata 等当前解析版本的元数据 |

这不表示只存在上述直接依赖；父 POM、BOM 和构建工具的固定传递依赖也属于所需元数据闭包。

## 4. 校验清单

最终 `gradle/verification-metadata.xml`：

- **266 个 component**。
- `verify-metadata=true`，对使用的 POM / module 做 SHA-256 校验。
- `verify-signatures=false`，本次没有引入 PGP 签名认证，不把 SHA-256 误称为签名。
- 包含 Loom 插件 marker POM 和 `net.fabricmc:fabric-loom:1.16.3`。
- Loom 实现包含 `fabric-loom-1.16.3.jar`、`.module`、`.pom` 三个校验条目。

Gradle 原生输出对 Loom 实现选择了 `.module`，未另列重定向用的 POM，因此将**实际从 Fabric Maven 下载到缓存的该 POM**计算 SHA-256 后补入清单，未修改任何依赖字节或版本。

Loom POM SHA-256：

```text
1517de6316a89404c62ea85ca4cbb14b9882f367c7130803176d00dc2bce7366
```

清单 SHA-256：

```text
c4e281095c96f26a421fc7d676a6d218f20f9739b40978b061863707f7edcee5
```

该校验清单是 HTTPS 获取/本地现有输入的哈希锁定记录，不是独立供应链安全审计。初次开工的 `writeLocalVerification` 历史取证任务未在本次运行，不能用它覆盖新的原生清单。

## 5. 正式严格离线复测

先移除全部四项临时代理属性，校验 `gradle.properties` 与备份逐字节一致，并设定 `JAVA_HOME=C:\Program Files\Java\jdk-21`。

第一次：

```text
gradlew.bat --offline build --dependency-verification=strict --no-daemon --console=plain
BUILD SUCCESSFUL in 24s
18 actionable tasks: 3 executed, 15 up-to-date
```

第二次为强制实际执行，未依赖构建缓存：

```text
gradlew.bat --offline build --dependency-verification=strict --rerun-tasks --no-build-cache --no-daemon --console=plain
All 1 required tests passed :)
BuildContractTest > commonMetadataSeparatesClientEntrypoint() PASSED
BuildContractTest > combinedModPackSupportsBothMinecraftPackFormats() PASSED
BuildContractTest > javaAndMinecraftTargetAreFrozen() PASSED
BUILD SUCCESSFUL in 30s
18 actionable tasks: 18 executed
```

两次退出码均为 0；没有关闭依赖校验，没有重新联网补缺项。编译、remap、打包、JUnit 3/3 和服务端 GameTest 1/1 均成功。

第二次仍有 Gradle 10 兼容性弃用提示，不影响锁定 Gradle 9.4.1 下的成功结果；没有为消除提示升级 Gradle 或 Loom。

## 6. 证据与未覆盖范围

`evidence/s0-01/metadata-repair/` 保存代理生效证明、原文件备份、联网日志、缓存增量及撤销清单、两次严格离线完整日志、强制复测 JUnit / GameTest XML、机器可读审计结果。

本次没有启动客户端、没有补客户端资产、没有做 Tectonic/C2ME/OpenCL 兼容运行、没有进入 S0-02 或 S0-03。**B01 已关闭不等于 S0-01 整项或阶段 0 已通过。**
