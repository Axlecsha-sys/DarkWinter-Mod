// Reuses the game's air-rush recording, with slow gusts and shelter attenuation.
package com.dark.winter.client.weather;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/** Reuses the game's air-rush recording, with slow gusts and shelter attenuation. */
final class WeatherWindSound extends AbstractTickableSoundInstance {
    private final ClientLevel level;

    WeatherWindSound(ClientLevel level) {
        super(SoundEvents.ELYTRA_FLYING, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        this.level = level;
        looping = true;
        delay = 0;
        relative = true;
        attenuation = Attenuation.NONE;
        volume = 0.0F;
        pitch = 0.65F;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != level || client.player == null || !WeatherClient.active()) {
            stop();
            return;
        }
        float speed = WeatherEffects.weather().windSpeed();
        float outside = 0.12F + 0.88F * WeatherEffects.localWindExposure();
        if (client.player.isUnderWater()) {
            outside = 0.0F;
        }
        float gust = 0.82F + 0.12F * (float) Math.sin(level.getGameTime() * 0.024)
                + 0.06F * (float) Math.sin(level.getGameTime() * 0.057);
        float target = Mth.clamp((speed - 0.14F) * 0.42F, 0.0F, 0.60F) * outside * gust;
        volume += (target - volume) * 0.035F;
        pitch = 0.60F + speed * 0.18F;
        if (speed < 0.15F && volume < 0.002F) {
            stop();
        }
    }
}
