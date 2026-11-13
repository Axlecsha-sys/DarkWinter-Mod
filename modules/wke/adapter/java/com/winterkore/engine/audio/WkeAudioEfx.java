// One bounded reverb environment per Minecraft OpenAL context, not a second audio device.
package com.winterkore.engine.audio;

import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTHRTF;

public final class WkeAudioEfx {
    private static volatile long context;
    private static volatile int effect,slot;
    private static float decay=-1,delay=-1;
    private static volatile boolean efxAvailable;
    private static volatile int hrtfStatus;
    private static volatile long roomCreations,roomReleases;
    private WkeAudioEfx() { }

    public static boolean supported() {
        return ALC10.alcGetCurrentContext()!=0 && ALC.getCapabilities().ALC_EXT_EFX;
    }

    /** Called only by ChannelHandle's audio executor with the host context current. */
    public static synchronized int room(AcousticModel.Parameters parameters) {
        long current=ALC10.alcGetCurrentContext();
        if(current==0||!supported())return 0;
        efxAvailable=true;
        if(context!=current) {
            // Context teardown owns old names; never delete a name in a different context.
            context=current;effect=0;slot=0;decay=-1;delay=-1;
            long device=ALC10.alcGetContextsDevice(current);
            hrtfStatus=ALC.getCapabilities().ALC_SOFT_HRTF
                    ?ALC10.alcGetInteger(device,SOFTHRTF.ALC_HRTF_STATUS_SOFT):0;
        }
        if(parameters.reverbGain()<.001f)return 0;
        if(slot==0) {
            effect=EXTEfx.alGenEffects();
            EXTEfx.alEffecti(effect,EXTEfx.AL_EFFECT_TYPE,EXTEfx.AL_EFFECT_REVERB);
            EXTEfx.alEffectf(effect,EXTEfx.AL_REVERB_GAIN,.40f);
            EXTEfx.alEffectf(effect,EXTEfx.AL_REVERB_GAINHF,.65f);
            EXTEfx.alEffectf(effect,EXTEfx.AL_REVERB_REFLECTIONS_GAIN,.20f);
            EXTEfx.alEffectf(effect,EXTEfx.AL_REVERB_LATE_REVERB_GAIN,1.10f);
            slot=EXTEfx.alGenAuxiliaryEffectSlots();
            roomCreations++;
        }
        // Quantization avoids rebuilding the effect for sub-centimetre camera motion.
        float newDecay=Math.round(parameters.decaySeconds()*20)/20f;
        float newDelay=Math.round(parameters.reflectionDelay()*1000)/1000f;
        if(newDecay!=decay||newDelay!=delay) {
            decay=newDecay;delay=newDelay;
            EXTEfx.alEffectf(effect,EXTEfx.AL_REVERB_DECAY_TIME,decay);
            EXTEfx.alEffectf(effect,EXTEfx.AL_REVERB_REFLECTIONS_DELAY,delay);
            EXTEfx.alAuxiliaryEffectSloti(slot,EXTEfx.AL_EFFECTSLOT_EFFECT,effect);
        }
        return slot;
    }

    /** Host calls after all channels are released, before destroying its still-current context. */
    public static synchronized void releaseRoom() {
        long current=ALC10.alcGetCurrentContext();
        if(current!=0&&current==context) {
            if(slot!=0) { EXTEfx.alAuxiliaryEffectSloti(slot,EXTEfx.AL_EFFECTSLOT_EFFECT,0);EXTEfx.alDeleteAuxiliaryEffectSlots(slot); }
            if(effect!=0)EXTEfx.alDeleteEffects(effect);
            if(slot!=0||effect!=0)roomReleases++;
        }
        context=0;effect=0;slot=0;decay=-1;delay=-1;efxAvailable=false;hrtfStatus=0;
    }

    public static Map<String,Object> statusSnapshot() {
        return Map.of("roomContext",context,"efxAvailable",efxAvailable,"roomEffect",effect,"roomSlot",slot,
                "roomCreations",roomCreations,"roomReleases",roomReleases,"hrtfStatus",hrtfStatus);
    }

    /** Actual effect values, read by the explicit diagnostic future on the audio executor only. */
    static Map<String,Object> inspectRoom() {
        Map<String,Object> result=new LinkedHashMap<>(statusSnapshot());
        if(context==ALC10.alcGetCurrentContext()&&effect!=0) {
            result.put("effectValid",EXTEfx.alIsEffect(effect));
            result.put("slotValid",EXTEfx.alIsAuxiliaryEffectSlot(slot));
            result.put("actualDecay",EXTEfx.alGetEffectf(effect,EXTEfx.AL_REVERB_DECAY_TIME));
            result.put("actualReflectionDelay",EXTEfx.alGetEffectf(effect,EXTEfx.AL_REVERB_REFLECTIONS_DELAY));
        }
        return result;
    }
}
