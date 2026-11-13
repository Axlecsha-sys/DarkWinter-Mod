package com.dark.winter.client.voice;

/** Tick-owned exertion and recovery; has no authority over stamina or movement. */
public final class BreathingEnvelope {
    private float effort,gain;
    private int runningTicks;
    private boolean worked;

    public float tick(boolean running,float stamina,float fatigue,boolean jumped) {
        stamina=unit(stamina,1);fatigue=unit(fatigue,0);
        runningTicks=running?runningTicks+1:0;
        if(jumped)effort=Math.min(1,effort+.14f);
        if(running)effort=Math.min(1,effort+.0045f);
        else effort=Math.max(0,effort-.0028f);
        if(runningTicks>=10||effort>=.30f)worked=true;
        // Low stamina prolongs an existing recovery; it does not start panting on login.
        if(worked)effort=Math.max(effort,(1-stamina)*.7f);
        float target=0;
        if(worked&&(running||effort>.035f))
            target=running? .25f+.40f*effort+.08f*(1-stamina)+.05f*fatigue : .70f*effort;
        if(!running&&effort<=.035f)worked=false;
        target=Math.min(.78f,target);
        gain=approach(gain,target,target>gain?.018f:.010f);
        return gain;
    }
    public float gain(){return gain;}
    public void reset(){effort=gain=0;runningTicks=0;worked=false;}
    private static float unit(float value,float fallback){return Float.isFinite(value)?Math.clamp(value,0,1):fallback;}
    private static float approach(float value,float target,float step){return value<target?Math.min(target,value+step):Math.max(target,value-step);}
}
