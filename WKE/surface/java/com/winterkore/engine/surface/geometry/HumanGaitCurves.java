package com.winterkore.engine.surface.geometry;

/** Authored contact, passing, toe-off and recovery poses. Angles are radians, phase is distance-owned. */
public final class HumanGaitCurves {
    // Thigh / knee / world-space foot pitch. A loop has eight independently authored poses.
    private static final float[][] WALK={
        {-.43f,.12f,-.16f},{-.25f,.16f,-.01f},{-.02f,.10f,0},{.23f,.12f,.03f},
        {.37f,.32f,.23f},{.12f,.85f,.20f},{-.25f,.65f,-.04f},{-.48f,.28f,-.14f}};
    private static final float[][] RUN={
        {-.72f,.32f,-.02f},{-.25f,.40f,.06f},{.35f,.55f,.30f},{.68f,1.25f,.42f},
        {.25f,1.70f,.22f},{-.55f,1.35f,-.10f},{-.95f,.85f,-.16f},{-.90f,.45f,-.08f}};
    // A snow step holds weight, pulls the buried boot up, carries a high knee, then presses down.
    // It is a complete authored loop, not an additive bounce over the ordinary walking cycle.
    private static final float[][] SNOW={
        {-.32f,.18f,-.12f},{-.18f,.14f,0},{.04f,.12f,0},{.22f,.30f,.14f},
        {-.12f,1.05f,.28f},{-.98f,2.05f,.22f},{-.98f,1.50f,-.12f},{-.56f,.56f,-.18f}};
    public record Leg(float thigh,float knee,float foot) { }
    private HumanGaitCurves() { }
    public static float mix(float a,float b,float weight){return a+(b-a)*weight;}
    private static float track(float[][] keys,double phase,int channel) {
        double p=phase/(Math.PI*2);p=(p-Math.floor(p))*keys.length;
        int i=(int)p,n=keys.length;float t=(float)(p-i),t2=t*t,t3=t2*t;
        float a=keys[(i+n-1)%n][channel],b=keys[i][channel],c=keys[(i+1)%n][channel],d=keys[(i+2)%n][channel];
        return .5f*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3);
    }
    public static Leg leg(double phase,float movement,float running,float crouch,float air,
                          float takeoff,float landing,float falling,boolean left) {
        return leg(phase,movement,running,crouch,air,takeoff,landing,falling,0,left);
    }
    public static Leg leg(double phase,float movement,float running,float crouch,float air,
                          float takeoff,float landing,float falling,float snow,boolean left) {
        double cycle=phase+(left?Math.PI:0);
        float move=movement*(1-crouch*.65f);
        float heavy=snowWeight(snow)*(1-air)*(1-crouch);
        float thighTrack=mix(track(WALK,cycle,0),track(RUN,cycle,0),running);
        float kneeTrack=mix(track(WALK,cycle,1),track(RUN,cycle,1),running);
        float pitchTrack=mix(track(WALK,cycle,2),track(RUN,cycle,2),running);
        if(heavy>0){
            thighTrack=mix(thighTrack,track(SNOW,cycle,0),heavy);
            kneeTrack=mix(kneeTrack,track(SNOW,cycle,1),heavy);
            pitchTrack=mix(pitchTrack,track(SNOW,cycle,2),heavy);
        }
        float thigh=thighTrack*move,knee=.055f+(kneeTrack-.055f)*move;
        float pitch=pitchTrack*move*(1-crouch);
        thigh-=.70f*crouch+.28f*landing;
        knee+=1.36f*crouch+.58f*landing;
        // Retain the departure leg through flight; a moving jump is deliberately asymmetric.
        float lead=Math.cos(cycle)>=0?1:-1,forward=movement*(.50f+.50f*running);
        float flightThigh=-.22f-.24f*takeoff+.16f*falling-forward*lead*.23f;
        float flightKnee=.62f+.52f*takeoff-.30f*falling+forward*lead*.20f;
        thigh=mix(thigh,flightThigh,air);knee=mix(knee,flightKnee,air);
        pitch=mix(pitch,-.10f+.12f*falling,air);
        // During recovery the toe follows the shin; cancelling a deeply bent knee
        // completely would fold the ankle backwards to keep the sole horizontal.
        return new Leg(thigh,Math.max(.025f,knee),Math.clamp(pitch-thigh-knee,-.75f,.60f));
    }
    /** Same depth envelope is used by stride, limbs, body balance and movement policy. */
    public static float snowWeight(float snow){return com.winterkore.engine.surface.SnowTravelPolicy.wadingPoseWeight(snow);}
    /** Lateral pelvis transfer over the loaded foot, shared with physical foot positions. Model units. */
    public static float pelvisShift(double phase,float movement,float crouch,float air,float snow){
        if(snow<=.015f||movement<=0||crouch>=1||air>=1)return 0;
        return -(float)Math.cos(phase)*.48f*snowWeight(snow)*movement*(1-crouch)*(1-air);
    }
    public static float arm(double phase,float move,float run,float crouch,float air,float takeoff,float falling,boolean left) {
        return arm(phase,move,run,crouch,air,takeoff,falling,0,left);
    }
    public static float arm(double phase,float move,float run,float crouch,float air,float takeoff,float falling,float snow,boolean left) {
        double cycle=phase+(left?Math.PI:0);
        float wave=(float)Math.cos(cycle),heavy=snowWeight(snow)*(1-crouch)*(1-air);
        float ground=-mix(track(WALK,cycle,0)*.70f,track(RUN,cycle,0)*.85f,run)*move*(1-crouch*.6f)-.10f*run*move+.13f*crouch;
        if(heavy>0)ground=mix(ground,(-track(SNOW,cycle,0)*.32f-.12f)*move,heavy);
        float flight=-.30f-.46f*takeoff+.14f*falling+wave*move*.24f;
        return mix(ground,flight,air);
    }
    public static float elbow(double phase,float move,float run,float crouch,float air,float takeoff,boolean left) {
        return elbow(phase,move,run,crouch,air,takeoff,0,left);
    }
    public static float elbow(double phase,float move,float run,float crouch,float air,float takeoff,float snow,boolean left) {
        float wave=(float)Math.sin(phase+(left?Math.PI:0));
        float ground=-.12f-(.86f+.10f*wave)*run*move-.13f*crouch;
        ground=mix(ground,-.32f-.16f*wave*move,snowWeight(snow)*move*(1-crouch));
        return mix(ground,-.36f-.35f*takeoff,air);
    }
}
