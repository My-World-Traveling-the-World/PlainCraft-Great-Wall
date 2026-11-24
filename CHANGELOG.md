# 万里长城 · 更新记录

以下版本来自 Git 的构建配置。日期对应版本提交，下载状态以实际发布页为准。

## 未发布

- 修复斜跨浅滩时水门开孔冲突导致整块施工计划丢失。

- 新增可选的游戏内补建单次 tick 峰值诊断，默认关闭。

- 首次施工和区块补建只为方块实体保留结案坐标，减少普通墙段临时分配。

- 游戏内的长城补建把大区块分到多个 tick 落块，完整施工后才记完工。
- 统一玩家介绍和开发入口，将固定构建输入与本机测试报告分开。

## 当前系列

这一系列的完整构建号含 `-api-standardization` 后缀。

| 版本 | 日期 | 主要变化 |
| --- | --- | --- |
| `0.7.138` | 2026-11-24 | 新普通世界优先沿陆地走线，楼址可前后移动，沿墙与楼体占地精采；修复水门桥墩覆盖马道。 |
| `0.7.137` | 2026-11-23 | 改用独立世界生成 API，历史重放保留最小夹具。 |
| `0.7.136` | 2026-11-23 | 新世界生成完整马道，有限墙向出生点两端延伸，修复逐格接坡、水域收口和长城群系。 |
| `0.7.135` | 2026-11-23 | 有限长度可配置到十万格，初选复用重叠区块请求。 |
| `0.7.134` | 2026-11-23 | 长城保护范围恢复完整后才放行初始道路。 |
| `0.7.133` | 2026-11-23 | 按区块汇总占地保护位图。 |
| `0.7.132` | 2026-11-23 | 保留真实结构起点，修正避让走廊坐标。 |
| `0.7.131` | 2026-11-22 | 支持模板中的悬挂灯笼，避免失败的长城提前放行。 |
| `0.7.130` | 2026-11-22 | 精采样切片不再重走路线前缀。 |
| `0.7.129` | 2026-11-22 | 通过共享 API 发布预热终态失败。 |
| `0.7.128` | 2026-11-21 | 遇海产生的终止空段不再取消有效精采样。 |
| `0.7.127` | 2026-11-21 | 稳定预热界面的并行阶段，区分补建与失败。 |
| `0.7.126` | 2026-11-12 | 记录两个联动接口的接线结果。 |
| `0.7.125` | 2026-11-12 | 经标准接口向道路公布长城预热状态。 |
| `0.7.124` | 2026-11-12 | 从标准接口订阅河流就绪。 |
| `0.7.123` | 2026-11-12 | 登记首个 API 标准化构建编号。 |

<details>
<summary>早期构建记录</summary>

早期构建保留 Git 记录中的改动摘要。

