// Samples climate and tick-cached shelter information for HUD presentation.
package com.dark.winter.client.weather;

import com.dark.winter.weather.WindExposure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Shelter queries are tick-cached rather than raycast once per HUD frame. */
public final class ClimateDisplay {
    private static ClientLevel level;
    private static long sampledAt = Long.MIN_VALUE;
    private static Vec3 position = Vec3.ZERO;
    private static float exposure = 1;
    private ClimateDisplay() { }

    public static float exposure() {
        Minecraft client = Minecraft.getInstance();
        if (!WeatherClient.active() || client.player == null) {
            level = null;
            return 1;
        }
        long now = client.level.getGameTime();
        if (level != client.level || now < sampledAt || now - sampledAt >= 10
                || position.distanceToSqr(client.player.position()) > 1) {
            level = client.level;
            sampledAt = now;
            position = client.player.position();
            exposure = WindExposure.exposure(client.player, WeatherClient.climate(0));
        }
        return exposure;
    }
}
