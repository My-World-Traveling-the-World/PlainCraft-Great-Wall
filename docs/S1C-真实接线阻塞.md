# 1C-1 真实墙接线：阻塞取证

状态：**BLOCKED / 未签收；已测试真实输入在 APPLY 前拒绝，暂无真实墙通过证书。**
日期：2026-10-01。正式 `greatwall.production=true` 仍拒绝启用。
本报告不授予 S1C 生产接线，不将服务器启动、解析 GameTest 或 oracle 对照当作真实墙生成。

## 1. 已运行与未运行

共同命令前缀：JDK21、`--offline --dependency-verification=strict`；
入口 `D:\云游四海-Mod源码\plaincraft-great-wall\tools\Test-S1Terrain.ps1`。
每轮为新的双标记隔离目录，没有读取真实存档或部署到真实 mods。

| Profile / seed | 冻结源码 | 证据 runId 后缀 | 实际结果 |
|---|---|---|---|
| vanilla / 9282026 | `9aef2f8` | `ef116e9d` | 根点拒绝；exit1；无 recipe/APPLY |
| vanilla / 9282026 | `9c152cc` | `24c4cd96` | 同域诊断复核：三个足印通过，但十五个轴均含水；其余足印失败；exit1 |
| vanilla / 0 | `2b30d05` | `a08fef16` | 配方成功，原规划错误被 CLOSED 覆盖；exit1；原错不能事后追认 |
| vanilla / 0 | `592dbf9` | `35da6874` | 第0段 HYDROLOGY_UNRESOLVED；exit1；原堆栈保存 |
| vanilla / 1 | `e2bfa82` | `7d1c9560` | root 批次48.6001ms，超限1；exit1；不是初始化耗时 |
| vanilla / 1 | `098fe04` | `e7f2c3f6` | root21.3595ms、超限0；第0段水文认证仍失败；exit1 |
| Tectonic / 0 | `f04b793` | `ddbb1181` | 第0段 HYDROLOGY_UNRESOLVED；exit1；无APPLY |
| Tectonic+C2ME / 真实墙 | 无 | 无 | NOT_RUN；历史 oracle/init 对照不继承为墙生成 |

证据根：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\`。
每轮对应 `s1c-wall-<profile>-20261001-<suffix>\generated\`。
已结束轮次保留 `inputs.json`、`execution.json`、原始 stdout/stderr/latest/debug、
crash 报告、manifest、STOPPING 诊断和 SHA 清单；`.log` 已用显式 `git add -f` 入库。
Tectonic 轮次也已结束并取得exit1凭证，611.8172438秒、sourceInputsUnchanged=true、
cleanShutdown=false、无reopen。两份原始crash分别来自规划失败与后续watchdog。

## 2. vanilla / 1 的水文拒绝不是代码猜测

原件：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\s1c-wall-vanilla-20261001-e7f2c3f6\generated\world\greatwall\diagnostics\session-STOPPING.json`。
该文件 SHA-256：
`56f2eed23ed7c3557de77cfd0d21ad3fe8f92ea62e38155bcaafc464346da77f`。

【已验证】`plannerFailures.first` 保存：
`HYDROLOGY_UNRESOLVED`，phase=HYDROLOGY，segment=0，operations=11796184，
chargedColumns=8458，crossing Q8=[26172,43232)，水位 min=max=63，
waterMismatch=false，bestDirection=-1，allWide=false。
三个横断中心为 (-1066,-497)、(-1093,-509)、(-1119,-523)。
这只是参考噪声水文，不等同最终 T0；本轮尚未进入 CAPTURE/APPLY。

下表为原始三中心×十六方向诊断中的拒绝见证，不从地形外观猜宽度：

| 方向 | 阻止“同方向三个中心均有两岸”的见证 |
|---|---|
| 0..9 | 中心0的 left.bank=false |
| 10..13 | 中心1的 left.bank=false |
| 14..15 | 中心2的 left.bank=false |

另一个独立见证：方向10/中心0两岸认证成功，但 widthQ8=5847，
小于50×256=12800；所以“所有方向、所有中心都有≥50下界”的 WIDE 证明不成立。
路线湿段长度为17060/256=66.640625格；这不是横断通道宽度，不能据此入海终止。

