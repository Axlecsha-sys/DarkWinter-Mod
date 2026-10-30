// Replaces native precipitation rendering with synchronized DarkWinter snowfall.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.weather.WeatherClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherPrecipitationMixin {
    // Verified against installed Mojang-mapped Minecraft 26.3 sources.
    @Inject(method = "extractRenderState(Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void darkwinter$customSnow(ClientLevel level, float partialTicks, Vec3 cameraPos,
                                       WeatherRenderState state, CallbackInfo ci) {
        if (WeatherClient.active()) {
            state.reset();
            ci.cancel();
        }
    }
}
