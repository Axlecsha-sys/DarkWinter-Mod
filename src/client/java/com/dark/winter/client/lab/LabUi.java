// Builds the laboratory UI tree and dispatches bounded native GUI events.
package com.dark.winter.client.lab;

import com.dark.winter.client.mixin.client.LabContainerAccess;
import com.dark.winter.client.mixin.client.LabEditAccess;
import com.dark.winter.client.mixin.client.LabKeyAccess;
import com.dark.winter.client.mixin.client.LabWorldEntryAccess;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.Slot;
import java.util.*;

/** Client-thread UI tree + bounded native GUI events. Never sends OS input. */
public final class LabUi {
    private static Screen previousScreen;
    private static String fingerprint = "";
    private static long revision = 1;
    private static final Map<String,Node> nodes = new LinkedHashMap<>();
    private static Screen pointerScreen;
    private static double pointerX, pointerY;
    private static boolean pointerValid;
    private record Rect(int x,int y,int w,int h) {
        boolean visible() { return w > 0 && h > 0; }
        Rect clip(Rect other) { int l=Math.max(x,other.x),t=Math.max(y,other.y);return new Rect(l,t,Math.max(0,Math.min(x+w,other.x+other.w)-l),Math.max(0,Math.min(y+h,other.y+other.h)-t)); }
    }
    private record Node(GuiEventListener target, Rect rect, JsonObject data) { }
    private LabUi() { }
    public static boolean hasPointer(Minecraft mc) { return pointerValid && pointerScreen == mc.gui.screen() && pointerScreen != null; }
    public static double pointerX() { return pointerX; }
    public static double pointerY() { return pointerY; }
    public static void resetPointer() { pointerValid=false; pointerScreen=null; }
    public static String translation(Component label) { return label.getContents() instanceof TranslatableContents t ? t.getKey() : ""; }

