// Redirects the existing sky draw into its fog background while preserving geographic and underwater sky hooks.
package com.dark.winter.visuals.mixin.vanillafog;

import com.dark.winter.visuals.vanillafog.VanillaFogRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SkyRenderer.class)
public abstract class VanillaFogSkyTargetMixin {
    @Shadow @Final private RenderTarget renderTarget;

    @Redirect(method="render",at=@At(value="FIELD",
            target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"),require=2,allow=2)
    private RenderTarget darkwinter$skyBackgroundTarget(SkyRenderer renderer) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        return frame==null?renderTarget:frame.skyTarget();
    }
}
