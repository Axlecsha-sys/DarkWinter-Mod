// Reuses only a completed native shadow texture for a bounded age and projection drift.
package com.dark.winter.visuals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Render-owner policy. Epoch publishers may run on native compiler workers; no GPU handles are retained. */
public final class ShadowCaptureCache {
    public static final long MAX_AGE_NANOS = 33_000_000L;
    public static final double MAX_TEXEL_DRIFT = .125;
    private static final boolean REQUESTED = Boolean.parseBoolean(
            System.getProperty("darkwinter.visuals.shadowCaptureReuse", "true"));
    private static final AtomicLong TERRAIN_EPOCH = new AtomicLong();
    private static final AtomicLong ANIMATION_EPOCH = new AtomicLong();

    private enum Reason {
        DISABLED, BACKEND, EMPTY, WORLD, RESOURCE, CONFIG, TARGET, QUALITY, SAMPLING,
        TERRAIN, ANIMATION, CAMERA, AGE, DRIFT, INVALID
    }
    private static final String[] MISS_NAMES = {"missDisabled","missBackend","missEmpty","missWorld",
            "missResource","missConfig","missTarget","missQuality","missSampling","missTerrain",
            "missAnimation","missCamera","missAge","missDrift","missInvalid"};
    private final boolean enabled;
    private final long[] misses = new long[Reason.values().length];
    private final Matrix4f captured = new Matrix4f(), inverse = new Matrix4f(), delta = new Matrix4f();
    private Object world, target, atlas, filtering;
    private int resource, resolution, quality, mode;
    private long config, terrain, animation, capturedAt;
    private float radius, clipRange;
    private double cameraX, cameraY, cameraZ;
    private boolean valid;
    private long attempts, captures, reuses, invalidations, captureRace, reuseRace, clearOff, clearError, clearRelease, clearConfig;

    public ShadowCaptureCache() { this(REQUESTED); }
    ShadowCaptureCache(boolean enabled) { this.enabled = enabled; }
    public static boolean requested() { return REQUESTED; }
    public static long terrainEpoch() { return TERRAIN_EPOCH.get(); }
    public static long animationEpoch() { return ANIMATION_EPOCH.get(); }

    /** A native mesh publication/reset is relevant, including an empty compiled section. */
    public static void terrainChanged() { if (REQUESTED) TERRAIN_EPOCH.incrementAndGet(); }
    /** TextureManager ticks the block atlas independently of whether its day clock advances. */
    public static void texturesTicked() { if (REQUESTED) ANIMATION_EPOCH.incrementAndGet(); }

    boolean reuse(boolean vulkan, Object world, int resource, long config, Object target, Object atlas,
            Object filtering, int mode, int resolution, int quality, float radius, float clipRange,
            double cameraX, double cameraY, double cameraZ, Matrix4fc requested,
            long terrain, long animation, long now) {
        attempts++;
        if (!enabled) return miss(Reason.DISABLED);
        if (!vulkan) return miss(Reason.BACKEND);
        if (!valid) return miss(Reason.EMPTY);
        if (this.world != world) return miss(Reason.WORLD);
        if (this.resource != resource) return miss(Reason.RESOURCE);
        if (this.config != config) return miss(Reason.CONFIG);
        if (this.target != target) return miss(Reason.TARGET);
        if (this.resolution != resolution || this.quality != quality
                || Float.floatToIntBits(this.radius) != Float.floatToIntBits(radius)
                || Float.floatToIntBits(this.clipRange) != Float.floatToIntBits(clipRange)) return miss(Reason.QUALITY);
        if (this.atlas != atlas || this.filtering != filtering || this.mode != mode) return miss(Reason.SAMPLING);
        if (this.terrain != terrain) return miss(Reason.TERRAIN);
        if (this.animation != animation) return miss(Reason.ANIMATION);
        // The first version captures on every position change; it never approximates camera translation.
        if (!same(this.cameraX,cameraX) || !same(this.cameraY,cameraY) || !same(this.cameraZ,cameraZ)) return miss(Reason.CAMERA);
        long age = now - capturedAt;
        if (age < 0 || age > MAX_AGE_NANOS) return miss(Reason.AGE);
        if (!inputs(resolution,radius,clipRange,cameraX,cameraY,cameraZ,requested)) return miss(Reason.INVALID);
        if (!withinDrift(requested,inverse,delta,resolution,radius,clipRange)) return miss(Reason.DRIFT);
        reuses++;
        return true;
    }

