// Computes bounded translational and orbital lag for the shoulder camera.
package com.dark.winter.client.motion;

/** Bounded camera translation/orbit lag. Never changes view rotation or the player's physics. */
public final class CameraWeight {
    public record Offset(double x,double y,double z,double yawDegrees,double side,double back) {
        public static final Offset ZERO=new Offset(0,0,0,0,0,0);
    }
    private boolean ready, wasGrounded;
    private double prevX,prevY,prevZ,prevYaw,previousTicks;
    private double x,y,z,yaw,airTime,fallSpeed,landingAge=1,landingAmount,detailGain;
    public void reset() { ready=false; x=y=z=yaw=airTime=fallSpeed=landingAmount=detailGain=0; landingAge=1; }
    public Offset update(double px,double py,double pz,double viewYaw,double ticks,
                         boolean grounded,double verticalSpeed,boolean active,GaitPose pose,MotionSettings s) {
        if (!Double.isFinite(px+py+pz+viewYaw+ticks+verticalSpeed)) {reset(); return Offset.ZERO;}
        double dt=(ticks-previousTicks)/20;
        double dx=px-prevX,dy=py-prevY,dz=pz-prevZ;
        if (!ready || dt<0 || dt>0.25 || dx*dx+dy*dy+dz*dz>9) {
            reset(); ready=true; prevX=px;prevY=py;prevZ=pz;prevYaw=viewYaw;previousTicks=ticks;
            wasGrounded=grounded; return Offset.ZERO;
        }
        if (dt>1e-8) {
            double dyaw=MotionMath.wrap(viewYaw-prevYaw);
            prevX=px;prevY=py;prevZ=pz;prevYaw=viewYaw;previousTicks=ticks;
            landingAge=Math.min(0.30,landingAge+dt);
            detailGain=MotionMath.decay(detailGain,active&&s.enabled()&&s.camera()?1:0,dt,0.065);
            if (!active || !s.enabled() || !s.camera()) {
                double a=Math.exp(-dt/0.045);
                x*=a;y*=a;z*=a;yaw*=a;landingAmount*=a;
                airTime=fallSpeed=0; wasGrounded=grounded;
            } else {
                double run=pose.running();
                x=MotionMath.lag(x,dx*0.66,dt,MotionMath.mix(0.12,0.15,run));
                z=MotionMath.lag(z,dz*0.66,dt,MotionMath.mix(0.12,0.15,run));
                double limit=MotionMath.mix(0.25,0.34,run),length=Math.hypot(x,z);
                if(length>limit){x*=limit/length;z*=limit/length;}
                boolean climbing=pose.climbWeight()>0.001;
                // Actual movement is already gradual. Don't add the old 0.7-block catch-up.
                y=MotionMath.clamp(MotionMath.lag(y,dy*(climbing?0.30:(grounded?1:0.25)),dt,
                        climbing?0.045:(grounded?0.16:0.03)),climbing?-0.12:(grounded?-0.70:-0.30),0.30);
                // Large single-frame warps reset rather than whipping the camera around.
                if (Math.abs(dyaw)>130) yaw=0;
                else yaw=MotionMath.clamp(MotionMath.lag(yaw,dyaw,dt,MotionMath.mix(0.12,0.16,run)),
                        -MotionMath.mix(8.0,13.0,run),MotionMath.mix(8.0,13.0,run));
                if (climbing) { airTime=fallSpeed=landingAmount=0; landingAge=0.30; }
                else if (!grounded) {airTime+=dt;fallSpeed=Math.max(fallSpeed,Math.max(0,-verticalSpeed));}
                else {
                    if(!wasGrounded && airTime>=0.12 && fallSpeed>0.16){landingAge=0;landingAmount=Math.min(0.075,(fallSpeed-0.16)*0.18);}
                    airTime=fallSpeed=0;
                }
                wasGrounded=grounded;
            }
        }
        double strength=s.enabled()&&s.camera()?s.cameraWeight():0;
        double a=pose.amount()*detailGain*(1.0-pose.climbWeight());
        double contact=MotionMath.contact(pose.phase());
        double gaitY=-MotionMath.mix(0.045,0.075,pose.running())*contact*a*s.stepWeight();
        double sway=Math.sin(Math.PI*pose.phase())*MotionMath.mix(0.025,0.040,pose.running())*a*s.stepWeight();
        double recoil=-0.030*contact*a*s.stepWeight();
        double t=MotionMath.clamp(landingAge/0.30,0,1);
        double landing=landingAmount*16*t*t*(1-t)*(1-t);
        double yawOut=MotionMath.clamp(yaw*s.turnWeight(),-17,17);
        return new Offset(x*strength,MotionMath.clamp(y+gaitY-landing,-0.78,0.32)*strength,z*strength,
                MotionMath.clamp(yawOut*strength,-18,18),sway*strength,recoil*strength);
    }
}
