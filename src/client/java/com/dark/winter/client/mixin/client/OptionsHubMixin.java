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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Repositions the original widgets. Their handlers, narration and option persistence stay vanilla. */
@Mixin(OptionsScreen.class)
public abstract class OptionsHubMixin extends Screen {
    @Unique private Button darkwinter$previous;
    @Unique private Button darkwinter$next;
    @Unique private int darkwinter$scroll;
    @Unique private int darkwinter$lastScroll;
    protected OptionsHubMixin(Component title) {super(title);}
    @Inject(method="init",at=@At("TAIL"))
    private void darkwinter$interface(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.translatable("menu.darkwinter.interface"),b->minecraft.gui.setScreen(new UiPreferencesScreen(this))).build());
        darkwinter$previous=addRenderableWidget(Button.builder(Component.literal("↑"),b->darkwinter$scroll(-1))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("menu.darkwinter.scroll_up"))).build());
        darkwinter$next=addRenderableWidget(Button.builder(Component.literal("↓"),b->darkwinter$scroll(1))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("menu.darkwinter.scroll_down"))).build());
        darkwinter$arrange();
    }
    @Inject(method="repositionElements",at=@At("TAIL"))
    private void darkwinter$layout(CallbackInfo ci) {darkwinter$arrange();}
    private void darkwinter$arrange() {
        var l=UiLayout.hub(width,height);
        String[] navKeys={"options.video","options.sounds","options.controls","menu.darkwinter.interface","options.accessibility","options.language"};
        var other=new java.util.ArrayList<AbstractWidget>();
        for(var child:children()) if(child instanceof AbstractWidget widget) {
            if(widget==darkwinter$previous||widget==darkwinter$next) continue;
            if(widget instanceof StringWidget) {widget.visible=false;continue;}
            if(MenuStyle.key(widget,"gui.done")) {widget.setPosition(width-130,height-28);widget.setWidth(110);widget.setHeight(20);continue;}
            int index=-1;
            for(int i=0;i<navKeys.length;i++) if(MenuStyle.key(widget,navKeys[i])) index=i;
            if(index>=0) {
                widget.setPosition(l.left(),l.top()+index*l.step());
                widget.setWidth(l.navWidth());
                widget.setHeight(Math.max(16,l.step()-3));
            } else {
                other.add(widget);
            }
        }
        int rows=Math.max(1,(height-44-70+5)/26);
        darkwinter$lastScroll=Math.max(0,other.size()-rows);
        darkwinter$scroll=Math.clamp(darkwinter$scroll,0,darkwinter$lastScroll);
        for(int i=0;i<other.size();i++) {
            var widget=other.get(i);
            widget.setPosition(l.right()+6,70+(i-darkwinter$scroll)*26);
            widget.setWidth(Math.max(1,l.contentWidth()-12));
            widget.setHeight(21);
            widget.visible=i>=darkwinter$scroll&&i<darkwinter$scroll+rows;
            if(!widget.visible&&getFocused()==widget)setFocused(darkwinter$scroll>0?darkwinter$previous:darkwinter$next);
        }
        if(darkwinter$previous!=null&&darkwinter$next!=null) {
            darkwinter$previous.setPosition(l.right()+l.contentWidth()-48,50);
            darkwinter$next.setPosition(l.right()+l.contentWidth()-26,50);
            for(var arrow:java.util.List.of(darkwinter$previous,darkwinter$next)) {
                arrow.setWidth(20);arrow.setHeight(18);arrow.visible=darkwinter$lastScroll>0;
            }
            darkwinter$previous.active=darkwinter$scroll>0;
            darkwinter$next.active=darkwinter$scroll<darkwinter$lastScroll;
        }
    }
    @Unique private void darkwinter$scroll(int direction) {
        darkwinter$scroll=Math.clamp(darkwinter$scroll+direction,0,darkwinter$lastScroll);
        darkwinter$arrange();
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        var l=UiLayout.hub(width,height);
        if(darkwinter$lastScroll>0&&x>=l.right()-4&&x<l.right()+l.contentWidth()+4&&y>=48&&y<height-40&&vertical!=0) {
            darkwinter$scroll(vertical>0?-1:1);return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        var l=UiLayout.hub(width,height);
        HudStyle.text(g,Component.translatable("options.title"),l.left(),18,1.42f,HudStyle.INK);
        g.fill(l.left(),39,width-l.left(),40,0x90537384);
        HudStyle.text(g,Component.translatable("menu.darkwinter.categories"),l.left(),46,.73f,HudStyle.MUTED);
        HudStyle.panel(g,l.right()-4,48,l.contentWidth()+8,Math.max(60,height-90));
        HudStyle.fit(g,Component.translatable("menu.darkwinter.common"),l.right()+6,54,
                l.contentWidth()-(darkwinter$lastScroll>0?58:12),.75f,HudStyle.MUTED);
        super.extractRenderState(g,mx,my,dt);
    }
}
