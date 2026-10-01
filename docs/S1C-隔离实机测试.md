# S1C 隔离实机测试

状态：候选测试入口，非发布签收、非S5完整模组。正式生产OFF，Tan's BLOCKED。
当前候选版本0.1.0-s1c-preview.1；最终SHA见本批artifact证据，不拿旧包SHA作证。
已完成prepareWallPreview（exit0，14秒），未启动客户端；
候选Jar SHA256：`6308ef8a7e1f0c6bc68c62c2b9aa0f3f520075ff89c16e05f227cd7725f0a03d`。

## 准备与启动

工程目录：D:\云游四海-Mod源码\plaincraft-great-wall

```powershell
Set-Location -LiteralPath 'D:\云游四海-Mod源码\plaincraft-great-wall'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --dependency-verification=strict prepareWallPreview --no-daemon
.\gradlew.bat --offline --dependency-verification=strict runWallPreview --no-daemon
```

prepare只构建并准备隔离输入，不开启游戏。
run使用本地缓存的Minecraft1.21.1客户端、锁定Loader/API、remapJar，JDK21/8G。
不调用Loom资源下载器；缺本地assets直接拒绝，不联网补齐。
预览没有serverSmoke/clientSmoke模组，不会由烟雾测试入口自动关闭。

全部mods/config/saves只在：
D:\云游四海-Mod源码\plaincraft-great-wall\build\run\wallPreview\

入口自动写入本工程隔离目录的两个授权标记：
greatwall_test_world、greatwall_s1c_fixture。
JVM开关固定greatwall.s1c.productionFixture=true、greatwall.production=false、
greatwall.s0.probe=false；不为真实实例添加这些标记。

创建**新的创造模式世界**，不导入、不打开真实存档。
生产原入口冻结实际出生点并在800～1500环带选root；不是在出生脚下造墙。
创造模式便于飞行观察；/greatwall status可以查看当前root/recipe及失败状态。
选点完成后的根点坐标可从该测试世界greatwall/manifest/world.json读取，
再用原版/tp在该隔离世界定位。若root/采样失败，保留诊断，不声称有墙。

世界保存后可在**同一候选Jar/配置**下重进；变更Jar/有效配方后必须新建测试世界，
不可复用旧manifest或删永久文件强行补墙。
seed0/1的历史噪声首段存在早入水；新规则允许提前收头，不保证任意seed有1200格陆墙。
更不能把预览是否启动当作三profile连续墙/重进/植被/成本验收。

## 边界

人工客户端启动、真实墙可见、路线完整、重进和性能均需各自证据；
本文提供入口，不预写PASS。当前未交付S2道路、S3玩法/FTB、S4完整视觉、S5发布。
Tan's profile不能加入这个核心候选测试后冒称整包已认证。
不能将remapJar复制至真实mods；本任务未执行部署。
