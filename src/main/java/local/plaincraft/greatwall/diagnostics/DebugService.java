/* 文件职责：调试可视化服务 —— 向订阅玩家发送有界 debug 页与粒子，不改变世界。 */
package local.plaincraft.greatwall.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.BreakInterval;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.Priority;
import local.plaincraft.greatwall.model.WallTypes.SegmentPlan;
import local.plaincraft.greatwall.model.WallTypes.SiteRecord;
import local.plaincraft.greatwall.model.WallTypes.Station;
import local.plaincraft.greatwall.model.WallTypes.WaterEvidence;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.compat.road.RoadTypes;
import local.plaincraft.greatwall.persistence.GateLedger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;

/**
 * 调试可视化服务。每玩家一个整体替换的不可变订阅模式，OFF 移除，订阅上限 16。
 * tick 每 10 tick 用已有 immutable plan 与已加载数据构建至多 1 页 debug payload
 * 与 256 粒子；先经 {@code ServerPlayNetworking.canSend(TYPE)} 判定，不支持 payload
 * 时只发粒子。绝不因可视化而无限调度规划：只在窗口 future 已完成时渲染，否则跳过并释放。
 */
public final class DebugService implements AutoCloseable {

    /** 订阅视图。 */
    public enum View { ROUTE, STATIONS, SUPPORT, GATES, WATER, DECAY, OFF }

    private static final int PERMISSION = 2;
    private static final int MAX_SUBSCRIBERS = 16;
    private static final int TICK_INTERVAL = 10;
    private static final int RADIUS_CAP_BLOCKS = 128;
    private static final int MAX_PARTICLES = 256;

    // ARGB 颜色：白主干/黄 anchor/红计划断口/紫实际断口/绿门/青水/橙支撑。
    private static final int COLOR_MAIN = 0xFFFFFFFF;
    private static final int COLOR_ANCHOR = 0xFFFFC107;
    private static final int COLOR_PLANNED_BREAK = 0xFFFF0000;
    private static final int COLOR_REALIZED_BREAK = 0xFF9C27B0;
    private static final int COLOR_GATE = 0xFF00FF00;
    private static final int COLOR_WATER = 0xFF00FFFF;
    private static final int COLOR_SUPPORT = 0xFFFF9800;

    private final WorldServices services;
    private final ConcurrentHashMap<UUID, Subscription> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> revisions = new ConcurrentHashMap<>();
    private long tickCounter;
    private volatile boolean closed;

    /** 每玩家订阅：模式 + 玩家引用。 */
    private record Subscription(ServerPlayer player, View view) {
        Subscription {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(view, "view");
        }
    }

