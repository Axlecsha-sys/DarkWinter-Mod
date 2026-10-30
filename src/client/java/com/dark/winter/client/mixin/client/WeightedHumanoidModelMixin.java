// Applies weighted gait offsets after native humanoid animation.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.motion.*;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applied after vanilla animation, also to vanilla humanoid armor models sharing the state. */
@Mixin(HumanoidModel.class)
public abstract class WeightedHumanoidModelMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method="setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",at=@At("TAIL"),require=1)
    private void darkwinter$weightTheStep(HumanoidRenderState state,CallbackInfo ci) {
        if((Object)this instanceof net.minecraft.client.model.player.PlayerModel player&&player.root().hasChild("darkwinter_human"))return;
        GaitPose g=((FabricRenderState)(Object)state).getData(GaitRuntime.POSE);
        if(g==null || g.weight()<=0 || state.isCrouching || state.isPassenger
                || state.isUsingItem || state.isFallFlying || state.isVisuallySwimming) return;
        double w=g.weight();
        double blend=MotionMath.clamp(g.amount()*w,0,1);
        double legAmplitude=MotionMath.mix(0.64,0.84,g.running());
        float r=(float)(MotionMath.leg(g.phase())*legAmplitude);
        float l=(float)(MotionMath.leg(g.phase()+1)*legAmplitude);
        rightLeg.xRot=(float)MotionMath.mix(rightLeg.xRot,r,blend);
        leftLeg.xRot=(float)MotionMath.mix(leftLeg.xRot,l,blend);
        if(g.arms()) {
            double arm=MotionMath.mix(0.30,0.50,g.running());
            rightArm.xRot=(float)MotionMath.mix(rightArm.xRot,-r*arm/legAmplitude,blend);
            leftArm.xRot=(float)MotionMath.mix(leftArm.xRot,-l*arm/legAmplitude,blend);
        }
        double climb=g.climbWeight()*w;
        double lift=Math.sin(Math.PI*g.climbPhase())*Math.min(1,climb);
        if (lift>0) {
            ModelPart lead=g.climbRight()?rightLeg:leftLeg;
            ModelPart rear=g.climbRight()?leftLeg:rightLeg;
            lead.xRot=(float)MotionMath.mix(lead.xRot,-0.85,lift);
            lead.y-=3.0f*(float)lift;
            rear.xRot=(float)MotionMath.mix(rear.xRot,0.22,lift);
        }
        double sink=MotionMath.contact(g.phase())*MotionMath.mix(0.24,0.42,g.running())*blend*(1-g.climbWeight());
        double lean=g.lean()*w+0.10*lift;
        double roll=(Math.sin(Math.PI*g.phase())*0.025*g.amount()-g.turn()*0.045)*w;
        darkwinter$upperPart(body,lean,roll,sink,1);
        darkwinter$upperPart(head,lean,roll,sink,0.25);
        darkwinter$upperPart(rightArm,lean,roll,sink,1);
        darkwinter$upperPart(leftArm,lean,roll,sink,1);
    }
    @Unique
    private static void darkwinter$upperPart(ModelPart p,double lean,double roll,double sink,double headCompensation) {
        // Rotate shoulder/head pivots about the hips as a group, rather than detaching them.
        double x=p.x,y=p.y-12,z=p.z;
        double cy=Math.cos(lean),sy=Math.sin(lean),cr=Math.cos(roll),sr=Math.sin(roll);
        double yy=y*cy-z*sy,zz=y*sy+z*cy;
        p.x=(float)(x*cr-yy*sr);
        p.y=(float)(x*sr+yy*cr+12+sink);
        p.z=(float)zz;
        p.xRot+=(float)(lean*headCompensation);
        p.zRot+=(float)(roll*headCompensation);
    }
}
