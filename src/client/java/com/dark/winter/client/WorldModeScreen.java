// Selects an explicit mode before creating a fresh world.
package com.dark.winter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** A preflight for new worlds only. Saves/recreation and the vanilla creation form stay intact. */
public final class WorldModeScreen extends Screen {
    private final Runnable returnToParent;
    private boolean story;
    private boolean chooseTesting;
    private TestingWorlds.Template testTemplate=TestingWorlds.Template.ISLAND;
    private static final TestingWorlds.Template[] TEST_TYPES = {TestingWorlds.Template.ISLAND, TestingWorlds.Template.NORMAL, TestingWorlds.Template.FLAT, TestingWorlds.Template.TEST_MAP};
    private static final int[] ISLAND_RADII = {64, 96, 128};
    private int islandSizeIndex = 1;
    private int islandShapeIndex;
    private Difficulty difficulty=Difficulty.NORMAL;
    private Button difficultyButton;

    public WorldModeScreen(Runnable returnToParent) {
        super(text("choose"));
        this.returnToParent=returnToParent;
    }
    private static Component text(String key) { return Component.translatable("menu.darkwinter."+key); }

    @Override protected void init() {
        if(chooseTesting) {
            int w=Math.min(340,width-42),x=(width-w)/2,y=Math.max(42,height/2-100);
            addRenderableWidget(Button.builder(Component.translatable("menu.darkwinter.test.template",
                    Component.translatable("menu.darkwinter.test."+testTemplate.name().toLowerCase(java.util.Locale.ROOT))),
                    b->{int index=java.util.Arrays.asList(TEST_TYPES).indexOf(testTemplate);testTemplate=TEST_TYPES[(index+1)%TEST_TYPES.length];rebuildWidgets();})
                    .bounds(x,y,w,24).build());
            addRenderableWidget(Button.builder(Component.literal("Радиус острова: " + ISLAND_RADII[islandSizeIndex] + " м"),
                    b->{islandSizeIndex=(islandSizeIndex+1)%ISLAND_RADII.length;rebuildWidgets();})
                    .bounds(x,y+30,(w-8)/2,24).build()).active=testTemplate==TestingWorlds.Template.ISLAND;
            addRenderableWidget(Button.builder(Component.literal("Форма: " + new String[]{"Круглая","Овальная","Полумесяц"}[islandShapeIndex]),
                    b->{islandShapeIndex=(islandShapeIndex+1)%3;rebuildWidgets();})
                    .bounds(x+(w+8)/2,y+30,(w-8)/2,24).build()).active=testTemplate==TestingWorlds.Template.ISLAND;
            addRenderableWidget(Button.builder(Component.translatable("menu.darkwinter.test.create"),
                    b->openTesting()).bounds(x,y+70,w,24).build());
        } else if(!story) {
            var c=UiLayout.cards(width,height);
            var a=c.left();var b=c.right();
            int gap=10,three=Math.max(70,Math.min(200,(width-48-2*gap)/3));
            int total=three*3+gap*2,start=(width-total)/2;
            addRenderableWidget(new IllustratedModeButton(start,a.y(),three,a.h(),true,button->{story=true;rebuildWidgets();}));
            addRenderableWidget(new IllustratedModeButton(start+three+gap,a.y(),three,a.h(),false,button->open(false)));
            addRenderableWidget(Button.builder(Component.translatable("menu.darkwinter.test.short"),button->{chooseTesting=true;rebuildWidgets();})
                    .bounds(start+2*(three+gap),a.y(),three,a.h()).build());
        } else {
            var l=UiLayout.story(width,height);var p=l.panel();int x=p.x(),w=p.w();
            difficultyButton=addRenderableWidget(Button.builder(difficultyText(),b->cycleDifficulty(1)).bounds(x+w/2+24,l.difficultyY(),w/2-48,UiLayout.selectorHeight(height)).build());
            addRenderableWidget(Button.builder(Component.literal("‹"),b->cycleDifficulty(-1)).bounds(x+w/2,l.difficultyY(),22,UiLayout.selectorHeight(height)).tooltip(net.minecraft.client.gui.components.Tooltip.create(text("difficulty.previous"))).build());
            addRenderableWidget(Button.builder(Component.literal("›"),b->cycleDifficulty(1)).bounds(x+w-22,l.difficultyY(),22,UiLayout.selectorHeight(height)).tooltip(net.minecraft.client.gui.components.Tooltip.create(text("difficulty.next"))).build());
            addRenderableWidget(new ConfigureButton(x,l.configureY(),w,UiLayout.configureHeight(height),b->{
                open(true);
                if(minecraft.gui.screen() instanceof CreateWorldScreen create)
                    ((MenuTabBar)create.children().stream().filter(MenuTabBar.class::isInstance).findFirst().orElseThrow()).selectTab(1,false);
            }));
            addRenderableWidget(Button.builder(text("continue"),b->open(true)).bounds(x+w/5,l.continueY(),w*3/5,UiLayout.continueHeight(height)).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK,b->onClose()).bounds(width/2-60,height-27,120,20).build());
    }

