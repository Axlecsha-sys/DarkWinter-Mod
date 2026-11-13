package com.winterkore.engine.render.mixin;

import com.winterkore.engine.render.WkeParticleReactiveRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class WkeParticleReactiveFrameMixin {
    @Inject(method="renderLevel",at=@At("HEAD"))
    private void wke$beginReactiveParticles(CallbackInfo ci) {
        var game=(GameRenderer)(Object)this;
        var position=game.gameRenderState().levelRenderState.cameraRenderState.pos;
        WkeParticleReactiveRects.beginWorldFrame(Minecraft.getInstance().level,position.x,position.y,position.z);
    }
}
