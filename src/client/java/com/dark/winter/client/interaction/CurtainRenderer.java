package com.dark.winter.client.interaction;

import com.dark.winter.block.CurtainEntity;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.client.CurtainMesh;
import com.dark.winter.client.FurnitureMeshModels;
import com.dark.winter.interaction.CurtainInteraction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class CurtainRenderer implements BlockEntityRenderer<CurtainEntity, CurtainRenderer.State> {
    static void initialize() {
        BlockEntityRenderers.register(CurtainInteraction.TYPE, context -> new CurtainRenderer());
    }

    static final class State extends BlockEntityRenderState {
        @Nullable CurtainMesh lower, upper;
        float closure, yaw;
        int upperLight;
    }

    @Override public int getViewDistance() { return Integer.MAX_VALUE; }

    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(CurtainEntity window, State state, float partial, Vec3 camera,
                                             ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(window, state, partial, camera, breaking);
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var lower = window.getBlockState();
        var block = (FurnitureBlock)lower.getBlock();
        state.lower = FurnitureMeshModels.curtainMesh(models.get(lower));
        state.upper = block.definition().parts().size() > 1
                ? FurnitureMeshModels.curtainMesh(models.get(lower.setValue(FurnitureBlock.PART, 1))) : null;
        state.yaw = switch (lower.getValue(FurnitureBlock.FACING)) {
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
        state.closure = window.progress(partial);
        state.upperLight = window.getLevel() == null ? state.lightCoords
                : LightCoordsUtil.getLightCoords(window.getLevel(), window.getBlockPos().above());
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        poses.pushPose();
        try {
            poses.translate(.5, 0, .5);
            poses.rotateDegrees(Axis.YP, state.yaw);
            poses.translate(-.5, 0, -.5);
            submitCloth(state.lower, state.closure, state.lightCoords, poses, collector);
            if (state.upper != null) {
                poses.translate(0, 1, 0);
                submitCloth(state.upper, state.closure, state.upperLight, poses, collector);
            }
        } finally {
            poses.popPose();
        }
    }

    private static void submitCloth(@Nullable CurtainMesh mesh, float progress, int light, PoseStack poses,
                                    SubmitNodeCollector collector) {
        if (mesh == null) return;
        collector.submitCustomGeometry(poses, RenderTypes.solidMovingBlock(),
                (pose, buffer) -> mesh.render(pose, buffer, progress, light));
    }
}
