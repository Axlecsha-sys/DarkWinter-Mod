// Styles the native advancement canvas while preserving progress and navigation.
package com.dark.winter.client.mixin.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scoped to the advancement canvas. Progress, connectors, icons and dragging stay native. */
@Mixin(AdvancementTab.class)
public abstract class AdvancementCanvasSkinMixin {
    @Shadow private double scrollX;
    @Shadow private double scrollY;

    @Inject(method="extractContents",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;enableScissor(IIII)V",shift=At.Shift.AFTER))
    private void darkwinter$canvas(GuiGraphicsExtractor g,int left,int top,CallbackInfo ci) {
        g.fillGradient(left,top,left+234,top+113,0xFF102432,0xFF07141F);
        int dx=Math.floorMod((int)Math.floor(scrollX),32),dy=Math.floorMod((int)Math.floor(scrollY),32);
        for(int x=left+dx;x<left+234;x+=32)g.fill(x,top,x+1,top+113,0x294D6A7D);
        for(int y=top+dy;y<top+113;y+=32)g.fill(left,y,left+234,y+1,0x294D6A7D);
    }

    @Redirect(method="extractContents",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private void darkwinter$replaceBlockTiles(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier texture,int x,int y,float u,float v,int w,int h,int tw,int th) {
        // The method's only blit site is its 16-pixel block-texture tiling loop.
        // A single scissored canvas above replaces it; no world texture is changed.
    }
}
