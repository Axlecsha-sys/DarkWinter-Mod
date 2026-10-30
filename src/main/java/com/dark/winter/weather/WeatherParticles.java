// Registers weather particle types for client-side implementations.
package com.dark.winter.weather;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Common registry entries only. Particle implementations and emission stay on the client. */
public final class WeatherParticles {
    public static final SimpleParticleType SNOW_FLAKE = register("snow_flake");
    public static final SimpleParticleType GROUND_SNOW = register("ground_snow");
    public static final SimpleParticleType BREATH = register("breath");

    private WeatherParticles() { }

    private static SimpleParticleType register(String name) {
        return Registry.register(BuiltInRegistries.PARTICLE_TYPE,
                Identifier.fromNamespaceAndPath("darkwinter", name), FabricParticleTypes.simple());
    }

    public static void init() { }
}
