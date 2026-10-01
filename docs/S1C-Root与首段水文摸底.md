# Root 与第0段水文：覆盖核对及40 seed摸底

状态：A 已源码核对；B 未运行。基线 `b440e33`，生产接线OFF，Tan's BLOCKED。
日期2026-10-01。本任务只增加测试 sourceSet/启动取证工具，不修改生产源码或水文政策。

## A. 谁保证实际第0段干燥

【源码事实】没有前置环节保证第0段实际路线全程干燥，也没有首段认证失败后重选root的闭环。
但“后半段无人检查”不是准确描述：实际路线后来由HydrologyClassifier检查，
它可以拒绝，却不能使已经选定的首段必然可认证/生成。

| 环节 | 实际范围/行为 | 源码索引 |
|---|---|---|
| root轴初筛 | distance=256/512/1024，across=0/-48/+48，九个离散REFERENCE列 | RootSearch.java:25、:157 |
| 接受轴 | invalid=false且wet=0；不读取AnchorField或首段路线 | RootSearch.java:176 |
| 第0段carrier | u长度2048，boundary1 lateral由seed给出[-384,+384]，只检几何包络/朝向 | AnchorField.java:12、:27、:42、:81 |
| 山脊路线 | carrier叠加受限残差；COARSE湿度只是评分项，不是“所有列干燥”证明 | RidgePlanner.java:118、:132 |
| 实际水文 | 第0段全路线加下一RouteShape前128格；REFERENCE/supercover/两岸宽度认证 | HydrologyClassifier.java:24；PlannerScheduler.java:300 |
| 认证失败 | 原HYDROLOGY_UNRESOLVED等错误；无回调至RootSearch重选 | HydrologyClassifier.java:218；PlannerScheduler.java:435 |

上述索引的完整源码根为：
`D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\`；
RootSearch在lifecycle/，AnchorField/RidgePlanner/PlannerScheduler在planning/，
HydrologyClassifier在terrain/。行号以本基线为准，生产代码将用完整SHA检查冻结。

九列干燥不等于0..1024连续干燥；1024之后和carrier偏移均不在九列证明中。
2048是轴向u跨度，不保证真实弧长S恰等于2048。
vanilla/1已有失败S=[26172,43232)/256，约102..169格；
该例在前1024格内也失败，所以不能单独归因“后半段没验”或“boundary1甩偏”。

【设计判断】若把root成功理解为“首段可认证且可施工”，当前实现不兑现此承诺。
这是已确认的覆盖/阶段衔接缺口；四十个样本只量化影响，不能替此缺口开脱或改变规则。

## B. 固定输入与观测边界

固定vanilla seed=0..39，每个独立新DedicatedServer、独立目录、真实原版出生点，
从同一原生HEAD/RETURN读取出生探测域和选定坐标；不伪造spawn或早期域。
原ProductionInputs冻结、RootSearch、AnchorField、RidgePlanner、HydrologyClassifier原样调用。
只计算route0及为原128格halo所需的route1，**只分类hydrology0**；
不调用PlannerScheduler.startupPump的0..3预热、不运行height contract/施工/T0/APPLY。
这些是统计运行器端口，不是生产接线或生命周期认证。

分类：

1. ROOT_NOT_FOUND：原root阶段找不到候选/足印/干燥轴。
2. root成功后hydrology0抛HYDROLOGY_UNRESOLVED或ROOT_NOT_FOUND。
3. root成功且hydrology0完整结束。包括原WIDE/SUPPORT_GAP；不保证有1200格陆墙或能施工。
4. OTHER：性能硬门槛、初始化/运行器/route依赖/超时等；原样计数，不能塞入前三类。

总分母固定40；额外列root成功后的条件比例。若OTHER影响阈值判断，给成功率上下界，
不删case、不换seed、不反复重跑到绿。

失败区间记录原始signed S、当前段裁剪S、对应实际轴向u与横向b：

- S独占桶：FIRST_0_1024 / SECOND_1024_2048 / STRADDLES_BOUNDARY / AFTER_2048 / EMPTY。
- u另行分桶，不能把u与S混为同一种“覆盖”。
- “终点附近”仅操作化为u与[1664,2048]相交的重叠标签；384本来是横向偏移幅度，
  该标签不能证明boundary1造成失败，不能加到两个半段计数中冒充互斥第三类。
- 横向超出±48另标；这也不是九个稀疏采样点覆盖全带的证明。
- 没能定位的budget/样本质量错误单列UNLOCATED，不猜crossing。

## 取证工具准备

新增测试专用RootHydrologyFixture与SeedSurveyLocations，原生产目录不变；
仅测试类路径可读取serverSmoke output，以实测坐标统计函数。
JDK21/offline/strict `test --tests local.plaincraft.greatwall.smoke.SeedSurveyLocationsTest
compileServerSmokeJava --rerun-tasks --no-build-cache --console=plain --no-daemon`：
exit0、26秒、6/6 JUnit。证据`docs/evidence/s1c/seed-survey-20261001/setup-unit/`。
覆盖曲线S/u不同、跨桶、signed halo裁剪、段内S>2048、终点标签重叠。
这不是六个seed结果；四十个实际输入仍NOT_RUN。

测试专用出生钩子已编译/remap通过：`compileServerSmokeJava remapServerSmokeJar`
（同JDK21/offline/strict、rerun、no-build-cache），exit0、21秒；
证据`seed-survey-20261001/hook-compile/`。钩子require=1，生产目录没有修改，
原RootProbe与InitialSpawn签名只在serverSmoke副本观察，普通模式只读开关后返回。
运行器固定0..39、最多2个独立JVM；每个独立world/runDir/stdout/退出凭证。
Gradle只准备一次严格离线缓存运行时，后续直接启动该已锁JDK21/原版server/API/模组Jar，
避免40次构建共用输出发生并行冲突。脚本AST syntaxErrors=0。
`OBSERVATION_COMPLETE`只表示该seed取证完成，任何失败分类都不写成墙通过。
