// The shared anchor renderer applies the world offset; this renderer only positions the actual local item mesh.
package com.dark.winter.client.placement;

import com.dark.winter.placement.item.PlacedItemBlock;
import com.dark.winter.placement.item.PlacedItemBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class PlacedItemRenderer implements BlockEntityRenderer<PlacedItemBlockEntity, PlacedItemRenderer.State> {
    private final ItemModelResolver resolver;
    public static final class State extends BlockEntityRenderState {
        public final ItemStackRenderState item = new ItemStackRenderState();
        public Direction facing = Direction.NORTH;
    }

    public PlacedItemRenderer(BlockEntityRendererProvider.Context context) { resolver = context.itemModelResolver(); }
    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(PlacedItemBlockEntity entity, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTicks, cameraPosition, breakProgress);
        state.facing = entity.getBlockState().getValue(PlacedItemBlock.FACING);
        resolver.updateForTopItem(state.item, entity.stack(), ItemDisplayContext.GROUND,
                entity.getLevel(), null, Long.hashCode(entity.getBlockPos().asLong()));
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.item.isEmpty()) return;
        AABB bounds = state.item.getModelBoundingBox();
        poses.pushPose();
        try {
            poses.translate(.5, 0, .5);
            poses.rotateDegrees(Axis.YP, -state.facing.toYRot());
            poses.translate(-(bounds.minX + bounds.maxX) * .5, -bounds.minY,
                    -(bounds.minZ + bounds.maxZ) * .5);
            state.item.submit(poses, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            poses.popPose();
        }
    }
}
