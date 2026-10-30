// Local environmental detail, driven by the same interpolated state as clouds and haze.
package com.dark.winter.client.weather;

import com.dark.winter.weather.WeatherParticles;
import com.dark.winter.weather.WeatherValues;
import com.dark.winter.weather.WindExposure;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Local environmental detail, driven by the same interpolated state as clouds and haze. */
public final class WeatherEffects {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final ArrayList<Particle> FLAKES = new ArrayList<>();
    private static final ArrayList<GroundSnowParticle> GROUND_SNOW = new ArrayList<>();
    private static final Map<UUID, BreathCycle> BREATHS = new HashMap<>();
    private static ClientLevel previousLevel;
    private static WeatherValues current;
    private static WeatherWindSound windSound;
    private static double snowAccumulator;
    private static double groundAccumulator;
    private static float localWindExposure = 1.0F;

    private WeatherEffects() { }

    public static void init() {
        ParticleProviderRegistry.getInstance().register(WeatherParticles.SNOW_FLAKE, SnowWeatherParticle.Provider::new);
        ParticleProviderRegistry.getInstance().register(WeatherParticles.GROUND_SNOW, GroundSnowParticle.Provider::new);
        ParticleProviderRegistry.getInstance().register(WeatherParticles.BREATH, BreathParticle.Provider::new);
        ClientTickEvents.END_CLIENT_TICK.register(WeatherEffects::tick);
    }

    static WeatherValues weather() {
        return current != null ? current : WeatherClient.sample(1.0F);
    }

    /** Live local weather particles, for the in-game verification harness. */
    public static int activeSnowCount() {
        return (int) FLAKES.stream().filter(Particle::isAlive).count();
    }

    public static int activeGroundSnowCount() {
        return (int) GROUND_SNOW.stream().filter(Particle::isAlive).count();
    }

    static float localWindExposure() {
        return localWindExposure;
    }

    private static void tick(Minecraft client) {
        if (previousLevel != client.level || !WeatherClient.active()) {
            FLAKES.forEach(Particle::remove);
            FLAKES.clear();
            GROUND_SNOW.forEach(Particle::remove);
            GROUND_SNOW.clear();
            BREATHS.clear();
            snowAccumulator = 0.0;
            groundAccumulator = 0.0;
            localWindExposure = 1.0F;
            current = null;
            previousLevel = client.level;
            if (windSound != null) {
                client.getSoundManager().stop(windSound);
                windSound = null;
            }
        }
        if (!WeatherClient.active() || client.level == null || client.player == null || client.isPaused()
                || !client.level.tickRateManager().runsNormally()) {
            return;
        }

        current = WeatherClient.sample(1.0F);
        if (client.level.getGameTime() % 10 == 0) {
            localWindExposure = (float) WindExposure.exposure(client.level, client.player.getEyePosition(),
                    new Vec3(current.windX(), 0.0, current.windZ()), client.player);
        }
        FLAKES.removeIf(particle -> {
            if (particle instanceof SnowWeatherParticle flake && flake.discardedByEngine(client.level.getGameTime())) {
                particle.remove();
            }
            return !particle.isAlive();
        });
        GROUND_SNOW.removeIf(particle -> {
            if (particle.discardedByEngine(client.level.getGameTime())) particle.remove();
            return !particle.isAlive();
        });
        ParticleStatus detail = client.options.particles().get();
        // The additional 800 particles belong only to the severe storm end of the blend.
        int stormExtra = Math.round(Mth.clamp((current.snowfall() - 1.0F) / 0.65F, 0.0F, 1.0F) * 800);
        int budget = switch (detail) {
            case ALL -> 2200 + stormExtra;
            case DECREASED -> 900 + stormExtra / 4;
            case MINIMAL -> 160;
        };
        while (FLAKES.size() > budget) {
            FLAKES.removeFirst().remove();
        }
        if(com.dark.winter.client.TestingAccess.enabled("precipitation")) {
            spawnSnow(client, detail, budget);spawnGroundSnow(client, detail);
        } else {
            FLAKES.forEach(Particle::remove);FLAKES.clear();
            GROUND_SNOW.forEach(Particle::remove);GROUND_SNOW.clear();
        }
        if(com.dark.winter.client.TestingAccess.enabled("atmosphere")) tickBreath(client, detail);
        else BREATHS.clear();
        if(!com.dark.winter.client.TestingAccess.enabled("wind") && windSound!=null) {
            client.getSoundManager().stop(windSound);windSound=null;
        }
        if (current.windSpeed() > 0.20F && (windSound == null || windSound.isStopped()
                || !client.getSoundManager().isActive(windSound))) {
            windSound = new WeatherWindSound(client.level);
            client.getSoundManager().play(windSound);
        }
    }

