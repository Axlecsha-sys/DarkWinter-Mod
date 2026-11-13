package com.dark.winter.client.interaction;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.StorageFurnitureBlock;
import com.dark.winter.block.StorageFurnitureEntity;
import com.dark.winter.client.FurnitureMeshModels;
import com.dark.winter.interaction.StorageFurnitureInteraction;
import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.placement.FurnitureTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
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
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class StorageFurnitureRenderer implements BlockEntityRenderer<StorageFurnitureEntity, StorageFurnitureRenderer.State> {
    private static final int[] UNTINTED = new int[0];

    static void initialize() {
        BlockEntityRenderers.register(StorageFurnitureInteraction.TYPE, context -> new StorageFurnitureRenderer());
    }

    private record Part(BlockPos offset, StorageFurnitureBlock.Leaf hinge, List<BlockStateModelPart> quads, int light) {}

    static final class State extends BlockEntityRenderState {
        List<Part> parts = List.of();
        float yaw, progress;
    }

    @Override public int getViewDistance() { return Integer.MAX_VALUE; }
    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(StorageFurnitureEntity entity, State state, float partial, Vec3 camera,
                                             ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partial, camera, breaking);
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var root = entity.getBlockState();
        var block = (StorageFurnitureBlock)root.getBlock();
        var level = entity.getLevel();
        var facing = root.getValue(FurnitureBlock.FACING);
        state.yaw = switch (facing) {
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
        state.progress = entity.progress(partial);
        BlockPos rootPos = entity.getBlockPos();
        Vec3 pivot = Vec3.atLowerCornerOf(rootPos);
        Vec3 translation = level == null ? Vec3.ZERO : AnchorRuntime.offset(level, rootPos);
        double rotation = level == null ? 0 : AnchorRuntime.rotation(level, rootPos);
        var parts = new ArrayList<Part>();
        for (int i = 0; i < block.definition().parts().size(); i++) {
            var leaf = FurnitureMeshModels.storageLeaves(models.get(root.setValue(FurnitureBlock.PART, i)));
            if (leaf == null) continue;
            BlockPos offset = block.definition().parts().get(i).offset();
            BlockPos logical = rootPos.offset(block.partOffset(i, facing));
            Vec3 lightPoint = FurnitureTransform.point(Vec3.atCenterOf(logical), pivot, rotation).add(translation);
            int light = level == null ? state.lightCoords : LightCoordsUtil.getLightCoords(level, BlockPos.containing(lightPoint));
            for (int index = 0; index < 2; index++) {
                if (!leaf.at(index).isEmpty()) parts.add(new Part(offset, block.kind().leaf(index), leaf.at(index), light));
            }
        }
        state.parts = List.copyOf(parts);
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        poses.pushPose();
        try {
            poses.translate(.5, 0, .5);
            poses.rotateDegrees(Axis.YP, state.yaw);
            poses.translate(-.5, 0, -.5);
            for (Part part : state.parts) {
                poses.pushPose();
                float x = (float)(part.hinge.hingeX() / 16), z = (float)(part.hinge.hingeZ() / 16);
                poses.translate(x, 0, z);
                poses.rotateDegrees(Axis.YP, part.hinge.angle() * state.progress);
                poses.translate(part.offset.getX() - x, part.offset.getY(), part.offset.getZ() - z);
                collector.submitBlockModel(poses, RenderTypes.solidMovingBlock(), part.quads, UNTINTED,
                        part.light, OverlayTexture.NO_OVERLAY, 0);
                poses.popPose();
            }
        } finally {
            poses.popPose();
        }
    }
}