    public DebugService(WorldServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    public void subscribe(ServerPlayer player, View view) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(view, "view");
        if (closed) {
            return;
        }
        if (view == View.OFF) {
            subscribers.remove(player.getUUID());
            revisions.remove(player.getUUID());
            return;
        }
        if (player.hasPermissions(PERMISSION) && sameSession(player)) {
            if (!subscribers.containsKey(player.getUUID())
                    && subscribers.size() >= MAX_SUBSCRIBERS) {
                return;
            }
            subscribers.put(player.getUUID(), new Subscription(player, view));
            revisions.putIfAbsent(player.getUUID(), 0L);
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        if (++tickCounter % TICK_INTERVAL != 0) {
            return;
        }
        for (Subscription subscription : subscribers.values()) {
            tickPlayer(subscription);
        }
    }

    public void disconnected(UUID player) {
        Objects.requireNonNull(player, "player");
        subscribers.remove(player);
        revisions.remove(player);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        subscribers.clear();
        revisions.clear();
    }

    // ------------------------------------------------------------------
    // 私有实现
    // ------------------------------------------------------------------

    private boolean sameSession(ServerPlayer player) {
        WorldServices current = WorldServices.find(player.getServer());
        return current != null && current == services;
    }

    private void tickPlayer(Subscription subscription) {
        ServerPlayer player = subscription.player();
        if (player.isRemoved() || !sameSession(player)) {
            subscribers.remove(player.getUUID());
            revisions.remove(player.getUUID());
            return;
        }
        int radius = radiusBlocks(player);
        ChunkKey anchor = anchorChunk(player);
        PlannerScheduler planner = services.planning();
        PlanLease<PlanWindow> lease =
                planner.requireWindow(anchor, Math.min(radius, 512), Priority.BACKGROUND);
        try {
            if (!lease.future().isDone()) {
                // 无缓存：不阻塞、不无限排计划，本 tick 跳过。
                return;
            }
            PlanWindow window = lease.future().getNow(new PlanWindow(List.of()));
            DebugPayload payload = buildPayload(player, window, radius, subscription.view());
            if (payload != null && ServerPlayNetworking.canSend(player, DebugPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            } else {
                // 不支持 payload：只发粒子。
                spawnParticles(player, window, radius, subscription.view());
            }
        } finally {
            lease.close();
        }
    }

    private int radiusBlocks(ServerPlayer player) {
        int viewDistance = 0;
        try {
            viewDistance = player.getServer().getPlayerList().getViewDistance();
        } catch (RuntimeException ignored) {
            viewDistance = 0;
        }
        if (viewDistance <= 0) {
            viewDistance = 8;
        }
        return Math.min(RADIUS_CAP_BLOCKS, viewDistance * 16);
    }

    private ChunkKey anchorChunk(ServerPlayer player) {
        int x = (int) Math.floor(player.getX());
        int z = (int) Math.floor(player.getZ());
        return new ChunkKey(services.recipe().dimension(), FixedMath.chunk(x), FixedMath.chunk(z));
    }

    private DebugPayload buildPayload(ServerPlayer player, PlanWindow window, int radius,
            View view) {
        long revision = revisions.merge(player.getUUID(), 1L, Long::sum);
        int playerX = (int) Math.floor(player.getX());
        int playerZ = (int) Math.floor(player.getZ());
        List<DebugPayload.Line> lines = new ArrayList<>();
        List<DebugPayload.Box> boxes = new ArrayList<>();
        List<DebugPayload.Label> labels = new ArrayList<>();

        for (SegmentPlan plan : window.plans()) {
            switch (view) {
                case ROUTE -> addRoute(lines, plan, playerX, playerZ, radius);
                case STATIONS -> addStations(lines, labels, plan, playerX, playerZ, radius);
                case SUPPORT -> addSupport(lines, plan, playerX, playerZ, radius);
                case GATES -> addGates(boxes, labels, plan, playerX, playerZ, radius);
                case WATER -> addWater(boxes, labels, plan, playerX, playerZ, radius);
                case DECAY -> addDecay(labels, plan, playerX, playerZ, radius);
                case OFF -> { /* 不渲染 */ }
                default -> { /* 不渲染 */ }
            }
        }
        if (lines.isEmpty() && boxes.isEmpty() && labels.isEmpty()) {
            return null;
        }
        return new DebugPayload(services.session(), revision, 0, 1, lines, boxes, labels);
    }

    private boolean withinRadius(Point2q point, int playerX, int playerZ, int radius) {
        long dx = FixedMath.floorBlock(point.xQ8()) - playerX;
        long dz = FixedMath.floorBlock(point.zQ8()) - playerZ;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private void addRoute(List<DebugPayload.Line> lines, SegmentPlan plan, int px, int pz,
            int radius) {
        List<Point2q> points = plan.geometry().points();
        for (int i = 1; i < points.size(); i++) {
            Point2q a = points.get(i - 1);
            Point2q b = points.get(i);
            if (withinRadius(a, px, pz, radius) || withinRadius(b, px, pz, radius)) {
                lines.add(new DebugPayload.Line(
                        pointAt(a, plan), pointAt(b, plan), COLOR_MAIN));
            }
        }
    }

    private void addStations(List<DebugPayload.Line> lines, List<DebugPayload.Label> labels,
            SegmentPlan plan, int px, int pz, int radius) {
        for (Station station : plan.stations().stations()) {
            Point2q p = station.point();
            if (!withinRadius(p, px, pz, radius)) {
                continue;
            }
            Point3i center = new Point3i(FixedMath.floorBlock(p.xQ8()), station.walkY(),
                    FixedMath.floorBlock(p.zQ8()));
            lines.add(new DebugPayload.Line(
                    new Point3i(center.x(), center.y() - 1, center.z()),
                    new Point3i(center.x(), center.y() + 1, center.z()), COLOR_ANCHOR));
            labels.add(new DebugPayload.Label(center, "S=" + station.globalSQ8()));
        }
    }

    private void addSupport(List<DebugPayload.Line> lines, SegmentPlan plan, int px, int pz,
            int radius) {
        // 支撑图不在 SegmentPlan 中；用站点锚点以橙色近似标注，不伪造支撑拓扑。
        for (Station station : plan.stations().stations()) {
            Point2q p = station.point();
            if (!withinRadius(p, px, pz, radius)) {
                continue;
            }
            Point3i center = new Point3i(FixedMath.floorBlock(p.xQ8()), station.walkY(),
                    FixedMath.floorBlock(p.zQ8()));
            lines.add(new DebugPayload.Line(
                    new Point3i(center.x() - 1, center.y(), center.z() - 1),
                    new Point3i(center.x() + 1, center.y(), center.z() + 1), COLOR_SUPPORT));
        }
    }

    private void addGates(List<DebugPayload.Box> boxes, List<DebugPayload.Label> labels,
            SegmentPlan plan, int px, int pz, int radius) {
        GateLedger ledger = services.gates();
        for (RoadTypes.GateRecord record : ledger.activeSnapshot().values()) {
            int gx = FixedMath.floorBlock(record.contract().frame().center().xQ8());
            int gz = FixedMath.floorBlock(record.contract().frame().center().zQ8());
            long dx = gx - px;
            long dz = gz - pz;
            if (dx * dx + dz * dz > (long) radius * radius) {
                continue;
            }
            int gy = record.contract().groundY();
            int halfW = record.contract().apertureWidth() / 2;
            int halfH = record.contract().apertureHeight() / 2;
            boxes.add(new DebugPayload.Box(new Bounds(gx - halfW, gy, gz - halfW,
                    gx + halfW + 1, gy + record.contract().apertureHeight(), gz + halfW + 1),
                    COLOR_GATE));
            labels.add(new DebugPayload.Label(
                    new Point3i(gx, gy + halfH, gz),
                    record.contract().id().namespace() + ":" + record.contract().id().ordinal()));
        }
    }

    private void addWater(List<DebugPayload.Box> boxes, List<DebugPayload.Label> labels,
            SegmentPlan plan, int px, int pz, int radius) {
        for (WaterEvidence evidence : plan.geometry().water()) {
            long cx = FixedMath.floorBlock(evidence.crossing().fromQ8());
            int x = (int) cx; // 粗略：以 crossing fromQ8 的 x 分量定位需投影，此处用站点近似。
            // 无逐点投影时，跳过不在半径内的水证据，避免编造坐标。
            // 水证据只带 ArcInterval 与岸边/桥墩点，用 bank 点近似中心。
            if (evidence.banks().isEmpty()) {
                continue;
            }
            Point3i bank = evidence.banks().get(0);
            long dx = bank.x() - px;
            long dz = bank.z() - pz;
            if (dx * dx + dz * dz > (long) radius * radius) {
                continue;
            }
            boxes.add(new DebugPayload.Box(new Bounds(bank.x(), evidence.waterTopY() - 1,
                    bank.z(), bank.x() + 1, evidence.waterTopY() + 1, bank.z() + 1),
                    COLOR_WATER));
            labels.add(new DebugPayload.Label(new Point3i(bank.x(), evidence.waterTopY(),
                    bank.z()), "water " + evidence.classification()));
        }
    }

    private void addDecay(List<DebugPayload.Label> labels, SegmentPlan plan, int px, int pz,
            int radius) {
        // 衰减状态需要 SiteRuntimeIndex.realized(SiteId)；该索引无枚举接口，
        // 无法在无具体 SiteId 时列出衰减。此处不渲染，避免伪造。
    }

    private Point3i pointAt(Point2q p, SegmentPlan plan) {
        // walkY 无逐点值；用段首站点高度近似，仅用于可视化，不做施工验收。
        int walkY = plan.stations().stations().isEmpty()
                ? 0 : plan.stations().stations().get(0).walkY();
        return new Point3i(FixedMath.floorBlock(p.xQ8()), walkY, FixedMath.floorBlock(p.zQ8()));
    }

    private void spawnParticles(ServerPlayer player, PlanWindow window, int radius, View view) {
        int budget = MAX_PARTICLES;
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        for (SegmentPlan plan : window.plans()) {
            if (budget <= 0) {
                break;
            }
            List<Point2q> points = switch (view) {
                case ROUTE -> plan.geometry().points();
                default -> plan.stations().stations().stream().map(Station::point).toList();
            };
            for (Point2q p : points) {
                if (budget <= 0) {
                    break;
                }
                if (!withinRadius(p, px, pz, radius)) {
                    continue;
                }
                spawnParticle(player, p);
                budget--;
            }
        }
    }

    private void spawnParticle(ServerPlayer player, Point2q p) {
        int walkY = player.blockPosition().getY();
        double x = FixedMath.floorBlock(p.xQ8()) + 0.5;
        double z = FixedMath.floorBlock(p.zQ8()) + 0.5;
        // 逐玩家粒子重载（MC 1.21.1 named-mapped 已 javap 确认存在）：
        // ServerLevel.sendParticles(ServerPlayer, ParticleOptions, boolean, double,double,double,int,double,double,double,double)。
        player.serverLevel().sendParticles(player, ParticleTypes.END_ROD, false,
                x, walkY + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
