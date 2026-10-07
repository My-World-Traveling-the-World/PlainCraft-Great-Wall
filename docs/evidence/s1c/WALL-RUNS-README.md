# s1c 下的 10-01 墙运行目录说明（2026-10-07）

`docs/evidence/s1c/` 下有 **7 个 `s1c-wall-*-20261001-*` 目录**（6 个 vanilla + 1 个 tectonic），
它们**全部是失败运行的证据，不是通过结果**。此文件消除歧义。

## 共同结局

| 项 | 值 |
|---|---|
| 运行日期 | 2026-10-01 00:53 起 |
| `exitCode` | **1**（全部 7 个） |
| crash 报告 | **每个 2 份** |
| 异常类型 | `java.lang.IllegalStateException`（全部 7 个同一根因） |
| 崩溃描述 | `Exception in server tick loop` |
| 消息 | `GreatWall production fixture required dependency failed` |
| 抛出点 | `ProductionSession.fail(ProductionSession.java:207)` |

## 这说明了什么

它们记录的是**本会话修复之前的接线缺陷**：production fixture 的某个依赖在启动时失败，
导致服务器 tick 循环抛异常并崩溃。**这一路径现在能走通** —— `ProductionSession.java:207`
已位于 `try { ... } catch (Throwable failure) { fail(failure); }` 之内，成功分支会记录
`FIXTURE_RECIPE_READY`。

**因此它们与 2026-10-07 的 `REAL_WALL_TRAVERSAL_PASS`（owners=505）不矛盾**：后者发生在
这些失败**之后**，且走的是修复后的路径。

## 读者须知

- **不要**把这 7 个目录当作墙生成的结果 —— 它们没有一个是 PASS。
- 权威的墙遍历结果在 `docs/evidence/s1c/real-wall-traversal-pass-20261007.json`
  （`status: PASS_OBSERVED_REAL_WALL`）。
- 保留这些目录的价值：它们是**接线缺陷真实存在过**的一手证据，删掉就无法回溯。

## 目录构成（每个约 13–16 个文件）

`staged-inputs.json`、`generated\execution.json`、`generated\inputs.json`、`generated\sha256.json`、
`generated\stdout.log`、`generated\stderr.log`、`generated\crash-reports\`（2 份）、
`generated\logs\`、`generated\world\greatwall\diagnostics\`（STOPPING 快照）。