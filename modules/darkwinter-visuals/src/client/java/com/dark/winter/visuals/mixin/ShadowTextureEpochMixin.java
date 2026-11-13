// Conservatively invalidates shadow alpha coverage after each native texture-animation tick.
package com.dark.winter.visuals.mixin;
import com.dark.winter.visuals.ShadowCaptureCache;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TextureManager.class)
public abstract class ShadowTextureEpochMixin {
    @Inject(method="tick()V",at=@At("RETURN"),require=1,allow=1)
    private void darkwinter$atlasMayHaveChanged(CallbackInfo ci) {
        ShadowCaptureCache.texturesTicked();
    }
}
