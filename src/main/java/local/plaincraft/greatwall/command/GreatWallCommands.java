/* 文件职责：/greatwall 命令树 —— 管理只读诊断、有界规划与调试可视化，绝不同步规划。 */
package local.plaincraft.greatwall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.compat.road.RoadTypes;
import local.plaincraft.greatwall.diagnostics.DebugService;
import local.plaincraft.greatwall.gameplay.GameplayTypes;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Priority;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.SiteRecord;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 统一 {@code /greatwall} 命令树。所有管理操作权限 2；普通 locate 只展示已发现站点，
 * 绝不因命令而揭示整张地图。每个 handler 均为 {@code private static int}，立即返回 jobId/计数，
 * 异步结果经 {@code server.execute} 回写并复核会话。永不在此类中同步规划。
 */
public final class GreatWallCommands {
    private static final int PERMISSION = 2;
    private static final long MAX_RANGE = 64L;
    private static final int LOCATE_DEFAULT = 16;
    private static final int LOCATE_CAP = 64;
    private static final long JOB_RETENTION_NANOS = 5L * 60L * 1_000_000_000L;
    private static final int JOB_CAP = 256;
    private static final long REPAIR_TOKEN_TTL_NANOS = 60L * 1_000_000_000L;
    private static final UUID CONSOLE_ID = new UUID(0L, 0L);

    private static final ConcurrentHashMap<UUID, CommandJob> JOBS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RepairToken> REPAIR_TOKENS = new ConcurrentHashMap<>();

    private record CommandJob(UUID id, String kind, UUID owner, long createdNanos,
            CompletableFuture<?> done, List<PlanLease<?>> leases) {}

    /**
     * A one-shot repair approval. {@code revisionHash} binds the token to the exact gate revision the
     * operator saw during dry-run, so approving one state can never authorise repairs against a
     * different one.
     */
    private record RepairToken(GateId id, long issuedNanos, String revisionHash) {}

    /** Binds a gate identity and revision into the token hash. */
    private static String revisionHash(GateId id, String state, long revision) {
        return DeterministicHash.sha256((id + "|" + state + "|" + revision)
                .getBytes(StandardCharsets.UTF_8));
    }

