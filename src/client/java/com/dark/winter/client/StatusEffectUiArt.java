// Draws compact instrument-style symbols for native status effects.
package com.dark.winter.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Original compact instrument symbols for status effects. No effect duration or behavior is changed. */
public final class StatusEffectUiArt {
    private enum Shape { MOVE, TOOL, FORCE, HEALTH, HARM, SHIELD, FIRE, WATER, EYE, FOOD,
        DROP, STAR, UP, DOWN, LUCK, OMEN, BADGE, WIND, WEB, BUG, SHELL }
    private record Mark(Component code, Shape shape) { }
    private static Mark mark(String code,Shape shape){return new Mark(Component.literal(code),shape);}
    private static final Map<String,Mark> MARKS=Map.ofEntries(
            Map.entry("speed",mark("SP",Shape.MOVE)),
            Map.entry("slowness",mark("SL",Shape.DOWN)),
            Map.entry("haste",mark("HA",Shape.TOOL)),
            Map.entry("mining_fatigue",mark("MF",Shape.TOOL)),
            Map.entry("strength",mark("ST",Shape.FORCE)),
            Map.entry("instant_health",mark("IH",Shape.HEALTH)),
            Map.entry("instant_damage",mark("ID",Shape.HARM)),
            Map.entry("jump_boost",mark("JB",Shape.UP)),
            Map.entry("nausea",mark("NA",Shape.WIND)),
            Map.entry("regeneration",mark("RE",Shape.HEALTH)),
            Map.entry("resistance",mark("RS",Shape.SHIELD)),
            Map.entry("fire_resistance",mark("FR",Shape.FIRE)),
            Map.entry("water_breathing",mark("WB",Shape.WATER)),
            Map.entry("invisibility",mark("IN",Shape.EYE)),
            Map.entry("blindness",mark("BL",Shape.EYE)),
            Map.entry("night_vision",mark("NV",Shape.EYE)),
            Map.entry("hunger",mark("HU",Shape.FOOD)),
            Map.entry("weakness",mark("WK",Shape.FORCE)),
            Map.entry("poison",mark("PO",Shape.DROP)),
            Map.entry("wither",mark("WI",Shape.HARM)),
            Map.entry("health_boost",mark("HB",Shape.HEALTH)),
            Map.entry("absorption",mark("AB",Shape.SHIELD)),
            Map.entry("saturation",mark("SA",Shape.FOOD)),
            Map.entry("glowing",mark("GL",Shape.STAR)),
            Map.entry("levitation",mark("LE",Shape.UP)),
            Map.entry("luck",mark("LU",Shape.LUCK)),
            Map.entry("unluck",mark("UN",Shape.LUCK)),
            Map.entry("slow_falling",mark("SF",Shape.DOWN)),
            Map.entry("conduit_power",mark("CP",Shape.STAR)),
            Map.entry("dolphins_grace",mark("DG",Shape.WATER)),
            Map.entry("bad_omen",mark("BO",Shape.OMEN)),
            Map.entry("hero_of_the_village",mark("HV",Shape.BADGE)),
            Map.entry("darkness",mark("DK",Shape.EYE)),
            Map.entry("trial_omen",mark("TO",Shape.OMEN)),
            Map.entry("raid_omen",mark("RO",Shape.OMEN)),
            Map.entry("wind_charged",mark("WC",Shape.WIND)),
            Map.entry("weaving",mark("WE",Shape.WEB)),
            Map.entry("oozing",mark("OO",Shape.DROP)),
            Map.entry("infested",mark("IF",Shape.BUG)),
            Map.entry("breath_of_the_nautilus",mark("BN",Shape.SHELL)));
    private static final Map<String,Integer> COLORS=new HashMap<>();
    private static final Mark UNKNOWN=mark("FX",Shape.STAR);

    private StatusEffectUiArt() { }

    public static boolean sprite(GuiGraphicsExtractor g,Identifier id,int x,int y,int w,int h,int color) {
        if(!id.getNamespace().equals("minecraft")||!id.getPath().startsWith("mob_effect/"))return false;
        if(w<=0||h<=0||(color>>>24)==0)return true;
        String name=id.getPath().substring("mob_effect/".length());
        Mark mark=MARKS.getOrDefault(name,UNKNOWN);
        int nativeColor=COLORS.computeIfAbsent(name,key->{
            var effect=BuiltInRegistries.MOB_EFFECT.getValue(Identifier.withDefaultNamespace(key));
            return effect==null?HudStyle.STABLE:0xFF000000|effect.getColor();
        });
        // Native tint and expiration alpha pass through both the symbol and its colored signal.
        int ink=multiply(HudStyle.INK,color),accent=multiply(nativeColor,color);
        int size=Math.min(w,h);
        g.pose().pushMatrix();g.pose().translate(x+(w-size)/2f,y+(h-size)/2f);g.pose().scale(size/18f,size/18f);
        drawSymbol(g,mark.shape(),ink,accent);
        HudStyle.text(g,mark.code(),9-HudStyle.width(mark.code())*.69f/2,9,.69f,ink);
        g.fill(3,17,15,18,accent);
        g.pose().popMatrix();
        return true;
    }

