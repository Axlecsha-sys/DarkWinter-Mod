// Advances a short terrain climb while clipping each displacement against collisions.
package com.dark.winter.movement;

import java.util.function.Function;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One short, collision-clipped climb. Never writes position, step attribute or jump cooldown. */
public final class TerrainStepMotion {
    public record Pose(double progress,double weight,boolean right) {
        public static final Pose NONE=new Pose(0,0,false);
    }
    private boolean active, lastRight;
    private int startTick, appliedTick=Integer.MIN_VALUE, finishTick=Integer.MIN_VALUE, duration;
    private int intentTick=Integer.MIN_VALUE, finishedSerial;
    private boolean intentKnown, intent=true, nextRight;
    private double startX,startY,startZ,targetY,dirX,dirZ,lastX,lastY,lastZ;
    private Object world;

    public void intent(int tick,boolean allowed,boolean right) {
        intentKnown=true; intentTick=tick; intent=allowed; nextRight=right;
    }
    public boolean active() { return active; }
    public int finishedSerial() { return finishedSerial; }
    public boolean right() { return lastRight; }
    public boolean recent(int tick) { return active || (finishTick!=Integer.MIN_VALUE && tick-finishTick>=0 && tick-finishTick<=2); }
    public void cancel() { active=false; finishTick=Integer.MIN_VALUE; }
    public Pose pose(int tick,float partial) {
        if (active) {
            double t=Math.max(0,Math.min(1,(tick-startTick+(double)partial)/duration));
            return new Pose(t,1,lastRight);
        }
        if (finishTick!=Integer.MIN_VALUE) {
            double age=tick-finishTick+(double)partial;
            if (age>=0 && age<2) return new Pose(1,1-StepCurve.eased(age/2),lastRight);
        }
        return Pose.NONE;
    }

    public Vec3 collide(Player p,Vec3 requested,Function<Vec3,Vec3> vanilla) {
        int tick=p.tickCount;
        boolean requestedByPlayer=!intentKnown || (intent && tick-intentTick>=0 && tick-intentTick<=1);
        if (!TerrainStepAssist.eligible(p) || !requestedByPlayer
                || !Double.isFinite(requested.x+requested.y+requested.z)
                || requested.y>0.05 || p.getDeltaMovement().y>0.05) {
            cancel(); return vanilla.apply(requested);
        }
        float ordinary=p.maxUpStep();
        if (active && (world!=p.level() || tick<startTick || tick-startTick>duration+5
                || Math.abs(p.getX()-lastX)>0.8 || Math.abs(p.getY()-lastY)>0.5
                || Math.abs(p.getZ()-lastZ)>0.8 || p.getY()<startY-0.02 || p.getY()>targetY+0.02)) cancel();
        // An extra SELF move may be a zero-motion bookkeeping call after the first
        // move has already crossed the lip. It must neither lift again nor probe a
        // new obstacle/cancel the climb just because its horizontal input is zero.
        if (appliedTick==tick) {
            if (!active) return vanilla.apply(requested);
            double n=Math.hypot(requested.x,requested.z);
            if (n>=0.001 && (!TerrainStepAssist.forward(p,requested)
                    || (requested.x*dirX+requested.z*dirZ)/n<0.5)) {
                cancel();return vanilla.apply(requested);
            }
            Vec3 result=vanilla.apply(new Vec3(requested.x,0,requested.z));
            remember(p,result);
            return result;
        }
        if (!TerrainStepAssist.forward(p,requested)) {
            if (active) cancel();
            return vanilla.apply(requested);
        }
        if (!active) {
            Vec3 normal=vanilla.apply(requested);
            float candidate=TerrainStepAssist.height(p,requested,ordinary);
            if (candidate<=ordinary || normal.y>ordinary+0.01
                    || Math.hypot(normal.x-requested.x,normal.z-requested.z)<1e-4) return normal;
            startX=p.getX();startY=p.getBoundingBox().minY;startZ=p.getZ();
            targetY=startY+candidate;
            double n=Math.hypot(requested.x,requested.z);
            dirX=requested.x/n;dirZ=requested.z/n;
            lastRight=intentKnown?nextRight:!lastRight;
            startTick=tick;duration=StepCurve.duration(candidate);
            appliedTick=Integer.MIN_VALUE;finishTick=Integer.MIN_VALUE;
            lastX=startX;lastY=p.getY();lastZ=startZ;world=p.level();active=true;
        }
        double n=Math.hypot(requested.x,requested.z);
        if ((requested.x*dirX+requested.z*dirZ)/n<0.5
                || !TerrainStepAssist.stillValid(p,requested,startY,targetY,ordinary)) {
            cancel();return vanilla.apply(requested);
        }
        appliedTick=tick;
        double nextY=StepCurve.target(startY,targetY,tick,startTick,duration);
        double lift=Math.max(0,nextY-p.getBoundingBox().minY);
        double progress=StepCurve.progress(tick,startTick,duration);
        // Entity.move compares its original request with the collision result.
        // Scaling unblocked X/Z here would fabricate a wall collision and erase
        // tangential velocity. Let the actual lip alone constrain forward travel.
        Vec3 planned=new Vec3(requested.x,lift,requested.z);
        // The vanilla solver clips vertical AND horizontal displacement; no bounding-box teleport.
        Vec3 result=vanilla.apply(planned);
        if (result.y+1e-5<lift) { cancel();return result; }
        remember(p,result);
        AABB end=p.getBoundingBox().move(result.x,result.y,result.z);
        boolean supported=!p.level().noCollision(p,end.move(0,-0.02,0));
        if (progress>=1) {
            if (end.minY>=targetY-0.002 && supported) {
                active=false;finishTick=tick;finishedSerial++;
            } else {
                // A removed/narrow landing must not leave the player hovering at
                // the target while waiting for support. Gravity resumes next move.
                cancel();
            }
        }
        return result;
    }

    private void remember(Player p,Vec3 result) {
        lastX=p.getX()+result.x;lastY=p.getY()+result.y;lastZ=p.getZ()+result.z;
    }
}
