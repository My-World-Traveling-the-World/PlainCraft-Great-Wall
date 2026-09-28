# S0-01 运行验收与签收

状态：**已通过本次用户指定的 S0-01 收尾验收，可以开始 S0-02。**

日志中的时间为本机实际记录。这里仅签收骨架构建和加载，不签 GPU 数值一致性、生成挂钩、T0 或完整玩法。

## 1. 文件改动

- `tools/Reuse-LocalAssets.ps1`：本地完整资源校验及 Loom 缓存复用；无下载代码。
- `tools/Stage-SmokeProfile.ps1`：以 JAR 内 metadata 选择本机 Tectonic/Lithostitched/C2ME 或原样整包，复制到项目 `build/run/serverSmoke`；逐 JAR SHA-256 比对并保存输入清单。不会复制 saves 或写真实实例。
- `gradle/smoke-tests.gradle`、`build.gradle`：普通 DedicatedServer smoke，独立测试 JAR、生产命名空间启动任务。生产启动直接复用本地 Minecraft/Loader/libraries，未使用可能自行下载的安装器。
- `src/serverSmoke/`：测试模组，校验实际 DedicatedServer 和 profile 必需 mod；启动后运行 40 ticks，正常 halt，SERVER_STOPPED 写 PASS。不进入发布 JAR。
- `docs/evidence/s0-01/runtime/`：本地资产结果、矩阵输入 SHA、完整输出、正常退出标记、两张实际截图。
- 首次初始化项目内 Git，基线提交 `60c34be` 保存本轮之前的已有文件；不改相邻工程。

## 2. 环境与边界

- Gradle/Javac/Java release：21。Loom 1.16.3、Gradle 9.4.1、Loader 0.19.3、API 0.116.14+1.21.1、JUnit 5.11.0 保持原锁。
- 全部 Gradle 调用均 `--offline --dependency-verification=strict`，无再次联网补依赖。
- OpenCL 整包的**游戏进程**使用本机已有 `C:\Program Files\Java\jdk-25.0.3`；不改变构建 JAVA_HOME 或生产编译目标。
- 这是实际 JAR 的要求，不是主动升级：OpenCL、显卡选择补丁、native-math 均声明 `java>=25`。Java 21 失败日志已保存。
- 客户端资源实际完整源为共享 `.minecraft/assets`，不是缺少 `17.json` 的版本专属 assets。
- 3911 个索引项逐项长度/SHA-1 通过，3888 个唯一 object 已在 Loom 缓存。复制正确内容的索引为 `indexes/1.21.1-17.json`。未下载任何 Minecraft assets。
- 三个普通服务器均创建全新隔离世界，seed=9282026，只绑定 127.0.0.1、动态端口。watchdog 仍 60000；没有连接玩家。
- Tectonic 单独矩阵采用该 JAR 的默认配置（实际 -64..320），不是宣称验证了高度6000。CPU 矩阵复制实际 C2ME 配置，但没有安装 OpenCL 模块。
- 整包从真实 mods 复制 521 个 JAR，另一个相同 SHA 的 Fabric API 由启动任务提供；复制了 9320 个配置/数据文件。客户端仅到主菜单，不打开任何存档。没有复制/降低资源分辨率来通过测试。
- 整包自己的 AutoTune 会按隔离运行环境处理副本配置，原始输入 SHA 和日志均保留；没有声称副本运行后所有配置仍逐字节不变。

## 3. 实测结果

| 项目 | 结果 | 原始证据 |
| --- | --- | --- |
| B02 原版客户端 | **通过**，24s，正常 Stopping、退出码0 | `client-smoke-gradle.txt`、`client-smoke.png`、`client-smoke-passed.txt` |
| 普通 DedicatedServer / vanilla 矩阵 | **通过**，25s，841 loaded chunks、40 ticks、全部维度保存 | `server-vanilla-02.txt`、`server-vanilla-pass.txt` |
| Tectonic 3.0.26 + Lithostitched 1.8.0+beta6 | **通过**，30s，新世界、40 ticks、正常保存退出 | `server-tectonic-01.txt`、`server-tectonic-pass.txt` |
| Tectonic + 本机 patched C2ME CPU | **通过**，35s，DFC 实际绑定 Tectonic；c2me-worker 生成出生区块，正常卸载/保存 | `server-cpu-01.txt`、`server-cpu-pass.txt` |
| 整包+OpenCL 客户端生产加载 | **通过**，2m3s，OpenCL Mixin 初始化、资源重载、主菜单截图、正常停止 | `client-pack-01-production-java25.txt`、`client-pack.png`、`client-pack-pass.txt` |
| 最终严格离线强制构建 | **通过**，31s，26/26 tasks executed，无构建缓存 | `final-offline-build.txt` |
| JUnit / Fabric GameTest | **通过**，3/3 和 1/1 | 最终 build 输出及 XML |

