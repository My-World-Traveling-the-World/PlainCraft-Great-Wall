# S1C 当前状态

**BLOCKED / 未签收 / 生产接线 OFF。** 2026-09-30。

本文件是阶段状态页，不是签收报告。S1B 在用户给定基线 `d0236ba` 已获审查通过；S1C 尚不满足真实生产生成、重进、植被和同 JVM 生命周期验收。

| 子项 | 状态 | 当前边界 |
|---|---|---|
| 1C-1 真实地形接线 | BLOCKED / B-S1C-01 | 完成若干基础类与三个实际 CPU profile oracle 正确性对照；Tectonic/C2ME 初始化超过冻结40ms门槛，生产尚未接线 |
| 1C-2 生命周期复位 | NOT_RUN / 未进入 | 不能用独立 JVM 的普通关服证明同 JVM静态复位或生产drain |
| 1C-3 植被抑制 | NOT_RUN / 未进入 | 尚无 margin32完整写集的生产 guard；不能以BlockCatalog分类代替树抑制 |

本轮最后的实际回归：JDK21、严格离线/校验，`test assemble compileServerSmokeJava --rerun-tasks --no-build-cache`，exit 0，**294/294 JUnit、56 suites、failure/error/skipped=0**。这不是完整 `build` / GameTest / S1C 签收。

三个普通 DedicatedServer 独立新世界诊断均正常退出，各有512列、196608个逐状态对照；vanilla oracle性能为限定样本PASS，Tectonic初始化最大121.753ms、C2ME最大1851.277ms，因此后两组为 **BLOCKED_PERFORMANCE**。当前已停止生产推进，等待初始化协议审查决策，没有静默排除冷构造或关闭DFC。

详细文档：

1. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-1C-1.md`：各基础批次、真实对照、命令/范围/证据。
2. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\S1C-阻塞-RandomState初始化.md`：旧合同、真实反例、源码/实际字节码、停止边界和待审查方向。
3. `D:\云游四海-Mod源码\plaincraft-great-wall\docs\evidence\s1c\native-oracle-profiles-20260930\`：本轮汇总、实际变换class/ABI、完整单测与编译打包证据。

生产启用、真实墙/地形、退出重进、人工实走、6000profile、Golden、S5/24h均不能写PASS。S0百轮矩阵本轮NOT_RUN；Tan's Huge Trees继续BLOCKED，不删mod冒充整包通过。