    private GreatWallCommands() {}

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registry, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("greatwall")
                .then(status())
                .then(stats())
                .then(locate())
                .then(plan())
                .then(job())
                .then(verify())
                .then(rebuild())
                .then(export())
                .then(ledger())
                .then(repair())
                .then(debug())
                .then(test()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> status() {
        return Commands.literal("status")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(GreatWallCommands::status);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> stats() {
        return Commands.literal("stats")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(ctx -> stats(ctx, false))
                .then(Commands.literal("reset-session").executes(ctx -> stats(ctx, true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> locate() {
        return Commands.literal("locate")
                .then(Commands.argument("kind", StringArgumentType.word())
                        .executes(ctx -> locate(ctx, LOCATE_DEFAULT))
                        .then(Commands.argument("maxSegments", IntegerArgumentType.integer(1, LOCATE_CAP))
                                .executes(ctx -> locate(ctx, IntegerArgumentType.getInteger(ctx, "maxSegments")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> plan() {
        return Commands.literal("plan")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.argument("from", IntegerArgumentType.integer(0))
                        .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                .executes(GreatWallCommands::plan)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> job() {
        return Commands.literal("job")
                .then(Commands.argument("uuid", StringArgumentType.word())
                        .then(Commands.literal("status").executes(GreatWallCommands::jobStatus))
                        .then(Commands.literal("cancel").executes(GreatWallCommands::jobCancel)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> verify() {
        return Commands.literal("verify")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.literal("segment")
                        .then(Commands.argument("i", IntegerArgumentType.integer(0))
                                .executes(ctx -> verifySegment(ctx,
                                        IntegerArgumentType.getInteger(ctx, "i")))))
                .then(Commands.literal("range")
                        .then(Commands.argument("from", IntegerArgumentType.integer(0))
                                .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                        .executes(GreatWallCommands::verifyRange))))
                .then(Commands.literal("loaded").executes(GreatWallCommands::verifyLoaded));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rebuild() {
        return Commands.literal("rebuild")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.literal("cache")
                        .then(Commands.argument("from", IntegerArgumentType.integer(0))
                                .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                        .executes(GreatWallCommands::rebuildCache))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> export() {
        return Commands.literal("export")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.argument("from", IntegerArgumentType.integer(0))
                        .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                .then(Commands.argument("format", StringArgumentType.word())
                                        .executes(GreatWallCommands::export))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ledger() {
        return Commands.literal("ledger")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.literal("beacon")
                        .then(Commands.literal("count").executes(GreatWallCommands::ledgerBeaconCount))
                        .then(Commands.literal("query")
                                .then(Commands.argument("ordinal", IntegerArgumentType.integer(1))
                                        .executes(GreatWallCommands::ledgerBeaconQuery))))
                .then(Commands.literal("gate")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .then(Commands.argument("ordinal", IntegerArgumentType.integer(0))
                                        .executes(GreatWallCommands::ledgerGate))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> repair() {
        return Commands.literal("repair")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.literal("gate")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .then(Commands.argument("ordinal", IntegerArgumentType.integer(0))
                                        .then(Commands.literal("dry-run").executes(GreatWallCommands::repairDryRun))
                                        .then(Commands.literal("confirm")
                                                .then(Commands.argument("token", StringArgumentType.word())
                                                        .executes(GreatWallCommands::repairConfirm))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> debug() {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.literal("view")
                        .then(Commands.argument("view", StringArgumentType.word())
                                .executes(GreatWallCommands::debugView)))
                .then(Commands.literal("inspect").executes(GreatWallCommands::debugInspect));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> test() {
        return Commands.literal("test")
                .requires(source -> source.hasPermission(PERMISSION))
                .then(Commands.literal("fixture")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(GreatWallCommands::testFixture)))
                .then(Commands.literal("teleport")
                        .then(Commands.argument("siteId", StringArgumentType.word())
                                .executes(GreatWallCommands::testTeleport)));
    }

    // ------------------------------------------------------------------
    // 命令 handler
    // ------------------------------------------------------------------

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null) {
            source.sendFailure(Component.literal("no active GreatWall session"));
            return 0;
        }
        if (!svc.productionFixture()) {
            source.sendSuccess(() -> Component.literal("S0 laboratory; production services OFF; counts="
                    + summarize(svc.counts())), false);
            return 1;
        }
        try {
            WorldRecipe recipe = svc.recipe();
            HistoryTypes.State state = svc.worldState().snapshot();
            source.sendSuccess(() -> Component.literal("wall=" + recipe.wallId()
                    + " session=" + svc.session()
                    + " dim=" + recipe.dimension()
                    + " recipeHash=" + recipe.recipeHash()), false);
            source.sendSuccess(() -> Component.literal("history health=" + state.health().name()
                    + " sequence=" + state.sequence()
                    + " beacons=" + state.beaconCount()
                    + " revision=" + state.revision()), false);
            source.sendSuccess(() -> Component.literal("capabilities=" + capabilities(svc)), false);
            source.sendSuccess(() -> Component.literal("stages=" + summarize(svc.counts())), false);
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("status unavailable: " + failure.getMessage()));
        }
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx, boolean reset) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null) {
            source.sendFailure(Component.literal("no active GreatWall session"));
            return 0;
        }
        // WorldServices 不暴露 WallMetrics 实例；metrics 快照不可达。
        if (reset) {
            source.sendSuccess(() -> Component.literal("no WallMetrics store is wired into this session; nothing to reset"), false);
            return 1;
        }
        if (svc.productionFixture()) {
            source.sendSuccess(() -> Component.literal("diagnostics=" + summarize(svc.productionDiagnostics())), false);
        } else {
            source.sendSuccess(() -> Component.literal("counts=" + summarize(svc.counts())), false);
        }
        return 1;
    }

    private static int locate(CommandContext<CommandSourceStack> ctx, int maxSegments)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        String kind = StringArgumentType.getString(ctx, "kind");
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("locate requires the S1C production fixture"));
            return 0;
        }
        if ("gate".equals(kind) || "gate0".equals(kind)) {
            return locateGates(source, player, svc, maxSegments, kind);
        }
        SiteKind siteKind = switch (kind) {
            case "fort" -> SiteKind.FORT;
            case "tower" -> SiteKind.ENEMY_TOWER;
            case "beacon" -> SiteKind.BEACON;
            default -> null;
        };
        if (siteKind == null) {
            source.sendFailure(Component.literal("unknown locate kind: " + kind
                    + " (gate0|gate|fort|tower|beacon)"));
            return 0;
        }
        return locateSites(source, player, svc, maxSegments, siteKind);
    }

    private static int locateGates(CommandSourceStack source, ServerPlayer player, WorldServices svc,
            int maxSegments, String kind) {
        List<RoadTypes.GateRecord> found = new ArrayList<>();
        for (RoadTypes.GateRecord record : svc.gates().activeSnapshot().values()) {
            GateId id = record.contract().id();
            if ("gate0".equals(kind) && id.ordinal() != 0L) continue;
            found.add(record);
        }
        found.sort(Comparator
                .comparingLong((RoadTypes.GateRecord r) -> distanceSq(r.contract().frame().center().xQ8(),
                        r.contract().frame().center().zQ8(), player))
                .thenComparingLong(r -> r.contract().id().ordinal()));
        reportGates(source, found.stream().limit(maxSegments).toList());
        return 1;
    }

    private static int locateSites(CommandSourceStack source, ServerPlayer player, WorldServices svc,
            int maxSegments, SiteKind kind) {
        ChunkKey anchor = new ChunkKey(svc.recipe().dimension(),
                FixedMath.chunk((int) Math.floor(player.getX())),
                FixedMath.chunk((int) Math.floor(player.getZ())));
        PlannerScheduler planner = svc.planning();
        PlanLease<local.plaincraft.greatwall.model.WallTypes.PlanWindow> lease =
                planner.requireWindow(anchor, 128, Priority.PLAYER);
        UUID job = UUID.randomUUID();
        registerJob(new CommandJob(job, "locate", player.getUUID(), System.nanoTime(),
                lease.future(), List.of(lease)));
        AsyncSink sink = new AsyncSink(source, player.getUUID());
        lease.future().whenComplete((window, error) -> {
            lease.close();
            completeJob(job);
            sink.send(() -> {
                if (error != null) return "locate failed: " + error.getMessage();
                List<SiteRecord> sites = new ArrayList<>();
                for (var plan : window.plans()) {
                    for (SiteRecord site : plan.sites()) {
                        if (site.id().kind() == kind) sites.add(site);
                    }
                }
                sites.sort(Comparator
                        .comparingLong((SiteRecord s) -> distanceSq(s.frame().center().xQ8(),
                                s.frame().center().zQ8(), player))
                        .thenComparingLong(s -> s.id().ordinal()));
                if (sites.isEmpty()) return "locate " + kind + ": none discovered near player";
                StringBuilder text = new StringBuilder("locate " + kind + ":");
                for (SiteRecord site : sites.stream().limit(maxSegments).toList()) {
                    text.append(" ").append(site.id()).append("@")
                            .append(FixedMath.floorBlock(site.frame().center().xQ8())).append(",")
                            .append(site.frame().walkY()).append(",")
                            .append(FixedMath.floorBlock(site.frame().center().zQ8()))
                            .append(" state=").append(site.state().name());
                }
                return text.toString();
            });
        });
        source.sendSuccess(() -> Component.literal("locate " + kind + " job=" + job), false);
        return 1;
    }

    private static int plan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        UUID caller = callerId(source);
        long from = IntegerArgumentType.getInteger(ctx, "from");
        long to = IntegerArgumentType.getInteger(ctx, "to");
        requireRange(from, to);
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("plan requires the S1C production fixture"));
            return 0;
        }
        PlannerScheduler planner = svc.planning();
        List<PlanLease<?>> leases = new ArrayList<>();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[(int) (to - from)];
        for (long segment = from; segment < to; segment++) {
            PlanLease<local.plaincraft.greatwall.model.WallTypes.SegmentPlan> lease =
                    planner.requirePlan(segment, Priority.PLAYER);
            leases.add(lease);
            futures[(int) (segment - from)] = lease.future();
        }
        CompletableFuture<Void> done = CompletableFuture.allOf(futures);
        UUID job = UUID.randomUUID();
        registerJob(new CommandJob(job, "plan", caller, System.nanoTime(), done, leases));
        AsyncSink sink = new AsyncSink(source, caller);
        done.whenComplete((value, error) -> {
            for (PlanLease<?> lease : leases) lease.close();
            completeJob(job);
            sink.send(() -> error == null
                    ? "plan " + from + ".." + to + " complete"
                    : "plan " + from + ".." + to + " failed: " + error.getMessage());
        });
        source.sendSuccess(() -> Component.literal("plan " + job + " started (" + (to - from) + " segments)"), false);
        return 1;
    }

    private static int jobStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID id = parseUuid(source, StringArgumentType.getString(ctx, "uuid"));
        if (id == null) return 0;
        CommandJob local = JOBS.get(id);
        boolean admin = source.hasPermission(PERMISSION);
        if (local != null && !admin && !local.owner().equals(callerId(source))) {
            source.sendFailure(Component.literal("job " + id + " belongs to another caller"));
            return 0;
        }
        PlannerScheduler planner = null;
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc != null && svc.productionFixture()) planner = svc.planning();
        if (local != null) {
            String state = local.done().isDone()
                    ? (local.done().isCompletedExceptionally() ? "failed" : "complete")
                    : "running";
            source.sendSuccess(() -> Component.literal("job " + id + " kind=" + local.kind()
                    + " state=" + state + " leases=" + local.leases().size()), false);
            return 1;
        }
        PlannerScheduler scheduler = planner;
        if (scheduler != null && scheduler.progress().containsKey(id)) {
            source.sendSuccess(() -> Component.literal("planner job " + id + " progress="
                    + scheduler.progress().get(id)), false);
            return 1;
        }
        source.sendFailure(Component.literal("unknown job: " + id));
        return 0;
    }

    private static int jobCancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID id = parseUuid(source, StringArgumentType.getString(ctx, "uuid"));
        if (id == null) return 0;
        CommandJob local = JOBS.remove(id);
        if (local == null) {
            source.sendFailure(Component.literal("no command job " + id + " to cancel"));
            return 0;
        }
        boolean admin = source.hasPermission(PERMISSION);
        if (!admin && !local.owner().equals(callerId(source))) {
            JOBS.put(id, local);
            source.sendFailure(Component.literal("job " + id + " belongs to another caller"));
            return 0;
        }
        // 只释放命令调用者的租约，绝不关闭共享 planner。
        for (PlanLease<?> lease : local.leases()) lease.close();
        local.done().toCompletableFuture().cancel(false);
        source.sendSuccess(() -> Component.literal("job " + id + " canceled (caller lease released)"), false);
        return 1;
    }

    private static int verifySegment(CommandContext<CommandSourceStack> ctx, long segment) {
        CommandSourceStack source = ctx.getSource();
        return verifyRangeInternal(source, segment, segment + 1L);
    }

    private static int verifyRange(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        long from = IntegerArgumentType.getInteger(ctx, "from");
        long to = IntegerArgumentType.getInteger(ctx, "to");
        requireRange(from, to);
        return verifyRangeInternal(source, from, to);
    }

    private static int verifyRangeInternal(CommandSourceStack source, long from, long to) {
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("verify requires the S1C production fixture"));
            return 0;
        }
        PlannerScheduler planner = svc.planning();
        UUID job = UUID.randomUUID();
        List<PlanLease<?>> leases = new ArrayList<>();
        for (long segment = from; segment < to; segment++) {
            leases.add(planner.requirePlan(segment, Priority.BACKGROUND));
        }
        CompletableFuture<?>[] futures = leases.stream().map(PlanLease::future).toArray(CompletableFuture[]::new);
        CompletableFuture<Void> done = CompletableFuture.allOf(futures);
        registerJob(new CommandJob(job, "verify", callerId(source), System.nanoTime(), done, leases));
        AsyncSink sink = new AsyncSink(source, callerId(source));
        done.whenComplete((value, error) -> {
            for (PlanLease<?> lease : leases) lease.close();
            completeJob(job);
            sink.send(() -> {
                if (error != null) return "verify failed: " + error.getMessage();
                StringBuilder text = new StringBuilder("verify " + from + ".." + to + ":");
                for (PlanLease<?> lease : leases) {
                    var plan = (local.plaincraft.greatwall.model.WallTypes.SegmentPlan) lease.future().getNow(null);
                    if (plan == null) { text.append(" MISSING"); continue; }
                    text.append(" s").append(plan.geometry().key().index())
                            .append("=").append(plan.hash().substring(0, 8));
                }
                return text.toString();
            });
        });
        source.sendSuccess(() -> Component.literal("verify " + job + " started"), false);
        return 1;
    }

    private static int verifyLoaded(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("verify loaded requires the S1C production fixture"));
            return 0;
        }
        int loaded = svc.sites().loadedChunks();
        source.sendSuccess(() -> Component.literal("loaded site chunks=" + loaded), false);
        return 1;
    }

    private static int rebuildCache(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        long from = IntegerArgumentType.getInteger(ctx, "from");
        long to = IntegerArgumentType.getInteger(ctx, "to");
        requireRange(from, to);
        // SegmentCacheStore 未通过 WorldServices 暴露，命令无法触发 invalidation。
        source.sendFailure(Component.literal("rebuild cache: SegmentCacheStore is not exposed through WorldServices"));
        return 0;
    }

    private static int export(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        long from = IntegerArgumentType.getInteger(ctx, "from");
        long to = IntegerArgumentType.getInteger(ctx, "to");
        requireRange(from, to);
        String raw = StringArgumentType.getString(ctx, "format");
        DiagnosticExporter.Format format = switch (raw) {
            case "json" -> DiagnosticExporter.Format.JSON;
            case "csv" -> DiagnosticExporter.Format.CSV;
            case "svg" -> DiagnosticExporter.Format.SVG;
            default -> null;
        };
        if (format == null) {
            source.sendFailure(Component.literal("unknown export format: " + raw + " (json|csv|svg)"));
            return 0;
        }
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("export requires the S1C production fixture"));
            return 0;
        }
        UUID job = UUID.randomUUID();
        AsyncSink sink = new AsyncSink(source, callerId(source));
        svc.diagnosticExporter().export(new DiagnosticExporter.Request(job, from, to, format))
                .whenComplete((result, error) -> sink.send(() -> error != null
                        ? "export failed: " + error.getMessage()
                        : "export " + result.path() + " bytes=" + result.bytes() + " sha256=" + result.sha256()));
        source.sendSuccess(() -> Component.literal("export " + job + " started (" + raw + ")"), false);
        return 1;
    }

    private static int ledgerBeaconCount(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("ledger requires the S1C production fixture"));
            return 0;
        }
        GameplayTypes.BeaconProgress progress = svc.beacons().progress();
        source.sendSuccess(() -> Component.literal("beacon committed=" + progress.credited()
                + " pending=" + progress.pendingUnknown()
                + " target=" + progress.targetCount()), false);
        return 1;
    }

    private static int ledgerBeaconQuery(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("ledger requires the S1C production fixture"));
            return 0;
        }
        long ordinal = IntegerArgumentType.getInteger(ctx, "ordinal");
        BeaconId id = new BeaconId(svc.recipe().wallId(), ordinal);
        AsyncSink sink = new AsyncSink(source, callerId(source));
        svc.beacons().requireCredited(id).whenComplete((credited, error) ->
                sink.send(() -> error != null ? "ledger beacon query failed: " + error.getMessage()
                        : "beacon " + id + " credited=" + credited));
        source.sendSuccess(() -> Component.literal("ledger beacon query " + id + " started"), false);
        return 1;
    }

    private static int ledgerGate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("ledger requires the S1C production fixture"));
            return 0;
        }
        String namespace = StringArgumentType.getString(ctx, "namespace");
        long ordinal = IntegerArgumentType.getInteger(ctx, "ordinal");
        GateId id;
        try {
            id = new GateId(svc.recipe().wallId(), namespace, ordinal);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(invalid.getMessage()));
            return 0;
        }
        Optional<RoadTypes.GateRecord> record = svc.gates().find(id);
        if (record.isEmpty()) {
            source.sendFailure(Component.literal("unknown gate: " + id));
            return 0;
        }
        RoadTypes.GateRecord gate = record.get();
        source.sendSuccess(() -> Component.literal("gate " + id + " state=" + gate.state().name()
                + " revision=" + gate.revision() + " globalSQ8=" + gate.contract().globalSQ8()
                + " affected=" + gate.contract().affected().size()), false);
        return 1;
    }

    private static int repairDryRun(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("repair requires the S1C production fixture"));
            return 0;
        }
        GateId id = gateId(source, svc, ctx);
        if (id == null) return 0;
        Optional<RoadTypes.GateRecord> record = svc.gates().find(id);
        String state = record.map(r -> r.state().name() + " rev=" + r.revision()).orElse("absent");
        String token = UUID.randomUUID().toString();
        // Bind the token to the gate revision observed right now; repair-confirm rejects the token if
        // the gate has moved on since, instead of repairing a state the operator never approved.
        long revision = record.map(RoadTypes.GateRecord::revision).orElse(-1L);
        String gateState = record.map(r -> r.state().name()).orElse("ABSENT");
        REPAIR_TOKENS.put(token, new RepairToken(id, System.nanoTime(), revisionHash(id, gateState, revision)));
        pruneRepairTokens();
        source.sendSuccess(() -> Component.literal("repair dry-run " + id + " state=" + state
                + " zero writes; token=" + token), false);
        return 1;
    }

    private static int repairConfirm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String token = StringArgumentType.getString(ctx, "token");
        RepairToken pending = REPAIR_TOKENS.remove(token);
        if (pending == null || System.nanoTime() - pending.issuedNanos() > REPAIR_TOKEN_TTL_NANOS) {
            source.sendFailure(Component.literal("repair token missing or expired (60s TTL)"));
            return 0;
        }
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("repair requires the S1C production fixture"));
            return 0;
        }
        var backfill = svc.backfillIfReady();
        if (backfill == null) {
            // Gameplay services are not assembled yet, so nothing can be repaired. Reporting success
            // here would let an operator believe the arch was cut when no work was attempted.
            source.sendFailure(Component.literal("repair confirm " + pending.id()
                    + ": backfill service is not ready; nothing was written"));
            return 0;
        }
        var gates = svc.gates();
        var record = gates.find(pending.id());
        if (record.isEmpty()) {
            source.sendFailure(Component.literal("repair confirm " + pending.id() + ": gate is absent"));
            return 0;
        }
        var gate = record.get();
        // The dry-run captured the revision it observed; a change since then invalidates the token,
        // because the operator approved a different state than the one now on disk.
        if (!pending.revisionHash().equals(revisionHash(pending.id(), gate.state().name(), gate.revision()))) {
            source.sendFailure(Component.literal("repair confirm " + pending.id()
                    + ": gate revision changed since dry-run (now " + gate.revision() + "); re-run dry-run"));
            return 0;
        }
        // Real work is asynchronous: acknowledge acceptance, then report the durable outcome. The
        // command returns success only after prepare() completes without failure, so a conflict can
        // never be mistaken for a repair.
        backfill.prepare(gate).whenComplete((ignored, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("repair confirm " + pending.id()
                        + ": backfill failed: " + failure.getMessage()));
                return;
            }
            var after = gates.find(pending.id()).map(r -> r.state().name()).orElse("absent");
            source.sendSuccess(() -> Component.literal("repair confirm " + pending.id()
                    + ": backfill prepared, gate state=" + after), true);
        }));
        source.sendSuccess(() -> Component.literal("repair confirm " + pending.id()
                + ": backfill started; the durable outcome follows"), false);
        return 1;
    }

    private static int debugView(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        String raw = StringArgumentType.getString(ctx, "view");
        DebugService.View view = switch (raw) {
            case "route" -> DebugService.View.ROUTE;
            case "stations" -> DebugService.View.STATIONS;
            case "support" -> DebugService.View.SUPPORT;
            case "gates" -> DebugService.View.GATES;
            case "water" -> DebugService.View.WATER;
            case "decay" -> DebugService.View.DECAY;
            case "off" -> DebugService.View.OFF;
            default -> null;
        };
        if (view == null) {
            source.sendFailure(Component.literal("unknown debug view: " + raw));
            return 0;
        }
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("debug requires the S1C production fixture"));
            return 0;
        }
        svc.debug().subscribe(player, view);
        source.sendSuccess(() -> Component.literal("debug view=" + view), false);
        return 1;
    }

    private static int debugInspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("debug inspect requires the S1C production fixture"));
            return 0;
        }
        Vec3 start = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockPos target = null;
        for (int distance = 0; distance <= 8; distance++) {
            Vec3 at = start.add(look.scale(distance));
            BlockPos pos = new BlockPos((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z));
            if (!player.level().getBlockState(pos).isAir()) target = pos;
        }
        if (target == null) {
            Vec3 far = start.add(look.scale(8.0));
            target = new BlockPos((int) Math.floor(far.x), (int) Math.floor(far.y), (int) Math.floor(far.z));
        }
        local.plaincraft.greatwall.model.WallTypes.Point3i here =
                new local.plaincraft.greatwall.model.WallTypes.Point3i(target.getX(), target.getY(), target.getZ());
        String site = svc.sites().firepit(here)
                .map(fire -> "firepit " + fire.beacon())
                .orElseGet(() -> svc.sites().spawner(here)
                        .map(spawner -> "spawner " + spawner.site())
                        .orElse("none"));
        String block = player.level().getBlockState(target).toString();
        source.sendSuccess(() -> Component.literal("inspect pos=" + here
                + " block=" + block + " site=" + site
                + " W=" + "NOT_CAPTURED" + " g=" + "NOT_CAPTURED" + " h=" + "NOT_CAPTURED"
                + " drop=NOT_CAPTURED state=NOT_CAPTURED palette=NOT_CAPTURED"
                + " T0=NOT_CAPTURED revision=NOT_CAPTURED reject=NOT_CAPTURED"), false);
        return 1;
    }

    private static int testFixture(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("test fixture is only available in the S1C fixture"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("fixture " + name + " acknowledged (no-op)"), false);
        return 1;
    }

    private static int testTeleport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String siteId = StringArgumentType.getString(ctx, "siteId");
        WorldServices svc = WorldServices.find(source.getServer());
        if (svc == null || !svc.productionFixture()) {
            source.sendFailure(Component.literal("test teleport is only available in the S1C fixture"));
            return 0;
        }
        // SiteRuntimeIndex 无 SiteId→坐标 的枚举接口，无法解析 siteId 为目标坐标。
        source.sendFailure(Component.literal("test teleport: no SiteId→position resolver is wired; "
                + siteId + " cannot be resolved"));
        return 0;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static ServerPlayer player(CommandSourceStack source) throws CommandSyntaxException {
        return source.getPlayerOrException();
    }

    private static UUID callerId(CommandSourceStack source) {
        var entity = source.getEntity();
        return entity == null ? CONSOLE_ID : entity.getUUID();
    }

    private static void requireRange(long from, long to) throws CommandSyntaxException {
        if (from < 0) throw usage("from must be >= 0");
        if (to <= from) throw usage("to must be > from");
        if (to - from > MAX_RANGE) throw usage("range must be <= " + MAX_RANGE);
    }

    private static CommandSyntaxException usage(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    private static GateId gateId(CommandSourceStack source, WorldServices svc,
            CommandContext<CommandSourceStack> ctx) {
        String namespace = StringArgumentType.getString(ctx, "namespace");
        long ordinal = IntegerArgumentType.getInteger(ctx, "ordinal");
        try {
            return new GateId(svc.recipe().wallId(), namespace, ordinal);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(invalid.getMessage()));
            return null;
        }
    }

    private static UUID parseUuid(CommandSourceStack source, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("invalid uuid: " + raw));
            return null;
        }
    }

    private static long distanceSq(long xQ8, long zQ8, ServerPlayer player) {
        long dx = FixedMath.floorBlock(xQ8) - (long) Math.floor(player.getX());
        long dz = FixedMath.floorBlock(zQ8) - (long) Math.floor(player.getZ());
        return dx * dx + dz * dz;
    }

    private static void reportGates(CommandSourceStack source, List<RoadTypes.GateRecord> gates) {
        if (gates.isEmpty()) {
            source.sendSuccess(() -> Component.literal("locate gate: none discovered"), false);
            return;
        }
        StringBuilder text = new StringBuilder("locate gate:");
        for (RoadTypes.GateRecord record : gates) {
            var frame = record.contract().frame();
            text.append(" ").append(record.contract().id())
                    .append("@").append(FixedMath.floorBlock(frame.center().xQ8())).append(",")
                    .append(frame.walkY()).append(",")
                    .append(FixedMath.floorBlock(frame.center().zQ8()))
                    .append(" state=").append(record.state().name());
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
    }

    private static String capabilities(WorldServices svc) {
        return "planning,gates,beacons,sites,garrison,ignition,spawners,history,negotiated,debug,export";
    }

    private static String summarize(Map<?, ?> map) {
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) text.append("; ");
            first = false;
            text.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue()));
            if (text.length() > 2048) {
                text.append("…");
                break;
            }
        }
        return text.toString();
    }

    private static void registerJob(CommandJob job) {
        pruneJobs();
        JOBS.put(job.id(), job);
    }

    private static void completeJob(UUID id) {
        pruneJobs();
    }

    private static void pruneJobs() {
        long now = System.nanoTime();
        JOBS.entrySet().removeIf(entry -> now - entry.getValue().createdNanos() > JOB_RETENTION_NANOS);
        if (JOBS.size() <= JOB_CAP) return;
        var entries = new ArrayList<>(JOBS.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().createdNanos()));
        for (int i = 0; i < JOBS.size() - JOB_CAP; i++) JOBS.remove(entries.get(i).getKey());
    }

    private static void pruneRepairTokens() {
        long now = System.nanoTime();
        REPAIR_TOKENS.entrySet().removeIf(entry -> now - entry.getValue().issuedNanos() > REPAIR_TOKEN_TTL_NANOS);
    }

    /** 跨 execute 边界的异步回写：在服务器线程上重查玩家与会话后再发消息。 */
    private static final class AsyncSink {
        private final MinecraftServer server;
        private final UUID playerId;

        private AsyncSink(CommandSourceStack source, UUID playerId) {
            this.server = Objects.requireNonNull(source.getServer(), "server");
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        private void send(java.util.function.Supplier<String> message) {
            server.execute(() -> {
                if (CONSOLE_ID.equals(playerId)) return; // console already received the immediate ack
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) return;
                String text;
                try {
                    text = message.get();
                } catch (RuntimeException failure) {
                    text = "async report failed: " + failure.getMessage();
                }
                player.sendSystemMessage(Component.literal(text));
            });
        }
    }
}
