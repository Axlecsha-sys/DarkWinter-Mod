// Client ABI retained; render and authoritative footprints share the same pure tick-owned gait.
package com.dark.winter.client.equipment;

import com.winterkore.engine.surface.geometry.HumanGaitTimeline;

public final class ThirdPersonMotionTimeline {
    public record Input(int tick,double x,double y,double z,float yaw,float vertical,
                        boolean grounded,boolean sprinting,boolean crouching,boolean enabled,boolean acting,float snow) {
        public Input(int tick,double x,double y,double z,float yaw,float vertical,boolean grounded,boolean sprinting,
                boolean crouching,boolean enabled,boolean acting){this(tick,x,y,z,yaw,vertical,grounded,sprinting,crouching,enabled,acting,0);}
    }
    public record Snapshot(double phase,float movement,float running,float crouching,float airborne,
                           float takeoff,float landing,float falling,boolean enabled,double distance,
                           float speed,float turn,float poseWeight,float acceleration,float snow) {
        public Snapshot(double phase,float movement,float running,float crouching,float airborne,float takeoff,float landing,
                float falling,boolean enabled,double distance,float speed,float turn,float poseWeight,float acceleration){
            this(phase,movement,running,crouching,airborne,takeoff,landing,falling,enabled,distance,speed,turn,poseWeight,acceleration,0);
        }
    }
    private final HumanGaitTimeline timeline=new HumanGaitTimeline();
    public void tick(Input i){timeline.tick(new HumanGaitTimeline.Input(i.tick,i.x,i.y,i.z,i.yaw,i.vertical,
            i.grounded,i.sprinting,i.crouching,i.enabled,i.acting,i.snow));}
    public Snapshot sample(float partial){return wrap(timeline.sample(partial));}
    public Snapshot current(){return wrap(timeline.current());}
    /** Shared immutable foot pose for immediate local snow pressure. */
    public HumanGaitTimeline.Snapshot gait(float partial){return timeline.sample(partial);}
    private static Snapshot wrap(HumanGaitTimeline.Snapshot p){
        return new Snapshot(p.phase(),p.movement(),p.running(),p.crouching(),p.airborne(),p.takeoff(),p.landing(),
                p.falling(),p.enabled(),p.distance(),p.speed(),p.turn(),p.poseWeight(),p.acceleration(),p.snow());
    }
}
