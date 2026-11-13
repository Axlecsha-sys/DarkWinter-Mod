// Per-source effects, composing existing character colour with world occlusion.
package com.winterkore.engine.audio;

import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTSourceSpatialize;

public final class WkeAudioSource {
    private final int source;
    private long context;
    private int directFilter,sendFilter,appliedSlot;
    private float bodyLow=1,bodyHigh=1,hostGain=1;
    private boolean worldSource,released,directAttached;
    private AcousticModel.Parameters parameters=AcousticModel.DRY;
    private static volatile int liveSources,liveFilters;
    private static volatile long applications,spatialApplications,wrongContextSkips;
    private static volatile String lastAudioThread="";

    public WkeAudioSource(int source) { this.source=source;liveSources++; }

    public float hostVolume(float gain) {
        hostGain=gain;
        return gain*(!WkeAudioEfx.supported()?parameters.directGain():1);
    }

    public void apply(AcousticModel.Parameters next,boolean positionalWorldSound) {
        parameters=positionalWorldSound?next:AcousticModel.DRY;worldSource=positionalWorldSound;
        long current=ALC10.alcGetCurrentContext();
        if(current==0||(context!=0&&current!=context)) { wrongContextSkips++;return; }
        if(positionalWorldSound&&AL.getCapabilities().AL_SOFT_source_spatialize)
            AL10.alSourcei(source,SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT,AL10.AL_TRUE);
        refresh();applications++;if(positionalWorldSound)spatialApplications++;
        lastAudioThread=Thread.currentThread().getName();
    }

    public void bodyColour(float low,float high) {
        float nextLow=Math.clamp(low,0,1),nextHigh=Math.clamp(high,0,1);
        if(Math.abs(nextLow-bodyLow)<.001f&&Math.abs(nextHigh-bodyHigh)<.001f)return;
        bodyLow=nextLow;bodyHigh=nextHigh;refresh();
    }

    private void refresh() {
        if(released)return;
        long current=ALC10.alcGetCurrentContext();
        if(current==0)return;
        if(context==0)context=current;
        if(current!=context)return;
        if(!WkeAudioEfx.supported()) {
            // Gain still conveys obstruction on devices without EFX; never claim a room effect.
            AL10.alSourcef(source,AL10.AL_GAIN,hostGain*parameters.directGain());return;
        }
        float high=Math.clamp(bodyHigh*parameters.highFrequencyGain(),.001f,1);
        boolean filterNeeded=bodyLow<.999f||high<.999f||parameters.directGain()<.999f;
        if(filterNeeded) {
            if(directFilter==0) {
                directFilter=EXTEfx.alGenFilters();liveFilters++;
                EXTEfx.alFilteri(directFilter,EXTEfx.AL_FILTER_TYPE,EXTEfx.AL_FILTER_BANDPASS);
            }
            EXTEfx.alFilterf(directFilter,EXTEfx.AL_BANDPASS_GAIN,parameters.directGain());
            EXTEfx.alFilterf(directFilter,EXTEfx.AL_BANDPASS_GAINLF,bodyLow);
            EXTEfx.alFilterf(directFilter,EXTEfx.AL_BANDPASS_GAINHF,high);
            AL10.alSourcei(source,EXTEfx.AL_DIRECT_FILTER,directFilter);directAttached=true;
        }else { AL10.alSourcei(source,EXTEfx.AL_DIRECT_FILTER,EXTEfx.AL_FILTER_NULL);directAttached=false; }
        int slot=worldSource?WkeAudioEfx.room(parameters):0;
        if(slot!=0) {
            if(sendFilter==0) {
                sendFilter=EXTEfx.alGenFilters();liveFilters++;
                EXTEfx.alFilteri(sendFilter,EXTEfx.AL_FILTER_TYPE,EXTEfx.AL_FILTER_LOWPASS);
            }
            float wet=parameters.reverbGain()*(float)Math.sqrt(parameters.directGain());
            EXTEfx.alFilterf(sendFilter,EXTEfx.AL_LOWPASS_GAIN,wet);
            EXTEfx.alFilterf(sendFilter,EXTEfx.AL_LOWPASS_GAINHF,Math.max(.15f,parameters.highFrequencyGain()));
            AL11.alSource3i(source,EXTEfx.AL_AUXILIARY_SEND_FILTER,slot,0,sendFilter);
            appliedSlot=slot;
        }else if(appliedSlot!=0) {
            AL11.alSource3i(source,EXTEfx.AL_AUXILIARY_SEND_FILTER,0,0,0);appliedSlot=0;
        }
    }

