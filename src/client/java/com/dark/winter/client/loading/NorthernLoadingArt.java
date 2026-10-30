// Draws loading artwork after resources are available without changing measured progress.
package com.dark.winter.client.loading;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.UiArt;
import com.dark.winter.client.UiPreferences;
import com.dark.winter.client.MenuStyle;
import com.dark.winter.loading.WorldPreloadPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Low-cost post-resource-load artwork. Progress remains the original server/tracker value. */
public final class NorthernLoadingArt {
    private NorthernLoadingArt() {}
    public static void background(GuiGraphicsExtractor g,int width,int height) {
        UiArt.cover(g,UiArt.LOADING,0,0,width,height,.54f,.5f,0xFFFFFFFF);
        g.fillGradient(0,0,width,height*2/3,0x52020815,0x08020815);
        UiArt.cover(g,UiArt.AURORA,0,0,width,height,.54f,.5f,
                HudStyle.alpha(0xFFFFFFFF,UiPreferences.animated()?.90f+.06f*(float)Math.sin(UiPreferences.seconds()*.17):.94f));
        g.fillGradient(0,0,width,height/3,0x39020A14,0x08020A14);
        g.fillGradient(0,height*2/3,width,height,0x10030912,0xC1030912);
        MenuStyle.atmosphere(g,width,height,true);
        MenuStyle.analogScreen(g,width,height);
    }
    public static void foreground(GuiGraphicsExtractor g,Font font,int width,int height,WorldPreloadPayload progress,float displayedProgress,boolean hasVanillaProgress) {
        int barWidth=Math.min(330,width-48),left=(width-barWidth)/2;
        int stageY=Math.max(46,Math.min(height-98,Math.round(height*.57f))),barY=stageY+20;
        // No solid central box. Only a feathered reading area behind the measured progress.
        UiArt.shadow(g,width/2,barY,Math.max(42,barWidth*2/3),.63f);

        String stage=progress==null?"opening":switch(progress.stage()) {
            case LOADING->"chunks";case SAVING->"saving";case READY->"ready";case FAILED->"failed";case CANCELLED->"cancelled";
        };
        HudStyle.centered(g,Component.translatable("loading.darkwinter."+stage),width/2,stageY,.9f,HudStyle.INK);
        g.fill(left-1,barY-1,left+barWidth+1,barY+4,0x90537384);
        g.fill(left,barY,left+barWidth,barY+3,0xDD07111B);
        if(progress!=null||hasVanillaProgress) {
            int complete=Math.round(barWidth*Math.clamp(displayedProgress,0,1));
            if(complete>0) {
                HudStyle.halo(g,left,barY,complete,3,HudStyle.STABLE,.90f);
                g.fillGradient(left,barY,left+complete,barY+3,HudStyle.INK,0xFF668EA4);
                g.fill(left+complete-1,barY-1,left+complete,barY+4,HudStyle.INK);
            }
        } // Unknown progress remains empty, never a fake advancing percentage.
        if(progress!=null) {
            HudStyle.fit(g,Component.translatable("loading.darkwinter.count",progress.completed(),progress.total()),left,barY+12,barWidth-38,.83f,HudStyle.MUTED);
            String elapsed=elapsed(progress.elapsedMillis());
            HudStyle.text(g,elapsed,left+barWidth-HudStyle.width(Component.literal(elapsed))*.83f,barY+12,.83f,HudStyle.MUTED);
            if(progress.stage()==WorldPreloadPayload.Stage.FAILED) HudStyle.centered(g,Component.translatable("loading.darkwinter.failed_hint"),width/2,barY+27,.8f,HudStyle.AMBER);
        } else HudStyle.centered(g,Component.translatable("loading.darkwinter.opening_hint"),width/2,barY+12,.8f,HudStyle.MUTED);
        if(height>230) {
            int markW=Math.min(134,width/3),markX=width-markW-24;
            UiArt.wordmark(g,markX,height-74,markW,HudStyle.INK);
            HudStyle.fit(g,Component.translatable("loading.darkwinter.north"),markX,height-49,markW,.72f,HudStyle.MUTED);
        }
    }
    public static int buttonY(int height){return Math.max(0,height-28);}

    /** Other connections/dimension transfers use their own tracker, never a local preload packet. */
    public static void transferForeground(GuiGraphicsExtractor g,int width,int height,float progress,boolean known) {
        int y=Math.max(38,Math.min(height-70,Math.round(height*.57f)));
        int w=Math.max(1,Math.min(330,width-48));
        HudStyle.centered(g,Component.translatable("multiplayer.downloadingTerrain"),width/2,y,.9f,HudStyle.INK);
        progressLine(g,(width-w)/2,y+20,w,known?progress:0);
        if(known) HudStyle.centered(g,Component.translatable("loading.progress",Math.round(Math.clamp(progress,0,1)*100)),
                width/2,y+32,.8f,HudStyle.MUTED);
        if(height>230) UiArt.wordmark(g,width-Math.min(134,width/3)-24,height-74,Math.min(134,width/3),HudStyle.INK);
    }

    /** Shared measured progress styling; callers retain the meaning and lifecycle of their value. */
    public static void progressLine(GuiGraphicsExtractor g,int x,int y,int width,float progress) {
        if(width<=0) return;
        g.fill(x,y,x+width,y+3,0xDD07111B);
        g.fill(x,y+3,x+width,y+4,0x80537384);
        int complete=Math.round(width*Math.clamp(progress,0,1));
        if(complete>0) {
            g.fillGradient(x,y,x+complete,y+3,HudStyle.INK,0xFF668EA4);
            g.fill(x+complete-1,y-1,x+complete,y+4,HudStyle.INK);
        }
    }

    private static String elapsed(long millis){long s=Math.max(0,millis/1000);return "%d:%02d".formatted(s/60,s%60);}
}
