# 固定格式兼容夹具

`legacy-v2-evidence.zip` 是以 `50ac545e` 的生产写入器生成的三份合成 v2 配套墙计划，包含负坐标区块。它不是玩家真实旧存档。ZIP 只为减少仓库体积，解压后仍由正式 `ChunkEvidenceStore` 读取原 `build-proof.bin`，不能在测试运行时重新生成。

各记录大小和 SHA256 见 `legacy-v2-evidence.json`。固定字节用于抓取“读写器同时改变后，往返测试仍绿”的格式退化；实际玩家旧 v2 已建/未建接缝、Minecraft 区块 NBT 升级与实体/光照仍需存档副本现场验证。更新生产算法不应顺手重录这组旧格式证据。
