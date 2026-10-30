// Edits the persisted presentation settings through native focusable controls.
package com.dark.winter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** All controls map to real persisted presentation settings; no placeholder sliders. */
public final class UiPreferencesScreen extends Screen {
    private final Screen parent;
    public UiPreferencesScreen(Screen parent){super(Component.translatable("menu.darkwinter.interface"));this.parent=parent;}
    public Screen parentScreen(){return parent;}
    private Component mode(){return Component.translatable("menu.darkwinter.motion."+UiPreferences.motion().name().toLowerCase(java.util.Locale.ROOT));}
    private Component pause(){return Component.translatable("menu.darkwinter.pause."+UiPreferences.pauseBackdrop().name().toLowerCase(java.util.Locale.ROOT));}
    private Component strength(){return Component.translatable("menu.darkwinter.fx.strength",Math.round(UiPreferences.strength()*100));}
    private Component mist(){return Component.translatable("menu.darkwinter.fx.mist."+(UiPreferences.mist()?"on":"off"));}
    private int left(){return SettingsNavigation.contentLeft(this);}
    private int top(){return 72;}
    @Override protected void init() {
        int x=left(),y=top(),w=SettingsNavigation.contentWidth(this),step=height<260?26:30;
        addRenderableWidget(Button.builder(mode(),b->{
            UiPreferences.set(switch(UiPreferences.motion()){case FULL->UiPreferences.Motion.REDUCED;case REDUCED->UiPreferences.Motion.OFF;case OFF->UiPreferences.Motion.FULL;});b.setMessage(mode());
        }).bounds(x+8,y,w-16,22).tooltip(Tooltip.create(Component.translatable("menu.darkwinter.motion.hint"))).build());
        addRenderableWidget(Button.builder(strength(),b->{
            float s=UiPreferences.strength();UiPreferences.setStrength(s<.65f?.75f:s<.9f?1f:s<1.1f?1.25f:.5f);b.setMessage(strength());
        }).bounds(x+8,y+step,w-16,22).build());
        addRenderableWidget(Button.builder(mist(),b->{UiPreferences.setMist(!UiPreferences.mist());b.setMessage(mist());}).bounds(x+8,y+step*2,w-16,22).build());
        addRenderableWidget(Button.builder(pause(),b->{UiPreferences.setPause(UiPreferences.pauseBackdrop()==UiPreferences.PauseBackdrop.WORLD?UiPreferences.PauseBackdrop.SHELTER:UiPreferences.PauseBackdrop.WORLD);b.setMessage(pause());
        }).bounds(x+8,y+step*3,w-16,22).tooltip(Tooltip.create(Component.translatable("menu.darkwinter.pause.hint"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),b->onClose()).bounds(width/2-65,height-28,130,20).build());
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float dt){
        if(minecraft.level!=null)MenuStyle.worldBackdrop(g,width,height);
        else {MenuStyle.landscape(g,width,height);g.fill(0,0,width,height,0x4A040C15);}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        super.extractRenderState(g,mx,my,dt);
        int x=left(),w=SettingsNavigation.contentWidth(this),y=top(),step=height<260?26:30;
        if(height>=300) HudStyle.paragraph(g,Component.translatable("menu.darkwinter.fx.note"),x+8,y+step*3+38,w-16,.8f,HudStyle.MUTED,3);
    }
}
