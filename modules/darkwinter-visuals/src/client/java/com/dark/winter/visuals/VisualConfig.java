// Loads rendering configuration and the active artistic profile on the render thread.
package com.dark.winter.visuals;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** One configuration file for rendering and the artistic profile. Render-thread only. */
public final class VisualConfig {
    // One shared configuration for sky and shadow rendering.
    private static final VisualConfig SHARED = new VisualConfig();
    public static VisualConfig shared() { return SHARED; }
    public static final Logger LOG = LoggerFactory.getLogger("DarkWinter/Visuals");
    public enum Quality {
        LOW(768, 32f, 8), BALANCED(1024, 40f, 12), HIGH(2048, 56f, 16);
        public final int resolution, steps;
        public final float radius;
        Quality(int resolution, float radius, int steps) {
            this.resolution = resolution; this.radius = radius; this.steps = steps;
        }
    }
    public boolean enabled = true, shadows = true, atmosphere = true, volume = true;
    public boolean overworldOnly = true, flipDepthY = false, flipShadowY = false, freezeSun = false;
    public Quality quality = Quality.BALANCED;
    public float shadowStrength = .50f, fogDensity = .0009f, maxFog = .055f;
    public float shaftStrength = .09f, rayDistance = 60f;
    public float depthBiasBlocks = .018f, normalOffsetBlocks = .012f;
    public float filmStrength = 1f, saturation = .72f, coldness = .34f, exposure = .90f;
    public float grain = .025f, chromaBleed = .34f, vignette = .16f, scanlines = .012f;
    // Reference pixels at 1080p; restrained temporal film, independent of game simulation.
    public float chromaticAberration = .60f, interlaceStrength = .12f;
    public float shutterStrength = .18f, stepPrintStrength = .12f, stepPrintFps = 18f;
    public int debug = 0;
    // Artistic disc size only; no change to light direction, intensity or shadow map.
    public float sunSizeMultiplier=2.6f, moonSizeMultiplier=3.2f;
    public float sunDiscBrightness=1.12f;
    // Fictional northern-Canadian setting, all geographic angles in degrees.
    public boolean geographicSun = true;
    public float latitude = 73.0f, longitude = -80.0f, utcOffsetHours = -5.0f;
    public float northRotation = 0.0f;
    public int calendarStartDay = 355;
    // 0 = permanently fixed seasonal date; 1 = one calendar day / Minecraft day.
    public float calendarDaysPerMinecraftDay = 0.10f;
    // Visual-only multiplier for the solar/lunar clock. 4 = one visible day spans 4 Minecraft days.
    public float visualDayLengthMultiplier = 4.0f;
    // Winter air is separate from clouds and the old bounded near-haze.
    public boolean winterHaze = true;
    public float hazeDensity = .0050f, hazeBaseHeight = 72f, hazeHeightFalloff = 42f;
    public float hazeStartDistance = 12f, hazeDetailStrength = .42f;
    public float hazeAirDensity = .00028f, hazeWindSpeed = .28f;
    public float hazeEdgeStart = .55f, hazeEdgeEnd = .88f;
    public long revision;
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("darkwinter/visuals.properties");
    private long lastCheck, lastModified = Long.MIN_VALUE;

