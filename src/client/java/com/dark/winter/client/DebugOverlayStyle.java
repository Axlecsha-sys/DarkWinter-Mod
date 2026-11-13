package com.dark.winter.client;

import com.dark.winter.client.vector.MenuGlyph;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Paints the lines already collected and permission-filtered by the native debug overlay. */
public final class DebugOverlayStyle {
    private record Row(FormattedCharSequence text,boolean groupStart,boolean separator) {}
    private DebugOverlayStyle() {}

    public static void extract(GuiGraphicsExtractor g,List<String> nativeLines,boolean left,int screenWidth,int reserve) {
        if(nativeLines.stream().noneMatch(s->s!=null&&!s.isBlank()))return;
        // The native caller may have applied the separate debug GUI scale already.
        int screenHeight=(int)(g.guiHeight()/Math.max(.001f,Math.abs(g.pose().m11())));
        boolean expanded=Minecraft.getInstance().debugEntries.isOverlayVisible();
        int margin=8,gap=16,w=Math.max(40,(screenWidth-2*margin-gap)/2);
        int x=left?margin:screenWidth-margin-w;
        int top=8,header=expanded?22:4;
        int available=Math.max(16,screenHeight-top-reserve-header-10);
        float scale=.85f;
        List<Row> rows=rows(nativeLines,w-20,scale);
        while(height(rows,scale)>available&&scale>.60f) {scale-=.05f;rows=rows(nativeLines,w-20,scale);}
        // Keep all requested data, including long mod IDs; never ellipsize diagnostics.
        if(height(rows,scale)>available) {
            scale*=available/height(rows,scale);
            rows=rows(nativeLines,w-20,scale);
        }
        int h=header+Math.round(height(rows,scale))+9;
        if(!expanded) {
            float max=0;
            for(Row row:rows)if(!row.separator)max=Math.max(max,Minecraft.getInstance().font.width(row.text)*scale);
            w=Math.min(w,Math.max(42,(int)Math.ceil(max)+20));x=left?margin:screenWidth-margin-w;
        }
        SvgHud.surface(g,x,top,w,h,MenuSurface.PANEL,0,0xDE7897A7);
        g.fill(x+2,top,x+18,top+1,0x9DC2D9E1);
        if(expanded) {
            SvgHud.menuIcon(g,left?MenuGlyph.VIDEO:MenuGlyph.INFO,x+9,top+6,11,HudStyle.MUTED);
            HudStyle.fit(g,Component.translatable(left?"menu.darkwinter.debug.heading":"menu.darkwinter.debug.details"),x+26,top+8,w-54,.67f,HudStyle.MUTED);
            if(left)HudStyle.text(g,"F3",x+w-23,top+8,.65f,HudStyle.STABLE);
            g.fill(x+9,top+20,x+w-9,top+21,0x354D7082);
        }
        float y=top+header+3;
        for(Row row:rows) {
            if(row.separator) {
                y+=2*scale;
                g.fill(x+10,(int)y,x+w-10,(int)y+1,0x234D7082);
                y+=6*scale;
            } else {
                g.pose().pushMatrix();g.pose().translate(x+10,y);g.pose().scale(scale,scale);
                g.text(Minecraft.getInstance().font,row.text,0,0,row.groupStart?HudStyle.STABLE:HudStyle.INK,false);
                g.pose().popMatrix();y+=13*scale;
            }
        }
    }
    private static List<Row> rows(List<String> lines,int width,float scale) {
        var rows=new ArrayList<Row>();boolean groupStart=true,pendingSeparator=false;
        for(String raw:lines) {
            if(raw==null||raw.isBlank()) {pendingSeparator=!rows.isEmpty();continue;}
            if(pendingSeparator){rows.add(new Row(null,false,true));groupStart=true;pendingSeparator=false;}
            var text=HudStyle.strong(Component.literal(present(raw)));
            for(var line:Minecraft.getInstance().font.split(text,Math.max(1,(int)(width/scale)))) {
                rows.add(new Row(line,groupStart,false));groupStart=false;
            }
        }
        return rows;
    }
    private static float height(List<Row> rows,float scale) {
        float h=0;for(Row row:rows)h+=(row.separator?8:13)*scale;return h;
    }
    static String present(String raw) {
        // Only native labels are translated. Values, IDs and actual bound keys are retained.
        if(raw.startsWith("Integrated server @ "))return raw.replace("Integrated server @ ","Встроенный сервер @ ").replace(" ms ticks"," мс/тик");
        if(raw.startsWith("Debug charts: ")||raw.startsWith("["))return raw
                .replace("Debug charts: ","Графики: ").replace("Profiler ","Профилировщик ")
                .replace("Bandwidth + Ping","Трафик + пинг").replace("Ping ","Пинг ").replace("Lightmap ","Освещение ")
                .replace(" visible"," вкл.").replace(" hidden"," выкл.");
        if(raw.startsWith("To edit: press "))return "Настроить: "+raw.substring("To edit: press ".length());
        String[][] labels={{"Mem: ","Память: "},{"Allocation rate: ","Выделение: "},{"Allocated: ","Зарезервировано: "},
                {"Display: ","Экран: "},{"Window: ","Окно: "},{"Block: ","Блок: "},{"Chunk: ","Чанк: "},
                {"Facing: ","Направление: "},{"Biome: ","Биом: "},{"Local Difficulty: ","Сложность: "}};
        for(var label:labels)if(raw.startsWith(label[0]))return label[1]+raw.substring(label[0].length());
        return raw;
    }
}
