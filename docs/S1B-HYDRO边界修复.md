# S1B HYDRO 边界定向修复

日期：2026-09-30。状态：两项原始反例 PASS；不是 S1B 签收。

## 根因与最小修订

原 HYDRO index=1（ENEMY_TOWER）和 index=19（BEACON）均为 width=49、after=false、gap=0。
河道 [232,281)，中心 S=256.5，计划岸边预留 [224,289)。
WaterGate.localBounds 原公式 floor((fromS-centerS)/256)-8 得到 -33。
逆栅格化把世界 x=223 的列中心 223.5 映射为局部 -33，错误占用预留区外的一列。
该列的水门必需净空与相邻完整楼体冲突。ChunkPlanCompiler 拒绝未授权擦除是正确行为。

最小修订：近岸下界改为 ceil((fromS-centerS)/256)-8，Q8 表达为 floorBlock(delta+255)-8。
本反例局部下界变为 -32，世界首列变为 224。整格中心结果不变。
不移动站点、不扩大水文预留区、不改构件尺寸/水道要求、不放宽 owner 权限。
这是半格中心取整公式与已有预留区合同的对齐，不是改变用户需求。

## 代码与反例

- D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\component\WaterGate.java：近岸取整。
- D:\云游四海-Mod源码\plaincraft-great-wall\src\main\java\local\plaincraft\greatwall\generation\ChunkPlanCompiler.java：错误附带冲突坐标和双方体素，权限条件不变。
- D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\AdjacentComponentMatrixTests.java：复用原 HYDRO 检查函数；保留旧公式 x=223 越界反例；增加完整水门不得越出八格岸边预留区断言。
- D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\java\local\plaincraft\greatwall\gametest\HydroBoundaryRegressionTests.java：只运行原 index=1/19，两参数在定向入口编号为 0/1。
- D:\云游四海-Mod源码\plaincraft-great-wall\src\gametest\resources\fabric.mod.json：注册定向入口；默认矩阵仍保留。

## 实测

同一定向入口，修复前 attempted=2 passed=0 failed=2，退出码 1，耗时 58 秒。
修复后 attempted=2 passed=2 failed=0，1 个 GameTest 方法通过，退出码 0，BUILD SUCCESSFUL in 2m 12s。
保留原正反请求一致性、完整构件、原生 owner 写入、碰撞/路径、装饰 survival、源水/主水道检查，没有降低断言。
原始日志：docs/evidence/s1b-closeout/hydro-boundary/red.log 和 green.log。

## 可复现命令

在 D:\云游四海-Mod源码\plaincraft-great-wall 执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict runGameTest -PgreatwallGameTestClasses=HydroBoundaryRegressionTests -PgreatwallTestHeap=1024m --console=plain --no-daemon
```

本次按用户要求只跑两个失败组合，没有启动无关测试。
修复后 HYDRO 全 72 项、其他矩阵、全量 build：NOT_RUN。
S1B 未签收；S1C 未启动；Tan's Huge Trees profile 仍为 BLOCKED。
没有联网、升级依赖、部署真实 mods 或打开真实存档。
