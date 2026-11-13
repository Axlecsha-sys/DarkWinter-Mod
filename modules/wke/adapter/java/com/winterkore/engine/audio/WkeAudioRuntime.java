// Captures bounded acoustic geometry on the client thread and schedules real channel updates.
package com.winterkore.engine.audio;

import com.dark.winter.client.voice.CharacterSoundInstance;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import com.winterkore.engine.audio.mixin.WkeSoundManagerAccess;

/** World reads stay on Minecraft's client thread; OpenAL never runs here. */
public final class WkeAudioRuntime {
    private static final long WINDOW_NANOS=100_000_000L,ROOM_NANOS=250_000_000L;
    private static final IdentityHashMap<SoundInstance,AcousticModel.Parameters> cached=new IdentityHashMap<>();
    private static ClientLevel world;
    private static AcousticModel.Room room=AcousticModel.OUTDOORS;
    private static MinecraftAcousticWorld.Budget budget=new MinecraftAcousticWorld.Budget(0);
    private static long windowStarted,lastUpdate,lastRoomProbe,windows,sourceQueries,roomQueries,budgetSkips;
    private static int cursor,lastWindowCells;
    private static volatile boolean enabled=true;
    private WkeAudioRuntime() { }

    public record Frame(AcousticModel.Parameters parameters,boolean worldSource) {
        public static final Frame DRY=new Frame(AcousticModel.DRY,false);
    }

    /** Main-thread capture at playback: short one-shots get occlusion before their first sample. */
    public static Frame prepare(SoundInstance sound) {
        Minecraft client=Minecraft.getInstance();
        if(!client.isSameThread()||!enabled||client.level==null)return Frame.DRY;
        Camera camera=client.gameRenderer.mainCamera();
        if(!camera.isInitialized())return Frame.DRY;
        if(!spatial(sound))return Frame.DRY;
        begin(client.level,camera.position(),System.nanoTime());
        Vec3 listener=camera.position();
        Vec3 source=sound.isRelative()?listener:new Vec3(sound.getX(),sound.getY(),sound.getZ());
        double distance=listener.distanceTo(source);
        if(!Double.isFinite(distance)||distance>AcousticModel.MAX_SOURCE_DISTANCE)return Frame.DRY;
        if(budget.remaining()<8) {
            budgetSkips++;
            return new Frame(cached.getOrDefault(sound,AcousticModel.evaluate(
                    new AcousticModel.Path(1,1,0,false),room,distance)),true);
        }
        AcousticModel.Path path=MinecraftAcousticWorld.path(world,source,listener,budget);
        if(!path.complete()) {
            // An exhausted/unloaded ray is not evidence that a previously blocked path opened.
            budgetSkips++;
            AcousticModel.Parameters known=cached.get(sound);
            // New sources may use the barriers already observed, but do not cache a partial trace.
            return new Frame(known!=null?known:AcousticModel.evaluate(path,room,distance),true);
        }
        AcousticModel.Parameters next=AcousticModel.evaluate(path,room,distance);
        AcousticModel.Parameters previous=cached.get(sound);
        AcousticModel.Parameters result=previous==null?next:AcousticModel.smooth(previous,next,.55f);
        cached.put(sound,result);sourceQueries++;
        return new Frame(result,true);
    }

    /** Called from SoundEngine.updateSource after it captures the native listener transform. */
    public static void update(Map<SoundInstance,ChannelAccess.ChannelHandle> channels,Camera camera) {
        Minecraft client=Minecraft.getInstance();
        if(!client.isSameThread())return;
        if(client.level==null) { resetWorld();return; }
        long now=System.nanoTime();
        if(!camera.isInitialized()||now-lastUpdate<WINDOW_NANOS)return;
        begin(client.level,camera.position(),now);
        lastUpdate=now;
        cached.keySet().removeIf(sound->!channels.containsKey(sound));
        int size=channels.size();
        if(size==0) { cursor=0;return; }
        cursor%=size;
        int index=0,updated=0;
        for(var entry:channels.entrySet()) {
            if(updated>=AcousticModel.MAX_SOURCES_PER_WINDOW)break;
            if(index++<cursor)continue;
            if(!spatial(entry.getKey()))continue;
            Frame frame=enabled?prepare(entry.getKey()):Frame.DRY;
            entry.getValue().execute(channel->((WkeAcousticChannel)channel).wke$acoustics(
                    frame.parameters(),frame.worldSource()));
            updated++;
        }
        cursor=index>=size?0:index;
    }

