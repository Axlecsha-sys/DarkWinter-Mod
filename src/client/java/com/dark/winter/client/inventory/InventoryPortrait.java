package com.dark.winter.client.inventory;

import com.dark.winter.client.equipment.HumanAnimations;
import com.dark.winter.client.equipment.HumanMotionTimeline;
import com.dark.winter.client.equipment.ThirdPersonAnimations;
import com.dark.winter.client.equipment.ThirdPersonMotionTimeline;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A fresh native avatar snapshot with fixed portrait lighting. No live entity pose is written. */
public final class InventoryPortrait {
    private static final HumanMotionTimeline.Snapshot HUMAN_IDLE=new HumanMotionTimeline.Snapshot(0,0,0,0,0,0,0,0,true);
    private static final ThirdPersonMotionTimeline.Snapshot THIRD_IDLE=new ThirdPersonMotionTimeline.Snapshot(0,0,0,0,0,0,0,0,true,0,0,0,1,0);
    private InventoryPortrait() {}
    public static void extract(GuiGraphicsExtractor graphics,InventoryLayout layout,boolean backpack) {
        var client=Minecraft.getInstance();if(client.player==null)return;
        var renderer=client.getEntityRenderDispatcher().getRenderer(client.player);
        var extracted=renderer.createRenderState(client.player,1f);
        if(!(extracted instanceof AvatarRenderState state))return;
        state.shadowPieces.clear();state.outlineColor=0;state.nameTag=null;
        state.bodyRot=180;state.yRot=0;state.xRot=0;state.ageInTicks=0;
        state.walkAnimationPos=0;state.walkAnimationSpeed=0;state.swingAnimation=0;
        state.isCrouching=false;state.isFallFlying=false;state.isVisuallySwimming=false;state.isPassenger=false;state.isUsingItem=false;
        state.isAutoSpinAttack=false;state.swimAmount=0;state.pose=Pose.STANDING;state.scale=1;state.ageScale=1;
        state.hasRedOverlay=false;state.deathTime=0;state.isUpsideDown=false;state.isInvisible=false;state.isInvisibleToPlayer=false;
        state.rightArmPose=HumanoidModel.ArmPose.EMPTY;state.leftArmPose=HumanoidModel.ArmPose.EMPTY;
        state.rightHandItemState.clear();state.leftHandItemState.clear();
        state.rightHandItemStack=net.minecraft.world.item.ItemStack.EMPTY;state.leftHandItemStack=net.minecraft.world.item.ItemStack.EMPTY;
        state.currentSwing=null;state.ticksUsingItem=0;state.arrowCount=0;state.stingerCount=0;state.showCape=false;state.displayFireAnimation=false;
        state.boundingBoxHeight=1.95f;state.boundingBoxWidth=.85f;
        ((FabricRenderState)state).setData(HumanAnimations.POSE,HUMAN_IDLE);
        ((FabricRenderState)state).setData(ThirdPersonAnimations.POSE,THIRD_IDLE);
        ((FabricRenderState)state).setData(InventoryPortraitLighting.PORTRAIT,true);
        var box=InventoryLayout.portrait(backpack);
        graphics.entity(state,box.h()*layout.scale()/2.15f,new Vector3f(0,.975f,0),new Quaternionf().rotateZ((float)Math.PI),new Quaternionf(),
                layout.x(box.x()),layout.y(box.y()),layout.x(box.x()+box.w()),layout.y(box.y()+box.h()));
    }
}