    public void destroy() {
        if(released)return;
        long current=ALC10.alcGetCurrentContext();
        if(current!=0&&(context==0||context==current)&&WkeAudioEfx.supported()) {
            if(directFilter!=0)AL10.alSourcei(source,EXTEfx.AL_DIRECT_FILTER,0);
            if(appliedSlot!=0)AL11.alSource3i(source,EXTEfx.AL_AUXILIARY_SEND_FILTER,0,0,0);
            if(directFilter!=0)EXTEfx.alDeleteFilters(directFilter);
            if(sendFilter!=0)EXTEfx.alDeleteFilters(sendFilter);
        }
        liveFilters-=(directFilter==0?0:1)+(sendFilter==0?0:1);
        directFilter=0;sendFilter=0;appliedSlot=0;released=true;liveSources--;
    }

    public Map<String,Object> inspect() {
        long current=ALC10.alcGetCurrentContext();
        if(current==0||(context!=0&&current!=context))
            return Map.of("active",false,"source",source,"reason","host OpenAL context is not current");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("source",source);result.put("worldSource",worldSource);
        result.put("audioThread",Thread.currentThread().getName());
        result.put("context",ALC10.alcGetCurrentContext());
        result.put("sourceValid",AL10.alIsSource(source));result.put("sourceGain",AL10.alGetSourcef(source,AL10.AL_GAIN));
        result.put("sourceRelative",AL10.alGetSourcei(source,AL10.AL_SOURCE_RELATIVE));
        float[] position=new float[3];
        AL10.alGetSourcefv(source,AL10.AL_POSITION,position);
        result.put("sourceX",position[0]);result.put("sourceY",position[1]);result.put("sourceZ",position[2]);
        result.put("directFilter",directFilter);result.put("directFilterAttached",directAttached);
        result.put("sendFilter",sendFilter);result.put("appliedRoomSlot",appliedSlot);
        result.put("requestedDirectGain",parameters.directGain());result.put("requestedHighGain",parameters.highFrequencyGain());
        result.put("barriers",parameters.barriers());result.put("enclosure",parameters.enclosure());result.put("distance",parameters.distance());
        result.put("bodyLow",bodyLow);result.put("bodyHigh",bodyHigh);
        if(directAttached&&directFilter!=0&&WkeAudioEfx.supported()) {
            result.put("filterValid",EXTEfx.alIsFilter(directFilter));
            result.put("actualDirectGain",EXTEfx.alGetFilterf(directFilter,EXTEfx.AL_BANDPASS_GAIN));
            result.put("actualLowGain",EXTEfx.alGetFilterf(directFilter,EXTEfx.AL_BANDPASS_GAINLF));
            result.put("actualHighGain",EXTEfx.alGetFilterf(directFilter,EXTEfx.AL_BANDPASS_GAINHF));
        }else {
            float actualGain=AL10.alGetSourcef(source,AL10.AL_GAIN);
            result.put("actualDirectGain",hostGain>0?actualGain/hostGain:1);result.put("actualHighGain",1);
        }
        result.put("actualWetGain",appliedSlot!=0&&sendFilter!=0&&WkeAudioEfx.supported()
                ?EXTEfx.alGetFilterf(sendFilter,EXTEfx.AL_LOWPASS_GAIN):0);
        result.putAll(WkeAudioEfx.inspectRoom());
        result.put("alError",AL10.alGetError());
        return result;
    }

    public static Map<String,Object> statusSnapshot() {
        return Map.of("liveSources",liveSources,"liveFilters",liveFilters,"parameterApplications",applications,
                "spatialApplications",spatialApplications,"wrongContextSkips",wrongContextSkips,"lastAudioThread",lastAudioThread);
    }
}
