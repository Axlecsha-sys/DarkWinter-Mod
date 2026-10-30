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
        if(initialized)return;initialized=true;
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
                player.onGround(),player.isSprinting(),player.isCrouching(),enabled,player.isUsingItem()||player.isSwinging()));
    }
    public static void extract(LivingEntity entity,LivingEntityRenderState state,float partial) {
        ThirdPersonMotionTimeline.Snapshot snapshot=null;
        if(entity instanceof Player player&&state instanceof AvatarRenderState) {
            // Bootstrap once if the avatar is extracted before its first client tick.
            tick(player);
            snapshot=TIMELINES.get(player).sample(partial);
        }
        ((FabricRenderState)state).setData(POSE,snapshot);
    }
    private static float mix(float a,float b,float w){return a+(b-a)*w;}
    private static float square(float x){return x*x;}
    private static float knee(double phase,float run,float move) {
        // The supported half is comparatively quiet; the trailing foot flexes for clearance.
        return .045f+square(Math.max(0,(float)Math.sin(phase+.52)))*(.30f+.47f*run)*move;
    }
    public static void apply(PlayerModel m,AvatarRenderState state,ThirdPersonMotionTimeline.Snapshot p) {
        if(!ThirdPersonBodyModel.isThirdPerson(m))return;
        HumanJoints.syncOverlays(m);
        if(p==null||!p.enabled())return;
        boolean protectedPose=HumanAnimations.protectedPose(state);
        float blend=p.poseWeight();
        if(protectedPose&&(!state.isUsingItem&&state.swingAnimation<=0||blend<.001f))return;
        var originals=blend<.999f?new java.util.IdentityHashMap<ModelPart,net.minecraft.client.model.geom.PartPose>():null;
        if(blend<.999f)for(ModelPart part:m.root().getAllParts())originals.put(part,part.storePose());
        if(state.isCrouching) {
            m.body.xRot-=.5f;m.rightArm.xRot-=.4f;m.leftArm.xRot-=.4f;
            m.rightLeg.z-=4;m.leftLeg.z-=4;m.head.y-=4.2f;m.body.y-=3.2f;m.leftArm.y-=3.2f;m.rightArm.y-=3.2f;
        }
        float air=p.airborne(),c=p.crouching()*(1-air),move=p.movement()*(1-air*.94f),run=p.running();
        float wave=(float)Math.sin(p.phase());
        // A rounded support-to-swing curve slows the foot near its planted extrema.
        float step=wave*(.86f+.14f*Math.abs(wave));
        float stride=(.39f+.32f*run)*move*(1-c*.62f);
        float right=-step*stride-.68f*c-.22f*p.landing(),left=step*stride-.68f*c-.22f*p.landing();
        float rk=knee(p.phase(),run,move)+1.32f*c+.44f*p.landing();
        float lk=knee(p.phase()+Math.PI,run,move)+1.32f*c+.44f*p.landing();
        float tuck=-.26f-.19f*p.takeoff()+.10f*p.falling();
        right=mix(right,tuck-.06f*run,air);left=mix(left,tuck+.06f*run,air);
        rk=mix(rk,.52f+.34f*p.takeoff()-.10f*p.falling(),air);
        lk=mix(lk,.52f+.34f*p.takeoff()-.10f*p.falling(),air);
        m.rightLeg.xRot=right;m.leftLeg.xRot=left;
        m.rightLeg.yRot=.015f-p.turn()*.025f;m.leftLeg.yRot=-.015f-p.turn()*.025f;
        m.rightLeg.zRot=.025f*c;m.leftLeg.zRot=-.025f*c;
        HumanJoints.shin(m.rightLeg).xRot=rk;HumanJoints.shin(m.leftLeg).xRot=lk;
        HumanJoints.foot(m.rightLeg).xRot=-right-rk-.07f*air;
        HumanJoints.foot(m.leftLeg).xRot=-left-lk-.07f*air;
        float reach=Math.max(reach(right,rk),reach(left,lk));
        float sink=(ThirdPersonBodyModel.LEG_LENGTH-reach)*(1-air);
        float hipZ=-.78f*c,sway=wave*.10f*move*(1-c);
        float lean=.052f*move+.18f*run*move+.47f*c+.06f*p.takeoff()+.09f*p.landing()+.025f*p.acceleration();
        float roll=wave*.018f*move*(1-c)-p.turn()*.018f;
        float breath=(float)Math.sin(state.ageInTicks*.075f)*.018f*(1-move)*(1-air);
        m.rightLeg.y+=sink;m.leftLeg.y+=sink;m.rightLeg.z+=hipZ;m.leftLeg.z+=hipZ;
        float armAmplitude=(.27f+.25f*run)*move*(1-c*.6f),armBias=-.16f*run*move-.14f*air;
        // Shoulder reaction follows the same support phase as pelvis and knees.
        m.rightArm.xRot=step*armAmplitude+armBias+.13f*c;
        m.leftArm.xRot=-step*armAmplitude+armBias+.13f*c;
        if(state.rightArmPose==HumanoidModel.ArmPose.ITEM)m.rightArm.xRot=m.rightArm.xRot*.55f-.26f;
        if(state.leftArmPose==HumanoidModel.ArmPose.ITEM)m.leftArm.xRot=m.leftArm.xRot*.55f-.26f;
        m.rightArm.zRot=.025f+.05f*run*move;m.leftArm.zRot=-m.rightArm.zRot;
        HumanJoints.forearm(m.rightArm).xRot=-.12f-.62f*run*move-.20f*air-.13f*c;
        HumanJoints.forearm(m.leftArm).xRot=-.12f-.62f*run*move-.20f*air-.13f*c;
        float twist=p.turn()*.065f-wave*.012f*move;
        for(ModelPart part:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm})
            upper(part,lean,roll,twist,sink+breath,hipZ,sway,part==m.head?.2f:1);
        // Blend back to vanilla's item/attack pose instead of snapping between two unrelated poses.
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
            float renderOffset=2/.9375f*blend;
            for(ModelPart part:new ModelPart[]{m.body,m.head,m.rightArm,m.leftArm,m.rightLeg,m.leftLeg})
                part.y-=renderOffset;
        }
    }
    private static float reach(float thigh,float knee) {
        return (float)(ThirdPersonBodyModel.KNEE_Y*Math.cos(thigh)
                +(ThirdPersonBodyModel.ANKLE_Y-ThirdPersonBodyModel.KNEE_Y)*Math.cos(thigh+knee)
                +ThirdPersonBodyModel.LEG_LENGTH-ThirdPersonBodyModel.ANKLE_Y);
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
