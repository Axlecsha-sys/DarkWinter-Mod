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
    /** These states own the whole pose; an upper-body item action does not release planted legs. */
    static boolean lowerBodyBlocked(AvatarRenderState s) {
        return s.isSpectator||s.isPassenger||s.isFallFlying||s.isVisuallySwimming||s.swimAmount>.001f
                ||s.pose==Pose.SLEEPING||s.isAutoSpinAttack;
    }
    public static boolean protectedPose(AvatarRenderState s) {
        return lowerBodyBlocked(s)||s.isUsingItem||s.swingAnimation>0
                ||(s.rightArmPose!=HumanoidModel.ArmPose.EMPTY&&s.rightArmPose!=HumanoidModel.ArmPose.ITEM)
                ||(s.leftArmPose!=HumanoidModel.ArmPose.EMPTY&&s.leftArmPose!=HumanoidModel.ArmPose.ITEM);
    }
    public static void apply(PlayerModel m,AvatarRenderState state,HumanMotionTimeline.Snapshot p) {
        if(!m.root().hasChild("darkwinter_human"))return;
        HumanJoints.syncOverlays(m);
        if(p==null||!p.enabled()||lowerBodyBlocked(state))return;
        boolean protectUpper=protectedPose(state);
        // Undo native leg crouching even while native arms/body retain their item pose.
        if(state.isCrouching) {
            m.rightLeg.z-=4;m.leftLeg.z-=4;
            if(!protectUpper){
                m.body.xRot-=.5f;m.rightArm.xRot-=.4f;m.leftArm.xRot-=.4f;
                m.head.y-=4.2f;m.body.y-=3.2f;m.leftArm.y-=3.2f;m.rightArm.y-=3.2f;
            }
        }
        // First-person legs use the same tick/distance-owned phase as third-person feet and contacts.
        var shared=((FabricRenderState)state).getData(ThirdPersonAnimations.POSE);
        double phase=shared==null?p.phase():shared.phase();
        float air=shared==null?p.airborne():shared.airborne(),c=(shared==null?p.crouching():shared.crouching())*(1-air);
        float move=shared==null?p.movement():shared.movement(),run=shared==null?p.running():shared.running();
        float takeoff=shared==null?p.takeoff():shared.takeoff(),landing=shared==null?p.landing():shared.landing(),falling=shared==null?p.falling():shared.falling();
        float snow=shared==null?0:shared.snow(),wading=com.winterkore.engine.surface.geometry.HumanGaitCurves.snowWeight(snow);
        float wave=(float)Math.sin(phase);
        var right=LocomotionCycles.leg(phase,move,run,c,air,takeoff,landing,falling,snow,false);
        var left=LocomotionCycles.leg(phase,move,run,c,air,takeoff,landing,falling,snow,true);
        m.rightLeg.xRot=right.thigh();m.leftLeg.xRot=left.thigh();
        float turn=shared==null?0:shared.turn();
        m.rightLeg.yRot=.015f-turn*.025f;m.leftLeg.yRot=-.015f-turn*.025f;
        m.rightLeg.zRot=.025f*c;m.leftLeg.zRot=-.025f*c;
        HumanJoints.shin(m.rightLeg).xRot=right.knee();HumanJoints.shin(m.leftLeg).xRot=left.knee();
        HumanJoints.foot(m.rightLeg).xRot=right.foot();HumanJoints.foot(m.leftLeg).xRot=left.foot();
        float reach=Math.max(FootContact.depth(m,state,m.rightLeg,false),
                FootContact.depth(m,state,m.leftLeg,true));
        float legLength=CharacterVisuals.hasWorldLegs(m)?ThirdPersonBodyModel.LEG_LENGTH:HumanBodyModel.LEG_LENGTH;
        float sink=(legLength-reach)*(1-air);
        float groundedMove=move*(1-air);
        float transfer=com.winterkore.engine.surface.geometry.HumanGaitCurves.pelvisShift(phase,move,c,air,snow);
        float hipZ=-.78f*c,sway=wave*.15f*groundedMove*(1-c)+transfer,lean=(.055f+.24f*wading)*groundedMove+.22f*run*groundedMove+.5f*c+.1f*takeoff+.12f*landing;
        float roll=wave*(.025f+.045f*wading)*groundedMove*(1-c),breath=(float)Math.sin(state.ageInTicks*.08f)*.025f*(1-move)*(1-air);
        m.rightLeg.x+=transfer;m.leftLeg.x+=transfer;
        m.rightLeg.y+=sink;m.leftLeg.y+=sink;m.rightLeg.z+=hipZ;m.leftLeg.z+=hipZ;
        // EntityRenderDispatcher applies AvatarRenderer's crouch offset before the extracted
        // body hook as well. The shared solver already planted these world-space feet.
        if(CharacterVisuals.hasWorldLegs(m)&&state.isCrouching){
            float renderOffset=2/.9375f;m.rightLeg.y-=renderOffset;m.leftLeg.y-=renderOffset;
        }
        // Item/attack animation keeps its native upper body; the same shared legs still press the snow.
        if(protectUpper)return;
        m.rightArm.xRot=LocomotionCycles.arm(phase,move,run,c,air,takeoff,falling,snow,false);
        m.leftArm.xRot=LocomotionCycles.arm(phase,move,run,c,air,takeoff,falling,snow,true);
        if(state.rightArmPose==HumanoidModel.ArmPose.ITEM)m.rightArm.xRot=m.rightArm.xRot*.55f-.26f;
        if(state.leftArmPose==HumanoidModel.ArmPose.ITEM)m.leftArm.xRot=m.leftArm.xRot*.55f-.26f;
        m.rightArm.zRot=.035f+.075f*run*move+.10f*wading*groundedMove;m.leftArm.zRot=-m.rightArm.zRot;
        HumanJoints.forearm(m.rightArm).xRot=LocomotionCycles.elbow(phase,move,run,c,air,takeoff,snow,false);
        HumanJoints.forearm(m.leftArm).xRot=LocomotionCycles.elbow(phase,move,run,c,air,takeoff,snow,true);
        for(ModelPart part:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm})upper(part,lean,roll,sink+breath,hipZ,sway,part==m.head?.2f:1);
    }
    private static void upper(ModelPart p,float lean,float roll,float sink,float z,float sway,float lookCompensation) {
        float x=p.x,y=p.y-HumanBodyModel.HIP_Y,zz=p.z;
        float cy=(float)Math.cos(lean),sy=(float)Math.sin(lean),cr=(float)Math.cos(roll),sr=(float)Math.sin(roll);
        float yy=y*cy-zz*sy;zz=y*sy+zz*cy;
        p.x=x*cr-yy*sr+sway;p.y=x*sr+yy*cr+HumanBodyModel.HIP_Y+sink;p.z=zz+z;
        p.xRot+=lean*lookCompensation;p.zRot+=roll*lookCompensation;
    }
    private HumanAnimations() { }
}
