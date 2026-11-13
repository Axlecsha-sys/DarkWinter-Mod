// World GPU scope is bounded by real execution calls; 2D HUD and menus remain outside.
package com.winterkore.engine.render.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.winterkore.engine.render.WkeRendererRuntime;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class WkeWorldScopeMixin {
    @WrapMethod(method = "renderLevel")
    private void wke$renderWorld(Operation<Void> original) {
        WkeRendererRuntime.enterWorld();
        try { original.call(); }
        finally { WkeRendererRuntime.leaveWorld(); }
    }

    @WrapMethod(method = "applyPostEffects")
    private void wke$worldPostEffects(Operation<Void> original) {
        WkeRendererRuntime.enterWorld();
        try { original.call(); }
        finally { WkeRendererRuntime.leaveWorld(); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;blitEntityOutline()V"), require = 1)
    private void wke$worldOutline(LevelRenderer renderer, Operation<Void> original) {
        WkeRendererRuntime.enterWorld();
        try { original.call(renderer); }
        finally { WkeRendererRuntime.leaveWorld(); }
    }
}
