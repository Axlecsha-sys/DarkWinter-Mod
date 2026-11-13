package com.winterkore.engine.surface.geometry;

/** Sampled removal proof after the host has applied its physical snow-bottom clamp. */
public final class SnowContactHandover {
    private SnowContactHandover() {}
    /**
     * Signed pressure is positive in a cavity and negative in a displaced bank.
     * Compare the canonical field with that same field plus the one retiring prediction.
     * Its original core still has an independent floor, including cuts thinner than tolerance.
     */
    public static boolean retainsSample(double canonical,double withPrediction,double original,
            boolean core,double toleranceMillimeters) {
        if(!Double.isFinite(canonical)||!Double.isFinite(withPrediction)||!Double.isFinite(original)
                ||!Double.isFinite(toleranceMillimeters)||toleranceMillimeters<0)return false;
        if(Math.abs(withPrediction-canonical)>toleranceMillimeters)return false;
        return !core||canonical>=Math.max(original-toleranceMillimeters,original*.5);
    }
}
