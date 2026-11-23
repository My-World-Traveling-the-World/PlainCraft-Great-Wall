# 开发期本机依赖

独立 RoadWeaver API 的开发件与玩家包分别构建，不从道路玩法 JAR 抽取。构建顺序见[开发文档](../docs/DEVELOPMENT.md)。

`docs/evidence/s0-01/environment.json` 是部分兼容代码和正式烟测使用的本机输入。C2ME 的 Rx、FTB 系列和 Fabric API 由 Gradle 按路径和校验和读取；第三方 JAR 不进仓库。
