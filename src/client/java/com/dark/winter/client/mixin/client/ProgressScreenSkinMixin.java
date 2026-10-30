// Save/copy progress presentation; stop and clear-screen behavior remain with ProgressScreen.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.loading.NorthernLoadingArt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Save/copy progress presentation; stop and clear-screen behavior remain with ProgressScreen. */
@Mixin(ProgressScreen.class)
public abstract class ProgressScreenSkinMixin extends Screen {
    @Shadow private Component header;
    @Shadow private Component stage;
    @Shadow private int progress;
    @Shadow private boolean stop;

    protected ProgressScreenSkinMixin(Component title) { super(title); }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void darkwinter$progress(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick,CallbackInfo ci) {
        // The original branch must still remove the screen after a completed operation.
        if(this.stop) return;
        super.extractRenderState(g,mouseX,mouseY,partialTick);
        int y=Math.max(30,Math.min(this.height-80,Math.round(this.height*.42f)));
        int width=Math.max(1,Math.min(300,this.width-48));
        if(this.header!=null) HudStyle.centered(g,this.header,this.width/2,y,.95f,HudStyle.INK);
        if(this.stage!=null) HudStyle.centered(g,this.stage,this.width/2,y+22,.83f,HudStyle.MUTED);
        NorthernLoadingArt.progressLine(g,(this.width-width)/2,y+43,width,this.progress/100f);
        // Zero is also the unknown-progress initial value, so do not invent a percentage for it.
        if(this.progress>0) HudStyle.centered(g,Component.literal(Math.clamp(this.progress,0,100)+"%"),
                this.width/2,y+54,.8f,HudStyle.MUTED);
        ci.cancel();
    }
}
