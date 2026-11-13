package com.dark.winter.client.voice;

import java.util.Arrays;

/** IDs and durations of the approved recordings, before and after nondestructive processing. */
public enum VoiceLine {
    COLD("cold",2137,12*60), WIND("wind",2057,14*60),
    HUNGRY("hungry",2137,12*60), THIRSTY("thirsty",1097,12*60),
    EXHAUSTED("exhausted",2137,8*60), WARMTH("warmth",4217,8*60),
    TRAFFIC("traffic",2697,25*60), COMING_BACK("coming_back",1897,25*60),
    LITTLE_THINGS("little_things",2457,25*60), VIEW("view",2057,25*60);

    public final String id;
    public final int durationMillis,cooldownSeconds;
    VoiceLine(String id,int durationMillis,int cooldownSeconds) {
        this.id=id;this.durationMillis=durationMillis;this.cooldownSeconds=cooldownSeconds;
    }
    public String subtitleKey(){return "protagonist."+id;}
    public String soundPath(){return "protagonist."+id;}
    public boolean reflection(){return ordinal()>=TRAFFIC.ordinal();}
    public static VoiceLine find(String id){return Arrays.stream(values()).filter(line->line.id.equals(id)).findFirst().orElse(null);}
}
