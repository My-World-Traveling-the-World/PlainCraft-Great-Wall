# S1C 一次性 lane-native 初始化协议

状态：**实施中 / 未签收 / 生产接线 OFF**。输入基线 `fad944e`，2026-09-30。

## 1. 授权与冻结合同差分

本次用户批准把一次性原生初始化与稳态采样拆成两个协议，不改变地形算法、采样预算、日程或画面质量。

1. 旧公式：`docs/S1-S5实施规格/S1-城墙本体.md:1082` 的构造/单 cell 超过 40ms 阻塞条款，此前把私有 RandomState 冷初始化也纳入采样超限。
2. 具体反例：旧真实证据中 Tectonic 初始化最大 121.7527ms、C2ME 最大 1851.2769ms。旧证书保持 BLOCKED，不回写为 PASS。
3. 获批最小修订：每 session 每 lane 的一次性 native 初始化显式锁外异步、独立容量/计时/取消/异常/drain，可超过 40ms；稳态 slices/cell/block/biome/advance 仍是 25ms 目标、40ms 检测。
4. 明确不变：私有同 seed/settings 的 RandomState；不使用 live 世界 RandomState；不跨 lane/session 借用；不在主线程或 worldgen 同步等初始化；startup 不等正常 tick，也不等 native ready；所有 P0/红线不变。
5. 验证与残余范围：按下列批次累计，不把协议单测写成真实墙生成验收。

这是协议拆分，不是静默放宽采样门槛：切片上限用于避免可重复采样工作卡顿；原生库一次性编译是另一个不可分割生产者，必须移出调用者/锁/tick，并用独立 future 与 drain 明示它的成本。实际 C2ME 变换字节码 `docs/evidence/s1c/native-oracle-profiles-20260930/abi/runtime-c2me-RandomState.txt:162` 在构造器返回前调用 `c2me-opts-dfc$postCreate`，`:430` 同步 `BytecodeGen.finalizeCompilation`。**Tectonic 单独约 122ms 的内部阶段分解仍未查明，不套用 C2ME 归因。**

## 2. 批次一：lane 生命周期与调度依赖

修改文件，根路径均为 `D:\云游四海-Mod源码\plaincraft-great-wall\`：

- `src/main/java/local/plaincraft/greatwall/terrain/OracleLane.java`，新增独立生产者。
- `src/main/java/local/plaincraft/greatwall/terrain/NaturalTerrainOracle.java`，同步诊断默认拒绝，只有解析 fixture 显式授权。
- `src/main/java/local/plaincraft/greatwall/planning/PlannerScheduler.java`，job 不持有 oracle；ready 前只接 future；lane 复用；增加真实 drain future。
- `src/test/java/local/plaincraft/greatwall/terrain/OracleLaneTest.java`，9 项生命周期反例。
- `src/test/java/local/plaincraft/greatwall/terrain/AnalyticTerrainOracle.java`，解析参考输入显式 opt-in。
- `src/test/java/local/plaincraft/greatwall/planning/PlannerSchedulerTest.java`，14 项（原 12 + 2）。

`OracleLane` executor 单线程独占 mutable oracle；初始化 future 仅公开 `Void` 副本，取消副本不取消生产者。初始化一次，最多 64 个批次先预留再入队，两个固定 lane 是 session 初始化容量。初始化错误不重试、不静默换输入；失败 job 可能遗留 cursor，调度器关闭整 session，不能把污染 lane 借给下一 job。关闭即拒绝新申请、让公开 promises 失败，但 close runnable 排在真正 constructor/动作之后；drain 仅在 owner 关闭 oracle 成功后完成，close 异常使 drain 异常。没有 shutdownNow 代替生产者结束。

初始化允许 CPU 原生构造的专用上下文下一批接入。本批不能单独证明 CpuTerrainOracle 的实际 native 初始化守卫或性能。

### 实际命令与结果

所有命令先设置 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`：

```powershell
.\gradlew.bat --offline --dependency-verification=strict test --tests 'local.plaincraft.greatwall.planning.PlannerSchedulerTest.hundredCallersShareOneCompute' --rerun-tasks --no-build-cache --console=plain --no-daemon
.\gradlew.bat --offline --dependency-verification=strict test --tests 'local.plaincraft.greatwall.terrain.OracleLaneTest' --tests 'local.plaincraft.greatwall.planning.PlannerSchedulerTest' --rerun-tasks --no-build-cache --console=plain --no-daemon
```

第一条在修生产代码之前退出 1：旧实现实际调用 3 次工厂，新协议要求 2 次。不是删除共享计算测试，保留 100 caller 同一对象断言，只按新授权调整生命周期计数。第二条退出 0，23s，**23/23，2 suites，0 failure/error/skipped**。

| 判据 | 预期 | 实际 |
|---|---|---|
| 每 lane 一次、跨 job 复用 | ready 后 5 个请求同身份，关服前零 close | PASS，owner 校验 fixture |
| 不跨 session/lane | 三 producer 身份不同 | PASS，生命周期 fixture；原生 seed/settings 下一批验证 |
| 初始化延迟不阻塞 startup | 两 factory latch 被扣住，pump 仍推进，无 tick | PASS，实际 scheduler 定向测试 |
| 取消与排空分离 | 取消 view 不取消保留请求；constructor 未返回不得 drain | PASS，反例 latch |
| 锁/线程守卫、容量/异常 | 非法入口被拒；64 预留，失败有显式错误 | PASS，fixture；不是实际 C2ME 生产锁证书 |
| 原生构造守卫、真实稳态 ≤40ms | 三 CPU profile 新证书 | NOT_RUN，本批未做 |

证据：`D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\lane-native-20260930\before\` 和 `lifecycle\`，包含原始日志、JUnit XML、退出凭证。SOURCE 为本批父提交加收录于同批的源码 diff；不声称父提交未修改代码直接通过。

## 3. 未验证与保持边界

生产墙生成/重进/不漏段/每 chunk 成本、6000 高度、非默认 minY、Golden、真实玩家实走、S5/24h：**NOT_RUN**。

Tan's Huge Trees profile：**BLOCKED**，树干保护不等于完整树写集授权。

本协议未完成前生产接线保持关闭。S0 百轮矩阵本批 NOT_RUN，未修改 S0 实验路径；没有执行全阶段 build/runGameTest。
