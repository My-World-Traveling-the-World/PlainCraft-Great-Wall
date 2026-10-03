# S1C Owner Coverage A+C 状态

日期：2026-10-03
源码提交：`062df0346a3733ccf2f823ee3f335b5302a9c278`

## A+C 不变式

`CoveragePlanner.cellsForChunk(owner, window)` 在派发前要求每个 cell 的
`realizationOwners(cell)` 集合包含该 owner；否则该 cell 不派发。
`ProductionWallPipeline` 对仍被派发但缺少 `collected.own()` 的情况继续 fail-closed。
不合成空 coverage，不改变 before-state、负空间权限或写入授权。

正向模板/水门/出口五朝向验证及非 owner 遍历验证见已提交证据
`docs/evidence/s1c/owner-coverage-ac-20261002/directed-optimization-v2/`
和 `docs/evidence/s1c/owner-coverage-ac-20261002/directed-gametest-optimization/`。

## 本轮验证

本轮新加的 invocation-local 保护盒 chunk 索引，以完整 immutable box 列表作来源，
按实际列 chunk 建立候选桶；在调用结束或异常时恢复 ThreadLocal scope 并释放预算。
缺桶仍回退完整列表，不放宽保护判据。

定向命令由 `tools/Invoke-VerifiedRun.ps1` 记录于
`docs/evidence/s2/session-binding-20261002/directed-s1c-s2-v1/`。
`test` 退出码 0，12 个已执行 suite 共 89 tests，0 failures/errors。
包含保护列索引、CoveragePlanner、入口合同、Road 快照/provider/adapter、
HistoryCodec/Index/Journal、WallWorldState 和 RecoveryCoordinator。

## seed17 隔离真实运行

证据目录：
`docs/evidence/s1c/owner-coverage-ac-20261002/s1c-ac-fast-20261002-231705/`

- sourceCommit：`062df0346a3733ccf2f823ee3f335b5302a9c278`
- 命令：`runServerSmoke -PsmokeProfile=vanilla -PsmokeRunId=s1c-ac-fast-20261002-231705 -Ps1cRealWall=true -Ps1cSeed=17 -Ps1cRepresentativeBlocks=100 --offline --dependency-verification=strict --no-daemon --console=plain`
- exitCode：1；耗时约 28m55s
- traversal：11/505 owners；累计已验证 voxels 6323、walkway feet 478
- STOPPING 会话计数：`written-voxels=17013`、APPLY enter/exit/proof durable 各 19；
  这是过程计数，不是完整遍历证书
- 失败：`real walkway lacks ground or two-block clearance BlockPos{x=207,y=71,z=932}`
- fixture 没有产出 PASS 报告；生产接线保持 OFF

因此 A+C 已越过先前 owner coverage 缺口并真实写出方块，但 seed17 真实墙验收仍
FAIL，失败层为实际 WALKWAY 支撑/净空复核；不得据此报告 S1C 签收。

## 尚未闭合

- 新失败坐标的真实区块方块状态尚未离线解码，暂不能判定是脚下支撑还是上方碰撞。
- vanilla 全 505 owner、重进一致性、Tectonic/C2ME 代表种子、成功路径每 chunk 成本：NOT_RUN。
- `S1C-DRAIN-01`：OPEN。此次 STOPPING 最终 flights/admitted/leases 为 0，但此次运行由校验失败和 watchdog 结束，不能据此关闭既有排空缺陷。
- Tan's Huge Trees：BLOCKED。
