// Draws SVG/SDF instruments and shared illustrations using their actual dimensions.
package com.dark.winter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import com.dark.winter.client.vector.SvgHud;
import com.dark.winter.client.vector.MenuGlyph;

/** Shared assets. All crops use the actual texture dimensions; no warped backgrounds. */
public final class UiArt {
    public static final Identifier LANDSCAPE=id("northern_lake"),STORY=id("story_cabin"),FREE=id("free_valley");
    private static final Identifier WORDMARK=id("wordmark");
    public static final Identifier AURORA=id("aurora_veil");
    private static final Identifier WORDMARK_GLOW=id("wordmark_glow");
    public static final Identifier SHELTER=id("pause_shelter"),LOADING=id("loading_north"),MIST=id("mist_bank"),FILM=id("optical_grain");
    private UiArt() {}
    private static Identifier id(String name){return Identifier.fromNamespaceAndPath("darkwinter","textures/gui/cinematic/"+name+".png");}
    public static int textureWidth(Identifier image) {
        if(image.equals(LANDSCAPE)) return 1672;
        if(image.equals(SHELTER)||image.equals(LOADING)||image.equals(AURORA)) return 1600;
        return 1536;
    }
    public static int textureHeight(Identifier image) {
        if(image.equals(LANDSCAPE)) return 941;
        if(image.equals(SHELTER)||image.equals(LOADING)||image.equals(AURORA)) return 900;
        return 1024;
    }
    public static void illustration(GuiGraphicsExtractor g,Identifier image,int x,int y,int w,int h,float light) {
        cover(g,image,x,y,w,h,.5f,.5f,0xFF000000|gray(Math.round(207+48*Math.clamp(light,0,1))));
    }
    private static int gray(int v){return (v<<16)|(v<<8)|v;}
    public static void cover(GuiGraphicsExtractor g,Identifier image,int x,int y,int w,int h,float focalX,float focalY,int color) {
        coverZoom(g,image,x,y,w,h,focalX,focalY,color,1f);
    }
    public static void coverZoom(GuiGraphicsExtractor g,Identifier image,int x,int y,int w,int h,float focalX,float focalY,int color,float zoom) {
        if(w<=0||h<=0) return;
        int tw=textureWidth(image),th=textureHeight(image);
        float ratio=w/(float)h,z=Math.clamp(zoom,1,1.5f);
        int sw=Math.max(1,Math.round(Math.min(tw,th*ratio)/z));
        int sh=Math.max(1,Math.round(Math.min(th,tw/ratio)/z));
        int u=Math.round((tw-sw)*Math.clamp(focalX,0,1)),v=Math.round((th-sh)*Math.clamp(focalY,0,1));
        g.blit(RenderPipelines.GUI_TEXTURED,image,x,y,u,v,w,h,sw,sh,tw,th,color);
    }
    public static void mist(GuiGraphicsExtractor g,int x,int y,int w,int h,float opacity) {
        if(w>0&&h>0&&opacity>0) g.blit(RenderPipelines.GUI_TEXTURED,MIST,x,y,0,0,w,h,1024,256,1024,256,HudStyle.alpha(0xFFC8D7DF,opacity));
    }
    public static void shadow(GuiGraphicsExtractor g,int cx,int cy,int radius,float opacity) {
        SvgHud.shadow(g,cx,cy,radius,HudStyle.alpha(0xFF02080D,opacity));
    }
    public static void grain(GuiGraphicsExtractor g,int x,int y,int w,int h,int phase,float opacity) {
        if(w<=0||h<=0||opacity<=0) return;
        // Tile rather than stretch; phase changes UV only. Existing scissor is never changed.
        for(int cy=0;cy<h;cy+=128) for(int cx=0;cx<w;cx+=128) {
            int cw=Math.min(128,w-cx),ch=Math.min(128,h-cy),u=Math.floorMod(phase*31,128),v=Math.floorMod(phase*17,128);
            g.blit(RenderPipelines.GUI_TEXTURED,FILM,x+cx,y+cy,u,v,cw,ch,cw,ch,256,256,HudStyle.alpha(0xFFC7D4DC,opacity));
        }
    }
    public static void arc(GuiGraphicsExtractor g,int x,int y,int size,float fraction,int color) {
        SvgHud.arc(g,x,y,size,fraction,color,false);
    }
    public static void arcGlow(GuiGraphicsExtractor g,int x,int y,int size,float fraction,int color) {
        SvgHud.arc(g,x,y,size,fraction,color,true);
    }
    public static void wordmark(GuiGraphicsExtractor g,int x,int y,int w,int color) {
        if(w<=0) return;
        int h=Math.max(1,w/8);
        g.blit(RenderPipelines.GUI_TEXTURED,WORDMARK_GLOW,x,y,0,0,w,h,1536,192,1536,192,HudStyle.alpha(color,.68f));
        g.blit(RenderPipelines.GUI_TEXTURED,WORDMARK,x,y,0,0,w,h,1536,192,1536,192,color);
    }
    /** Analytic halo. No texture, blur pass, readback, or changing the caller's scissor. */
    public static void frameGlow(GuiGraphicsExtractor g,int x,int y,int w,int h,int color,float opacity) {
        if(w<1||h<1||opacity<=0||(color>>>24)==0) return;
        SvgHud.frameGlow(g,x,y,w,h,HudStyle.alpha(color,Math.clamp(opacity,0,1)));
    }
    public static void utilityIcon(GuiGraphicsExtractor g,int index,int x,int y,int size,int color) {
        if(index<0||index>=3||size<=0) return;
        SvgHud.menuIcon(g,switch(index){case 0->MenuGlyph.LANGUAGE;case 1->MenuGlyph.ACCESSIBILITY;default->MenuGlyph.INTERFACE;},x,y,size,color);
    }
    public static void compass(GuiGraphicsExtractor g,int x,int y,int size,int color) {
        SvgHud.compass(g,x,y,size,color);
    }
    public static void modeBadge(GuiGraphicsExtractor g,boolean story,int x,int y,int size,int color) {
        SvgHud.menuIcon(g,story?MenuGlyph.STORY:MenuGlyph.FREE,x,y,size,color);
    }
    public static void icon(GuiGraphicsExtractor g,int index,int x,int y,int size,int color) {
        SvgHud.icon(g,index,x,y,size,color);
    }
    public static void warning(GuiGraphicsExtractor g,int x,int y,int size,int color){SvgHud.warning(g,x,y,size,color);}
    public static void trend(GuiGraphicsExtractor g,int x,int y,int size,boolean up,int color){SvgHud.trend(g,x,y,size,up,color);}
}
