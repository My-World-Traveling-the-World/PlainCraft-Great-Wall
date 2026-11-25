# 万里长城 · PlainCraft Great Wall

**在 Minecraft 越过山脊，沿城墙走进门楼和烽火台。**

`Minecraft 1.21.1` · `Fabric` · `Java 21+`

[安装](#安装) · [模组联动](#模组联动) · [版本更新](CHANGELOG.md) · [开发指南](HANDOFF.md)

| 功能 | 游戏里的体验 |
| --- | --- |
| 随地形延伸的城墙 | 普通世界沿规划路线生成城墙、马道和垛口，通过预留墙节连接楼体。 |
| 五种实物楼体 | 沿路可遇门楼、敌楼、烽火台、角楼和澄海楼。 |
| 烽火与战利品 | 烽火台参与烽火接力，楼内容器可提供战利品。 |
| 超平坦展区 | 近距离查看建筑套件、墙体剖面与模板接法。 |

## 安装

使用 **Fabric Loader 0.19.3+**，安装长城、Fabric API 0.116.14+1.21.1 或更高的 1.21.1 版本，以及[云游四海世界生成 API](https://github.com/My-World-Traveling-the-World/PlainCraft-Worldgen-API/releases)。游戏内配置界面需要 **Mod Menu 和 Cloth Config API**。

## 模组联动

安装对应SeedGen适配补丁时，世界生成API 1.2.0可提供已有模板墙楼的方块预览；实际补建完成后刷新真实远景。未缓存的计划保持未知，不为远景额外加载或采样。

长城可以独立生成。合装兼容版本时，河流先准备水系，长城随后预热并公布保护范围，道路据此避开墙体。三者的开局顺序为 **河流 → 长城 → 道路**，前置只装一份。[RoadWeaver 重制版](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered)