    private static void drawSymbol(GuiGraphicsExtractor g,Shape shape,int ink,int accent) {
        switch(shape) {
            case MOVE -> {line(g,4,2,7,4,ink);line(g,7,4,4,7,ink);line(g,10,2,13,4,ink);line(g,13,4,10,7,ink);}
            case TOOL -> {line(g,5,2,13,3,ink);line(g,9,3,6,7,ink);line(g,12,3,14,5,accent);}
            case FORCE -> {line(g,5,4,13,4,ink);line(g,4,2,4,6,ink);line(g,6,2,6,6,ink);line(g,12,2,12,6,ink);line(g,14,2,14,6,ink);}
            case HEALTH -> {g.fill(8,1,10,8,ink);g.fill(5,3,13,5,ink);}
            case HARM -> {line(g,6,1,12,7,ink);line(g,12,1,6,7,ink);g.fill(8,3,11,5,accent);}
            case SHIELD -> {line(g,5,1,13,1,ink);line(g,5,1,6,5,ink);line(g,13,1,12,5,ink);line(g,6,5,9,8,ink);line(g,12,5,9,8,ink);}
            case FIRE -> {line(g,9,1,6,5,ink);line(g,6,5,8,7,ink);line(g,8,7,12,6,ink);line(g,12,6,10,3,ink);line(g,9,4,9,6,accent);}
            case WATER -> {for(int row=0;row<2;row++){int y=2+row*4;line(g,4,y+1,7,y,ink);line(g,7,y,10,y+2,ink);line(g,10,y+2,14,y+1,ink);}}
            case EYE -> {line(g,4,4,8,1,ink);line(g,8,1,14,4,ink);line(g,4,4,8,7,ink);line(g,8,7,14,4,ink);g.fill(8,3,10,5,accent);}
            case FOOD -> {line(g,5,1,5,7,ink);line(g,3,1,3,3,ink);line(g,7,1,7,3,ink);line(g,3,3,7,3,ink);line(g,12,1,12,7,ink);line(g,12,1,14,4,ink);}
            case DROP -> {line(g,9,1,5,5,ink);line(g,9,1,13,5,ink);line(g,5,5,8,7,ink);line(g,13,5,10,7,ink);g.fill(8,6,11,8,accent);}
            case STAR -> {line(g,9,1,9,7,ink);line(g,4,4,14,4,ink);line(g,6,2,12,6,accent);line(g,12,2,6,6,accent);}
            case UP -> {line(g,9,1,9,7,ink);line(g,5,4,9,1,ink);line(g,13,4,9,1,ink);}
            case DOWN -> {line(g,9,1,9,7,ink);line(g,5,4,9,7,ink);line(g,13,4,9,7,ink);}
            case LUCK -> {line(g,9,1,5,4,ink);line(g,5,4,9,7,ink);line(g,9,7,13,4,ink);line(g,13,4,9,1,ink);g.fill(8,3,11,5,accent);}
            case OMEN -> {line(g,9,1,4,7,ink);line(g,9,1,14,7,ink);line(g,4,7,14,7,ink);g.fill(9,3,10,5,accent);g.fill(9,6,10,7,accent);}
            case BADGE -> {line(g,5,1,13,1,ink);line(g,5,1,5,7,ink);line(g,13,1,13,7,ink);line(g,5,7,9,5,ink);line(g,13,7,9,5,ink);}
            case WIND -> {line(g,3,2,12,2,ink);line(g,12,2,14,3,ink);line(g,14,3,12,4,ink);line(g,5,5,10,5,accent);line(g,10,5,12,7,accent);}
            case WEB -> {line(g,9,1,9,7,ink);line(g,4,4,14,4,ink);line(g,5,1,13,7,ink);line(g,13,1,5,7,ink);}
            case BUG -> {GuiSurfaceArt.frame(g,7,2,5,5,ink,false);for(int y=2;y<=6;y+=2){line(g,4,y-1,7,y,ink);line(g,11,y,14,y-1,ink);}}
            case SHELL -> {line(g,5,7,5,2,ink);line(g,5,2,12,1,ink);line(g,12,1,14,5,ink);line(g,14,5,9,7,ink);line(g,9,7,8,4,ink);line(g,8,4,11,3,accent);}
        }
    }
    private static void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int color){GuiSurfaceArt.line(g,x0,y0,x1,y1,color);}
    private static int multiply(int base,int tint) {
        int alpha=(base>>>24)*(tint>>>24)/255;
        int r=((base>>>16)&255)*((tint>>>16)&255)/255;
        int green=((base>>>8)&255)*((tint>>>8)&255)/255;
        int b=(base&255)*(tint&255)/255;
        return alpha<<24|r<<16|green<<8|b;
    }
}
