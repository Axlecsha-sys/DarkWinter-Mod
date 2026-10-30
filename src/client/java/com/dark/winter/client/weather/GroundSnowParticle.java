// Saltating grains and small powder wisps, constrained to the exposed snow surface.
package com.dark.winter.client.weather;

import com.dark.winter.weather.WeatherValues;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleLimit;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Optional;

/** Saltating grains and small powder wisps, constrained to the exposed snow surface. */
public final class GroundSnowParticle extends SingleQuadParticle {
    private static final ParticleLimit LIMIT = new ParticleLimit(700);
    private final float phase;
    private final float lift;
    private final float opacity;
    private final float spin;
    private final boolean powder;
    private final Vec3[] trail = new Vec3[5];
    private long lastTickTime;

    private GroundSnowParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        powder = random.nextFloat() < 0.72F;
        setSprite(sprites.get(powder ? random.nextInt(2) : 2 + random.nextInt(3), 4));
        setSize(0.025F, 0.025F);
        quadSize = powder ? 0.11F + random.nextFloat() * 0.075F : 0.018F + random.nextFloat() * 0.025F;
        lift = powder ? quadSize + 0.015F + random.nextFloat() * 0.035F : 0.045F + random.nextFloat() * 0.15F;
        phase = random.nextFloat() * Mth.TWO_PI;
        opacity = powder ? 0.34F + random.nextFloat() * 0.13F : 0.45F + random.nextFloat() * 0.25F;
        spin = (random.nextFloat() - 0.5F) * 0.035F;
        roll = oRoll = phase;
        lifetime = 45 + random.nextInt(55);
        friction = 1.0F;
        gravity = 0.0F;
        alpha = 0.0F;
        setColor(0.95F, 0.98F, 1.0F);
        lastTickTime = level.getGameTime();
        WeatherValues weather = WeatherEffects.weather();
        xd = weather.windX() * weather.windSpeed() * 0.24;
        zd = weather.windZ() * weather.windSpeed() * 0.24;
        double surface = WeatherEffects.snowSurface(level, x, z);
        if (Double.isFinite(surface)) {
            setPos(x, surface + lift, z);
            yo = this.y;
        } else {
            remove();
        }
        for (int i = 0; i < trail.length; i++) trail[i] = new Vec3(this.x, this.y, this.z);
    }

    @Override
    public void tick() {
        lastTickTime = level.getGameTime();
        float coverage = WeatherClient.groundSnow(1.0F);
        if (!WeatherClient.active() || coverage < 0.005F) {
            remove();
            return;
        }
        WeatherValues weather = WeatherEffects.weather();
        double gust = 0.88 + 0.14 * Math.sin(level.getGameTime() * 0.037 + x * 0.06)
                + 0.08 * Math.sin(level.getGameTime() * 0.081);
        double speed = weather.windSpeed() * 0.32 * gust;
        xd += (weather.windX() * speed - xd) * 0.16;
        zd += (weather.windZ() * speed - zd) * 0.16;
        double surface = WeatherEffects.snowSurface(level, x + xd, z + zd);
        // End a ribbon at cliffs, a wall, water, bare ground, and covered terrain.
        if (!Double.isFinite(surface) || Math.abs(surface + lift - y) > 0.55) {
            remove();
            return;
        }
        yd = surface + lift + 0.022 * Math.sin(age * 0.31 + phase) - y;
        oRoll = roll;
        roll += spin;
        double previousX = x, previousZ = z;
        for (int i = trail.length - 1; i > 0; i--) trail[i] = trail[i - 1];
        trail[0] = new Vec3(x, y, z);
        super.tick();
        if (onGround || !level.getFluidState(BlockPos.containing(x, y, z)).isEmpty()
                || (Math.abs(x - previousX) + Math.abs(z - previousZ) < 0.003 && speed > 0.03)) {
            remove();
        }
        double distance = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceToSqr(x, y, z);
        if (distance > 30.0 * 30.0) remove();
        alpha = opacity * coverage * Mth.clamp(age / 7.0F, 0.0F, 1.0F)
                * Mth.clamp((lifetime - age) / 18.0F, 0.0F, 1.0F)
                * Mth.clamp((float) (distance - 0.20) / 1.2F, 0.0F, 1.0F);
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        super.extract(state, camera, partialTick);
        if (!powder || age < 5) return;
        // Overlapping soft quads at real past positions form a low wind-aligned ribbon.
        // The saved path followed snow and collisions; no invented trail crosses walls or cliffs.
        Quaternionf rotation = new Quaternionf(camera.rotation());
        rotation.rotateZ(Mth.lerp(partialTick, oRoll, roll));
        Vec3 cameraPos = camera.position();
        float mainAlpha = alpha;
        for (int i = 1; i < trail.length - 1; i++) {
            Vec3 point = trail[i + 1].lerp(trail[i], partialTick);
            alpha = mainAlpha * (1.0F - i * 0.21F);
            extractRotatedQuad(state, rotation, (float) (point.x - cameraPos.x),
                    (float) (point.y - cameraPos.y), (float) (point.z - cameraPos.z), partialTick);
        }
        alpha = mainAlpha;
    }

    boolean discardedByEngine(long gameTime) {
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
            return new GroundSnowParticle(level, x, y, z, sprites);
        }
    }
}