两张截图均实际打开检查：原版 Minecraft 主菜单，以及“云游四海”整包主菜单；没有用占位图片替代。

## 4. 失败尝试与未签收项

1. 初次 serverSmoke 的 Loom `runDir` 使用绝对 Windows 路径被重复拼接。改为项目相对路径后通过，没有改模组代码或依赖。
2. 整包 Java21：Loader 明确拒绝 `java>=25` 的三个模块。没有改 metadata、关闭校验或删除模块。
3. 整包 Java25 开发环境：Fabric RuntimeModRemapper 的 EnumExtensionClassVisitor 空指针。改用原始生产命名空间及已重映射测试 JAR 后越过此问题。没有升级 Loader。
4. 原样客户端整包 + Java25 的**额外专用服尝试失败**：mobility 的 BoatEntityMixin 无法在 SERVER 环境加载。该失败不属于“原版地形普通 DedicatedServer”成功结果，也不能被客户端通过掩盖。没有通过删除 mobility 假装整包专用服通过。挂在 Fabric 错误对话框的隔离进程已记录线程 dump 后定点终止；后续加 `fabric.noGui=true` 防无人值守等待错误框。
5. 本次整包矩阵验证客户端加载与退出；**尚未验证 OpenCL 设备执行、实际新世界生成、GPU/CPU 判决一致性和整包专用服兼容**。S0-02/03 不得引用主菜单截图证明这些事项。
6. `/reload` 的 WorldRecipeGuard 按用户最新顺序在 S0-02 实现，此处没有伪造资源指纹测试。

## 5. 可复跑命令

从工程根目录使用 PowerShell 7。每次普通服务器使用一个新的 run ID；脚本拒绝打开已有 smoke 世界。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\tools\Reuse-LocalAssets.ps1 -SourceAssets 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\assets'
.\gradlew.bat --offline runClientSmoke --dependency-verification=strict --no-daemon --console=plain
.\gradlew.bat --offline runServerSmoke -PsmokeProfile=vanilla -PsmokeRunId=vanilla-new --dependency-verification=strict --no-daemon --console=plain

$instance='D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING'
.\tools\Stage-SmokeProfile.ps1 -SourceInstance $instance -Profile tectonic -RunId tectonic-new
.\gradlew.bat --offline runServerSmoke -PsmokeProfile=tectonic -PsmokeRunId=tectonic-new --dependency-verification=strict --no-daemon --console=plain
.\tools\Stage-SmokeProfile.ps1 -SourceInstance $instance -Profile tectonic-c2me -RunId cpu-new
.\gradlew.bat --offline runServerSmoke -PsmokeProfile=tectonic-c2me -PsmokeRunId=cpu-new --dependency-verification=strict --no-daemon --console=plain

.\tools\Stage-SmokeProfile.ps1 -SourceInstance $instance -Profile pack-opencl -RunId pack-new
.\gradlew.bat --offline runClientSmokeProduction -PsmokeProfile=pack-opencl -PsmokeRunId=pack-new -PsmokeJavaVersion=25 --dependency-verification=strict --no-daemon --console=plain

.\gradlew.bat --offline build remapServerSmokeJar remapClientSmokeJar --dependency-verification=strict --rerun-tasks --no-build-cache --no-daemon --console=plain
```

## 6. 签收清单

- [x] B01 严格离线构建保留且本轮再复测。
- [x] B02 纯本地资产复用、客户端启动、截图、正常退出。
- [x] 普通 DedicatedServer 不是 GameTestServer；没有可选道路/FTB/C2ME 也能启动。
- [x] 用户指定四套**加载**矩阵有实际日志及明确运行侧/Java版本。
- [x] 原样专用服整包失败与客户端通过分别记录，未声称全兼容。
- [x] 不升级依赖，不修改真实 mods/saves，不实现城墙本体。
- [x] 未添加代理配置；校验仍 strict。
- [ ] WorldRecipeGuard 的 reload 验收：移交 S0-02，不在此勾选。
