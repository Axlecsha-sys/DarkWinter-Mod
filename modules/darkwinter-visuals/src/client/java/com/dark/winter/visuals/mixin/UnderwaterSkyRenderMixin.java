// Applies underwater horizon occlusion after all geographic celestial bodies in the existing sky pass.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.render.UnderwaterSkyOccluder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkyRenderer.class)
public abstract class UnderwaterSkyRenderMixin {
    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSunMoonAndStars(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/vertex/PoseStack;FFFLnet/minecraft/world/level/MoonPhase;FF)V"
        ),
        require = 1
    )
    private void darkwinter$occludeWaterSky(SkyRenderer renderer, RenderPass pass, PoseStack poseStack,
            float sunAngle, float moonAngle, float starAngle, MoonPhase moonPhase,
            float rainBrightness, float starBrightness, Operation<Void> original,
            @Local(argsOnly = true) SkyRenderState state) {
        original.call(renderer, pass, poseStack, sunAngle, moonAngle, starAngle, moonPhase, rainBrightness, starBrightness);
        UnderwaterSkyOccluder.render(pass, state);
    }

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;renderDarkDisc(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
        ),
        require = 1
    )
    private void darkwinter$preserveWaterFogBelowHorizon(SkyRenderer renderer, RenderPass pass,
            Operation<Void> original, @Local(argsOnly = true) SkyRenderState state) {
        // Vanilla tests the player's immersion, while a detached camera can be underwater on its own.
        if (!UnderwaterSkyOccluder.isActive(state)) original.call(renderer, pass);
    }
}
