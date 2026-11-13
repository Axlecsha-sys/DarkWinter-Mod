package com.dark.winter.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Deforms the original linen quads in window-local coordinates, preserving their atlas UVs. */
public final class CurtainMesh {
    // Heights and widths of the gathered linen in cabin_openings.curtain.
    private static final float[] ROW_HEIGHTS = {0, .23f, .40f, .72f, 1};
    private static final float[] OUTSIDE_EDGE = {1.62f, 1.59f, 1.58f, 1.55f, 1.54f};
    private static final float[] INSIDE_EDGE = {3.95f, 3.25f, 3.99f, 4.62f, 4.85f};
    private final List<Quad> quads;

    private record Vertex(float x, float y, float z, float closedX, float closedY, float closedZ, float u, float v) {}
    private record Quad(Vertex[] vertices, BakedQuad original, boolean binding) {}

    CurtainMesh(List<BakedQuad> fabric, List<BakedQuad> bindings, int partY, int height) {
        var result = new ArrayList<Quad>(fabric.size() + bindings.size());
        for (BakedQuad quad : fabric) result.add(bake(quad, partY, height, false));
        for (BakedQuad quad : bindings) result.add(bake(quad, partY, height, true));
        quads = List.copyOf(result);
    }

    private static Quad bake(BakedQuad quad, int partY, int height, boolean binding) {
        boolean right = quad.position(0).x() > .5f;
        Vertex[] vertices = new Vertex[4];
        for (int i = 0; i < vertices.length; i++) {
            var pos = quad.position(i);
            float y = binding ? ROW_HEIGHTS[1] : (16 * (pos.y() + partY) - 1.48f) / (height - 2.91f);
            int row = 0;
            while (row < ROW_HEIGHTS.length - 2 && y > ROW_HEIGHTS[row + 1]) row++;
            float t = Math.clamp((y - ROW_HEIGHTS[row]) / (ROW_HEIGHTS[row + 1] - ROW_HEIGHTS[row]), 0, 1);
            float outer = Mth.lerp(t, OUTSIDE_EDGE[row], OUTSIDE_EDGE[row + 1]);
            float inner = Mth.lerp(t, INSIDE_EDGE[row], INSIDE_EDGE[row + 1]);
            float distance = (right ? 1 - pos.x() : pos.x()) * 16;
            float fraction = Math.clamp((distance - outer) / ((inner - outer) * (right ? .91f : 1)), 0, 1);
            // Let the hems overlap slightly at different depths so the pane never shows through the seam.
            float closed = binding ? .80f + .43f * fraction : Mth.lerp(fraction, 1.19f, 8.10f);
            float x = right ? (16 - closed) / 16 : closed / 16;
            float closedY = pos.y() - Math.max(0, (y - .72f) / .28f) * .24f / 16;
            float closedZ = pos.z() + (right ? .48f : .38f) / 16;
            long uv = quad.packedUV(i);
            vertices[i] = new Vertex(pos.x(), pos.y(), pos.z(), x, closedY, closedZ,
                    UVPair.unpackU(uv), UVPair.unpackV(uv));
        }
        return new Quad(vertices, quad, binding);
    }

    public void render(PoseStack.Pose pose, VertexConsumer buffer, float progress, int light) {
        var points = new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        var normal = new Vector3f();
        var edge = new Vector3f();
        var transformed = new Vector3f();
        // Lift the folds clear of the crossbar first, then draw them along the window.
        float lift = Math.min(1, progress * 4);
        lift = lift * lift * (3 - 2 * lift);
        for (Quad quad : quads) {
            float horizontal = quad.binding() ? lift : progress;
            for (int i = 0; i < points.length; i++) {
                Vertex v = quad.vertices()[i];
                points[i].set(Mth.lerp(horizontal, v.x(), v.closedX()),
                        Mth.lerp(lift, v.y(), v.closedY()), Mth.lerp(lift, v.z(), v.closedZ()));
            }
            points[2].sub(points[0], normal).cross(points[3].sub(points[1], edge));
            if (normal.lengthSquared() > 1e-12f) normal.normalize();
            else normal.set(quad.original().direction().getUnitVec3f());
            pose.transformNormal(normal, normal);
            for (int i = 0; i < points.length; i++) {
                Vertex v = quad.vertices()[i];
                pose.pose().transformPosition(points[i], transformed);
                buffer.addVertex(transformed.x, transformed.y, transformed.z, -1, v.u(), v.v(),
                        OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z);
            }
        }
    }
}