    private static boolean spatial(SoundInstance sound) {
        if(sound.getSource()==SoundSource.MUSIC)return false;
        // Foot contact still excites the room in first person; centered breathing/voice stays dry.
        if(sound.isRelative())return sound instanceof CharacterSoundInstance character&&character.footstep();
        return sound.getAttenuation()!=SoundInstance.Attenuation.NONE;
    }

    private static void begin(ClientLevel current,Vec3 listener,long now) {
        if(world!=current) { resetWorld();world=current; }
        if(windowStarted==0||now-windowStarted>=WINDOW_NANOS) {
            lastWindowCells=budget.used();budget=new MinecraftAcousticWorld.Budget(AcousticModel.MAX_QUERY_CELLS_PER_WINDOW);
            windowStarted=now;windows++;
        }
        if(lastRoomProbe==0||now-lastRoomProbe>=ROOM_NANOS) {
            AcousticModel.Room sampled=MinecraftAcousticWorld.room(current,listener,budget);
            if(sampled!=null) { room=sampled;lastRoomProbe=now;roomQueries++; }
        }
    }

    /** Called after the host stops all channels, including disconnect/device reload. No OpenAL here. */
    public static void resetWorld() {
        world=null;cached.clear();room=AcousticModel.OUTDOORS;windowStarted=0;lastRoomProbe=0;lastUpdate=0;
        budget=new MinecraftAcousticWorld.Budget(0);cursor=0;
    }

    public static Map<String,Object> statusSnapshot() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",enabled);result.put("worldPresent",world!=null);result.put("trackedSounds",cached.size());
        result.put("sourceQueries",sourceQueries);result.put("roomQueries",roomQueries);result.put("queryWindows",windows);
        result.put("budgetSkips",budgetSkips);result.put("cellsThisWindow",budget.used());result.put("cellsLastWindow",lastWindowCells);
        result.put("maxCellsPerWindow",AcousticModel.MAX_QUERY_CELLS_PER_WINDOW);
        result.put("maxSourcesPerUpdate",AcousticModel.MAX_SOURCES_PER_WINDOW);
        result.put("roomEnclosure",room.enclosure());result.put("roomMeanDistance",room.meanDistance());
        result.putAll(WkeAudioSource.statusSnapshot());result.putAll(WkeAudioEfx.statusSnapshot());
        result.put("backend",Boolean.TRUE.equals(result.get("efxAvailable"))?"OPENAL_EFX":"OPENAL_POSITIONAL");
        return result;
    }

    /** Reads actual AL source/filter/effect values on ChannelHandle's existing audio executor. */
    public static CompletableFuture<Map<String,Object>> captureSource(SoundInstance sound) {
        Minecraft client=Minecraft.getInstance();
        if(!client.isSameThread())return CompletableFuture.failedFuture(
                new IllegalStateException("captureSource must be requested on the Minecraft client thread"));
        var engine=((WkeSoundManagerAccess)client.getSoundManager()).wke$soundEngine();
        var handle=((WkeSoundEngineAccess)engine).wke$channels().get(sound);
        if(handle==null||handle.isStopped())return CompletableFuture.completedFuture(Map.of("active",false));
        CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();
        handle.execute(channel->{
            try {
                Map<String,Object> state=new LinkedHashMap<>(((WkeAcousticChannel)channel).wke$audioSnapshot());
                state.putIfAbsent("active",true);result.complete(state);
            }catch(Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.completeOnTimeout(Map.of("active",false,"reason","channel released before capture"),2,TimeUnit.SECONDS);
    }

    public static void setEnabledForTest(boolean value) { enabled=value; }
}
