package com.dark.winter.client;

import com.dark.winter.placement.FreeFurniturePlacement;
import com.dark.winter.placement.SurfacePlacement;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Draws the complete placement candidate using the current frame's interaction ray. */
public final class FurniturePlacementPreviewRenderer {
    private static final Vec3 LIGHT = new Vec3(.42, .82, -.38).normalize();
    private static final int VALID = 0x8846EF74;
    private static final int INVALID = 0x99FF514F;
    private static final FeatureRendererType<Ghost> TYPE = FeatureRendererType.create("DarkWinter Furniture Preview");

    private FurniturePlacementPreviewRenderer() {}

    public static void initialize() {
        FeatureRendererRegistry.register(TYPE, PreviewFeature::new);
    }

    public static void submit(SubmitNodeCollector collector, CameraRenderState cameraState) {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (!cameraState.initialized || player == null || client.level == null || player.isSpectator() || !player.isAlive()
                || client.gui.screen() != null || client.gui.overlay() != null || client.isPaused()
                || client.getCameraEntity() != player || client.hitResult == null) return;
        var stack = player.getMainHandItem();
        if (!FreeFurniturePlacement.applies(stack)) return;
        BlockHitResult hit = client.hitResult instanceof BlockHitResult blockHit ? blockHit
                : BlockHitResult.miss(client.hitResult.getLocation(), player.getDirection().getOpposite(),
                        BlockPos.containing(client.hitResult.getLocation()));
        FurniturePlacementControls.beforePreview(client);
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        var preview = SurfacePlacement.furniturePreview(context);
        if (preview == null) return;
        Vec3 origin = preview.origin();
        Vec3 eye = cameraState.pos.subtract(origin);
        var quads = FurnitureMeshModels.previewQuads(preview.state());
        boolean contactMark = !preview.valid() && hit.getType() == HitResult.Type.BLOCK;
        if (quads.isEmpty() && !contactMark) return;
        // Blend over the depth-tested opaque terrain in both transparency modes.
        collector.submitCustom(SubmitRenderPhases.SOLID,
                new Ghost(quads, eye, contactMark ? hit.getLocation().subtract(cameraState.pos) : Vec3.ZERO, hit.getDirection(),
                        preview.rotation(), preview.valid(), contactMark));
    }

    private record Ghost(List<FurnitureMeshModels.PreviewQuad> quads, Vec3 eye, Vec3 contact, Direction face,
                         double rotation, boolean valid, boolean contactMark) implements SubmitNode {
        @Override public FeatureRendererType<Ghost> featureType() { return TYPE; }
    }

    private static final class PreviewFeature extends RenderTypeFeatureRenderer<Ghost> {
        @Override protected void buildGroup(FeatureFrameContext context, List<Ghost> ghosts) {
            var buffer = getVertexBuilder(RenderTypes.debugQuads());
            for (Ghost ghost : ghosts) {
                int color = ghost.valid ? VALID : INVALID;
                double cos = Math.cos(ghost.rotation);
                double sin = Math.sin(ghost.rotation);
                Vec3 localEye = inverseRotate(ghost.eye, cos, sin);
                Vec3 localLight = inverseRotate(LIGHT, cos, sin);
                for (var quad : ghost.quads) {
                    if (quad.normal().dot(localEye) <= quad.normal().dot(quad.a())) continue;
                    float light = .70f + .30f * (float) Math.max(0, quad.normal().dot(localLight));
                    int tinted = ARGB.scaleRGB(color, light);
                    vertex(buffer, quad.a(), ghost.eye, cos, sin, tinted);
                    vertex(buffer, quad.b(), ghost.eye, cos, sin, tinted);
                    vertex(buffer, quad.c(), ghost.eye, cos, sin, tinted);
                    vertex(buffer, quad.d(), ghost.eye, cos, sin, tinted);
                }
                if (ghost.contactMark) addContactMark(ghost);
            }
        }

        private static Vec3 inverseRotate(Vec3 point, double cos, double sin) {
            return new Vec3(cos * point.x + sin * point.z, point.y, cos * point.z - sin * point.x);
        }

        private static void vertex(VertexConsumer buffer, Vec3 point, Vec3 eye, double cos, double sin, int color) {
            // The coordinates are already camera-relative; no identity matrix transform is needed.
            buffer.addVertex((float) (cos * point.x - sin * point.z - eye.x),
                    (float) (point.y - eye.y), (float) (sin * point.x + cos * point.z - eye.z)).setColor(color);
        }

        private void addContactMark(Ghost ghost) {
            Direction face = ghost.face;
            Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
            Vec3 axis = face.getAxis().isVertical() ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 across = normal.cross(axis);
            Vec3 relativeCenter = ghost.contact.add(normal.scale(.012));
            var lines = getVertexBuilder(RenderTypes.linesTranslucentNoDepthWrite());
            // Keep a visible rejection mark when the model is occluded.
            for (Vec3 direction : List.of(axis.add(across), axis.subtract(across))) {
                for (int sign : new int[]{-1, 1}) {
                    Vec3 relative = relativeCenter.add(direction.scale(sign * .055));
                    lines.addVertex((float) relative.x, (float) relative.y, (float) relative.z)
                            .setColor(INVALID).setNormal((float) direction.x, (float) direction.y, (float) direction.z)
                            .setLineWidth(1.7f);
                }
            }
        }
    }
}
