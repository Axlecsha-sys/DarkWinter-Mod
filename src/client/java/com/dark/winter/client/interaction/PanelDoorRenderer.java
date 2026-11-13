package com.dark.winter.client.interaction;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.PanelDoorBlock;
import com.dark.winter.block.PanelDoorEntity;
import com.dark.winter.client.FurnitureMeshModels;
import com.dark.winter.interaction.PanelDoorInteraction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class PanelDoorRenderer implements BlockEntityRenderer<PanelDoorEntity, PanelDoorRenderer.State> {
    private static final int[] UNTINTED = new int[0];

    public static void initialize() {
        BlockEntityRenderers.register(PanelDoorInteraction.TYPE, context -> new PanelDoorRenderer());
    }

    public static final class State extends BlockEntityRenderState {
        List<BlockStateModelPart> lower = List.of(), upper = List.of();
        Vec3 hinge = Vec3.ZERO;
        float angle;
        int upperLight;
    }

    // The door replaces terrain geometry, so let the visible chunk sections control its distance.
    @Override public int getViewDistance() { return Integer.MAX_VALUE; }

    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(PanelDoorEntity door, State state, float partial, Vec3 camera,
                                              ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(door, state, partial, camera, breaking);
        var modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var lower = door.getBlockState();
        state.lower = FurnitureMeshModels.doorLeaf(modelSet.get(lower));
        state.upper = FurnitureMeshModels.doorLeaf(modelSet.get(lower.setValue(FurnitureBlock.PART, 1)));
        state.hinge = PanelDoorBlock.hinge(lower.getValue(FurnitureBlock.FACING));
        state.angle = 90 * door.progress(partial);
        state.upperLight = door.getLevel() == null ? state.lightCoords
                : LightCoordsUtil.getLightCoords(door.getLevel(), door.getBlockPos().above());
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.lower.isEmpty()) return;
        poses.pushPose();
        try {
            poses.translate(state.hinge.x, 0, state.hinge.z);
            poses.rotateDegrees(Axis.YP, state.angle);
            poses.translate(-state.hinge.x, 0, -state.hinge.z);
            var type = RenderTypes.solidMovingBlock();
            collector.submitBlockModel(poses, type, state.lower, UNTINTED, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poses.translate(0, 1, 0);
            collector.submitBlockModel(poses, type, state.upper, UNTINTED, state.upperLight, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            poses.popPose();
        }
    }
}
