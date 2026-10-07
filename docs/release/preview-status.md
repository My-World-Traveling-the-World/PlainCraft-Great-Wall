# 预览交付状态

**NOT_READY：不是 S5 签收发行版。** 正式生产 OFF，Tan's Huge Trees BLOCKED。
版本名称现为 `0.1.0-s1c-preview.2`；没有启用正式生成或修改真实 mods/存档。

> **本文件已于 2026-10-07 按实测结果重写。** 此前版本停留在 `0.1.0-s1c-preview.1` /
> 源码冻结 `dcf7e6f`，其中「S1C 48/505 超时」「故障矩阵 NOT_RUN」等条目**已被后续运行推翻**。
> 逐项现状以 `docs/release/acceptance.json` 与 `docs/release/verification-report-20261007.md` 为准。

## 阶段检查点（2026-10-07 实测）

| 阶段 | 当前事实 | 签收 |
|---|---|---|
| S1C | **真实地形遍历已 PASS**：`REAL_WALL_TRAVERSAL_PASS owners=505 voxels=28046 feet=2537`（5h43m，`production=OFF`）；红线 `WAIT_WHILE_LOCKED=0`、`STAGE_ENTER==STAGE_EXIT=787727` | 遍历项已通过；人工实走与 height6000 未做 |
| S2 | fork 纯 Y 异步 grade 有真实 solver 调用；JUnit 全绿 | GW-011 具名项仍 NOT_RUN |
| S3 | 自然刷怪守卫及五张 loot 资源有局部原生服务器测试（证据在 `docs/evidence/build/s3-*`） | 点火/首次箱子 proof/FTB/完整 GW-010 未完成 |
| S4 | 三族资源解析、真实 registry 校验及测试专用烟幕记录器有实现 | 生产图册未完成；**链接反例因 Windows 未开开发者模式 NOT_RUN**（见下）；视觉未签收 |
| S5 | **故障矩阵 exception 180/180 + halt 180/180 全通过**；压力 profile 1h `passed=true`；24h 进行中 | 24h 未出结果；发布未签收 |

## 版本与构建事实（2026-10-07 核实）

| 项 | 值 |
|---|---|
| `mod_version` | `0.1.0-s1c-preview.2` |
| HEAD | 见本文件所在仓库的 `git log -1`（本文件不写死哈希，避免再次过时） |
| 成品 jar SHA-256 | `80506fe57a6379b151ef3dab4ce7b83ec62b5ad74e631d0049777f5c4d08f456` |
| 可复现性 | `remapJar --rerun-tasks --offline --dependency-verification=strict` 与删产物重建**同 SHA** |
| 发布门禁 | `verifyReleaseArtifacts` 可运行；**唯一失败项是版本号非 `0.1.0`**，属正确拒绝 |

**本文件刻意不再写死 HEAD 与阶段行号引用** —— 上一版写死 `dcf7e6f` 后即随提交过时。
需要精确值请查 `acceptance.json`（每次运行更新）或直接读 git。

## 必须保留的待测项（2026-10-07 更新）

已取得证据、**从待测项移除**：

- ~~点集/accumulator 修正后 seed17 505/505~~ → **已 PASS**（`docs/evidence/s1c/real-wall-traversal-pass-20261007.json`）。
- ~~exception/halt 故障恢复全矩阵~~ → **360/360 全通过**（`docs/evidence/s5/`）。
- ~~真实 metrics/stress 与短长压~~ → **1h `passed=true`**（`docs/evidence/` 与 `build/s5-stress-1h-evidence/`）。
- ~~40ms 硬门禁~~ → 2026-10-05 按用户授权改为**只记录不拒绝**。

**仍然 NOT_RUN**：

- vanilla/Tectonic/C2ME 完整生成及重进不漏段（只跑过 vanilla）。
- 全路线地表已注册结构保持原样、绕行/安全断口。
- 完整植被抑制、同 JVM A→B→A、6000 高/非默认 minY/Golden。
- S0 百轮、GW-011 十二项、双方实际道路施工和 durable backfill/ACK。
- 真实首次箱子 proof/不补货、点火/刷怪/双队/FTB 完整 GW-010。
- 96+32 生产图册、BetterSmoke 有/无完整截图、**人工视觉和实走**。
- **24h 长压**（进行中，未出结果）。
- 两次可复现最终离线包（`Test-S5Release.ps1` 未跑）、真实整包、全部 P0 关闭、用户最终验收。
- **符号链接反例**：本机 Windows 未启用开发者模式，`Files.createSymbolicLink` 无权限，
  `SmokeVisualChecklistTests` 据此报告 `link_cases=NOT_RUN` 并**拒绝给出总体 PASS**（正确行为）。
  取该证据需在开发者模式或提权环境重跑 S4。

`S1C-DRAIN-01` 继续 OPEN；STOPPING 瞬间的非零 leases/reservation 不当作已排空。
**为何零计数也不能关单**：已有快照中出现过 `flights=0/admitted=0/leases=0`，但那次运行是
**以失败结束**的（`state.failed=true`），失败会走完整 teardown，计数按构造归零 —— 因此它
**不能证明成功路径上排空正常**，而后者正是该项所问。`README.md:32`（2026-10-04）与
`S1C-OwnerCoverage-A+C状态.md:53` 均以同一理由拒绝用该快照关单。结清它需要一次【成功运行】
的 STOPPING 快照。
完整 GameTest 若通过，仍不自动关闭这些项目或将本预览改名为正式发行版。
