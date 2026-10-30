// Draws native widgets with the shared menu materials.
package com.dark.winter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Native-widget materials only. State, input, narration and geometry remain native. */
public final class MenuWidgetSkin {
    private MenuWidgetSkin() {}

    public static boolean draw(GuiGraphicsExtractor g,Identifier sprite,int x,int y,int w,int h,int tint) {
        if(!sprite.getNamespace().equals("minecraft")||w<=0||h<=0) return false;
        String p=sprite.getPath();
        float alpha=(tint>>>24)/255f;
        boolean lit=p.contains("highlighted"),disabled=p.contains("disabled");
        float visibility=alpha*(disabled?.42f:1f);
        int ink=HudStyle.alpha(lit?HudStyle.INK:HudStyle.MUTED,visibility);
        if(p.equals("icon/language")||p.equals("widget/language")||p.equals("icon/accessibility")||p.equals("widget/accessibility")) {
            int index=p.endsWith("language")?0:1;
            UiArt.utilityIcon(g,index,x+(w-Math.min(w,h))/2,y+(h-Math.min(w,h))/2,Math.min(w,h),HudStyle.alpha(HudStyle.INK,alpha));
            return true;
        }
        if(icon(g,p,x,y,w,h,ink,visibility))return true;
        if(p.startsWith("notification/")) {
            String value=p.substring("notification/".length());
            if(!java.util.Set.of("1","2","3","4","5","more").contains(value))return false;
            g.fill(x,y,x+w,y+h,fade(0xEC263F4E,alpha));frame(g,x,y,w,h,fade(HudStyle.STABLE,alpha));
            if(value.equals("more")) {
                for(int i=0;i<3;i++)g.fill(x+2+i*2,y+h/2,x+3+i*2,y+h/2+1,ink);
            } else HudStyle.centered(g,net.minecraft.network.chat.Component.literal(value),x+w/2,y+1,Math.min(.7f,(h-1)/9f),ink);
            return true;
        }
        if(p.startsWith("widget/page_forward")||p.startsWith("widget/page_backward")) {
            // Book pages have a light paper surface, so retain a dark arrow contrast.
            int paperInk=fade(lit?0xFF264B60:0xFF536B77,alpha);
            boolean forward=p.startsWith("widget/page_forward");
            stroke(g,x,y,w,h,forward?3:13,8,forward?13:3,8,paperInk);
            stroke(g,x,y,w,h,forward?9:7,4,forward?13:3,8,paperInk);
            stroke(g,x,y,w,h,forward?9:7,12,forward?13:3,8,paperInk);
            return true;
        }
        if(p.startsWith("widget/cross_button")) {
            surface(g,x,y,w,h,visibility,lit,false);
            stroke(g,x,y,w,h,5,5,11,11,ink);stroke(g,x,y,w,h,11,5,5,11,ink);return true;
        }
        if(p.equals("widget/slot_frame")) {
            // A Realms world thumbnail remains visible within the frame.
            frame(g,x,y,w,h,ink);frame(g,x+2,y+2,w-4,h-4,fade(0x60537384,alpha));return true;
        }
        if(p.startsWith("dialog/warning_button")) {
            surface(g,x,y,w,h,visibility,lit,false);warning(g,x,y,w,h,fade(HudStyle.AMBER,visibility));return true;
        }
        if(p.startsWith("widget/realms_button")) {
            surface(g,x,y,w,h,visibility,lit,false);
            oval(g,x+w/2,y+h/2,Math.max(2,w*5/16),Math.max(2,h*5/16),ink);
            stroke(g,x,y,w,h,3,8,13,8,ink);stroke(g,x,y,w,h,8,3,8,13,ink);return true;
        }
        if(p.startsWith("tooltip/")&&(p.endsWith("background")||p.endsWith("frame"))) {
            // Tooltip sprites have a transparent nine-pixel margin; text geometry is unchanged.
            x+=9;y+=9;w-=18;h-=18;
            if(p.endsWith("background")) {
                g.fillGradient(x-2,y-2,x+w+2,y+h+2,fade(0x40000000,alpha),fade(0x80000000,alpha));
                g.fillGradient(x,y,x+w,y+h,fade(0xF2152735,alpha),fade(0xF9081521,alpha));
            } else frame(g,x,y,w,h,fade(0xB08EA9B9,alpha));
            return true;
        }
        if(p.equals("popup/background")) {surface(g,x,y,w,h,alpha,false,false);return true;}
        if(p.startsWith("widget/checkbox")) {
            surface(g,x,y,w,h,visibility,lit,false);
            if(p.contains("selected")) {
                int a=Math.max(1,w/12),cx=x+w/4,cy=y+h/2;
                for(int i=0;i<w/5;i++) g.fill(cx+i,cy+i,cx+i+a,cy+i+a,ink);
                int turn=w/5;
                for(int i=0;i<w/2;i++) g.fill(cx+turn+i,cy+turn-i,cx+turn+i+a,cy+turn-i+a,ink);
            }
            return true;
        }
        if(p.startsWith("widget/locked_button")||p.startsWith("widget/unlocked_button")) {
            surface(g,x,y,w,h,visibility,lit,false);
            int cx=x+w/2,cy=y+h/2,half=Math.max(3,w/5),top=y+Math.max(3,h/5);
            g.fill(cx-half,cy,cx+half,cy+Math.max(4,h/4),ink);
            g.fill(cx-half+1,top,cx+half-1,top+1,ink);
            g.fill(cx-half,top+1,cx-half+1,cy,ink);
            if(p.startsWith("widget/unlocked")) g.fill(cx+half-1,top+1,cx+half,top+3,ink);
            else g.fill(cx+half-1,top+1,cx+half,cy,ink);
            g.fill(cx,cy+1,cx+1,cy+3,fade(0xFF101D27,visibility));
            return true;
        }
        if(p.startsWith("widget/text_field")||p.equals("widget/preedit")) {
            g.fillGradient(x,y,x+w,y+h,fade(0xE008121D,alpha),fade(0xE5132431,alpha));
            frame(g,x,y,w,h,fade(lit?HudStyle.STABLE:0xA35B7484,alpha));
            if(lit) g.fill(x+1,y+h-2,x+w-1,y+h-1,fade(0x7098C6D6,alpha));
            return true;
        }
        if(p.startsWith("widget/scroller")) {
            if(p.contains("background")) g.fill(x+1,y,x+w-1,y+h,fade(0xB0071018,alpha));
            else {
                g.fillGradient(x+1,y,x+w-1,y+h,ink,fade(0xFF40525F,visibility));
                g.fill(x+1,y,x+w-1,y+1,fade(HudStyle.INK,visibility));
            }
            return true;
        }
        if(p.startsWith("widget/slider_handle")) {
            g.fill(x+2,y+2,x+w-2,y+h-2,fade(0xD01C2E3A,visibility));
            g.fill(x+3,y+3,x+w-3,y+h-3,ink);return true;
        }
        if(p.startsWith("widget/button")||p.startsWith("widget/slider")||p.startsWith("widget/tab")) {
            surface(g,x,y,w,h,visibility,lit,p.contains("selected"));return true;
        }
        return false;
    }