【源码事实】冻结实施规格
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1-S5实施规格\S1-城墙本体.md:286`～294
及紧随的“方向离散导致无法认证的弯曲/碎岛水域明确失败”条款要求该分支可见失败。
实际代码
`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\HydrologyClassifier.java:186`
的 classify 在198行汇总两岸、200行否决allWide、217～220行抛原错误。
因此此具体拒绝与冻结合同一致；不是有依据的生产者权限/几何修复机会。

【未证明】未采集该水域完整二维状态图，也未在原生独立算法中重证所有横断采样。
不能把该域称为“实际必然是海洋”或“实际必然能用水门跨越”。
也不能将 vanilla/0 的旧缺诊断错误写成与本轮相同的具体横断根因。

## 3. 已闭合的实现缺口与证据边界

### 3.1 Tectonic / 0 的另一种真实拒绝

【已验证】原件
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\s1c-wall-tectonic-20261001-ddbb1181\generated\world\greatwall\diagnostics\session-STOPPING.json`
记录root最大17.5455ms、超限0；PlannerScheduler最大13.0712ms，无over40ms计数。
lane初始化134.2791/84.3689ms另列，Tectonic冷初始化内部分解仍未查明。
这是steady sampling的观测通过，不是实际墙或每chunk成本通过。

首错segment0，crossing Q8=[245999,264687)，chargedColumns=11762，
水位63且无失配，bestDirection=11，allWide=false。
方向11的三个中心均有两岸，但widthQ8分别13632/10295/12708：
共同最大宽度53.25格，不满足NARROW的<50条件。
方向4/中心0两岸宽度12309Q8<12800，又否决了“全部中心全部方向≥50”的WIDE证明。
按原合同必须UNRESOLVED；不能只取三个中心的最小宽度改为窄河，
也不能只取其最大值改为海洋。原路线湿span为73格，仍不能充当横断宽。

本轮没有进CAPTURE/APPLY，owner计数0、voxel0；不是“写墙后出现缺口”。
Tectonic与Lithostitched的JAR SHA和历史native oracle认证完全相同，未升级或关闭第三方。
尚未运行的Tectonic其他seed/C2ME不能据此宣称必失败，只保留NOT_RUN。

### 3.2 最小实现修复

- `PlannerScheduler` 保留首个异常的 phase/segment/code/原始堆栈，
  parent close 不再把已知失败改成无原因 CLOSED；同反例 before 红、after 绿。
- 水文拒绝新增有限诊断；没有改变判定、采样顺序、24000/150000预算或canonical字节。
- RootSearch 批次增加5ms目标窗口guard；仍计完整批次，单个45ms原语仍拒绝。
  21ms反例在重建旧边界下实际红；最终四套定向JUnit43/43、exit0、编译通过。
  原生上一轮48.6001ms缺少原语/GC/调度分解，原因未查明；不得把这个反例替它归因。
- 纠正先前口头/文档计时混写：12.4489ms属于PlannerScheduler，
  root原件为21.3595ms。原始凭证没有修改。
- 构建来源SHA进入recipe指纹；源码改变后只建新fixture，未在旧recipe上重采或续写。

## 4. 核心验收仍不满足

| 判据 | 当前结论 |
|---|---|
| 实际写出墙体 / 非零voxel | 未获得PASS证书；已结束代表运行均FAIL_BEFORE_APPLY |
| 同一世界退出/重进、owner/hash不漏 | NOT_RUN；失败轮次根本没有进入reopen |
| 每chunk APPLY、owner生产、完整FULL延迟 | NOT_RUN；root/规划batch耗时不能冒充每chunk成本 |
| C2ME真实施工锁前future、取消、耐久T0 | NOT_RUN；编译或早期spawn见证不能代替实际施工 |
| 失败后正常关服 / 完整producer drain | 未闭合；保存阶段再次遇fatal，watchdog结束，无STOPPED/clean marker |
| 1C-2同JVM世界A→B→A、1C-3完整植被写集 | NOT_RUN；不把已建前置类当已签收 |
| 正式生产接线 | OFF，不授权 |
| Tan's Huge Trees原样profile | BLOCKED，不删mod冒充通过 |
| 6000、非默认minY、Golden、人工实走、S5/24h | NOT_RUN |
| S0百轮 / S1B慢矩阵本批重跑 | NOT_RUN；本批无阶段签收，不调用旧PASS充数 |

