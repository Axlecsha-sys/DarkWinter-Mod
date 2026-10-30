// Draws synchronized calendar and climate readings in the HUD.
package com.dark.winter.client;

import com.dark.winter.client.weather.ClimateDisplay;
import com.dark.winter.client.weather.WeatherClient;
import com.dark.winter.visuals.VisualTime;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Presentation of actual calendar/climate readings. No climate state is modified. */
public final class DateTimeHud {
    private DateTimeHud() {}
    public static void render(GuiGraphicsExtractor g) {
        if (!TestingAccess.enabled("hud")) return;
        var c=Minecraft.getInstance();
        if(c.player==null||c.level==null||c.gui.hud.isHidden()||(c.gui.screen()!=null&&!(c.gui.screen() instanceof ChatScreen))) return;
        var date=VisualTime.snapshot(c.level,0);
        int x=SurvivalNeedsHud.MARGIN,y=SurvivalNeedsHud.MARGIN,w=Math.min(176,g.guiWidth()-x*2);
        boolean weather=WeatherClient.active();
        Component name=weather?Component.translatable(WeatherClient.targetPreset().translationKey()):Component.empty();
        boolean transition=weather&&WeatherClient.weatherName().getContents() instanceof TranslatableContents t
                &&t.getKey().equals("weather.darkwinter.transition");
        float nameScale=.81f;
        int textSpace=Math.max(56,w-56);
        int nameLines=weather?Math.max(1,Math.min(2,c.font.split(HudStyle.font(name),(int)(textSpace/nameScale)).size())):1;
        int nameY=y+26;
        int airY=nameY+nameLines*10+5;
        int feelsY=airY+10;
        int windRuleY=feelsY+12;
        int windY=windRuleY+5;
        int panelH=weather?Math.max(72,windY+8-y):26;
        HudStyle.instrumentPanel(g,x,y,w,panelH);

        // Shared optical header band: time and date sit on the same row, with the date eased slightly lower.
        HudStyle.text(g,date.timeText(),x+7,y+5,1.17f,HudStyle.INK);
        HudStyle.fit(g,Component.literal(date.dateTextRu()),x+62,y+8,w-71,.72f,HudStyle.MUTED);
        if(!weather) return;

        var climate=WeatherClient.climate(0);
        boolean rough=WeatherClient.targetPreset()==com.dark.winter.weather.WeatherPreset.BLIZZARD
                ||WeatherClient.targetPreset()==com.dark.winter.weather.WeatherPreset.SEVERE_BLIZZARD||climate.windMetersPerSecond()>=12;
        int accent=rough?HudStyle.AMBER:HudStyle.MUTED;
        g.fill(x+7,y+21,x+w-7,y+22,0x555B7687);
        // A compact transition marker replaces the long prefix, leaving room for the full weather name.
        if(transition) {
            int marker=HudStyle.alpha(HudStyle.AMBER,.72f);
            g.fill(x+3,nameY+1,x+4,nameY+6,marker);
            g.fill(x+2,nameY+1,x+3,nameY+2,marker);
            g.fill(x+4,nameY+5,x+5,nameY+6,marker);
        }
        HudStyle.paragraph(g,name,x+7,nameY,textSpace,nameScale,HudStyle.INK,2);
        HudStyle.fit(g,Component.translatable("hud.darkwinter.field.air",decimal(climate.airTemperatureC())),x+7,airY,textSpace,.77f,0xFFD0DEE4);
        HudStyle.fit(g,Component.translatable("hud.darkwinter.field.feels",decimal(climate.feelsLikeC(ClimateDisplay.exposure()))),x+7,feelsY,textSpace,.73f,HudStyle.MUTED);
        int cx=x+w-22,cy=y+45+(nameLines-1)*5;
        UiArt.shadow(g,cx,cy,18,.70f);
        UiArt.compass(g,cx-16,cy-16,32,0xC7ACCEDD);
        HudStyle.centered(g,Component.literal("С"),cx,cy-21,.60f,HudStyle.MUTED);
        if(climate.windMetersPerSecond()>=.3f) {
            g.pose().pushMatrix();g.pose().translate(cx,cy);
            // Source bearing, geographic north up, not the direction of the camera.
            g.pose().rotate((float)Math.toRadians(climate.sourceBearingDegrees()));
            UiArt.icon(g,6,-9,-9,18,HudStyle.alpha(accent,.90f));g.pose().popMatrix();
        } else g.fill(cx-1,cy-1,cx+2,cy+2,HudStyle.MUTED);
        Component wind=climate.windMetersPerSecond()<.3f?Component.translatable("hud.darkwinter.wind.calm")
                :Component.translatable("hud.darkwinter.wind",Component.translatable(climate.direction().translationKey()),decimal(climate.windMetersPerSecond()));
        g.fill(x+7,windRuleY,x+w-7,windRuleY+1,0x50537384);
        g.fill(x+7,windRuleY,x+24,windRuleY+1,HudStyle.alpha(accent,.57f));
        HudStyle.halo(g,x+7,windRuleY,17,1,accent,rough?.29f:.10f);
        HudStyle.fit(g,wind,x+7,windY,w-14,.76f,rough?HudStyle.AMBER:HudStyle.MUTED);
    }
    private static String decimal(float value){return String.format(Locale.ROOT,"%.1f",value);}
}