    /** Explicit native icon meanings; unknown resource names retain their own rendering. */
    private static boolean icon(GuiGraphicsExtractor g,String path,int x,int y,int w,int h,int ink,float alpha) {
        String p=path.endsWith("_highlighted")?path.substring(0,path.length()-12):path;
        if(p.startsWith("icon/ping_")) {
            String suffix=p.substring("icon/ping_".length());
            int count=suffix.equals("unknown")?0:suffix.length()==1&&suffix.charAt(0)>='1'&&suffix.charAt(0)<='5'?suffix.charAt(0)-'0':-1;
            if(count<0)return false;
            for(int i=0;i<5;i++)g.fill(x+i*w/5,y+h-Math.max(1,(i+1)*h/5),x+i*w/5+Math.max(1,w/6),y+h,i<count?ink:fade(0xFF3B515E,alpha));
            if(count==0)stroke(g,x,y,w,h,2,14,14,2,fade(HudStyle.AMBER,alpha));return true;
        }
        switch(p) {
            case "icon/search" -> {
                oval(g,x+w*6/16,y+h*6/16,Math.max(2,w*4/16),Math.max(2,h*4/16),ink);
                stroke(g,x,y,w,h,9,9,14,14,ink);
            }
            case "icon/info" -> {
                oval(g,x+w/2,y+h/2,Math.max(2,w*6/16),Math.max(2,h*6/16),ink);
                stroke(g,x,y,w,h,8,7,8,11,ink);stroke(g,x,y,w,h,8,4,8,4,ink);
            }
            case "icon/checkmark", "pending_invite/accept" -> {
                stroke(g,x,y,w,h,3,8,6,11,ink);stroke(g,x,y,w,h,6,11,13,4,ink);
            }
            case "pending_invite/reject" -> {
                stroke(g,x,y,w,h,4,4,12,12,ink);stroke(g,x,y,w,h,12,4,4,12,ink);
            }
            case "player_list/make_operator", "player_list/remove_operator" -> {
                // A crown marks privileges; the adjacent plus/minus shows the action.
                stroke(g,x,y,w,h,2,5,3,11,ink);stroke(g,x,y,w,h,3,11,10,11,ink);
                stroke(g,x,y,w,h,10,11,11,5,ink);stroke(g,x,y,w,h,2,5,5,8,ink);
                stroke(g,x,y,w,h,5,8,7,3,ink);stroke(g,x,y,w,h,7,3,9,8,ink);stroke(g,x,y,w,h,9,8,11,5,ink);
                stroke(g,x,y,w,h,11,13,15,13,ink);
                if(p.endsWith("make_operator"))stroke(g,x,y,w,h,13,11,13,15,ink);
            }
            case "player_list/remove_player" -> {
                oval(g,x+w*6/16,y+h*5/16,Math.max(1,w*2/16),Math.max(1,h*2/16),ink);
                stroke(g,x,y,w,h,2,13,3,9,ink);stroke(g,x,y,w,h,3,9,8,9,ink);stroke(g,x,y,w,h,8,9,9,13,ink);
                stroke(g,x,y,w,h,11,8,15,12,ink);stroke(g,x,y,w,h,15,8,11,12,ink);
            }
            case "realm_status/closed" -> {
                frame(g,x+w/4,y+h/2,w/2,Math.max(2,h*3/8),ink);
                stroke(g,x,y,w,h,5,8,5,4,ink);stroke(g,x,y,w,h,5,4,11,4,ink);stroke(g,x,y,w,h,11,4,11,8,ink);
            }
            case "realm_status/open" -> {
                oval(g,x+w/2,y+h/2,Math.max(2,w*6/16),Math.max(2,h*6/16),ink);
                stroke(g,x,y,w,h,4,8,7,11,ink);stroke(g,x,y,w,h,7,11,12,5,ink);
            }
            case "realm_status/expired", "realm_status/expires_soon" -> {
                int accent=fade(p.endsWith("expired")?HudStyle.CRITICAL:HudStyle.AMBER,alpha);
                oval(g,x+w/2,y+h/2,Math.max(2,w*6/16),Math.max(2,h*6/16),accent);
                if(p.endsWith("expired")) {
                    stroke(g,x,y,w,h,5,5,11,11,accent);stroke(g,x,y,w,h,11,5,5,11,accent);
                } else {
                    stroke(g,x,y,w,h,8,4,8,8,accent);stroke(g,x,y,w,h,8,8,11,9,accent);
                }
            }
            case "icon/music_notes" -> {
                stroke(g,x,y,w,h,5,4,5,12,ink);stroke(g,x,y,w,h,12,2,12,10,ink);stroke(g,x,y,w,h,5,4,12,2,ink);
                g.fill(x+w*2/16,y+h*11/16,x+w*6/16,y+h*14/16,ink);
                g.fill(x+w*9/16,y+h*9/16,x+w*13/16,y+h*12/16,ink);
            }
            case "icon/invite" -> {
                frame(g,x+w/8,y+h/4,w*3/4,h/2,ink);
                stroke(g,x,y,w,h,2,4,8,9,ink);stroke(g,x,y,w,h,8,9,14,4,ink);
            }
            case "icon/news" -> {
                frame(g,x+w/8,y+h/8,w*3/4,h*3/4,ink);
                frame(g,x+w/4,y+h/4,Math.max(2,w/4),Math.max(2,h/4),ink);
                stroke(g,x,y,w,h,9,4,12,4,ink);stroke(g,x,y,w,h,9,7,12,7,ink);stroke(g,x,y,w,h,4,11,12,11,ink);
            }
            case "icon/link" -> {
                stroke(g,x,y,w,h,9,3,13,3,ink);stroke(g,x,y,w,h,13,3,13,7,ink);stroke(g,x,y,w,h,7,9,13,3,ink);
                stroke(g,x,y,w,h,6,4,3,4,ink);stroke(g,x,y,w,h,3,4,3,13,ink);stroke(g,x,y,w,h,3,13,12,13,ink);stroke(g,x,y,w,h,12,13,12,10,ink);
            }
            case "icon/video_link" -> {
                frame(g,x+w/8,y+h/4,w*3/4,h/2,ink);
                stroke(g,x,y,w,h,6,6,10,8,ink);stroke(g,x,y,w,h,10,8,6,10,ink);stroke(g,x,y,w,h,6,10,6,6,ink);
            }
            case "icon/new_realm" -> {
                oval(g,x+w*7/16,y+h*7/16,Math.max(2,w*5/16),Math.max(2,h*5/16),ink);
                stroke(g,x,y,w,h,2,7,12,7,ink);stroke(g,x,y,w,h,7,2,7,12,ink);
                stroke(g,x,y,w,h,11,13,15,13,ink);stroke(g,x,y,w,h,13,11,13,15,ink);
            }
            case "icon/trial_available" -> {
                oval(g,x+w/2,y+h/2,Math.max(2,w*6/16),Math.max(2,h*6/16),ink);
                stroke(g,x,y,w,h,8,4,8,8,ink);stroke(g,x,y,w,h,8,8,11,9,ink);
            }
            case "icon/unseen_notification" -> {
                oval(g,x+w/2,y+h/2,Math.max(2,w*5/16),Math.max(2,h*5/16),fade(HudStyle.AMBER,alpha));
                stroke(g,x,y,w,h,8,5,8,8,ink);stroke(g,x,y,w,h,8,11,8,11,ink);
            }
            case "icon/draft_report", "pause_menu/player_reporting" -> {
                frame(g,x+w/4,y+h/8,w/2,h*3/4,ink);
                stroke(g,x,y,w,h,6,5,10,5,ink);stroke(g,x,y,w,h,6,8,10,8,ink);
                stroke(g,x,y,w,h,9,13,14,8,fade(HudStyle.AMBER,alpha));
            }
            case "pause_menu/social_interactions" -> {
                oval(g,x+w*5/16,y+h*5/16,Math.max(1,w*2/16),Math.max(1,h*2/16),ink);
                oval(g,x+w*11/16,y+h*5/16,Math.max(1,w*2/16),Math.max(1,h*2/16),ink);
                stroke(g,x,y,w,h,2,12,3,9,ink);stroke(g,x,y,w,h,3,9,7,9,ink);stroke(g,x,y,w,h,7,9,8,12,ink);
                stroke(g,x,y,w,h,9,9,13,9,ink);stroke(g,x,y,w,h,13,9,14,12,ink);
            }
            case "pause_menu/bug" -> {
                oval(g,x+w/2,y+h*9/16,Math.max(2,w*3/16),Math.max(2,h*4/16),ink);
                stroke(g,x,y,w,h,6,4,5,2,ink);stroke(g,x,y,w,h,10,4,11,2,ink);
                for(int i=0;i<3;i++){stroke(g,x,y,w,h,2,6+i*3,5,7+i*2,ink);stroke(g,x,y,w,h,11,7+i*2,14,6+i*3,ink);}
            }
            default -> {return false;}
        }
        return true;
    }

