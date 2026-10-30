// Adds DarkWinter branding and navigation to the native title screen.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.MenuStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleBrandingMixin extends Screen {
    protected TitleBrandingMixin(Component title) { super(title); }
    @Inject(method="init",at=@At("TAIL"))
    private void darkwinter$singleplayerMenu(CallbackInfo ci) {
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("menu.darkwinter.interface"),b->minecraft.gui.setScreen(new com.dark.winter.client.UiPreferencesScreen((Screen)(Object)this))).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Соединения"),
                b->com.dark.winter.client.online.FriendsScreen.open((Screen)(Object)this)).build());
        int left=MenuStyle.left(width),w=MenuStyle.rowWidth(width),step=MenuStyle.rowStep(height),small=0;
        int top=Math.max(65,Math.min(MenuStyle.menuTop(height),height-5*step-42));
        for(var child:new java.util.ArrayList<>(children())) if(child instanceof AbstractWidget widget) {
            String label=widget.getMessage().getString();
            if(widget instanceof FriendsButton || label.equals(Component.translatable("menu.multiplayer").getString())
                    || label.equals(Component.translatable("menu.online").getString()) || label.equals("TW")) {
                removeWidget(widget); continue;
            }
            int row=label.equals(Component.translatable("menu.singleplayer").getString())?0
                    :label.equals("Соединения")?1
                    :label.equals(Component.translatable("menu.options").getString())?2
                    :label.equals(Component.translatable("menu.darkwinter.interface").getString())?3
                    :label.equals(Component.translatable("menu.quit").getString())?4:-1;
            if(row>=0) {
                widget.setWidth(w);widget.setHeight(24);widget.setPosition(left,top+row*step);
                if(row==0) widget.setMessage(Component.translatable("menu.darkwinter.play"));
            } else if(widget.getWidth()==20) widget.setPosition(left+24*small++,top+5*step+6);
        }
    }

    @ModifyArg(method="extractRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),index=1)
    private String darkwinter$versionLabel(String original) { return "DARKWINTER"; }
    @Redirect(method="extractRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
    private void darkwinter$logo(LogoRenderer logo,GuiGraphicsExtractor g,int width,float opacity) {
        MenuStyle.title(g,width,((TitleScreen)(Object)this).height,opacity);
    }
    @Redirect(method="extractRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
    private void darkwinter$replaceDecorativeSplash(SplashRenderer splash,GuiGraphicsExtractor g,int width,Font font,float opacity) {
        // DarkWinter's fixed location/date strap replaces the decorative Minecraft splash only.
    }
}
