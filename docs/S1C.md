# S1C 当前状态

**进行中 / 未签收 / 生产接线 OFF。** 2026-09-30。B-S1C-01 已按用户批准的分栏协议完成实现和前置复验；不等于 S1C 整阶段通过。

本文件是阶段状态页，不是签收报告。S1B 在用户给定基线 `d0236ba` 已获审查通过；S1C 尚不满足真实生产生成、重进、植被和同 JVM 生命周期验收。

| 子项 | 状态 | 当前边界 |
|---|---|---|
| 1C-1 真实地形接线 | 进行中 / 初始化前置协议通过 | 三实际 CPU profile 的私有初始化/稳态采样/实际 scheduler 批次已复验；新世界授权、出生选址、生产 gates 与真实施工仍未接线 |
| 1C-2 生命周期复位 | NOT_RUN / 未进入 | native lane drain 的单测/诊断不等于实际 SERVER_STOPPING、同 JVM A→B→A 或生产 replay |
| 1C-3 植被抑制 | NOT_RUN / 未进入 | 尚无 margin32完整写集的生产 guard；不能以BlockCatalog分类代替树抑制 |

本轮最后实际回归：JDK21、严格离线/校验，`test assemble compileServerSmokeJava runGameTest -PgreatwallGameTestClasses=OracleGameTests,NoiseBridgeGameTests --rerun-tasks --no-build-cache`，exit 0，**309/309 JUnit、57 suites、定向 GameTest 6/6，failure/error/skipped=0**。这不是完整 `build` / 全 GameTest / S1C 签收。

最终源码 `6026605` 上三个普通 DedicatedServer 独立新世界诊断均正常退出、exit 0、laneProtocolPassed=true，各有512列、196608个逐状态对照。每 lane 一次锁外异步初始化单列，跨 job 复用并实际关闭排空；稳态仍 25ms目标/40ms检测。实际 scheduler 批次最大 vanilla 7.8147ms、Tectonic 24.5981ms、C2ME 6.7015ms，超限 0，仅观测输入域。C2ME 初始化仍有约 2.286s，未删除成本或关闭 DFC。Tectonic 约122ms的旧观察及本轮冷阶段的内部原因仍未查明。

详细文档：

1. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-1C-1.md`：各基础批次、真实对照、命令/范围/证据。
2. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-Lane初始化.md`：获批合同差分、两种红例、所有批次/最终源码三 profile、完整范围与可复现命令。
3. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-阻塞-RandomState初始化.md`：保留旧合同和旧失败；顶部说明新授权已实现，不改旧证书状态。
4. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\lane-native-20260930\` 及三个最终 b797f27f/93cb5fa6/7b2bdc7d run：当前前置验证。

生产启用、真实墙/地形、退出重进、人工实走、6000profile、Golden、S5/24h均不能写PASS。S0百轮矩阵本轮NOT_RUN；Tan's Huge Trees继续BLOCKED，不删mod冒充整包通过。
