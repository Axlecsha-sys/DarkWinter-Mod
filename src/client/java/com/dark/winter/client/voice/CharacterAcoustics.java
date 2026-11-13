package com.dark.winter.client.voice;

/** Distance cues for an external body, beyond stereo pan; the first-person mix bypasses them. */
public final class CharacterAcoustics {
    public static final float REFERENCE_DISTANCE=1;
    public static final float ROLLOFF=.45f;
    private CharacterAcoustics() {}

    public static Colour at(double distance) {
        double d=Double.isFinite(distance)?Math.max(0,distance):0;
        float depth=(float)Math.clamp((d-.75)/(d+1.5),0,1);
        return new Colour(1-.22f*depth,1-.78f*depth);
    }
    public record Colour(float low,float high) {}
}
