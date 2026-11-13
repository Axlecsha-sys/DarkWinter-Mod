// Applies WKE's shared cutout mip policy while leaving other host material strategies intact.
package com.winterkore.engine.material.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import com.winterkore.engine.material.WkeMaterialMips;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MipmapGenerator.class)
public abstract class WkeMaterialMipmapMixin {
    @Inject(method = "generateMipLevels", at = @At("HEAD"), cancellable = true, require = 1)
    private static void wke$materialMips(Identifier name, NativeImage[] current, int requested,
            MipmapStrategy strategy, float alphaBias, Transparency transparency,
            CallbackInfoReturnable<NativeImage[]> cir) {
        NativeImage[] prepared = WkeMaterialMips.generate(name, current, requested, strategy, alphaBias, transparency);
        if (prepared != null) cir.setReturnValue(prepared);
    }
}
