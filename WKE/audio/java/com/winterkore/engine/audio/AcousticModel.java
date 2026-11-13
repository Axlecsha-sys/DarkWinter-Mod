// Host-independent, bounded geometric acoustics for WKE audio adapters.
package com.winterkore.engine.audio;

/** Acoustic estimates from the host's real collision geometry; no world ownership or audio API. */
public final class AcousticModel {
    public static final int MAX_QUERY_CELLS_PER_WINDOW=768;
    public static final int MAX_SOURCES_PER_WINDOW=8;
    public static final double MAX_SOURCE_DISTANCE=80;
    public static final double ROOM_PROBE_DISTANCE=18;
    public static final Material STONE=new Material(.22f,.12f,.88f);
    public static final Material WOOD=new Material(.48f,.28f,.65f);
    public static final Material GLASS=new Material(.66f,.42f,.90f);
    public static final Material SOFT=new Material(.36f,.10f,.20f);
    public static final Material SNOW=new Material(.52f,.16f,.16f);
    public static final Material FOLIAGE=new Material(.80f,.62f,.12f);
    public static final Room OUTDOORS=new Room(0,ROOM_PROBE_DISTANCE,0);
    public static final Parameters DRY=new Parameters(1,1,0,.25f,.002f,0,0,0);
    private AcousticModel() { }

    public record Material(float transmission,float highTransmission,float reflectivity) { }
    public record Path(float transmission,float highTransmission,int barriers,boolean complete) { }
    public record Room(float enclosure,double meanDistance,float reflectivity) { }
    public record Parameters(float directGain,float highFrequencyGain,float reverbGain,
            float decaySeconds,float reflectionDelay,float enclosure,int barriers,double distance) { }

    public static Parameters evaluate(Path path,Room room,double distance) {
        double range=Double.isFinite(distance)?Math.clamp(distance,0,MAX_SOURCE_DISTANCE):0;
        float enclosure=unit(room.enclosure());
        float reflection=unit(room.reflectivity());
        float direct=Math.clamp(path.transmission(),.08f,1f);
        float high=Math.clamp(path.highTransmission()*(float)Math.exp(-range*.0025),.04f,1f);
        // A room needs several reflecting boundaries. Outdoor ground alone has no room tail.
        float wet=Math.clamp(enclosure*reflection*.38f,0,.38f);
        float decay=Math.clamp(.22f+(float)Math.sqrt(Math.max(.1,room.meanDistance()))*enclosure
                *(.30f+reflection*.52f),.22f,3.4f);
        float delay=Math.clamp((float)(room.meanDistance()*2/343),.002f,.10f);
        return new Parameters(direct,high,wet,decay,delay,enclosure,path.barriers(),range);
    }

    public static Parameters smooth(Parameters previous,Parameters next,float fraction) {
        float t=unit(fraction);
        return new Parameters(lerp(previous.directGain(),next.directGain(),t),
                lerp(previous.highFrequencyGain(),next.highFrequencyGain(),t),
                lerp(previous.reverbGain(),next.reverbGain(),t),next.decaySeconds(),
                next.reflectionDelay(),next.enclosure(),next.barriers(),next.distance());
    }

    private static float unit(float value) { return Float.isFinite(value)?Math.clamp(value,0,1):0; }
    private static float lerp(float a,float b,float t) { return a+(b-a)*t; }
}
