# 长城启动契约开发交接

## 2026-11-16：第34轮，订阅及就绪守卫

### 一、改了什么

第一步为行为等价端口搬迁：RoadWeaverLifecycleBridge 的公开 register 仍通知 WorldServices，内部可注入会话通知；GreatWallReadinessProvider 默认仍读取 WorldServices，内部可注入等待和快照读口。未改 API 签名、生成判据、几何、配置及持久化格式。补入本地 API 开发件的 testRuntimeClasspath 锁坐标一条，未删除其他依赖锁。

### 二、我验证了什么

开工01408e65干净，当前分支refactor/planning-frozen-feature-paint，无upstream；显式 git pull --rebase origin refactor/planning-frozen-feature-paint 已确认无需更新。原始 testRuntimeClasspath 因缺本地重映射API锁坐标失败；定向更新后基线745项，failures/errors/skipped均0，BUILD SUCCESSFUL。生成锁文件会移除未解析配置的旧归属，本轮仅保留新增那一条坐标。端口搬迁后既有Prewarm系列测试及compileJava通过，日志build-round34-ports.log。

### 三、我没能验证什么

尚未补新守卫及负控。未运行Minecraft、未部署；真实新建/重开/缺peer时序、ServerLevel故障、帧时间和加载速度均未验证。端口不证明三模组最终Mixin执行顺序。

### 四、遗留半截

本次端口搬迁可编译。下一步补新守卫及变异；严格闸门在clear释放旧闩锁时是否误报ready待实测。不能把端口搬迁称为此任务已全部完成。

### 五、下一步

用真实RiverReadinessApi测试长城订阅与晚订阅，验证完成/失败及签名排除时钟。增加世界重开严格闸门回归，先复现再修复。完成后同步道路docs/HANDOFF.md。

### 六、已解决的坑

原分支未配置upstream，显式拉取同名远端分支；不擅自切main。长城已有Windows classpath桥接脚本，测试不需要照搬河流的入口改造。定向--tests必须紧接test任务，放compileJava之后会被解析为编译任务的未知选项。