| 版本 | 日期 | 主要变化 |
| --- | --- | --- |
| `0.7.122` | 2026-11-07 | 细采样改吃 geometry 折线，第二次进存档不再重算 4 段路线 |
| `0.7.121` | 2026-11-05 | PGW-COMPILE-RETRY 日志按状态变化降噪 + cell 缓存预算回退 65,536 |
| `0.7.120` | 2026-11-05 | cell 缓存预算 65,536 → 131,072 + 淘汰计数（查 fine 那 400 秒） |
| `0.7.119` | 2026-11-05 | 启动阶段默认并行。 |
| `0.7.118` | 2026-11-05 | backfill 计数可分 + 三处死代码/文档清理 |
| `0.7.117` | 2026-11-05 | 配置屏 chunk_cache_limit 上限 64 -> 256，与运行时钳制对齐 |
| `0.7.116` | 2026-11-05 | 加 PGW-COMPILE-RETRY 探针，定位编译前那 22 秒 |
| `0.7.115` | 2026-11-05 | 根搜索在一次提交中连续执行八批任务。 |
| `0.7.114` | 2026-11-04 | 续段异常不再静默 + surfaceNanosTotal 可见性 |
| `0.7.113` | 2026-11-04 | 修发布阻断级回归（batchNanosTotal 的 claimOwner） |
| `0.7.112` | 2026-11-04 | StageBudget 与 lane 同源 + 细采样埋点接活路径 + publishMs |
| `0.7.111` | 2026-11-04 | 调整根搜索批预算与默认预取窗口，修正 JVM 参数。 |
| `0.7.110` | 2026-11-04 | 根搜索预取扩窗 + 消掉细采样后的排队等待 |
| `0.7.109` | 2026-11-04 | 修正预热进度卡片的分母。 |
| `0.7.108` | 2026-11-03 | 终态失败立刻释放启动阶段，不再无限重试 |
| `0.7.107` | 2026-11-02 | 加载进度对应实际执行阶段。 |
| `0.7.106` | 2026-11-01 | 加入启动链和地形采样诊断。 |
| `0.7.105` | 2026-11-01 | 恢复山脊参考采样，补持久化告警并回收扫描余量。 |
| `0.7.104` | 2026-11-01 | 按方块判水，统一地面与证据锚点。 |
| `0.7.103` | 2026-11-01 | 版本 0.7.103 —— 地形口径修正（groundTopY 取第一层空气）随包发布 |
| `0.7.102` | 2026-11-01 | 修复深河谷越界、水门表与地形指纹。 |
| `0.7.101` | 2026-10-31 | 登记模板成品构建编号。 |
| `0.7.100` | 2026-10-28 | 容器兜底规则——模板里未显式指定的箱子/桶按楼层自动挂表、书架自动塞书 |
| `0.7.99` | 2026-10-28 | 门楼箱子战利品——三层主题表 + 标签化 mod 物品池 + 方块实体模板 |
| `0.7.98` | 2026-10-28 | 从纯净客户端存档重采门楼模板（+499 细节/109 光源）并修地基材质选择 |
| `0.7.97` | 2026-10-28 | 模板支持光源方块（light 用 UPDATE_ALL 写入，光立刻亮）(0.7.97) |
| `0.7.96` | 2026-10-27 | 墙起始相位 3->0，垛口缺口与门楼同相位，拼缝不再连出两个缺 |
| `0.7.95` | 2026-10-27 | 门楼模板改为存档原样（42,198 格，取消悬空薄板裁剪）(0.7.95) |
| `0.7.94` | 2026-10-27 | 同步玩家精修（云杉檐口/半砖排/材质替换/过道竖鳍删除）到门楼模板 |
| `0.7.93` | 2026-10-27 | 删掉门楼模板底部 168 格悬空散水（0.7.93） |
| `0.7.92` | 2026-10-27 | 落地层改用第一层空气(+1)、直线墙按模板直铺、门楼补齐两端缺口 |
| `0.7.91` | 2026-10-27 | 精修版门楼/城墙模板 + 直接落地 + 底板驱动地基 |
| `0.7.90` | 2026-10-27 | 独立门楼抬高3格、城墙抬高1格并入库全局底层地基 |
| `0.7.89` | 2026-10-27 | sweep diagonal wall and lower standalone gate |
| `0.7.86` | 2026-10-27 | lower standalone gate showcase by three blocks |
| `0.7.85` | 2026-10-26 | keep gate template whole and fill every footprint column with bottom material |
| `0.7.84` | 2026-10-26 | use real terrain ground and clipped guard rects |
| `0.7.83` | 2026-10-26 | connect doorway no-fill guard in clipped and standalone gates |
| `0.7.82` | 2026-10-26 | name showcase gate accounting for timeout diagnostics |
| `0.7.81` | 2026-10-25 | bottom-layer foundation and shared straight wall path |
| `0.7.80` | 2026-10-25 | root terrain demo voxels |
| `0.7.79` | 2026-10-25 | preserve gate doorway and align showcase walls |
| `0.7.78` | 2026-10-25 | add axis-aligned gate with diagonal wall approach showcase |
| `0.7.77` | 2026-10-25 | connect full gate to phase-matched wall modules |
| `0.7.76` | 2026-10-25 | contain terrain demo failures in superflat |
| `0.7.75` | 2026-10-25 | keep terrain demo columns buildable across synthetic drop |
| `0.7.74` | 2026-10-25 | include terrain demo footprint in showcase guard |
| `0.7.73` | 2026-10-25 | production-equivalent terrain demo in superflat workshop |
| `0.7.72` | 2026-10-25 | align showcase guard rectangles with current layout |
| `0.7.71` | 2026-10-25 | prevent showcase footprint overlap and add joined area |
| `0.7.70` | 2026-10-25 | add separate joined gate-wall showcase area |
| `0.7.69` | 2026-10-25 | consume per-section baseY and deep foundation columns |
| `0.7.68` | 2026-10-25 | true route order and complete cross-section grouping |
| `0.7.67` | 2026-10-25 | preserve route order for foundation planner |
| `0.7.66` | 2026-10-25 | consume per-column foundation plan in wall voxels |
| `0.7.65` | 2026-10-25 | wire foundation fill and full-footprint gate height |
| `0.7.64` | 2026-10-25 | P2 quality - natural footing, held refs, marker honesty, ignition identity |
| `0.7.63` | 2026-10-25 | P1 audit repair - guard footprint, save marker authority, player edits |
| `0.7.62` | 2026-10-25 | seal completed backfill chunks, never refill player edits |
| `0.7.61` | 2026-10-25 | persist showcase completion so re-entering a template save never restamps |
| `0.7.60` | 2026-10-24 | B-scope lifecycle, guard coverage and production metadata |
| `0.7.59` | 2026-10-24 | chunk-granular coarse guard, live restamp, seed coverage and drain self-check |
| `0.7.58` | 2026-10-24 | typed interop API (static facade + interopJar) so the road mod compiles against it |
| `0.7.57` | 2026-10-24 | remove the self-heal feature entirely - the mod never re-fills gutted voxels |
| `0.7.56` | 2026-10-24 | read-only stamp verification, self-heal off by default |
| `0.7.55` | 2026-10-24 | /greatwall restamp - reset every showcase stamp and repaint |
| `0.7.54` | 2026-10-24 | self-heal gutted voxels + /greatwall repaint to requeue finished chunks |
| `0.7.53` | 2026-10-24 | never paint at startup; seed road guard from plans and open the road gate immediately |
| `0.7.52` | 2026-10-24 | lay the 8 kit pieces one per row so they stop overlapping |
| `0.7.51` | 2026-10-24 | voxelize the split kit by conservative rasterization (no sampling holes) - 443k blocks |
| `0.7.50` | 2026-10-24 | re-voxel the split kit (subdivide big triangles, fill <=2 gaps) - 319k blocks |
| `0.7.49` | 2026-10-24 | count pieces for the showcase gate, widen rect radius for wide props |
| `0.7.48` | 2026-10-24 | publish template footprints up-front so roads avoid pieces before they are stamped |
| `0.7.47` | 2026-10-24 | hold roads until showcase stamping settles, add radius guard |
| `0.7.46` | 2026-10-24 | mark columns from the showcase stamps too, so superflat worlds exercise the road guard |
| `0.7.45` | 2026-10-24 | add /greatwall roadguard self-check for road avoidance |
| `0.7.44` | 2026-10-24 | mark wall columns in the worldgen feature path so roads avoid them everywhere |
| `0.7.43` | 2026-10-24 | track showcase stamps, drop unused 4.7MB palace.csv |
| `0.7.42` | 2026-10-24 | expose read-only road guard, mark every wall column |
| `0.7.17` | 2026-10-23 | showcase-only mode, tower template integrated |
| `0.7.14` | 2026-10-22 | gate template 145 wide, cycle-aligned seams, levelled footing |
| `0.7.1` | 2026-10-22 | match the reference walkway height; drop the extra ramps |
| `0.7.0` | 2026-10-22 | reference merlon rhythm 3+1; retire the hand-made showcase |
| `0.6.9` | 2026-10-22 | extrude the reference wall cross-section instead of approximating it |
| `0.6.8` | 2026-10-22 | wall matches the reference (11x15+3); fix floating gate stamp |
| `0.6.7` | 2026-10-22 | stamp the whole reference gate into the test route |
| `0.6.6` | 2026-10-22 | keep only the two straight test routes |
| `0.6.5` | 2026-10-22 | drop the standalone gate blob, keep only the gatehouse template on the gate |
| `0.6.4` | 2026-10-22 | stamp the reference gatehouse on the gate |
| `0.6.2` | 2026-10-22 | stamp the reference gate template into the flat showcase |
| `0.6.1` | 2026-10-22 | 14-high gate opening, 8-wide stair with parapets |
| `0.6.0` | 2026-10-22 | seven-band wall section from the reference gate |
| `0.5.3` | 2026-10-22 | showcase chunk range in absolute coordinates |
| `0.5.2` | 2026-10-22 | dougong brackets and lattice windows |
| `0.5.1` | 2026-10-22 | red columns, tile eaves and ridge |
| `0.5.0` | 2026-10-22 | isolated showcase models behind spawn |
| `0.4.4` | 2026-10-22 | fort outposts and walkway ramps |
| `0.4.3` | 2026-10-22 | drop the outer ledge; parapet stays three blocks |
| `0.4.2` | 2026-10-22 | L3 gate surround and sills, L4 props, taller gatehouse roof |
| `0.4.1` | 2026-10-22 | L2 overhang plinth and coping |
| `0.4.0` | 2026-10-22 | L1 trim - plinth, belt course every six, three-wide parapets |
| `0.3.9` | 2026-10-22 | 3/3 crenels, compact 600-block showcase |
| `0.3.8` | 2026-10-22 | gate tunnel at ground level keeps the walkway continuous |
| `0.3.7` | 2026-10-22 | parapet thickness three blocks |
| `0.3.6` | 2026-10-22 | 3x3 unlit campfire clusters for the beacon quest |
| `0.3.5` | 2026-10-22 | tower stairs, interior props, pavilion columns |
| `0.3.4` | 2026-10-22 | tower upper floor and wall niches |
| `0.3.3` | 2026-10-22 | tower interior vault and stone slab floor |
| `0.3.2` | 2026-10-22 | tiled roof house and campfire beacon |
| `0.3.1` | 2026-10-22 | tower crenels, beacon fire, sea pavilion |
| `0.3.0` | 2026-10-22 | tower doorways, gate bars, beacon crenellation |
| `0.2.9` | 2026-10-22 | gatehouse, roof house, spouts, end platform and beacon tower |
| `0.2.7` | 2026-10-22 | arched gate, gatehouse, belt course; three-wide merlons |
| `0.2.6` | 2026-10-22 | three-wide merlons, closed ends, solid base course |
| `0.2.4` | 2026-10-22 | three 2000-block routes (east, north, diagonal) |
| `0.2.3` | 2026-10-22 | halve every dimension, wall included |
| `0.2.2` | 2026-10-22 | light every block and close the wall ends and gate floor |
| `0.2.1` | 2026-10-21 | keep chunk tickets until the section is painted and stop skipping unloaded chunks |
| `0.2.0` | 2026-10-21 | one continuous 320-block route again |
| `0.1.9` | 2026-10-21 | keep the gallery out of real saves |
| `0.1.8` | 2026-10-21 | flat-world gallery with five separate exhibits |
| `0.1.7` | 2026-10-21 | skip wall warm-up in a flat test world instead of crashing |
| `0.1.6` | 2026-10-21 | add tower, gate, bastion and sea platform to the sample |
| `0.1.5` | 2026-10-21 | skip the wall pipeline in a flat test world |
| `0.1.4` | 2026-10-21 | procedural Great Wall sample with real-world proportions |
| `0.1.3` | 2026-10-21 | paint only loaded chunks at startup and hand the road gate to the in-game drain |
| `0.1.2` | 2026-10-21 | bound the in-game drain, never drop a chunk, and re-prove the done ledger |
| `0.1.1` | 2026-10-21 | ticketed non-blocking chunk requests so the startup pass cannot park for ever |
| `0.1.0` | 2026-10-08 | set mod_version to 0.1.0, clearing the release gate |
| `0.1.0-s1c-preview.2` | 2026-10-06 | dedicated Great Wall biome identity, startup split, N-lane |
| `0.1.0-s1c-preview.1` | 2026-10-01 | package isolated offline wall preview without smoke shutdown |
| `0.1.0-s0.3` | 2026-09-29 | certify terrain snapshot barrier and crash recovery |
| `0.1.0-s0.2` | 2026-09-29 | certify startup pump and outside-lock planning gates |
| `0.1.0-s0.1` | 2026-09-29 | preserve S0-01 skeleton and verified offline build baseline |

</details>

构建、模板接线和验证见 [开发指南](HANDOFF.md)。
