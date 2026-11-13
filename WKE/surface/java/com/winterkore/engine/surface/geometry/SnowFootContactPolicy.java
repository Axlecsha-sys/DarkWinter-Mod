package com.winterkore.engine.surface.geometry;

/** Shared authoritative/predicted pressure from actual sole immersion in a frozen snow field. */
public final class SnowFootContactPolicy {
    public record Press(float depthMillimeters,float compaction) {}
    /** Immutable measured support, not an externally requested pressure depth. */
    public record Support(double physicalColumnDepthMm,double authoredBaseMm,double skinY) {}
    private SnowFootContactPolicy(){}
    /** Replay the captured contact against its original skin, bounded by the current live material. */
    public static Press sampleCaptured(Support captured,double livePhysicalColumnDepthMm,boolean compatibleOwner,
            double soleY,boolean scuff,float pressure){
        if(captured==null||!compatibleOwner||!Double.isFinite(livePhysicalColumnDepthMm)||livePhysicalColumnDepthMm<1)return null;
        var press=sample(captured.physicalColumnDepthMm(),captured.authoredBaseMm(),captured.skinY(),soleY,scuff,pressure);
        if(press==null)return null;
        return new Press((float)Math.min(press.depthMillimeters(),Math.min(750,livePhysicalColumnDepthMm*.9)),press.compaction());
    }
    /** Stable depth selected once from the measured column; animation only admits the contact. */
    public static Press sampleFixed(double physicalColumnDepthMm,double authoredBaseMm,double skinY,double soleY,boolean scuff,float pressure){
        if(!Double.isFinite(physicalColumnDepthMm+authoredBaseMm+skinY+soleY)||!Float.isFinite(pressure)
                ||physicalColumnDepthMm<1||pressure<=0||pressure>1||soleY>skinY+.02)return null;
        // A fixed channel is the full admitted sole segment, not a weak animation scuff.
        if(scuff&&physicalColumnDepthMm<200)return null;
        double band=Math.max(25,Math.floor(physicalColumnDepthMm/50)*50);
        double depth=Math.min(750,Math.min(physicalColumnDepthMm*.75,band*.55));
        if(depth<.5)return null;
        return new Press((float)depth,scuff?.10f*pressure:.65f*pressure);
    }
    /** Fixed-profile replay retains the measured frame and the existing current-material bound. */
    public static Press sampleCapturedFixed(Support captured,double livePhysicalColumnDepthMm,boolean compatibleOwner,
            double soleY,boolean scuff,float pressure){
        if(captured==null||!compatibleOwner||!Double.isFinite(livePhysicalColumnDepthMm)||livePhysicalColumnDepthMm<1)return null;
        var press=sampleFixed(captured.physicalColumnDepthMm(),captured.authoredBaseMm(),captured.skinY(),soleY,scuff,pressure);
        if(press==null)return null;
        return new Press((float)Math.min(press.depthMillimeters(),Math.min(750,livePhysicalColumnDepthMm*.9)),press.compaction());
    }
    /** Use accepted rendered pressure during short frame stalls; resume tick fallback after two seconds. */
    public static boolean renderedPressureActive(long acceptedTick,long serverTick){
        long age=serverTick-acceptedTick;
        return acceptedTick!=Long.MIN_VALUE&&acceptedTick<=serverTick&&age>=0&&age<40;
    }
    public static Press sample(double physicalColumnDepthMm,double authoredBaseMm,double skinY,double soleY,boolean scuff,float pressure){
        if(!Double.isFinite(physicalColumnDepthMm+authoredBaseMm+skinY+soleY)||!Float.isFinite(pressure)
                ||physicalColumnDepthMm<1||pressure<=0||pressure>1||soleY>skinY+.02)return null;
        double immersion=Math.max(0,(skinY-soleY)*1000);
        if(scuff&&immersion<4)return null;
        double resting=Math.min(36,physicalColumnDepthMm*.16),limit=Math.min(750,physicalColumnDepthMm*.9);
        double depth=Math.min(limit,Math.max(scuff?0:resting,immersion)*(scuff?.58:1)*pressure);
        if(depth<.5)return null;
        return new Press((float)depth,scuff?.10f*pressure:.65f*pressure);
    }
}
