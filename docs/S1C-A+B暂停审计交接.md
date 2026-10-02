# S1C A+B 暂停审计交接

日期：2026-10-02，Asia/Shanghai。用户要求“现在什么情况了，不行停下来让审计看看”。
**已停止代码实现/测试/真实运行，未进入 S2-S5。**
生产代码检查点 `3a8d891`；随后仅保留未完成的边缘owner反例测试与报告/证据归档。
正式production OFF，Tan's Huge Trees BLOCKED，S1C未签收。

## 1. 当前结论

不是完成：两轮固定seed17真实运行均 `FAIL_BEFORE_APPLY`，退出码1，
`voxels=null`。没有 `real-wall-progress.json` 或“墙写出方块”证书。

入口代理高差门槛已替换，共享入口证书已接RootSearch及四类入口。
**实际施工证书 lowerFeetY=70，不是此前只读建议的68**。原庭院/门道是平面，
自然峰到70；以68铺平面会侵入自然峰，且负空间编译器拒绝凿峰。
本实现选择不改几何、不挖峰，从另一端外部天然69以一步差接入70。
这个实施差分必须审计，不能把原只读高度图结论直接当最终合同证明。

## 2. 两轮真实结果

### 第一轮：8f49e24，12:27:05～12:34:29

证据：`docs/evidence/s1c/entrance-ab-20261002/entrance-ab17-20261002-122705/`

- 固定vanilla seed17，新隔离目录，root `(206,67,953)`。
- recipe `343b565dae30b3f8c6e96504b72427f6f6afc1ef57dc73c5efed7a66eb0bf61c`。
- root REFERENCE列39272，maxBatch25.9201ms，over40=0。
- 当时入口证书lower70；随后`ground-exit apron not captured`，未APPLY。
- 自审发现证书3条路径复用2个外部体素；新增真红例并修一对一匹配，
  这份旧证书不作最终三宽验收。
- 后续关服watchdog；S1C-DRAIN-01仍未闭合。

### 第二轮：3a8d891，13:40:25～14:10:12

证据：`docs/evidence/s1c/entrance-ab-20261002/entrance-ab17-final-20261002-134025/`

- root仍 `(206,67,953)`，seed17未换、原0号关隘未跳。
- recipe `a28f489b5d0d6b1eb37586c050c81ef15b2a2108f797acdc5de633fa3da17499`。
- root列39272，maxBatch16.6182ms，over40=0。
- 实际frame centerQ8(52736,243968)、tx247565951、tz1044812234、W87。
- 实际DURABLE_T0证书lower70，证据hash
  `73ba05977804bf466acd7f4989068bfee165cb7ee5389eefde46072bb82d42b2`：
  `(181,69,957)→(182,70,957)`、
  `(182,69,958)→(183,70,958)`、
  `(182,69,959)→(183,70,959)`。
  三个外部位置独立，不再复用。
- `PRODUCTION_EXIT_READY=4`，之后14:09:10失败：
  **`T0_MISSING: owner not in realization evidence`**。
- 命令29m46s、exit1；voxels=null，仍无APPLY/进度文件。
- STOPPING flights=8/admitted=8，后续watchdog；不称正常排空。
- compiledAllocationPeak36,309,360、terrainAllocationPeak91,905,864字节，
  均为失败工作集数值，不冒充成功chunk成本/完整堆验收。

运行变慢有明确范围差异：以前fort0直接失败，这次已认证入口，连续区间出口
决议做了1597次PREPARE_FEATURES（包含重读）与1312次references；
仍只有首个请求owner执行链，**不是已完成505个owner**。
最终缺少owner覆盖的异常发生前，没有写墙验收数值。

## 3. 最新阻塞：事实与推理必须分开

**已验证事实**：

调用链：
`ProductionWallPipeline.compileAndApply:150` 在 `collected.own()==null` 拒绝；
`resolveCell:183` 从当前cell metadata取请求owner；
`loadCell:225` 只捕获 `CoveragePlanner.realizationOwners(cell,plans)` 的集合。
原日志没在异常里记录请求owner坐标，有限event窗口也不足以恢复它，
因此**确切失败owner坐标未查明**。

**源码推理/拟议修复，尚未实施**：

`cellsForChunk` 按196格halo和±192弧域给一个owner选cell；
`realizationOwners` 只含station±16和固定组件实际包络/apron。
远离组件的halo owner可能没有任何cell捕获它的metadata，即使该owner
最终体素写集为空，现有compile仍需要自己的真实耐久coverage。
建议锁外异步独立捕获请求owner并绑定coverage，再收集cell；
不能合成空coverage、删除null拒绝、关闭before-state或扩大writer权限。
需审计正确的capture时序/预算/pin/drain，**代码未改**。

