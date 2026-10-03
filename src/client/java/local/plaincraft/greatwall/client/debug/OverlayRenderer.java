package local.plaincraft.greatwall.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import local.plaincraft.greatwall.diagnostics.DebugPayload;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Appends bounded diagnostic primitives to vanilla's buffers without flushing them. */
public final class OverlayRenderer {
    private static final double RANGE_SQUARED = 256.0 * 256.0;
    private static final int LINE_LIMIT = 2048;
    private static final int LABEL_LIMIT = 32;

    private OverlayRenderer() {}

    public static void render(WorldRenderContext context, List<DebugPayload> pages) {
        var client = Minecraft.getInstance();
        if (client == null || !client.isSameThread()) {
            throw new IllegalStateException("Great Wall overlay requires the actual client owner");
        }
        if (context == null || context.matrixStack() == null || context.consumers() == null
                || client.level == null || context.world() != client.level
                || context.camera() == null || !context.camera().isInitialized() || !complete(pages)) return;
        var camera = context.camera().getPosition();
        var origin = new Vec3(Math.floor(camera.x), Math.floor(camera.y), Math.floor(camera.z));
        var poses = context.matrixStack();
        var buffers = context.consumers();
        poses.pushPose();
        try {
            var view = new Matrix4f(poses.last().pose());
            poses.translate(-camera.x, -camera.y, -camera.z);
            // Equivalent camera/origin translation, without subtracting large GPU floats.
            poses.last().pose().set(view).translate((float) (origin.x - camera.x),
                    (float) (origin.y - camera.y), (float) (origin.z - camera.z));
            var vertices = buffers.getBuffer(RenderType.lines());
            int lines = 0;
            for (var page : pages) {
                for (var line : page.lines()) {
                    if (lines == LINE_LIMIT) break;
                    if (line.from().equals(line.to()) || !near(line.from(), camera)
                            || !near(line.to(), camera)) continue;
                    line(poses.last(), vertices, line, origin);
                    lines++;
                }
                for (var box : page.boxes()) {
                    // A wire box contributes twelve lines to the same frame budget.
                    if (LINE_LIMIT - lines < 12) break;
                    if (!near(box.bounds(), camera)) continue;
                    box(poses, vertices, box, origin);
                    lines += 12;
                }
                if (lines == LINE_LIMIT) break;
            }
            int labels = 0;
            for (var page : pages) {
                for (var label : page.labels()) {
                    if (labels == LABEL_LIMIT) break;
                    if (!near(label.pos(), camera)) continue;
                    label(client, poses, buffers, label, origin);
                    labels++;
                }
                if (labels == LABEL_LIMIT) break;
            }
        } finally {
            poses.popPose();
        }
    }

    static boolean complete(List<DebugPayload> pages) {
        if (pages == null || pages.isEmpty() || pages.size() > 256) return false;
        var first = pages.getFirst();
        if (first == null || first.totalPages() != pages.size()) return false;
        for (int index = 0; index < pages.size(); index++) {
            var page = pages.get(index);
            if (page == null || !first.session().equals(page.session())
                    || first.revision() != page.revision() || page.page() != index
                    || page.totalPages() != pages.size()) return false;
        }
        return true;
    }

    static boolean near(Point3i point, Vec3 camera) {
        return distanceSquared(point.x(), point.y(), point.z(), camera) <= RANGE_SQUARED;
    }

    static boolean near(Bounds bounds, Vec3 camera) {
        // Every corner must be in range; remote vertices are never emitted for a crossing box.
        double x = Math.max(Math.abs((double) bounds.minX() - camera.x),
                Math.abs((double) bounds.maxXExclusive() - camera.x));
        double y = Math.max(Math.abs((double) bounds.minY() - camera.y),
                Math.abs((double) bounds.maxYExclusive() - camera.y));
        double z = Math.max(Math.abs((double) bounds.minZ() - camera.z),
                Math.abs((double) bounds.maxZExclusive() - camera.z));
        return x * x + y * y + z * z <= RANGE_SQUARED;
    }

    private static double distanceSquared(double x, double y, double z, Vec3 camera) {
        double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void line(PoseStack.Pose pose, VertexConsumer vertices, DebugPayload.Line line, Vec3 origin) {
        var from = line.from();
        var to = line.to();
        double dx = (double) to.x() - from.x(), dy = (double) to.y() - from.y();
        double dz = (double) to.z() - from.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = (float) (dx / length), ny = (float) (dy / length), nz = (float) (dz / length);
        vertices.addVertex(pose, (float) (from.x() - origin.x), (float) (from.y() - origin.y),
                (float) (from.z() - origin.z)).setColor(line.argb()).setNormal(pose, nx, ny, nz);
        vertices.addVertex(pose, (float) (to.x() - origin.x), (float) (to.y() - origin.y),
                (float) (to.z() - origin.z)).setColor(line.argb()).setNormal(pose, nx, ny, nz);
    }

    private static void box(PoseStack poses, VertexConsumer vertices, DebugPayload.Box box, Vec3 origin) {
        var bounds = box.bounds();
        int color = box.argb();
        LevelRenderer.renderLineBox(poses, vertices, bounds.minX() - origin.x, bounds.minY() - origin.y,
                bounds.minZ() - origin.z, bounds.maxXExclusive() - origin.x, bounds.maxYExclusive() - origin.y,
                bounds.maxZExclusive() - origin.z,
                ((color >>> 16) & 255) / 255.0F, ((color >>> 8) & 255) / 255.0F,
                (color & 255) / 255.0F, ((color >>> 24) & 255) / 255.0F);
    }

    private static void label(Minecraft client, PoseStack poses, MultiBufferSource buffers, DebugPayload.Label label,
            Vec3 origin) {
        poses.pushPose();
        try {
            var point = label.pos();
            poses.translate(point.x() - origin.x, point.y() - origin.y, point.z() - origin.z);
            poses.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
            poses.scale(0.025F, -0.025F, 0.025F);
            client.font.drawInBatch(label.text(), -client.font.width(label.text()) / 2.0F, 0,
                    0xFFFFFFFF, false, poses.last().pose(), buffers, Font.DisplayMode.NORMAL,
                    0, LightTexture.FULL_BRIGHT);
        } finally {
            poses.popPose();
        }
    }
}
