package com.dark.winter.client.mixin.client;

import com.dark.winter.client.voice.CharacterSoundInstance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Channel;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native ticks update position/gain, but relative/attenuation are otherwise set only at playback. */
@Mixin(SoundEngine.class)
abstract class CharacterSoundPerspectiveMixin {
    @Shadow @Final private Map<SoundInstance,ChannelAccess.ChannelHandle> instanceToChannel;

    @WrapOperation(method="play",at=@At(value="INVOKE",target="Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V"),require=1,allow=1)
    private void darkwinter$initialCharacterDepth(ChannelAccess.ChannelHandle handle,Consumer<Channel> configure,
            Operation<Void> original,@Local(argsOnly=true) SoundInstance sound) {
        if(sound instanceof CharacterSoundInstance character) {
            var client=Minecraft.getInstance();var perspective=character.perspective(client,client.gameRenderer.mainCamera());
            original.call(handle,(Consumer<Channel>)channel->{configure.accept(channel);perspective.apply(channel);});
        }else original.call(handle,configure);
    }

    @Inject(method="updateSource",at=@At("TAIL"),require=1,allow=1)
    private void darkwinter$characterPerspective(Camera camera,CallbackInfo ci) {
        var client=Minecraft.getInstance();
        for(var entry:instanceToChannel.entrySet()) {
            if(entry.getKey() instanceof CharacterSoundInstance character&&!character.isStopped()) {
                var perspective=character.perspective(client,camera);
                entry.getValue().execute(perspective::apply);
            }
        }
    }
}