## 5. 停止条件与下一步授权边界

当前停止真实运行：vanilla预置0/1/9282026都未取得真实墙证书，追加Tectonic/0也拒绝。
不继续无人授权的seed搜索，不更换地形、删水、跳过case或关闭保护。
下一步需交还事实并取得范围/语义决策：
额外固定代表seed的选择，或改变UNRESOLVED的生产政策，均须明确记录范围；
不能私自把水文错误改为空段、SUPPORT_GAP、WIDE或“无墙成功”。
此时不能宣称1C-1/S1C完成；所有未进入阶段保持NOT_RUN。

## 6. 本轮文件与最终离线检查

本轮修复/诊断生产文件（绝对路径）：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\planning\PlannerScheduler.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\ProductionSession.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\terrain\HydrologyClassifier.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\lifecycle\RootSearch.java`

测试文件：

- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\planning\PlannerSchedulerTest.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\terrain\HydrologyTest.java`
- `D:\云游四海-Mod源码\plaincraft-great-wall\src\test\java\local\plaincraft\greatwall\lifecycle\RootSearchTest.java`

报告文件：

- `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-1C-1.md`
- `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C.md`
- `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-真实接线阻塞.md`

原生接线此前各批文件及证据见1C-1报告§16～24；本轮没有重写已签收S1A/S1B构件，
没有修改依赖版本、永久历史、T0/owner权限或C2ME区域锁规则。

最终输入 `be43628`（生产源自`f04b793`未变），所有实际源码/Gradle/工具输入SHA相同：

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict test assemble compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon
```

【已验证】exit0，`BUILD SUCCESSFUL in 2m 5s`，
**332/332 JUnit、61套件、failures=errors=skipped=0**。
61份XML全为本轮新鲜文件，sourceInputsUnchanged=true。
证据绝对目录：
`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\real-wiring-blocker-20261001\final-unit-assemble\`。
含输入SHA、命令/退出凭证、完整stdout、原XML及artifact/archive审计。

本轮新打包为 `build/libs/plaincraft-great-wall-0.1.0-s0.3.jar`，
SHA256=`dfe146262610ccb7bb4974c500f3cb5e37b43428fe6302e4b5d63d7caabe6a42`，
class major=65（Java21）；sources Jar也为本轮新生成。
两个新Jar均无serverSmoke/GameTest目录条目。旧s0.1/s0.2 Jar只是目录库存，
已在审计中标freshThisRun=false，不能作为本轮产物。没有部署或发布。

【范围】这是全量纯JUnit+assemble+编译，不是完整build或全GameTest。
S0百轮、S1B慢矩阵、真实C2ME施工、真实owner峰值、真实墙APPLY/reopen/每chunk成本、
Golden/6000/人工实走/S5/24h本轮NOT_RUN。JUnit中解析fixture/资源预留压力的PASS
不替代真实生产内存或地形验收。
五轮本批真实失败归档共65个SHA清单条目全部匹配并被Git收录；
历史两轮9282026另已归档，本项审计不把它们混算成本批五轮。

## 7. 可复现的拒绝输入

以下命令会创建新的隔离世界并保留失败，不用于“重跑到绿”：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\tools\Test-S1Terrain.ps1 -Profiles vanilla -Seeds 1 -Reopen
.\tools\Test-S1Terrain.ps1 -Profiles tectonic -Seeds 0 -Reopen
```

实际已观察的拒绝分别见表中e7f2c3f6和ddbb1181；完整配置/来源/时间戳在原inputs.json。
性能时长不能预承诺复跑相同，未取得新执行凭证不得沿用旧exitCode。
调度与诊断修订提交：`592dbf9`、`e2bfa82`、`098fe04`、`f04b793`；
旧失败与新证据分开留存，不删除或静默改绿。
