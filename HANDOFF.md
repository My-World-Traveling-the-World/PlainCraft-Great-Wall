# 万里长城开发指南与交接

[项目介绍](README.md) · [版本更新](CHANGELOG.md)

## 构建和验证

使用 JDK 21、外置 Gradle 9.4.1、Mojang 官方映射。先在同级 `plaincraft-worldgen-api` 运行 `:api:contractJar :api-fabric:devModJar :api-fabric:contractIntermediaryJar`，再在同级河流源码运行 `planContractJar`。自定义前置路径用 `-PworldgenApiProjectDir=<路径>`。

```powershell
& $gradle test compileClientJava remapJar interopJar runGameTest --daemon --console=plain
```

`-PgreatwallGameTestClasses=BiomeWriterGameTests` 可单测群系组。道路编译需要本仓库 `interopJar`。正式 JAR 位于 `build/libs/`，本机 C2ME、FTB 与烟测输入锁见 `gradle/local-inputs.json`。普通报告进入 `build/`，跨 Gradle clean 的结果放 `test-output/`。旧 v2 格式的 `src/test/resources/fixtures/legacy-v2-evidence.zip` 冻结历史生产写入字节，保留原 SHA 和负坐标样本。

## 模板与生成

新普通世界使用模板配方 v3。五种楼体以明确端面接墙，整楼按四个正交方向旋转，城墙可斜行。墙用 `wall_module.csv` 配套剖面，接墙按模板末列的缺口衔接三垛一缺，区块边界延续相位。门楼约 800 格、敌楼和烽火台每 300 格交替、角楼约 4000 格、澄海楼在路线起点，实际占地和安全坡度决定候选是否采用。

有限长城从出生点附近向两端延伸。短水满足支撑与双岸条件时用水门，宽水绕不过去时可局部收口。楼体从完整占地最高地表计算马道高度，最低层的非空气列向下填地基，从而保留门洞。留意屋顶维度上限、逐格坡度和已建区块的防重复写入。新世界使用完整马道与无风化墙体。已有 v1/v2 世界按各自冻结的配方续建。超平坦使用独立展示流程。

实物模板、测试夹具和开发输入分别放在资源、测试与 Gradle 目录。更新模板可从 `tools/export-flat-template.py` 与 `tools/prepare-wall-templates.py` 开始，两端接口及落块结果需要 GameTest 和现场复核。

## 当前交接 · 2026-11-23

1. **改动**：进世界后的区块补建按 tick 暂停和续扫，一份计划每 tick 最多访问 512 体素，体素之间检查 4 ms 软预算。区块卸载或计划换代撤销游标；实体结案前复核方块，结案后才记完工。开局规划及放行顺序维持原样。
2. **验证**：1027 项单测，零跳过、失败和错误；3 项 `BiomeWriterGameTests` 和 Fabric `remapJar`、`interopJar` 成功。按现有本机构建方式，离线验证临时使用 `--dependency-verification=off`。
3. **未验证**：尚无整合包同种子对照。4 ms 只限制扫描/落块阶段，队列选择、光照调用、写实体、标记和台账仍可能占用更长时间；总补建时间也待测。
4. **负控**：把每 tick 体素上限由 512 改成 2048，`tickSlicesCapWorkAndOnlyPublishAfterAFullPaint` 因第一批超过 512 失败；原文已恢复并重新跑全量验证。
5. **下一步**：同种子分别记录总补建时间与慢 tick，带上 `-Dgreatwall.backfillTimings=on` 的 `WALL_BACKFILL_SLICE`；检查跨 tick 卸载、重进、门洞、容器及道路保护范围。
6. **历史与回退**：基线 `3db30ae2`，本次提交可单独回退，不修改模板或存档格式。[清理前开发记录](https://github.com/My-World-Traveling-the-World/PlainCraft-Great-Wall/tree/f26341ee71fa1dfe460ad0af4976137952afa0a3)。
## 2026-11-24 · 施工结案减负

1. **改动**：新区块首次施工和游戏内补建仅记录已满足的实体坐标，普通墙砖不再进入哈希集合或结案快照。仍逐格落块，保持完成章和实体执行顺序。
2. **验证**：1028 项单测，零跳过、失败和错误；群系组 3 项 GameTest、remapJar 和 interopJar 通过。
3. **未验证**：未在整合包测分配量、垃圾回收和慢 tick；未运行其余 GameTest 分组。
4. **负控**：临时恢复记录普通墙砖，实体专用集合断言报错；原文件按字节恢复后重验全量单测与构建。
5. **下一步**：同种子记录无实体墙段与楼体补建的单 tick 和总耗时，再判断下一瓶颈是否为光照更新或区块调度。
6. **历史与回退**：基线 f22f6d02；无需重建已有墙体或迁移数据。

## 2026-11-24 · 补建耗时与采样口径

1. **改动**：JVM 启用 `-Dgreatwall.backfillPeakTimings=on` 后，每个世界只在补建回调刷新峰值时输出 `WALL_BACKFILL_TICK_PEAK`。覆盖排队、区块请求、落块及结案，不是整个服务端 tick 的 MSPT。默认关闭，不改补建预算、路由、模板或存档格式。
2. **验证**：1028 项单测零跳过、零失败；remapJar 与 interopJar 成功。核对 11 格模板墙、±52 格的全线精采截面、随切线旋转的弯段，以及按正交旋转包围盒逐列计算的楼体最高地表。
3. **未验证**：尚无新计时的实机峰值，未改选址或走廊宽度。楼体高度逐列实采，施工支撑仍从走廊取证并以邻列补漏；不能用前者证明后者必然覆盖全部占地。
4. **负控**：未新增守卫。首次将补建主体移到辅助方法触发现有非阻塞与扫描上限守卫，最终保留生产入口的原扫描与检查，回归通过。
5. **下一步**：先钉住弯道、楼体包围盒和接墙口的施工取证，再尝试直墙窄带、弯墙按实际占地扩采、楼体按占地单独加宽；平缓选址先对有限里程候选粗筛，再精采胜出的候选。
6. **历史与回退**：基线 d1b2fc6c；本轮诊断可独立回退，无需迁移数据。
