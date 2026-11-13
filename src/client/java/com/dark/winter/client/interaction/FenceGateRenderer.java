package com.dark.winter.client.interaction;

import com.dark.winter.block.FenceGateEntity;
import com.dark.winter.block.FenceGateFurnitureBlock;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.client.FurnitureMeshModels;
import com.dark.winter.interaction.FenceGateInteraction;
import com.dark.winter.placement.AnchorRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class FenceGateRenderer implements BlockEntityRenderer<FenceGateEntity, FenceGateRenderer.State> {
    private static final int[] UNTINTED = new int[0];

    static void initialize() {
        BlockEntityRenderers.register(FenceGateInteraction.TYPE, context -> new FenceGateRenderer());
    }

    private record Part(BlockPos offset, FurnitureMeshModels.GateLeaf leaf, int light) {}

    static final class State extends BlockEntityRenderState {
        List<Part> parts = List.of();
        float yaw, angle;
    }

    @Override public int getViewDistance() { return Integer.MAX_VALUE; }
    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(FenceGateEntity gate, State state, float partial, Vec3 camera,
                                             ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(gate, state, partial, camera, breaking);
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var root = gate.getBlockState();
        var block = (FenceGateFurnitureBlock)root.getBlock();
        var level = gate.getLevel();
        var facing = root.getValue(FurnitureBlock.FACING);
        state.yaw = switch (facing) {
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
        state.angle = 90 * gate.progress(partial);
        var parts = new ArrayList<Part>();
        for (int i = 0; i < block.definition().parts().size(); i++) {
            var leaf = FurnitureMeshModels.gateLeaf(models.get(root.setValue(FurnitureBlock.PART, i)));
            if (leaf == null || (leaf.solid().isEmpty() && leaf.snow().isEmpty())) continue;
            BlockPos offset = block.definition().parts().get(i).offset();
            BlockPos logical = gate.getBlockPos().offset(block.partOffset(i, facing));
            int light = level == null ? state.lightCoords : LightCoordsUtil.getLightCoords(level,
                    BlockPos.containing(Vec3.atCenterOf(logical).add(AnchorRuntime.offset(level, logical))));
            parts.add(new Part(offset, leaf, light));
        }
        state.parts = List.copyOf(parts);
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        poses.pushPose();
        try {
            poses.translate(.5, 0, .5);
            poses.rotateDegrees(Axis.YP, state.yaw);
            poses.translate(-.5, 0, -.5);
            var hinge = FenceGateFurnitureBlock.hinge(net.minecraft.core.Direction.NORTH);
            poses.translate(hinge.x, 0, hinge.z);
            poses.rotateDegrees(Axis.YP, state.angle);
            poses.translate(-hinge.x, 0, -hinge.z);
            for (Part part : state.parts) {
                poses.pushPose();
                poses.translate(part.offset.getX(), part.offset.getY(), part.offset.getZ());
                if (!part.leaf.solid().isEmpty()) {
                    collector.submitBlockModel(poses, RenderTypes.solidMovingBlock(), part.leaf.solid(), UNTINTED,
                            part.light, OverlayTexture.NO_OVERLAY, 0);
                }
                if (!part.leaf.snow().isEmpty()) {
                    collector.submitBlockModel(poses, RenderTypes.cutoutMovingBlock(), part.leaf.snow(), UNTINTED,
                            part.light, OverlayTexture.NO_OVERLAY, 0);
                }
                poses.popPose();
            }
        } finally {
            poses.popPose();
        }
    }
}
