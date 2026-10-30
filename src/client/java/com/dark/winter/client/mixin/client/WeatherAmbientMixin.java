// Routes local weather ambience through DarkWinter weather effects.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.weather.WeatherClient;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class WeatherAmbientMixin {
    // This method in 26.3 only emits vanilla rain splashes and rain sounds.
    @Inject(method = "tickWeatherEffects()V", at = @At("HEAD"), cancellable = true)
    private void darkwinter$winterAmbience(CallbackInfo ci) {
        if (WeatherClient.active()) {
            ci.cancel();
        }
    }
}