    private static void spawnSnow(Minecraft client, ParticleStatus detail, int budget) {
        double quality = switch (detail) {
            case ALL -> 1.0;
            case DECREASED -> 0.45;
            case MINIMAL -> 0.075;
        };
        // Nonlinear density keeps light snow occasional. Only the storm end gains a denser near field.
        double storm = Mth.clamp((current.snowfall() - 0.38) / 0.62, 0.0, 1.0);
        storm = storm * storm * (3.0 - 2.0 * storm);
        double amount = (26.0 + 16.0 * storm) * Math.pow(current.snowfall(), 1.5) * quality;
        snowAccumulator = Math.min(snowAccumulator + amount, 108.0);
        int attempts = Math.min((int) snowAccumulator, Math.max(0, budget - FLAKES.size()));
        snowAccumulator -= (int) snowAccumulator; // Never bank particles behind a roof or while the budget is full.
        Vec3 camera = client.gameRenderer.mainCamera().position();
        int radius = Mth.clamp(client.options.weatherRadius().get() + 7, 12, 22);
        ClientLevel level = client.level;
        for (int i = 0; i < attempts; i++) {
            boolean nearField = RANDOM.nextDouble() < 0.65 * storm;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = nearField ? 1.6 + RANDOM.nextDouble() * 9.4 : Math.sqrt(RANDOM.nextDouble()) * radius;
            double x = camera.x + Math.cos(angle) * distance;
            double z = camera.z + Math.sin(angle) * distance;
            double y = nearField ? camera.y - 1.5 + RANDOM.nextDouble() * 8.5
                    : camera.y - 3.0 + RANDOM.nextDouble() * 16.0;
            BlockPos pos = BlockPos.containing(x, y, z);
            if (camera.distanceToSqr(x, y, z) < 1.5 || !exposed(level, pos)
                    || client.player.getBoundingBox().inflate(0.35).contains(x, y, z)) {
                continue;
            }
            Particle particle = client.particleEngine.createParticle(WeatherParticles.SNOW_FLAKE, x, y, z, 0.0, 0.0, 0.0);
            if (particle != null) {
                FLAKES.add(particle);
            }
        }
    }

    /** MOTION_BLOCKING includes glass, leaves and fluids: skylight alone leaks snow through glass roofs. */
    static boolean exposed(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Returns the visible snow surface only when it is the top of a loaded, open column. */
    static double snowSurface(ClientLevel level, double x, double z) {
        int blockX = Mth.floor(x), blockZ = Mth.floor(z);
        if (!level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4)) return Double.NaN;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        // One snow layer can have an empty collision shape. Check the heightmap cell as well.
        for (int y = top; y >= top - 1; y--) {
            BlockPos pos = new BlockPos(blockX, y, blockZ);
            var state = level.getBlockState(pos);
            if (!level.getFluidState(pos).isEmpty()) return Double.NaN;
            if (state.is(Blocks.SNOW)) return y + state.getValue(SnowLayerBlock.LAYERS) / 8.0;
            if (state.is(Blocks.SNOW_BLOCK)) return y + 1.0;
        }
        return Double.NaN;
    }

