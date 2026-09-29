# S1B 补充合同

日期：2026-09-29。基线：80f2b24。仅补足实施规格没有编码值的细节，不改已签收 S1A 的日程、预算、几何或依赖版本。

## 第一批：栅格与破败

- `Rasterizer.supercover` 按闭线段覆盖，包括线段端点恰在网格线时接触的两侧格、角点接触的四格；结果按 y,z,x 排序。单次遍历轴向格差之和不得超过 262144，否则拒绝，避免不受限分配。构件最大 256 owner 限制仍独立生效。
- 变换使用整数/BigInteger 中间积；逆变换只对世界方块中心执行一次 Z 镜像。输出高度只加一次 W。
- `DecayField` 的格点噪声 domain 固定为 `decay-noise`，ordered fields 为 `(scaleBlocks, knotIndex)`；部分破败垛口/灯的整组 domain 为 `decay-group`，fields 为 `(floorDiv(globalSQ8,1024), componentHash)`。微缺口仍严格使用规格的 `decay-detail`。
- 维护影响用最近站点距离，输入顺序不影响结果。超出 48 格影响严格为零。所有方法无 Minecraft 依赖，没有全局可变缓存。