    private static void warning(GuiGraphicsExtractor g,int x,int y,int w,int h,int ink) {
        stroke(g,x,y,w,h,8,3,2,13,ink);stroke(g,x,y,w,h,2,13,14,13,ink);stroke(g,x,y,w,h,14,13,8,3,ink);
        stroke(g,x,y,w,h,8,7,8,9,ink);stroke(g,x,y,w,h,8,11,8,11,ink);
    }
    private static void stroke(GuiGraphicsExtractor g,int x,int y,int w,int h,int ax,int ay,int bx,int by,int color) {
        int x1=x+Math.round(w*ax/16f),y1=y+Math.round(h*ay/16f),x2=x+Math.round(w*bx/16f),y2=y+Math.round(h*by/16f);
        GuiSurfaceArt.line(g,x1,y1,x2,y2,color);
    }
    private static void oval(GuiGraphicsExtractor g,int cx,int cy,int rx,int ry,int color) {
        for(int i=0;i<16;i++) {
            double a=Math.PI*i/8,b=Math.PI*(i+1)/8;
            GuiSurfaceArt.line(g,cx+(int)Math.round(rx*Math.cos(a)),cy+(int)Math.round(ry*Math.sin(a)),cx+(int)Math.round(rx*Math.cos(b)),cy+(int)Math.round(ry*Math.sin(b)),color);
        }
    }

    private static int fade(int color,float alpha){return HudStyle.alpha(color,alpha);}
    private static void frame(GuiGraphicsExtractor g,int x,int y,int w,int h,int color) {
        if(w<=0||h<=0)return;
        g.fill(x,y,x+w,y+1,color);g.fill(x,y+h-1,x+w,y+h,color);
        g.fill(x,y,x+1,y+h,color);g.fill(x+w-1,y,x+w,y+h,color);
    }
    private static void surface(GuiGraphicsExtractor g,int x,int y,int w,int h,float alpha,boolean lit,boolean selected) {
        g.fillGradient(x,y,x+w,y+h,fade(lit?0xF12E4758:0xDD192C3B,alpha),fade(0xE0081420,alpha));
        frame(g,x,y,w,h,fade(lit||selected?HudStyle.STABLE:0x99557081,alpha));
        if(lit||selected)g.fill(x+3,y+h-3,x+w-3,y+h-2,fade(selected?HudStyle.STABLE:0x70A9DAE7,alpha));
    }
}
