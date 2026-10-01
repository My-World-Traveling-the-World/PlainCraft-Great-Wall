# S1C WIDE收头收尾

日期2026-10-01。状态：PAUSED_FOR_AUDIT，未签收。正式production OFF，Tan's BLOCKED。
最新状态和分叉以S1C-暂停审计交接.md为准；以下已完成的历史证据保留。

## 冻结新规则

已先检查用户留下的未提交diff，没有重新实现该生产变更：
HydrologyClassifier.classify的waterMismatch及最后else已改为WIDE；
HydrologyTest原水位矛盾测试已改为inconsistentWaterLevelTerminatesAsWide。
本批只在其上补回归证据；不实现旧延迟填平、深基础或施工测宽提案。

sample中的24000列耗尽、REFERENCE质量/河床错误仍HYDROLOGY_UNRESOLVED。
窄河原WaterGate、已认证宽河原terminal/EndCap、探测窗口/drop12/owner权限不改。
WIDE现在也表示用户授权的保守终止政策，不必然表示实测width>=50。
新反例保留实测40格、水位63/66差3格及无桥墩，不能伪造宽度凑50。

## 实际完成与证据

| 运行 | 实际结果 | 凭证 |
|---|---|---|
| 用户两处改动上的全量JUnit | 343/343，exit0，1m17s | docs/evidence/s1c/wide-closeout-20261001-151922/ |
| 补充后的Hydrology/PlannerScheduler/PrefixSummary/Realization四组+assemble | 41/41，exit0，34s | docs/evidence/s1c/wide-regression-20261001-152423/ |
| 完整build任务图核对 | 包含:runGameTest，慢矩阵未被自动排除 | 当前调用输出；此项不是build执行PASS |
| 完整build及全部GameTest | 已启动后主动中止exit1，NOT_COMPLETED | evidence/s1c/wide-full-build-20261001-153829/；追加结构要求后停止旧基线 |
| 新规则三profile真实生成/重进/每chunk成本 | NOT_RUN / 待执行 | 原五轮失败原件保留，不重写 |
| 客户端人工实走 | NOT_RUN | 程序化验证不能代替 |

隔离客户端输入准备已完成：prepareWallPreview exit0、14秒，未启动客户端。
证据docs/evidence/s1c/preview-package-20261001-153315/。
remapJar候选为build/libs/plaincraft-great-wall-0.1.0-s1c-preview.1.jar，
SHA256=`6308ef8a7e1f0c6bc68c62c2b9aa0f3f520075ff89c16e05f227cd7725f0a03d`。
本次ZIP检查326条目，未含gametest/smoke/RootTimingAnalyzer/WorkerCountAgent，
包含WallSection；这是限定名称范围检查，不冒称S5全部发布审计完成。
隔离mods仅有锁定FabricAPI与此候选Jar，没有部署真实实例。

343项全量运行发生在新增两个回归方法之前；41项是新增后所选四组，
不能把二者相加冒称384项新全量。
全量日志/XML及输入diff已归档；新增后最终全量数以最终运行为准。

新JUnit：formerUnequalLevelWitnessKeepsMeasuredWidthButCannotBuildAWaterGate；
fragmentedWaterTerminatesGeometryAndPermanentPrefixWithoutChangingSamplingFailures。
后者实际调用原PlannerScheduler，从水位矛盾到geometry OCEAN terminal，
再验证PrefixSummaryIndex封存相同终点并禁止终点之后继续reduce。
RealizationPlannerTest.partialTerminalCellKeepsOneNearSideCapAndOriginalReason本批亦实跑。
这是解析fixture下游证据，不声称真实世界已生成墙。

## 本批文件

绝对根：D:\云游四海-Mod源码\plaincraft-great-wall\

- src/main/java/local/plaincraft/greatwall/terrain/HydrologyClassifier.java：承接用户已改生产策略。
- src/test/java/local/plaincraft/greatwall/terrain/HydrologyTest.java：承接用户测试，并补原反例指标。
- src/test/java/local/plaincraft/greatwall/planning/PlannerSchedulerTest.java：新增永久终点链回归。
- gradle/smoke-tests.gradle：新增prepareWallPreview/runWallPreview，核心包与客户端缓存隔离启动，无自动关客户端的smoke模组。
- gradle.properties：仅本mod版本改0.1.0-s1c-preview.1，依赖锁不改。
- docs/S1C.md、本报告、docs/S1C-隔离实机测试.md及本批证据。

## 构建纪律及下一步

本批命令均先JAVA_HOME=JDK21，--offline --dependency-verification=strict。
依赖不升级、不联网、不关闭校验、不修改真实mods、不读真实存档。
隔离fixture允许开；正式greatwall.production=true仍会被生产入口拒绝。
本批确认完成后按全局拓扑7→8推进，不以历史/JUnit通过冒充真实S1C签收。
如发现新真实运行或规格冲突，本报告更新真实阻塞及凭证，不能预填PASS。
