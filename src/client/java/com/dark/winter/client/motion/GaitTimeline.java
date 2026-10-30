// Advances gait phase from supported travel measured in game ticks.
package com.dark.winter.client.motion;

/** Tick-space gait. Phase advances with actual supported travel, not held keys or FPS. */
public final class GaitTimeline {
    private double phase=0.5, previousPhase=0.5, amount, oldAmount, running, oldRunning;
    private double lean, oldLean, turn, oldTurn, yawLag, oldYawLag, oldSpeed;
    private boolean initialized;
    private double oldBodyYaw;
    public void reset(double bodyYaw) {
        phase=previousPhase=0.5; amount=oldAmount=running=oldRunning=0;
        lean=oldLean=turn=oldTurn=yawLag=oldYawLag=oldSpeed=0;
        oldBodyYaw=bodyYaw; initialized=true;
    }
    /** Returns true for at most one foot contact in this tick. */
    public boolean tick(double horizontalBlocks, double verticalBlocks, double bodyYaw,
                        boolean supported, boolean intentional, boolean sprinting,
                        boolean eligible, MotionSettings s) {
        if (!initialized || !Double.isFinite(horizontalBlocks) || !Double.isFinite(verticalBlocks) || !Double.isFinite(bodyYaw)
                || horizontalBlocks>1.6 || Math.abs(verticalBlocks)>2.5) {
            reset(Double.isFinite(bodyYaw)?bodyYaw:0); return false;
        }
        previousPhase=phase; oldAmount=amount; oldRunning=running;
        oldLean=lean; oldTurn=turn; oldYawLag=yawLag;
        boolean walking=eligible && supported && intentional && horizontalBlocks>0.002;
        double speed=walking?horizontalBlocks*20:0;
        running=MotionMath.decay(running, walking&&sprinting?1:0,0.05,0.18);
        amount=MotionMath.decay(amount,walking?MotionMath.clamp(speed/2.4,0,1):0,0.05,walking?0.075:0.08);
        if (amount<0.0001) amount=0;
        if (walking) {
            double step=MotionMath.mix(s.walkStepBlocks(),s.runStepBlocks(),running);
            phase+=horizontalBlocks/step;
        }
        double yawDelta=MotionMath.wrap(bodyYaw-oldBodyYaw);
        oldBodyYaw=bodyYaw;
        if (!eligible || Math.abs(yawDelta)>120) { yawLag=0; turn=MotionMath.decay(turn,0,0.05,0.06); }
        else {
            yawLag=MotionMath.clamp(MotionMath.lag(yawLag,yawDelta,0.05,0.12),-10,10);
            turn=MotionMath.decay(turn,MotionMath.clamp(yawDelta/18,-1,1),0.05,0.12);
        }
        double acceleration=MotionMath.clamp((speed-oldSpeed)/0.05/18,-1,1);
        oldSpeed=speed;
        double slope=walking?MotionMath.clamp(verticalBlocks/Math.max(horizontalBlocks,0.08),-0.4,0.7):0;
        double targetLean=eligible ? amount*(0.055+running*0.065+slope*0.035)+acceleration*0.040 : 0;
        lean=MotionMath.decay(lean,targetLean,0.05,0.12);
        return walking && Math.floor(phase)>Math.floor(previousPhase);
    }
    public GaitPose sample(double partial, boolean arms, MotionSettings s) {
        double t=MotionMath.clamp(partial,0,1);
        return new GaitPose(MotionMath.mix(previousPhase,phase,t),
                MotionMath.mix(oldAmount,amount,t),MotionMath.mix(oldRunning,running,t),
                MotionMath.mix(oldLean,lean,t),MotionMath.mix(oldTurn,turn,t),
                MotionMath.mix(oldYawLag,yawLag,t),arms,s.bodyWeight());
    }
    public void plant(boolean right) {
        long next=(long)Math.floor(phase)+1;
        if (((next&1L)==0)!=right) next++;
        phase=previousPhase=next;
    }
    public double phase() { return phase; }
}
