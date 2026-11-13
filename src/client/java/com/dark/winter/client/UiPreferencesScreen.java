// Edits the persisted presentation settings through native focusable controls.
package com.dark.winter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** All controls map to real persisted presentation settings; no placeholder sliders. */
public final class UiPreferencesScreen extends Screen {
    private static final int ROWS = 8;
    private static final String CHUNK_PRELOAD = "menu.darkwinter.additional_chunk_preload";
    private static final String CREATIVE_ITEM_HIGHLIGHT = "menu.darkwinter.creative_item_highlight";
    private final Screen parent;
    public UiPreferencesScreen(Screen parent){super(Component.translatable("menu.darkwinter.interface"));this.parent=parent;}
    public Screen parentScreen(){return parent;}
    private Component mode(){return Component.translatable("menu.darkwinter.motion."+UiPreferences.motion().name().toLowerCase(java.util.Locale.ROOT));}
    private Component pause(){return Component.translatable("menu.darkwinter.pause."+UiPreferences.pauseBackdrop().name().toLowerCase(java.util.Locale.ROOT));}
    private Component strength(){return Component.translatable("menu.darkwinter.fx.strength",Math.round(UiPreferences.strength()*100));}
    private Component mist(){return Component.translatable("menu.darkwinter.fx.mist."+(UiPreferences.mist()?"on":"off"));}
    private int left(){return SettingsNavigation.contentLeft(this);}
    private int top(){return Math.clamp(height-32-20*ROWS,66,72);}
    private Component toggleLabel(String key,boolean enabled){
        return Component.translatable("options.generic_value",Component.translatable(key),
                Component.translatable(enabled?"options.on":"options.off"));
    }
    private Component houseMovement(){return toggleLabel("menu.darkwinter.creative_house_movement",UiPreferences.creativeHouseMovement());}
    private Component houseAnimation(){return toggleLabel("menu.darkwinter.creative_house_animation",UiPreferences.creativeHouseAnimation());}
    private Component chunkPreload(){return toggleLabel(CHUNK_PRELOAD,UiPreferences.additionalChunkPreload());}
    private Component creativeItemHighlight(){return toggleLabel(CREATIVE_ITEM_HIGHLIGHT,UiPreferences.creativeItemHighlight());}
    @Override protected void init() {
        int x=left(),y=top(),w=SettingsNavigation.contentWidth(this),step=Math.clamp((height-y-44)/ROWS,10,30),buttonHeight=Math.min(22,step-2);
        addRenderableWidget(Button.builder(mode(),b->{
            UiPreferences.set(switch(UiPreferences.motion()){case FULL->UiPreferences.Motion.REDUCED;case REDUCED->UiPreferences.Motion.OFF;case OFF->UiPreferences.Motion.FULL;});b.setMessage(mode());
        }).bounds(x+8,y,w-16,buttonHeight).tooltip(Tooltip.create(Component.translatable("menu.darkwinter.motion.hint"))).build());
        addRenderableWidget(Button.builder(strength(),b->{
            float s=UiPreferences.strength();UiPreferences.setStrength(s<.65f?.75f:s<.9f?1f:s<1.1f?1.25f:.5f);b.setMessage(strength());
        }).bounds(x+8,y+step,w-16,buttonHeight).build());
        addRenderableWidget(Button.builder(mist(),b->{UiPreferences.setMist(!UiPreferences.mist());b.setMessage(mist());}).bounds(x+8,y+step*2,w-16,buttonHeight).build());
        addRenderableWidget(Button.builder(pause(),b->{UiPreferences.setPause(UiPreferences.pauseBackdrop()==UiPreferences.PauseBackdrop.WORLD?UiPreferences.PauseBackdrop.SHELTER:UiPreferences.PauseBackdrop.WORLD);b.setMessage(pause());
        }).bounds(x+8,y+step*3,w-16,buttonHeight).tooltip(Tooltip.create(Component.translatable("menu.darkwinter.pause.hint"))).build());
        addRenderableWidget(Button.builder(houseMovement(),b->{
            UiPreferences.setCreativeHouseMovement(!UiPreferences.creativeHouseMovement());b.setMessage(houseMovement());
        }).bounds(x+8,y+step*4,w-16,buttonHeight).tooltip(Tooltip.create(Component.translatable("menu.darkwinter.creative_house_movement"))).build());
        addRenderableWidget(Button.builder(houseAnimation(),b->{
            UiPreferences.setCreativeHouseAnimation(!UiPreferences.creativeHouseAnimation());b.setMessage(houseAnimation());
        }).bounds(x+8,y+step*5,w-16,buttonHeight).tooltip(Tooltip.create(Component.translatable("menu.darkwinter.creative_house_animation"))).build());
        addRenderableWidget(Button.builder(chunkPreload(),b->{
            UiPreferences.setAdditionalChunkPreload(!UiPreferences.additionalChunkPreload());b.setMessage(chunkPreload());
        }).bounds(x+8,y+step*6,w-16,buttonHeight).tooltip(Tooltip.create(Component.translatable(CHUNK_PRELOAD+".hint"))).build());
        addRenderableWidget(Button.builder(creativeItemHighlight(),b->{
            UiPreferences.setCreativeItemHighlight(!UiPreferences.creativeItemHighlight());b.setMessage(creativeItemHighlight());
        }).bounds(x+8,y+step*7,w-16,buttonHeight).tooltip(Tooltip.create(Component.translatable(CREATIVE_ITEM_HIGHLIGHT+".hint"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),b->onClose()).bounds(width/2-65,height-28,130,20).build());
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float dt){
        if(minecraft.level!=null)MenuStyle.worldBackdrop(g,width,height);
        else {MenuStyle.landscape(g,width,height);g.fill(0,0,width,height,0x4A040C15);}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        super.extractRenderState(g,mx,my,dt);
        int x=left(),w=SettingsNavigation.contentWidth(this),y=top();
        if(height>=375) HudStyle.paragraph(g,Component.translatable("menu.darkwinter.fx.note"),x+8,y+30*(ROWS-1)+34,w-16,.8f,HudStyle.MUTED,3);
    }
}
