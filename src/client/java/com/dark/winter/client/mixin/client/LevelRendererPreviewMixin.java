// Submits editor placement previews through the active native gizmo collector.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.WallPlacementPreviewRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kept under the old class name to match dark-winter.client.mixins.json.
 * Minecraft.tick() runs inside the per-tick GizmoCollector in Minecraft 26.3.
 */
@Mixin(Minecraft.class)
public abstract class LevelRendererPreviewMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void darkwinter$renderWallPreview(CallbackInfo ci) {
        WallPlacementPreviewRenderer.onClientTick((Minecraft) (Object) this);
    }
}
