// Computes collision-aware shoulder-camera offsets and motion smoothing.
package com.dark.winter.client;

import com.dark.winter.client.motion.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Replacement for the first camera prototype. All offsets go through the existing collision test. */
public final class ShoulderCameraMotion {
    private final CameraWeight weight=new CameraWeight();
    private Object world,player;
    public void reset(){weight.reset();world=player=null;}
    public Vec3 apply(Minecraft mc,Vec3 eye,Vec3 forward,Vec3 up,Vec3 right,
                      float side,double height,double distance,float partial,boolean active) {
        MotionConfig.poll();
        if(world!=mc.level||player!=mc.player){reset();world=mc.level;player=mc.player;}
        double yaw=Math.toDegrees(Math.atan2(-forward.x,forward.z));
        GaitPose gait=GaitRuntime.sample(partial);
        CameraWeight.Offset o=weight.update(eye.x,eye.y,eye.z,yaw,
                mc.player.tickCount+(double)partial,mc.player.onGround(),mc.player.getDeltaMovement().y,
                active&&!GaitRuntime.precision(),gait,MotionConfig.get());
        Vec3 arm=up.scale(height).add(right.scale(side)).subtract(forward.scale(distance));
        double a=Math.toRadians(o.yawDegrees()),c=Math.cos(a),s=Math.sin(a);
        // Rotate the shoulder arm, not the look direction. Crosshair/raycast remain responsive.
        Vec3 orbit=new Vec3(c*arm.x-s*arm.z,arm.y,s*arm.x+c*arm.z);
        return eye.add(orbit).add(o.x(),o.y(),o.z())
                .add(right.scale(o.side())).subtract(forward.scale(o.back()));
    }
}