    public static boolean busy(Minecraft mc) {
        String name=mc.gui.screen()==null?"":mc.gui.screen().getClass().getSimpleName();
        return mc.gui.overlay()!=null || name.contains("Loading") || name.contains("Progress") || name.contains("Receiving")
            || name.equals("GenericWaitingScreen") || name.equals("GenericMessageScreen")
            || mc.level!=null && com.dark.winter.client.loading.WorldPreloadClient.active();
    }
    public static JsonObject snapshot(Minecraft mc) {
        Screen screen=mc.gui.screen();
        nodes.clear();
        JsonArray elements=new JsonArray();
        Rect viewport=new Rect(0,0,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
        Set<GuiEventListener> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        if(screen!=null) {
            for(GuiEventListener child:screen.children()) visit(child,viewport,elements,seen,0);
            if(screen instanceof AbstractContainerScreen<?> container) {
                LabContainerAccess origin=(LabContainerAccess)container;
                for(Slot slot:container.getMenu().slots) {
                    Rect r=new Rect(origin.darkwinter$left()+slot.x,origin.darkwinter$top()+slot.y,16,16).clip(viewport);
                    JsonObject item=base("slot",r,slot.isActive(),r.visible());
                    item.addProperty("label",slot.hasItem()?slot.getItem().getHoverName().getString():"Пустой слот " + slot.index);
                    item.addProperty("slot",slot.index); item.addProperty("count",slot.getItem().getCount());
                    item.addProperty("effect","Обычное взаимодействие со слотом; LEFT/RIGHT и modifiers проходят штатную серверную проверку.");
                    add(null,r,item,elements);
                }
            }
            if(screen instanceof LabUiProvider provider) for(var region:provider.darkwinter$labRegions()) {
                Rect r=new Rect(region.x(),region.y(),region.width(),region.height()).clip(viewport);
                JsonObject item=base("custom_region",r,true,r.visible());
                item.addProperty("semantic_id",region.id()); item.addProperty("label",region.label());
                item.addProperty("effect",region.effect()); item.addProperty("selected",region.selected());
                add(null,r,item,elements);
            }
        }
        // Changes to screen, widget identities, bounds, enabled state or values invalidate all old IDs.
        String next=System.identityHashCode(screen)+":"+viewport+":"+elements;
        if(previousScreen!=screen || !next.equals(fingerprint)) { revision++; previousScreen=screen; fingerprint=next; }
        JsonObject out=new JsonObject(); out.addProperty("revision",revision);
        out.addProperty("screen",screen==null?"":screen.getClass().getSimpleName());
        out.addProperty("title",screen==null?"":screen.getTitle().getString());
        out.addProperty("narration",screen==null?"":screen.getNarrationMessage().getString());
        if(screen instanceof LabUiProvider provider) {
            JsonObject extra=new JsonObject();provider.darkwinter$labState().forEach(extra::addProperty);out.add("screen_state",extra);
        }
        out.addProperty("purpose",LabHelp.screen(screen==null?"":screen.getClass().getSimpleName()));
        out.addProperty("gui_width",viewport.w);out.addProperty("gui_height",viewport.h);
        out.addProperty("overlay",mc.gui.overlay()==null?"":mc.gui.overlay().getClass().getSimpleName());
        out.addProperty("busy",busy(mc));
        out.addProperty("truncated",nodes.size()>=400);out.add("elements",elements);
        out.addProperty("coordinates","GUI units, not screenshot pixels. Refresh snapshot after an action; never reuse stale revision.");
        return out;
    }
    private static JsonObject base(String type,Rect rect,boolean active,boolean visible) {
        JsonObject item=new JsonObject();item.addProperty("type",type); item.addProperty("active",active);item.addProperty("visible",visible);
        item.addProperty("x",rect.x);item.addProperty("y",rect.y);item.addProperty("width",rect.w);item.addProperty("height",rect.h);
        return item;
    }
    private static void add(GuiEventListener target,Rect r,JsonObject item,JsonArray output) {
        if(nodes.size()>=400)return;
        String id="e"+nodes.size();item.addProperty("id",id);
        // Identity is used only in the revision fingerprint, never as an invocation target from a request.
        item.addProperty("identity",target==null?0:System.identityHashCode(target));
        nodes.put(id,new Node(target,r,item)); output.add(item);
    }
    private static void visit(GuiEventListener child,Rect clip,JsonArray output,Set<GuiEventListener> seen,int depth) {
        if(depth>12 || nodes.size()>=400 || !seen.add(child))return;
        var bounds=child.getRectangle();
        Rect raw=new Rect(bounds.left(),bounds.top(),bounds.width(),bounds.height());
        Rect r=raw.clip(clip);
        boolean active=!(child instanceof AbstractWidget w)||w.active;
        boolean visible=(!(child instanceof AbstractWidget w)||w.visible)&&r.visible();
        JsonObject item=base(child.getClass().getSimpleName(),r,active,visible);
        String label=child instanceof AbstractWidget w?w.getMessage().getString():child.getClass().getSimpleName();
        String key=child instanceof AbstractWidget w?translation(w.getMessage()):"";
        item.addProperty("label",label);item.addProperty("translation_key",key);item.addProperty("focused",child.isFocused());
        item.addProperty("effect",LabHelp.effect(key,label,Minecraft.getInstance().gui.screen().getClass().getSimpleName()));
        if(child instanceof EditBox field) {
            item.addProperty("value",field.getValue());item.addProperty("editable",((LabEditAccess)field).darkwinter$editable());
            item.addProperty("max_length",((LabEditAccess)field).darkwinter$maxLength());
        }
        if(child instanceof LabWorldEntryAccess entry) {
            var summary=entry.darkwinter$summary();
            item.addProperty("label",summary.getLevelName());item.addProperty("world_id",summary.getLevelId());
            item.addProperty("effect","Выбирает сохранение; double_click открывает этот мир через штатные проверки Minecraft.");
        }
        add(child,r,item,output);
        if(child instanceof ContainerEventHandler container && (!(child instanceof AbstractWidget widget) || widget.visible)) {
            Rect nextClip=child instanceof AbstractSelectionList<?>?r:clip;
            for(var nested:container.children()) visit(nested,nextClip,output,seen,depth+1);
        }
    }

    public static JsonArray controls() {
        JsonArray result=new JsonArray();
        LabKeyAccess.darkwinter$all().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            var mapping=entry.getValue();JsonObject item=new JsonObject();
            String label=Component.translatable(entry.getKey()).getString();
            item.addProperty("id",entry.getKey()); item.addProperty("label",label);
            item.addProperty("binding",mapping.saveString());item.addProperty("bound",!mapping.isUnbound());
            item.addProperty("effect",LabHelp.effect(entry.getKey(),label)); result.add(item);
        });
        return result;
    }
    public static boolean knownKey(String key) { return LabLease.KEYS.contains(key)||LabKeyAccess.darkwinter$all().containsKey(key); }
    private static int sdlModifiers(int flags) {
        return ((flags&1)!=0?0x0001:0)|((flags&2)!=0?0x0040:0)|((flags&4)!=0?0x0100:0)|((flags&8)!=0?0x0400:0);
    }
    private static MouseButtonEvent mouse(double x,double y,int button,int modifiers) { return new MouseButtonEvent(x,y,new MouseButtonInfo(switch(button){case 0->1;case 1->3;default->2;},sdlModifiers(modifiers))); }
    private static void move(Screen screen,double x,double y) {
        pointerX=x;pointerY=y;pointerValid=true;pointerScreen=screen;
        screen.mouseMoved(x,y);screen.afterMouseMove();
    }
    public static JsonObject act(Minecraft mc,JsonObject request) {
        JsonObject before=snapshot(mc);
        if(!request.has("revision") || request.get("revision").getAsLong()!=revision)throw new IllegalStateException("STALE_UI: get a fresh dw_ui_snapshot before acting");
        if(mc.gui.overlay()!=null)throw new IllegalStateException("BUSY: an overlay owns input; wait for a fresh snapshot");
        Screen screen=mc.gui.screen();
        String operation=request.get("operation").getAsString();
        boolean accepted=true;
        if(operation.equals("pause")) {
            if(screen!=null || mc.level==null)throw new IllegalStateException("Pause requires the in-game scene");
            mc.pauseGame(false);
        } else {
            if(screen==null)throw new IllegalStateException("No screen is open; use gameplay bindings or pause");
            Node node=request.has("element_id")?nodes.get(request.get("element_id").getAsString()):null;
            if(request.has("element_id")&&node==null)throw new IllegalArgumentException("Unknown UI element");
            if(node!=null && (!node.data.get("visible").getAsBoolean()||!node.data.get("active").getAsBoolean()))
                throw new IllegalStateException("Element is hidden, clipped or disabled");
            double x=node==null?number(request,"x",0):node.rect.x+node.rect.w/2.0;
            double y=node==null?number(request,"y",0):node.rect.y+node.rect.h/2.0;
            int button=integer(request,"button",0,0,2),modifiers=integer(request,"modifiers",0,0,15);
            switch(operation) {
                case "click", "double_click", "hover", "scroll", "drag" -> {
                    if(x<0||y<0||x>=screen.width||y>=screen.height)throw new IllegalArgumentException("Pointer outside GUI bounds");
                    double toX=number(request,"to_x",x),toY=number(request,"to_y",y);
                    double scroll=number(request,"scroll_y",0);
                    if(operation.equals("drag")&&(toX<0||toY<0||toX>=screen.width||toY>=screen.height))throw new IllegalArgumentException("Drag target outside GUI");
                    if(operation.equals("scroll")&&Math.abs(scroll)>20)throw new IllegalArgumentException("Scroll limited to 20 steps");
                    move(screen,x,y);
                    if(operation.equals("click")||operation.equals("double_click")||operation.equals("drag")) {
                        MouseButtonEvent press=mouse(x,y,button,modifiers);
                        accepted=screen.mouseClicked(press,operation.equals("double_click"));
                        try {
                            if(operation.equals("drag")&&mc.gui.screen()==screen) {
                                move(screen,toX,toY);accepted=screen.mouseDragged(mouse(toX,toY,button,modifiers),toX-x,toY-y)||accepted;
                                x=toX;y=toY;
                            }
                        } finally { if(mc.gui.screen()==screen)screen.mouseReleased(mouse(x,y,button,modifiers)); }
                        screen.afterMouseAction();
                    } else if(operation.equals("scroll")) {
                        accepted=screen.mouseScrolled(x,y,0,scroll);screen.afterMouseAction();
                    }
                }
                case "set_text" -> {
                    if(node==null || !(node.target instanceof EditBox field)||!((LabEditAccess)field).darkwinter$editable())
                        throw new IllegalStateException("Choose an editable text field");
                    String text=request.get("text").getAsString();
                    if(text.length()>((LabEditAccess)field).darkwinter$maxLength())throw new IllegalArgumentException("Text exceeds field max_length");
                    field.setValue(text);screen.afterKeyboardAction();
                }
                case "type_text" -> {
                    String text=request.get("text").getAsString();if(text.length()>1000)throw new IllegalArgumentException("Text limited to 1000 characters");
                    if(node!=null) {
                        if(!(node.target instanceof EditBox field)||!((LabEditAccess)field).darkwinter$editable())throw new IllegalStateException("Choose an editable text field");
                        move(screen,x,y);screen.mouseClicked(mouse(x,y,0,0),false);screen.mouseReleased(mouse(x,y,0,0));
                    }
                    for(int point:text.codePoints().toArray())accepted=screen.charTyped(new CharacterEvent(point));
                    screen.afterKeyboardAction();
                }
                case "key" -> {
                    String name=request.get("key").getAsString();
                    if(!name.matches("key\\.keyboard\\.[a-z0-9._-]{1,40}"))throw new IllegalArgumentException("Use a key.keyboard.* name");
                    var key=InputConstants.getKey(name);
                    if(key.equals(InputConstants.UNKNOWN))throw new IllegalArgumentException("Unknown keyboard key");
                    int mods=sdlModifiers(modifiers);
                    KeyEvent event=new KeyEvent(key.getValue(),org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(key.getValue(),(short)mods,true),mods);
                    accepted=screen.keyPressed(event);if(mc.gui.screen()==screen)screen.keyReleased(event);screen.afterKeyboardAction();
                }
                case "close" -> screen.onClose();
                default -> throw new IllegalArgumentException("Unknown UI operation");
            }
        }
        revision++;fingerprint="";
        JsonObject out=snapshot(mc);out.addProperty("accepted",accepted);out.addProperty("previous_revision",before.get("revision").getAsLong());
        return out;
    }
    private static double number(JsonObject data,String key,double fallback) {
        double value=data.has(key)?data.get(key).getAsDouble():fallback;
        if(!Double.isFinite(value))throw new IllegalArgumentException("Finite coordinates required");return value;
    }
    private static int integer(JsonObject data,String key,int fallback,int low,int high) {
        double value=number(data,key,fallback);if(value!=Math.rint(value)||value<low||value>high)throw new IllegalArgumentException("Invalid "+key);return(int)value;
    }
}
