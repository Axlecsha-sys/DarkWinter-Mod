// Draws reserve dials, health, and sampled survival trends.
package com.dark.winter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.Mth;

/** Five reserve dials over one health rail. Values are read only; trends are sampled independently of FPS. */
public final class SurvivalNeedsHud {
    private static final float[] displayed=new float[6],previous=new float[6],anchor=new float[6];
    private static final int[] direction=new int[6];
    private static final long[] trendUntil=new long[6],sampleAt=new long[6];
    public static final int HEIGHT=54,MARGIN=15;
    private static java.util.UUID previousPlayer;
    private static Object previousWorld;
    private static long lastFrame;
    private SurvivalNeedsHud() {}
    public static int panelWidth(int width){return UiLayout.hudWidth(width);}
    public static int panelX(int width){return MARGIN;}
    public static int panelY(int width,int height,boolean chat) {
        var p=Minecraft.getInstance().player;
        boolean reserve=width<620&&p!=null&&(p.getArmorValue()>0||p.getAirSupply()<p.getMaxAirSupply()||p.getVehicle()!=null);
        return height-HEIGHT-(reserve?52:chat?24:MARGIN);
    }
    public static int chatBottom(int width,int height){return panelY(width,height,Minecraft.getInstance().gui.screen() instanceof ChatScreen)-8;}
    public static void render(GuiGraphicsExtractor g) {
        if (!TestingAccess.enabled("hud")) return;
        var c=Minecraft.getInstance();
        if(c.player==null||c.gui.hud.isHidden()||(c.gui.screen()!=null&&!(c.gui.screen() instanceof ChatScreen))) return;
        long now=System.nanoTime();
        boolean fresh=!c.player.getUUID().equals(previousPlayer)||previousWorld!=c.level||lastFrame==0||now-lastFrame>500_000_000L;
        previousPlayer=c.player.getUUID();previousWorld=c.level;
        float blend=fresh||!UiPreferences.animated()?1:1-(float)Math.exp(-(now-lastFrame)/160_000_000.0);lastFrame=now;
        int w=panelWidth(g.guiWidth()),x=MARGIN,y=panelY(g.guiWidth(),g.guiHeight(),c.gui.screen() instanceof ChatScreen);
        sample(0,c.player.getHealth()/Math.max(1,c.player.getMaxHealth()),fresh,blend,now);
        sample(1,SprintStamina.getStamina()/Math.max(1,SprintStamina.getMaxStamina()),fresh,blend,now);
        sample(2,(100-SurvivalNeeds.getHunger())/100,fresh,blend,now);
        sample(3,SurvivalNeeds.getTemperature()/100,fresh,blend,now);
        sample(4,SurvivalNeeds.getThirst()/100,fresh,blend,now);
        sample(5,1-SurvivalNeeds.getFatigue()/100,fresh,blend,now);

        // The reserve dials start and end exactly over the health rail, so the group reads as one instrument.
        int bx=x+18,railEnd=x+w-2,len=railEnd-bx;
        float step=len/4f;
        int size=Math.min(31,Math.max(24,Math.round(Math.min(step-2,30))));
        int shadow=Math.min(Math.round(step*.45f),size/2+2);
        for(int i=1;i<=5;i++) ring(g,i,Math.round(bx+(i-1)*step),y+18,size,shadow,now);

        // Health is the foundation of the group, not a sixth dial. No enclosing HUD rectangle.
        int filled=Math.round(len*displayed[0]);
        int health=critical(0)?HudStyle.CRITICAL:0xFFD34D58;
        int barOuterTop=y+41,barOuterBottom=y+49,barInnerTop=y+42,barInnerBottom=y+48;
        int heartSize=12,heartY=(barOuterTop+barOuterBottom-heartSize)/2;
        UiArt.icon(g,0,x,heartY,heartSize,critical(0)?health:HudStyle.INK);
        g.fill(bx-2,barOuterTop,bx+len+2,barOuterBottom,0x823F5C6C);
        g.fill(bx-1,barInnerTop,bx+len+1,barInnerBottom,0xCC020913);
        if(filled>0) {
            HudStyle.halo(g,bx,barInnerTop+1,filled,4,health,critical(0)?pulse(now)*.55f:.24f);
            g.fillGradient(bx,barInnerTop+1,bx+filled,barInnerTop+3,HudStyle.mix(health,HudStyle.INK,.12f),health);
            g.fillGradient(bx,barInnerTop+3,bx+filled,barInnerTop+5,health,0xFF481F2D);
            for(int dx=2;dx<filled-3;dx+=5) {
                for(int row=0;row<4;row++) g.fill(bx+dx+row,barInnerTop+4-row,bx+dx+row+1,barInnerTop+5-row,0x74080D17);
            }
            g.fill(bx,barInnerTop+1,bx+filled,barInnerTop+2,HudStyle.alpha(HudStyle.INK,.15f));
        }
        if(critical(0)) g.fill(x+w-3,y+39,x+w-1,y+42,HudStyle.alpha(health,pulse(now)));
        float absorption=c.player.getAbsorptionAmount();
        if(absorption>0) {
            int extra=Math.round(len*Math.min(1,absorption/Math.max(1,c.player.getMaxHealth())));
            g.fill(bx,barOuterBottom,bx+len,barOuterBottom+2,0x601B2530);
            g.fill(bx,barOuterBottom,bx+Math.max(1,extra),barOuterBottom+2,0xFFD9B771);
            if(absorption>c.player.getMaxHealth()) g.fill(bx+len-1,barOuterBottom-1,bx+len+1,barOuterBottom+3,0xFFF1D199);
        }
    }
    private static void sample(int i,float value,boolean fresh,float blend,long now) {
        value=Mth.clamp(value,0,1);
        if(fresh){displayed[i]=anchor[i]=value;direction[i]=0;trendUntil[i]=0;sampleAt[i]=now;}
        else if(now-sampleAt[i]>=120_000_000L) {
            float delta=value-anchor[i];
            if(Math.abs(delta)>.0006f) {direction[i]=delta>0?1:-1;if(i==5) direction[i]=-direction[i];trendUntil[i]=now+1_200_000_000L;}
            anchor[i]=value;sampleAt[i]=now;
        }
        previous[i]=value;
        displayed[i]=i==0&&value<displayed[i]?value:displayed[i]+(value-displayed[i])*blend;
    }
    private static boolean critical(int i){return previous[i]<=(i==0?.25f:i==1?.15f:.2f);}
    private static int color(int i) {
        int calm=switch(i){case 1,4->0xFFB9DEEC;case 2,5->0xFFCFDCD9;default->0xFFD8E8EB;};
        return critical(i)?HudStyle.CRITICAL:previous[i]<=.35f?HudStyle.AMBER:calm;
    }
    private static float pulse(long now){return UiPreferences.full()?.84f+.16f*(float)Math.sin(now/1_000_000_000.0*Math.PI*.9):.88f;}
    private static void ring(GuiGraphicsExtractor g,int i,int cx,int cy,int size,int shadow,long now) {
        int color=color(i);UiArt.shadow(g,cx,cy,shadow,.62f);
        UiArt.arc(g,cx-size/2,cy-size/2,size,1,0x824B697A);
        UiArt.arcGlow(g,cx-size/2,cy-size/2,size,displayed[i],HudStyle.alpha(color,critical(i)?.51f*pulse(now):.29f));
        UiArt.arc(g,cx-size/2,cy-size/2,size,displayed[i],HudStyle.alpha(color,critical(i)?1f:.86f));
        int iconSize=Math.min(18,Math.max(10,size-5));
        UiArt.icon(g,i,cx-iconSize/2,cy-iconSize/2,iconSize,HudStyle.alpha(HudStyle.mix(color,HudStyle.INK,.08f),.84f));
        if(critical(i)) {
            int ex=cx+size/2-1,ey=cy-size/2;
            g.fill(ex,ey,ex+1,ey+3,HudStyle.alpha(HudStyle.AMBER,pulse(now)));g.fill(ex,ey+4,ex+1,ey+5,HudStyle.AMBER);
        }
        if(trendUntil[i]>now) {
            boolean worsening=i==5?direction[i]>0:direction[i]<0;
            int arrow=HudStyle.alpha(worsening?HudStyle.AMBER:HudStyle.INK,Math.min(1,(trendUntil[i]-now)/250_000_000f));
            int ay=cy+size/2+1,tip=direction[i]>0?ay:ay+5;
            g.fill(cx,ay,cx+1,ay+6,arrow);
            for(int step=0;step<3;step++){int yy=tip+(direction[i]>0?step:-step);g.fill(cx-step,yy,cx-step+1,yy+1,arrow);g.fill(cx+step,yy,cx+step+1,yy+1,arrow);}
        }
    }
}
