package com.dark.winter.client.mixin.client;

import com.dark.winter.client.voice.CharacterAcoustics;
import com.dark.winter.client.voice.CharacterAudioChannel;
import com.mojang.blaze3d.audio.Channel;
import com.winterkore.engine.audio.WkeAcousticChannel;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.SOFTSourceSpatialize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Character distance colour composes with WKE world occlusion in the same direct filter. */
@Mixin(Channel.class)
abstract class CharacterAudioChannelMixin implements CharacterAudioChannel {
    @Shadow @Final private int source;

    @Override public void darkwinter$characterDepth(boolean first,boolean footstep,float range,CharacterAcoustics.Colour colour) {
        if(AL.getCapabilities().AL_SOFT_source_spatialize) {
            // Preserve the centered first-person voice/breath and alternating foot contact.
            AL10.alSourcei(source,SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT,
                    first&&!footstep?AL10.AL_FALSE:AL10.AL_TRUE);
        }
        if(first) {
            ((Channel)(Object)this).disableAttenuation();
            ((WkeAcousticChannel)this).wke$bodyColour(1,1);
            return;
        }
        AL10.alSourcei(source,0xD000,0xD002); // AL_INVERSE_DISTANCE_CLAMPED, per-source extension
        AL10.alSourcef(source,AL10.AL_REFERENCE_DISTANCE,CharacterAcoustics.REFERENCE_DISTANCE);
        AL10.alSourcef(source,AL10.AL_ROLLOFF_FACTOR,CharacterAcoustics.ROLLOFF);
        AL10.alSourcef(source,AL10.AL_MAX_DISTANCE,Math.max(1,range));
        ((WkeAcousticChannel)this).wke$bodyColour(colour.low(),colour.high());
    }
}
