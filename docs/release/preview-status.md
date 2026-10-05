# 预览交付状态

**NOT_READY：不是 S5 签收发行版。** 正式生产 OFF，Tan's Huge Trees BLOCKED。
版本名称仍为 `0.1.0-s1c-preview.1`；没有启用正式生成或修改真实 mods/存档。

## 阶段检查点

| 阶段 | 当前事实 | 签收 |
|---|---|---|
| S1C | 旧隔离 seed17 检查点 voxels=24736、48/505（含 12 NON_OWNER）；最终超时 exit=1。点集和累计捕获优化已分别通过定向回归 | 未签收 |
| S2 | fork 纯 Y 异步 grade 有真实 solver 调用；168/168 具名 JUnit 和 3348 独立 checks | GW-011 12 项 NOT_RUN，未签收 |
| S3 | 自然刷怪守卫及五张 loot 资源有局部原生服务器测试 | 点火/首次箱子 proof/FTB/完整 GW-010 未完成 |
| S4 | 三族资源解析、真实 registry 校验及测试专用烟幕记录器有实现 | 生产图册未完成；链接反例因 Windows 权限 NOT_RUN；视觉未签收 |
| S5 | 指标和 payload 有独立测试，客户端缓存/裁剪 runner 通过 | 真实计时/导出绑定/网络/恢复故障/长压/发布未完成 |

阶段细节及原始失败记录见 `docs/S1C-台阶复验与超时-20261003.md`、
`docs/S1C-累计捕获与重复准备诊断-20261004.md` 和 `docs/S2-状态.md` 至 `docs/S5-状态.md`。
JUnit、解析或图册记录器通过不作为实机通过。

## 完整包回归

本轮源码冻结在 `dcf7e6f`；随后提交只涉及只读工具、状态文档和证据。
原始执行及逐文件 SHA 在 `docs/evidence/build/full-package-20261004/`。
该命令无 GameTest class 筛选，严格离线、严格依赖校验、强制重跑。
完整结果以该目录的最终 execution、JUnit 和 GameTest 归档为准；
运行中或缺最终结果不记 PASS。

不会将旧构建产物或旧 495/495 测试替代本轮完整包结果。
不会将原模组源码的 test-only/clientSmoke/serverSmoke 组件作为发布入口。

## 必须保留的待测项

- 点集/accumulator 修正后 seed17 505/505：NOT_RUN。
- vanilla/Tectonic/C2ME 完整生成及重进不漏段：NOT_RUN。
- 全路线地表已注册结构保持原样、绕行/安全断口：NOT_RUN。
- 成功路径每 chunk 成本、CPU oracle 切片计时观测（2026-10-05 起 40ms 硬门禁已按用户授权移除，改为只记录不拒绝）：NOT_RUN。
- 完整植被抑制、同 JVM A→B→A、6000 高/非默认 minY/Golden：NOT_RUN。
- S0 百轮、GW-011 十二项、双方实际道路施工和 durable backfill/ACK：NOT_RUN。
- 真实首次箱子 proof/不补货、点火/刷怪/双队/FTB 完整 GW-010：NOT_RUN。
- 96+32 生产图册、BetterSmoke 有/无完整截图、人工视觉和实走：NOT_RUN。
- 真实 metrics/stress、exception/halt 故障恢复全矩阵、长压/24h：NOT_RUN。
- 两次可复现最终离线包、真实整包、全部 P0 关闭、用户最终验收：NOT_RUN。

`S1C-DRAIN-01` 继续 OPEN；STOPPING 瞬间的非零 leases/reservation 不当作已排空。
完整 GameTest 若通过，仍不自动关闭这些项目或将本预览改名为正式发行版。
