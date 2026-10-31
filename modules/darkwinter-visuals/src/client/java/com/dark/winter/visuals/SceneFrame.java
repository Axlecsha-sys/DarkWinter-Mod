// Defines the std140 scene-uniform layout shared by Java and the visual shaders.
package com.dark.winter.visuals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Shared std140 contract: 26 vec4 = 416 bytes. Existing offsets are unchanged. */
public final class SceneFrame {
    public static final String BLOCK = "DarkWinterScene";
    public static final int BYTES = 416;
    public static SceneFrame active;
    public static volatile int resourceEpoch;
    public final Matrix4f inverseViewProjection = new Matrix4f();
    public final Matrix4f shadowFromRelative = new Matrix4f();
    public final Vector3f sun = new Vector3f(0,1,0);
    public VisualConfig config;
    public WeatherInput.State weather;
    public boolean shadowValid, inFluid, weatherSky, atmosphereAllowed, backgroundAvailable;
    public float intensity, timeSeconds, cameraHeight, cameraX, cameraZ, clipRange;

    public float hazeDriftX, hazeDriftZ, cloudDriftX, cloudDriftZ;
    public boolean filmHistoryValid;
    public float filmDeltaSeconds, filmHeldAgeSeconds, filmFieldPhase;

    public void write(ByteBuffer buffer) {
        if (buffer.capacity()<BYTES) throw new IllegalArgumentException("DarkWinterScene requires "+BYTES+" bytes");
        ByteBuffer b=buffer.duplicate().order(ByteOrder.nativeOrder());
        inverseViewProjection.get(0,b);
        shadowFromRelative.get(64,b);
        var q=config.quality;
        boolean visual=com.dark.winter.client.TestingAccess.enabled("visuals");
        boolean air=atmosphereAllowed && com.dark.winter.client.TestingAccess.enabled("atmosphere");
        var settings=com.dark.winter.client.TestingAccess.settings();
        boolean lab=settings!=null;
        vec(b,128,sun.x,sun.y,sun.z,intensity);
        vec(b,144,air?config.fogDensity:0,air?config.maxFog:0,visual?config.shaftStrength:0,config.rayDistance);
        vec(b,160,q.resolution,2*q.radius/q.resolution,config.depthBiasBlocks/clipRange,lab?settings.shadowStrength:config.shadowStrength);
        vec(b,176,weather.cloudCover(),weather.snowfall(),weather.windSpeed(),weather.fogMultiplier());
        vec(b,192,weather.windX(),weather.windZ(),timeSeconds,q.steps);
        int moduleFlags=(config.volume&&visual?1:0)|(weatherSky?2:0)|(backgroundAvailable?4:0);
        vec(b,208,shadowValid&&visual?1:0,moduleFlags,config.debug,inFluid?1:0);
        float terrainDistance=16f*Math.max(2,
                net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance());
        vec(b,224,cameraX,cameraHeight,cameraZ,terrainDistance);
        vec(b,240,config.flipDepthY?1:0,config.flipShadowY?1:0,clipRange,config.normalOffsetBlocks);
        vec(b,256,visual?(lab?settings.filmStrength:(config.atmosphere?config.filmStrength:0)):0,config.saturation,config.coldness,config.exposure);
        vec(b,272,config.grain,config.chromaBleed,config.vignette,config.scanlines);
        boolean haze=air && com.dark.winter.client.TestingAccess.resolve("atmosphere",config.winterHaze);
        vec(b,288,haze?(lab?settings.hazeDensity:config.hazeDensity):0,config.hazeBaseHeight,
                config.hazeHeightFalloff,config.hazeStartDistance);
        vec(b,304,config.hazeDetailStrength,hazeDriftX,hazeDriftZ,haze?config.hazeAirDensity:0);
        // Horizontal visibility only. Climbing does NOT erase the ground below the player.
        float available=Math.max(24f,terrainDistance-16f);
        vec(b,320,available*config.hazeEdgeStart,available*config.hazeEdgeEnd,
                q==VisualConfig.Quality.LOW?4f:6f,haze?1f:0f);
        vec(b,336,weather.cloudRoundness(),weather.cloudScale(),weather.cloudStretch(),weather.skyBlue());
        var moon=SolarRuntime.currentMoon(net.minecraft.client.Minecraft.getInstance().level);
        // Spare components carry the exact geographic Moon direction; zero selects vanilla fallback.
        vec(b,352,weather.fogBlue(),moon==null?0:(float)moon.x(),moon==null?0:(float)moon.y(),moon==null?0:(float)moon.z());
        // Angular bounds enclose the full textured quad, including crescents and enlarged discs.
        float sunRadius=(float)Math.atan(Math.sqrt(2)*SolarDaylight.sunHalfSizeAtDistance100()*config.sunSizeMultiplier/100);
        float moonRadius=(float)Math.atan(Math.sqrt(2)*LunarEphemeris.moonHalfSizeAtDistance100()*config.moonSizeMultiplier/100);
        vec(b,368,cloudDriftX,cloudDriftZ,sunRadius,moonRadius);
        vec(b,384,config.chromaticAberration,config.interlaceStrength,config.shutterStrength,config.stepPrintStrength);
        vec(b,400,filmHistoryValid?1:0,filmDeltaSeconds,filmHeldAgeSeconds,filmFieldPhase);
    }
    private static void vec(ByteBuffer b,int p,float x,float y,float z,float w) {
        b.putFloat(p,x); b.putFloat(p+4,y); b.putFloat(p+8,z); b.putFloat(p+12,w);
    }
}
