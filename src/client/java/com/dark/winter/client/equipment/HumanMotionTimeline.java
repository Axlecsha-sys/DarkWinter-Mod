// Samples framerate-independent visual motion envelopes without world references.
package com.dark.winter.client.equipment;

/** Framerate-independent visual envelopes. Contains no entity/world references or gameplay state. */
public final class HumanMotionTimeline {
    public record Input(double time,float speed,float vertical,boolean grounded,boolean sprinting,boolean crouching,boolean enabled) { }
    public record Snapshot(double phase,float movement,float running,float crouching,float airborne,float takeoff,float landing,float falling,boolean enabled) { }
    private double time=Double.NaN,phase;
    private float movement,running,crouching,airborne,takeoff,landing,falling,previousVertical;
    private boolean grounded=true,enabled=true;
    private Snapshot cached=new Snapshot(0,0,0,0,0,0,0,0,false);
    private static float approach(float current,float target,double rate,double dt) {return current+(target-current)*(float)(1-Math.exp(-rate*dt));}
    public Snapshot update(Input input) {
        if(!Double.isFinite(input.time)||!Float.isFinite(input.speed)||!Float.isFinite(input.vertical))throw new IllegalArgumentException("Finite motion sample required");
        if(Double.isNaN(time)||input.time<time-5||input.time-time>40) {
            time=input.time;phase=input.time*.28;movement=Math.clamp(input.speed/.1f,0,1);
            running=input.sprinting?1:0;crouching=input.crouching?1:0;airborne=input.grounded?0:1;
            takeoff=landing=0;falling=input.vertical<0?1:0;grounded=input.grounded;enabled=input.enabled;
        } else {
            if(input.time<=time)return cached;
            double dt=Math.min(3,input.time-time);time=input.time;
            if(input.enabled&&enabled) {
                if(grounded&&!input.grounded&&input.vertical>.07f)takeoff=1;
                if(!grounded&&input.grounded)landing=Math.clamp(-previousVertical*2.2f,.45f,1);
            }
            movement=approach(movement,input.enabled?Math.clamp(input.speed/.1f,0,1):0,.3,dt);
            running=approach(running,input.enabled&&input.sprinting?1:0,.24,dt);
            crouching=approach(crouching,input.enabled&&input.crouching?1:0,.34,dt);
            airborne=approach(airborne,input.enabled&&!input.grounded?1:0,.65,dt);
            falling=approach(falling,Math.clamp((.08f-input.vertical)/.4f,0,1),.3,dt);
            takeoff=approach(takeoff,0,.32,dt);landing=approach(landing,0,.2,dt);
            phase+=(.28+.14*running)*movement*(input.grounded?1:.18)*dt*(1-crouching*.48);
            grounded=input.grounded;enabled=input.enabled;
        }
        previousVertical=input.vertical;
        return cached=new Snapshot(phase,movement,running,crouching,airborne,takeoff,landing,falling,input.enabled);
    }
    public Snapshot current() {return cached;}
}
