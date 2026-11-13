// Retains native outline colours and line types while submitting supported model-aligned edges.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.highlight.ModelHighlight;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ModelHighlightRenderMixin {
    @Inject(method="submitHitOutline",at=@At("HEAD"),cancellable=true)
    private void darkwinter$submitModelOutline(PoseStack poses,SubmitNodeCollector collector,RenderType type,
            BlockOutlineRenderState state,int color,float width,boolean afterTerrain,CallbackInfo ci){
        if(ModelHighlight.submit(poses,collector,type,state,color,width,afterTerrain))ci.cancel();
    }
}
