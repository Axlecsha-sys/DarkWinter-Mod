package com.dark.winter.client.mixin.client;

import com.dark.winter.client.DebugMenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.debug.DebugOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugOptionsScreen.class)
public abstract class DebugOptionsSkinMixin extends Screen {
    protected DebugOptionsSkinMixin(Component title) {super(title);}
    @Inject(method={"init","repositionElements"},at=@At("TAIL"))
    private void darkwinter$layout(CallbackInfo ci) {DebugMenuStyle.arrange(this);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        DebugMenuStyle.arrange(this);DebugMenuStyle.extract(g,this);super.extractRenderState(g,mx,my,dt);
    }
}
