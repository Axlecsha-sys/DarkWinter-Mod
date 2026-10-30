// Arranges native option widgets within the shared settings hub.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import com.dark.winter.client.UiLayout;
import com.dark.winter.client.UiPreferencesScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Repositions the original widgets. Their handlers, narration and option persistence stay vanilla. */
@Mixin(OptionsScreen.class)
public abstract class OptionsHubMixin extends Screen {
    protected OptionsHubMixin(Component title) {super(title);}
    @Inject(method="init",at=@At("TAIL"))
    private void darkwinter$interface(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.translatable("menu.darkwinter.interface"),b->minecraft.gui.setScreen(new UiPreferencesScreen(this))).build());
        darkwinter$arrange();
    }
    @Inject(method="repositionElements",at=@At("TAIL"))
    private void darkwinter$layout(CallbackInfo ci) {darkwinter$arrange();}
    private void darkwinter$arrange() {
        var l=UiLayout.hub(width,height);
        String[] navKeys={"options.video","options.sounds","options.controls","menu.darkwinter.interface","options.accessibility","options.language"};
        int other=0;
        for(var child:children()) if(child instanceof AbstractWidget widget) {
            if(widget instanceof StringWidget) {widget.visible=false;continue;}
            if(MenuStyle.key(widget,"gui.done")) {widget.setPosition(width-130,height-28);widget.setWidth(110);widget.setHeight(20);continue;}
            int index=-1;
            for(int i=0;i<navKeys.length;i++) if(MenuStyle.key(widget,navKeys[i])) index=i;
            if(index>=0) {
                widget.setPosition(l.left(),l.top()+index*l.step());
                widget.setWidth(l.navWidth());
                widget.setHeight(Math.max(16,l.step()-3));
            } else {
                widget.setPosition(l.right()+6,70+other*26);
                widget.setWidth(Math.max(90,l.contentWidth()-12));
                widget.setHeight(21);
                other++;
            }
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        var l=UiLayout.hub(width,height);
        HudStyle.text(g,Component.translatable("options.title"),l.left(),18,1.42f,HudStyle.INK);
        g.fill(l.left(),39,width-l.left(),40,0x90537384);
        HudStyle.text(g,Component.translatable("menu.darkwinter.categories"),l.left(),46,.73f,HudStyle.MUTED);
        HudStyle.panel(g,l.right()-4,48,l.contentWidth()+8,Math.max(60,height-90));
        HudStyle.fit(g,Component.translatable("menu.darkwinter.common"),l.right()+6,54,l.contentWidth()-12,.75f,HudStyle.MUTED);
        super.extractRenderState(g,mx,my,dt);
    }
}
