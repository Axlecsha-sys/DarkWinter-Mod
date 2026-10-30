// Persists presentation settings while preserving unknown configuration keys.
package com.dark.winter.client;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Presentation only. Unknown keys survive saves; changes never affect world or gameplay settings. */
public final class UiPreferences {
    public enum Motion { FULL, REDUCED, OFF }
    public enum PauseBackdrop { WORLD, SHELTER }
    private static Motion motion=Motion.FULL;
    private static PauseBackdrop pause=PauseBackdrop.WORLD;
    private static float strength=1f;
    private static boolean mist=true;
    private static final long START=System.nanoTime();
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("darkwinter/ui.properties");
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("DarkWinter/UI");
    private UiPreferences() {}
    public static void initialize() {
        if(!Files.exists(FILE)) return;
        try(var in=Files.newBufferedReader(FILE)) {
            var p=new Properties();p.load(in);
            Motion m=Motion.valueOf(p.getProperty("analogMotion","FULL").trim().toUpperCase(Locale.ROOT));
            PauseBackdrop b=PauseBackdrop.valueOf(p.getProperty("pauseBackdrop","WORLD").trim().toUpperCase(Locale.ROOT));
            float s=Float.parseFloat(p.getProperty("analogStrength","1.0"));
            if(!Float.isFinite(s)||s<0||s>1.5f) throw new IllegalArgumentException("analogStrength must be 0..1.5");
            String mv=p.getProperty("menuMist","true").trim();
            if(!mv.equalsIgnoreCase("true")&&!mv.equalsIgnoreCase("false")) throw new IllegalArgumentException("menuMist must be true/false");
            motion=m;pause=b;strength=s;mist=Boolean.parseBoolean(mv);
        } catch(IOException|IllegalArgumentException e){LOG.warn("Cannot read UI settings; keeping last valid state",e);}
    }
    public static Motion motion(){return motion;}
    public static PauseBackdrop pauseBackdrop(){return pause;}
    public static float strength(){return strength;}
    public static boolean mist(){return mist;}
    public static boolean animated(){return motion!=Motion.OFF;}
    public static boolean full(){return motion==Motion.FULL;}
    public static double seconds(){return animated()?(System.nanoTime()-START)/1_000_000_000.0:0;}
    public static void set(Motion value){motion=Objects.requireNonNull(value);save();}
    public static void setPause(PauseBackdrop value){pause=Objects.requireNonNull(value);save();}
    public static void setMist(boolean value){mist=value;save();}
    public static void setStrength(float value){if(!Float.isFinite(value)) return;strength=Math.clamp(value,0,1.5f);save();}
    private static void save() {
        try {
            var p=new Properties();
            if(Files.exists(FILE)) try(var in=Files.newBufferedReader(FILE)){p.load(in);}
            p.setProperty("analogMotion",motion.name());p.setProperty("pauseBackdrop",pause.name());
            p.setProperty("analogStrength",Float.toString(strength));p.setProperty("menuMist",Boolean.toString(mist));
            Files.createDirectories(FILE.getParent());
            Path tmp=Files.createTempFile(FILE.getParent(),"ui-",".tmp");
            try {
                try(var out=Files.newBufferedWriter(tmp)){p.store(out,"DarkWinter UI: presentation only");}
                try {Files.move(tmp,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException e){Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING);}
            } finally {Files.deleteIfExists(tmp);}
        } catch(IOException e){LOG.error("Cannot save UI settings",e);}
    }
}
