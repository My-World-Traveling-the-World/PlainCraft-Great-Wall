# S1C 当前状态

## 2026-10-02 fort0 批准修订结果

**BLOCKED_AFTER_APPROVED_REVISIONS，未签收，生产 OFF，Tan's BLOCKED。**
结构前置筛选和“可替换不等于可承重”两项修订已实现。固定 vanilla seed17
选择了结构清空的根 `(206,67,953)`，但真实 fort0 仍在 APPLY 前失败：
3600 列中 `SUPPORTED=3344`、`PROTECTED=256`；地面入口 190 列中有 18 列
被 `mossy_cobblestone`/`sweet_berry_bush` 保护。`progressVoxels=null`，
没有墙体写入证书。全量 JUnit 350/350、最新审计工具编译通过。
这些自然装饰不属于本次获批的矿石/砾石范围，未静默放行；需新的有限
FEATURES 前预留/抑制授权。完整记录见 `docs/S1C-fort0修订结果.md`。

## 2026-10-01 接手792f967后的来源取证

**BLOCKED_PENDING_AUTHORIZATION，未签收，生产OFF，Tan's BLOCKED。**
最新结论以`S1C-fort0保护来源诊断.md`为准；以下旧阶段记录保留其原证据范围。
固定vanilla/17新隔离运行实测：87次首次T0捕获、108条已解析引用、未解析0、
旧metadata读回0。fort0根点(-185,65,1199)在真实103-piece Jigsaw结构保护范围内；
不是本轮仍插入conservativeReadBounds，也不是误走S0 snapshot。
编译及2项JUnit/8项定向native通过，但真实运行exit1、未进入APPLY，随后关服watchdog。
BlockCatalog/资源标签未改；用户要求先批准分类修订。
需确认recipe冻结前拒绝结构冲突候选及自然可替换/承重分类分离，
批准后才能实修；S1C仍无voxels>0证书，S2～S5骨架不代替生产集成。

**PAUSED_FOR_AUDIT / 未签收 / 正式生产接线 OFF。** 2026-10-01用户要求先梳理交审计，暂停代码/编译/测试。WIDE新策略已承接入库：全量JUnit343/343、新增后定向41/41；完整build因追加结构要求主动中止exit1，不能写通过。结构草稿未编译/测试，当前全高度投影不符合“只地表”新范围；COARSE水提示尚未实施。真实墙/重进/成本仍NOT_RUN。

**接手先读 `S1C-暂停审计交接.md`。** 它列出已提交代码715428f、三文件未提交草稿、
全部证据口径、RoadWeaver快水实际源码差异、Root承重和地表结构判据分叉。

本次新策略与测试交付见 `S1C-WIDE收头收尾.md`，不采用此前未批准的延迟填平/深基础提案。
下面阶段表和历史运行结果保留其原证据时间；真正签收须由后续新凭证更新。

本文件是阶段状态页，不是签收报告。S1B 在用户给定基线 `d0236ba` 已获审查通过；S1C 尚不满足真实生产生成、重进、植被和同 JVM 生命周期验收。

| 子项 | 状态 | 当前边界 |
|---|---|---|
| 1C-1 真实地形接线 | BLOCKED / 隔离接线已实现，真实墙未通过 | 新世界永久授权、实际出生/根点、Vanilla/C2ME gates、生产barrier与真实遍历运行器已接隔离fixture；正式production=true仍拒绝。vanilla/9282026根点拒绝，vanilla/0、vanilla/1、Tectonic/0在水文认证拒绝，尚无墙体APPLY证书 |
| 1C-2 生命周期复位 | NOT_RUN / 未进入 | native lane drain 的单测/诊断不等于实际 SERVER_STOPPING、同 JVM A→B→A 或生产 replay |
| 1C-3 植被抑制 | 前置guard/原版Tree钩已实现，未签收 | margin32含潜在收头/马道足印；原生31配置CODEC边界可解析，但31×100实际写集、真菌/巨菇家族及真实抑树尚NOT_RUN；不能把ABI/包络单测当抑制通过 |

历史前置全量回归：JDK21、严格离线/校验，`test assemble compileServerSmokeJava runGameTest -PgreatwallGameTestClasses=OracleGameTests,NoiseBridgeGameTests --rerun-tasks --no-build-cache`，exit 0，**309/309 JUnit、57 suites、定向 GameTest 6/6，failure/error/skipped=0**。源码此后有接线变更，此证据不称当前源码全量回归。

本批最近定向回归：RootSearch/WorldBootstrap/PlannerScheduler/Hydrology四套
**43/43 JUnit、exit0、30秒**，compileServerSmokeJava通过（JDK21/offline/strict）。
保留原异常及旧批次超时的before红例；证据 `docs/evidence/s1c/planner-failure-20261001/`
与`root-batch-guard-20261001/after-final/`。

本批最终离线回归：`test assemble compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon`
（同JDK21/offline/strict），**332/332 JUnit、61套件、exit0、2m5s**，无失败/错误/跳过，
所有源输入SHA未变。证据 `docs/evidence/s1c/real-wiring-blocker-20261001/final-unit-assemble/`。
这不是完整build/全GameTest；真实墙/reopen/成本仍无PASS凭证，
不继承历史309项或S1B矩阵替代S1C。

最终源码 `6026605` 上三个普通 DedicatedServer 独立新世界诊断均正常退出、exit 0、laneProtocolPassed=true，各有512列、196608个逐状态对照。每 lane 一次锁外异步初始化单列，跨 job 复用并实际关闭排空；稳态仍 25ms目标/40ms检测。实际 scheduler 批次最大 vanilla 7.8147ms、Tectonic 24.5981ms、C2ME 6.7015ms，超限 0，仅观测输入域。C2ME 初始化仍有约 2.286s，未删除成本或关闭 DFC。Tectonic 约122ms的旧观察及本轮冷阶段的内部原因仍未查明。

详细文档：

1. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-1C-1.md`：各基础批次、真实对照、命令/范围/证据。
2. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-Lane初始化.md`：获批合同差分、两种红例、所有批次/最终源码三 profile、完整范围与可复现命令。
3. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-阻塞-RandomState初始化.md`：保留旧合同和旧失败；顶部说明新授权已实现，不改旧证书状态。
4. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\lane-native-20260930\` 及三个最终 b797f27f/93cb5fa6/7b2bdc7d run：当前前置验证。

真实运行详情与各批输入见1C-1报告§16～31。正式生产启用、真实墙通过、退出重进、人工实走、6000profile、Golden、S5/24h均不能写PASS。S0百轮矩阵本轮NOT_RUN；Tan's Huge Trees继续BLOCKED，不删mod冒充整包通过。
