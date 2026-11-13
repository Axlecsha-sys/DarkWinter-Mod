package com.dark.winter.client.interaction;

import com.dark.winter.block.DiningChairBlock;
import com.dark.winter.block.DiningChairEntity;
import com.dark.winter.client.FurnitureMeshModels;
import com.dark.winter.interaction.DiningChairInteraction;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class DiningChairRenderer implements BlockEntityRenderer<DiningChairEntity, DiningChairRenderer.State> {
    private static final int[] UNTINTED = new int[0];

    static void initialize() {
        BlockEntityRenderers.register(DiningChairInteraction.TYPE, context -> new DiningChairRenderer());
    }

    static final class State extends BlockEntityRenderState {
        List<BlockStateModelPart> parts = List.of();
        Vec3 offset = Vec3.ZERO;
    }

    @Override public State createRenderState() { return new State(); }
    @Override public int getViewDistance() { return Integer.MAX_VALUE; }

    @Override public void extractRenderState(DiningChairEntity chair, State state, float partial, Vec3 camera,
                                             ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(chair, state, partial, camera, breaking);
        var block = chair.getBlockState();
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        state.parts = FurnitureMeshModels.diningChairParts(models.get(block));
        state.offset = DiningChairBlock.slide(block, chair.progress(partial));
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        poses.pushPose();
        try {
            poses.translate(state.offset.x, state.offset.y, state.offset.z);
            collector.submitBlockModel(poses, RenderTypes.solidMovingBlock(), state.parts, UNTINTED,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            poses.popPose();
        }
    }
}
