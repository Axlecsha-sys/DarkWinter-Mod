// Renders the sky using matching geographic solar and lunar motion.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.LunarEphemeris;
import com.dark.winter.visuals.SolarDaylight;
import com.dark.winter.visuals.VisualConfig;
import com.dark.winter.visuals.VisualTime;
import com.dark.winter.visuals.SolarEphemeris;
import com.dark.winter.visuals.SolarRuntime;
import com.dark.winter.client.weather.WeatherClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Geographic sky with matching solar and lunar motion; visual only. */
@Mixin(SkyRenderer.class)
public abstract class GeographicSunSkyMixin {

    @Unique private LunarEphemeris.Position darkwinter$lunarCurrent;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void darkwinter$astronomicalSky(ClientLevel level, float partialTicks, Camera camera,
                                              SkyRenderState state, CallbackInfo ci) {
        SolarRuntime.capture(level, partialTicks);
        // This is a visual-only clock. Do not modify vanilla world time or server phases.
        if (SolarRuntime.current(level) != null) {
            var geography=SolarRuntime.geography();
            double clock=VisualTime.clockTicks(level,partialTicks);
            var epoch=com.dark.winter.client.CalendarClient.modern();
            if (epoch!=null) {
                var date=epoch.at(level.getDefaultClockTime()+(double)partialTicks).date();
                darkwinter$lunarCurrent=LunarEphemeris.calculateCalendar(clock,geography.latitude(),geography.longitude(),
                        geography.utcOffsetHours(),geography.northRotation(),date);
            } else {
                darkwinter$lunarCurrent=LunarEphemeris.calculate(clock,geography.latitude(),geography.longitude(),
                        geography.utcOffsetHours(),geography.northRotation(),geography.calendarStartDay(),geography.calendarDaysPerMinecraftDay());
            }
        } else {
            darkwinter$lunarCurrent = null;
        }
        SolarEphemeris.Position position = SolarRuntime.current(level);
        if (position == null || state.skyColor == null) return;

        float elevation = (float) position.elevationDegrees();
        float daylight = SolarDaylight.illumination(elevation);
        // Custom cloud/fog passes occlude the discs themselves. Vanilla rain must
        // not independently hide them while the server selects clear weather.
        if (WeatherClient.active()) state.rainBrightness = 1.0f;
        float weather = Math.max(0.0f, Math.min(1.0f, state.rainBrightness));
        // Consistent cold sky throughout the year, including summer midnight daylight.
        // Does not write back to biome or world data.
        float cloudDim = 0.65f + 0.35f * weather;
        float red   = (0.009f * (1.0f - daylight) + 0.39f * daylight) * cloudDim;
        float green = (0.015f * (1.0f - daylight) + 0.50f * daylight) * cloudDim;
        float blue  = (0.031f * (1.0f - daylight) + 0.65f * daylight) * cloudDim;
        state.skyColor = new Vector3f(red, green, blue);
        state.starBrightness = SolarDaylight.stars(elevation) * weather * 0.82f;
        // Vanilla sunset fan is tied to its old 12/12-hour clock and would fire at wrong times.
        // Suppress it rather than display a false dawn in the middle of polar day/night.
        state.sunriseAndSunsetColor = new Vector4f(0f, 0f, 0f, 0f);
    }

    // The sun, moon and stars each have a pushed PoseStack frame. Only the SUN frame is changed.
    @ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSun(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V"),
        index = 2, require = 1)
    private PoseStack darkwinter$geographicSunDirection(PoseStack stack) {
        SolarEphemeris.Position position = SolarRuntime.current(Minecraft.getInstance().level);
        if (position == null) return stack;
        Quaternionf rotation = new Quaternionf().rotationTo(0f, 1f, 0f,
            (float) position.x(), (float) position.y(), (float) position.z());
        stack.last().pose().identity().rotate(rotation);
        return stack;
    }

    @ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSun(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V"),
        index = 1, require = 1)
    private float darkwinter$sunVisibleAboveHorizon(float rainBrightness) {
        SolarEphemeris.Position position = SolarRuntime.current(Minecraft.getInstance().level);
        if (position == null) return rainBrightness;
        return rainBrightness * SolarDaylight.sunDiscVisibility((float) position.elevationDegrees());
    }

    @ModifyArg(method = "renderSun", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;applyCelestialBodyTransform(Lcom/mojang/blaze3d/vertex/PoseStack;FF)Lorg/joml/Matrix4f;"),
        index = 2, require = 1)
    private float darkwinter$realisticSunDiameter(float vanillaSize) {
        SolarEphemeris.Position position = SolarRuntime.current(Minecraft.getInstance().level);
        if (position == null) return vanillaSize;
        // Vanilla quad is [-1..1], 100 units away. Half-size = distance*tan(0.53deg/2).
        return SolarDaylight.sunHalfSizeAtDistance100()*VisualConfig.shared().sunSizeMultiplier;
    }

    @ModifyArg(method = "renderSun", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
        index = 1, require = 1)
    private Vector4f darkwinter$sunDiscBrightness(Vector4f color) {
        if (SolarRuntime.current(Minecraft.getInstance().level) == null) return color;
        float brightness = VisualConfig.shared().sunDiscBrightness;
        return new Vector4f(color.x * brightness, color.y * brightness, color.z * brightness, color.w);
    }

    // Each body has its own pushed PoseStack; change only the Moon's copy.
    @ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V"),
        index = 3, require = 1)
    private PoseStack darkwinter$geographicMoonDirection(PoseStack stack) {
        LunarEphemeris.Position moon = darkwinter$lunarCurrent;
        if (moon == null) return stack;
        Quaternionf rotation = new Quaternionf().rotationTo(0f, 1f, 0f,
            (float)moon.x(), (float)moon.y(), (float)moon.z());
        stack.last().pose().identity().rotate(rotation);
        return stack;
    }

    @ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V"),
        index = 1, require = 1)
    private MoonPhase darkwinter$geographicMoonPhase(MoonPhase vanillaPhase) {
        LunarEphemeris.Position moon = darkwinter$lunarCurrent;
        return moon == null ? vanillaPhase : MoonPhase.values()[moon.phaseIndex()];
    }

    @ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V"),
        index = 2, require = 1)
    private float darkwinter$moonHorizonVisibility(float rainBrightness) {
        LunarEphemeris.Position moon = darkwinter$lunarCurrent;
        return moon == null ? rainBrightness
                : rainBrightness * LunarEphemeris.visibility(moon.elevationDegrees());
    }

    // The Sun is now ~0.53 degrees. At roughly the same distance, the Moon
    // should have a comparable angular diameter, not the vanilla giant square.
    @ModifyArg(method = "renderMoon", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SkyRenderer;applyCelestialBodyTransform(Lcom/mojang/blaze3d/vertex/PoseStack;FF)Lorg/joml/Matrix4f;"),
        index = 2, require = 1)
    private float darkwinter$realisticMoonDiameter(float vanillaSize) {
        return darkwinter$lunarCurrent == null ? vanillaSize
                : LunarEphemeris.moonHalfSizeAtDistance100()*VisualConfig.shared().moonSizeMultiplier;
    }

}
