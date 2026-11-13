package com.winterkore.engine.render.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.winterkore.engine.render.WkeParticleReactiveRects;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the actual frustum-selected quad inputs while the host builds its real particle VBO. */
@Mixin(QuadParticleRenderState.class)
public abstract class WkeParticleReactiveQuadMixin {
    @Inject(method="renderRotatedQuad",at=@At("TAIL"))
    private void wke$capturePreparedParticle(VertexConsumer builder,float x,float y,float z,
            float qx,float qy,float qz,float qw,float scale,float u0,float u1,float v0,float v1,
            int color,int lightCoords,CallbackInfo ci) {
        WkeParticleReactiveRects.captureParticle(x,y,z,qx,qy,qz,qw,scale,color);
    }
}
