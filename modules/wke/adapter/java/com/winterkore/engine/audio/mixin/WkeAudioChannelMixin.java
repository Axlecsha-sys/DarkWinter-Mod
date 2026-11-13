package com.winterkore.engine.audio.mixin;

import com.mojang.blaze3d.audio.Channel;
import com.winterkore.engine.audio.AcousticModel;
import com.winterkore.engine.audio.WkeAcousticChannel;
import com.winterkore.engine.audio.WkeAudioSource;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs in exactly the same context/thread as the host's real Channel operations. */
@Mixin(Channel.class)
public abstract class WkeAudioChannelMixin implements WkeAcousticChannel {
    @Shadow @Final private int source;
    @Unique private WkeAudioSource wke$source;

    @Unique private WkeAudioSource wke$state() {
        if(wke$source==null)wke$source=new WkeAudioSource(source);
        return wke$source;
    }

    @Override public void wke$acoustics(AcousticModel.Parameters parameters,boolean worldSource) {
        wke$state().apply(parameters,worldSource);
    }
    @Override public void wke$bodyColour(float low,float high) { wke$state().bodyColour(low,high); }
    @Override public Map<String,Object> wke$audioSnapshot() { return wke$state().inspect(); }

    @ModifyVariable(method="setVolume",at=@At("HEAD"),argsOnly=true)
    private float wke$preserveHostGain(float volume) { return wke$state().hostVolume(volume); }

    @Inject(method="destroy",at=@At("HEAD"))
    private void wke$releaseFilters(CallbackInfo ci) { if(wke$source!=null)wke$source.destroy(); }
}
