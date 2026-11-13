package com.dark.winter.client;

import com.dark.winter.client.vector.MenuGlyph;
import com.dark.winter.client.vector.SvgHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Presentation around the original language list and its original search responder. */
public final class LanguageMenuStyle {
    private LanguageMenuStyle() {}
    private static AbstractSelectionList<?> list(Screen screen) {
        for(var child:screen.children()) if(child instanceof AbstractSelectionList<?> list)return list;
        return null;
    }
    public static void arrange(Screen screen) {
        int x=SettingsNavigation.contentLeft(screen),w=SettingsNavigation.contentWidth(screen);
        int listY=111,listH=Math.max(30,((screen.height-177)/26)*26+4),footerY=screen.height-28;
        var actions=new java.util.ArrayList<AbstractWidget>();
        AbstractWidget done=null;
        for(var child:screen.children()) {
            if(child instanceof StringWidget label) {
                if(label.getMessage().getString().equals(screen.getTitle().getString())) {label.visible=false;continue;}
                label.setMessage(HudStyle.strong(label.getMessage()).copy().withColor(HudStyle.MUTED));
                label.setMaxWidth(w-8);label.setWidth(w-8);
                label.setPosition(x+4,screen.height-55);
                label.setTooltip(Tooltip.create(label.getMessage()));
            } else if(child instanceof EditBox search) {
                search.visible=true;search.setPosition(x+20,72);search.setSize(w-20,22);
                search.setMessage(Component.translatable("gui.language.search"));
                search.setHint(HudStyle.strong(Component.translatable("gui.language.search")).copy().withColor(HudStyle.MUTED));
                search.setTextShadow(false);search.setTextColor(HudStyle.INK);
            } else if(child instanceof AbstractSelectionList<?> list) {
                list.visible=true;
                // Native resizing scrolls to the selection. Do not repeat it every frame.
                if(list.getX()!=x||list.getY()!=listY||list.getWidth()!=w||list.getHeight()!=listH) {
                    list.updateSizeAndPosition(w,listH,x,listY);
                    list.setScrollAmount(Math.floor(list.scrollAmount()/26)*26);
                }
            } else if(child instanceof Button button&&!SettingsNavigation.sidebar(button)) {
                if(MenuStyle.key(button,"gui.done"))done=button;else actions.add(button);
            }
        }
        if(done!=null)actions.add(done);
        int gap=8,n=actions.size(),buttonW=Math.min(160,Math.max(40,(w-gap*Math.max(0,n-1))/Math.max(1,n)));
        int start=x+w-n*buttonW-gap*Math.max(0,n-1);
        for(int i=0;i<n;i++){var action=actions.get(i);action.setPosition(start+i*(buttonW+gap),footerY);action.setSize(buttonW,20);}
    }
    public static void extract(GuiGraphicsExtractor g,Screen screen) {
        int x=SettingsNavigation.contentLeft(screen),w=SettingsNavigation.contentWidth(screen);
        SvgHud.menuIcon(g,MenuGlyph.SEARCH,x+1,77,12,HudStyle.MUTED);
        for(var child:screen.children())if(child instanceof EditBox search&&search.getValue().isEmpty()&&search.isFocused())
            HudStyle.fit(g,HudStyle.strong(Component.translatable("gui.language.search")),search.getX()+5,search.getY()+7,search.getWidth()-10,1f,HudStyle.alpha(HudStyle.MUTED,.65f));
        HudStyle.text(g,Component.translatable("menu.darkwinter.language.available"),x+5,100,.65f,HudStyle.MUTED);
        var list=list(screen);
        if(list==null)return;
        var count=Component.literal(Integer.toString(list.children().size()));
        HudStyle.text(g,count,x+w-8-HudStyle.width(count)*.65f,100,.65f,HudStyle.MUTED);
        if(list.children().isEmpty())HudStyle.centered(g,Component.translatable("menu.darkwinter.language.empty"),x+w/2,list.getY()+20,.85f,HudStyle.MUTED);
    }
    public static void entry(GuiGraphicsExtractor g,LayoutElement row,Component language,String code,int mx,int my,boolean hovered) {
        Screen screen=Minecraft.getInstance().gui.screen();var list=screen==null?null:list(screen);
        boolean selected=list!=null&&list.getSelected()==row;
        int x=row.getX(),y=row.getY(),w=row.getWidth();
        if(selected)SvgHud.menuIcon(g,MenuGlyph.CHECK,x+7,y+7,11,HudStyle.STABLE);
        else g.fill(x+11,y+12,x+13,y+14,0x476B8998);
        Component full=HudStyle.strong(language);
        Component fitted=MenuText.fit(full,Math.max(1,(int)((w-37)/.93f)));
        HudStyle.text(g,fitted,x+26,y+4,.93f,selected?HudStyle.INK:0xFFBED0D8);
        HudStyle.text(g,code,x+26,y+16,.60f,HudStyle.MUTED);
        if(hovered&&fitted!=full)g.setTooltipForNextFrame(full,mx,my);
    }
}