    private static void spawnGroundSnow(Minecraft client, ParticleStatus detail) {
        int budget = switch (detail) {
            case ALL -> 700;
            case DECREASED -> 280;
            case MINIMAL -> 50;
        };
        while (GROUND_SNOW.size() > budget) GROUND_SNOW.removeFirst().remove();
        float strength = WeatherClient.groundSnow(1.0F);
        double quality = detail == ParticleStatus.ALL ? 1.0 : detail == ParticleStatus.DECREASED ? 0.4 : 0.07;
        groundAccumulator = Math.min(groundAccumulator + strength * Math.min(current.windSpeed(), 1.6F)
                * 25.0 * quality, 40.0);
        int attempts = Math.min((int) groundAccumulator, Math.max(0, budget - GROUND_SNOW.size()));
        groundAccumulator -= (int) groundAccumulator;
        Vec3 camera = client.gameRenderer.mainCamera().position();
        for (int i = 0; i < attempts; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            // Keep some ribbons close enough for their shallow shape to read from shoulder height.
            double distance = RANDOM.nextFloat() < 0.55F ? 1.5 + RANDOM.nextDouble() * 7.5
                    : 1.5 + Math.sqrt(RANDOM.nextDouble()) * 16.5;
            double x = camera.x + Math.cos(angle) * distance;
            double z = camera.z + Math.sin(angle) * distance;
            double surface = snowSurface(client.level, x, z);
            if (!Double.isFinite(surface) || Math.abs(surface - client.player.getY()) > 7.0) continue;
            Particle particle = client.particleEngine.createParticle(WeatherParticles.GROUND_SNOW,
                    x, surface + 0.16, z, 0.0, 0.0, 0.0);
            if (particle instanceof GroundSnowParticle ground) GROUND_SNOW.add(ground);
        }
    }

    private static void tickBreath(Minecraft client, ParticleStatus detail) {
        if (detail == ParticleStatus.MINIMAL) {
            BREATHS.clear();
            return;
        }
        Vec3 camera = client.gameRenderer.mainCamera().position();
        HashSet<UUID> nearby = new HashSet<>();
        int emissions = 0;
        for (AbstractClientPlayer player : client.level.players()) {
            if (!player.isAlive() || player.isSpectator() || player.isInvisible() || player.isUnderWater()
                    || player.isInLava() || player.isSleeping() || camera.distanceToSqr(player.position()) > 144.0
                    || (player == client.player && client.options.getCameraType().isFirstPerson())) {
                continue;
            }
            UUID id = player.getUUID();
            nearby.add(id);
            BreathCycle cycle = BREATHS.computeIfAbsent(id, ignored -> new BreathCycle(25 + RANDOM.nextInt(55)));
            if (cycle.burstTicks > 0) {
                cycle.burstTicks--;
                if ((cycle.burstTicks & 1) == 0 && emissions < 8) {
                    emitBreath(client, player);
                    emissions++;
                }
            } else if (--cycle.waitTicks <= 0) {
                cycle.burstTicks = detail == ParticleStatus.DECREASED ? 6 : 10;
                cycle.waitTicks = player.isSprinting() ? 55 + RANDOM.nextInt(35) : 85 + RANDOM.nextInt(60);
            }
        }
        BREATHS.keySet().retainAll(nearby);
    }

    private static void emitBreath(Minecraft client, AbstractClientPlayer player) {
        // Remote players can turn their head independently of their body's travel yaw.
        Vec3 direction = Entity.calculateViewVector(player.getXRot(), player.getYHeadRot());
        Vec3 up = Entity.calculateViewVector(player.getXRot() - 90.0F, player.getYHeadRot());
        Vec3 start = player.getEyePosition().add(up.scale(-0.10));
        Vec3 mouth = start.add(direction.scale(0.38));
        BlockPos pos = BlockPos.containing(mouth);
        if (!client.level.getFluidState(pos).isEmpty()
                || client.level.clip(new ClipContext(start, mouth, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, player)).getType() != HitResult.Type.MISS) {
            return;
        }
        // The old slow, faint puff dissolved behind the head in the shoulder camera.
        // A short outward jet clears the silhouette before soft expansion takes over.
        Vec3 motion = direction.scale(0.14);
        Particle particle = client.particleEngine.createParticle(WeatherParticles.BREATH,
                mouth.x + RANDOM.nextGaussian() * 0.012, mouth.y + RANDOM.nextGaussian() * 0.008,
                mouth.z + RANDOM.nextGaussian() * 0.012, motion.x, motion.y + 0.004, motion.z);
        if (particle instanceof BreathParticle breath) breath.emittedBy(player);
    }

    private static final class BreathCycle {
        int waitTicks;
        int burstTicks;

        BreathCycle(int waitTicks) {
            this.waitTicks = waitTicks;
        }
    }
}
