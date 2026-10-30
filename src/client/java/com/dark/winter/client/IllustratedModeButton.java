// Draws focusable world-mode cards using the original button hit regions.
package com.dark.winter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Two real focusable buttons, with adaptive image-first cards. No duplicated invisible click targets. */
public final class IllustratedModeButton extends Button {
    private final boolean story;
    private final Identifier image;
    private float light;
    private long lastPaint;
    public IllustratedModeButton(int x,int y,int w,int h,boolean story,OnPress action) {
        super(x,y,w,h,Component.translatable("menu.darkwinter."+(story?"story":"free")),action,DEFAULT_NARRATION);
        this.story=story;image=story?UiArt.STORY:UiArt.FREE;
        String mode=story?"story":"free";
        setTooltip(Tooltip.create(HudStyle.font(Component.translatable("menu.darkwinter."+mode+".desc").append("\n").append(Component.translatable("menu.darkwinter."+mode+".note")))));
        setTooltipDelay(java.time.Duration.ofMillis(650));
    }
    @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
        long now=System.nanoTime();float elapsed=lastPaint==0?0:Math.min(.1f,(now-lastPaint)/1_000_000_000f);lastPaint=now;
        float target=active&&isHoveredOrFocused()?1:0;
        light+=(target-light)*(UiPreferences.animated()?1-(float)Math.exp(-elapsed/(target>light?.16f:.22f)):1);
        int x=getX(),y=getY(),w=getWidth(),h=getHeight();
        int picture=Math.max(22,Math.min(Math.round(h*.49f),h-94));
        if(h<146) picture=Math.max(22,Math.round(h*.42f));
        g.fillGradient(x,y,x+w,y+h,0xD5182B39,0xF3040D17);
        UiArt.coverZoom(g,image,x+1,y+1,w-2,picture,story?.50f:.67f,story?.64f:.5f,HudStyle.mix(0xFFD0D6DD,0xFFFFFFFF,light),story?1.18f:1f);
        g.fillGradient(x+1,y+picture-16,x+w-1,y+picture+2,0x00050D17,0xCB050D17);
        int edge=HudStyle.mix(0xFF698899,HudStyle.INK,light);
        g.fill(x,y,x+w,y+1,edge);g.fill(x,y+h-1,x+w,y+h,edge);g.fill(x,y,x+1,y+h,edge);g.fill(x+w-1,y,x+w,y+h,edge);
        g.fill(x+1,y+picture+1,x+w-1,y+picture+2,HudStyle.alpha(edge,.35f));
        HudStyle.halo(g,x,y,w,h,HudStyle.STABLE,.18f+light*.70f);
        String mode=story?"story":"free";
        int titleY=y+picture+9;
        UiArt.modeBadge(g,story,x+9,titleY-1,14,HudStyle.INK);
        HudStyle.fit(g,HudStyle.strong(Component.translatable("menu.darkwinter."+mode+".short")),x+29,titleY,w-39,Math.max(.92f,Math.min(1.08f,w/200f)),HudStyle.INK);
        float body=Math.min(.90f,Math.max(.78f,w/265f));
        int summaryY=titleY+18,limit=Math.max(0,(int)((y+h-26-summaryY)/(body*12)));
        HudStyle.paragraph(g,Component.translatable("menu.darkwinter."+mode+".card"),x+10,summaryY,w-22,body,0xFFB2C8D3,limit);
        int ax=x+w-23+Math.round(light*3),ay=y+h-17;
        if(h>196) g.fill(x+10,y+h-29,x+w-10,y+h-28,0x40537384);
        g.fill(ax-7,ay+3,ax+3,ay+4,edge);
        for(int i=0;i<4;i++){g.fill(ax+i,ay+i,ax+i+1,ay+i+1,edge);g.fill(ax+i,ay+6-i,ax+i+1,ay+7-i,edge);}
        if(light>.01f) g.fillGradient(x+2,y+picture-5,x+w-2,y+picture,HudStyle.alpha(HudStyle.STABLE,light*.26f),0x009BD9ED);
    }
}
