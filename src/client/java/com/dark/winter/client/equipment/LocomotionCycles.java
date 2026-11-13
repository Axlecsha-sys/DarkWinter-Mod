package com.dark.winter.client.equipment;

import com.winterkore.engine.surface.geometry.HumanGaitCurves;

/** Stable client facade for the same authored curves used by authoritative sole contacts. */
public final class LocomotionCycles {
    public record Leg(float thigh,float knee,float foot) { }
    private LocomotionCycles(){}
    public static float mix(float a,float b,float weight){return HumanGaitCurves.mix(a,b,weight);}
    public static Leg leg(double phase,float movement,float running,float crouch,float air,
                          float takeoff,float landing,float falling,boolean left){
        return leg(phase,movement,running,crouch,air,takeoff,landing,falling,0,left);
    }
    public static Leg leg(double phase,float movement,float running,float crouch,float air,
                          float takeoff,float landing,float falling,float snow,boolean left){
        var p=HumanGaitCurves.leg(phase,movement,running,crouch,air,takeoff,landing,falling,snow,left);
        return new Leg(p.thigh(),p.knee(),p.foot());
    }
    public static float arm(double phase,float move,float run,float crouch,float air,float takeoff,float falling,boolean left){
        return HumanGaitCurves.arm(phase,move,run,crouch,air,takeoff,falling,left);
    }
    public static float arm(double phase,float move,float run,float crouch,float air,float takeoff,float falling,float snow,boolean left){
        return HumanGaitCurves.arm(phase,move,run,crouch,air,takeoff,falling,snow,left);
    }
    public static float elbow(double phase,float move,float run,float crouch,float air,float takeoff,boolean left){
        return HumanGaitCurves.elbow(phase,move,run,crouch,air,takeoff,left);
    }
    public static float elbow(double phase,float move,float run,float crouch,float air,float takeoff,float snow,boolean left){
        return HumanGaitCurves.elbow(phase,move,run,crouch,air,takeoff,snow,left);
    }
}
