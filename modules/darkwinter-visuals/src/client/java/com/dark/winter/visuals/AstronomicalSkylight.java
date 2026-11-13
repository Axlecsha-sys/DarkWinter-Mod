// Samples static environmental skylight without applying the vanilla clock cycle twice.
package com.dark.winter.visuals;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

/** Render-thread cache of dimension and biome attributes; no world light data is modified. */
public final class AstronomicalSkylight {
    // Presentation-only floor keeps night snow relief readable without changing the physical sky or light data.
    public static final float MIN_SCENE_SKYLIGHT = .08f;
    private static ClientLevel world;
    private static EnvironmentAttributeSystem staticLayers;
    private static boolean disconnectRegistered;

    private AstronomicalSkylight() { }

    public static float staticFactor(ClientLevel level, Vec3 position) {
        if (!disconnectRegistered) {
            ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> clear());
            disconnectRegistered = true;
        }
        if (world != level || staticLayers == null) {
            staticLayers = EnvironmentAttributeSystem.builder().addStaticLayers(level).build();
            world = level;
        }
        return staticLayers.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, position);
    }

    /** One geographic day/night factor, preserving explicit dimension and biome scaling. */
    public static float factor(float staticFactor, float elevationDegrees, float weather) {
        float illumination=Float.isFinite(elevationDegrees)
                ? Math.max(SolarDaylight.illumination(elevationDegrees),MIN_SCENE_SKYLIGHT):0f;
        return Math.clamp(staticFactor * illumination * weather, 0f, 1f);
    }

    /** Native 26.3 rain blend, then thunder blend, applied after geographic daylight. */
    public static float nativeWeather(float skyFactor, float rainLevel, float thunderLevel) {
        float rainOnly=rainLevel-thunderLevel;
        if (rainOnly>0f) {
            float rainSky=skyFactor+.3125f*(.24f-skyFactor);
            skyFactor+=rainOnly*(rainSky-skyFactor);
        }
        if (thunderLevel>0f) {
            float thunderSky=skyFactor+.52734375f*(.24f-skyFactor);
            skyFactor+=thunderLevel*(thunderSky-skyFactor);
        }
        return Math.clamp(skyFactor,0f,1f);
    }

    public static void clear() {
        world = null;
        staticLayers = null;
    }
}
