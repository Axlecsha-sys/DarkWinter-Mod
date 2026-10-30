// Applies astronomical sky illumination while retaining native block light.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.SolarDaylight;
import com.dark.winter.visuals.SolarEphemeris;
import com.dark.winter.visuals.SolarRuntime;
import com.dark.winter.client.weather.WeatherClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Override sky illumination only. Block light (torches, fireplaces) stays vanilla. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class AstronomicalLightmapMixin {
    @Inject(method="extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V",at=@At("TAIL"),require=1)
    private void darkwinter$geographicSkyLight(LightmapRenderState state,float partialTicks,CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) return;
        // Lightmap extraction occurs earlier than SkyRenderer extraction in Minecraft 26.3.
        // Capture now, rather than accidentally using yesterday's/previous frame's sun.
        SolarRuntime.capture(level,partialTicks);
        SolarEphemeris.Position sun = SolarRuntime.current(level);
        if (sun == null) return;
        // Vanilla only refreshes these values on a game tick. Multiplying the previous
        // renderState every frame compounds twilight/weather darkness at higher FPS.
        // Re-read precisely the same source used by the installed 26.3 extractor.
        float baseline=client.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR,partialTicks);
        float weather=1f;
        if (WeatherClient.active()) {
            float visibility=WeatherClient.sample(partialTicks).sunVisibility();
            // Diffuse skylight survives dense clouds: overcast 1, snow .775, blizzard .55.
            weather=.5f+.5f*Math.clamp(visibility,0f,1.1f);
        }
        state.skyFactor = Math.clamp(baseline*SolarDaylight.illumination((float) sun.elevationDegrees())*weather,0f,1f);
        state.needsUpdate = true; // Rebuild each frame while the sun moves.
    }
}
