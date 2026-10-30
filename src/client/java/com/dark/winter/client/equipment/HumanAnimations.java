// Visual-only joint animation, shared by third person, worn clothing and the extracted first-person body.
package com.dark.winter.client.equipment;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/** Visual-only joint animation, shared by third person, worn clothing and the extracted first-person body. */
public final class HumanAnimations {
    public static final RenderStateDataKey<HumanMotionTimeline.Snapshot> POSE=RenderStateDataKey.create(()->"darkwinter:human_animation");
    private static final Map<Player,HumanMotionTimeline> TIMELINES=new WeakHashMap<>();
    public static void extract(LivingEntity entity,LivingEntityRenderState state,float partial) {
        HumanMotionTimeline.Snapshot snapshot=null;
        if(entity instanceof Player player&&state instanceof AvatarRenderState) {
            boolean enabled=player.isAlive()&&!player.isSpectator()&&!player.isPassenger()&&!player.isSleeping()
                    &&!player.isFallFlying()&&!player.isVisuallySwimming()&&!player.isInWater()&&!player.isInLava()
                    &&!player.onClimbable()&&!player.getAbilities().flying;
            float speed=(float)player.getDeltaMovement().horizontalDistance();
            snapshot=TIMELINES.computeIfAbsent(player,key->new HumanMotionTimeline()).update(new HumanMotionTimeline.Input(
                    player.tickCount+partial,speed,(float)player.getDeltaMovement().y,player.onGround(),player.isSprinting(),player.isCrouching(),enabled));
        }
        // All renderer snapshots are reusable. Clearing this also prevents a non-player inheriting another entity's pose.
        ((FabricRenderState)state).setData(POSE,snapshot);
    }
    public static boolean protectedPose(AvatarRenderState s) {
        return s.isSpectator||s.isPassenger||s.isFallFlying||s.isVisuallySwimming||s.swimAmount>.001f
                ||s.pose==Pose.SLEEPING||s.isAutoSpinAttack||s.isUsingItem||s.swingAnimation>0
                ||(s.rightArmPose!=HumanoidModel.ArmPose.EMPTY&&s.rightArmPose!=HumanoidModel.ArmPose.ITEM)
                ||(s.leftArmPose!=HumanoidModel.ArmPose.EMPTY&&s.leftArmPose!=HumanoidModel.ArmPose.ITEM);
    }
    public static void apply(PlayerModel m,AvatarRenderState state,HumanMotionTimeline.Snapshot p) {
        if(!m.root().hasChild("darkwinter_human"))return;
        HumanJoints.syncOverlays(m);
        if(p==null||!p.enabled()||protectedPose(state))return;
        // Undo vanilla's binary crouch before applying the continuous, articulated crouch envelope.
        if(state.isCrouching) {
            m.body.xRot-=.5f;m.rightArm.xRot-=.4f;m.leftArm.xRot-=.4f;
            m.rightLeg.z-=4;m.leftLeg.z-=4;m.head.y-=4.2f;m.body.y-=3.2f;m.leftArm.y-=3.2f;m.rightArm.y-=3.2f;
        }
        float air=p.airborne(),c=p.crouching()*(1-air),move=p.movement()*(1-air*.88f),run=p.running();
        float wave=(float)Math.sin(p.phase()),opposite=-wave;
        float stride=(.43f+.38f*run)*move*(1-c*.65f);
        float right=-wave*stride-.72f*c-.24f*p.landing(),left=-opposite*stride-.72f*c-.24f*p.landing();
        float rightKnee=.055f+square(Math.max(0,(float)Math.sin(p.phase()+.65)))*(.33f+.6f*run)*move+1.42f*c+.48f*p.landing();
        float leftKnee=.055f+square(Math.max(0,(float)Math.sin(p.phase()+Math.PI+.65)))*(.33f+.6f*run)*move+1.42f*c+.48f*p.landing();
        float tucked=-.3f-.25f*p.takeoff()+.12f*p.falling();
        right=lerp(right,tucked-.08f*run,air);left=lerp(left,tucked+.08f*run,air);
        rightKnee=lerp(rightKnee,.58f+.48f*p.takeoff()-.13f*p.falling(),air);
        leftKnee=lerp(leftKnee,.58f+.48f*p.takeoff()-.13f*p.falling(),air);
        m.rightLeg.xRot=right;m.leftLeg.xRot=left;
        m.rightLeg.yRot=.015f;m.leftLeg.yRot=-.015f;m.rightLeg.zRot=.035f*c;m.leftLeg.zRot=-.035f*c;
        HumanJoints.shin(m.rightLeg).xRot=rightKnee;HumanJoints.shin(m.leftLeg).xRot=leftKnee;
        HumanJoints.foot(m.rightLeg).xRot=-right-rightKnee-.1f*air;
        HumanJoints.foot(m.leftLeg).xRot=-left-leftKnee-.1f*air;
        float reach=Math.max(reach(right,rightKnee),reach(left,leftKnee));
        float sink=(HumanBodyModel.LEG_LENGTH-reach)*(1-air);
        float hipZ=-.8f*c,sway=wave*.15f*move*(1-c),lean=.055f*move+.22f*run*move+.5f*c+.1f*p.takeoff()+.12f*p.landing();
        float roll=wave*.025f*move*(1-c),breath=(float)Math.sin(state.ageInTicks*.08f)*.025f*(1-move)*(1-air);
        m.rightLeg.y+=sink;m.leftLeg.y+=sink;m.rightLeg.z+=hipZ;m.leftLeg.z+=hipZ;
        float armAmplitude=(.32f+.34f*run)*move*(1-c*.6f),armBias=-.2f*run*move-.17f*air;
        m.rightArm.xRot=wave*armAmplitude+armBias+.16f*c;
        m.leftArm.xRot=-wave*armAmplitude+armBias+.16f*c;
        if(state.rightArmPose==HumanoidModel.ArmPose.ITEM)m.rightArm.xRot=m.rightArm.xRot*.55f-.26f;
        if(state.leftArmPose==HumanoidModel.ArmPose.ITEM)m.leftArm.xRot=m.leftArm.xRot*.55f-.26f;
        m.rightArm.zRot=.035f+.075f*run*move;m.leftArm.zRot=-m.rightArm.zRot;
        HumanJoints.forearm(m.rightArm).xRot=-.1f-.8f*run*move-.24f*air-.14f*c;
        HumanJoints.forearm(m.leftArm).xRot=-.1f-.8f*run*move-.24f*air-.14f*c;
        for(ModelPart part:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm})upper(part,lean,roll,sink+breath,hipZ,sway,part==m.head?.2f:1);
    }
    private static float square(float f){return f*f;}
    private static float lerp(float a,float b,float w){return a+(b-a)*w;}
    private static float reach(float thigh,float knee){return (float)(HumanJoints.KNEE_Y*Math.cos(thigh)+(HumanJoints.ANKLE_Y-HumanJoints.KNEE_Y)*Math.cos(thigh+knee)+HumanBodyModel.LEG_LENGTH-HumanJoints.ANKLE_Y);}
    private static void upper(ModelPart p,float lean,float roll,float sink,float z,float sway,float lookCompensation) {
        float x=p.x,y=p.y-HumanBodyModel.HIP_Y,zz=p.z;
        float cy=(float)Math.cos(lean),sy=(float)Math.sin(lean),cr=(float)Math.cos(roll),sr=(float)Math.sin(roll);
        float yy=y*cy-zz*sy;zz=y*sy+zz*cy;
        p.x=x*cr-yy*sr+sway;p.y=x*sr+yy*cr+HumanBodyModel.HIP_Y+sink;p.z=zz+z;
        p.xRot+=lean*lookCompensation;p.zRot+=roll*lookCompensation;
    }
    private HumanAnimations() { }
}
