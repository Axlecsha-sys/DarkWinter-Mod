// Shared cinematic presentation; original widgets retain their handlers and hit rectangles.
package com.dark.winter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import com.dark.winter.client.vector.MenuGlyph;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;

/** Shared cinematic presentation; original widgets retain their handlers and hit rectangles. */
public final class MenuStyle {
    private MenuStyle() {}
    public static boolean themed(Screen screen) {
        if(screen==null) return false;
        String n=screen.getClass().getName();
        // The old menu whitelist left whole native screens (credits, stats, death,
        // dialogs and containers) with unrelated buttons. Only presentation is shared;
        // each screen retains its own handlers, hit rectangles and content.
        return n.startsWith("net.minecraft.") || n.startsWith("com.mojang.realmsclient.") || n.startsWith("com.dark.winter.");
    }
    public static boolean earlyMessage(Screen screen) {
        if(screen==null) return false;
        String n=screen.getClass().getSimpleName();
        return n.equals("GenericMessageScreen")||n.equals("GenericWaitingScreen")||n.equals("ProgressScreen");
    }
    public static boolean rows(Screen screen){return screen instanceof TitleScreen||screen instanceof PauseScreen&&Minecraft.getInstance().isLocalServer();}
    public static int left(int width){return UiLayout.menuLeft(width);}
    public static int rowWidth(int width){return UiLayout.menuWidth(width);}
    public static int rowStep(int height){return UiLayout.menuStep(height);}
    public static int menuTop(int height){return UiLayout.menuTop(height);}
    public static int titleMenuTop(int height){
        int step=rowStep(height);
        return Math.max(65,Math.min(menuTop(height),height-5*step-42));
    }
    public static void landscape(GuiGraphicsExtractor g,int width,int height) {
        double t=UiPreferences.seconds();
        // UV motion inside a crop: no shifting edges or half-pixel movement of widgets.
        float focus=UiPreferences.full()?.5f+.04f*(float)Math.sin(t*.035):.5f;
        Screen active=Minecraft.getInstance().gui.screen();
        boolean forest=active instanceof WorldModeScreen||active instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
        UiArt.cover(g,forest?UiArt.STORY:UiArt.LANDSCAPE,0,0,width,height,forest?.46f:focus,.5f,forest?0xFFA5B4C1:0xFFFFFFFF);
        if(active instanceof TitleScreen)
            sideVeil(g,Math.min(width,left(width)+rowWidth(width)+76),height,0x66030A12);
        atmosphere(g,width,height,false);
        if(!(Minecraft.getInstance().gui.screen() instanceof TitleScreen)) g.fill(0,0,width,height,forest?0x8A030A13:0x59040D16);
        g.fillGradient(0,0,width,height/3,0x45030910,0x00030910);
        g.fillGradient(0,height*3/4,width,height,0x00030910,0x94030910);
        analogScreen(g,width,height);
    }
    public static void atmosphere(GuiGraphicsExtractor g,int width,int height,boolean loading) {
        if(!UiPreferences.mist()||!UiPreferences.animated()) return;
        double t=UiPreferences.seconds();float strength=UiPreferences.strength();
        for(int bank=0;bank<3;bank++) {
            int w=Math.round(width*(.74f+bank*.12f)),h=Math.max(36,Math.round(height*(.12f+bank*.025f)));
            int x=Math.round(width*.52f-w*.5f+(float)Math.sin(t*(.019+bank*.007)+bank*2.1)*width*.13f);
            int y=Math.round(height*(.50f+bank*.15f)+(float)Math.sin(t*.032+bank)*height*.018f)-h/2;
            UiArt.mist(g,x,y,w,h,(loading?.17f:.13f)*strength);
        }
    }
    public static void earlyBackdrop(GuiGraphicsExtractor g,int w,int h) {
        // Does not access custom images/fonts during resource preparation.
        g.fillGradient(0,0,w,h,0xFF08131E,0xFF1A2A36);
        g.fillGradient(0,h*2/3,w,h,0x00030A12,0x64030A12);
    }
    public static void worldBackdrop(GuiGraphicsExtractor g,int w,int h) {
        // Chat, inventories and in-world forms keep the actual world visible.
        g.fillGradient(0,0,w,h,0x270B1723,0x69040D16);
    }
    public static void analogScreen(GuiGraphicsExtractor g,int width,int height) {
        HudStyle.analog(g,0,0,width,height,true);
    }
    /** Four continuous GPU gradients retain the quadratic veil without visible strip boundaries. */
    private static void sideVeil(GuiGraphicsExtractor g,int width,int height,int color) {
        if(width<=0||height<=0)return;
        g.pose().pushMatrix();
        try {
            // At -90 degrees local (x,y) becomes (y,-x): local Y is screen X.
            g.pose().rotate(-(float)(Math.PI*.5));
            for(int span=0;span<4;span++) {
                int left=span*width/4,right=(span+1)*width/4;
                if(right<=left)continue;
                float start=1-left/(float)width,end=1-right/(float)width;
                g.fillGradient(-height,left,0,right,
                        HudStyle.alpha(color,start*start),HudStyle.alpha(color,end*end));
            }
        } finally { g.pose().popMatrix(); }
    }
    public static void title(GuiGraphicsExtractor g,int width,int height,float opacity) {
        int x=left(width),y=Math.max(14,Math.round(height*.125f));
        int wordmark=Math.min(width-x-22,Math.round(width*.465f));
        // Short ultrawide viewports need the full wordmark and strap above the first row.
        y=Math.max(8,Math.min(y,titleMenuTop(height)-wordmark/8-22));
        UiArt.wordmark(g,x,y,wordmark,HudStyle.alpha(HudStyle.INK,opacity));
        if(UiPreferences.full()&&(UiPreferences.seconds()+6.4)%19<.16)
            UiArt.wordmark(g,x+1,y,wordmark,HudStyle.alpha(HudStyle.STABLE,.12f*opacity*UiPreferences.strength()));
        int subY=y+wordmark/8+8;
        HudStyle.fit(g,Component.translatableWithFallback("menu.darkwinter.location_year","СЕВЕРНАЯ КАНАДА / 1997"),x,subY,wordmark,.74f,HudStyle.alpha(HudStyle.MUTED,opacity));
        if(width>=580) {
            String[] words={"north","survival","isolation","year"};
            for(int i=0;i<words.length;i++) HudStyle.text(g,Component.translatable("menu.darkwinter.signal."+words[i]),width-80,24+i*10,.57f,HudStyle.alpha(HudStyle.MUTED,.48f*opacity));
        }
    }
    public static void pause(GuiGraphicsExtractor g,Screen screen) {
        int sw=screen.width,sh=screen.height;
        if(UiPreferences.pauseBackdrop()==UiPreferences.PauseBackdrop.SHELTER) {
            // Explicit menu-art option, not a representation of the player's current location.
            UiArt.cover(g,UiArt.SHELTER,0,0,sw,sh,.65f,.5f,0xFFFFFFFF);
            g.fillGradient(0,0,sw,sh,0x25081420,0x3602070D);
        } else g.fillGradient(0,0,sw,sh,0x42091420,0x79030912);
        int x=UiLayout.pauseLeft(sw),w=rowWidth(sw);
        sideVeil(g,Math.min(sw,x+w+57),sh,0xCB030A13);
        int top=UiLayout.pauseTop(sh);
        g.fill(x-8,top-8,x-7,Math.min(sh-16,top+174),0x70537384);
        HudStyle.logo(g,x+12,Math.max(16,Math.round(sh*.085f)),2.0f,2.6f,Math.min(w-12,250),HudStyle.INK);
        analogScreen(g,sw,sh);
    }
    public static boolean key(AbstractWidget w,String key){return w.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t&&key.equals(t.getKey());}
    public static void action(GuiGraphicsExtractor g,AbstractWidget widget,float alpha,float light) {
        int x=widget.getX(),y=widget.getY(),w=widget.getWidth(),h=widget.getHeight();
        boolean primary=key(widget,"menu.returnToGame")||key(widget,"menu.darkwinter.play");
        float lit=widget.active?Math.clamp(light,0,1):0;
        int ink=HudStyle.mix(HudStyle.MUTED,HudStyle.INK,primary?.94f:.72f+lit*.28f);
        SvgHud.surface(g,x,y,w,h,!widget.active?MenuSurface.DISABLED:primary?MenuSurface.PRIMARY:MenuSurface.ACTION,lit,HudStyle.alpha(HudStyle.STABLE,alpha));
        MenuGlyph glyph=primary?MenuGlyph.PLAY:key(widget,"menu.options")?MenuGlyph.SETTINGS
            :key(widget,"menu.darkwinter.interface")?MenuGlyph.INTERFACE
            :key(widget,"menu.quit")||key(widget,"menu.returnToMenu")?MenuGlyph.EXIT
            :key(widget,"menu.darkwinter.connections.short")?MenuGlyph.CONNECTIONS
            :key(widget,"gui.advancements")?MenuGlyph.STORY:MenuGlyph.INFO;
        float visibility=alpha*(widget.active?1:.48f);
        SvgHud.menuIcon(g,glyph,x+7,y+(h-14)/2,14,HudStyle.alpha(ink,visibility));
        HudStyle.fit(g,HudStyle.strong(widget.getMessage()),x+28,y+(h-9)/2f,w-38,1f,HudStyle.alpha(ink,visibility));
        if(widget.isFocused()&&widget.active)g.fill(x+2,y+6,x+3,y+h-6,HudStyle.alpha(HudStyle.INK,alpha));
    }
    public static void button(GuiGraphicsExtractor g,AbstractWidget widget,float alpha,float light) {
        if(rows(Minecraft.getInstance().gui.screen())&&widget.getWidth()>60){action(g,widget,alpha,light);return;}
        int x=widget.getX(),y=widget.getY(),w=widget.getWidth(),h=widget.getHeight();
        float lit=widget.active?Math.clamp(light,0,1):0;
        boolean primary=key(widget,"selectWorld.select")||key(widget,"selectWorld.create")||key(widget,"menu.darkwinter.continue");
        boolean danger=key(widget,"selectWorld.delete");int accent=danger?HudStyle.CRITICAL:HudStyle.STABLE;
        if(primary&&widget.active) lit=.38f+.62f*lit;
        if(SettingsNavigation.sidebar(widget)) {
            if(widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key
                    && SettingsNavigation.selected(Minecraft.getInstance().gui.screen(),key.getKey())) lit=Math.max(lit,.82f);
            SvgHud.surface(g,x,y,w,h,MenuSurface.SIDEBAR,lit,HudStyle.alpha(accent,alpha));
            if(lit>.01f)g.fill(x,y+4,x+1,y+h-4,HudStyle.alpha(accent,lit*alpha));
            var glyph=SettingsNavigation.glyph(widget);
            if(glyph!=null)SvgHud.menuIcon(g,glyph,x+7,y+(h-13)/2,13,HudStyle.alpha(lit>.5f?HudStyle.INK:HudStyle.MUTED,alpha));
            return;
        }
        SvgHud.surface(g,x,y,w,h,!widget.active?MenuSurface.DISABLED:primary?MenuSurface.PRIMARY:MenuSurface.BUTTON,lit,HudStyle.alpha(accent,alpha));
        if(widget.isFocused()&&widget.active)g.fill(x+3,y+5,x+4,y+h-5,HudStyle.alpha(HudStyle.INK,alpha));
        if(key(widget,"menu.darkwinter.continue")&&w>140) {
            int ax=x+w-18,ay=y+h/2;
            for(int i=0;i<4;i++) {g.fill(ax+i,ay-3+i,ax+i+1,ay-2+i,HudStyle.INK);g.fill(ax+i,ay+3-i,ax+i+1,ay+4-i,HudStyle.INK);}
        }
    }
    public static void listSelection(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean focused) {
        int c=focused?HudStyle.STABLE:0xFF85ACBE;
        SvgHud.surface(g,x,y,w,h,MenuSurface.SELECTED,focused?1:.25f,c);
        g.fill(x+3,y+5,x+4,y+h-5,HudStyle.INK);
    }
}
