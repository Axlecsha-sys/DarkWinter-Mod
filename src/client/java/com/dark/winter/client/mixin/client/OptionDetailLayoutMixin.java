// Keeps the shared section navigation and native content in separate GUI regions.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import com.dark.winter.client.SettingsNavigation;
import com.dark.winter.client.UiLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the shared section navigation and native content in separate GUI regions.
 * Native list entry mixins move their real widgets along with the clipping rectangle.
 */
@Mixin(Screen.class)
public abstract class OptionDetailLayoutMixin {
    @Shadow public int width;
    @Shadow public int height;
    @Shadow public abstract Component getTitle();
    @Shadow public abstract List<? extends GuiEventListener> children();
    @Shadow protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    @Unique private Button darkwinter$languageToggle;
    @Unique private boolean darkwinter$languageExpanded=false;

    @Inject(method="init(II)V",at=@At("TAIL"))
    private void darkwinter$init(int screenWidth,int screenHeight,CallbackInfo ci) {
        Screen self=(Screen)(Object)this;
        if(!darkwinter$detail(self)) return;
        SettingsNavigation.ensure(self,b->addRenderableWidget(b));
        if(darkwinter$language(self) && (darkwinter$languageToggle==null || !children().contains(darkwinter$languageToggle))) {
            darkwinter$languageToggle=addRenderableWidget(Button.builder(darkwinter$languageLabel(),b-> {
                darkwinter$languageExpanded=!darkwinter$languageExpanded;
                darkwinter$languageToggle.setMessage(darkwinter$languageLabel());
                darkwinter$arrange(self);
            }).build());
        }
        darkwinter$arrange(self);
    }

    @Inject(method="resize(II)V",at=@At("TAIL"))
    private void darkwinter$reposition(int screenWidth,int screenHeight,CallbackInfo ci) {
        Screen self=(Screen)(Object)this;
        if(darkwinter$detail(self)) {SettingsNavigation.ensure(self,b->addRenderableWidget(b));darkwinter$arrange(self);}
    }

    @Inject(method="rebuildWidgets",at=@At("TAIL"))
    private void darkwinter$rebuilt(CallbackInfo ci) {
        Screen self=(Screen)(Object)this;
        if(!darkwinter$detail(self)) return;
        // Reuse the same initialization path after a resource/GUI rebuild.
        darkwinter$init(width,height,ci);
    }

    @Inject(method="extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",at=@At("HEAD"))
    private void darkwinter$panel(GuiGraphicsExtractor g,int mx,int my,float dt,CallbackInfo ci) {
        Screen self=(Screen)(Object)this;
        if(!darkwinter$detail(self)) return;
        SettingsNavigation.frame(g,self);
    }

    @Unique
    private void darkwinter$arrange(Screen self) {
        int contentW=SettingsNavigation.contentWidth(self),contentX=SettingsNavigation.contentLeft(self),contentY=72,footerY=height-28;
        AbstractWidget done=null,search=null,list=null;
        var footer=new ArrayList<AbstractWidget>();
        var movable=new ArrayList<AbstractWidget>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE;
        for(var child:children()) if(child instanceof AbstractWidget widget) {
            if(widget==darkwinter$languageToggle) continue;
            if(SettingsNavigation.sidebar(widget)) continue;
            if(widget instanceof StringWidget) {widget.visible=false;continue;}
            if(MenuStyle.key(widget,"gui.done")) {done=widget;continue;}
            if(darkwinter$language(self) && widget instanceof EditBox field) {search=field;continue;}
            if(darkwinter$language(self) && widget instanceof AbstractSelectionList) {list=widget;continue;}
            if(darkwinter$language(self) && widget instanceof Button) {footer.add(widget);continue;}
            if(widget instanceof AbstractSelectionList<?> selection) {
                if(widget instanceof net.minecraft.client.gui.components.OptionsList options) SettingsNavigation.rowHeights(options,self);
                if(widget instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsList keys) SettingsNavigation.keyRowHeights(keys,self);
                selection.updateSizeAndPosition(contentW+8,Math.max(40,footerY-contentY-10),contentX-4,contentY);
                continue;
            }
            // Accessibility's help link and similar native footer actions must not
            // be translated downward together with the options body.
            if(widget instanceof Button && widget.getY()>=height-64) {footer.add(widget);continue;}
            movable.add(widget);
            minX=Math.min(minX,widget.getX());
            minY=Math.min(minY,widget.getY());
        }
        if(darkwinter$language(self)) {
            darkwinter$layoutLanguage(contentX,contentY,contentW,footerY,search,list,footer,done);
            return;
        }
        if(minX!=Integer.MAX_VALUE) {
            int dx=contentX-minX,dy=contentY-minY;
            for(var widget:movable) {
                widget.setPosition(widget.getX()+dx,widget.getY()+dy);
                int overflow=widget.getX()+widget.getWidth()-(contentX+contentW);
                if(overflow>0) widget.setWidth(Math.max(80,widget.getWidth()-overflow));
            }
        }
        darkwinter$footer(footer,done,footerY,contentX,contentW);
    }

    @Unique
    private void darkwinter$layoutLanguage(int contentX,int contentY,int contentW,int footerY,AbstractWidget search,AbstractWidget list,List<AbstractWidget> footer,AbstractWidget done) {
        if(darkwinter$languageToggle!=null) {
            darkwinter$languageToggle.setMessage(darkwinter$languageLabel());
            darkwinter$languageToggle.setPosition(contentX,contentY);
            darkwinter$languageToggle.setWidth(contentW);
            darkwinter$languageToggle.setHeight(20);
        }
        int bodyY=contentY+26;
        if(search!=null) {
            search.visible=darkwinter$languageExpanded;
            search.setPosition(contentX,bodyY);
            search.setWidth(contentW);
            search.setHeight(20);
            if(darkwinter$languageExpanded) bodyY+=26;
        }
        if(list!=null) {
            list.visible=darkwinter$languageExpanded;
            ((AbstractSelectionList<?>)list).updateSizeAndPosition(contentW,Math.max(40,footerY-bodyY-10),contentX,bodyY);
        }
        if(search!=null && !search.visible && ((Screen)(Object)this).getFocused()==search)
            ((Screen)(Object)this).setFocused(darkwinter$languageToggle);
        darkwinter$footer(footer,done,footerY,contentX,contentW);
    }

    @Unique private void darkwinter$footer(List<AbstractWidget> footer,AbstractWidget done,int y,int contentX,int contentW) {
        var actions=new ArrayList<>(footer);
        if(done!=null)actions.add(done);
        if(actions.isEmpty())return;
        int gap=8,count=actions.size();
        int buttonW=Math.min(count==1?110:170,Math.max(40,(contentW-gap*(count-1))/count));
        int x=contentX+contentW-(count*buttonW+gap*(count-1));
        for(int i=0;i<count;i++) {
            var action=actions.get(i);
            action.setPosition(x+i*(buttonW+gap),y);action.setWidth(buttonW);action.setHeight(20);
        }
    }

    @Unique private boolean darkwinter$detail(Screen self) {
        return SettingsNavigation.applies(self);
    }
    @Unique private boolean darkwinter$language(Screen self) {return self.getClass().getSimpleName().contains("Language");}
    @Unique private Component darkwinter$languageLabel() {
        return Component.literal(darkwinter$languageExpanded?"▼ ":"▶ ").append(Component.translatable("options.language"));
    }
}
