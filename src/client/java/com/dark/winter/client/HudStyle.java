// Defines the shared HUD palette, typography, and drawing helpers.
package com.dark.winter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** One UI palette, font and optical treatment. No world-render or gameplay state. */
public final class HudStyle {
    public static final int INK=0xFFDCE8EB,MUTED=0xFF93AEBB,LINE=0x90537384,PANEL=0x9012202B;
    public static final int STABLE=0xFFA9DAE7,AMBER=0xFFD09A59,CRITICAL=0xFFD05A60;
    private static final net.minecraft.network.chat.FontDescription FONT=new net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.fromNamespaceAndPath("darkwinter","ui"));
    private static final float TEXT_WIDTH=1.0f;
    private HudStyle() {}
    public static Component font(Component text){return text.copy().withStyle(s->s.withFont(FONT));}
    /** Hierarchy is size/contrast, not the engine's synthetic bold on a TTF. */
    public static Component strong(Component text){return font(text).copy().withStyle(style->style.withBold(false).withItalic(false));}
    public static int width(Component text){return Math.round(Minecraft.getInstance().font.width(font(text))*TEXT_WIDTH);}
    public static void well(GuiGraphicsExtractor g,int cx,int cy,int radius){UiArt.shadow(g,cx,cy,radius,1f);}
    public static void panel(GuiGraphicsExtractor g,int x,int y,int w,int h) {
        if(w<=0||h<=0) return;
        g.fillGradient(x,y,x+w,y+h,0x6D12232E,0xA507121C);
        g.fillGradient(x+1,y+1,x+w-1,y+Math.min(h/2,22),0x25395666,0x00395666);
        g.fill(x,y,x+1,y+h,0x99698493);g.fill(x,y,x+w,y+1,0x70537384);
        g.fill(x,y+h-1,x+w,y+h,0x70537384);g.fill(x+w-1,y,x+w,y+h,0x50537384);
        g.fill(x+1,y+1,x+2,y+h-1,0x70010910);
        g.fill(x,y,x+Math.min(18,w),y+1,0xCBC2D9E1);
        halo(g,x,y,w,h,STABLE,.18f);
        analog(g,x+2,y+2,w-4,h-4,false);
    }
    /** Quieter, thinner glass only for the in-game weather instrument.
     * Menu panels deliberately retain their own cinematic treatment. */
    public static void instrumentPanel(GuiGraphicsExtractor g,int x,int y,int w,int h) {
        if(w<=0||h<=0) return;
        g.fillGradient(x,y,x+w,y+h,0x6412202B,0x9007111B);
        g.fillGradient(x+1,y+1,x+w-1,y+Math.min(17,h/2),0x203C5163,0x003C5163);
        g.fill(x,y,x+1,y+h,0x77698493);
        g.fill(x,y,x+w,y+1,0x565B7687);
        g.fill(x+w-1,y,x+w,y+h,0x40537384);
        g.fill(x,y+h-1,x+w,y+h,0x45537384);
        g.fill(x,y,x+Math.min(15,w),y+1,0xA9C2D9E1);
        halo(g,x,y,w,h,STABLE,.075f);
        analog(g,x+2,y+2,w-4,h-4,false);
    }
    public static void analog(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean backdrop) {
        if(w<1||h<1||!UiPreferences.animated()) return;
        float strength=UiPreferences.strength();
        double t=UiPreferences.seconds();int frame=UiPreferences.full()?(int)(t*12):0;
        UiArt.grain(g,x,y,w,h,frame,(backdrop?.045f:.010f)*strength);
        if(backdrop) for(int row=y+1;row<y+h;row+=3) g.fill(x,row,x+w,row+1,alpha(0x1500050B,strength));
        // One soft band per ~17 seconds. No flicker/strobe of legible text or hit targets.
        double phase=(t+4.7)%17;
        if(UiPreferences.full()&&backdrop&&phase<.30) {
            int py=y+Math.min(h-2,(int)(phase/.30*Math.max(1,h-2)));
            g.fillGradient(x,py,x+w,Math.min(y+h,py+2),alpha(0x447FA7BB,strength),0x007FA7BB);
        }
    }
    public static void halo(GuiGraphicsExtractor g,int x,int y,int w,int h,int color,float strength) {
        if(w<=0||h<=0||strength<=0) return;
        UiArt.frameGlow(g,x,y,w,h,color,strength);
    }
    public static int alpha(int color,float opacity){return (Math.round((color>>>24)*Math.clamp(opacity,0,1))<<24)|(color&0xFFFFFF);}
    public static int mix(int a,int b,float fraction) {
        float f=Math.clamp(fraction,0,1);
        return 0xFF000000|(Math.round(((a>>16)&255)*(1-f)+((b>>16)&255)*f)<<16)
                |(Math.round(((a>>8)&255)*(1-f)+((b>>8)&255)*f)<<8)|Math.round((a&255)*(1-f)+(b&255)*f);
    }
    public static void text(GuiGraphicsExtractor g,Component text,float x,float y,float scale,int color) {
        if(scale<=0||(color>>>24)==0) return;
        g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(scale*TEXT_WIDTH,scale);
        g.text(Minecraft.getInstance().font,font(text),0,0,color,false);g.pose().popMatrix();
    }
    public static void text(GuiGraphicsExtractor g,String s,float x,float y,float scale,int color){text(g,Component.literal(s),x,y,scale,color);}
    public static void fit(GuiGraphicsExtractor g,Component text,float x,float y,int w,float scale,int color) {
        text(g,MenuText.fit(font(text),Math.max(0,(int)(w/(scale*TEXT_WIDTH)))),x,y,scale,color);
    }
    public static void centered(GuiGraphicsExtractor g,Component text,int center,int y,float scale,int color){text(g,text,center-width(text)*scale/2,y,scale,color);}
    public static void paragraph(GuiGraphicsExtractor g,Component text,int x,int y,int width,float scale,int color) {
        paragraph(g,text,x,y,width,scale,color,Integer.MAX_VALUE);
    }
    public static void paragraph(GuiGraphicsExtractor g,Component text,int x,int y,int width,float scale,int color,int maxLines) {
        if(width<=0||maxLines<=0) return;
        var f=Minecraft.getInstance().font;
        g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(scale*TEXT_WIDTH,scale);
        int lineY=0,count=0;
        for(var line:f.split(font(text),Math.max(1,(int)(width/(scale*TEXT_WIDTH))))) {
            if(count++>=maxLines) break;
            g.text(f,line,0,lineY,color,false);lineY+=12;
        }
        g.pose().popMatrix();
    }
    public static int logoWidth(float scale,float tracking) {
        return Math.round((88+8*tracking)*scale);
    }
    /** Rendered ten-letter wordmark: never distributes font binaries or an alphabet. */
    public static void logo(GuiGraphicsExtractor g,int x,int y,float size,float tracking,int maxWidth,int color) {
        int w=Math.min(maxWidth,logoWidth(size,tracking));
        if(w<=0) return;
        UiArt.wordmark(g,x,y,w,color);
        if(UiPreferences.full()&&(UiPreferences.seconds()+6.4)%19<.16) {
            // Optical echo, not a delayed click target or shifted line of live text.
            UiArt.wordmark(g,x+1,y,w,alpha(STABLE,.10f*UiPreferences.strength()));
        }
    }
}
