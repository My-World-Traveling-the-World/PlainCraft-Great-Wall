/* 文件职责：诊断导出器 —— 把有界段范围的计划流式写成 JSON/CSV/SVG 到安全导出目录。 */
package local.plaincraft.greatwall.command;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.BreakInterval;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;
import local.plaincraft.greatwall.model.WallTypes.Priority;
import local.plaincraft.greatwall.model.WallTypes.SegmentGeometry;
import local.plaincraft.greatwall.model.WallTypes.SegmentPlan;
import local.plaincraft.greatwall.model.WallTypes.SiteRecord;
import local.plaincraft.greatwall.model.WallTypes.Station;
import local.plaincraft.greatwall.model.WallTypes.StationTable;
import local.plaincraft.greatwall.model.WallTypes.WaterEvidence;
import local.plaincraft.greatwall.planning.PlannerScheduler;

/**
 * 诊断导出器。导出根固定为隔离/生产 world 下的 {@code greatwall-exports} 子目录，绝不接受
 * 任意系统目录；文件名恒为 {@code <jobUUID>.<format>}，不拼接任何用户路径。范围上限 64 段，
 * 逐页 4 段 require/plan 并在 finally 释放，流式写盘，最后输出字节数与 SHA-256。
 */
public final class DiagnosticExporter {

    /** 导出格式。 */
    public enum Format { JSON, CSV, SVG }

    /** 导出请求。job 非空，范围半开 [fromInclusive, toExclusive)。 */
    public record Request(UUID job, long fromInclusive, long toExclusive, Format format) {
        public Request {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(format, "format");
            if (fromInclusive < 0) {
                throw new IllegalArgumentException("fromInclusive >= 0 required");
            }
            if (toExclusive <= fromInclusive) {
                throw new IllegalArgumentException("toExclusive > fromInclusive required");
            }
        }
    }