新增GameTest
`fringeOwnerOutsideAllComponentReadsStillHasItsOwnDurableCoverage`
已保存为未完成红例：共4项，原3项通过，新项72.07秒/1,000,000tick超时，
并未得到`owner not in realization evidence`同异常。
不能声称已经定向复现根因；不加Disabled、不删反例，不改timeout以凑绿。

## 4. 已验证范围

| 批次 | 实际结果 |
|---|---|
| full-unit-native | JUnit360/360；新native类未登记导致命令exit1，native NOT_RUN |
| native-after-registration | 24/24原生，Gradle exit0；含原32高度/56塔与关隘入口/水门cap保护等 |
| apron-before | 捕获实际旋转构件guard/apron新测试红，旧覆盖测试绿 |
| distinct-apron-before | 2个外部格复用为3接入真红例 |
| apron-matching-after | 全量JUnit362/362、67 suites、零fail/error/skipped；native4项中1红（过大cap预读触64MiB） |
| apron-final | 12/12定向JUnit，4/4原生exit0；原32MiB compiled/64MiB terrain/pin冷重放断言不变 |
| fringe-owner-before | 4项原生，原3绿、新项tick超时；exit1，未完成反例 |
| real run1/run2 | 都exit1，无真实APPLY/voxels凭证 |

原生管线压力旁证（apron-final）：
8个owner APPLY、8次反向重放通过；测得heapPeak792,549,728字节，
terrainAllocationPeak39,254,144、compiledAllocationPeak31,024,160。
这是原生ProtoChunk＋解析计划，不是真实C2ME/成功chunk成本。

全量362测试证据在最终CoveragePlanner缩回原station窗口**之前**；
最终小变动新跑12项及4个native，不把之前全量重新写成最终源码fresh full PASS。
新增fringe测试是后来加入的失败WIP，当前全测试不能主张全绿。

## 5. 审计优先检查

1. **合同与实现差分**：用户原拟议“可选lower/自然路径”与当前
   “不改平面几何、取max、完整外侧一步接入”的范围是否一致。
   新补充合同写明max和平面限制；不能靠写文档替代用户需求。
2. **原只读结论撤销范围**：旧高度图把local格聚合并只做单列BFS，
   没验证三宽/真实净空/自然峰与平门道写集；不再主张lower68可写。
3. **单一方法≠完全相同证据**：RootSearch采样轴向frame，施工用首段实际切向；
   本例axis tx186784294/tz1057370858，施工tx247565951/tz1044812234。
   Root预检仍不是施工真frame/T0的完全等价证明，见下一项。
4. **natural与T0**：Root ClearSkyColumn只代表REFERENCE天然top以上空域，
   不含surface/features/实际注册盒的逐列数据；源标记区分NATURAL_REFERENCE/
   DURABLE_T0/ANALYTIC_FIXTURE，但不能把源标记当补足缺失证据。
5. **ComponentInput证据闭包**：旧构造器默认analytic，生产明确传T0；
   逐调用点检查没有在生产重建时丢证据。纯model新增依赖component
   是否符合原类型依赖边界，也应核验。
6. **一对一宽度证明**：确定性匹配外部列、不复用，自然过渡头空和整个
   declared face验证不等于已经完成任意曲地全通路证明。
7. **捕获读域**：CoveragePlanner只修实际模板盒与一列apron，
   station读域保持原±16；潜在cap读集完整性尚不能从本修订推成全PASS。
8. **owner耐久coverage**：最新异常真实，但确切owner坐标未知；
   先用低成本反例查清，不再直接重跑30分钟真实世界。
9. **资源预算**：工作表分配前预留、返回证书保留预算、临时表释放，
   不改S1B32/64MiB回归；ComponentInput analytic兼容分配亦应纳入审计。
10. **排空与误报**：S1C-DRAIN-01未修。14:09线程栈实际已在失败后关服，
    CPU活跃/worker等待不能证明生成进展。此前持续状态更新过强，现明确更正。

## 6. 提交/证据与停止点

生产提交bad0bc6、0da7a2b、deed883、07f52c8、2085d7f、8f49e24、
708e4ec、3a8d891；详见 `docs/S1C-A+B实施.md`。
最新待归档WIP只新增上述fringe测试，没有实现owner-capture修复。
两轮隔离原始manifest/cache/T0、日志、crash/diagnostics均完整保留。
测试脚本遇到UTC/本地和PowerShell自动DateTime转换问题，其原记录保留。
没有部署mods、打开真实存档、换seed或删水、改负空间权限。

审计期间停止运行/实现；恢复必须从本交接继续，不重做已绿基础。
S2-S5未进入，现有骨架不算接线完成。完整build/全慢矩阵、真实重进连续、
Tectonic/C2ME真实墙、成功成本、全植被家族、同JVM生命周期、
6000高/非默认minY/Golden、S0本次、S5/24h/人工/视觉仍NOT_RUN。
