// Shared cinematic presentation; original widgets retain their handlers and hit rectangles.
package com.dark.winter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;

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
    public static void landscape(GuiGraphicsExtractor g,int width,int height) {
        double t=UiPreferences.seconds();
        // UV motion inside a crop: no shifting edges or half-pixel movement of widgets.
        float focus=UiPreferences.full()?.5f+.04f*(float)Math.sin(t*.035):.5f;
        Screen active=Minecraft.getInstance().gui.screen();
        boolean forest=active instanceof WorldModeScreen||active instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
        UiArt.cover(g,forest?UiArt.STORY:UiArt.LANDSCAPE,0,0,width,height,forest?.46f:focus,.5f,forest?0xFFA5B4C1:0xFFFFFFFF);
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
        for(int y=h*2/3;y<h;y+=3) {
            int alpha=Math.min(100,(y-h*2/3)*2);
            g.fill(0,y,w,Math.min(h,y+3),(alpha<<24)|0x030A12);
        }
        g.fill(12,12,w-12,13,0x60537384);g.fill(12,h-14,w-12,h-13,0x40537384);
    }
    public static void worldBackdrop(GuiGraphicsExtractor g,int w,int h) {
        // Chat, inventories and in-world forms keep the actual world visible.
        g.fillGradient(0,0,w,h,0x270B1723,0x69040D16);
    }
    public static void analogScreen(GuiGraphicsExtractor g,int width,int height) {
        HudStyle.analog(g,1,1,width-2,height-2,true);
        g.fill(3,3,width-3,4,0x50537384);g.fill(3,height-4,width-3,height-3,0x40537384);
    }
    public static void title(GuiGraphicsExtractor g,int width,int height,float opacity) {
        int x=left(width),y=Math.max(14,Math.round(height*.125f));
        int wordmark=Math.min(width-x-22,Math.round(width*.465f));
        UiArt.wordmark(g,x,y,wordmark,HudStyle.alpha(HudStyle.INK,opacity));
        if(UiPreferences.full()&&(UiPreferences.seconds()+6.4)%19<.16)
            UiArt.wordmark(g,x+1,y,wordmark,HudStyle.alpha(HudStyle.STABLE,.12f*opacity*UiPreferences.strength()));
        int subY=y+wordmark/8+8;
        HudStyle.fit(g,Component.literal("N O R T H E R N  C A N A D A  /  1 9 9 7"),x,subY,wordmark,.74f,HudStyle.alpha(HudStyle.MUTED,opacity));
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
        int x=left(sw),w=rowWidth(sw);
        for(int i=0;i<20;i++) {
            int l=Math.max(0,x-18)+i*(w+75)/20,r=Math.max(0,x-18)+(i+1)*(w+75)/20;
            float weight=1-i/20f;
            g.fill(l,0,r,sh,HudStyle.alpha(0xCB030A13,weight*weight));
        }
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
        int ink=HudStyle.mix(HudStyle.MUTED,HudStyle.INK,(primary?.90f:.63f)+lit*(primary?.10f:.37f));
        g.fillGradient(x,y,x+w,y+h,HudStyle.alpha(0xFF203747,alpha*(.07f+lit*.34f)),HudStyle.alpha(0x50050D17,alpha));
        float edgeAmount=(primary?.72f:.035f)+lit*.77f;
        int edge=HudStyle.alpha(HudStyle.STABLE,edgeAmount*alpha);
        g.fill(x,y,x+w,y+1,edge);g.fill(x,y+h-1,x+w,y+h,edge);
        if(primary||lit>.01f) {
            g.fill(x,y,x+1,y+h,edge);g.fill(x+w-1,y,x+w,y+h,edge);
            HudStyle.halo(g,x,y,w,h,HudStyle.STABLE,alpha*((primary?.55f:.06f)+lit*.48f));
        }
        if(lit>.01f) {g.fill(x-4,y+5,x-2,y+h-5,HudStyle.alpha(HudStyle.INK,lit*alpha));HudStyle.halo(g,x-4,y+5,2,h-10,HudStyle.STABLE,lit*alpha*.6f);}
        int icon=key(widget,"menu.options")?7:key(widget,"menu.darkwinter.interface")?10
                :key(widget,"menu.quit")||key(widget,"menu.returnToMenu")?8:key(widget,"gui.stats")?9:-1;
        if(icon==10) UiArt.utilityIcon(g,2,x+6,y+(h-14)/2,14,HudStyle.alpha(ink,alpha));
        else if(icon>=0) UiArt.icon(g,icon,x+6,y+(h-14)/2,14,HudStyle.alpha(ink,alpha));
        else HudStyle.text(g,"›",x+9,y+(h-12)/2f,1.3f,HudStyle.alpha(ink,alpha));
        HudStyle.fit(g,HudStyle.strong(widget.getMessage()),x+28,y+(h-9)/2f,w-38,1.06f,HudStyle.alpha(ink,alpha));
        if(!primary&&lit<.8f) g.fill(x+28,y+h-1,x+w-10,y+h,HudStyle.alpha(0x55537384,alpha));
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
            g.fillGradient(x,y,x+w,y+h,HudStyle.alpha(0xFF2A4353,alpha*(.08f+lit*.38f)),0x2007111B);
            g.fill(x+5,y+h-1,x+w,y+h,HudStyle.alpha(HudStyle.LINE,alpha*(.5f+lit*.5f)));
            g.fill(x,y+3,x+2,y+h-3,HudStyle.alpha(accent,lit*alpha));return;
        }
        int edge=HudStyle.alpha(HudStyle.mix(HudStyle.LINE,accent,lit),alpha*(widget.active?.48f+lit*.48f:.22f));
        g.fillGradient(x,y,x+w,y+h,HudStyle.alpha(0xFF192D3C,alpha*(.40f+lit*.32f)),HudStyle.alpha(0xA006101B,alpha));
        g.fill(x,y,x+w,y+1,edge);g.fill(x,y+h-1,x+w,y+h,edge);
        g.fill(x,y,x+1,y+h,edge);g.fill(x+w-1,y,x+w,y+h,edge);
        g.fillGradient(x+1,y+1,x+w-1,y+Math.max(2,h/2),HudStyle.alpha(0x28547387,alpha*(.4f+lit)),0x00547387);
        if(primary||danger) g.fill(x,y+h-1,x+w,y+h,HudStyle.alpha(accent,alpha*(primary?.75f:.5f)));
        HudStyle.halo(g,x,y,w,h,accent,lit*alpha*.86f);
        if(lit>.05f&&widget.active) g.fill(x+3,y+4,x+4,y+h-4,HudStyle.alpha(HudStyle.INK,lit*alpha));
        if(key(widget,"menu.darkwinter.continue")&&w>140) {
            int ax=x+w-18,ay=y+h/2;
            for(int i=0;i<4;i++) {g.fill(ax+i,ay-3+i,ax+i+1,ay-2+i,HudStyle.INK);g.fill(ax+i,ay+3-i,ax+i+1,ay+4-i,HudStyle.INK);}
        }
    }
    public static void listSelection(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean focused) {
        g.fillGradient(x,y,x+w,y+h,0xDF1E3544,0xE206101A);
        g.fillGradient(x+2,y+1,x+w-2,y+Math.min(h/2,16),0x284D768D,0x004D768D);
        int c=focused?HudStyle.STABLE:0xFF85ACBE;
        g.fill(x,y,x+w,y+1,c);g.fill(x,y+h-1,x+w,y+h,c);g.fill(x,y,x+1,y+h,c);g.fill(x+w-1,y,x+w,y+h,c);
        g.fill(x+3,y+5,x+4,y+h-5,HudStyle.INK);HudStyle.halo(g,x,y,w,h,c,focused?.76f:.52f);
    }
}
