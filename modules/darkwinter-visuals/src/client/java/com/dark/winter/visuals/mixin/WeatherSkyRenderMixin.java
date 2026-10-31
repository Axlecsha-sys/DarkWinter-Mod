// Keeps native celestial geometry while assigning procedural weather its own pre-terrain sky background.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.render.WeatherSkyRenderer;
import com.dark.winter.visuals.vanillafog.VanillaFogRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkyRenderer.class)
public abstract class WeatherSkyRenderMixin {
    @WrapOperation(method="render",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/SkyRenderer;renderSunriseAndSunset(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/vertex/PoseStack;FLorg/joml/Vector4fc;)V"),require=1)
    private void darkwinter$weatherBackground(SkyRenderer renderer,RenderPass pass,PoseStack poses,
            float sunAngle,org.joml.Vector4fc sunriseColor,Operation<Void> original) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        if(frame!=null && WeatherSkyRenderer.active()) frame.weatherSky().renderBackground(pass);
        else original.call(renderer,pass,poses,sunAngle,sunriseColor);
    }

    @WrapOperation(method="render",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/SkyRenderer;renderDarkDisc(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),require=1)
    private void darkwinter$preserveWeatherBackground(SkyRenderer renderer,RenderPass pass,Operation<Void> original) {
        if(!WeatherSkyRenderer.active()) original.call(renderer,pass);
    }
}
