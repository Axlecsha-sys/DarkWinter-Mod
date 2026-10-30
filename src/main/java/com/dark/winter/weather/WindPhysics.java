// Bounded walking resistance and horizontal gusts, applied by the normal travel/collision path.
package com.dark.winter.weather;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.function.Function;

/** Bounded walking resistance and horizontal gusts, applied by the normal travel/collision path. */
public final class WindPhysics {
    private static Function<Player, ClimateSample> clientClimate = player -> null;
    private WindPhysics() { }

    /** Installed by the client entrypoint; common/server classes never load client classes. */
    public static void setClientClimate(Function<Player, ClimateSample> source) { clientClimate = source; }

    public static Vec3 travelInput(Player player, Vec3 input) {
        if (!Level.OVERWORLD.equals(player.level().dimension()) || !player.isAlive() || !player.canSimulateMovement()
                || player.isSpectator() || player.isCreative() || player.noPhysics || player.getAbilities().flying
                || player.isFallFlying() || player.isPassenger() || player.isInLiquid() || player.onClimbable()) return input;
        ClimateSample climate = player.level().isClientSide() ? clientClimate.apply(player)
                : player.level().getServer() == null ? null : WeatherServer.climate(player.level().getServer());
        // Shelter only reduces wind speed; at <= 3 m/s pressure is zero even fully exposed.
        if (climate == null || climate.windMetersPerSecond() <= 3) return input;
        float exposure = WindExposure.exposure(player, climate);
        double speed = climate.windMetersPerSecond() * exposure;
        double pressure = Math.clamp((speed - 3) / 23, 0, 1);
        pressure *= pressure;
        if (pressure < .00001) return input;

        double yaw = Math.toRadians(player.getYRot());
        double x = input.x * Math.cos(yaw) - input.z * Math.sin(yaw);
        double z = input.z * Math.cos(yaw) + input.x * Math.sin(yaw);
        double length = Math.hypot(x, z);
        double along = length < 1.0e-6 ? 0 : (x * climate.windX() + z * climate.windZ()) / length;
        // Walking into a storm is slower; a tailwind gives only a small assist.
        double scale = 1 - .42 * pressure * Math.max(0, -along) + .06 * pressure * Math.max(0, along);
        double time = player.level().getGameTime();
        double gust = .78 + .14 * Math.sin(time * .067) + .08 * Math.sin(time * .023 + .9);
        double bracing = player.isShiftKeyDown() ? .25 : 1;
        double push = .016 * pressure * gust * bracing * (player.onGround() ? 1 : .45);
        Vec3 motion = player.getDeltaMovement();
        // Do not accelerate an existing fall, knockback or ice slide beyond this modest drift.
        double downwind = motion.x * climate.windX() + motion.z * climate.windZ();
        if (downwind < .12) {
            player.setDeltaMovement(motion.add(climate.windX() * push, 0, climate.windZ() * push));
        }
        return new Vec3(input.x * scale, input.y, input.z * scale);
    }
}