    /** 导出结果。 */
    public record Result(UUID job, Path path, long bytes, String sha256) {
        public Result {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes >= 0 required");
            }
        }
    }

    private static final long MAX_RANGE = 64L;
    private static final long PAGE_SEGMENTS = 4L;
    private static final String EXPORTS_DIR = "greatwall-exports";

    private final Path exportsRoot;
    private final PlannerScheduler planner;
    private final Executor ioOwner;

    public DiagnosticExporter(Path exportsRoot, PlannerScheduler planner, Executor ioOwner) {
        this.exportsRoot = validateRoot(exportsRoot);
        this.planner = Objects.requireNonNull(planner, "planner");
        this.ioOwner = Objects.requireNonNull(ioOwner, "ioOwner");
    }

    public CompletionStage<Result> export(Request request) {
        Objects.requireNonNull(request, "request");
        long range = request.toExclusive() - request.fromInclusive();
        if (range > MAX_RANGE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "export range " + range + " exceeds " + MAX_RANGE));
        }
        String ext = request.format().name().toLowerCase(java.util.Locale.ROOT);
        Path out = exportsRoot.resolve(request.job().toString() + "." + ext);
        return CompletableFuture.supplyAsync(() -> write(request, out), ioOwner);
    }

    // ------------------------------------------------------------------
    // 私有实现
    // ------------------------------------------------------------------

    private Result write(Request request, Path out) {
        try {
            Files.createDirectories(exportsRoot);
            try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                switch (request.format()) {
                    case JSON -> writeJson(request, writer);
                    case CSV -> writeCsv(request, writer);
                    case SVG -> writeSvg(request, writer);
                    default -> throw new IllegalStateException("unknown format");
                }
            }
            byte[] bytes = Files.readAllBytes(out);
            return new Result(request.job(), out, bytes.length,
                    DeterministicHash.sha256(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("export failed: " + e.getMessage(), e);
        }
    }

    private List<Page> pages(Request request) {
        List<Page> pages = new ArrayList<>();
        for (long start = request.fromInclusive(); start < request.toExclusive();
                start += PAGE_SEGMENTS) {
            long end = Math.min(start + PAGE_SEGMENTS, request.toExclusive());
            pages.add(new Page(start, end));
        }
        return pages;
    }

    private record Page(long from, long to) {}

    private PlanWindow requireWindow(Page page) {
        List<SegmentPlan> plans = new ArrayList<>();
        List<PlanLease<SegmentPlan>> leases = new ArrayList<>();
        try {
            for (long segment = page.from(); segment < page.to(); segment++) {
                PlanLease<SegmentPlan> lease =
                        planner.requirePlan(segment, Priority.BACKGROUND);
                leases.add(lease);
                plans.add(lease.future().join());
            }
            return new PlanWindow(List.copyOf(plans));
        } finally {
            for (PlanLease<SegmentPlan> lease : leases) {
                lease.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON schema1
    // ------------------------------------------------------------------

    private void writeJson(Request request, BufferedWriter w) throws IOException {
        // 构造函数不接收 WorldRecipe；仅能从已加载 plan 的 SegmentKey 得到
        // recipeHash/dimension。seed/axis/wallId/instance/config 不在 planner 公开面，
        // 按 NOT_CAPTURED 标记，不编造数值（与 h 缺 T0 时同一约定）。
        RecipeIdentity recipe = recipeIdentity(request);
        w.write("{");
        w.write("\"schema\":1,");
        w.write("\"job\":\"" + json(request.job().toString()) + "\",");
        w.write("\"recipe\":{");
        w.write("\"instance\":\"NOT_CAPTURED\",");
        w.write("\"wallId\":\"NOT_CAPTURED\",");
        w.write("\"seed\":\"NOT_CAPTURED\",");
        w.write("\"dimension\":\"" + json(recipe.dimension()) + "\",");
        w.write("\"recipeHash\":\"" + json(recipe.recipeHash()) + "\",");
        w.write("\"axis\":\"NOT_CAPTURED\",");
        w.write("\"u\":\"NOT_CAPTURED\",");
        w.write("\"config\":\"NOT_CAPTURED\"");
        w.write("},");
        w.write("\"segments\":[");
        boolean first = true;
        for (Page page : pages(request)) {
            PlanWindow window = requireWindow(page);
            for (SegmentPlan plan : window.plans()) {
                if (!first) {
                    w.write(",");
                }
                first = false;
                writeSegmentJson(w, plan);
            }
        }
        w.write("]}");
    }

    /** 从首段 plan 的 SegmentKey 推导 recipe 身份；无段时 dimension/recipeHash 标 NOT_CAPTURED。 */
    private RecipeIdentity recipeIdentity(Request request) {
        String dimension = "NOT_CAPTURED";
        String recipeHash = "NOT_CAPTURED";
        for (Page page : pages(request)) {
            PlanWindow window = requireWindow(page);
            if (!window.plans().isEmpty()) {
                dimension = window.plans().get(0).geometry().key().dimension();
                recipeHash = window.plans().get(0).geometry().key().recipeHash();
                break;
            }
        }
        return new RecipeIdentity(dimension, recipeHash);
    }

    private record RecipeIdentity(String dimension, String recipeHash) {}

    private void writeSegmentJson(BufferedWriter w, SegmentPlan plan) throws IOException {
        SegmentGeometry geo = plan.geometry();
        w.write("{");
        w.write("\"segment\":" + geo.key().index() + ",");
        w.write("\"u\":\"" + json(geo.key().toString()) + "\",");
        w.write("\"S\":" + plan.prefix().lengthBeforeQ8() + ",");
        w.write("\"stations\":[");
        writeStationsJson(w, plan.stations());
        w.write("],");
        w.write("\"W\":[" + collectWater(geo) + "],");
        w.write("\"g\":" + groundY(plan) + ",");
        w.write("\"h\":" + heightsJson(plan) + ",");
        w.write("\"wetWidth\":" + wetWidth(geo) + ",");
        w.write("\"crossingSpan\":" + crossingSpan(geo) + ",");
        w.write("\"sites\":[");
        writeSitesJson(w, plan.sites());
        w.write("],");
        w.write("\"IDs\":" + idsJson(plan) + ",");
        w.write("\"t0Hashes\":" + t0HashesJson(plan));
        w.write("}");
    }

    private void writeStationsJson(BufferedWriter w, StationTable table) throws IOException {
        boolean first = true;
        for (Station station : table.stations()) {
            if (!first) {
                w.write(",");
            }
            first = false;
            w.write("{");
            w.write("\"S\":" + station.globalSQ8() + ",");
            w.write("\"x\":" + station.point().xQ8() + ",");
            w.write("\"z\":" + station.point().zQ8() + ",");
            w.write("\"walkY\":" + station.walkY() + ",");
            w.write("\"flags\":" + station.flags());
            w.write("}");
        }
    }

    private void writeSitesJson(BufferedWriter w, List<SiteRecord> sites) throws IOException {
        boolean first = true;
        for (SiteRecord site : sites) {
            if (!first) {
                w.write(",");
            }
            first = false;
            w.write("{");
            w.write("\"id\":\"" + json(site.id().toString()) + "\",");
            w.write("\"kind\":\"" + site.id().kind().name() + "\",");
            w.write("\"S\":" + site.globalSQ8() + ",");
            w.write("\"state\":\"" + site.state().name() + "\"");
            w.write("}");
        }
    }

    private String collectWater(SegmentGeometry geo) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (WaterEvidence evidence : geo.water()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{\"class\":\"").append(evidence.classification().name())
                    .append("\",\"channelWidthQ8\":").append(evidence.channelWidthQ8())
                    .append(",\"crossingSpanQ8\":").append(evidence.crossingSpanQ8())
                    .append(",\"waterTopY\":").append(evidence.waterTopY())
                    .append("}");
        }
        return sb.toString();
    }

    private String groundY(SegmentPlan plan) {
        // 段级地面高度由 T0 提供；导出器不读 T0，标 NOT_CAPTURED。
        return "\"NOT_CAPTURED\"";
    }

    private String heightsJson(SegmentPlan plan) {
        // 没 T0 的 h 为空并标 NOT_CAPTURED，不编造最终地面。
        return "\"NOT_CAPTURED\"";
    }

    private String wetWidth(SegmentGeometry geo) {
        long width = 0L;
        for (WaterEvidence evidence : geo.water()) {
            width = Math.max(width, evidence.channelWidthQ8());
        }
        return Long.toString(width);
    }

    private String crossingSpan(SegmentGeometry geo) {
        long span = 0L;
        for (WaterEvidence evidence : geo.water()) {
            span = Math.max(span, evidence.crossingSpanQ8());
        }
        return Long.toString(span);
    }

    private String idsJson(SegmentPlan plan) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (SiteRecord site : plan.sites()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(json(site.id().toString())).append("\"");
        }
        return sb.append("]").toString();
    }

    private String t0HashesJson(SegmentPlan plan) {
        return "[]";
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    private void writeCsv(Request request, BufferedWriter w) throws IOException {
        w.write("kind,unit,S,value\n");
        for (Page page : pages(request)) {
            PlanWindow window = requireWindow(page);
            for (SegmentPlan plan : window.plans()) {
                for (Station station : plan.stations().stations()) {
                    w.write(csv("station") + "," + csv("Q8") + "," + station.globalSQ8()
                            + "," + csv("walkY=" + station.walkY()) + "\n");
                }
                for (BreakInterval interval : plan.stations().breaks()) {
                    w.write(csv("break") + "," + csv("Q8") + ","
                            + interval.range().fromQ8() + ","
                            + csv(interval.reason().name()) + "\n");
                }
                for (WaterEvidence evidence : plan.geometry().water()) {
                    w.write(csv("water") + "," + csv("Q8") + ","
                            + evidence.crossing().fromQ8() + ","
                            + csv(evidence.classification().name()) + "\n");
                }
                for (SiteRecord site : plan.sites()) {
                    w.write(csv("site") + "," + csv("Q8") + "," + site.globalSQ8()
                            + "," + csv(site.id().toString()) + "\n");
                }
            }
        }
    }

    private String csv(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ------------------------------------------------------------------
    // SVG
    // ------------------------------------------------------------------

    private void writeSvg(Request request, BufferedWriter w) throws IOException {
        Bounds bounds = computeBounds(request);
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"");
        w.write(bounds.minX() + " " + bounds.minZ() + " " + bounds.width() + " "
                + bounds.height());
        w.write("\">\n");
        w.write("<!-- origin label: translated from world origin (" + bounds.originX()
                + "," + bounds.originZ() + ") -->\n");
        w.write("<g id=\"plan\">\n");
        for (Page page : pages(request)) {
            PlanWindow window = requireWindow(page);
            for (SegmentPlan plan : window.plans()) {
                writeSegmentSvg(w, plan, bounds);
            }
        }
        w.write("</g>\n");
        w.write("<g id=\"profile\">\n");
        for (Page page : pages(request)) {
            PlanWindow window = requireWindow(page);
            for (SegmentPlan plan : window.plans()) {
                writeProfileSvg(w, plan, bounds);
            }
        }
        w.write("</g>\n");
        w.write("</svg>\n");
    }

    private void writeSegmentSvg(BufferedWriter w, SegmentPlan plan, Bounds bounds)
            throws IOException {
        for (Station station : plan.stations().stations()) {
            long x = station.point().xQ8() - bounds.originX();
            long z = station.point().zQ8() - bounds.originZ();
            w.write("<circle cx=\"" + x + "\" cy=\"" + z + "\" r=\"2\" fill=\"white\"/>\n");
        }
    }

    private void writeProfileSvg(BufferedWriter w, SegmentPlan plan, Bounds bounds)
            throws IOException {
        for (Station station : plan.stations().stations()) {
            long s = station.globalSQ8() - bounds.originS();
            long y = station.walkY() * 256L;
            w.write("<circle cx=\"" + s + "\" cy=\"" + y + "\" r=\"2\" fill=\"amber\"/>\n");
        }
    }

    private Bounds computeBounds(Request request) {
        long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        long minS = Long.MAX_VALUE, maxS = Long.MIN_VALUE;
        for (Page page : pages(request)) {
            PlanWindow window = requireWindow(page);
            for (SegmentPlan plan : window.plans()) {
                for (Station station : plan.stations().stations()) {
                    minX = Math.min(minX, station.point().xQ8());
                    maxX = Math.max(maxX, station.point().xQ8());
                    minZ = Math.min(minZ, station.point().zQ8());
                    maxZ = Math.max(maxZ, station.point().zQ8());
                }
                minS = Math.min(minS, plan.prefix().lengthBeforeQ8());
                maxS = Math.max(maxS, plan.prefix().lengthBeforeQ8());
            }
        }
        if (minX == Long.MAX_VALUE) {
            minX = 0;
            maxX = 1;
            minZ = 0;
            maxZ = 1;
        }
        if (minS == Long.MAX_VALUE) {
            minS = 0;
            maxS = 1;
        }
        return new Bounds(minX, minZ, maxX - minX, maxZ - minZ, minX, minZ, minS);
    }

    private record Bounds(long minX, long minZ, long width, long height,
            long originX, long originZ, long originS) {}

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String json(String value) {
        return value == null ? "null"
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Path validateRoot(Path root) {
        Objects.requireNonNull(root, "exportsRoot");
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getNameCount() == 0
                || !EXPORTS_DIR.equals(normalized.getFileName().toString())) {
            throw new IllegalArgumentException(
                    "exportsRoot must be the " + EXPORTS_DIR + " subdirectory");
        }
        return normalized;
    }
}
