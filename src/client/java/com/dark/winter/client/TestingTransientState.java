// Preserves client-side test state while isolated sessions leave the authoring save untouched.
package com.dark.winter.client;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;

/** Client-only prototype state accompanies isolated tests; author snapshots survive disconnect. */
public final class TestingTransientState {
    private record State(int schema,SprintStamina.State stamina,float temperature,float fatigue,float thirst,
                         CameraType camera,boolean rightShoulder) {}
    private static final Gson JSON=new Gson();
    private static Object restoredCameraLevel;
    private TestingTransientState() {}
    /** Consumed by the outer world-entry initializer in the same client tick. */
    public static boolean consumeRestoredCamera(Minecraft client) {
        Object restored=restoredCameraLevel;restoredCameraLevel=null;
        return restored!=null&&restored==client.level;
    }
    public static void save(Path file) throws IOException {
        State state=new State(2,SprintStamina.captureState(),SurvivalNeeds.getTemperature(),SurvivalNeeds.getFatigue(),
                SurvivalNeeds.getThirst(),Minecraft.getInstance().options.getCameraType(),ShoulderCameraState.isRightShoulder());
        Files.createDirectories(file.getParent());Path tmp=Files.createTempFile(file.getParent(),"client-state-", ".tmp");
        try {Files.writeString(tmp,JSON.toJson(state));
            try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
    public static void restore(Path file) throws IOException {
        if(!Files.isRegularFile(file))return;
        if(Files.size(file)>16384)throw new IOException("Client state snapshot too large");
        State state;
        try {state=JSON.fromJson(Files.readString(file),State.class);
            if(state.schema()!=2||state.camera()==null||!Float.isFinite(state.temperature()+state.fatigue()+state.thirst()))
                throw new IOException("Invalid client state");
            SprintStamina.restoreState(state.stamina());
        }catch(RuntimeException e){throw new IOException("Invalid client state",e);}
        SurvivalNeeds.setTemperature(state.temperature());SurvivalNeeds.setFatigue(state.fatigue());SurvivalNeeds.setThirst(state.thirst());
        Minecraft client=Minecraft.getInstance();
        client.options.setCameraType(state.camera());restoredCameraLevel=client.level;
        if(ShoulderCameraState.isRightShoulder()!=state.rightShoulder())ShoulderCameraState.toggleShoulder();
        ShoulderCameraState.finishManualToFirst();ShoulderCameraState.pauseSmoothing();
    }
}
