# 长城开发文档

Minecraft 1.21.1 / Java 21 / Fabric，Gradle 9.4.1、Loom 1.16.3、Mojang 官方映射。

1. 在同级道路仓库执行 `-PapiOnly :api:contractJar :api-fabric:devModJar :api-fabric:contractIntermediaryJar`，不构建道路本体。自定义前置路径用 `-ProadweaverApiProjectDir=<路径>`。
2. 在同级河流工作区执行 `planContractJar`，为开发提供可选联动接口；玩家不必安装河流。
3. 用外置 Gradle 执行 `test compileClientJava remapJar runGameTest --daemon --console=plain`。可用 `-PgreatwallGameTestClasses=BiomeWriterGameTests` 单测群系组。使用已缓存依赖时可离线；依赖锁和校验元数据保留。

玩家用 `build/libs/plaincraft-great-wall-<版本>.jar` 和独立 RoadWeaver API。测试代码在 `src/test`、`src/gametest`、`src/clientSmoke` 和 `src/serverSmoke`；历史重放的四个最小夹具文件在 `src/test/fixtures`。本机 C2ME、FTB 和正式烟测仍依赖 `docs/evidence/s0-01/environment.json`，其余运行报告放到 `build/`。

[正式模板](正式模板生成.md)描述现行产物，[交接](../HANDOFF.md)记录每轮验证和未验收范围，[清理前 Git 快照](https://github.com/My-World-Traveling-the-World/PlainCraft-Great-Wall/tree/92ade7445194ba20ff0d104b5a2b6373/docs)可查旧报告。实机优先核对出生点双向覆盖、实物对接、短水门、宽水两岸、地基和群系；分别记录规划、实际落块和用时。
