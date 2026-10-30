// Draws native workstation, recipe, notification, and auxiliary HUD surfaces.
package com.dark.winter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Presentation of native workstation, recipe, notification and auxiliary HUD surfaces.
 * Slot coordinates, clipping, values and event handlers still belong to Minecraft. */
public final class GuiSurfaceArt {
    private GuiSurfaceArt() {}
    public static void frame(GuiGraphicsExtractor g,int x,int y,int w,int h,int ink,boolean filled) {
        if(w<1||h<1)return;
        if(filled)g.fillGradient(x,y,x+w,y+h,0xF21B2B36,0xFA09141E);
        g.fill(x,y,x+w,y+1,ink);g.fill(x,y+h-1,x+w,y+h,ink);
        g.fill(x,y,x+1,y+h,ink);g.fill(x+w-1,y,x+w,y+h,ink);
    }
    public static void slot(GuiGraphicsExtractor g,int x,int y,int w,int h) {
        g.fill(x,y,x+w,y+h,0xD0061018);
        frame(g,x,y,w,h,0xFF456170,false);
        g.fill(x+1,y+h-2,x+w-1,y+h-1,0x60334E5C);
    }
    public static void line(GuiGraphicsExtractor g,int x1,int y1,int x2,int y2,int ink) {
        int steps=Math.max(Math.abs(x2-x1),Math.abs(y2-y1));
        for(int i=0;i<=steps;i++){float t=steps==0?0:i/(float)steps;int x=Math.round(x1+(x2-x1)*t),y=Math.round(y1+(y2-y1)*t);g.fill(x,y,x+1,y+1,ink);}
    }
    private static void arrow(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean left,int ink) {
        int cy=y+h/2,a=x+Math.max(1,w/5),b=x+w-Math.max(2,w/5),tip=left?a:b,tail=left?b:a;
        line(g,tail,cy,tip,cy,ink);line(g,tip,cy,(tip+tail)/2,y+h/4,ink);line(g,tip,cy,(tip+tail)/2,y+h-h/4-1,ink);
    }
    private static void cross(GuiGraphicsExtractor g,int x,int y,int w,int h,int ink) {
        line(g,x+w/4,y+h/4,x+w*3/4,y+h*3/4,ink);line(g,x+w*3/4,y+h/4,x+w/4,y+h*3/4,ink);
    }
    private static void check(GuiGraphicsExtractor g,int x,int y,int w,int h,int ink) {
        line(g,x+w/5,y+h/2,x+w*2/5,y+h*3/4,ink);line(g,x+w*2/5,y+h*3/4,x+w*4/5,y+h/4,ink);
    }
    private static void book(GuiGraphicsExtractor g,int x,int y,int w,int h,int ink) {
        frame(g,x+w/5,y+h/5,w*3/5,h*3/5,ink,false);
        line(g,x+w/2,y+h/5,x+w/2,y+h*4/5,ink);
        line(g,x+w/4,y+h/3,x+w*2/5,y+h/3,ink);line(g,x+w*3/5,y+h/3,x+w*3/4,y+h/3,ink);
    }
    private static void symbol(GuiGraphicsExtractor g,String p,int x,int y,int w,int h,int ink) {
        if(p.contains("cancel")||p.contains("error")||p.contains("out_of_stock")||p.contains("unreachable")||p.contains("reject")||p.endsWith("close")){cross(g,x,y,w,h,HudStyle.CRITICAL);return;}
        if(p.contains("confirm")||p.contains("accept")){check(g,x,y,w,h,ink);return;}
        if(p.contains("backward")||p.contains("scroll_left")||p.contains("unselect")){arrow(g,x,y,w,h,true,ink);return;}
        if(p.contains("forward")||(p.contains("arrow")&&!p.endsWith("_up")&&!p.endsWith("_down"))||p.contains("join")||p.endsWith("select")){arrow(g,x,y,w,h,false,ink);return;}
        if(p.contains("map")){
            int ox=p.contains("duplicated")?3:0;
            if(ox>0)frame(g,x+w/5+ox,y+h/5,w*3/5,h*3/5,0xFF607B89,false);
            frame(g,x+w/5,y+h/5+ox,w*3/5,h*3/5,ink,false);
            line(g,x+w/3,y+h/3,x+w*2/3,y+h*2/3,ink);
            if(p.contains("scaled")){line(g,x+w*2/3,y+h/4,x+w*5/6,y+h/4,ink);line(g,x+w*3/4,y+h/6,x+w*3/4,y+h/3,ink);}return;
        }
        if(p.contains("book")||p.contains("recipe")){book(g,x,y,w,h,ink);return;}
        if(p.startsWith("container/slot/")) {
            if(p.endsWith("banner")) {line(g,x+w/3,y+2,x+w/3,y+h-2,ink);frame(g,x+w/3,y+2,w/2,h/2,ink,false);}
            else if(p.endsWith("dye")){line(g,x+w/2,y+2,x+3,y+h*2/3,ink);line(g,x+w/2,y+2,x+w-3,y+h*2/3,ink);line(g,x+3,y+h*2/3,x+w/2,y+h-2,ink);line(g,x+w-3,y+h*2/3,x+w/2,y+h-2,ink);}
            else {frame(g,x+3,y+2,w-6,h-4,ink,false);line(g,x+4,y+h/2,x+w/2,y+4,ink);line(g,x+w/2,y+4,x+w-4,y+h/2,ink);}
            return;
        }
        if(p.contains("lock")){frame(g,x+w/4,y+h/2,w/2,Math.max(2,h/3),ink,false);frame(g,x+w/3,y+h/4,Math.max(2,w/3),Math.max(2,h/3),ink,false);return;}
        if(p.contains("warning")||p.contains("report")||p.contains("incompatible")){line(g,x+w/2,y+h/5,x+w/2,y+h*3/5,HudStyle.AMBER);g.fill(x+w/2,y+h*4/5,x+w/2+1,y+h*4/5+1,HudStyle.AMBER);return;}
        if(p.contains("move_up")||p.contains("sort_up")||p.contains("arrow_up")||p.contains("move_down")||p.contains("sort_down")||p.contains("arrow_down")){
            boolean up=p.endsWith("up")||p.contains("up_");int tip=up?y+h/4:y+h*3/4,tail=up?y+h*3/4:y+h/4;
            line(g,x+w/4,tail,x+w/2,tip,ink);line(g,x+w*3/4,tail,x+w/2,tip,ink);return;
        }
        // Distinct document/instrument mark for secondary utilities; never replaces item icons.
        frame(g,x+w/4,y+h/4,Math.max(2,w/2),Math.max(2,h/2),ink,false);
        g.fill(x+w/3,y+h/2,x+w*2/3+1,y+h/2+1,ink);
    }
    public static boolean sprite(GuiGraphicsExtractor g,Identifier id,int x,int y,int w,int h,int color) {
        // Testing keeps Minecraft's actual slot dividers, selection, offhand and attack-indicator sprites.
        if(TestingWorlds.isTesting()&&id.getNamespace().equals("minecraft")
                &&(id.getPath().equals("hud/hotbar")||id.getPath().startsWith("hud/hotbar_")))return false;
        if(StatusEffectUiArt.sprite(g,id,x,y,w,h,color))return true;
        if(!id.getNamespace().equals("minecraft")||w<=0||h<=0)return false;
        String p=id.getPath();float alpha=(color>>>24)/255f;
        int ink=HudStyle.alpha(p.contains("disabled")?HudStyle.MUTED:HudStyle.INK,alpha);
        if(p.startsWith("toast/")) {
            if(w>=100){frame(g,x,y,w,h,0xB07393A3,true);g.fill(x+2,y+3,x+4,y+h-3,HudStyle.AMBER);}
            else symbol(g,p,x,y,w,h,ink);
            return true;
        }
        if(p.startsWith("boss_bar/")) {
            if(p.startsWith("boss_bar/notched_")) {int count=Integer.parseInt(p.substring("boss_bar/notched_".length()).split("_")[0]);for(int i=1;i<count;i++){int px=x+w*i/count;g.fill(px,y,px+1,y+h,0x80425260);}return true;}
            if(p.endsWith("background"))frame(g,x,y,w,h,0xFF516C7A,true);
            else g.fill(x,y+1,x+w,y+Math.max(2,h-1),p.contains("red")?HudStyle.CRITICAL:p.contains("yellow")?HudStyle.AMBER:HudStyle.STABLE);
            return true;
        }
        if(p.startsWith("hud/"))return hud(g,p,x,y,w,h,ink);
        if(!(p.startsWith("container/")||p.startsWith("recipe_book/")||p.startsWith("advancements/")||p.startsWith("statistics/")
                ||p.startsWith("social_interactions/")||p.startsWith("server_list/")||p.startsWith("transferable_list/")
                ||p.startsWith("world_list/")||p.startsWith("spectator/")||p.startsWith("gamemode_switcher/")||p.startsWith("friends/")))return false;
        if(p.contains("slot_highlight")) {
            frame(g,x+3,y+3,w-6,h-6,0xFFB6DDE6,false);
            if(p.endsWith("back"))g.fill(x+4,y+4,x+w-4,y+h-4,0x264F8197);
        } else if(p.contains("scroller")){
            frame(g,x,y,w,h,p.contains("disabled")?0x80465D6B:0xFF8EAAB9,true);g.fill(x+3,y+h/2,x+w-3,y+h/2+1,ink);
        } else if(p.contains("tab_")||p.endsWith("/tab")||p.endsWith("tab_selected")||p.contains("frame_")||p.contains("box_")||p.endsWith("title_box")){
            boolean selected=(p.contains("selected")&&!p.contains("unselected"))||(p.contains("obtained")&&!p.contains("unobtained"));
            frame(g,x,y,w,h,selected?HudStyle.STABLE:0xFF506B7A,true);if(selected)g.fill(x+1,y+h-3,x+w-1,y+h-1,HudStyle.STABLE);
        } else if(p.contains("progress")||p.contains("fuel_length")||p.contains("experience_bar")){
            if(p.endsWith("background")||p.endsWith("border"))frame(g,x,y,w,h,0xFF4C6877,true);
            else if(p.contains("lit_progress")){for(int i=0;i<3;i++)line(g,x+(i+1)*w/4,y+h-1,x+(i+1)*w/4,y+(i%2)*h/3,HudStyle.AMBER);}
            else g.fill(x,y,x+w,y+h,p.contains("fuel")?HudStyle.AMBER:HudStyle.STABLE);
        } else if(p.contains("bubbles")){
            for(int i=0;i<3;i++)frame(g,x+(i%2)*w/2,y+i*h/3,Math.max(2,w/3),Math.max(2,h/6),HudStyle.STABLE,false);
        } else if(p.contains("level_")){
            String n=p.substring(p.indexOf("level_")+6,p.indexOf("level_")+7);HudStyle.centered(g,net.minecraft.network.chat.Component.literal(n),x+w/2,y+Math.max(0,(h-9)/2),.85f,ink);
        } else if(p.startsWith("recipe_book/button")){
            frame(g,x,y,w,h,0xFF617D8B,true);book(g,x,y,w,h,ink);
        } else if(p.contains("filter")){
            frame(g,x,y,w,h,p.contains("enabled")?HudStyle.STABLE:0xFF546B79,true);
            line(g,x+w/4,y+h/3,x+w*3/4,y+h/3,ink);line(g,x+w/4,y+h/3,x+w/2,y+h*2/3,ink);line(g,x+w*3/4,y+h/3,x+w/2,y+h*2/3,ink);line(g,x+w/2,y+h*2/3,x+w/2,y+h*4/5,ink);
        } else if(p.contains("button")||p.contains("enchantment_slot")||p.contains("/pattern")||p.contains("/recipe")||p.contains("slot_")||p.endsWith("/slot")||p.contains("background")||p.contains("overlay")){
            int edge=p.contains("highlighted")||p.contains("selected")?HudStyle.STABLE:0xFF506C7C;
            slot(g,x,y,w,h);frame(g,x,y,w,h,edge,false);
            if(p.contains("disabled_slot"))cross(g,x,y,w,h,HudStyle.MUTED);
        } else if(p.contains("ping")){
            int count=5;char n=p.charAt(p.length()-1);if(n>='1'&&n<='5')count=n-'0';for(int i=0;i<5;i++)g.fill(x+i*w/5,y+h-(i+1)*h/5,x+i*w/5+Math.max(1,w/6),y+h,i<count?ink:0xFF3B515E);
        } else symbol(g,p,x,y,w,h,ink);
        return true;
    }
    private static boolean hud(GuiGraphicsExtractor g,String p,int x,int y,int w,int h,int ink) {
        if(p.contains("bar_")||p.contains("indicator")){
            if(p.endsWith("background"))frame(g,x,y,w,h,0xFF536E7C,true);else g.fill(x,y,x+w,y+h,p.contains("cooldown")?HudStyle.AMBER:HudStyle.STABLE);return true;
        }
        if(p.contains("armor_")||p.contains("air")||p.contains("heart/")){
            boolean empty=p.contains("empty")||p.contains("container"),half=p.contains("half");
            int c=p.contains("poison")?0xFFB0BA75:p.contains("absorbing")?HudStyle.AMBER:p.contains("heart")?HudStyle.CRITICAL:HudStyle.STABLE;
            frame(g,x+1,y+2,Math.max(2,w-2),Math.max(2,h-3),0xFF67828F,false);
            if(!empty)g.fill(x+2,y+3,x+Math.max(3,half?w/2:w-2),y+h-2,c);return true;
        }
        if(p.contains("effect_background")){frame(g,x,y,w,h,0xFF668491,true);return true;}
        if(p.equals("hud/crosshair")){line(g,x+w/2,y+2,x+w/2,y+h-3,ink);line(g,x+2,y+h/2,x+w-3,y+h/2,ink);return true;}
        if(p.contains("arrow")){symbol(g,p,x,y,w,h,ink);return true;}
        if(p.contains("hotbar")){frame(g,x,y,w,h,0xFF64828F,true);return true;}
        return false;
    }
    public static boolean texture(GuiGraphicsExtractor g,Identifier id,int x,int y,int w,int h) {
        if(!id.getNamespace().equals("minecraft"))return false;
        String p=id.getPath();
        if(p.startsWith("textures/gui/container/")||p.equals("textures/gui/recipe_book.png")){
            frame(g,x,y,w,h,0xFF617E8D,true);g.fill(x+2,y+2,x+w-2,y+Math.min(17,h-2),0xA0273B48);
            if(p.startsWith("textures/gui/container/")&&Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c
                    &&!(c instanceof DarkWinterInventoryScreen)) {
                var origin=(ContainerSurfaceAccess)c;
                // Wells belong to the panel/background pass, before the native placeholder icons.
                for(var s:c.getMenu().slots)if(s.isActive()) {
                    int sx=origin.darkwinter$panelLeft()+s.x-1,sy=origin.darkwinter$panelTop()+s.y-1;
                    if(sx>=x&&sy>=y&&sx+18<=x+w&&sy+18<=y+h)slot(g,sx,sy,18,18);
                }
                if(p.endsWith("/furnace.png")||p.endsWith("/blast_furnace.png")||p.endsWith("/smoker.png")) {
                    arrow(g,x+79,y+34,24,17,false,0xFF536C79);
                    for(int i=0;i<3;i++)line(g,x+58+i*4,y+48,x+58+i*4,y+38+(i%2)*3,0xFF536C79);
                } else if(p.endsWith("/crafting_table.png"))arrow(g,x+88,y+35,24,17,false,0xFF536C79);
                else if(p.endsWith("/smithing.png"))arrow(g,x+66,y+47,26,17,false,0xFF7B96A3);
                else if(p.endsWith("/anvil.png")) {
                    line(g,x+53,y+54,x+65,y+54,0xFF7B96A3);
                    line(g,x+59,y+48,x+59,y+60,0xFF7B96A3);
                    arrow(g,x+99,y+46,26,17,false,0xFF7B96A3);
                } else if(p.endsWith("/grindstone.png"))arrow(g,x+91,y+33,26,17,false,0xFF7B96A3);
            }
            return true;
        }
        if(p.equals("textures/gui/advancements/window.png")){frame(g,x,y,w,h,0xFF6B8898,false);return true;}
        if(p.equals("textures/gui/book.png")){
            g.fillGradient(x,y,x+w,y+h,0xFFE1DDCB,0xFFC6C5B6);frame(g,x,y,w,h,0xFF576771,false);g.fill(x+w/2,y+4,x+w/2+1,y+h-4,0x60424E52);return true;
        }
        return false;
    }
    public static int readableText(int color) {
        String screen=Minecraft.getInstance().gui.screen()==null?"":Minecraft.getInstance().gui.screen().getClass().getSimpleName();
        if(screen.contains("Book")||screen.contains("Sign")||screen.equals("LecternScreen"))return color;
        int rgb=color&0xFFFFFF;
        if(rgb==0||rgb==0x404040||rgb==0x202020)return (color&0xFF000000)|(HudStyle.INK&0xFFFFFF);
        return color;
    }
}