    private void cycleDifficulty(int step) {
        Difficulty[] values={Difficulty.EASY,Difficulty.NORMAL,Difficulty.HARD};
        int index=difficulty==Difficulty.EASY?0:difficulty==Difficulty.NORMAL?1:2;
        difficulty=values[Math.floorMod(index+step,values.length)];
        difficultyButton.setMessage(difficultyText());
    }

    private Component difficultyText() { return difficulty.getDisplayName(); }

    private void openTesting() {
        TestingWorlds.arm(testTemplate);
        CreateWorldScreen.openFresh(minecraft,()->{TestingWorlds.cancel();minecraft.gui.setScreen(this);});
        if(minecraft.gui.screen() instanceof CreateWorldScreen create) {
            var state=create.getUiState();
            state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            state.setDifficulty(Difficulty.NORMAL);
            state.setGenerateStructures(false);
            state.setBonusChest(false);
            setField(create,"selectWorld.enterName", testTemplate==TestingWorlds.Template.ISLAND ? "DarkWinter — тестовый остров" : "DarkWinter — лаборатория");
            if(testTemplate == TestingWorlds.Template.ISLAND || testTemplate == TestingWorlds.Template.TEST_MAP) {
                var registries=state.getSettings().worldgenLoadContext();
                state.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                        registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.SINGLE_BIOME_SURFACE)));
                int radius=testTemplate==TestingWorlds.Template.TEST_MAP ? 64 : ISLAND_RADII[islandSizeIndex];
                String shape=testTemplate==TestingWorlds.Template.TEST_MAP ? "ROUND" : com.dark.winter.testing.TestingIslandGenerator.SHAPES[islandShapeIndex];
                long seed=state.getSettings().options().seed();
                state.updateDimensions((access,dimensions)->dimensions.replaceOverworldGenerator(access,
                        new com.dark.winter.testing.TestingIslandGenerator(access.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.SNOWY_TAIGA),radius,shape,seed)));
            } else if(testTemplate == TestingWorlds.Template.FLAT) {
                var registries=state.getSettings().worldgenLoadContext();
                state.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                        registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)));

            }
        }
    }

    private void open(boolean northern) {
        TestingWorlds.cancel();
        // Only entry from the world list is intercepted; no pending mode leaks into another world.
        CreateWorldScreen.openFresh(minecraft,()->minecraft.gui.setScreen(this));
        if(northern && minecraft.gui.screen() instanceof CreateWorldScreen create) {
            var state=create.getUiState();
            setField(create,"selectWorld.enterName","DarkWinter");
            state.setDifficulty(difficulty);
            state.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            // These vanilla fields have responders but no model-to-view listener.
            // Use the existing tab/field API so the displayed seed equals the saved seed.
            var tabs=(MenuTabBar)create.children().stream().filter(MenuTabBar.class::isInstance).findFirst().orElseThrow();
            tabs.selectTab(1,false);
            setField(create,"selectWorld.enterSeed","19971008");
            tabs.selectTab(0,false);
            var registries=state.getSettings().worldgenLoadContext();
            state.setWorldType(new WorldCreationUiState.WorldTypeEntry(registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.SINGLE_BIOME_SURFACE)));
            state.updateDimensions((access,dimensions)->dimensions.replaceOverworldGenerator(access,
                    new NoiseBasedChunkGenerator(new FixedBiomeSource(access.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.SNOWY_TAIGA)),
                            access.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD))));
        }
    }

    private static void setField(CreateWorldScreen screen,String key,String value) {
        Component label=Component.translatable(key);
        for(var child:screen.children()) if(child instanceof EditBox field && field.getMessage().equals(label)) {
            field.setValue(value);
            return;
        }
        throw new IllegalStateException("Missing world creation field: "+key);
    }

    @Override public void onClose() {
        if(story || chooseTesting) { story=false;chooseTesting=false;TestingWorlds.cancel();rebuildWidgets(); } else returnToParent.run();
    }

    @Override public void extractBackground(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick) {
        MenuStyle.landscape(g,width,height);
    }


    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick) {
        if(chooseTesting) {
            HudStyle.logo(g,(width-320)/2,Math.max(10,height/2-148),1.2f,2.2f,320,HudStyle.INK);
            HudStyle.centered(g,Component.translatable("menu.darkwinter.test.note"),width/2,Math.max(42,height/2-124),.85f,HudStyle.MUTED);
        } else if(story) {
            var l=UiLayout.story(width,height);var p=l.panel();int x=p.x(),y=p.y(),w=p.w(),image=l.imageHeight();
            HudStyle.logo(g,x,Math.max(2,y-35),1.28f,2.4f,w,HudStyle.INK);
            HudStyle.text(g,text("story"),x,y-13,.8f,HudStyle.MUTED);
            HudStyle.panel(g,x-1,y-2,w+2,image+4);
            int iw=Math.min(120,w/3);
            UiArt.cover(g,UiArt.STORY,x+2,y+1,iw,image-2,.48f,.58f,0xFFFFFFFF);
            int tx=x+iw+12,space=w-iw-22;
            HudStyle.fit(g,HudStyle.strong(text("region")),tx,y+4,space,.96f,HudStyle.INK);
            if(image>=44) HudStyle.paragraph(g,text("story.summary"),tx,y+21,space,.80f,HudStyle.MUTED,Math.max(1,(image-24)/10));
            HudStyle.panel(g,x,l.difficultyY(),w,UiLayout.selectorHeight(height));
            HudStyle.text(g,text("difficulty.label"),x+10,l.difficultyY()+7,.8f,HudStyle.MUTED);
        } else {
            var c=UiLayout.cards(width,height);
            HudStyle.logo(g,c.left().x(),c.titleY(),1.4f,2.2f,c.left().w()+c.right().w(),HudStyle.INK);
            HudStyle.text(g,title,c.left().x(),c.titleY()+23,.84f,HudStyle.MUTED);
        }
        super.extractRenderState(g,mouseX,mouseY,partialTick);
    }
    /** Visual caption, real original configure action; no extra invisible widget. */
    private static final class ConfigureButton extends Button {
        private float glow;
        private long previous;
        private ConfigureButton(int x,int y,int w,int h,OnPress action) {
            super(x,y,w,h,text("configure"),action,DEFAULT_NARRATION);
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("configure.hint")));
        }
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
            long now=System.nanoTime();
            float elapsed=previous==0?0:Math.min(.1f,(now-previous)/1_000_000_000f);previous=now;
            float target=active&&isHoveredOrFocused()?1:0;
            glow+=(target-glow)*(UiPreferences.animated()?1-(float)Math.exp(-elapsed/.16f):1);
            MenuStyle.button(g,this,alpha,glow);
            int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            if(w>=300&&h>=30) {
                HudStyle.fit(g,getMessage(),x+10,y+(h-10)/2f,w*2/5-18,.96f,HudStyle.INK);
                g.fill(x+w*2/5,y+7,x+w*2/5+1,y+h-7,0x50537384);
                HudStyle.paragraph(g,text("configure.hint"),x+w*2/5+12,y+7,w*3/5-23,.70f,HudStyle.MUTED,2);
            } else HudStyle.centered(g,getMessage(),x+w/2,y+(h-10)/2,.92f,HudStyle.INK);
        }
    }

}
