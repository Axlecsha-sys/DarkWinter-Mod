// Tracks tick-owned visual motion for repeatable render sampling.
package com.dark.winter.client.equipment;

/** Tick-owned visual motion. Rendering can sample this state any number of times without advancing it. */
public final class ThirdPersonMotionTimeline {
    public record Input(int tick,double x,double y,double z,float yaw,float vertical,
                        boolean grounded,boolean sprinting,boolean crouching,boolean enabled,boolean acting) { }
    public record Snapshot(double phase,float movement,float running,float crouching,float airborne,
                           float takeoff,float landing,float falling,boolean enabled,double distance,
                           float speed,float turn,float poseWeight,float acceleration) { }
    private Input last;
    private Snapshot current=idle(false),previous=current;
    private static Snapshot idle(boolean enabled) {return new Snapshot(0,0,0,0,0,0,0,0,enabled,0,0,0,enabled?1:0,0);}
    private static float approach(float a,float b,double rate,double dt) {return a+(b-a)*(float)(1-Math.exp(-rate*dt));}
    private static float mix(float a,float b,float t){return a+(b-a)*t;}
    private static double mix(double a,double b,float t){return a+(b-a)*t;}
    private static float wrap(float a){a%=360;if(a>=180)a-=360;if(a< -180)a+=360;return a;}
    public void tick(Input input) {
        if(last!=null&&input.tick==last.tick)return;
        if(!Double.isFinite(input.x)||!Double.isFinite(input.y)||!Double.isFinite(input.z)
                ||!Float.isFinite(input.yaw)||!Float.isFinite(input.vertical))throw new IllegalArgumentException("Finite motion required");
        int elapsed=last==null?0:input.tick-last.tick;
        double horizontal=last==null?0:Math.hypot(input.x-last.x,input.z-last.z);
        if(last==null||elapsed<1||elapsed>5||horizontal>1.6*elapsed||Math.abs(input.y-last.y)>2.5*elapsed) {
            current=new Snapshot(0,0,0,input.crouching?1:0,input.grounded?0:1,0,0,0,input.enabled,
                    0,0,0,input.enabled&&!input.acting?1:0,0);
            previous=current;last=input;return;
        }
        previous=current;
        float speed=(float)(horizontal/elapsed);
        boolean supported=input.enabled&&input.grounded&&last.grounded;
        double travel=supported&&speed>.002f?horizontal:0;
        float target=travel>0?Math.clamp(speed/.10f,0,1):0;
        float move=approach(current.movement,target,target>current.movement?.34:.40,elapsed);
        if(move<.0001f)move=0;
        float run=approach(current.running,input.sprinting&&target>0?1:0,.27,elapsed);
        float crouch=approach(current.crouching,input.crouching?1:0,.36,elapsed);
        float air=approach(current.airborne,input.grounded?0:1,input.grounded?.9:.72,elapsed);
        float takeoff=approach(current.takeoff,0,.34,elapsed);
        float landing=approach(current.landing,0,.25,elapsed);
        if(input.enabled&&last.grounded&&!input.grounded&&input.vertical>.07f)takeoff=1;
        if(input.enabled&&!last.grounded&&input.grounded)landing=Math.clamp(-last.vertical*2.2f,.45f,1);
        float falling=approach(current.falling,Math.clamp((.08f-input.vertical)/.4f,0,1),.32,elapsed);
        float yawDelta=wrap(input.yaw-last.yaw)/elapsed;
        float turn=approach(current.turn,input.enabled&&Math.abs(yawDelta)<90?Math.clamp(yawDelta/15,-1,1):0,.30,elapsed);
        float accel=approach(current.acceleration,Math.clamp((speed-current.speed)/(.045f*elapsed),-1,1),.32,elapsed);
        float weight=approach(current.poseWeight,input.enabled&&!input.acting?1:0,.48,elapsed);
        // A complete left/right cycle covers actual ground distance; envelopes never manufacture travel.
        double phase=current.phase+travel*Math.PI*2/(2.0+.5*run-.55*crouch);
        current=new Snapshot(phase,move,run,crouch,air,takeoff,landing,falling,input.enabled,
                current.distance+travel,speed,turn,weight,accel);
        last=input;
    }
    public Snapshot sample(float partial) {
        float t=Math.clamp(partial,0,1);Snapshot a=previous,b=current;
        return new Snapshot(mix(a.phase,b.phase,t),mix(a.movement,b.movement,t),mix(a.running,b.running,t),
                mix(a.crouching,b.crouching,t),mix(a.airborne,b.airborne,t),mix(a.takeoff,b.takeoff,t),
                mix(a.landing,b.landing,t),mix(a.falling,b.falling,t),b.enabled,mix(a.distance,b.distance,t),
                mix(a.speed,b.speed,t),mix(a.turn,b.turn,t),mix(a.poseWeight,b.poseWeight,t),
                mix(a.acceleration,b.acceleration,t));
    }
    public Snapshot current(){return current;}
}
