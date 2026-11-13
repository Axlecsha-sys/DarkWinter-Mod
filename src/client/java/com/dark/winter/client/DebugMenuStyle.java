package com.dark.winter.client;

import com.dark.winter.client.vector.MenuGlyph;
import com.dark.winter.client.vector.SvgHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The F3 configuration screen retains its native profiles, status buttons and permission checks. */
public final class DebugMenuStyle {
    private DebugMenuStyle() {}
    public static Component entryName(String code) {
        return Component.translatableWithFallback("menu.darkwinter.debug.entry."+code,code);
    }
    public static Component statusCaption(AbstractWidget button) {
        String suffix=MenuStyle.key(button,"debug.entry.always")?"always":MenuStyle.key(button,"debug.entry.overlay")?"overlay":"off";
        return Component.translatable("menu.darkwinter.debug.status."+suffix);
    }
    public static void arrange(Screen screen) {
        int x=Math.max(18,Math.min(34,screen.width/22)),w=screen.width-2*x;
        int searchY=77;
        for(var child:screen.children())if(child instanceof MultiLineTextWidget warning) {
            warning.setMessage(HudStyle.strong(Component.translatable("menu.darkwinter.debug.warning")).copy().withColor(HudStyle.MUTED));
            warning.setTooltip(net.minecraft.client.gui.components.Tooltip.create(HudStyle.font(Component.translatable("debug.options.warning"))));
            warning.setMaxWidth(w-20).setCentered(false);warning.setPosition(x+8,54);
            searchY=Math.max(searchY,54+warning.getHeight()+10);
        }
        int listY=searchY+32;
        var footer=new java.util.ArrayList<AbstractWidget>();
        for(var child:screen.children()) {
            if(child instanceof StringWidget title)title.visible=false;
            else if(child instanceof MultiLineTextWidget)continue;
            else if(child instanceof EditBox search) {
                search.setPosition(x+26,searchY);search.setSize(w-34,22);search.setTextShadow(false);search.setTextColor(HudStyle.INK);
                search.setHint(HudStyle.strong(Component.translatable("debug.options.search")).copy().withColor(HudStyle.MUTED));
            } else if(child instanceof AbstractSelectionList<?> list) {
                int h=Math.max(30,((screen.height-listY-55)/26)*26+4);
                if(list.getX()!=x+4||list.getY()!=listY||list.getWidth()!=w-8||list.getHeight()!=h)
                    list.updateSizeAndPosition(w-8,h,x+4,listY);
            } else if(child instanceof AbstractWidget widget)footer.add(widget);
        }
        int n=footer.size(),gap=6,bw=(w-gap*Math.max(0,n-1))/Math.max(1,n);
        for(int i=0;i<n;i++){var button=footer.get(i);button.setPosition(x+i*(bw+gap),screen.height-28);button.setSize(bw,20);}
    }
    public static void extract(GuiGraphicsExtractor g,Screen screen) {
        int x=Math.max(18,Math.min(34,screen.width/22)),w=screen.width-2*x;
        SvgHud.menuIcon(g,MenuGlyph.VIDEO,x,18,16,HudStyle.MUTED);
        HudStyle.text(g,Component.translatable("debug.options.title"),x+24,18,1.3f,HudStyle.INK);
        HudStyle.text(g,"F3",x+w-18,22,.72f,HudStyle.STABLE);
        g.fill(x,39,x+w,40,HudStyle.LINE);
        HudStyle.panel(g,x-4,48,w+8,Math.max(60,screen.height-90));
        for(var child:screen.children())if(child instanceof EditBox search) {
            SvgHud.menuIcon(g,MenuGlyph.SEARCH,x+8,search.getY()+5,12,HudStyle.MUTED);
            if(search.getValue().isEmpty()&&search.isFocused())
                HudStyle.fit(g,HudStyle.strong(Component.translatable("debug.options.search")),search.getX()+5,search.getY()+7,search.getWidth()-10,1f,HudStyle.alpha(HudStyle.MUTED,.65f));
        }
    }
}