    public void poll() {
        long now = System.nanoTime();
        if (lastCheck != 0 && now - lastCheck < 1_000_000_000L) return;
        lastCheck = now;
        try {
            if (!Files.exists(path)) return; // Defaults are valid even before installation of the template.
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (modified == lastModified) return;
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) { p.load(reader); }
            // Validate the entire candidate before changing live settings.
            Quality q = Quality.valueOf(p.getProperty("quality", "BALANCED").trim().toUpperCase(Locale.ROOT));
            boolean en = bool(p,"enabled",true), sh = bool(p,"shadows",true);
            boolean at = bool(p,"atmosphere",true), vo = bool(p,"volume",true);
            boolean ow = bool(p,"overworldOnly",true), fd = bool(p,"flipDepthY",false);
            boolean fs = bool(p,"flipShadowY",false), freeze = bool(p,"freezeSun",false);
            boolean geo = bool(p,"geographicSun",true);
            float nextLat=num(p,"latitude",73f,-89.9f,89.9f);
            float nextLon=num(p,"longitude",-80f,-180f,180f);
            float nextOffset=num(p,"utcOffsetHours",-5f,-12f,14f);
            float nextNorth=num(p,"northRotation",0f,-360f,360f);
            int nextDay=(int)num(p,"calendarStartDay",355f,1,365);
            float nextSpeed=num(p,"calendarDaysPerMinecraftDay",.10f,0f,20f);
            float nextDayLength=num(p,"visualDayLengthMultiplier",4.0f,1f,32f);
            float[] n = {
                num(p,"shadowStrength",.50f,0,.7f), num(p,"fogDensity",.0009f,0,.02f),
                num(p,"maxFog",.055f,0,.20f), num(p,"shaftStrength",.09f,0,.20f),
                num(p,"rayDistance",60,16,112), num(p,"depthBiasBlocks",.018f,.002f,.10f),
                num(p,"normalOffsetBlocks",.012f,0,.06f), num(p,"filmStrength",1,0,1),
                num(p,"saturation",.72f,0,1.2f), num(p,"coldness",.34f,0,.5f),
                num(p,"exposure",.90f,.6f,1.2f), num(p,"grain",.025f,0,.06f),
                num(p,"chromaBleed",.34f,0,.7f), num(p,"vignette",.16f,0,.3f),
                num(p,"scanlines",.012f,0,.03f), num(p,"debug",0,0,6)
            };
            float[] film = {
                num(p,"chromaticAberration",.60f,0,2f),
                num(p,"interlaceStrength",.12f,0,.35f),
                num(p,"shutterStrength",.18f,0,.4f),
                num(p,"stepPrintStrength",.12f,0,.4f),
                num(p,"stepPrintFps",18f,12f,30f)
            };
            float nextSunSize=num(p,"sunSizeMultiplier",2.6f,.5f,4f);
            float nextMoonSize=num(p,"moonSizeMultiplier",3.2f,.5f,4f);
            float nextSunBrightness=num(p,"sunDiscBrightness",1.12f,.8f,1.4f);
            boolean nextWinterHaze = bool(p,"winterHaze",true);
            float[] h = {
                num(p,"hazeDensity",.0050f,0,.03f),
                num(p,"hazeBaseHeight",72f,-64,1024),
                num(p,"hazeHeightFalloff",42f,8,256),
                num(p,"hazeStartDistance",12f,0,64),
                num(p,"hazeDetailStrength",.42f,0,.8f),
                num(p,"hazeAirDensity",.00028f,0,.003f),
                num(p,"hazeWindSpeed",.28f,0,2),
                num(p,"hazeEdgeStart",.55f,.2f,.9f),
                num(p,"hazeEdgeEnd",.88f,.4f,.95f)
            };
            if (h[8] <= h[7]+.05f)
                throw new IllegalArgumentException("hazeEdgeEnd must exceed hazeEdgeStart by more than 0.05");
            sunSizeMultiplier=nextSunSize; moonSizeMultiplier=nextMoonSize;
            sunDiscBrightness=nextSunBrightness;
            winterHaze=nextWinterHaze;
            hazeDensity=h[0]; hazeBaseHeight=h[1]; hazeHeightFalloff=h[2];
            hazeStartDistance=h[3]; hazeDetailStrength=h[4]; hazeAirDensity=h[5];
            hazeWindSpeed=h[6]; hazeEdgeStart=h[7]; hazeEdgeEnd=h[8];
            quality=q; enabled=en; shadows=sh; atmosphere=at; volume=vo;
            geographicSun=geo; latitude=nextLat; longitude=nextLon;
            utcOffsetHours=nextOffset; northRotation=nextNorth;
            calendarStartDay=nextDay; calendarDaysPerMinecraftDay=nextSpeed; visualDayLengthMultiplier=nextDayLength;
            overworldOnly=ow; flipDepthY=fd; flipShadowY=fs; freezeSun=freeze;
            shadowStrength=n[0]; fogDensity=n[1]; maxFog=n[2]; shaftStrength=n[3]; rayDistance=n[4];
            depthBiasBlocks=n[5]; normalOffsetBlocks=n[6]; filmStrength=n[7]; saturation=n[8];
            coldness=n[9]; exposure=n[10]; grain=n[11]; chromaBleed=n[12]; vignette=n[13]; scanlines=n[14];
            chromaticAberration=film[0]; interlaceStrength=film[1]; shutterStrength=film[2];
            stepPrintStrength=film[3]; stepPrintFps=film[4];
            debug=(int)n[15]; lastModified=modified; revision++;
            LOG.info("RC2: quality={}, shadows={}, volume={}, film={}, debug={}, config={}",
                    quality,shadows,volume,atmosphere,debug,path);
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Invalid visuals configuration. Last valid settings retained: {}", e.toString());
            try { lastModified=Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { }
        }
    }
    private static boolean bool(Properties p,String key,boolean fallback) {
        String v=p.getProperty(key,Boolean.toString(fallback)).trim();
        if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false"))
            throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(v);
    }
    private static float num(Properties p,String key,float fallback,float min,float max) {
        float v=Float.parseFloat(p.getProperty(key,Float.toString(fallback)).trim());
        if (!Float.isFinite(v) || v<min || v>max) throw new IllegalArgumentException(key+" outside "+min+".."+max);
        return v;
    }
}
