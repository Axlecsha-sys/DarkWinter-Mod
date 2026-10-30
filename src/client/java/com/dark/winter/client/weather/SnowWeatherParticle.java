// A small depth-tested flake with terminal velocity, slow tumble and coherent gusts.
package com.dark.winter.client.weather;

import com.dark.winter.weather.WeatherValues;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleLimit;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.Optional;

/** A small depth-tested flake with terminal velocity, slow tumble and coherent gusts. */
public final class SnowWeatherParticle extends SingleQuadParticle {
    private static final ParticleLimit LIMIT = new ParticleLimit(3000);
    private final float phase;
    private final float fallSpeed;
    private final float spin;
    private final float opacity;
    private long lastTickTime;

    private SnowWeatherParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        lastTickTime = level.getGameTime();
        setSprite(sprites.get(random));
        setSize(0.035F, 0.035F);
        quadSize = 0.025F + random.nextFloat() * random.nextFloat() * 0.080F;
        lifetime = 130 + random.nextInt(80);
        friction = 1.0F;
        gravity = 0.0F;
        phase = random.nextFloat() * Mth.TWO_PI;
        fallSpeed = 0.055F + random.nextFloat() * 0.065F;
        spin = (random.nextFloat() - 0.5F) * 0.045F;
        opacity = 0.65F + random.nextFloat() * 0.28F;
        alpha = 0.0F;
        roll = oRoll = phase;
        setColor(0.91F + random.nextFloat() * 0.06F, 0.95F + random.nextFloat() * 0.04F, 1.0F);
        WeatherValues weather = WeatherEffects.weather();
        xd = weather.windX() * weather.windSpeed() * 0.38;
        yd = -fallSpeed;
        zd = weather.windZ() * weather.windSpeed() * 0.38;
    }

    @Override
    public void tick() {
        lastTickTime = level.getGameTime();
        if (!WeatherClient.active()) {
            remove();
            return;
        }
        WeatherValues weather = WeatherEffects.weather();
        double time = level.getGameTime();
        double gust = 0.85 + 0.12 * Math.sin(time * 0.037 + x * 0.06) + 0.08 * Math.sin(time * 0.081);
        double wind = weather.windSpeed() * 0.46 * gust;
        double flutter = Math.sin(age * 0.085 + phase) * 0.012;
        xd += (weather.windX() * wind + flutter - xd) * 0.09;
        zd += (weather.windZ() * wind + Math.cos(age * 0.067 + phase) * 0.01 - zd) * 0.09;
        yd += (-fallSpeed * (1.0 + weather.windSpeed() * 0.9) - yd) * 0.10;
        oRoll = roll;
        roll += spin * (1.0F + weather.windSpeed());
        super.tick();
        if (onGround || ((age & 3) == 0 && !WeatherEffects.exposed(level, BlockPos.containing(x, y, z)))) {
            remove();
        }
        double cameraDistance = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceToSqr(x, y, z);
        if (cameraDistance > 36.0 * 36.0) {
            remove();
        }
        float fadeIn = Mth.clamp(age / 9.0F, 0.0F, 1.0F);
        float fadeOut = Mth.clamp((lifetime - age) / 24.0F, 0.0F, 1.0F);
        float nearFade = Mth.clamp((float) (cameraDistance - 0.35) / 1.6F, 0.0F, 1.0F);
        alpha = opacity * fadeIn * fadeOut * nearFade;
    }

    boolean discardedByEngine(long gameTime) {
        // A resource reload clears ParticleEngine without calling Particle.remove().
        return gameTime - lastTickTime > 3;
    }

    @Override
    public Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    @Override
    public Optional<ParticleLimit> getParticleLimit() {
        return Optional.of(LIMIT);
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double dx, double dy, double dz, RandomSource random) {
            return new SnowWeatherParticle(level, x, y, z, sprites);
        }
    }
}
