// Skip completely clipped GUI draws before the native render pass rejects a zero-sized scissor.
package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3's ScissorStack uses ScreenRectangle.empty() for disjoint clips,
 * but GuiRenderer.enableScissor forwards their zero dimensions to RenderPass.
 * Skip the whole clipped draw, including its drawIndexed call. Never unclip it or
 * replace the empty region with a visible pixel. Non-empty draws stay native.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiEmptyScissorMixin {
    @Inject(method = "executeDraw", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/render/GuiRenderer;enableScissor(Lnet/minecraft/client/gui/navigation/ScreenRectangle;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            cancellable = true)
    private void darkwinter$skipEmptyScissor(CallbackInfo ci, @Local ScreenRectangle area) {
        var window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
        // Use the same frame snapshot and integer GUI-to-pixel conversion as enableScissor,
        // not the live Window, which can already have changed during a resize.
        int scale = window.guiScale;
        int left = area.left() * scale;
        int top = area.top() * scale;
        int right = Math.min(area.right() * scale, window.width);
        int bottom = Math.min(area.bottom() * scale, window.height);
        if (right <= left || bottom <= top) ci.cancel();
    }
}
