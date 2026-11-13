package com.dark.winter.client.voice;

import java.util.Arrays;
import java.util.random.RandomGenerator;

/** Pure scheduling policy. Time is active gameplay seconds, never time spent in menus. */
public final class DialogueDirector {
    public record Frame(float heat,float hunger,float thirst,float stamina,float fatigue,boolean exhausted,
                        boolean climate,float feels,float exposure,float wind,boolean heatNearby,
                        boolean outdoors,boolean calm,boolean humanTrace,boolean smallKeepsake,boolean vista) {}
    private final RandomGenerator random;
    private final long[] last=new long[VoiceLine.values().length],since=new long[VoiceLine.values().length];
    private final boolean[] episode=new boolean[6];
    private long nextSpeech=35,nextReflection,coldUntil,lastSpeech=-1000;
    private float coldestFeels=Float.POSITIVE_INFINITY,lowestHeat=100;
    private VoiceLine previous;

    public DialogueDirector(RandomGenerator random) {
        this.random=random;Arrays.fill(last,Long.MIN_VALUE/2);Arrays.fill(since,-1);
        nextReflection=8*60+random.nextInt(4*60+1);
    }
    public VoiceLine choose(Frame f,long now) {
        boolean cold=f.heat<=28||(f.climate&&f.feels<=-18);
        if(cold){coldUntil=now+5*60;if(f.climate)coldestFeels=Math.min(coldestFeels,f.feels);lowestHeat=Math.min(lowestHeat,f.heat);}
        boolean relief=now<coldUntil&&(f.heat>=lowestHeat+12||f.heatNearby
                ||f.climate&&f.exposure<=.30f&&f.feels>=coldestFeels+6);
        if(f.heat>48&&(!f.climate||f.feels>-12))episode[VoiceLine.COLD.ordinal()]=false;
        if(!f.climate||f.wind<5||f.exposure<.4f)episode[VoiceLine.WIND.ordinal()]=false;
        if(f.hunger<55)episode[VoiceLine.HUNGRY.ordinal()]=false;
        if(f.thirst>45)episode[VoiceLine.THIRSTY.ordinal()]=false;
        if(!f.exhausted&&f.stamina>.65f&&f.fatigue<70)episode[VoiceLine.EXHAUSTED.ordinal()]=false;
        if(cold&&!relief)episode[VoiceLine.WARMTH.ordinal()]=false;
        boolean[] condition={cold&&!relief,f.climate&&f.wind>=7.5f&&f.exposure>=.65f&&f.feels<=-5,
                f.hunger>=70,f.thirst<=25,f.exhausted||f.stamina<=.12f||f.fatigue>=85,relief,
                f.calm&&f.outdoors,f.calm&&f.humanTrace,f.calm&&f.smallKeepsake,f.calm&&f.outdoors&&f.vista};
        for(int i=0;i<condition.length;i++) {
            if(!condition[i])since[i]=-1;
            else if(since[i]<0)since[i]=now;
        }
        if(now<nextSpeech)return relief&&now-lastSpeech>=25&&ready(VoiceLine.WARMTH,now,6)
                &&!episode[VoiceLine.WARMTH.ordinal()]?VoiceLine.WARMTH:null;
        // Live conditions are evaluated afresh. There is no queue of outdated complaints.
        VoiceLine[] priority={VoiceLine.THIRSTY,VoiceLine.EXHAUSTED,VoiceLine.COLD,VoiceLine.HUNGRY,VoiceLine.WARMTH,VoiceLine.WIND};
        for(VoiceLine line:priority) {
            int stable=switch(line){case EXHAUSTED->2;case WARMTH->6;case WIND->10;default->15;};
            if(ready(line,now,stable)&&!episode[line.ordinal()])return line;
        }
        if(now<nextReflection||!f.calm)return null;
        VoiceLine[] thoughts={VoiceLine.COMING_BACK,VoiceLine.VIEW,VoiceLine.LITTLE_THINGS,VoiceLine.TRAFFIC};
        int start=random.nextInt(thoughts.length);
        for(int i=0;i<thoughts.length;i++) {
            VoiceLine line=thoughts[(start+i)%thoughts.length];
            int stable=line==VoiceLine.TRAFFIC?90:line==VoiceLine.VIEW?8:5;
            if(line!=previous&&ready(line,now,stable))return line;
        }
        return null;
    }
    private boolean ready(VoiceLine line,long now,int stable) {
        return since[line.ordinal()]>=0&&now-since[line.ordinal()]>=stable
                &&now-last[line.ordinal()]>=line.cooldownSeconds;
    }
    public void spoken(VoiceLine line,long now) {
        previous=line;last[line.ordinal()]=now;lastSpeech=now;nextSpeech=now+110+random.nextInt(51);
        if(line.reflection())nextReflection=now+10*60+random.nextInt(6*60+1);
        else episode[line.ordinal()]=true;
        if(line==VoiceLine.WARMTH){coldUntil=0;coldestFeels=Float.POSITIVE_INFINITY;lowestHeat=100;}
    }
    public void interrupt(long now){nextSpeech=Math.max(nextSpeech,now+15);}
    public VoiceLine previous(){return previous;}
}
