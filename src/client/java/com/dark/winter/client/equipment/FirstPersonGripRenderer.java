// A visual arm follows the existing vanilla item pose, including use, equip and attack transforms.
package com.dark.winter.client.equipment;

import com.dark.winter.client.mixin.client.PlayerModelAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import org.joml.Vector3f;

/** A visual arm follows the existing vanilla item pose, including use, equip and attack transforms. */
public final class FirstPersonGripRenderer {
    public record GripFrame(long submitted, String item, HumanoidArm arm) { }
    private record Key(HumanoidArm arm, boolean sleeve) { }
    private static final class Shape {
        final PlayerModel neutral;
        final Map<Key, ModelPart> skin = new HashMap<>();
        Shape(boolean slim) { neutral = new PlayerModel(HumanBodyModel.createRoot(slim), slim); }
        ModelPart arm(HumanoidArm side, boolean sleeve) {
            return skin.computeIfAbsent(new Key(side, sleeve), key -> FirstPersonSurfaces.grip(neutral.getArm(side), new PoseStack(), path -> sleeve || !path.contains("sleeve")));
        }
    }
    private static final Shape WIDE = new Shape(false), SLIM = new Shape(true);
    private static GripFrame lastFrame = new GripFrame(0, "none", HumanoidArm.RIGHT);

    public static GripFrame lastFrame() { return lastFrame; }

    /** Shared view-only adjustment: the item and its grip keep the same animated local frame. */
    public static void frameTool(ItemStack stack, HumanoidArm hand, PoseStack poses) {
        frameTool(poses, hand, isTool(stack));
    }

    static void frameTool(PoseStack poses, HumanoidArm hand, boolean tool) {
        if (!tool) return;
        float side = hand == HumanoidArm.RIGHT ? 1 : -1;
        // The resting vanilla tool frame has a mirrored 45-degree yaw. Equal local X/Z
        // components bring it inward without pushing it nearer the eye or changing its scale.
        poses.translate(-.08f * side, .10f, -.08f);
    }

    private static boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES) || stack.is(ItemTags.SWORDS);
    }

    public static void submit(AvatarRenderState state, HumanoidArm hand, ItemStack stack, ItemStackRenderState itemGeometry, PoseStack poses, SubmitNodeCollector collector, int light) {
        if (state.isInvisible || stack.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        var renderer = client.getEntityRenderDispatcher().getRenderer(state);
        Shape shape = ((PlayerModelAccessor)renderer.getModel()).darkwinter$isSlim() ? SLIM : WIDE;
        boolean sleeve = hand == HumanoidArm.RIGHT ? state.showRightSleeve : state.showLeftSleeve;
        ModelPart arm = shape.neutral.getArm(hand);
        // Position the real palm at the item-frame grip rather than moving any skeleton pivot.
        Vector3f palm = FirstPersonSurfaces.curl(new Vector3f(arm.x, arm.y + FirstPersonSurfaces.PALM_Y, -.13f)).div(16);
        PoseStack orientation = new PoseStack();
        orientation.rotateDegrees(Axis.XP, -75);
        palm.mulPosition(orientation.last().pose());
        boolean tool = isTool(stack);
        Vector3f target = gripTarget(itemGeometry, tool, stack.getItem() instanceof BlockItem);
        poses.pushPose();
        poses.translate(target.x - palm.x, target.y - palm.y, target.z - palm.z);
        poses.rotateDegrees(Axis.XP, -75);
        collector.submitModelPart(shape.arm(hand, sleeve), poses, RenderTypes.entityTranslucent(state.skin.body().texturePath()),
                light, OverlayTexture.NO_OVERLAY, null);
        if (client.player != null) ClothingLayer.submitGrip(shape.neutral, arm, client.player, poses, collector, light);
        poses.popPose();
        lastFrame = new GripFrame(lastFrame.submitted() + 1, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), hand);
    }

    static Vector3f gripTarget(ItemStackRenderState item, boolean tool, boolean block) {
        var bounds = item.getModelBoundingBox();
        Vector3f target = new Vector3f((float)((bounds.minX+bounds.maxX)*.5),
                (float)(bounds.minY+bounds.getYsize()*.28), (float)((bounds.minZ+bounds.maxZ)*.5));
        if (block) { target.y=(float)bounds.minY-.035f; return target; }
        if (tool) {
            // Read the actual display-transformed shaft, including resource-pack geometry.
            // Grip above its cropped bottom, while remaining below a large solid tool head.
            target.y=(float)(bounds.minY+bounds.getYsize()*.425);
            float low=(float)(bounds.minY+bounds.getYsize()*.30), high=(float)(bounds.minY+bounds.getYsize()*.55);
            Vector3f sum=new Vector3f();int[] count={0};
            item.visitExtents(vertex->{if(vertex.y()>=low&&vertex.y()<=high){sum.add(vertex);count[0]++;}});
            if(count[0]>0) target.set(sum.div(count[0]));
        }
        return target;
    }

    private FirstPersonGripRenderer() { }
}