    /** Counts a real pass even in the control. Only a consistent pass can become reusable. */
    void captured(boolean vulkan, Object world, int resource, long config, Object target, Object atlas,
            Object filtering, int mode, int resolution, int quality, float radius, float clipRange,
            double cameraX, double cameraY, double cameraZ, Matrix4fc matrix,
            long beforeTerrain, long beforeAnimation, long afterTerrain, long afterAnimation, long now) {
        captures++;
        if (!enabled || !vulkan) { forget(); return; }
        if (beforeTerrain != afterTerrain || beforeAnimation != afterAnimation) {
            captureRace++; invalidate(); return;
        }
        if (!inputs(resolution,radius,clipRange,cameraX,cameraY,cameraZ,matrix)) { misses[Reason.INVALID.ordinal()]++; invalidate(); return; }
        captured.set(matrix);
        inverse.set(matrix).invert();
        if (!affine(inverse)) { misses[Reason.INVALID.ordinal()]++; invalidate(); return; }
        this.world=world;this.resource=resource;this.config=config;this.target=target;this.atlas=atlas;
        this.filtering=filtering;this.mode=mode;this.resolution=resolution;this.quality=quality;
        this.radius=radius;this.clipRange=clipRange;
        this.cameraX=cameraX;this.cameraY=cameraY;this.cameraZ=cameraZ;
        terrain=afterTerrain;animation=afterAnimation;capturedAt=now;valid=true;
    }

    void copyMatrix(Matrix4f destination) {
        if (!valid) throw new IllegalStateException("No completed shadow texture to reuse");
        destination.set(captured);
    }
    void rejectRacedReuse() { reuses--; reuseRace++; invalidate(); }
    void clearConfig() { if (valid) { clearConfig++; invalidate(); } }
    void clearOff() { if (valid) { clearOff++; invalidate(); } }
    void clearError() { if (valid) { clearError++; invalidate(); } }
    void clearRelease() { if (valid) { clearRelease++; invalidate(); } }
    private boolean miss(Reason reason) { misses[reason.ordinal()]++; invalidate(); return false; }
    private void invalidate() { if (valid) invalidations++; forget(); }
    private void forget() { valid=false;world=target=atlas=filtering=null; }
    private static boolean same(double a,double b) { return Double.doubleToLongBits(a)==Double.doubleToLongBits(b); }

    /**
     * D maps the captured orthographic clip box to the requested clip box. Since D is affine,
     * each row's maximum error occurs at a corner of [-1,1] x [-1,1] x [0,1].
     */
    static boolean withinDrift(Matrix4fc requested, Matrix4fc capturedInverse, Matrix4f scratch,
            int resolution,float radius,float clipRange) {
        scratch.set(requested).mul(capturedInverse);
        if (!affine(scratch)) return false;
        double x=Math.abs(scratch.m00()-1d)+Math.abs(scratch.m10())
                +Math.max(Math.abs(scratch.m30()),Math.abs((double)scratch.m20()+scratch.m30()));
        double y=Math.abs(scratch.m01())+Math.abs(scratch.m11()-1d)
                +Math.max(Math.abs(scratch.m31()),Math.abs((double)scratch.m21()+scratch.m31()));
        double z=Math.abs(scratch.m02())+Math.abs(scratch.m12())
                +Math.max(Math.abs(scratch.m32()),Math.abs((double)scratch.m22()-1+scratch.m32()));
        double xyLimit=2*MAX_TEXEL_DRIFT/resolution;
        double depthLimit=MAX_TEXEL_DRIFT*(2d*radius/resolution)/clipRange;
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)
                &&x<=xyLimit&&y<=xyLimit&&z<=depthLimit;
    }
    private static boolean inputs(int resolution,float radius,float clipRange,double x,double y,double z,Matrix4fc matrix) {
        return resolution>0&&Float.isFinite(radius)&&radius>0&&Float.isFinite(clipRange)&&clipRange>0
                &&Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&affine(matrix);
    }
    private static boolean affine(Matrix4fc m) {
        return m.isFinite()&&m.m03()==0&&m.m13()==0&&m.m23()==0&&m.m33()==1;
    }

    /** Allocates only when diagnostics explicitly ask, outside a measured frame. */
    public Map<String,Object> statistics() {
        Map<String,Long> counts=new LinkedHashMap<>();
        counts.put("attempts",attempts);counts.put("captures",captures);counts.put("reuses",reuses);
        counts.put("invalidations",invalidations);counts.put("captureRace",captureRace);counts.put("reuseRace",reuseRace);
        counts.put("clearOff",clearOff);counts.put("clearError",clearError);counts.put("clearRelease",clearRelease);counts.put("clearConfig",clearConfig);
        for(int i=0;i<MISS_NAMES.length;i++)counts.put(MISS_NAMES[i],misses[i]);
        return Map.of("enabled",enabled,"maxAgeNanos",MAX_AGE_NANOS,"maxTexelDrift",MAX_TEXEL_DRIFT,
                "terrainEpoch",terrainEpoch(),"animationEpoch",animationEpoch(),"counters",Map.copyOf(counts));
    }
}
