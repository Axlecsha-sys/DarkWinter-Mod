// Keeps perspective-sized celestial billboards out of the isolated orthographic terrain view.
package com.dark.winter.biomeeditor.mixin;
import com.dark.winter.biomeeditor.BiomeEditorWorker;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SkyRenderer.class)
public abstract class EditorCelestialMixin {
    @Inject(method="renderSun",at=@At("HEAD"),cancellable=true,require=1)
    private void editor$hideSunBillboard(CallbackInfo ci){if(BiomeEditorWorker.orbitActive())ci.cancel();}
    @Inject(method="renderMoon",at=@At("HEAD"),cancellable=true,require=1)
    private void editor$hideMoonBillboard(CallbackInfo ci){if(BiomeEditorWorker.orbitActive())ci.cancel();}
}
