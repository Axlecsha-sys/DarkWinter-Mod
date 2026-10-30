// Coordinates the client gait timeline, body pose, step audio, and camera movement.
package com.dark.winter.client.motion;

import com.dark.winter.client.SprintStamina;
import com.dark.winter.movement.TerrainStepAccess;
import com.dark.winter.movement.TerrainStepMotion;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Client thread bridge. One tick timeline drives sound, body pose and camera movement. */
public final class GaitRuntime {
    public static final RenderStateDataKey<GaitPose> POSE=RenderStateDataKey.create(()->"darkwinter:weighted_gait");
    private static final GaitTimeline GAIT=new GaitTimeline();
    private static final WalkEnvelope WALK=new WalkEnvelope();
    private static Object world;
    private static LocalPlayer player;
    private static Vec3 previousPos;
    private static int lastTick=-1, preciseUntil=-1;
    private static int lastTerrainSerial;
    private static boolean intentional;
    private static boolean allowed;
    private GaitRuntime() { }

    public static boolean posture(LocalPlayer p) {
        return p!=null && p.isAlive() && !p.getAbilities().flying && !p.isSpectator()
                && !p.isPassenger() && !p.isInWater() && !p.isInLava()
                && !p.isFallFlying() && !p.onClimbable() && !p.isSleeping()
                && !p.isCrouching() && !p.isVisuallySwimming();
    }
    private static void bind(Minecraft mc) {
        if(player==mc.player && world==mc.level) return;
        player=mc.player;world=mc.level;previousPos=null;lastTick=-1;preciseUntil=-1;
        intentional=false;allowed=false;lastTerrainSerial=0;WALK.reset();StepAudio.reset();
        GAIT.reset(player==null?0:player.yBodyRot);
    }
    public static Vec2 filterMovement(Minecraft mc,Vec2 raw) {
        MotionConfig.poll();bind(mc);
        intentional=raw.x*raw.x+raw.y*raw.y>0.0001f && mc.gui.screen()==null;
        boolean eligible=posture(mc.player) && mc.gui.screen()==null;
        boolean sprint=eligible && raw.y>0.001f && !mc.options.keyDown.isDown()
                && mc.options.keySprint.isDown() && SprintStamina.canAutoRun();
        if (mc.player!=null) {
            TerrainStepMotion climb=((TerrainStepAccess)mc.player).darkwinter$stepMotion();
            boolean right=(((long)Math.floor(GAIT.phase())+1L)&1L)==0;
            climb.intent(mc.player.tickCount,eligible && raw.y>0.01f && !mc.options.keyJump.isDown(),right);
        }
        WalkEnvelope.Output movement=WALK.filter(raw.x,raw.y,sprint,eligible,MotionConfig.get());
        return new Vec2((float)movement.x(),(float)movement.z());
    }
    public static void tick(LocalPlayer p) {
        Minecraft mc=Minecraft.getInstance();
        if(p!=mc.player || mc.level==null) return;
        MotionConfig.poll();bind(mc);
        if(p.tickCount==lastTick) return;
        Vec3 pos=p.position();
        MotionSettings s=MotionConfig.get();
        allowed=s.enabled()&&posture(p)&&mc.gui.screen()==null;
        if(mc.options.keyAttack.isDown()||mc.options.keyUse.isDown()||p.isUsingItem()) preciseUntil=p.tickCount+5;
        if(previousPos==null || lastTick<0 || p.tickCount-lastTick>5 || p.tickCount<lastTick) {
            previousPos=pos;lastTick=p.tickCount;GAIT.reset(p.yBodyRot);StepAudio.reset();return;
        }
        Vec3 delta=pos.subtract(previousPos);previousPos=pos;lastTick=p.tickCount;
        TerrainStepMotion climb=((TerrainStepAccess)p).darkwinter$stepMotion();
        boolean climbing=climb.recent(p.tickCount);
        boolean step=GAIT.tick(Math.hypot(delta.x,delta.z),delta.y,p.yBodyRot,p.onGround()||climbing,
                intentional,p.isSprinting(),allowed,s);
        boolean planted=climb.finishedSerial()!=lastTerrainSerial;
        lastTerrainSerial=climb.finishedSerial();
        if (planted) GAIT.plant(climb.right());
        if(!allowed) {StepAudio.reset();return;}
        StepAudio.tick(p,s);
        if(s.footsteps() && !p.isSilent()) {
            if (planted) StepAudio.contact(p,s,climb.right());
            else if(step && !climbing) StepAudio.contact(p,s,((long)Math.floor(GAIT.phase())&1L)==0);
        }
    }
    public static GaitPose sample(float partial) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=player || mc.level!=world || !allowed) return GaitPose.NONE;
        boolean arms=player.getMainHandItem().isEmpty()&&player.getOffhandItem().isEmpty();
        GaitPose gait=GAIT.sample(partial,arms,MotionConfig.get());
        TerrainStepMotion.Pose climb=((TerrainStepAccess)player).darkwinter$stepMotion().pose(player.tickCount,partial);
        return gait.withClimb(climb.progress(),climb.weight(),climb.right());
    }
    public static boolean precision() {
        Minecraft mc=Minecraft.getInstance();
        return mc.player==null || mc.gui.screen()!=null || mc.options.keyAttack.isDown()
                || mc.options.keyUse.isDown() || mc.player.isUsingItem() || mc.player.tickCount<=preciseUntil;
    }
    public static boolean replaceStep(Entity entity) {
        Minecraft mc=Minecraft.getInstance();
        MotionSettings s=MotionConfig.get();
        return entity==mc.player && s.enabled()&&s.footsteps()&&posture(mc.player)
                && mc.gui.screen()==null;
    }
}
