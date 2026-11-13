// Applies visual third-person poses without changing input or physics.
package com.dark.winter.client.equipment;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Third-person pose only. No input, velocity, camera or first-person state is written here. */
public final class ThirdPersonAnimations {
    public static final RenderStateDataKey<ThirdPersonMotionTimeline.Snapshot> POSE=RenderStateDataKey.create(()->"darkwinter:third_person_animation");
    private static final Map<Player,ThirdPersonMotionTimeline> TIMELINES=new WeakHashMap<>();
    private static Object world;
    private static boolean initialized;
    public static void init() {
        if(initialized)return;initialized=true;FootContact.init();
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(world!=client.level){TIMELINES.clear();world=client.level;}
            if(client.level!=null)for(Player player:client.level.players())tick(player);
        });
    }
    private static void tick(Player player) {
        boolean enabled=player.isAlive()&&!player.isSpectator()&&!player.isPassenger()&&!player.isSleeping()
                &&!player.isFallFlying()&&!player.isVisuallySwimming()&&!player.isInWater()&&!player.isInLava()
                &&!player.onClimbable()&&!player.getAbilities().flying;
        TIMELINES.computeIfAbsent(player,key->new ThirdPersonMotionTimeline()).tick(new ThirdPersonMotionTimeline.Input(
                player.tickCount,player.getX(),player.getY(),player.getZ(),player.yBodyRot,(float)player.getDeltaMovement().y,
                com.winterkore.engine.surface.host.SnowLocomotion.grounded(player),player.isSprinting(),player.isCrouching(),enabled,player.isUsingItem()||player.isSwinging(),
                com.winterkore.engine.surface.host.SnowLocomotion.gaitWeight(player)));
    }
    /** Pressure and the avatar sample one timeline; packet timing cannot shift the local foot phase. */
    public static com.winterkore.engine.surface.geometry.HumanGaitTimeline.Snapshot gait(Player player,float partial){
        tick(player);return TIMELINES.get(player).gait(partial);
    }
    public static void extract(LivingEntity entity,LivingEntityRenderState state,float partial) {
        ThirdPersonMotionTimeline.Snapshot snapshot=null;
        if(entity instanceof Player player&&state instanceof AvatarRenderState avatar) {
            // Bootstrap once if the avatar is extracted before its first client tick.
            tick(player);
            var timeline=TIMELINES.get(player);
            snapshot=timeline.sample(partial);
            if(player==net.minecraft.client.Minecraft.getInstance().player)
                com.winterkore.engine.surface.client.SnowLocalContacts.capture(player,avatar,timeline.gait(partial));
        }
        ((FabricRenderState)state).setData(POSE,snapshot);
    }
    private static float mix(float a,float b,float w){return a+(b-a)*w;}
    public static void apply(PlayerModel m,AvatarRenderState state,ThirdPersonMotionTimeline.Snapshot p) {
        if(!ThirdPersonBodyModel.isThirdPerson(m))return;
        HumanJoints.syncOverlays(m);
        if(p==null||!p.enabled()||HumanAnimations.lowerBodyBlocked(state))return;
        float blend=HumanAnimations.protectedPose(state)?0:p.poseWeight();
        var originals=blend<.999f?new java.util.IdentityHashMap<ModelPart,net.minecraft.client.model.geom.PartPose>():null;
        // Only upper-body subtrees blend to native item/attack poses. Grounded leg/boot joints
        // always remain on the shared foot curve, including the return from an upper-body action.
        if(blend<.999f)for(ModelPart upper:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm})
            for(ModelPart part:upper.getAllParts())originals.put(part,part.storePose());
        if(state.isCrouching) {
            m.body.xRot-=.5f;m.rightArm.xRot-=.4f;m.leftArm.xRot-=.4f;
            m.rightLeg.z-=4;m.leftLeg.z-=4;m.head.y-=4.2f;m.body.y-=3.2f;m.leftArm.y-=3.2f;m.rightArm.y-=3.2f;
        }
        float air=p.airborne(),c=p.crouching()*(1-air),move=p.movement(),run=p.running();
        float wave=(float)Math.sin(p.phase());
        var right=LocomotionCycles.leg(p.phase(),move,run,c,air,p.takeoff(),p.landing(),p.falling(),p.snow(),false);
        var left=LocomotionCycles.leg(p.phase(),move,run,c,air,p.takeoff(),p.landing(),p.falling(),p.snow(),true);
        m.rightLeg.xRot=right.thigh();m.leftLeg.xRot=left.thigh();
        m.rightLeg.yRot=.015f-p.turn()*.025f;m.leftLeg.yRot=-.015f-p.turn()*.025f;
        m.rightLeg.zRot=.025f*c;m.leftLeg.zRot=-.025f*c;
        HumanJoints.shin(m.rightLeg).xRot=right.knee();HumanJoints.shin(m.leftLeg).xRot=left.knee();
        HumanJoints.foot(m.rightLeg).xRot=right.foot();HumanJoints.foot(m.leftLeg).xRot=left.foot();
        float reach=Math.max(FootContact.depth(m,state,m.rightLeg,false),
                FootContact.depth(m,state,m.leftLeg,true));
        float sink=(ThirdPersonBodyModel.LEG_LENGTH-reach)*(1-air);
        float groundedMove=move*(1-air),wading=com.winterkore.engine.surface.geometry.HumanGaitCurves.snowWeight(p.snow());
        float transfer=com.winterkore.engine.surface.geometry.HumanGaitCurves.pelvisShift(p.phase(),move,c,air,p.snow());
        float hipZ=-.78f*c,sway=wave*.14f*groundedMove*(1-c)+transfer;
        // Keep the crouched upper body below the local eye while preserving the shared hip and leg pose.
        float crouchLean=CharacterVisuals.isBody(state)?.16f:.47f;
        float lean=(.052f+.24f*wading)*groundedMove+.22f*run*groundedMove+crouchLean*c+.09f*p.takeoff()+.12f*p.landing()+.025f*p.acceleration();
        float roll=wave*(.022f+.045f*wading)*groundedMove*(1-c)-p.turn()*.018f;
        float breath=(float)Math.sin(state.ageInTicks*.075f)*.018f*(1-move)*(1-air);
        m.rightLeg.x+=transfer;m.leftLeg.x+=transfer;
        m.rightLeg.y+=sink;m.leftLeg.y+=sink;m.rightLeg.z+=hipZ;m.leftLeg.z+=hipZ;
        m.rightArm.xRot=LocomotionCycles.arm(p.phase(),move,run,c,air,p.takeoff(),p.falling(),p.snow(),false);
        m.leftArm.xRot=LocomotionCycles.arm(p.phase(),move,run,c,air,p.takeoff(),p.falling(),p.snow(),true);
        if(state.rightArmPose==HumanoidModel.ArmPose.ITEM)m.rightArm.xRot=m.rightArm.xRot*.55f-.26f;
        if(state.leftArmPose==HumanoidModel.ArmPose.ITEM)m.leftArm.xRot=m.leftArm.xRot*.55f-.26f;
        m.rightArm.zRot=.025f+.05f*run*move+.10f*wading*groundedMove;m.leftArm.zRot=-m.rightArm.zRot;
        HumanJoints.forearm(m.rightArm).xRot=LocomotionCycles.elbow(p.phase(),move,run,c,air,p.takeoff(),p.snow(),false);
        HumanJoints.forearm(m.leftArm).xRot=LocomotionCycles.elbow(p.phase(),move,run,c,air,p.takeoff(),p.snow(),true);
        float twist=p.turn()*.065f-wave*(.045f+.065f*wading)*groundedMove;
        for(ModelPart part:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm})
            upper(part,lean,roll,twist,sink+breath,hipZ,sway,part==m.head?.2f:1);
        // Blend the upper body back to the native item/attack pose without moving planted soles.
        if(blend<.999f)for(var entry:originals.entrySet()) {
            ModelPart part=entry.getKey();var o=entry.getValue();
            part.x=mix(o.x(),part.x,blend);part.y=mix(o.y(),part.y,blend);part.z=mix(o.z(),part.z,blend);
            part.xRot=mix(o.xRot(),part.xRot,blend);part.yRot=mix(o.yRot(),part.yRot,blend);part.zRot=mix(o.zRot(),part.zRot,blend);
        }
        // AvatarRenderer 26.3 adds a separate -2/16 world-space crouch offset
        // after this articulated solver has already planted the soles at y=24.
        // Its avatar scale is .9375, so undo that outside displacement here once,
        // on every root body part equally. Clothing and held items share these
        // same pivots; moving only feet (or only the skin root) would split the rig.
        if(state.isCrouching) {
            float renderOffset=2/.9375f;
            m.rightLeg.y-=renderOffset;m.leftLeg.y-=renderOffset;
            for(ModelPart part:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm})
                part.y-=renderOffset*blend;
        }
    }
    private static void upper(ModelPart p,float lean,float roll,float twist,float sink,float z,float sway,float headCompensation) {
        float x=p.x,y=p.y-ThirdPersonBodyModel.HIP_Y,zz=p.z;
        float cy=(float)Math.cos(lean),sy=(float)Math.sin(lean),cr=(float)Math.cos(roll),sr=(float)Math.sin(roll);
        float yy=y*cy-zz*sy;zz=y*sy+zz*cy;
        float xx=x*cr-yy*sr;yy=x*sr+yy*cr;
        float ct=(float)Math.cos(twist),st=(float)Math.sin(twist);
        p.x=xx*ct+zz*st+sway;p.y=yy+ThirdPersonBodyModel.HIP_Y+sink;p.z=-xx*st+zz*ct+z;
        p.xRot+=lean*headCompensation;p.zRot+=roll*headCompensation;
        p.yRot+=twist*headCompensation;
    }
    private ThirdPersonAnimations() { }
}
