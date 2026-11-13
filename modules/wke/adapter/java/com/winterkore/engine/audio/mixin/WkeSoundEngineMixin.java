package com.winterkore.engine.audio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Channel;
import com.winterkore.engine.audio.WkeAcousticChannel;
import com.winterkore.engine.audio.WkeAudioEfx;
import com.winterkore.engine.audio.WkeAudioRuntime;
import com.winterkore.engine.audio.WkeSoundEngineAccess;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class WkeSoundEngineMixin implements WkeSoundEngineAccess {
    @Shadow @Final private Map<SoundInstance,ChannelAccess.ChannelHandle> instanceToChannel;
    @Override public Map<SoundInstance,ChannelAccess.ChannelHandle> wke$channels() { return instanceToChannel; }

    @WrapOperation(method="play",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V"),
            require=1,allow=1)
    private void wke$configureActualSource(ChannelAccess.ChannelHandle handle,Consumer<Channel> configure,
            Operation<Void> original,@Local(argsOnly=true) SoundInstance sound) {
        WkeAudioRuntime.Frame frame=WkeAudioRuntime.prepare(sound);
        original.call(handle,(Consumer<Channel>)channel->{
            configure.accept(channel);
            ((WkeAcousticChannel)channel).wke$acoustics(frame.parameters(),frame.worldSource());
        });
    }

    @Inject(method="updateSource",at=@At("TAIL"))
    private void wke$updateAcoustics(Camera camera,CallbackInfo ci) {
        WkeAudioRuntime.update(instanceToChannel,camera);
    }

    // stopAll first joins/shuts down the executor, then destroys every channel on this thread.
    @WrapOperation(method="stopAll",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/sounds/ChannelAccess;clear()V"))
    private void wke$clearRoomAfterSources(ChannelAccess channels,Operation<Void> original) {
        original.call(channels);WkeAudioEfx.releaseRoom();WkeAudioRuntime.resetWorld();
    }
}
