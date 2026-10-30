// Loads and refreshes bounded movement and camera settings from the local configuration file.
package com.dark.winter.client.motion;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MotionConfig {
    private static final Logger LOG=LoggerFactory.getLogger("DarkWinter/Motion");
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("darkwinter/motion.properties");
    private static volatile MotionSettings current=MotionSettings.DEFAULT;
    private static long lastPoll, modified=Long.MIN_VALUE;
    private static boolean triedCreate;
    private MotionConfig() { }
    private record TestingOverride(MotionSettings source,boolean movement,boolean camera,double walkSpeed,double cameraWeight,double turnWeight,MotionSettings settings) {}
    private static volatile TestingOverride testingOverride;
    public static MotionSettings get() {
        MotionSettings source=current;
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab==null)return source;
        boolean movement=com.dark.winter.client.TestingAccess.enabled("motion");
        boolean camera=com.dark.winter.client.TestingAccess.enabled("camera");
        TestingOverride cache=testingOverride;
        if(cache!=null && cache.source()==source && cache.movement()==movement && cache.camera()==camera
                &&cache.walkSpeed()==lab.motionWalkSpeed&&cache.cameraWeight()==lab.motionCameraWeight&&cache.turnWeight()==lab.motionTurnWeight)return cache.settings();
        MotionSettings derived=source.testing(movement,camera).testingNumbers(lab.motionWalkSpeed,lab.motionCameraWeight,lab.motionTurnWeight);
        testingOverride=new TestingOverride(source,movement,camera,lab.motionWalkSpeed,lab.motionCameraWeight,lab.motionTurnWeight,derived);
        return derived;
    }
    public static void poll() {
        long now=System.nanoTime();
        if(lastPoll!=0 && now-lastPoll<1_000_000_000L) return;
        lastPoll=now;
        try {
            if(!Files.exists(FILE)) {
                if(triedCreate) return;
                triedCreate=true;
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE,DEFAULT_FILE,StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            long stamp=Files.getLastModifiedTime(FILE).toMillis();
            if(stamp==modified) return;
            Properties p=new Properties();
            try(Reader r=Files.newBufferedReader(FILE,StandardCharsets.UTF_8)){p.load(r);}
            MotionSettings next=new MotionSettings(
                    bool(p,"enabled",true),bool(p,"locomotion",true),bool(p,"camera",true),bool(p,"body",true),
                    bool(p,"footsteps",true),bool(p,"clothing",false),
                    number(p,"walkSpeed",0.82,0.55,1.0),number(p,"startSeconds",0.14,0.04,0.30),
                    number(p,"cameraWeight",1,0,1.6),number(p,"turnWeight",1,0,1.6),number(p,"stepWeight",1,0,1.6),
                    number(p,"walkStepBlocks",1.65,0.8,2.5),number(p,"runStepBlocks",2.05,1.2,3),
                    number(p,"stepVolume",0.30,0,0.65),number(p,"clothVolume",0.065,0,0.3),
                    number(p,"bodyWeight",1,0,1.5),
                    number(p,"backwardSpeed",0.60,0.30,1.0),number(p,"strafeSpeed",0.80,0.40,1.0),
                    number(p,"reverseSeconds",0.10,0.0,0.25),bool(p,"lookControl",true),
                    number(p,"lookResponseSeconds",0.085,0.015,0.20),
                    number(p,"maxYawSpeed",210,60,720),number(p,"maxPitchSpeed",150,45,540));
            current=next; modified=stamp;
            LOG.info("Motion: walkSpeed={}, cameraWeight={}, turnWeight={}, footsteps={}, config={}",
                    next.walkSpeed(),next.cameraWeight(),next.turnWeight(),next.footsteps(),FILE);
        } catch(IOException|IllegalArgumentException e) {
            LOG.warn("Motion configuration rejected; previous values kept: {}",e.toString());
            try {modified=Files.getLastModifiedTime(FILE).toMillis();} catch(IOException ignored) { }
        }
    }
    private static boolean bool(Properties p,String name,boolean fallback) {
        String v=p.getProperty(name,Boolean.toString(fallback)).trim();
        if(!v.equalsIgnoreCase("true")&&!v.equalsIgnoreCase("false")) throw new IllegalArgumentException(name+" must be true or false");
        return Boolean.parseBoolean(v);
    }
    private static double number(Properties p,String name,double fallback,double min,double max) {
        double v=Double.parseDouble(p.getProperty(name,Double.toString(fallback)).trim());
        if(!Double.isFinite(v)||v<min||v>max) throw new IllegalArgumentException(name+" must be in "+min+".."+max);
        return v;
    }
    private static final String DEFAULT_FILE="""
            # DarkWinter weighted walking. Reloaded once a second; no restart for settings.
            # Client-side prototype. This file is separate from visuals.properties.
            enabled=true
            locomotion=true
            camera=true
            body=true
            footsteps=true
            clothing=false
            # Ordinary walking only; existing Shift/sprint/stamina remain in control of sprint.
            walkSpeed=0.82
            startSeconds=0.14
            # 0 disables an effect; 1 is the expressive default.
            cameraWeight=1.0
            turnWeight=1.0
            stepWeight=1.0
            bodyWeight=1.0
            # Distance between alternating foot contacts (blocks).
            walkStepBlocks=1.65
            runStepBlocks=2.05
            # Subject to the in-game Players sound slider as well.
            stepVolume=0.30
            clothVolume=0.0
            # RC2: relative to ordinary forward walking (also limits diagonals).
            backwardSpeed=0.60
            strafeSpeed=0.80
            # Replant delay when changing an axis to its opposite sign.
            reverseSeconds=0.10
            # Applies to shoulder view only; angles are degrees / second.
            lookControl=true
            lookResponseSeconds=0.085
            maxYawSpeed=210
            maxPitchSpeed=150
            # RC2 uses only block step SoundEvents. clothing/clothVolume are ignored.
            """;
}
